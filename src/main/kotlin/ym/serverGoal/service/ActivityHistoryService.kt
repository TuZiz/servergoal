package ym.serverGoal.service

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ym.serverGoal.model.ActiveActivity
import ym.serverGoal.model.ActivityHistoryEntry
import ym.serverGoal.model.ActivityTemplate
import ym.serverGoal.platform.AsyncIoService
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture

class ActivityHistoryService(
    private val plugin: JavaPlugin,
    private val io: AsyncIoService
) {
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneId.systemDefault())

    fun recordAsync(activity: ActiveActivity, template: ActivityTemplate?, completed: Boolean): CompletableFuture<Unit> {
        val snapshot = ym.serverGoal.storage.ActivityCodec.decode(ym.serverGoal.storage.ActivityCodec.encode(activity))
            ?: return CompletableFuture.completedFuture(Unit)
        val templateSnapshot = template
        return io.supply("write activity history") {
            val directory = historyDirectory()
            directory.mkdirs()
            val reward = templateSnapshot?.contributionReward
            val minContribution = reward?.minContribution ?: 0
            val eligible = snapshot.contributions.values.count { it >= minContribution && it > 0 }
            val top = snapshot.contributions.entries
                .sortedByDescending { it.value }
                .take(10)
                .map { (uuid, amount) -> (snapshot.playerNames[uuid] ?: uuid.toString()) to amount }
            val endedAt = System.currentTimeMillis()
            val id = "${snapshot.templateId}-${snapshot.startedAt}"
            val yaml = YamlConfiguration()
            yaml.set("id", id)
            yaml.set("activity", snapshot.displayName)
            yaml.set("template-id", snapshot.templateId)
            yaml.set("started-at", snapshot.startedAt)
            yaml.set("started-at-iso", Instant.ofEpochMilli(snapshot.startedAt).toString())
            yaml.set("ended-at", endedAt)
            yaml.set("ended-at-iso", Instant.ofEpochMilli(endedAt).toString())
            yaml.set("completed", completed)
            yaml.set("total-collected", snapshot.totalCollected)
            yaml.set("target-total", snapshot.effectiveTargetTotal)
            yaml.set("participants", snapshot.contributions.values.count { it > 0 })
            yaml.set("reward.pool", reward?.poolAmount ?: 0)
            yaml.set("reward.min-contribution", minContribution)
            yaml.set("reward.eligible-players", eligible)
            top.forEachIndexed { index, entry ->
                val path = "top.${index + 1}"
                yaml.set("$path.player", entry.first)
                yaml.set("$path.amount", entry.second)
            }
            val safeId = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            yaml.save(File(directory, "${formatter.format(Instant.ofEpochMilli(endedAt))}-$safeId.yml"))
        }
    }

    fun recentAsync(limit: Int = 45): CompletableFuture<List<ActivityHistoryEntry>> {
        return io.supply("read activity history") { recent(limit) }
    }

    fun recent(limit: Int = 45): List<ActivityHistoryEntry> {
        val directory = historyDirectory()
        if (!directory.isDirectory) {
            return emptyList()
        }
        return directory.listFiles { file -> file.isFile && file.extension.equals("yml", ignoreCase = true) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .take(limit.coerceAtLeast(1))
            .mapNotNull { file -> load(file) }
    }

    private fun load(file: File): ActivityHistoryEntry? {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val top = yaml.getConfigurationSection("top")?.getKeys(false)
            ?.mapNotNull { key ->
                val player = yaml.getString("top.$key.player") ?: return@mapNotNull null
                player to yaml.getInt("top.$key.amount")
            }
            ?: emptyList()
        return ActivityHistoryEntry(
            id = yaml.getString("id", file.nameWithoutExtension) ?: file.nameWithoutExtension,
            activity = yaml.getString("activity", "") ?: "",
            templateId = yaml.getString("template-id", "") ?: "",
            startedAt = yaml.getLong("started-at", 0L),
            endedAt = yaml.getLong("ended-at", file.lastModified()),
            completed = yaml.getBoolean("completed", false),
            totalCollected = yaml.getInt("total-collected", 0),
            targetTotal = yaml.getInt("target-total", 0),
            participants = yaml.getInt("participants", 0),
            topPlayers = top,
            rewardPool = yaml.getInt("reward.pool", 0),
            rewardEligiblePlayers = yaml.getInt("reward.eligible-players", 0),
            rewardMinContribution = yaml.getInt("reward.min-contribution", 0)
        )
    }

    private fun historyDirectory(): File = File(plugin.dataFolder, "data/activity-history")
}
