package ym.serverGoal.storage

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ym.serverGoal.config.ConfigService
import ym.serverGoal.config.MessageService
import ym.serverGoal.model.ActiveActivity
import ym.serverGoal.platform.AsyncIoService
import ym.serverGoal.platform.MainThreadIoGuard
import java.io.File
import java.util.concurrent.CompletableFuture

class ActivityStorage(
    private val plugin: JavaPlugin,
    private val config: ConfigService,
    private val messages: MessageService,
    private val io: AsyncIoService
) {
    private data class BackendCache(val signature: String, val backend: ActivitySyncBackend)

    private val localFile: File = File(plugin.dataFolder, "data/activity.yml")
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
    fun save(activity: ActiveActivity?): ActiveActivity? {
        MainThreadIoGuard.reject("save activity state")
        val prepared = activity
        val merged = merge(prepared, loadRemoteWithFallback())
        writeLocal(merged)
        val savedRemote = saveRemoteWithRetry(merged)
        val finalMerged = merge(merged, savedRemote)
        writeLocal(finalMerged)
        return finalMerged
    }

    fun saveAsync(activity: ActiveActivity?): CompletableFuture<ActiveActivity?> {
        return io.supply("save activity state") { save(activity) }
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
            tableName = database.activityStateTableName(),
            conflictPolicy = settings.sync.conflictPolicy,
            pool = database.pool
        )
        backendCache = BackendCache(signature, backend)
        return backend
    }

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
            database.activityStateTableName(),
            pool.maximumPoolSize.toString(),
            pool.minimumIdle.toString(),
            pool.connectionTimeoutMs.toString(),
            pool.maxLifetimeMs.toString()
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

    private fun saveRemoteWithRetry(activity: ActiveActivity?): ActiveActivity? {
        MainThreadIoGuard.reject("save remote activity state")
        val backend = backend() ?: return activity
        val maxAttempts = (config.settings.sync.maxRetries + 1).coerceAtLeast(1)
        val retryDelayMillis = (config.settings.sync.retryIntervalSeconds.coerceAtLeast(1L)) * 1000L
        var lastFailure: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
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

    @Synchronized
    fun shutdown() {
        clearBackend()
    }

    private fun clearBackend() {
        backendCache?.backend?.close()
        backendCache = null
    }
}
