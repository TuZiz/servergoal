package ym.serverGoal.gui

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import ym.serverGoal.platform.TaskHandle

data class ItemProgressSlot(
    val symbol: String,
    val index: Int
)

class GoalMenuHolder(
    val menuId: String,
    val page: Int,
    val templateId: String? = null,
    val actions: MutableMap<Int, GuiAction> = linkedMapOf(),
    val itemProgressSlots: MutableMap<Int, ItemProgressSlot> = linkedMapOf()
) : InventoryHolder {
    lateinit var menuInventory: Inventory
    var refreshTask: TaskHandle? = null

    override fun getInventory(): Inventory = menuInventory

    fun cancelRefreshTask() {
        refreshTask?.cancel()
        refreshTask = null
    }
}
