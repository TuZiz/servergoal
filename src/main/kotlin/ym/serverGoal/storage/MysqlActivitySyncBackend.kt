package ym.serverGoal.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.configuration.file.YamlConfiguration
import ym.serverGoal.model.ActiveActivity
import ym.serverGoal.model.DatabasePoolSettings
import ym.serverGoal.platform.MainThreadIoGuard
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean

class MysqlActivitySyncBackend(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
    tableName: String,
    private val conflictPolicy: String,
    private val pool: DatabasePoolSettings
) : ActivitySyncBackend {
    private val tableName = quoteQualifiedIdentifier(tableName)
    private val stateKey = "current"
    private val lockKey = "servergoal:activity:$tableName"
    private val schemaReady = AtomicBoolean(false)

    @Volatile
    private var dataSource: HikariDataSource? = null

    override fun load(): ActiveActivity? {
        MainThreadIoGuard.reject("load mysql activity sync")
        ensureSchema()
        connection().use { conn ->
            return readSnapshot(conn)
        }
    }

    override fun save(activity: ActiveActivity?): ActiveActivity? {
        MainThreadIoGuard.reject("save mysql activity sync")
        if (activity == null) {
            return null
        }
        ensureSchema()
        connection().use { conn ->
            conn.autoCommit = false
            acquireLock(conn)
            try {
                val current = readSnapshot(conn)
                val merged = ActivityStateMerger.merge(current, activity, conflictPolicy) ?: activity
                writeSnapshot(conn, merged)
                conn.commit()
                return merged
            } catch (failure: Throwable) {
                runCatching { conn.rollback() }
                throw failure
            } finally {
                runCatching { releaseLock(conn) }
            }
        }
    }

    override fun close() {
        dataSource?.close()
        dataSource = null
        schemaReady.set(false)
    }

    private fun ensureSchema() {
        if (schemaReady.get()) {
            return
        }
        synchronized(schemaReady) {
            if (schemaReady.get()) {
                return
            }
            connection().use { conn ->
                conn.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS $tableName (
                          state_key VARCHAR(64) NOT NULL,
                          payload LONGTEXT NOT NULL,
                          PRIMARY KEY (state_key)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent()
                    )
                }
            }
            schemaReady.set(true)
        }
    }

    private fun readSnapshot(conn: Connection): ActiveActivity? {
        conn.prepareStatement("SELECT payload FROM $tableName WHERE state_key = ?").use { statement ->
            statement.setString(1, stateKey)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                val payload = resultSet.getString(1) ?: return null
                return decode(payload)
            }
        }
    }

    private fun writeSnapshot(conn: Connection, activity: ActiveActivity) {
        conn.prepareStatement(
            """
            INSERT INTO $tableName (state_key, payload)
            VALUES (?, ?)
            ON DUPLICATE KEY UPDATE payload = VALUES(payload)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, stateKey)
            statement.setString(2, encode(activity))
            statement.executeUpdate()
        }
    }

    private fun acquireLock(conn: Connection) {
        conn.prepareStatement("SELECT GET_LOCK(?, 10)").use { statement ->
            statement.setString(1, lockKey)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw SQLException("ServerGoal MySQL sync lock timeout: $lockKey")
                }
            }
        }
    }

    private fun releaseLock(conn: Connection) {
        conn.prepareStatement("SELECT RELEASE_LOCK(?)").use { statement ->
            statement.setString(1, lockKey)
            statement.executeQuery().use { }
        }
    }

    private fun connection(): Connection = dataSource().connection

    private fun dataSource(): HikariDataSource {
        val existing = dataSource
        if (existing != null && !existing.isClosed) {
            return existing
        }
        synchronized(this) {
            val current = dataSource
            if (current != null && !current.isClosed) {
                return current
            }
            val maximumPoolSize = pool.maximumPoolSize.coerceAtLeast(1)
            val minimumIdle = pool.minimumIdle.coerceIn(0, maximumPoolSize)
            val config = HikariConfig().apply {
                jdbcUrl = this@MysqlActivitySyncBackend.jdbcUrl
                username = this@MysqlActivitySyncBackend.username
                password = this@MysqlActivitySyncBackend.password
                this.maximumPoolSize = maximumPoolSize
                this.minimumIdle = minimumIdle
                connectionTimeout = pool.connectionTimeoutMs.coerceAtLeast(250L)
                maxLifetime = pool.maxLifetimeMs.coerceAtLeast(30_000L)
                poolName = "ServerGoal-MySQL"
                addDataSourceProperty("useUnicode", "true")
                addDataSourceProperty("characterEncoding", "utf8")
                addDataSourceProperty("useSSL", "false")
                addDataSourceProperty("allowPublicKeyRetrieval", "true")
                addDataSourceProperty("cachePrepStmts", "true")
                addDataSourceProperty("prepStmtCacheSize", "250")
                addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            }
            return HikariDataSource(config).also { dataSource = it }
        }
    }

    private fun encode(activity: ActiveActivity): String {
        return ActivityCodec.encode(activity).saveToString()
    }

    private fun decode(payload: String): ActiveActivity? {
        val yaml = YamlConfiguration()
        yaml.loadFromString(payload)
        return ActivityCodec.decode(yaml)
    }

    private fun quoteQualifiedIdentifier(raw: String): String {
        val parts = raw.split('.')
        require(parts.isNotEmpty()) { "Invalid MySQL table name: $raw" }
        return parts.joinToString(".") { part ->
            require(part.matches(Regex("[A-Za-z0-9_]+"))) { "Invalid MySQL identifier: $raw" }
            "`$part`"
        }
    }
}
