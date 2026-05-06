package ym.serverGoal.config

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ComponentBuilder
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import ym.serverGoal.platform.PlatformScheduler
import ym.serverGoal.util.ColorText
import java.util.concurrent.TimeUnit

class MessageService(
    private val scheduler: PlatformScheduler,
    private val resources: ResourceService
) {
    @Volatile
    private var lang: YamlConfiguration = YamlConfiguration()

    @Synchronized
    fun reload() {
        lang = resources.loadMerged("lang/zh_cn.yml")
    }

    fun raw(path: String, placeholders: Map<String, String> = emptyMap()): String {
        val snapshot = lang
        val template = snapshot.getString(path)
            ?: snapshot.getString("messages.missing-language-text")
            ?.replace("%key%", path)
            ?: path
        return ColorText.applyPlaceholders(template, placeholders)
    }

    fun rawList(path: String, placeholders: Map<String, String> = emptyMap()): List<String> {
        val snapshot = lang
        return snapshot.getStringList(path).map { ColorText.applyPlaceholders(it, placeholders) }
    }

    fun text(key: String, placeholders: Map<String, String> = emptyMap()): String {
        return ColorText.colorize(raw("messages.$key", placeholders))
    }

    fun list(path: String, placeholders: Map<String, String> = emptyMap()): List<String> {
        return ColorText.renderList(rawList(path, placeholders))
    }

    fun send(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        sender.sendMessage(text(key, placeholders))
    }

    fun sendScheduled(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        val player = sender as? Player
        if (player != null) {
            sendPlayer(player, key, placeholders)
            return
        }
        scheduler.runGlobal {
            sender.sendMessage(text(key, placeholders))
        }
    }

    fun broadcast(key: String, placeholders: Map<String, String> = emptyMap()) {
        val message = text(key, placeholders)
        scheduler.runGlobal {
            Bukkit.getConsoleSender().sendMessage(message)
            for (player in Bukkit.getOnlinePlayers()) {
                scheduler.runForPlayer(player) {
                    player.sendMessage(message)
                }
            }
        }
    }

    fun broadcastLines(key: String, placeholders: Map<String, String> = emptyMap()) {
        val lines = rawList("messages.$key", placeholders).map(ColorText::colorize)
        if (lines.isEmpty()) {
            return
        }
        scheduler.runGlobal {
            for (line in lines) {
                Bukkit.getConsoleSender().sendMessage(line)
            }
            for (player in Bukkit.getOnlinePlayers()) {
                scheduler.runForPlayer(player) {
                    for (line in lines) {
                        player.sendMessage(line)
                    }
                }
            }
        }
    }

    fun broadcastClickableLines(
        key: String,
        placeholders: Map<String, String> = emptyMap(),
        command: String,
        hoverKey: String
    ) {
        val lines = rawList("messages.$key", placeholders).map(ColorText::colorize)
        if (lines.isEmpty()) {
            return
        }
        val hover = ColorText.colorize(raw("messages.$hoverKey", placeholders))
        scheduler.runGlobal {
            for (line in lines) {
                Bukkit.getConsoleSender().sendMessage(line)
            }
            for (player in Bukkit.getOnlinePlayers()) {
                scheduler.runForPlayer(player) {
                    for (line in lines) {
                        val components = TextComponent.fromLegacyText(line)
                        for (component in components) {
                            component.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, command)
                            component.hoverEvent = HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                ComponentBuilder(hover).create()
                            )
                        }
                        player.spigot().sendMessage(*components)
                    }
                }
            }
        }
    }

    fun sendPlayer(player: Player, key: String, placeholders: Map<String, String> = emptyMap()) {
        scheduler.runForPlayer(player) {
            player.sendMessage(text(key, placeholders))
        }
    }

    fun formatDuration(millis: Long): String {
        if (millis <= 0L) {
            return raw("time.ended")
        }
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (hours > 0) {
            raw("time.hours", mapOf("hours" to hours.toString(), "minutes" to minutes.toString()))
        } else if (minutes > 0) {
            raw("time.minutes", mapOf("minutes" to minutes.toString(), "seconds" to seconds.toString()))
        } else {
            raw("time.seconds", mapOf("seconds" to seconds.toString()))
        }
    }
}
