package ym.serverGoal.service

import org.bukkit.entity.Player
import ym.serverGoal.config.ConfigService
import ym.serverGoal.config.MessageService
import ym.serverGoal.model.ActiveActivity
import ym.serverGoal.model.ActivityTemplate
import ym.serverGoal.model.ClaimResult
import ym.serverGoal.model.CollectionItem
import ym.serverGoal.model.ContributionRewardDefinition
import ym.serverGoal.model.SubmissionResult
import ym.serverGoal.storage.ActivityStorage
import java.util.UUID
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.math.min

class ActivityService(
    private val config: ConfigService,
    private val messages: MessageService,
    private val storage: ActivityStorage,
    private val matcher: ItemMatcher,
    private val rewards: RewardService
) {
    private var current: ActiveActivity? = null

    private data class ContributionPayout(
        val uuid: UUID,
        val playerName: String,
        val contribution: Int,
        val amount: Int
    )

    private data class ContributionRewardBatch(
        val activityName: String,
        val definition: ContributionRewardDefinition,
        val totalContribution: Int,
        val payouts: List<ContributionPayout>
    )

    fun loadAsync(): CompletableFuture<ActiveActivity?> {
        return storage.loadAsync().thenApply { loaded ->
            synchronized(this) {
                current = loaded
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

    @Synchronized
    fun activeActivity(): ActiveActivity? = current

    @Synchronized
    fun currentTemplate(): ActivityTemplate? {
        val active = current ?: return null
        return config.template(active.templateId)
    }

    @Synchronized
    fun startTemplate(templateId: String, minutesOverride: Int? = null): Boolean {
        val template = config.template(templateId) ?: return false
        val existing = current
        if (existing != null && existing.active) {
            return false
        }
        val now = System.currentTimeMillis()
        val durationMinutes = minutesOverride?.coerceAtLeast(1) ?: template.durationMinutes
        current = ActiveActivity(
            templateId = template.id,
            displayName = template.displayName,
            startedAt = now,
            endsAt = now + durationMinutes * 60_000L,
            active = true,
            completed = false,
            totalCollected = 0,
            unlockedStage = 0
        )
        persistCurrentLocked(current)
        messages.broadcast(
            "activity-started",
            mapOf(
                "activity" to template.displayName,
                "minutes" to durationMinutes.toString(),
                "target" to template.targetTotal.toString()
            )
        )
        return true
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
        if (active.active && System.currentTimeMillis() >= active.endsAt) {
            finishLocked(active, active.completed || isFinalReachedLocked(active))
        }
    }

    @Synchronized
    fun submitInventory(player: Player): SubmissionResult {
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

        var submitted = 0
        val submittedByItem = linkedMapOf<String, Int>()
        val contents = player.inventory.storageContents
        for (index in contents.indices) {
            val stack = contents[index] ?: continue
            if (stack.type.isAir || stack.amount <= 0) {
                continue
            }
            val matched = findAcceptableItem(template, active, stack) ?: continue
            val itemRemaining = if (config.settings.protectionEnabled) {
                (matched.targetAmount - (active.collectedByItem[matched.key] ?: 0)).coerceAtLeast(0)
            } else {
                stack.amount
            }
            val activityRemaining = if (config.settings.protectionEnabled) {
                (template.targetTotal - active.totalCollected).coerceAtLeast(0)
            } else {
                stack.amount
            }
            val take = min(stack.amount, min(itemRemaining, activityRemaining))
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
            if (config.settings.protectionEnabled && active.totalCollected + submitted >= template.targetTotal) {
                break
            }
        }

        if (submitted <= 0) {
            return SubmissionResult(false, messageKey = "submit-no-items")
        }

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

        val unlocked = unlockStagesLocked(template, active)
        if (config.settings.saveOnSubmit) {
            persistCurrentLocked(active)
        }
        for (stage in unlocked) {
            messages.broadcast(
                "stage-unlocked",
                mapOf(
                    "activity" to template.displayName,
                    "stage" to stage.index.toString(),
                    "stage_name" to stage.displayName,
                    "threshold" to stage.threshold.toString()
                )
            )
        }

        if (config.settings.endWhenFinalStageComplete && active.totalCollected >= template.targetTotal) {
            finishLocked(active, true)
        }

        return SubmissionResult(
            success = true,
            amount = submitted,
            messageKey = "submit-success",
            placeholders = mapOf(
                "amount" to submitted.toString(),
                "total" to active.totalCollected.toString(),
                "target" to template.targetTotal.toString()
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

    private fun findAcceptableItem(template: ActivityTemplate, active: ActiveActivity, stack: org.bukkit.inventory.ItemStack): CollectionItem? {
        return template.acceptedItems.firstOrNull { item ->
            val currentAmount = active.collectedByItem[item.key] ?: 0
            (!config.settings.protectionEnabled || currentAmount < item.targetAmount) && matcher.matches(stack, item)
        }
    }

    private fun unlockStagesLocked(template: ActivityTemplate, active: ActiveActivity): List<ym.serverGoal.model.StageDefinition> {
        val unlocked = template.stages.filter { it.index > active.unlockedStage && active.totalCollected >= it.threshold }
        if (unlocked.isNotEmpty()) {
            active.unlockedStage = unlocked.maxOf { it.index }
        }
        return unlocked
    }

    private fun finishLocked(active: ActiveActivity, completed: Boolean) {
        active.active = false
        active.completed = completed
        val contributionReward = if (completed) prepareContributionRewardLocked(active) else null
        val saveFuture = persistCurrentLocked(active)
        if (contributionReward != null) {
            saveFuture?.whenComplete { saved, failure ->
                if (failure == null && saved?.contributionRewardDistributedBy == config.settings.serverId) {
                    dispatchContributionRewardLocked(contributionReward)
                }
            }
        }
        messages.broadcast(
            if (completed) "activity-completed" else "activity-ended",
            mapOf(
                "activity" to active.displayName,
                "total" to active.totalCollected.toString()
            )
        )
    }

    private fun isFinalReachedLocked(active: ActiveActivity): Boolean {
        val template = config.template(active.templateId) ?: return false
        return active.totalCollected >= template.targetTotal
    }

    private fun prepareContributionRewardLocked(active: ActiveActivity): ContributionRewardBatch? {
        val template = config.template(active.templateId) ?: return null
        val reward = template.contributionReward ?: return null
        if (!reward.enabled || reward.poolAmount <= 0 || reward.commands.isEmpty() || active.contributionRewardDistributed) {
            return null
        }

        val totalContribution = active.contributions.values.sum()
        if (totalContribution <= 0) {
            return null
        }

        val contributors = active.contributions.entries
            .filter { it.value > 0 }
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

        val payouts = exactShares.map { it.first }.filter { it.amount > 0 }
        if (payouts.isEmpty()) {
            return null
        }

        active.contributionRewardDistributed = true
        active.contributionRewardDistributedBy = config.settings.serverId
        active.contributionRewardDistributedAt = System.currentTimeMillis()
        return ContributionRewardBatch(active.displayName, reward, totalContribution, payouts)
    }

    private fun dispatchContributionRewardLocked(batch: ContributionRewardBatch) {
        for (payout in batch.payouts) {
            val placeholders = mapOf(
                "activity" to batch.activityName,
                "player" to payout.playerName,
                "uuid" to payout.uuid.toString(),
                "contribution" to payout.contribution.toString(),
                "amount" to payout.amount.toString(),
                "reward_amount" to payout.amount.toString(),
                "pool_amount" to batch.definition.poolAmount.toString(),
                "total_contribution" to batch.totalContribution.toString()
            )
            rewards.executeConsole(batch.definition.commands, placeholders)
        }
        messages.broadcast(
            batch.definition.broadcastMessageKey,
            mapOf(
                "activity" to batch.activityName,
                "pool_amount" to batch.definition.poolAmount.toString(),
                "total_contribution" to batch.totalContribution.toString(),
                "players" to batch.payouts.size.toString()
            )
        )
    }

    private fun commonRewardPlaceholders(uuid: UUID, reward: String, contribution: Int): Map<String, String> {
        val active = current
        return mapOf(
            "reward" to reward,
            "contribution" to contribution.toString(),
            "player" to (active?.playerNames?.get(uuid) ?: uuid.toString())
        )
    }

    private fun persistCurrentLocked(activity: ActiveActivity?): CompletableFuture<ActiveActivity?>? {
        if (activity == null) {
            return null
        }
        activity.revision += 1
        activity.updatedAt = System.currentTimeMillis()
        activity.updatedBy = config.settings.serverId
        val snapshot = cloneActivity(activity)
        return storage.saveAsync(snapshot).thenApply { merged ->
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
                return current
            }
            if (sameActivity(currentSnapshot, merged)) {
                if (merged.revision >= currentSnapshot.revision) {
                    current = merged
                }
                current?.let { reconcileMergedProgressLocked(it) }
                return current
            }
            if (merged.updatedAt >= currentSnapshot.updatedAt) {
                current = merged
            }
            current?.let { reconcileMergedProgressLocked(it) }
            return current
        }
    }

    private fun reconcileMergedProgressLocked(currentState: ActiveActivity) {
        val template = config.template(currentState.templateId) ?: return
        if (!currentState.active) {
            return
        }
        val unlocked = unlockStagesLocked(template, currentState)
        if (unlocked.isNotEmpty()) {
            persistCurrentLocked(currentState)
            for (stage in unlocked) {
                messages.broadcast(
                    "stage-unlocked",
                    mapOf(
                        "activity" to currentState.displayName,
                        "stage" to stage.index.toString(),
                        "stage_name" to stage.displayName,
                        "threshold" to stage.threshold.toString()
                    )
                )
            }
        }
        if (!currentState.completed && config.settings.endWhenFinalStageComplete && currentState.totalCollected >= template.targetTotal) {
            finishLocked(currentState, true)
        }
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

}
