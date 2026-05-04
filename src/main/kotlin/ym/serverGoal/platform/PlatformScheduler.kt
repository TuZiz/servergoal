package ym.serverGoal.platform

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.function.Consumer

class PlatformScheduler(private val plugin: JavaPlugin) {
    private val globalScheduler: Any? = runCatching {
        Bukkit.getServer().javaClass.getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer())
    }.getOrNull()

    fun runGlobal(task: () -> Unit) {
        val scheduler = globalScheduler
        if (scheduler != null) {
            val executed = runCatching {
                val method = scheduler.javaClass.methods.firstOrNull {
                    it.name == "execute" && it.parameterCount == 2
                } ?: return@runCatching false
                method.invoke(scheduler, plugin, Runnable { task() })
                true
            }.getOrDefault(false)
            if (executed) {
                return
            }
            runCatching {
                val method = scheduler.javaClass.methods.firstOrNull {
                    it.name == "run" && it.parameterCount == 2
                } ?: return@runCatching false
                method.invoke(scheduler, plugin, Consumer<Any> { task() })
                true
            }.getOrDefault(false).also {
                if (it) return
            }
        }
        Bukkit.getScheduler().runTask(plugin, Runnable { task() })
    }

    fun runRepeating(initialDelayTicks: Long, periodTicks: Long, task: () -> Unit): TaskHandle {
        val scheduler = globalScheduler
        if (scheduler != null) {
            val foliaTask = runCatching {
                val method = scheduler.javaClass.methods.firstOrNull {
                    it.name == "runAtFixedRate" && it.parameterCount == 4
                } ?: return@runCatching null
                method.invoke(
                    scheduler,
                    plugin,
                    Consumer<Any> { task() },
                    initialDelayTicks,
                    periodTicks
                )
            }.getOrNull()
            if (foliaTask != null) {
                return ReflectiveTaskHandle(foliaTask)
            }
        }
        val bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { task() }, initialDelayTicks, periodTicks)
        return BukkitTaskHandle(bukkitTask)
    }

    fun runForPlayer(player: Player, task: () -> Unit) {
        val entityScheduler = runCatching {
            player.javaClass.getMethod("getScheduler").invoke(player)
        }.getOrNull()
        if (entityScheduler != null) {
            val scheduled = runCatching {
                val method = entityScheduler.javaClass.methods.firstOrNull {
                    it.name == "run" && it.parameterCount == 3
                } ?: return@runCatching false
                method.invoke(entityScheduler, plugin, Consumer<Any> { task() }, null)
                true
            }.getOrDefault(false)
            if (scheduled) {
                return
            }
            val executed = runCatching {
                val method = entityScheduler.javaClass.methods.firstOrNull {
                    it.name == "execute" && it.parameterCount == 4
                } ?: return@runCatching false
                method.invoke(entityScheduler, plugin, Runnable { task() }, null, 1L)
                true
            }.getOrDefault(false)
            if (executed) {
                return
            }
        }
        runGlobal(task)
    }
}

interface TaskHandle {
    fun cancel()
}

private class BukkitTaskHandle(private val task: BukkitTask) : TaskHandle {
    override fun cancel() {
        task.cancel()
    }
}

private class ReflectiveTaskHandle(private val task: Any) : TaskHandle {
    override fun cancel() {
        runCatching {
            task.javaClass.getMethod("cancel").invoke(task)
        }
    }
}
