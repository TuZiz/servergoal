package ym.serverGoal.storage

import ym.serverGoal.model.ActiveActivity

interface ActivitySyncBackend : AutoCloseable {
    fun load(): ActiveActivity?
    fun save(activity: ActiveActivity?): ActiveActivity?
    override fun close() {
    }
}
