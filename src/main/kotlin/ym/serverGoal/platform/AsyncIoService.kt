package ym.serverGoal.platform

import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AsyncIoService(private val plugin: JavaPlugin) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "${plugin.name}-IO").apply {
            isDaemon = false
            priority = Thread.NORM_PRIORITY
        }
    }
    private val shuttingDown = AtomicBoolean(false)
    private val inFlight = AtomicInteger(0)

    fun <T> supply(operation: String, task: () -> T): CompletableFuture<T> {
        if (shuttingDown.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("ServerGoal IO service is shutting down"))
        }
        val future = CompletableFuture<T>()
        inFlight.incrementAndGet()
        executor.execute {
            try {
                MainThreadIoGuard.reject(operation)
                future.complete(task())
            } catch (failure: Throwable) {
                plugin.logger.warning(
                    "ServerGoal async IO failed during $operation: ${failure.message ?: failure.javaClass.name}"
                )
                future.completeExceptionally(failure)
            } finally {
                inFlight.decrementAndGet()
            }
        }
        return future
    }

    fun run(operation: String, task: () -> Unit): CompletableFuture<Unit> {
        return supply(operation) {
            task()
        }
    }

    fun awaitIdle(timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(0L)
        while (System.currentTimeMillis() < deadline) {
            if (inFlight.get() <= 0) {
                return true
            }
            Thread.sleep(25L)
        }
        return inFlight.get() <= 0
    }

    fun shutdownGracefully(timeoutMillis: Long): Boolean {
        shuttingDown.set(true)
        val remaining = timeoutMillis.coerceAtLeast(0L)
        val idle = awaitIdle(remaining)
        executor.shutdown()
        val terminated = executor.awaitTermination(remaining, TimeUnit.MILLISECONDS)
        return idle && terminated
    }
}
