package ym.serverGoal.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.configuration.file.YamlConfiguration
import ym.serverGoal.model.ActiveActivity
import ym.serverGoal.model.ActivityTemplate
import ym.serverGoal.model.CommittedReservationResult
import ym.serverGoal.model.DatabasePoolSettings
import ym.serverGoal.model.ReservedSubmission
import ym.serverGoal.model.RewardOutboxEntry
import ym.serverGoal.platform.MainThreadIoGuard
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MysqlActivitySyncBackend(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
    stateTableName: String,
    reservationTableName: String,
    rewardOutboxTableName: String,
    private val conflictPolicy: String,
    private val pool: DatabasePoolSettings,
    private val useSsl: Boolean,
    private val requireSsl: Boolean,
    private val verifyServerCertificate: Boolean,
    private val allowPublicKeyRetrieval: Boolean,
    private val reservationExpireMillis: Long,
    private val outboxClaimTimeoutMillis: Long
) : ActivitySyncBackend {
    private val stateTableName = quoteQualifiedIdentifier(stateTableName)
    private val reservationTableName = quoteQualifiedIdentifier(reservationTableName)
    private val rewardOutboxTableName = quoteQualifiedIdentifier(rewardOutboxTableName)
    private val stateKey = "current"
    private val lockKey = "servergoal:activity:$stateTableName"
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
        return saveWithOutbox(activity, emptyList())
    }

    fun saveWithOutbox(activity: ActiveActivity?, outboxEntries: List<RewardOutboxEntry>): ActiveActivity? {
        MainThreadIoGuard.reject("save mysql activity sync")
        ensureSchema()
        connection().use { conn ->
            conn.autoCommit = false
            acquireLock(conn)
            try {
                val current = readSnapshot(conn)
                val shouldInsertOutbox = outboxEntries.isNotEmpty() &&
                    activity != null &&
                    canQueueContributionOutbox(current, activity)
                val prepared = if (!shouldInsertOutbox && outboxEntries.isNotEmpty()) {
                    activity?.also {
                        it.contributionRewardQueued = current?.contributionRewardQueued ?: it.contributionRewardQueued
                        it.contributionRewardQueuedBy = current?.contributionRewardQueuedBy ?: it.contributionRewardQueuedBy
                        it.contributionRewardQueuedAt = current?.contributionRewardQueuedAt ?: it.contributionRewardQueuedAt
                        it.contributionRewardDistributed = current?.contributionRewardDistributed ?: it.contributionRewardDistributed
                        it.contributionRewardDistributedBy = current?.contributionRewardDistributedBy ?: it.contributionRewardDistributedBy
                        it.contributionRewardDistributedAt = current?.contributionRewardDistributedAt ?: it.contributionRewardDistributedAt
                    }
                } else {
                    activity
                }
                val merged = when {
                    prepared == null -> current
                    current == null -> prepared
                    else -> ActivityStateMerger.merge(current, prepared, conflictPolicy) ?: prepared
                }
                if (merged != null) {
                    writeSnapshot(conn, merged)
                }
                if (shouldInsertOutbox) {
                    outboxEntries.forEach { insertOutbox(conn, it) }
                }
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

    fun reserveSubmission(
        activity: ActiveActivity,
        template: ActivityTemplate,
        serverId: String,
        playerId: UUID,
        playerName: String,
        proposedByItem: Map<String, Int>,
        protectionEnabled: Boolean
    ): ReservedSubmission? {
        MainThreadIoGuard.reject("reserve mysql activity submission")
        if (proposedByItem.isEmpty()) {
            return null
        }
        ensureSchema()
        connection().use { conn ->
            conn.autoCommit = false
            acquireLock(conn)
            try {
                val current = readSnapshot(conn) ?: activity
                if (current.templateId != activity.templateId || current.startedAt != activity.startedAt || !current.active) {
                    conn.commit()
                    return null
                }
                deleteStaleReservations(conn, current)
                val reservations = readReservations(conn).filter { matchesActivity(it, current) }
                val reservedByItem = linkedMapOf<String, Int>()
                for (reservation in reservations) {
                    for ((key, amount) in reservation.acceptedByItem) {
                        reservedByItem[key] = (reservedByItem[key] ?: 0) + amount
                    }
                }

                val acceptedByItem = linkedMapOf<String, Int>()
                var acceptedTotal = 0
                var remainingActivity = if (protectionEnabled) {
                    (template.targetTotal - current.totalCollected - reservedByItem.values.sum()).coerceAtLeast(0)
                } else {
                    Int.MAX_VALUE
                }
                val itemTargets = template.acceptedItems.associate { it.key to it.targetAmount }
                for ((key, requested) in proposedByItem) {
                    if (requested <= 0) {
                        continue
                    }
                    val accepted = if (!protectionEnabled) {
                        requested
                    } else {
                        val itemRemaining = (itemTargets[key] ?: 0) - (current.collectedByItem[key] ?: 0) - (reservedByItem[key] ?: 0)
                        min(requested, min(itemRemaining.coerceAtLeast(0), remainingActivity))
                    }
                    if (accepted <= 0) {
                        continue
                    }
                    acceptedByItem[key] = accepted
                    acceptedTotal += accepted
                    if (protectionEnabled) {
                        remainingActivity = (remainingActivity - accepted).coerceAtLeast(0)
                    }
                }
                if (acceptedTotal <= 0) {
                    conn.commit()
                    return null
                }

                val reservation = ReservedSubmission(
                    id = UUID.randomUUID().toString(),
                    activityTemplateId = current.templateId,
                    activityStartedAt = current.startedAt,
                    serverId = serverId,
                    playerId = playerId,
                    playerName = playerName,
                    acceptedByItem = acceptedByItem,
                    totalAccepted = acceptedTotal,
                    createdAt = System.currentTimeMillis()
                )
                insertReservation(conn, reservation)
                conn.commit()
                return reservation
            } catch (failure: Throwable) {
                runCatching { conn.rollback() }
                throw failure
            } finally {
                runCatching { releaseLock(conn) }
            }
        }
    }

    fun commitReservedSubmission(
        activity: ActiveActivity,
        template: ActivityTemplate,
        reservation: ReservedSubmission,
        serverId: String,
        protectionEnabled: Boolean
    ): CommittedReservationResult {
        MainThreadIoGuard.reject("commit mysql reserved submission")
        ensureSchema()
        connection().use { conn ->
            conn.autoCommit = false
            acquireLock(conn)
            try {
                val current = readSnapshot(conn) ?: activity
                val storedReservation = readReservation(conn, reservation.id) ?: run {
                    conn.commit()
                    return CommittedReservationResult(current, false)
                }
                if (!matchesActivity(storedReservation, current)) {
                    deleteReservation(conn, reservation.id)
                    conn.commit()
                    return CommittedReservationResult(current, false)
                }

                if (!current.active) {
                    deleteReservation(conn, reservation.id)
                    conn.commit()
                    return CommittedReservationResult(current, false)
                }

                val itemTargets = template.acceptedItems.associate { it.key to it.targetAmount }
                val appliedByItem = linkedMapOf<String, Int>()
                var appliedTotal = 0
                var remainingActivity = if (protectionEnabled) {
                    (template.targetTotal - current.totalCollected).coerceAtLeast(0)
                } else {
                    Int.MAX_VALUE
                }
                for ((key, reserved) in storedReservation.acceptedByItem) {
                    if (reserved <= 0) {
                        continue
                    }
                    val accepted = if (!protectionEnabled) {
                        reserved
                    } else {
                        val itemRemaining = (itemTargets[key] ?: 0) - (current.collectedByItem[key] ?: 0)
                        min(reserved, min(itemRemaining.coerceAtLeast(0), remainingActivity))
                    }
                    if (accepted <= 0) {
                        continue
                    }
                    appliedByItem[key] = accepted
                    appliedTotal += accepted
                    if (protectionEnabled) {
                        remainingActivity = (remainingActivity - accepted).coerceAtLeast(0)
                    }
                }

                if (appliedTotal > 0) {
                    current.totalCollected += appliedTotal
                    current.playerNames[storedReservation.playerId] = storedReservation.playerName
                    current.contributions[storedReservation.playerId] = (current.contributions[storedReservation.playerId] ?: 0) + appliedTotal
                    val serverContribution = current.serverContributions.getOrPut(serverId) { linkedMapOf() }
                    serverContribution[storedReservation.playerId] =
                        (serverContribution[storedReservation.playerId] ?: 0) + appliedTotal
                    for ((key, amount) in appliedByItem) {
                        current.collectedByItem[key] = (current.collectedByItem[key] ?: 0) + amount
                        val serverItems = current.serverCollectedByItem.getOrPut(serverId) { linkedMapOf() }
                        serverItems[key] = (serverItems[key] ?: 0) + amount
                    }
                    current.updatedAt = System.currentTimeMillis()
                    current.updatedBy = serverId
                    current.revision += 1
                    writeSnapshot(conn, current)
                }

                deleteReservation(conn, reservation.id)
                conn.commit()
                return CommittedReservationResult(current, appliedTotal > 0)
            } catch (failure: Throwable) {
                runCatching { conn.rollback() }
                throw failure
            } finally {
                runCatching { releaseLock(conn) }
            }
        }
    }

    fun cancelReservedSubmission(reservationId: String) {
        MainThreadIoGuard.reject("cancel mysql reserved submission")
        ensureSchema()
        connection().use { conn ->
            conn.autoCommit = false
            acquireLock(conn)
            try {
                deleteReservation(conn, reservationId)
                conn.commit()
            } catch (failure: Throwable) {
                runCatching { conn.rollback() }
                throw failure
            } finally {
                runCatching { releaseLock(conn) }
            }
        }
    }

    fun claimNextRewardOutbox(serverId: String): RewardOutboxEntry? {
        MainThreadIoGuard.reject("claim mysql reward outbox")
        ensureSchema()
        connection().use { conn ->
            conn.autoCommit = false
            acquireLock(conn)
            try {
                val now = System.currentTimeMillis()
                conn.prepareStatement(
                    """
                    UPDATE $rewardOutboxTableName
                    SET claimed_by = '', claimed_at = 0
                    WHERE completed_at = 0
                      AND claimed_by <> ''
                      AND claimed_at > 0
                      AND claimed_at <= ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, now - outboxClaimTimeoutMillis.coerceAtLeast(1L))
                    statement.executeUpdate()
                }
                val row = conn.prepareStatement(
                    """
                    SELECT outbox_id, payload, created_at, attempt_count, last_error
                    FROM $rewardOutboxTableName
                    WHERE completed_at = 0 AND claimed_by = ''
                    ORDER BY created_at ASC
                    LIMIT 1
                    """.trimIndent()
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) {
                            null
                        } else {
                            resultSet.readOutboxRow()
                        }
                    }
                } ?: run {
                    conn.commit()
                    return null
                }

                conn.prepareStatement(
                    """
                    UPDATE $rewardOutboxTableName
                    SET claimed_by = ?, claimed_at = ?, attempt_count = attempt_count + 1
                    WHERE outbox_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, serverId)
                    statement.setLong(2, now)
                    statement.setString(3, row.id)
                    statement.executeUpdate()
                }
                conn.commit()
                row.claimedBy = serverId
                row.claimedAt = now
                row.attemptCount += 1
                return row
            } catch (failure: Throwable) {
                runCatching { conn.rollback() }
                throw failure
            } finally {
                runCatching { releaseLock(conn) }
            }
        }
    }

    fun completeRewardOutbox(
        entryId: String,
        activityTemplateId: String,
        activityStartedAt: Long,
        completedBy: String
    ): ActiveActivity? {
        MainThreadIoGuard.reject("complete mysql reward outbox")
        ensureSchema()
        connection().use { conn ->
            conn.autoCommit = false
            acquireLock(conn)
            try {
                val current = readSnapshot(conn)
                if (current != null && current.templateId == activityTemplateId && current.startedAt == activityStartedAt) {
                    current.contributionRewardQueued = false
                    current.contributionRewardDistributed = true
                    current.contributionRewardDistributedBy = completedBy
                    current.contributionRewardDistributedAt = System.currentTimeMillis()
                    current.updatedAt = System.currentTimeMillis()
                    current.updatedBy = completedBy
                    current.revision += 1
                    writeSnapshot(conn, current)
                }
                conn.prepareStatement("DELETE FROM $rewardOutboxTableName WHERE outbox_id = ?").use { statement ->
                    statement.setString(1, entryId)
                    statement.executeUpdate()
                }
                conn.commit()
                return current
            } catch (failure: Throwable) {
                runCatching { conn.rollback() }
                throw failure
            } finally {
                runCatching { releaseLock(conn) }
            }
        }
    }

    fun updateRewardOutboxProgress(entry: RewardOutboxEntry) {
        MainThreadIoGuard.reject("update mysql reward outbox progress")
        ensureSchema()
        connection().use { conn ->
            conn.autoCommit = false
            acquireLock(conn)
            try {
                conn.prepareStatement(
                    """
                    UPDATE $rewardOutboxTableName
                    SET payload = ?, last_error = ?
                    WHERE outbox_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, encodeOutbox(entry))
                    statement.setString(2, entry.lastError)
                    statement.setString(3, entry.id)
                    statement.executeUpdate()
                }
                conn.commit()
            } catch (failure: Throwable) {
                runCatching { conn.rollback() }
                throw failure
            } finally {
                runCatching { releaseLock(conn) }
            }
        }
    }

    fun releaseRewardOutbox(entryId: String, error: String) {
        MainThreadIoGuard.reject("release mysql reward outbox")
        ensureSchema()
        connection().use { conn ->
            conn.autoCommit = false
            acquireLock(conn)
            try {
                conn.prepareStatement(
                    """
                    UPDATE $rewardOutboxTableName
                    SET claimed_by = '', claimed_at = 0, last_error = ?
                    WHERE outbox_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, error)
                    statement.setString(2, entryId)
                    statement.executeUpdate()
                }
                conn.commit()
            } catch (failure: Throwable) {
                runCatching { conn.rollback() }
                throw failure
            } finally {
                runCatching { releaseLock(conn) }
            }
        }
    }

    fun ensureRewardOutbox(entry: RewardOutboxEntry) {
        MainThreadIoGuard.reject("ensure mysql reward outbox")
        ensureSchema()
        connection().use { conn ->
            conn.autoCommit = false
            acquireLock(conn)
            try {
                val current = readSnapshot(conn)
                if (canRecoverContributionOutbox(current, entry) && !outboxExists(conn, entry.id)) {
                    insertOutbox(conn, entry)
                }
                conn.commit()
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
                        CREATE TABLE IF NOT EXISTS $stateTableName (
                          state_key VARCHAR(64) NOT NULL,
                          payload LONGTEXT NOT NULL,
                          PRIMARY KEY (state_key)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent()
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS $reservationTableName (
                          reservation_id VARCHAR(64) NOT NULL,
                          state_key VARCHAR(64) NOT NULL,
                          payload LONGTEXT NOT NULL,
                          created_at BIGINT NOT NULL,
                          PRIMARY KEY (reservation_id),
                          KEY idx_state_key (state_key)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent()
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS $rewardOutboxTableName (
                          outbox_id VARCHAR(64) NOT NULL,
                          state_key VARCHAR(64) NOT NULL,
                          payload LONGTEXT NOT NULL,
                          created_at BIGINT NOT NULL,
                          claimed_by VARCHAR(64) NOT NULL DEFAULT '',
                          claimed_at BIGINT NOT NULL DEFAULT 0,
                          completed_at BIGINT NOT NULL DEFAULT 0,
                          attempt_count INT NOT NULL DEFAULT 0,
                          last_error TEXT NOT NULL,
                          PRIMARY KEY (outbox_id),
                          KEY idx_outbox_claim (completed_at, claimed_by, created_at)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent()
                    )
                }
            }
            schemaReady.set(true)
        }
    }

    private fun readSnapshot(conn: Connection): ActiveActivity? {
        conn.prepareStatement("SELECT payload FROM $stateTableName WHERE state_key = ?").use { statement ->
            statement.setString(1, stateKey)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                val payload = resultSet.getString(1) ?: return null
                return decodeActivity(payload)
            }
        }
    }

    private fun writeSnapshot(conn: Connection, activity: ActiveActivity) {
        conn.prepareStatement(
            """
            INSERT INTO $stateTableName (state_key, payload)
            VALUES (?, ?)
            ON DUPLICATE KEY UPDATE payload = VALUES(payload)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, stateKey)
            statement.setString(2, encodeActivity(activity))
            statement.executeUpdate()
        }
    }

    private fun readReservations(conn: Connection): List<ReservedSubmission> {
        conn.prepareStatement(
            "SELECT payload FROM $reservationTableName WHERE state_key = ? ORDER BY created_at ASC"
        ).use { statement ->
            statement.setString(1, stateKey)
            statement.executeQuery().use { resultSet ->
                val reservations = mutableListOf<ReservedSubmission>()
                while (resultSet.next()) {
                    decodeReservation(resultSet.getString("payload"))?.let(reservations::add)
                }
                return reservations
            }
        }
    }

    private fun readReservation(conn: Connection, reservationId: String): ReservedSubmission? {
        conn.prepareStatement(
            "SELECT payload FROM $reservationTableName WHERE reservation_id = ? AND state_key = ?"
        ).use { statement ->
            statement.setString(1, reservationId)
            statement.setString(2, stateKey)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                return decodeReservation(resultSet.getString("payload"))
            }
        }
    }

    private fun insertReservation(conn: Connection, reservation: ReservedSubmission) {
        conn.prepareStatement(
            """
            INSERT INTO $reservationTableName (reservation_id, state_key, payload, created_at)
            VALUES (?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, reservation.id)
            statement.setString(2, stateKey)
            statement.setString(3, encodeReservation(reservation))
            statement.setLong(4, reservation.createdAt)
            statement.executeUpdate()
        }
    }

    private fun deleteReservation(conn: Connection, reservationId: String) {
        conn.prepareStatement("DELETE FROM $reservationTableName WHERE reservation_id = ?").use { statement ->
            statement.setString(1, reservationId)
            statement.executeUpdate()
        }
    }

    private fun deleteStaleReservations(conn: Connection, current: ActiveActivity) {
        val reservations = readReservations(conn)
        val expireBefore = System.currentTimeMillis() - reservationExpireMillis.coerceAtLeast(1L)
        for (reservation in reservations) {
            if (!matchesActivity(reservation, current) || reservation.createdAt <= expireBefore) {
                deleteReservation(conn, reservation.id)
            }
        }
    }

    private fun insertOutbox(conn: Connection, entry: RewardOutboxEntry) {
        conn.prepareStatement(
            """
            INSERT INTO $rewardOutboxTableName (
              outbox_id, state_key, payload, created_at, claimed_by, claimed_at, completed_at, attempt_count, last_error
            ) VALUES (?, ?, ?, ?, '', 0, 0, 0, '')
            ON DUPLICATE KEY UPDATE outbox_id = outbox_id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, entry.id)
            statement.setString(2, stateKey)
            statement.setString(3, encodeOutbox(entry))
            statement.setLong(4, entry.createdAt)
            statement.executeUpdate()
        }
    }

    private fun outboxExists(conn: Connection, entryId: String): Boolean {
        conn.prepareStatement(
            "SELECT 1 FROM $rewardOutboxTableName WHERE outbox_id = ? LIMIT 1"
        ).use { statement ->
            statement.setString(1, entryId)
            statement.executeQuery().use { resultSet ->
                return resultSet.next()
            }
        }
    }

    private fun canQueueContributionOutbox(current: ActiveActivity?, activity: ActiveActivity): Boolean {
        if (current == null) {
            return true
        }
        if (current.templateId != activity.templateId || current.startedAt != activity.startedAt) {
            return false
        }
        return !current.contributionRewardQueued && !current.contributionRewardDistributed
    }

    private fun canRecoverContributionOutbox(current: ActiveActivity?, entry: RewardOutboxEntry): Boolean {
        if (current == null) {
            return false
        }
        if (current.templateId != entry.activityTemplateId || current.startedAt != entry.activityStartedAt) {
            return false
        }
        if (current.contributionRewardDistributed) {
            return false
        }
        return current.contributionRewardQueued || current.completed
    }

    private fun ResultSet.readOutboxRow(): RewardOutboxEntry {
        val payload = getString("payload")
        val entry = decodeOutbox(payload)
            ?: throw SQLException("Invalid ServerGoal reward outbox payload")
        entry.attemptCount = getInt("attempt_count")
        entry.lastError = getString("last_error") ?: ""
        return entry
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
                addDataSourceProperty("useSSL", useSsl.toString())
                addDataSourceProperty("requireSSL", requireSsl.toString())
                addDataSourceProperty("verifyServerCertificate", verifyServerCertificate.toString())
                addDataSourceProperty("allowPublicKeyRetrieval", allowPublicKeyRetrieval.toString())
                addDataSourceProperty("cachePrepStmts", "true")
                addDataSourceProperty("prepStmtCacheSize", "250")
                addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            }
            return HikariDataSource(config).also { dataSource = it }
        }
    }

    private fun matchesActivity(reservation: ReservedSubmission, activity: ActiveActivity): Boolean {
        return reservation.activityTemplateId == activity.templateId && reservation.activityStartedAt == activity.startedAt
    }

    private fun encodeActivity(activity: ActiveActivity): String {
        return ActivityCodec.encode(activity).saveToString()
    }

    private fun decodeActivity(payload: String): ActiveActivity? {
        val yaml = YamlConfiguration()
        yaml.loadFromString(payload)
        return ActivityCodec.decode(yaml)
    }

    private fun encodeReservation(reservation: ReservedSubmission): String {
        val yaml = YamlConfiguration()
        yaml.set("id", reservation.id)
        yaml.set("activity.template-id", reservation.activityTemplateId)
        yaml.set("activity.started-at", reservation.activityStartedAt)
        yaml.set("server-id", reservation.serverId)
        yaml.set("player.uuid", reservation.playerId.toString())
        yaml.set("player.name", reservation.playerName)
        yaml.set("total-accepted", reservation.totalAccepted)
        yaml.set("created-at", reservation.createdAt)
        for ((key, amount) in reservation.acceptedByItem) {
            yaml.set("accepted-by-item.$key", amount)
        }
        return yaml.saveToString()
    }

    private fun decodeReservation(payload: String?): ReservedSubmission? {
        if (payload.isNullOrBlank()) {
            return null
        }
        val yaml = YamlConfiguration()
        yaml.loadFromString(payload)
        val acceptedByItem = linkedMapOf<String, Int>()
        yaml.getConfigurationSection("accepted-by-item")?.getKeys(false)?.forEach { key ->
            acceptedByItem[key] = yaml.getInt("accepted-by-item.$key")
        }
        return ReservedSubmission(
            id = yaml.getString("id") ?: return null,
            activityTemplateId = yaml.getString("activity.template-id") ?: return null,
            activityStartedAt = yaml.getLong("activity.started-at"),
            serverId = yaml.getString("server-id", "") ?: "",
            playerId = UUID.fromString(yaml.getString("player.uuid") ?: return null),
            playerName = yaml.getString("player.name", "") ?: "",
            acceptedByItem = acceptedByItem,
            totalAccepted = yaml.getInt("total-accepted"),
            createdAt = yaml.getLong("created-at")
        )
    }

    private fun encodeOutbox(entry: RewardOutboxEntry): String {
        return RewardOutboxCodec.encode(entry).saveToString()
    }

    private fun decodeOutbox(payload: String?): RewardOutboxEntry? {
        if (payload.isNullOrBlank()) {
            return null
        }
        val yaml = YamlConfiguration()
        yaml.loadFromString(payload)
        return RewardOutboxCodec.decode(yaml)
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
