package ym.serverGoal.gui

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

class GoalMenuHolder(
    val menuId: String,
    val page: Int,
    val actions: MutableMap<Int, GuiAction> = linkedMapOf()
) : InventoryHolder {
    lateinit var menuInventory: Inventory

    override fun getInventory(): Inventory = menuInventory
}
