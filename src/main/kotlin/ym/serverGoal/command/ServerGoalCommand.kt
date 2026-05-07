package ym.serverGoal.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ym.serverGoal.ServerGoal
import ym.serverGoal.config.ConfigService
import ym.serverGoal.config.MessageService
import ym.serverGoal.gui.GoalGuiService
import ym.serverGoal.platform.AsyncIoService
import ym.serverGoal.service.ActivityService

class ServerGoalCommand(
    private val plugin: ServerGoal,
    private val io: AsyncIoService,
    private val config: ConfigService,
    private val activity: ActivityService,
    private val gui: GoalGuiService,
    private val messages: MessageService
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "gui", "open" -> openTemplate(sender, args)
            "join", "participate" -> openJoin(sender)
            "top" -> sendTop(sender)
            "history" -> openHistory(sender)
            "rewards" -> openRewards(sender)
            "status" -> sendStatus(sender)
            "reload" -> reload(sender)
            "start", "star" -> start(sender, args)
            "rotate" -> rotate(sender, args)
            "end" -> end(sender)
            "create" -> create(sender, args)
            "additem" -> addItem(sender, args)
            "templates" -> listTemplates(sender)
            else -> sendHelp(sender)
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        return when (args.size) {
            1 -> listOf("open", "join", "top", "history", "status", "reload", "start", "star", "rotate", "end", "create", "additem", "templates")
                .filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> when (args[0].lowercase()) {
                "open", "gui" -> config.templateIds().filter { it.startsWith(args[1], ignoreCase = true) }
                "start", "star", "create", "additem" -> config.templateIds().filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun openMain(sender: CommandSender) {
        val player = sender as? Player ?: run {
            messages.send(sender, "player-only")
            return
        }
        gui.openMain(player)
    }

    private fun openJoin(sender: CommandSender) {
        val activeTemplate = activity.currentTemplate()?.id ?: config.settings.defaultTemplate
        openTemplate(sender, arrayOf("open", activeTemplate))
    }

    private fun openTemplate(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run {
            messages.send(sender, "player-only")
            return
        }
        val templateId = args.getOrNull(1) ?: run {
            messages.send(sender, "usage-open")
            return
        }
        if (config.template(templateId) == null) {
            messages.send(sender, "template-missing", mapOf("template" to templateId))
            return
        }
        gui.openTemplate(player, templateId)
    }

    private fun sendHelp(sender: CommandSender) {
        messages.rawList("messages.usage-root").forEach { line ->
            sender.sendMessage(ym.serverGoal.util.ColorText.colorize(line))
        }
    }

    private fun sendTop(sender: CommandSender) {
        val player = sender as? Player ?: run {
            messages.send(sender, "player-only")
            return
        }
        gui.sendTopToChat(player)
    }

    private fun openRewards(sender: CommandSender) {
        val player = sender as? Player ?: run {
            messages.send(sender, "player-only")
            return
        }
        gui.openRewards(player)
    }

    private fun openHistory(sender: CommandSender) {
        val player = sender as? Player ?: run {
            messages.send(sender, "player-only")
            return
        }
        gui.openHistory(player)
    }

    private fun reload(sender: CommandSender) {
        if (!sender.hasPermission("servergoal.admin.reload")) {
            messages.send(sender, "no-permission")
            return
        }
        plugin.reloadRuntime(sender)
    }

    private fun start(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("servergoal.admin.start")) {
            messages.send(sender, "no-permission")
            return
        }
        val templateId = args.getOrNull(1) ?: config.settings.defaultTemplate
        val minutes = args.getOrNull(2)?.toIntOrNull()
        if (activity.startTemplate(templateId, minutes)) {
            messages.send(sender, "started", mapOf("template" to templateId))
        } else {
            messages.send(sender, "start-failed", mapOf("template" to templateId))
        }
    }

    private fun rotate(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("servergoal.admin.start")) {
            messages.send(sender, "no-permission")
            return
        }
        val minutes = args.getOrNull(1)?.toIntOrNull()
        val selected = activity.startRotatedTemplate(minutes)
        if (selected != null) {
            messages.send(sender, "rotation-started", mapOf("template" to selected))
        } else {
            messages.send(sender, "rotation-start-failed")
        }
    }

    private fun end(sender: CommandSender) {
        if (!sender.hasPermission("servergoal.admin.end")) {
            messages.send(sender, "no-permission")
            return
        }
        if (activity.endActivity(false)) {
            messages.send(sender, "ended")
        } else {
            messages.send(sender, "end-failed")
        }
    }

    private fun create(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("servergoal.admin.create")) {
            messages.send(sender, "no-permission")
            return
        }
        val id = args.getOrNull(1) ?: run {
            messages.send(sender, "usage-create")
            return
        }
        val minutes = args.getOrNull(2)?.toIntOrNull() ?: 60
        val target = args.getOrNull(3)?.toIntOrNull() ?: 100
        io.run("create activity template") {
            config.createTemplate(id, minutes, target)
        }.whenComplete { _, failure ->
            if (failure != null) {
                messages.sendScheduled(
                    sender,
                    "template-create-failed",
                    mapOf("template" to id, "error" to (failure.message ?: failure.javaClass.name))
                )
                return@whenComplete
            }
            messages.sendScheduled(sender, "template-created", mapOf("template" to id))
        }
    }

    private fun addItem(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("servergoal.admin.additem")) {
            messages.send(sender, "no-permission")
            return
        }
        val player = sender as? Player ?: run {
            messages.send(sender, "player-only")
            return
        }
        val id = args.getOrNull(1) ?: run {
            messages.send(sender, "usage-additem")
            return
        }
        val targetAmount = args.getOrNull(2)?.toIntOrNull() ?: 1
        val item = player.inventory.itemInMainHand
        if (item.type.isAir) {
            messages.send(sender, "hold-item")
            return
        }
        val itemSnapshot = item.clone()
        val itemName = item.type.name
        io.supply("add held item to activity template") {
            config.addHeldItemToTemplate(id, itemSnapshot, targetAmount)
        }.whenComplete { key, failure ->
            if (failure != null) {
                messages.sendScheduled(
                    sender,
                    "item-add-failed",
                    mapOf("template" to id, "error" to (failure.message ?: failure.javaClass.name))
                )
                return@whenComplete
            }
            if (key == null) {
                messages.sendScheduled(sender, "template-missing", mapOf("template" to id))
            } else {
                messages.sendScheduled(sender, "item-added", mapOf("template" to id, "item" to itemName, "key" to key))
            }
        }
    }

    private fun sendStatus(sender: CommandSender) {
        val active = activity.activeActivity()
        if (active == null) {
            messages.send(sender, "status-empty")
            return
        }
        val template = activity.currentTemplate()
        messages.send(
            sender,
            "status-line",
            mapOf(
                "activity" to active.displayName,
                "template" to (template?.id ?: active.templateId),
                "state" to activityState(active.active, active.completed),
                "total" to active.totalCollected.toString(),
                "target" to (template?.let { activity.targetTotal(active, it) } ?: 0).toString(),
                "contribution" to (active.contributions[(sender as? Player)?.uniqueId] ?: 0).toString()
            )
        )
    }

    private fun listTemplates(sender: CommandSender) {
        val ids = config.templateIds()
        if (ids.isEmpty()) {
            messages.send(sender, "template-empty")
            return
        }
        messages.send(sender, "template-list", mapOf("templates" to ids.joinToString(", ")))
    }

    private fun activityState(active: Boolean, completed: Boolean): String {
        return when {
            active -> messages.raw("gui.activity-state.running")
            completed -> messages.raw("gui.activity-state.completed")
            else -> messages.raw("gui.activity-state.ended")
        }
    }
}
