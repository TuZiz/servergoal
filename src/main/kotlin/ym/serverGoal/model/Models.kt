package ym.serverGoal.model

import org.bukkit.inventory.ItemStack
import java.util.UUID

data class PluginSettings(
    val serverId: String = "server-1",
    val debug: Boolean = false,
    val failOpenOnDatabaseError: Boolean = true,
    val protectionEnabled: Boolean,
    val adminTestMode: Boolean,
    val endWhenFinalStageComplete: Boolean,
    val saveOnSubmit: Boolean,
    val schedulerCheckSeconds: Long,
    val defaultTemplate: String,
    val notifications: NotificationSettings = NotificationSettings(),
    val rotation: RotationSettings = RotationSettings(),
    val submission: SubmissionSettings = SubmissionSettings(),
    val storage: StorageSettings = StorageSettings(),
    val database: DatabaseSettings = DatabaseSettings(),
    val sync: SyncSettings = SyncSettings()
) {
    val databaseStorageEnabled: Boolean
        get() = storage.type == "database"
}

data class NotificationSettings(
    val progress: ProgressNotificationSettings = ProgressNotificationSettings()
)

data class ProgressNotificationSettings(
    val enabled: Boolean = true,
    val intervalSeconds: Long = 300L
)

data class StorageSettings(
    val type: String = "yaml",
    val databaseFailureStrategy: String = "fail-fast"
) {
    val fallbackYamlOnDatabaseFailure: Boolean
        get() = databaseFailureStrategy == "fallback-yaml"
}

data class SubmissionSettings(
    val cooldownSeconds: Long = 2L,
    val maxItemsPerSubmit: Int = 2304
)

data class RotationSettings(
    val enabled: Boolean = false,
    val autoStart: Boolean = false,
    val intervalDays: Int = 7,
    val checkIntervalSeconds: Long = 300L,
    val pool: List<String> = emptyList()
)

data class DatabaseSettings(
    val type: String = "mysql",
    val host: String = "127.0.0.1",
    val port: Int = 3306,
    val database: String = "servergoal",
    val username: String = "",
    val password: String = "",
    val tablePrefix: String = "servergoal_",
    val jdbcUrl: String = "",
    val useSsl: Boolean = false,
    val requireSsl: Boolean = false,
    val verifyServerCertificate: Boolean = false,
    val allowPublicKeyRetrieval: Boolean = true,
    val pool: DatabasePoolSettings = DatabasePoolSettings()
) {
    fun activityStateTableName(): String = "${tablePrefix}activity_state"

    fun submissionReservationTableName(): String = "${tablePrefix}submission_reservation"

    fun rewardOutboxTableName(): String = "${tablePrefix}reward_outbox"

    fun serverHeartbeatTableName(): String = "${tablePrefix}server_heartbeat"

    fun effectiveJdbcUrl(): String {
        if (jdbcUrl.isNotBlank()) {
            return jdbcUrl
        }
        return when (type) {
            "mysql", "mariadb" -> "jdbc:mysql://$host:$port/$database"
            else -> ""
        }
    }
}

data class DatabasePoolSettings(
    val maximumPoolSize: Int = 4,
    val minimumIdle: Int = 1,
    val connectionTimeoutMs: Long = 30_000L,
    val maxLifetimeMs: Long = 1_800_000L
)

data class SyncSettings(
    val pollIntervalTicks: Long = 20L,
    val retryIntervalSeconds: Long = 10L,
    val maxRetries: Int = 3,
    val conflictPolicy: String = "merge-max",
    val eventRetentionDays: Int = 7,
    val processOwnEvents: Boolean = false,
    val submissionReservationExpireSeconds: Long = 45L,
    val outboxClaimTimeoutSeconds: Long = 45L,
    val onlineHeartbeatExpireSeconds: Long = 90L
)

data class MatchRule(
    val material: Boolean = true,
    val materials: List<String> = emptyList(),
    val itemMeta: Boolean = false,
    val displayName: Boolean = false,
    val customModelData: Boolean = true,
    val itemModel: Boolean = true
)

data class CollectionItem(
    val key: String,
    val displayName: String,
    val targetAmount: Int,
    val displayItem: ItemStack,
    val matchItem: ItemStack,
    val matchRule: MatchRule,
    val activityLore: List<String> = emptyList()
)

data class StageDefinition(
    val index: Int,
    val threshold: Int,
    val displayName: String,
    val minContribution: Int,
    val displayItem: ItemStack,
    val lore: List<String>,
    val commands: List<String>
)

