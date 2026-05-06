package ym.serverGoal.storage

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ym.serverGoal.config.ConfigService
import ym.serverGoal.config.MessageService
import ym.serverGoal.model.ActiveActivity
import ym.serverGoal.model.ActivityTemplate
import ym.serverGoal.model.CommittedReservationResult
import ym.serverGoal.model.ReservedSubmission
import ym.serverGoal.model.RewardOutboxEntry
import ym.serverGoal.platform.AsyncIoService
import ym.serverGoal.platform.MainThreadIoGuard
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ActivityStorage(
    private val plugin: JavaPlugin,
    private val config: ConfigService,
    private val messages: MessageService,
    private val io: AsyncIoService
) {
    private data class BackendCache(val signature: String, val backend: ActivitySyncBackend)

    private val localFile: File = File(plugin.dataFolder, "data/activity.yml")
    private val localOutboxFile: File = File(plugin.dataFolder, "data/reward-outbox.yml")

    @Volatile
    private var backendCache: BackendCache? = null

    @Synchronized
    fun load(): ActiveActivity? {
        MainThreadIoGuard.reject("load activity state")
        val local = loadLocal()
        val remote = loadRemoteWithFallback()
        val merged = merge(local, remote)
        writeLocal(merged)
        return merged
    }

    fun loadAsync(): CompletableFuture<ActiveActivity?> {
        return io.supply("load activity state") { load() }
    }

    @Synchronized
    fun save(activity: ActiveActivity?, outboxEntries: List<RewardOutboxEntry> = emptyList()): ActiveActivity? {
        MainThreadIoGuard.reject("save activity state")
        val prepared = activity
        val merged = merge(prepared, loadRemoteWithFallback())
        if (outboxEntries.isNotEmpty()) {
            if (!config.settings.databaseStorageEnabled) {
                appendLocalOutbox(outboxEntries)
            }
            val savedRemote = saveRemoteWithRetry(merged, outboxEntries)
            val finalMerged = merge(merged, savedRemote)
            writeLocal(finalMerged)
            return finalMerged
        }
        writeLocal(merged)
        val savedRemote = saveRemoteWithRetry(merged)
        val finalMerged = merge(merged, savedRemote)
        writeLocal(finalMerged)
        return finalMerged
    }

    fun saveAsync(activity: ActiveActivity?, outboxEntries: List<RewardOutboxEntry> = emptyList()): CompletableFuture<ActiveActivity?> {
        return io.supply("save activity state") { save(activity, outboxEntries) }
    }

    @Synchronized
    fun sync(current: ActiveActivity?): ActiveActivity? {
        MainThreadIoGuard.reject("sync activity state")
        val merged = merge(merge(loadLocal(), loadRemoteWithFallback()), current)
        writeLocal(merged)
        val savedRemote = saveRemoteWithRetry(merged)
        val finalMerged = merge(merged, savedRemote)
        writeLocal(finalMerged)
        return finalMerged
    }

    fun syncAsync(current: ActiveActivity?): CompletableFuture<ActiveActivity?> {
        return io.supply("sync activity state") { sync(current) }
    }

    fun reserveSubmissionAsync(
        activity: ActiveActivity,
        template: ActivityTemplate,
        playerId: UUID,
        playerName: String,
        proposedByItem: Map<String, Int>
    ): CompletableFuture<ReservedSubmission?> {
        return io.supply("reserve activity submission") {
            reserveSubmission(activity, template, playerId, playerName, proposedByItem)
        }
    }

    @Synchronized
    fun reserveSubmission(
        activity: ActiveActivity,
        template: ActivityTemplate,
        playerId: UUID,
        playerName: String,
        proposedByItem: Map<String, Int>
    ): ReservedSubmission? {
        MainThreadIoGuard.reject("reserve activity submission")
        val backend = mysqlBackend() ?: return null
        return backend.reserveSubmission(
            activity = activity,
            template = template,
            serverId = config.settings.serverId,
            playerId = playerId,
            playerName = playerName,
            proposedByItem = proposedByItem,
            protectionEnabled = config.settings.protectionEnabled
        )
    }

    fun commitReservedSubmissionAsync(
        activity: ActiveActivity,
        template: ActivityTemplate,
        reservation: ReservedSubmission
    ): CompletableFuture<CommittedReservationResult> {
        return io.supply("commit reserved activity submission") {
            commitReservedSubmission(activity, template, reservation)
        }
    }

    @Synchronized
    fun commitReservedSubmission(
        activity: ActiveActivity,
        template: ActivityTemplate,
        reservation: ReservedSubmission
    ): CommittedReservationResult {
        MainThreadIoGuard.reject("commit reserved activity submission")
        val backend = mysqlBackend() ?: return CommittedReservationResult(save(activity), true)
        val committed = backend.commitReservedSubmission(
            activity = activity,
            template = template,
            reservation = reservation,
            serverId = config.settings.serverId,
            protectionEnabled = config.settings.protectionEnabled
        )
        committed.activity?.let(::writeLocal)
        return committed
    }

    fun cancelReservedSubmissionAsync(reservation: ReservedSubmission): CompletableFuture<Unit> {
        return io.supply("cancel reserved activity submission") {
            cancelReservedSubmission(reservation)
        }
    }

    @Synchronized
    fun cancelReservedSubmission(reservation: ReservedSubmission) {
        MainThreadIoGuard.reject("cancel reserved activity submission")
        mysqlBackend()?.cancelReservedSubmission(reservation.id)
    }

    fun claimNextRewardOutboxAsync(): CompletableFuture<RewardOutboxEntry?> {
        return io.supply("claim reward outbox") { claimNextRewardOutbox() }
    }

    @Synchronized
    fun claimNextRewardOutbox(): RewardOutboxEntry? {
        MainThreadIoGuard.reject("claim reward outbox")
        return if (config.settings.databaseStorageEnabled) {
            mysqlBackend()?.claimNextRewardOutbox(config.settings.serverId)
        } else {
            claimNextLocalOutbox()
        }
    }

    fun completeRewardOutboxAsync(entry: RewardOutboxEntry): CompletableFuture<ActiveActivity?> {
        return io.supply("complete reward outbox") { completeRewardOutbox(entry) }
    }

    fun updateRewardOutboxProgressAsync(entry: RewardOutboxEntry): CompletableFuture<Unit> {
        return io.supply("update reward outbox progress") {
            updateRewardOutboxProgress(entry)
        }
    }

    @Synchronized
    fun updateRewardOutboxProgress(entry: RewardOutboxEntry) {
        MainThreadIoGuard.reject("update reward outbox progress")
        if (config.settings.databaseStorageEnabled) {
            mysqlBackend()?.updateRewardOutboxProgress(entry)
            return
        }
        updateLocalOutboxProgress(entry)
    }

    @Synchronized
    fun completeRewardOutbox(entry: RewardOutboxEntry): ActiveActivity? {
        MainThreadIoGuard.reject("complete reward outbox")
        val updated = if (config.settings.databaseStorageEnabled) {
            mysqlBackend()?.completeRewardOutbox(
                entryId = entry.id,
                activityTemplateId = entry.activityTemplateId,
                activityStartedAt = entry.activityStartedAt,
                completedBy = config.settings.serverId
            )
        } else {
            completeLocalOutbox(entry)
        }
        if (updated != null) {
            writeLocal(updated)
        }
        return updated
    }

    fun failRewardOutboxAsync(entry: RewardOutboxEntry, error: String): CompletableFuture<Unit> {
        return io.supply("fail reward outbox") {
            failRewardOutbox(entry, error)
        }
    }

    @Synchronized
    fun failRewardOutbox(entry: RewardOutboxEntry, error: String) {
        MainThreadIoGuard.reject("fail reward outbox")
        if (config.settings.databaseStorageEnabled) {
            mysqlBackend()?.releaseRewardOutbox(entry.id, error)
            return
        }
        releaseLocalOutbox(entry, error)
    }

    fun ensureRewardOutboxAsync(entry: RewardOutboxEntry): CompletableFuture<Unit> {
        return io.supply("ensure reward outbox") {
            ensureRewardOutbox(entry)
        }
    }

    @Synchronized
    fun ensureRewardOutbox(entry: RewardOutboxEntry) {
        MainThreadIoGuard.reject("ensure reward outbox")
        if (config.settings.databaseStorageEnabled) {
            mysqlBackend()?.ensureRewardOutbox(entry)
            return
        }
        if (!localOutboxContains(entry)) {
            appendLocalOutbox(listOf(entry))
        }
    }

    private fun loadLocal(): ActiveActivity? {
        if (!localFile.exists()) {
            return null
        }
        return ActivityCodec.decode(YamlConfiguration.loadConfiguration(localFile))
    }

    private fun writeLocal(activity: ActiveActivity?) {
        MainThreadIoGuard.reject("write local activity state")
        localFile.parentFile.mkdirs()
        ActivityCodec.encode(activity).save(localFile)
    }

    private fun backend(): ActivitySyncBackend? {
        val settings = config.settings
        if (!settings.databaseStorageEnabled) {
            clearBackend()
            return null
        }

        val signature = backendSignature()
        val cached = backendCache
        if (cached != null && cached.signature == signature) {
            return cached.backend
        }

        cached?.backend?.close()
        val database = settings.database
        if (database.type !in setOf("mysql", "mariadb")) {
            throw IllegalStateException("Unsupported ServerGoal database type: ${database.type}")
        }
        val jdbcUrl = database.effectiveJdbcUrl()
        if (jdbcUrl.isBlank()) {
            throw IllegalStateException("ServerGoal database storage requires database.host/port/database or database.jdbc-url")
        }
        val backend = MysqlActivitySyncBackend(
            jdbcUrl = jdbcUrl,
            username = database.username,
            password = database.password,
            stateTableName = database.activityStateTableName(),
            reservationTableName = database.submissionReservationTableName(),
            rewardOutboxTableName = database.rewardOutboxTableName(),
            conflictPolicy = settings.sync.conflictPolicy,
            pool = database.pool,
            useSsl = database.useSsl,
            requireSsl = database.requireSsl,
            verifyServerCertificate = database.verifyServerCertificate,
            allowPublicKeyRetrieval = database.allowPublicKeyRetrieval,
            reservationExpireMillis = settings.sync.submissionReservationExpireSeconds.coerceAtLeast(5L) * 1000L,
            outboxClaimTimeoutMillis = settings.sync.outboxClaimTimeoutSeconds.coerceAtLeast(5L) * 1000L
        )
        backendCache = BackendCache(signature, backend)
        return backend
    }

    private fun mysqlBackend(): MysqlActivitySyncBackend? = backend() as? MysqlActivitySyncBackend

    private fun backendSignature(): String {
        val settings = config.settings
        val database = settings.database
        val pool = database.pool
        return listOf(
            settings.storage.type,
            settings.storage.databaseFailureStrategy,
            settings.sync.conflictPolicy,
            database.type,
            database.effectiveJdbcUrl(),
            database.username,
            database.password,
            database.useSsl.toString(),
            database.requireSsl.toString(),
            database.verifyServerCertificate.toString(),
            database.allowPublicKeyRetrieval.toString(),
            database.activityStateTableName(),
            database.submissionReservationTableName(),
            database.rewardOutboxTableName(),
            pool.maximumPoolSize.toString(),
            pool.minimumIdle.toString(),
            pool.connectionTimeoutMs.toString(),
            pool.maxLifetimeMs.toString(),
            settings.sync.submissionReservationExpireSeconds.toString(),
            settings.sync.outboxClaimTimeoutSeconds.toString()
        ).joinToString("\u001F")
    }

    private fun loadRemoteWithFallback(): ActiveActivity? {
        val backend = backend() ?: return null
        return try {
            backend.load()
        } catch (failure: Throwable) {
            handleRemoteFailure("console.sync-load-failed", failure)
            null
        }
    }

    private fun saveRemoteWithRetry(
        activity: ActiveActivity?,
        outboxEntries: List<RewardOutboxEntry> = emptyList()
    ): ActiveActivity? {
        MainThreadIoGuard.reject("save remote activity state")
        val backend = backend() ?: return activity
        val maxAttempts = (config.settings.sync.maxRetries + 1).coerceAtLeast(1)
        val retryDelayMillis = (config.settings.sync.retryIntervalSeconds.coerceAtLeast(1L)) * 1000L
        var lastFailure: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                if (outboxEntries.isNotEmpty() && backend is MysqlActivitySyncBackend) {
                    return backend.saveWithOutbox(activity, outboxEntries) ?: activity
                }
                return backend.save(activity) ?: activity
            } catch (failure: Throwable) {
                lastFailure = failure
                if (attempt < maxAttempts - 1) {
                    runCatching {
                        Thread.sleep(retryDelayMillis)
                    }
                }
            }
        }
        handleRemoteFailure("console.sync-save-failed", lastFailure ?: IllegalStateException("remote save failed"))
        if (outboxEntries.isNotEmpty() && config.settings.storage.fallbackYamlOnDatabaseFailure) {
            appendLocalOutbox(outboxEntries)
        }
        return activity
    }

    private fun merge(left: ActiveActivity?, right: ActiveActivity?): ActiveActivity? {
        return ActivityStateMerger.merge(left, right, config.settings.sync.conflictPolicy)
    }

    private fun handleRemoteFailure(messageKey: String, failure: Throwable) {
        plugin.logger.warning(
            messages.raw(messageKey, mapOf("error" to (failure.message ?: failure.javaClass.name)))
        )
        if (!config.settings.storage.fallbackYamlOnDatabaseFailure) {
            throw failure
        }
    }

    private fun loadLocalOutbox(): MutableList<RewardOutboxEntry> {
        if (!localOutboxFile.exists()) {
            return mutableListOf()
        }
        val yaml = YamlConfiguration.loadConfiguration(localOutboxFile)
        val entries = mutableListOf<RewardOutboxEntry>()
        yaml.getConfigurationSection("entries")?.getKeys(false)?.forEach { id ->
            val entryYaml = YamlConfiguration()
            yaml.getConfigurationSection("entries.$id")?.getKeys(true)?.forEach { key ->
                if (key.isNotEmpty()) {
                    entryYaml.set(key, yaml.get("entries.$id.$key"))
                }
            }
            RewardOutboxCodec.decode(entryYaml)?.let(entries::add)
        }
        return entries.sortedBy { it.createdAt }.toMutableList()
    }

    private fun saveLocalOutbox(entries: List<RewardOutboxEntry>) {
        MainThreadIoGuard.reject("write local reward outbox")
        localOutboxFile.parentFile.mkdirs()
        val yaml = YamlConfiguration()
        for (entry in entries) {
            val entryYaml = RewardOutboxCodec.encode(entry)
            for (key in entryYaml.getKeys(true)) {
                if (key.isEmpty()) {
                    continue
                }
                yaml.set("entries.${entry.id}.$key", entryYaml.get(key))
            }
        }
        yaml.save(localOutboxFile)
    }

    private fun appendLocalOutbox(outboxEntries: List<RewardOutboxEntry>) {
        if (outboxEntries.isEmpty()) {
            return
        }
        val existing = loadLocalOutbox().associateByTo(linkedMapOf()) { it.id }
        for (entry in outboxEntries) {
            existing.putIfAbsent(entry.id, entry)
        }
        saveLocalOutbox(existing.values.toList())
    }

    private fun localOutboxContains(entry: RewardOutboxEntry): Boolean {
        return loadLocalOutbox().any { it.id == entry.id }
    }

    private fun claimNextLocalOutbox(): RewardOutboxEntry? {
        val entries = loadLocalOutbox()
        val timeoutMillis = config.settings.sync.outboxClaimTimeoutSeconds.coerceAtLeast(5L) * 1000L
        val now = System.currentTimeMillis()
        for (entry in entries) {
            if (entry.completedAt > 0L) {
                continue
            }
            if (entry.claimedBy.isNotBlank() && entry.claimedAt > 0L && now - entry.claimedAt >= timeoutMillis) {
                entry.claimedBy = ""
                entry.claimedAt = 0L
            }
        }
        val entry = entries.firstOrNull { it.completedAt <= 0L && it.claimedBy.isBlank() } ?: return null
        entry.claimedBy = config.settings.serverId
        entry.claimedAt = now
        entry.attemptCount += 1
        saveLocalOutbox(entries)
        return entry
    }

    private fun completeLocalOutbox(entry: RewardOutboxEntry): ActiveActivity? {
        val entries = loadLocalOutbox()
        val target = entries.firstOrNull { it.id == entry.id } ?: return loadLocal()
        target.completedAt = System.currentTimeMillis()
        target.lastError = ""
        saveLocalOutbox(entries.filter { it.completedAt <= 0L })

        val activity = loadLocal() ?: return null
        if (activity.templateId != entry.activityTemplateId || activity.startedAt != entry.activityStartedAt) {
            return activity
        }
        activity.contributionRewardQueued = false
        activity.contributionRewardDistributed = true
        activity.contributionRewardDistributedBy = config.settings.serverId
        activity.contributionRewardDistributedAt = System.currentTimeMillis()
        activity.updatedAt = System.currentTimeMillis()
        activity.updatedBy = config.settings.serverId
        activity.revision += 1
        writeLocal(activity)
        return activity
    }

    private fun releaseLocalOutbox(entry: RewardOutboxEntry, error: String) {
        val entries = loadLocalOutbox()
        val target = entries.firstOrNull { it.id == entry.id } ?: return
        target.claimedBy = ""
        target.claimedAt = 0L
        target.lastError = error
        saveLocalOutbox(entries)
    }

    private fun updateLocalOutboxProgress(entry: RewardOutboxEntry) {
        val entries = loadLocalOutbox()
        val target = entries.firstOrNull { it.id == entry.id } ?: return
        target.executedCommandCount = entry.executedCommandCount.coerceIn(0, entry.commands.size)
        target.broadcastSent = entry.broadcastSent
        target.lastError = entry.lastError
        saveLocalOutbox(entries)
    }

    @Synchronized
    fun shutdown() {
        clearBackend()
    }

    private fun clearBackend() {
        backendCache?.backend?.close()
        backendCache = null
    }
}
