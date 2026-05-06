package ym.serverGoal.service

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ym.serverGoal.config.MessageService
import ym.serverGoal.model.RewardOutboxEntry
import ym.serverGoal.platform.PlatformScheduler
import java.util.concurrent.CompletableFuture

class RewardService(
    private val scheduler: PlatformScheduler,
    private val messages: MessageService
) {
    fun execute(player: Player, commands: List<String>, placeholders: Map<String, String>) {
        executeResolved(resolveCommands(commands, placeholders + mapOf("player" to player.name)))
    }

    fun resolveCommands(commands: List<String>, placeholders: Map<String, String>): List<String> {
        return commands.mapNotNull { command ->
            val resolved = apply(command, placeholders).trim()
            resolved.takeIf { it.isNotBlank() }
        }
    }

    fun executeResolved(commands: List<String>) {
        for (command in commands) {
            scheduler.runGlobal {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.removePrefix("/"))
            }
        }
    }

    fun executeOutbox(
        entry: RewardOutboxEntry,
        onProgress: (RewardOutboxEntry) -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) }
    ): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        executeOutboxCommand(entry, entry.executedCommandCount.coerceIn(0, entry.commands.size), onProgress, future)
        return future
    }

    private fun executeOutboxCommand(
        entry: RewardOutboxEntry,
        index: Int,
        onProgress: (RewardOutboxEntry) -> CompletableFuture<Unit>,
        future: CompletableFuture<Unit>
    ) {
        if (future.isDone) {
            return
        }
        if (index >= entry.commands.size) {
            executeOutboxBroadcast(entry, onProgress, future)
            return
        }
        val command = entry.commands[index]
        entry.executedCommandCount = index + 1
        onProgress(entry).whenComplete { _, progressFailure ->
            if (progressFailure != null) {
                future.completeExceptionally(progressFailure)
                return@whenComplete
            }
            scheduler.runGlobal {
                try {
                    val executed = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.removePrefix("/"))
                    if (!executed) {
                        throw IllegalStateException("ServerGoal outbox command rejected after progress ledger update: $command")
                    }
                } catch (failure: Throwable) {
                    future.completeExceptionally(failure)
                    return@runGlobal
                }
                executeOutboxCommand(entry, index + 1, onProgress, future)
            }
        }
    }

    private fun executeOutboxBroadcast(
        entry: RewardOutboxEntry,
        onProgress: (RewardOutboxEntry) -> CompletableFuture<Unit>,
        future: CompletableFuture<Unit>
    ) {
        if (entry.broadcastMessageKey.isBlank() || entry.broadcastSent) {
            future.complete(Unit)
            return
        }
        entry.broadcastSent = true
        onProgress(entry).whenComplete { _, progressFailure ->
            if (progressFailure != null) {
                future.completeExceptionally(progressFailure)
                return@whenComplete
            }
            scheduler.runGlobal {
                try {
                    messages.broadcast(entry.broadcastMessageKey, entry.broadcastPlaceholders)
                    future.complete(Unit)
                } catch (failure: Throwable) {
                    future.completeExceptionally(failure)
                }
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
