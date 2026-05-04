package ym.serverGoal

import org.bukkit.plugin.java.JavaPlugin
import ym.serverGoal.command.ServerGoalCommand
import ym.serverGoal.config.ConfigService
import ym.serverGoal.config.MessageService
import ym.serverGoal.config.ResourceService
import ym.serverGoal.gui.GoalGuiService
import ym.serverGoal.listener.GuiListener
import ym.serverGoal.platform.AsyncIoService
import ym.serverGoal.platform.PlatformScheduler
import ym.serverGoal.platform.TaskHandle
import ym.serverGoal.service.ActivityService
import ym.serverGoal.service.ItemMatcher
import ym.serverGoal.service.RewardService
import ym.serverGoal.storage.ActivityStorage

class ServerGoal : JavaPlugin() {
    private lateinit var scheduler: PlatformScheduler
    private lateinit var io: AsyncIoService
    private lateinit var resourceService: ResourceService
    private lateinit var configService: ConfigService
    private lateinit var messageService: MessageService
    private lateinit var activityStorage: ActivityStorage
    private lateinit var activityService: ActivityService
    private lateinit var rewardService: RewardService
    private lateinit var guiService: GoalGuiService
    private lateinit var command: ServerGoalCommand
    private var timerTask: TaskHandle? = null
    private var listenersRegistered: Boolean = false

    override fun onEnable() {
        scheduler = PlatformScheduler(this)
        io = AsyncIoService(this)
        resourceService = ResourceService(this)
        messageService = MessageService(scheduler, resourceService)
        configService = ConfigService(resourceService, messageService)
        activityStorage = ActivityStorage(this, configService, messageService, io)
        rewardService = RewardService(scheduler)
        activityService = ActivityService(configService, messageService, activityStorage, ItemMatcher(), rewardService)
        guiService = GoalGuiService(activityService, messageService, resourceService)
        command = ServerGoalCommand(this, io, configService, activityService, guiService, messageService)
        bootstrapRuntime()
    }

    override fun onDisable() {
        timerTask?.cancel()
        val saveFuture = if (this::activityService.isInitialized) {
            activityService.saveAsync()
        } else {
            null
        }
        saveFuture?.whenComplete { _, _ ->
            if (this::activityStorage.isInitialized) {
                activityStorage.shutdown()
            }
        }
        if (saveFuture == null && this::activityStorage.isInitialized) {
            activityStorage.shutdown()
        }
        if (this::io.isInitialized) {
            io.shutdown()
        }
    }

    fun reloadRuntime(sender: org.bukkit.command.CommandSender? = null) {
        io.run("ServerGoal runtime reload") {
            messageService.reload()
            configService.reload()
            guiService.reload()
        }.thenCompose {
            activityService.loadAsync()
        }.whenComplete { _, failure ->
            scheduler.runGlobal {
                if (!isEnabled) {
                    return@runGlobal
                }
                if (failure != null) {
                    logger.severe("ServerGoal reload failed: ${failure.message ?: failure.javaClass.name}")
                    sender?.let { messageService.sendScheduled(it, "reload-failed", mapOf("error" to (failure.message ?: failure.javaClass.name))) }
                    return@runGlobal
                }
                registerRuntime()
                restartTimer()
                sender?.let { messageService.sendScheduled(it, "reloaded") }
            }
        }
    }

    private fun bootstrapRuntime() {
        io.run("ServerGoal bootstrap") {
            resourceService.releaseDefaults()
            messageService.reload()
            configService.reload()
            guiService.reload()
        }.thenCompose {
            activityService.loadAsync()
        }.whenComplete { _, failure ->
            scheduler.runGlobal {
                if (!isEnabled) {
                    return@runGlobal
                }
                if (failure != null) {
                    logger.severe("ServerGoal bootstrap failed: ${failure.message ?: failure.javaClass.name}")
                    server.pluginManager.disablePlugin(this)
                    return@runGlobal
                }
                registerRuntime()
                restartTimer()
                logger.info(
                    messageService.raw(
                        "console.enabled",
                        mapOf("templates" to configService.templateIds().joinToString(","))
                    )
                )
            }
        }
    }

    private fun registerRuntime() {
        val registeredCommand = getCommand("servergoal") ?: return
        registeredCommand.setExecutor(command)
        registeredCommand.tabCompleter = command

        if (!listenersRegistered) {
            server.pluginManager.registerEvents(
                GuiListener(guiService, activityService, rewardService, messageService),
                this
            )
            listenersRegistered = true
        }
    }

    private fun restartTimer() {
        timerTask?.cancel()
        val schedulerTicks = configService.settings.schedulerCheckSeconds.coerceAtLeast(5L) * 20L
        val periodTicks = if (configService.settings.databaseStorageEnabled) {
            minOf(schedulerTicks, configService.settings.sync.pollIntervalTicks)
        } else {
            schedulerTicks
        }
        timerTask = scheduler.runRepeating(periodTicks, periodTicks) {
            activityService.synchronizeAsync()
            activityService.checkTimer()
        }
    }
}
