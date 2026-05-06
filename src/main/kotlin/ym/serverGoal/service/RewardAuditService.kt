package ym.serverGoal.service

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ym.serverGoal.config.ConfigService
import ym.serverGoal.model.RewardOutboxEntry
import ym.serverGoal.platform.AsyncIoService
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture

class RewardAuditService(
    private val plugin: JavaPlugin,
    private val config: ConfigService,
    private val io: AsyncIoService
) {
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneId.systemDefault())

    fun recordCompletedOutboxAsync(entry: RewardOutboxEntry): CompletableFuture<Unit> {
        return io.supply("write reward audit log") {
            val directory = File(plugin.dataFolder, "data/reward-history")
            directory.mkdirs()
            val timestamp = System.currentTimeMillis()
            val yaml = YamlConfiguration()
            yaml.set("audit.created-at", timestamp)
            yaml.set("audit.created-at-iso", Instant.ofEpochMilli(timestamp).toString())
            yaml.set("audit.server-id", config.settings.serverId)
            yaml.set("outbox.id", entry.id)
            yaml.set("outbox.created-by", entry.createdBy)
            yaml.set("outbox.created-at", entry.createdAt)
            yaml.set("outbox.claimed-by", entry.claimedBy)
            yaml.set("outbox.claimed-at", entry.claimedAt)
            yaml.set("outbox.attempt-count", entry.attemptCount)
            yaml.set("activity.template-id", entry.activityTemplateId)
            yaml.set("activity.started-at", entry.activityStartedAt)
            yaml.set("commands.total", entry.commands.size)
            yaml.set("commands.executed-count", entry.executedCommandCount)
            yaml.set("commands.entries", entry.commands)
            yaml.set("broadcast.message-key", entry.broadcastMessageKey)
            yaml.set("broadcast.sent", entry.broadcastSent)
            for ((key, value) in entry.broadcastPlaceholders) {
                yaml.set("broadcast.placeholders.$key", value)
            }
            yaml.set("last-error", entry.lastError)

            val safeId = entry.id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val file = File(directory, "${formatter.format(Instant.ofEpochMilli(timestamp))}-$safeId.yml")
            yaml.save(file)
        }
    }
}
