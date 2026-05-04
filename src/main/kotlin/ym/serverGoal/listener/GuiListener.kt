package ym.serverGoal.listener

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import ym.serverGoal.config.MessageService
import ym.serverGoal.gui.GoalGuiService
import ym.serverGoal.gui.GoalMenuHolder
import ym.serverGoal.gui.GuiActionType
import ym.serverGoal.service.ActivityService
import ym.serverGoal.service.RewardService

class GuiListener(
    private val gui: GoalGuiService,
    private val activity: ActivityService,
    private val rewards: RewardService,
    private val messages: MessageService
) : Listener {
    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? GoalMenuHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val rawSlot = event.rawSlot
        if (rawSlot < 0 || rawSlot >= event.view.topInventory.size) {
            return
        }
        val action = holder.actions[rawSlot] ?: return
        when (action.type) {
            GuiActionType.SUBMIT -> {
                val result = activity.submitInventory(player)
                messages.sendPlayer(player, result.messageKey, result.placeholders)
                gui.openMain(player)
            }
            GuiActionType.REWARDS -> gui.openRewards(player)
            GuiActionType.TOP -> gui.openTop(player)
            GuiActionType.BACK -> gui.openMain(player)
            GuiActionType.CLOSE -> player.closeInventory()
            GuiActionType.REFRESH -> gui.reopen(player, holder)
            GuiActionType.PREVIOUS_PAGE -> openPage(player, holder, holder.page - 1)
            GuiActionType.NEXT_PAGE -> openPage(player, holder, holder.page + 1)
            GuiActionType.CLAIM_STAGE -> {
                val stage = action.value?.toIntOrNull() ?: return
                val result = activity.claimStageReward(player, stage)
                if (result.success) {
                    rewards.execute(player, result.commands, result.placeholders)
                }
                messages.sendPlayer(player, result.messageKey, result.placeholders)
                gui.openRewards(player, holder.page)
            }
            GuiActionType.CLAIM_PERSONAL -> {
                val rewardId = action.value ?: return
                val result = activity.claimPersonalReward(player, rewardId)
                if (result.success) {
                    rewards.execute(player, result.commands, result.placeholders)
                }
                messages.sendPlayer(player, result.messageKey, result.placeholders)
                gui.openRewards(player, holder.page)
            }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is GoalMenuHolder) {
            event.isCancelled = true
        }
    }

    private fun openPage(player: Player, holder: GoalMenuHolder, page: Int) {
        val nextPage = page.coerceAtLeast(0)
        when (holder.menuId) {
            "rewards" -> gui.openRewards(player, nextPage)
            "top" -> gui.openTop(player, nextPage)
            else -> gui.openMain(player)
        }
    }
}
