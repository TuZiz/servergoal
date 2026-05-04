package ym.serverGoal.platform

import org.bukkit.Bukkit

object MainThreadIoGuard {
    fun reject(operation: String) {
        if (Bukkit.isPrimaryThread()) {
            throw IllegalStateException("ServerGoal refused main-thread IO: $operation")
        }
    }
}
