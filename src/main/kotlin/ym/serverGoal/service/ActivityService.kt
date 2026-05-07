package ym.serverGoal.service

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.Bukkit
import ym.serverGoal.config.ConfigService
import ym.serverGoal.config.MessageService
import ym.serverGoal.model.ActiveActivity
import ym.serverGoal.model.ActivityTemplate
import ym.serverGoal.model.ClaimResult
import ym.serverGoal.model.CollectionItem
import ym.serverGoal.model.ContributionRewardDefinition
import ym.serverGoal.model.RewardOutboxEntry
import ym.serverGoal.model.StageDefinition
import ym.serverGoal.model.SubmissionResult
import ym.serverGoal.platform.PlatformScheduler
import ym.serverGoal.storage.ActivityStorage
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

class ActivityService(
    private val config: ConfigService,
    private val messages: MessageService,
    private val storage: ActivityStorage,
    private val matcher: ItemMatcher,
    private val rewards: RewardService,
    private val rewardAudit: RewardAuditService,
    private val history: ActivityHistoryService,
    private val scheduler: PlatformScheduler
) {
    private var current: ActiveActivity? = null
    private val pendingSubmitters = linkedSetOf<UUID>()
    private val lastSubmissionAt = linkedMapOf<UUID, Long>()
    private var announcedActivityKey: String? = null
    private var announcedCompletionKey: String? = null
    private val lastProgressNoticeByActivity = linkedMapOf<String, Long>()
    private val outboxRunning = AtomicBoolean(false)

    @Volatile
    private var shuttingDown: Boolean = false

    private data class ContributionPayout(
        val uuid: UUID,
        val playerName: String,
        val contribution: Int,
        val amount: Int
    )

    private data class ContributionRewardBatch(
        val outboxEntry: RewardOutboxEntry
    )

    private data class SubmissionProposal(
        val requestedByItem: Map<String, Int>,
        val totalRequested: Int
    )

    private data class DeductionResult(
        val success: Boolean,
        val totalDeducted: Int,
        val removedItems: List<ItemStack> = emptyList()
    )

    fun loadAsync(): CompletableFuture<ActiveActivity?> {
        return storage.loadAsync().thenApply { loaded ->
            synchronized(this) {
                current = loaded
                rememberLoadedAnnouncementsLocked(loaded)
                loaded
            }
        }
    }

    fun saveAsync(): CompletableFuture<ActiveActivity?> {
        val snapshot = synchronized(this) {
            val source = current ?: return CompletableFuture.completedFuture(null)
            source.revision += 1
            source.updatedAt = System.currentTimeMillis()
            source.updatedBy = config.settings.serverId
            cloneActivity(source)
        }
        return if (snapshot == null) {
            CompletableFuture.completedFuture(null)
        } else {
            storage.saveAsync(snapshot).thenApply { merged -> applyMergedState(merged) }
        }
    }

    fun synchronizeAsync(): CompletableFuture<ActiveActivity?> {
        val snapshot = synchronized(this) { current?.let { cloneActivity(it) } }
        return if (snapshot == null) {
            CompletableFuture.completedFuture(null)
        } else {
            storage.syncAsync(snapshot).thenApply { merged -> applyMergedState(merged) }
        }
    }

    fun drainRewardOutboxAsync(maxEntries: Int = 4): CompletableFuture<Unit> {
        if (!outboxRunning.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(Unit)
        }
        val future = CompletableFuture<Unit>()
        drainRewardOutboxLoop(maxEntries.coerceAtLeast(1), future)
        return future
    }

    fun beginShutdown() {
        shuttingDown = true
    }

    fun shutdownAsync(): CompletableFuture<Unit> {
        shuttingDown = true
        return saveAsync().handle { _, _ -> }
    }

    @Synchronized
    fun activeActivity(): ActiveActivity? = current

    @Synchronized
    fun currentTemplate(): ActivityTemplate? {
        val active = current ?: return null
        return config.template(active.templateId)
    }

    @Synchronized
    fun displayTemplate(): ActivityTemplate? {
        return currentTemplate()
            ?: config.template(config.settings.defaultTemplate)
            ?: config.allTemplates().firstOrNull()
    }

    @Synchronized
    fun displayTemplate(templateId: String?): ActivityTemplate? {
        if (templateId.isNullOrBlank()) {
            return displayTemplate()
        }
        return config.template(templateId) ?: displayTemplate()
    }

    @Synchronized
    fun startTemplate(templateId: String, minutesOverride: Int? = null): Boolean {
        if (shuttingDown) {
            return false
        }
        val template = config.template(templateId) ?: return false
        val existing = current
        if (existing != null && existing.active) {
            return false
        }
        val now = System.currentTimeMillis()
        val durationMinutes = minutesOverride?.coerceAtLeast(1) ?: template.durationMinutes
        val localOnlinePlayers = Bukkit.getOnlinePlayers().size.coerceAtLeast(1)
        val onlinePlayers = if (config.settings.databaseStorageEnabled) {
            storage.networkOnlinePlayers(localOnlinePlayers)
        } else {
            localOnlinePlayers
        }.coerceAtLeast(1)
        val multiplier = targetMultiplier(template, onlinePlayers)
        val effectiveItemTargets = template.acceptedItems.associate { item ->
            item.key to scaledTarget(item.targetAmount, multiplier)
        }.toMutableMap()
        val effectiveTargetTotal = scaledTarget(template.targetTotal, multiplier)
        val active = ActiveActivity(
            templateId = template.id,
            displayName = template.displayName,
            startedAt = now,
            endsAt = now + durationMinutes * 60_000L,
            active = true,
            completed = false,
            effectiveTargetTotal = effectiveTargetTotal,
            effectiveItemTargets = effectiveItemTargets,
            effectiveStageThresholds = linkedMapOf(),
            dynamicTargetPlayers = onlinePlayers,
            dynamicTargetMultiplier = multiplier,
            totalCollected = 0,
            unlockedStage = 0
        )
        current = active
        persistCurrentLocked(active)
        announceActivityStartedLocked(active, template)
        return true
    }

    @Synchronized
    fun startDefaultTemplate(minutesOverride: Int? = null): Boolean {
        return startTemplate(config.settings.defaultTemplate, minutesOverride)
    }

    @Synchronized
    fun startRotatedTemplate(minutesOverride: Int? = null): String? {
        if (current?.active == true) {
            return null
        }
        val candidates = config.settings.rotation.pool
            .ifEmpty { config.templateIds() }
            .filter { config.template(it) != null }
            .distinct()
        if (candidates.isEmpty()) {
            return null
        }
        val intervalMillis = config.settings.rotation.intervalDays.coerceAtLeast(1) * 86_400_000L
        val bucket = System.currentTimeMillis() / intervalMillis
        val index = Math.floorMod(bucket.toInt(), candidates.size)
        val selected = candidates[index]
        return if (startTemplate(selected, minutesOverride)) selected else null
    }

    @Synchronized
    fun autoStartRotationIfDue(): String? {
        val rotation = config.settings.rotation
        if (!rotation.enabled || !rotation.autoStart || current?.active == true) {
            return null
        }
        val latest = current
        val intervalMillis = rotation.intervalDays.coerceAtLeast(1) * 86_400_000L
        if (latest != null && System.currentTimeMillis() - latest.startedAt < intervalMillis) {
            return null
        }
        return startRotatedTemplate()
    }

    @Synchronized
    fun endActivity(completed: Boolean): Boolean {
        val active = current ?: return false
        if (!active.active) {
            return false
        }
        finishLocked(active, completed)
        return true
    }

    @Synchronized
    fun checkTimer() {
        val active = current ?: return
        if (active.active) {
            config.template(active.templateId)?.let { template ->
                announcePeriodicProgressLocked(active, template)
            }
        }
        if (active.active && System.currentTimeMillis() >= active.endsAt) {
            finishLocked(active, active.completed || isFinalReachedLocked(active))
        }
    }

    fun submitInventoryAsync(player: Player, itemKey: String? = null): CompletableFuture<SubmissionResult> {
        if (!config.settings.databaseStorageEnabled) {
            return CompletableFuture.completedFuture(submitInventory(player, itemKey))
        }
        if (shuttingDown) {
            return CompletableFuture.completedFuture(SubmissionResult(false, messageKey = "service-shutting-down"))
        }

        val (activeSnapshot, templateSnapshot, proposal) = synchronized(this) {
            val active = current ?: return CompletableFuture.completedFuture(SubmissionResult(false, messageKey = "no-activity"))
            val template = config.template(active.templateId)
                ?: return CompletableFuture.completedFuture(SubmissionResult(false, messageKey = "template-missing"))
            if (!active.active) {
                return CompletableFuture.completedFuture(SubmissionResult(false, messageKey = "activity-not-running"))
            }
            if (System.currentTimeMillis() >= active.endsAt) {
                finishLocked(active, active.completed || isFinalReachedLocked(active))
                return CompletableFuture.completedFuture(SubmissionResult(false, messageKey = "activity-not-running"))
            }
            if (config.settings.adminTestMode && !player.hasPermission("servergoal.admin.test")) {
                return CompletableFuture.completedFuture(SubmissionResult(false, messageKey = "admin-test-mode"))
            }
            if (!pendingSubmitters.add(player.uniqueId)) {
                return CompletableFuture.completedFuture(SubmissionResult(false, messageKey = "submit-pending"))
            }
            val cooldownResult = checkSubmitCooldownLocked(player.uniqueId)
            if (cooldownResult != null) {
                pendingSubmitters.remove(player.uniqueId)
                return CompletableFuture.completedFuture(cooldownResult)
            }
            val proposal = collectSubmissionProposal(template, active, player, itemKey)
            if (proposal.totalRequested <= 0) {
                pendingSubmitters.remove(player.uniqueId)
                return CompletableFuture.completedFuture(SubmissionResult(false, messageKey = "submit-no-items"))
            }
            Triple(cloneActivity(active) ?: active, template, proposal)
        }

        val future = CompletableFuture<SubmissionResult>()
        storage.reserveSubmissionAsync(
            activeSnapshot,
            templateSnapshot,
            player.uniqueId,
            player.name,
            proposal.requestedByItem
        ).whenComplete { reservation, reserveFailure ->
            if (reserveFailure != null) {
                if (config.settings.storage.fallbackYamlOnDatabaseFailure) {
                    scheduler.runForPlayer(player) {
                        finishPendingSubmission(player.uniqueId)
                        future.complete(submitInventory(player, itemKey))
                    }
                } else {
                    finishPendingSubmission(player.uniqueId)
                    future.complete(
                        SubmissionResult(false, messageKey = "submit-sync-failed", placeholders = mapOf("error" to (reserveFailure.message ?: reserveFailure.javaClass.name)))
                    )
                }
                return@whenComplete
            }
            if (reservation == null || reservation.totalAccepted <= 0) {
                finishPendingSubmission(player.uniqueId)
                future.complete(SubmissionResult(false, messageKey = "submit-no-slots"))
                return@whenComplete
            }
            scheduler.runForPlayer(player) {
                continueReservedSubmission(player, templateSnapshot, reservation, future)
            }
        }
        return future
    }

    @Synchronized
    fun submitInventory(player: Player, itemKey: String? = null): SubmissionResult {
        if (shuttingDown) {
            return SubmissionResult(false, messageKey = "service-shutting-down")
        }
        val active = current ?: return SubmissionResult(false, messageKey = "no-activity")
        val template = config.template(active.templateId) ?: return SubmissionResult(false, messageKey = "template-missing")
        if (config.settings.adminTestMode && !player.hasPermission("servergoal.admin.test")) {
            return SubmissionResult(false, messageKey = "admin-test-mode")
        }
        if (!active.active) {
            return SubmissionResult(false, messageKey = "activity-not-running")
        }
        if (System.currentTimeMillis() >= active.endsAt) {
            finishLocked(active, active.completed || isFinalReachedLocked(active))
            return SubmissionResult(false, messageKey = "activity-not-running")
        }
        if (template.acceptedItems.isEmpty()) {
            return SubmissionResult(false, messageKey = "no-accepted-items")
        }
        checkSubmitCooldownLocked(player.uniqueId)?.let { return it }

        var submitted = 0
        val submittedByItem = linkedMapOf<String, Int>()
        val contents = player.inventory.storageContents
        val maxSubmit = config.settings.submission.maxItemsPerSubmit.coerceAtLeast(1)
        for (index in contents.indices) {
            val stack = contents[index] ?: continue
            if (stack.type.isAir || stack.amount <= 0) {
                continue
            }
            val matched = findAcceptableItem(template, active, stack, itemKey = itemKey) ?: continue
            val itemRemaining = if (config.settings.protectionEnabled) {
                (itemTarget(active, matched) - (active.collectedByItem[matched.key] ?: 0)).coerceAtLeast(0)
            } else {
                stack.amount
            }
            val remainingLimit = (maxSubmit - submitted).coerceAtLeast(0)
            val take = min(stack.amount, min(itemRemaining, remainingLimit))
            if (take <= 0) {
                continue
            }

            submitted += take
            submittedByItem[matched.key] = (submittedByItem[matched.key] ?: 0) + take
            if (stack.amount == take) {
                contents[index] = null
            } else {
                stack.amount = stack.amount - take
                contents[index] = stack
            }
            if (submitted >= maxSubmit) {
                break
            }
        }

        if (submitted <= 0) {
            return SubmissionResult(false, messageKey = "submit-no-items")
        }
        markSubmissionAcceptedLocked(player.uniqueId)

        player.inventory.storageContents = contents
        player.updateInventory()
        active.totalCollected += submitted
        active.playerNames[player.uniqueId] = player.name
        active.contributions[player.uniqueId] = (active.contributions[player.uniqueId] ?: 0) + submitted
        if (config.settings.databaseStorageEnabled) {
            val serverContributions = active.serverContributions.getOrPut(config.settings.serverId) { linkedMapOf() }
            serverContributions[player.uniqueId] = (serverContributions[player.uniqueId] ?: 0) + submitted
        }
        for ((key, amount) in submittedByItem) {
            active.collectedByItem[key] = (active.collectedByItem[key] ?: 0) + amount
            if (config.settings.databaseStorageEnabled) {
                val serverItems = active.serverCollectedByItem.getOrPut(config.settings.serverId) { linkedMapOf() }
                serverItems[key] = (serverItems[key] ?: 0) + amount
            }
        }

        if (config.settings.saveOnSubmit) {
            persistCurrentLocked(active)
        }

        if (config.settings.endWhenFinalStageComplete && isFinalReachedLocked(active)) {
            finishLocked(active, true)
        }

        return SubmissionResult(
            success = true,
            amount = submitted,
            messageKey = "submit-success",
            placeholders = mapOf(
                "amount" to submitted.toString(),
                "total" to active.totalCollected.toString(),
                "target" to targetTotal(active, template).toString()
            )
        )
    }

    @Synchronized
    fun claimStageReward(player: Player, stageIndex: Int): ClaimResult {
        val active = current ?: return ClaimResult(false, "no-activity")
        val template = config.template(active.templateId) ?: return ClaimResult(false, "template-missing")
        val stage = template.stages.firstOrNull { it.index == stageIndex } ?: return ClaimResult(false, "reward-missing")
        if (active.unlockedStage < stage.index) {
            return ClaimResult(false, "stage-locked", placeholders = mapOf("stage" to stage.index.toString()))
        }
        val contribution = active.contributionOf(player.uniqueId)
        if (contribution < stage.minContribution) {
            return ClaimResult(
                false,
                "reward-contribution-too-low",
                placeholders = mapOf(
                    "need" to stage.minContribution.toString(),
                    "contribution" to contribution.toString()
                )
            )
        }
        val claimed = active.claimedStageRewards.getOrPut(player.uniqueId) { linkedSetOf() }
        if (!claimed.add(stage.index)) {
            return ClaimResult(false, "reward-already-claimed")
        }
        persistCurrentLocked(active)
        return ClaimResult(
            true,
            "reward-claimed",
            commands = stage.commands,
            placeholders = commonRewardPlaceholders(player.uniqueId, stage.index.toString(), contribution)
        )
    }

    @Synchronized
    fun claimPersonalReward(player: Player, rewardId: String): ClaimResult {
        val active = current ?: return ClaimResult(false, "no-activity")
        val template = config.template(active.templateId) ?: return ClaimResult(false, "template-missing")
        val reward = template.personalRewards.firstOrNull { it.id.equals(rewardId, ignoreCase = true) }
            ?: return ClaimResult(false, "reward-missing")
        val contribution = active.contributionOf(player.uniqueId)
        if (contribution < reward.threshold) {
            return ClaimResult(
                false,
                "reward-contribution-too-low",
                placeholders = mapOf(
                    "need" to reward.threshold.toString(),
                    "contribution" to contribution.toString()
                )
            )
        }
        val claimed = active.claimedPersonalRewards.getOrPut(player.uniqueId) { linkedSetOf() }
        if (!claimed.add(reward.id)) {
            return ClaimResult(false, "reward-already-claimed")
        }
        persistCurrentLocked(active)
        return ClaimResult(
            true,
            "reward-claimed",
            commands = reward.commands,
            placeholders = commonRewardPlaceholders(player.uniqueId, reward.id, contribution)
        )
    }

    @Synchronized
    fun ranking(limit: Int = 10): List<Pair<String, Int>> {
        val active = current ?: return emptyList()
        return active.contributions.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (uuid, amount) -> (active.playerNames[uuid] ?: uuid.toString()) to amount }
    }

    @Synchronized
    fun rankOf(uuid: UUID): Int {
        val active = current ?: return 0
        val sorted = active.contributions.entries.sortedByDescending { it.value }
        return sorted.indexOfFirst { it.key == uuid }.takeIf { it >= 0 }?.plus(1) ?: 0
    }

    private fun continueReservedSubmission(
        player: Player,
        template: ActivityTemplate,
        reservation: ym.serverGoal.model.ReservedSubmission,
        future: CompletableFuture<SubmissionResult>
    ) {
        if (!player.isOnline) {
            storage.cancelReservedSubmissionAsync(reservation)
            finishPendingSubmission(player.uniqueId)
            future.complete(SubmissionResult(false, messageKey = "submit-player-offline"))
            return
        }
        val currentSnapshot = synchronized(this) {
            val active = current
            when {
                active == null -> null
                active.templateId != reservation.activityTemplateId -> null
                active.startedAt != reservation.activityStartedAt -> null
                !active.active -> null
                else -> cloneActivity(active)
            }
        }
        if (currentSnapshot == null) {
            storage.cancelReservedSubmissionAsync(reservation)
            finishPendingSubmission(player.uniqueId)
            future.complete(SubmissionResult(false, messageKey = "activity-not-running"))
            return
        }

        val deduction = deductReservedItems(player, template, reservation.acceptedByItem)
        if (!deduction.success || deduction.totalDeducted != reservation.totalAccepted) {
            storage.cancelReservedSubmissionAsync(reservation)
            finishPendingSubmission(player.uniqueId)
            future.complete(SubmissionResult(false, messageKey = "submit-inventory-changed"))
            return
        }

        storage.commitReservedSubmissionAsync(currentSnapshot, template, reservation).whenComplete { committed, commitFailure ->
            if (commitFailure != null || committed == null || !committed.committed) {
                restoreRemovedItems(player, deduction.removedItems)
                storage.cancelReservedSubmissionAsync(reservation)
                finishPendingSubmission(player.uniqueId)
                future.complete(
                    SubmissionResult(
                        false,
                        messageKey = if (commitFailure != null) "submit-sync-failed" else "submit-commit-rejected",
                        placeholders = mapOf("error" to (commitFailure?.message ?: "reservation-rejected"))
                    )
                )
                return@whenComplete
            }
            val merged = applyMergedState(committed.activity)
            synchronized(this) {
                markSubmissionAcceptedLocked(player.uniqueId)
            }
            finishPendingSubmission(player.uniqueId)
            future.complete(
                SubmissionResult(
                    success = true,
                    amount = reservation.totalAccepted,
                    messageKey = "submit-success",
                    placeholders = mapOf(
                        "amount" to reservation.totalAccepted.toString(),
                        "total" to (merged?.totalCollected ?: reservation.totalAccepted).toString(),
                        "target" to targetTotal(merged ?: currentSnapshot, template).toString()
                    )
                )
            )
        }
    }

    private fun collectSubmissionProposal(
        template: ActivityTemplate,
        active: ActiveActivity,
        player: Player,
        itemKey: String? = null
    ): SubmissionProposal {
        val requestedByItem = linkedMapOf<String, Int>()
        var submitted = 0
        val contents = player.inventory.storageContents
        var simulatedTotal = active.totalCollected
        val simulatedByItem = active.collectedByItem.toMutableMap()
        val maxSubmit = config.settings.submission.maxItemsPerSubmit.coerceAtLeast(1)
        for (stack in contents) {
            if (stack == null || stack.type.isAir || stack.amount <= 0) {
                continue
            }
            val matched = findAcceptableItem(template, active, stack, simulatedByItem, itemKey) ?: continue
            val itemRemaining = if (config.settings.protectionEnabled) {
                (itemTarget(active, matched) - (simulatedByItem[matched.key] ?: 0)).coerceAtLeast(0)
            } else {
                stack.amount
            }
            val remainingLimit = (maxSubmit - submitted).coerceAtLeast(0)
            val take = min(stack.amount, min(itemRemaining, remainingLimit))
            if (take <= 0) {
                continue
            }
            requestedByItem[matched.key] = (requestedByItem[matched.key] ?: 0) + take
            simulatedByItem[matched.key] = (simulatedByItem[matched.key] ?: 0) + take
            simulatedTotal += take
            submitted += take
            if (submitted >= maxSubmit) {
                break
            }
        }
        return SubmissionProposal(requestedByItem = requestedByItem, totalRequested = submitted)
    }

    private fun deductReservedItems(
        player: Player,
        template: ActivityTemplate,
        acceptedByItem: Map<String, Int>
    ): DeductionResult {
        if (acceptedByItem.isEmpty()) {
            return DeductionResult(false, 0)
        }
        val remaining = acceptedByItem.toMutableMap()
        val contents = player.inventory.storageContents
        val removed = mutableListOf<ItemStack>()
        var totalDeducted = 0

        for (item in template.acceptedItems) {
            var needed = remaining[item.key] ?: 0
            if (needed <= 0) {
                continue
            }
            for (index in contents.indices) {
                val stack = contents[index] ?: continue
                if (stack.type.isAir || stack.amount <= 0 || !matcher.matches(stack, item)) {
                    continue
                }
                val take = min(stack.amount, needed)
                if (take <= 0) {
                    continue
                }
                val removedStack = stack.clone()
                removedStack.amount = take
                removed += removedStack
                totalDeducted += take
                needed -= take
                if (stack.amount == take) {
                    contents[index] = null
                } else {
                    stack.amount = stack.amount - take
                    contents[index] = stack
                }
                if (needed <= 0) {
                    break
                }
            }
            if (needed > 0) {
                return DeductionResult(false, totalDeducted, removed)
            }
            remaining[item.key] = 0
        }

        if (remaining.values.any { it > 0 }) {
            return DeductionResult(false, totalDeducted, removed)
        }

        player.inventory.storageContents = contents
        player.updateInventory()
        return DeductionResult(true, totalDeducted, removed)
    }

    private fun restoreRemovedItems(player: Player, removedItems: List<ItemStack>) {
        if (removedItems.isEmpty()) {
            return
        }
        scheduler.runForPlayer(player) {
            val leftovers = linkedMapOf<Int, ItemStack>()
            for (item in removedItems) {
                leftovers.putAll(player.inventory.addItem(item))
            }
            if (leftovers.isNotEmpty()) {
                for (leftover in leftovers.values) {
                    player.world.dropItemNaturally(player.location, leftover)
                }
            }
            player.updateInventory()
        }
    }

    private fun findAcceptableItem(
        template: ActivityTemplate,
        active: ActiveActivity,
        stack: ItemStack,
        currentByItem: Map<String, Int> = active.collectedByItem,
        itemKey: String? = null
    ): CollectionItem? {
        return template.acceptedItems.firstOrNull { item ->
            if (itemKey != null && !item.key.equals(itemKey, ignoreCase = true)) {
                return@firstOrNull false
            }
            val currentAmount = currentByItem[item.key] ?: 0
            (!config.settings.protectionEnabled || currentAmount < itemTarget(active, item)) && matcher.matches(stack, item)
        }
    }

    private fun unlockStagesLocked(template: ActivityTemplate, active: ActiveActivity): List<StageDefinition> {
        val unlocked = template.stages.filter { it.index > active.unlockedStage && active.totalCollected >= stageThreshold(active, it) }
        if (unlocked.isNotEmpty()) {
            active.unlockedStage = unlocked.maxOf { it.index }
        }
        return unlocked
    }

    private fun finishLocked(active: ActiveActivity, completed: Boolean) {
        active.active = false
        active.completed = completed
        val contributionReward = if (completed) prepareContributionRewardLocked(active) else null
        val outboxEntries = contributionReward?.let { listOf(it.outboxEntry) } ?: emptyList()
        val saveFuture = persistCurrentLocked(active, outboxEntries)
        if (outboxEntries.isNotEmpty()) {
            saveFuture?.whenComplete { _, failure ->
                if (failure == null) {
                    drainRewardOutboxAsync()
                } else {
                    synchronized(this) {
                        if (current != null && sameActivity(current!!, active) && current!!.contributionRewardQueued) {
                            current!!.contributionRewardQueued = false
                            current!!.contributionRewardQueuedBy = ""
                            current!!.contributionRewardQueuedAt = 0L
                        }
                    }
                }
            }
        }
        val template = config.template(active.templateId)
        history.recordAsync(active, template, completed)
        if (completed && template != null) {
            announceCompletionLocked(active, template)
        } else {
            messages.broadcast(
                "activity-ended",
                mapOf(
                    "activity" to active.displayName,
                    "total" to active.totalCollected.toString()
                )
            )
        }
    }

    private fun isFinalReachedLocked(active: ActiveActivity): Boolean {
        val template = config.template(active.templateId) ?: return false
        if (template.acceptedItems.isEmpty()) {
            return active.totalCollected >= targetTotal(active, template)
        }
        return template.acceptedItems.all { item ->
            (active.collectedByItem[item.key] ?: 0) >= itemTarget(active, item)
        }
    }

    private fun prepareContributionRewardLocked(active: ActiveActivity): ContributionRewardBatch? {
        val template = config.template(active.templateId) ?: return null
        val reward = template.contributionReward ?: return null
        if (!reward.enabled || reward.poolAmount <= 0 || reward.commands.isEmpty()) {
            return null
        }
        if (active.contributionRewardQueued || active.contributionRewardDistributed) {
            return null
        }

        val contributors = active.contributions.entries
            .filter { it.value >= reward.minContribution && it.value > 0 }
            .map {
                ContributionPayout(
                    uuid = it.key,
                    playerName = active.playerNames[it.key] ?: it.key.toString(),
                    contribution = it.value,
                    amount = 0
                )
            }
            .sortedWith(
                compareByDescending<ContributionPayout> { it.contribution }
                    .thenBy { it.playerName.lowercase(Locale.ROOT) }
                    .thenBy { it.uuid.toString() }
            )

        if (contributors.isEmpty()) {
            return null
        }

        val totalContribution = contributors.sumOf { it.contribution }
        if (totalContribution <= 0) {
            return null
        }

        val payouts = contributionPayouts(reward, totalContribution, contributors)
        if (payouts.isEmpty()) {
            return null
        }

        val resolvedCommands = mutableListOf<String>()
        for (payout in payouts) {
            val placeholders = mapOf(
                "activity" to active.displayName,
                "player" to payout.playerName,
                "uuid" to payout.uuid.toString(),
                "contribution" to payout.contribution.toString(),
                "amount" to payout.amount.toString(),
                "reward_amount" to payout.amount.toString(),
                "pool_amount" to reward.poolAmount.toString(),
                "total_contribution" to totalContribution.toString(),
                "min_contribution" to reward.minContribution.toString()
            )
            resolvedCommands += rewards.resolveCommands(reward.commands, placeholders)
        }

        active.contributionRewardQueued = true
        active.contributionRewardQueuedBy = config.settings.serverId
        active.contributionRewardQueuedAt = System.currentTimeMillis()

        val outboxEntry = RewardOutboxEntry(
            id = contributionOutboxId(active),
            activityTemplateId = active.templateId,
            activityStartedAt = active.startedAt,
            createdBy = config.settings.serverId,
            createdAt = System.currentTimeMillis(),
            commands = resolvedCommands,
            broadcastMessageKey = reward.broadcastMessageKey,
            broadcastPlaceholders = mapOf(
                "activity" to active.displayName,
                "pool_amount" to reward.poolAmount.toString(),
                "total_contribution" to totalContribution.toString(),
                "min_contribution" to reward.minContribution.toString(),
                "players" to payouts.size.toString()
            )
        )
        return ContributionRewardBatch(outboxEntry)
    }

    private fun contributionPayouts(
        reward: ContributionRewardDefinition,
        totalContribution: Int,
        contributors: List<ContributionPayout>
    ): List<ContributionPayout> {
        val totalContributionLong = totalContribution.toLong()
        val exactShares = contributors.map { entry ->
            val exact = reward.poolAmount.toLong() * entry.contribution.toLong()
            val base = (exact / totalContributionLong).toInt()
            val remainder = exact % totalContributionLong
            entry.copy(amount = base) to remainder
        }.sortedWith(
            compareByDescending<Pair<ContributionPayout, Long>> { it.second }
                .thenByDescending { it.first.contribution }
                .thenBy { it.first.playerName.lowercase(Locale.ROOT) }
                .thenBy { it.first.uuid.toString() }
        ).toMutableList()

        var remaining = reward.poolAmount - exactShares.sumOf { it.first.amount }
        var index = 0
        while (remaining > 0 && exactShares.isNotEmpty()) {
            val current = exactShares[index % exactShares.size]
            exactShares[index % exactShares.size] = current.first.copy(amount = current.first.amount + 1) to current.second
            remaining--
            index++
        }

        return exactShares.map { it.first }.filter { it.amount > 0 }
    }

    private fun contributionOutboxId(active: ActiveActivity): String {
        val source = "servergoal:contribution:${active.templateId}:${active.startedAt}"
        return UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    private fun commonRewardPlaceholders(uuid: UUID, reward: String, contribution: Int): Map<String, String> {
        val active = current
        return mapOf(
            "reward" to reward,
            "contribution" to contribution.toString(),
            "player" to (active?.playerNames?.get(uuid) ?: uuid.toString())
        )
    }

    private fun persistCurrentLocked(
        activity: ActiveActivity?,
        outboxEntries: List<RewardOutboxEntry> = emptyList()
    ): CompletableFuture<ActiveActivity?>? {
        if (activity == null) {
            return null
        }
        activity.revision += 1
        activity.updatedAt = System.currentTimeMillis()
        activity.updatedBy = config.settings.serverId
        val snapshot = cloneActivity(activity)
        return storage.saveAsync(snapshot, outboxEntries).thenApply { merged ->
            applyMergedState(merged)
        }
    }

    private fun applyMergedState(merged: ActiveActivity?): ActiveActivity? {
        if (merged == null) {
            return null
        }
        synchronized(this) {
            val currentSnapshot = current
            if (currentSnapshot == null) {
                current = merged
                reconcileMergedProgressLocked(merged)
                announceStateChangesLocked(null, current)
                return current
            }
            if (sameActivity(currentSnapshot, merged)) {
                if (merged.revision >= currentSnapshot.revision) {
                    current = merged
                }
                current?.let { reconcileMergedProgressLocked(it) }
                announceStateChangesLocked(currentSnapshot, current)
                return current
            }
            if (merged.updatedAt >= currentSnapshot.updatedAt) {
                current = merged
            }
            current?.let { reconcileMergedProgressLocked(it) }
            announceStateChangesLocked(currentSnapshot, current)
            return current
        }
    }

    private fun reconcileMergedProgressLocked(currentState: ActiveActivity) {
        if (!currentState.active) {
            return
        }
        if (!currentState.completed && config.settings.endWhenFinalStageComplete && isFinalReachedLocked(currentState)) {
            finishLocked(currentState, true)
        }
    }

    private fun rememberLoadedAnnouncementsLocked(activity: ActiveActivity?) {
        if (activity == null) {
            return
        }
        val key = activityKey(activity)
        announcedActivityKey = key
        lastProgressNoticeByActivity[key] = System.currentTimeMillis()
        if (!activity.active && activity.completed) {
            announcedCompletionKey = key
        }
    }

    private fun announceStateChangesLocked(previous: ActiveActivity?, activity: ActiveActivity?) {
        if (activity == null) {
            return
        }
        val template = config.template(activity.templateId) ?: return
        val same = previous != null && sameActivity(previous, activity)
        if (activity.active && (!same || announcedActivityKey != activityKey(activity))) {
            announceActivityStartedLocked(activity, template)
        }
        if (!activity.active && activity.completed) {
            announceCompletionLocked(activity, template)
        }
    }

    private fun announceActivityStartedLocked(active: ActiveActivity, template: ActivityTemplate) {
        val key = activityKey(active)
        if (announcedActivityKey == key) {
            return
        }
        announcedActivityKey = key
        lastProgressNoticeByActivity[key] = System.currentTimeMillis()
        messages.broadcastClickableLines(
            "activity-started-detailed",
            announcementPlaceholders(active, template),
            "/sg join",
            "activity-join-hover"
        )
    }

    private fun announcePeriodicProgressLocked(active: ActiveActivity, template: ActivityTemplate) {
        val settings = config.settings.notifications.progress
        if (!settings.enabled) {
            return
        }
        val now = System.currentTimeMillis()
        val key = activityKey(active)
        val last = lastProgressNoticeByActivity[key] ?: active.startedAt
        if (now - last < settings.intervalSeconds * 1000L) {
            return
        }
        lastProgressNoticeByActivity[key] = now
        messages.broadcastClickableLines(
            "activity-progress-periodic",
            announcementPlaceholders(active, template),
            "/sg join",
            "activity-join-hover"
        )
    }

    private fun announceCompletionLocked(active: ActiveActivity, template: ActivityTemplate) {
        val key = activityKey(active)
        if (announcedCompletionKey == key) {
            return
        }
        announcedCompletionKey = key
        messages.broadcastClickableLines(
            "activity-completed-detailed",
            announcementPlaceholders(active, template),
            "/sg top",
            "activity-top-hover"
        )
    }

    private fun announcementPlaceholders(active: ActiveActivity, template: ActivityTemplate): Map<String, String> {
        val total = active.totalCollected
        val target = targetTotal(active, template)
        val percent = if (target <= 0) 0 else (total * 100 / target).coerceIn(0, 100)
        val minutes = ((active.endsAt - active.startedAt) / 60_000L).coerceAtLeast(1L)
        return mapOf(
            "activity" to template.displayName,
            "template" to template.id,
            "minutes" to minutes.toString(),
            "remaining" to messages.formatDuration(active.endsAt - System.currentTimeMillis()),
            "total" to total.toString(),
            "target" to target.toString(),
            "dynamic_players" to active.dynamicTargetPlayers.toString(),
            "dynamic_multiplier" to String.format("%.2f", active.dynamicTargetMultiplier),
            "remaining_amount" to (target - total).coerceAtLeast(0).toString(),
            "percent" to percent.toString(),
            "items" to template.acceptedItems.joinToString("、") { it.displayName }.ifBlank { messages.raw("gui.no-reward") }
        )
    }

    private fun activityKey(activity: ActiveActivity): String {
        return "${activity.templateId}:${activity.startedAt}"
    }

    private fun sameActivity(left: ActiveActivity, right: ActiveActivity): Boolean {
        return left.templateId == right.templateId && left.startedAt == right.startedAt
    }

    private fun cloneActivity(activity: ActiveActivity?): ActiveActivity? {
        if (activity == null) {
            return null
        }
        val yaml = ym.serverGoal.storage.ActivityCodec.encode(activity)
        return ym.serverGoal.storage.ActivityCodec.decode(yaml)
    }

    @Synchronized
    private fun finishPendingSubmission(playerId: UUID) {
        pendingSubmitters.remove(playerId)
    }

    private fun checkSubmitCooldownLocked(playerId: UUID): SubmissionResult? {
        val cooldownMillis = config.settings.submission.cooldownSeconds.coerceAtLeast(0L) * 1000L
        if (cooldownMillis <= 0L) {
            return null
        }
        val now = System.currentTimeMillis()
        val last = lastSubmissionAt[playerId] ?: return null
        val remaining = cooldownMillis - (now - last)
        if (remaining <= 0L) {
            return null
        }
        return SubmissionResult(
            false,
            messageKey = "submit-cooldown",
            placeholders = mapOf("seconds" to ((remaining + 999L) / 1000L).toString())
        )
    }

    private fun markSubmissionAcceptedLocked(playerId: UUID) {
        if (config.settings.submission.cooldownSeconds > 0L) {
            lastSubmissionAt[playerId] = System.currentTimeMillis()
        }
    }

    private fun drainRewardOutboxLoop(remaining: Int, future: CompletableFuture<Unit>) {
        if (remaining <= 0 || shuttingDown && current == null) {
            outboxRunning.set(false)
            future.complete(Unit)
            return
        }
        storage.claimNextRewardOutboxAsync().whenComplete { entry, claimFailure ->
            if (claimFailure != null || entry == null) {
                if (claimFailure == null && entry == null) {
                    val recovered = recoverMissingContributionOutbox()
                    if (recovered != null) {
                        storage.ensureRewardOutboxAsync(recovered).whenComplete { _, ensureFailure ->
                            if (ensureFailure != null) {
                                outboxRunning.set(false)
                                future.completeExceptionally(ensureFailure)
                            } else {
                                drainRewardOutboxLoop(remaining, future)
                            }
                        }
                        return@whenComplete
                    }
                }
                outboxRunning.set(false)
                if (claimFailure != null) {
                    future.completeExceptionally(claimFailure)
                } else {
                    future.complete(Unit)
                }
                return@whenComplete
            }
            rewards.executeOutbox(entry) { progressed ->
                storage.updateRewardOutboxProgressAsync(progressed)
            }.whenComplete { _, executeFailure ->
                if (executeFailure != null) {
                    storage.failRewardOutboxAsync(entry, executeFailure.message ?: executeFailure.javaClass.name)
                        .whenComplete { _, _ ->
                            outboxRunning.set(false)
                            future.completeExceptionally(executeFailure)
                        }
                    return@whenComplete
                }
                storage.completeRewardOutboxAsync(entry).whenComplete { merged, completeFailure ->
                    if (completeFailure != null) {
                        outboxRunning.set(false)
                        future.completeExceptionally(completeFailure)
                        return@whenComplete
                    }
                    rewardAudit.recordCompletedOutboxAsync(entry)
                    applyMergedState(merged)
                    drainRewardOutboxLoop(remaining - 1, future)
                }
            }
        }
    }

    private fun recoverMissingContributionOutbox(): RewardOutboxEntry? {
        val snapshot = synchronized(this) {
            val active = current ?: return null
            if ((!active.completed && !active.contributionRewardQueued) || active.contributionRewardDistributed) {
                return null
            }
            cloneActivity(active)
        } ?: return null
        val template = config.template(snapshot.templateId) ?: return null
        val reward = template.contributionReward ?: return null
        if (!reward.enabled || reward.poolAmount <= 0 || reward.commands.isEmpty()) {
            return null
        }
        val contributors = snapshot.contributions.entries
            .filter { it.value >= reward.minContribution && it.value > 0 }
            .map {
                ContributionPayout(
                    uuid = it.key,
                    playerName = snapshot.playerNames[it.key] ?: it.key.toString(),
                    contribution = it.value,
                    amount = 0
                )
            }
            .sortedWith(
                compareByDescending<ContributionPayout> { it.contribution }
                    .thenBy { it.playerName.lowercase(Locale.ROOT) }
                    .thenBy { it.uuid.toString() }
            )
        val totalContribution = contributors.sumOf { it.contribution }
        if (totalContribution <= 0) {
            return null
        }
        val payouts = contributionPayouts(reward, totalContribution, contributors)
        val resolvedCommands = mutableListOf<String>()
        for (payout in payouts) {
            resolvedCommands += rewards.resolveCommands(
                reward.commands,
                mapOf(
                    "activity" to snapshot.displayName,
                    "player" to payout.playerName,
                    "uuid" to payout.uuid.toString(),
                    "contribution" to payout.contribution.toString(),
                    "amount" to payout.amount.toString(),
                    "reward_amount" to payout.amount.toString(),
                    "pool_amount" to reward.poolAmount.toString(),
                    "total_contribution" to totalContribution.toString(),
                    "min_contribution" to reward.minContribution.toString()
                )
            )
        }
        if (resolvedCommands.isEmpty()) {
            return null
        }
        return RewardOutboxEntry(
            id = contributionOutboxId(snapshot),
            activityTemplateId = snapshot.templateId,
            activityStartedAt = snapshot.startedAt,
            createdBy = snapshot.contributionRewardQueuedBy.ifBlank { config.settings.serverId },
            createdAt = snapshot.contributionRewardQueuedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
            commands = resolvedCommands,
            broadcastMessageKey = reward.broadcastMessageKey,
            broadcastPlaceholders = mapOf(
                "activity" to snapshot.displayName,
                "pool_amount" to reward.poolAmount.toString(),
                "total_contribution" to totalContribution.toString(),
                "min_contribution" to reward.minContribution.toString(),
                "players" to payouts.size.toString()
            )
        )
    }

    fun targetTotal(active: ActiveActivity?, template: ActivityTemplate): Int {
        return active?.effectiveTargetTotal?.takeIf { it > 0 } ?: template.targetTotal
    }

    fun itemTarget(active: ActiveActivity?, item: CollectionItem): Int {
        return active?.effectiveItemTargets?.get(item.key)?.takeIf { it > 0 } ?: item.targetAmount
    }

    fun stageThreshold(active: ActiveActivity?, stage: StageDefinition): Int {
        return active?.effectiveStageThresholds?.get(stage.index)?.takeIf { it > 0 } ?: stage.threshold
    }

    private fun targetMultiplier(template: ActivityTemplate, onlinePlayers: Int): Double {
        val settings = template.dynamicTarget
        if (!settings.enabled) {
            return 1.0
        }
        val raw = onlinePlayers.toDouble() / settings.basePlayers.toDouble()
        return raw.coerceIn(settings.minMultiplier, settings.maxMultiplier)
    }

    private fun scaledTarget(base: Int, multiplier: Double): Int {
        return (base.toDouble() * multiplier).roundToInt().coerceAtLeast(1)
    }
}
