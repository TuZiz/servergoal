package ym.serverGoal.platform

import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AsyncIoService(private val plugin: JavaPlugin) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "${plugin.name}-IO").apply {
            isDaemon = false
            priority = Thread.NORM_PRIORITY
        }
    }

    fun <T> supply(operation: String, task: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        executor.execute {
            try {
                MainThreadIoGuard.reject(operation)
                future.complete(task())
            } catch (failure: Throwable) {
                plugin.logger.warning(
                    "ServerGoal async IO failed during $operation: ${failure.message ?: failure.javaClass.name}"
                )
                future.completeExceptionally(failure)
            }
        }
        return future
    }

    fun run(operation: String, task: () -> Unit): CompletableFuture<Unit> {
        return supply(operation) {
            task()
            Unit
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
