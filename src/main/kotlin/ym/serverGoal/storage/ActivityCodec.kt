package ym.serverGoal.storage

import org.bukkit.configuration.file.YamlConfiguration
import ym.serverGoal.model.ActiveActivity
import java.util.UUID

object ActivityCodec {
    fun encode(activity: ActiveActivity?): YamlConfiguration {
        val yaml = YamlConfiguration()
        if (activity == null) {
            return yaml
        }
        yaml.set("active.template-id", activity.templateId)
        yaml.set("active.display-name", activity.displayName)
        yaml.set("active.started-at", activity.startedAt)
        yaml.set("active.ends-at", activity.endsAt)
        yaml.set("active.active", activity.active)
        yaml.set("active.completed", activity.completed)
        yaml.set("active.total-collected", activity.totalCollected)
        yaml.set("active.unlocked-stage", activity.unlockedStage)
        yaml.set("active.contribution-reward-queued", activity.contributionRewardQueued)
        yaml.set("active.contribution-reward-queued-by", activity.contributionRewardQueuedBy)
        yaml.set("active.contribution-reward-queued-at", activity.contributionRewardQueuedAt)
        yaml.set("active.contribution-reward-distributed", activity.contributionRewardDistributed)
        yaml.set("active.contribution-reward-distributed-by", activity.contributionRewardDistributedBy)
        yaml.set("active.contribution-reward-distributed-at", activity.contributionRewardDistributedAt)
        yaml.set("active.revision", activity.revision)
        yaml.set("active.updated-at", activity.updatedAt)
        yaml.set("active.updated-by", activity.updatedBy)
        for ((key, amount) in activity.collectedByItem) {
            yaml.set("active.collected-items.$key", amount)
        }
        for ((serverId, collectedItems) in activity.serverCollectedByItem) {
            for ((key, amount) in collectedItems) {
                yaml.set("active.server-collected-items.$serverId.$key", amount)
            }
        }
        for ((uuid, amount) in activity.contributions) {
            yaml.set("active.contributions.$uuid", amount)
        }
        for ((serverId, contributions) in activity.serverContributions) {
            for ((uuid, amount) in contributions) {
                yaml.set("active.server-contributions.$serverId.$uuid", amount)
            }
        }
        for ((uuid, name) in activity.playerNames) {
            yaml.set("active.player-names.$uuid", name)
        }
        for ((uuid, stages) in activity.claimedStageRewards) {
            yaml.set("active.claimed-stage.$uuid", stages.sorted())
        }
        for ((uuid, rewards) in activity.claimedPersonalRewards) {
            yaml.set("active.claimed-personal.$uuid", rewards.sorted())
        }
        return yaml
    }

    fun decode(yaml: YamlConfiguration): ActiveActivity? {
        if (!yaml.contains("active.template-id")) {
            return null
        }
        val activity = ActiveActivity(
            templateId = yaml.getString("active.template-id") ?: return null,
            displayName = yaml.getString("active.display-name") ?: "",
            startedAt = yaml.getLong("active.started-at"),
            endsAt = yaml.getLong("active.ends-at"),
            active = yaml.getBoolean("active.active", false),
            completed = yaml.getBoolean("active.completed", false),
            totalCollected = yaml.getInt("active.total-collected"),
            unlockedStage = yaml.getInt("active.unlocked-stage"),
            contributionRewardQueued = yaml.getBoolean("active.contribution-reward-queued", false),
            contributionRewardQueuedBy = yaml.getString("active.contribution-reward-queued-by", "") ?: "",
            contributionRewardQueuedAt = yaml.getLong("active.contribution-reward-queued-at", 0L),
            contributionRewardDistributed = yaml.getBoolean("active.contribution-reward-distributed", false),
            contributionRewardDistributedBy = yaml.getString("active.contribution-reward-distributed-by", "") ?: "",
            contributionRewardDistributedAt = yaml.getLong("active.contribution-reward-distributed-at", 0L),
            revision = yaml.getLong("active.revision", 0L),
            updatedAt = yaml.getLong("active.updated-at", 0L),
            updatedBy = yaml.getString("active.updated-by", "") ?: ""
        )

        yaml.getConfigurationSection("active.collected-items")?.getKeys(false)?.forEach { key ->
            activity.collectedByItem[key] = yaml.getInt("active.collected-items.$key")
        }
        yaml.getConfigurationSection("active.contributions")?.getKeys(false)?.forEach { raw ->
            val uuid = runCatching { UUID.fromString(raw) }.getOrNull() ?: return@forEach
            activity.contributions[uuid] = yaml.getInt("active.contributions.$raw")
        }
        yaml.getConfigurationSection("active.server-collected-items")?.getKeys(false)?.forEach { serverId ->
            val serverSection = yaml.getConfigurationSection("active.server-collected-items.$serverId") ?: return@forEach
            val items = activity.serverCollectedByItem.getOrPut(serverId) { linkedMapOf() }
            serverSection.getKeys(false).forEach { key ->
                items[key] = yaml.getInt("active.server-collected-items.$serverId.$key")
            }
        }
        yaml.getConfigurationSection("active.server-contributions")?.getKeys(false)?.forEach { serverId ->
            val serverSection = yaml.getConfigurationSection("active.server-contributions.$serverId") ?: return@forEach
            val contributions = activity.serverContributions.getOrPut(serverId) { linkedMapOf() }
            serverSection.getKeys(false).forEach { raw ->
                val uuid = runCatching { UUID.fromString(raw) }.getOrNull() ?: return@forEach
                contributions[uuid] = yaml.getInt("active.server-contributions.$serverId.$raw")
            }
        }
        yaml.getConfigurationSection("active.player-names")?.getKeys(false)?.forEach { raw ->
            val uuid = runCatching { UUID.fromString(raw) }.getOrNull() ?: return@forEach
            activity.playerNames[uuid] = yaml.getString("active.player-names.$raw") ?: uuid.toString()
        }
        yaml.getConfigurationSection("active.claimed-stage")?.getKeys(false)?.forEach { raw ->
            val uuid = runCatching { UUID.fromString(raw) }.getOrNull() ?: return@forEach
            activity.claimedStageRewards[uuid] = yaml.getIntegerList("active.claimed-stage.$raw").toMutableSet()
        }
        yaml.getConfigurationSection("active.claimed-personal")?.getKeys(false)?.forEach { raw ->
            val uuid = runCatching { UUID.fromString(raw) }.getOrNull() ?: return@forEach
            activity.claimedPersonalRewards[uuid] = yaml.getStringList("active.claimed-personal.$raw").toMutableSet()
        }
        return activity
    }
}
