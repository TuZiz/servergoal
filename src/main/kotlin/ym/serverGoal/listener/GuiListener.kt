package ym.serverGoal.listener

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import ym.serverGoal.config.MessageService
import ym.serverGoal.gui.GoalGuiService
import ym.serverGoal.gui.GoalMenuHolder
import ym.serverGoal.gui.GuiActionType
import ym.serverGoal.platform.PlatformScheduler
import ym.serverGoal.service.ActivityService
import ym.serverGoal.service.RewardService

class GuiListener(
    private val gui: GoalGuiService,
    private val activity: ActivityService,
    private val rewards: RewardService,
    private val messages: MessageService,
    private val scheduler: PlatformScheduler
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
                if (!holder.templateId.isNullOrBlank() && activity.currentTemplate()?.id != holder.templateId) {
                    messages.sendPlayer(
                        player,
                        if (activity.activeActivity() == null) "no-activity" else "activity-not-running"
                    )
                    return
                }
                activity.submitInventoryAsync(player, action.value).whenComplete { result, failure ->
                    if (failure != null) {
                        messages.sendPlayer(
                            player,
                            "submit-sync-failed",
                            mapOf("error" to (failure.message ?: failure.javaClass.name))
                        )
                    } else if (result != null) {
                        messages.sendPlayer(player, result.messageKey, result.placeholders)
                    }
                    scheduler.runForPlayer(player) {
                        if (player.isOnline) {
                            gui.openMain(player)
                        }
                    }
                }
            }
            GuiActionType.REWARDS -> gui.openRewards(player)
            GuiActionType.HISTORY -> gui.openHistory(player)
            GuiActionType.TOP -> gui.sendTopToChat(player)
            GuiActionType.START_DEFAULT -> {
                if (!player.hasPermission("servergoal.admin.start")) {
                    messages.sendPlayer(player, "no-permission")
                    return
                }
                if (activity.startDefaultTemplate()) {
                    messages.sendPlayer(player, "started", mapOf("template" to "default"))
                    gui.openMain(player)
                } else {
                    messages.sendPlayer(player, "start-failed", mapOf("template" to "default"))
                }
            }
            GuiActionType.BACK -> gui.openMain(player)
            GuiActionType.CLOSE -> player.closeInventory()
            GuiActionType.REFRESH -> gui.reopen(player, holder)
            GuiActionType.PREVIOUS_PAGE -> openPage(player, holder, holder.page - 1)
            GuiActionType.NEXT_PAGE -> openPage(player, holder, holder.page + 1)
            GuiActionType.CLAIM_PERSONAL -> {
                val rewardId = action.value ?: return
                val result = activity.claimPersonalReward(player, rewardId)
                if (result.success) {
                    rewards.execute(player, result.commands, result.placeholders)
                }
                messages.sendPlayer(player, result.messageKey, result.placeholders)
                gui.openMain(player)
            }
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        (event.inventory.holder as? GoalMenuHolder)?.cancelRefreshTask()
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
            "history" -> gui.openHistory(player, nextPage)
            else -> gui.openMain(player)
        }
    }
}
