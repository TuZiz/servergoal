package ym.serverGoal.storage

import org.bukkit.configuration.file.YamlConfiguration
import ym.serverGoal.model.RewardOutboxEntry

object RewardOutboxCodec {
    fun encode(entry: RewardOutboxEntry): YamlConfiguration {
        val yaml = YamlConfiguration()
        yaml.set("id", entry.id)
        yaml.set("activity.template-id", entry.activityTemplateId)
        yaml.set("activity.started-at", entry.activityStartedAt)
        yaml.set("created-by", entry.createdBy)
        yaml.set("created-at", entry.createdAt)
        yaml.set("commands", entry.commands)
        yaml.set("broadcast.message-key", entry.broadcastMessageKey)
        for ((key, value) in entry.broadcastPlaceholders) {
            yaml.set("broadcast.placeholders.$key", value)
        }
        yaml.set("executed-command-count", entry.executedCommandCount)
        yaml.set("broadcast-sent", entry.broadcastSent)
        yaml.set("claimed-by", entry.claimedBy)
        yaml.set("claimed-at", entry.claimedAt)
        yaml.set("completed-at", entry.completedAt)
        yaml.set("attempt-count", entry.attemptCount)
        yaml.set("last-error", entry.lastError)
        return yaml
    }

    fun decode(yaml: YamlConfiguration): RewardOutboxEntry? {
        val id = yaml.getString("id") ?: return null
        val placeholders = linkedMapOf<String, String>()
        yaml.getConfigurationSection("broadcast.placeholders")?.getKeys(false)?.forEach { key ->
            placeholders[key] = yaml.getString("broadcast.placeholders.$key", "") ?: ""
        }
        return RewardOutboxEntry(
            id = id,
            activityTemplateId = yaml.getString("activity.template-id") ?: return null,
            activityStartedAt = yaml.getLong("activity.started-at"),
            createdBy = yaml.getString("created-by", "") ?: "",
            createdAt = yaml.getLong("created-at"),
            commands = yaml.getStringList("commands"),
            broadcastMessageKey = yaml.getString("broadcast.message-key", "") ?: "",
            broadcastPlaceholders = placeholders,
            executedCommandCount = yaml.getInt("executed-command-count", 0),
            broadcastSent = yaml.getBoolean("broadcast-sent", false),
            claimedBy = yaml.getString("claimed-by", "") ?: "",
            claimedAt = yaml.getLong("claimed-at", 0L),
            completedAt = yaml.getLong("completed-at", 0L),
            attemptCount = yaml.getInt("attempt-count", 0),
            lastError = yaml.getString("last-error", "") ?: ""
        )
    }
}