data class PersonalRewardDefinition(
    val id: String,
    val threshold: Int,
    val displayName: String,
    val displayItem: ItemStack,
    val lore: List<String>,
    val commands: List<String>
)

data class ContributionRewardDefinition(
    val enabled: Boolean = true,
    val poolAmount: Int,
    val minContribution: Int = 0,
    val commands: List<String>,
    val broadcastMessageKey: String = "activity-contribution-distributed"
)

data class DynamicTargetSettings(
    val enabled: Boolean = false,
    val basePlayers: Int = 20,
    val minMultiplier: Double = 1.0,
    val maxMultiplier: Double = 1.0
)

data class ReservedSubmission(
    val id: String,
    val activityTemplateId: String,
    val activityStartedAt: Long,
    val serverId: String,
    val playerId: UUID,
    val playerName: String,
    val acceptedByItem: Map<String, Int>,
    val totalAccepted: Int,
    val createdAt: Long
)

data class CommittedReservationResult(
    val activity: ActiveActivity?,
    val committed: Boolean
)

data class RewardOutboxEntry(
    val id: String,
    val activityTemplateId: String,
    val activityStartedAt: Long,
    val createdBy: String,
    val createdAt: Long,
    val commands: List<String>,
    val broadcastMessageKey: String,
    val broadcastPlaceholders: Map<String, String>,
    var executedCommandCount: Int = 0,
    var broadcastSent: Boolean = false,
    var claimedBy: String = "",
    var claimedAt: Long = 0L,
    var completedAt: Long = 0L,
    var attemptCount: Int = 0,
    var lastError: String = ""
)

data class ActivityTemplate(
    val id: String,
    val displayName: String,
    val durationMinutes: Int,
    val targetTotal: Int,
    val dynamicTarget: DynamicTargetSettings = DynamicTargetSettings(),
    val acceptedItems: List<CollectionItem>,
    val stages: List<StageDefinition>,
    val personalRewards: List<PersonalRewardDefinition>,
    val contributionReward: ContributionRewardDefinition? = null
)

data class ActiveActivity(
    val templateId: String,
    val displayName: String,
    val startedAt: Long,
    var endsAt: Long,
    var active: Boolean,
    var completed: Boolean,
    var effectiveTargetTotal: Int,
    val effectiveItemTargets: MutableMap<String, Int> = linkedMapOf(),
    val effectiveStageThresholds: MutableMap<Int, Int> = linkedMapOf(),
    var dynamicTargetPlayers: Int = 0,
    var dynamicTargetMultiplier: Double = 1.0,
    var totalCollected: Int,
    var unlockedStage: Int,
    val collectedByItem: MutableMap<String, Int> = linkedMapOf(),
    val contributions: MutableMap<UUID, Int> = linkedMapOf(),
    val serverCollectedByItem: MutableMap<String, MutableMap<String, Int>> = linkedMapOf(),
    val serverContributions: MutableMap<String, MutableMap<UUID, Int>> = linkedMapOf(),
    val playerNames: MutableMap<UUID, String> = linkedMapOf(),
    val claimedStageRewards: MutableMap<UUID, MutableSet<Int>> = linkedMapOf(),
    val claimedPersonalRewards: MutableMap<UUID, MutableSet<String>> = linkedMapOf(),
    var contributionRewardQueued: Boolean = false,
    var contributionRewardQueuedBy: String = "",
    var contributionRewardQueuedAt: Long = 0L,
    var contributionRewardDistributed: Boolean = false,
    var contributionRewardDistributedBy: String = "",
    var contributionRewardDistributedAt: Long = 0L,
    var revision: Long = 0L,
    var updatedAt: Long = System.currentTimeMillis(),
    var updatedBy: String = ""
) {
    fun contributionOf(uuid: UUID): Int = contributions[uuid] ?: 0
}

data class SubmissionResult(
    val success: Boolean,
    val amount: Int = 0,
    val messageKey: String,
    val placeholders: Map<String, String> = emptyMap()
)

data class ClaimResult(
    val success: Boolean,
    val messageKey: String,
    val commands: List<String> = emptyList(),
    val placeholders: Map<String, String> = emptyMap()
)

data class ActivityHistoryEntry(
    val id: String,
    val activity: String,
    val templateId: String,
    val startedAt: Long,
    val endedAt: Long,
    val completed: Boolean,
    val totalCollected: Int,
    val targetTotal: Int,
    val participants: Int,
    val topPlayers: List<Pair<String, Int>>,
    val rewardPool: Int,
    val rewardEligiblePlayers: Int,
    val rewardMinContribution: Int
)
