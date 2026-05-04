package ym.serverGoal.service

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ym.serverGoal.platform.PlatformScheduler

class RewardService(private val scheduler: PlatformScheduler) {
    fun execute(player: Player, commands: List<String>, placeholders: Map<String, String>) {
        executeConsole(commands, placeholders + mapOf("player" to player.name))
    }

    fun executeConsole(commands: List<String>, placeholders: Map<String, String>) {
        for (command in commands) {
            val resolved = apply(command, placeholders)
            if (resolved.isBlank()) {
                continue
            }
            scheduler.runGlobal {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved.removePrefix("/"))
            }
        }
    }

    private fun apply(command: String, placeholders: Map<String, String>): String {
        var result = command
        for ((key, value) in placeholders) {
            result = result.replace("%$key%", value)
        }
        return result
    }
}
