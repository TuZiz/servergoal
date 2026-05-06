package ym.serverGoal.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import ym.serverGoal.config.MessageService
import ym.serverGoal.config.ResourceService
import ym.serverGoal.model.ActiveActivity
import ym.serverGoal.model.ActivityTemplate
import ym.serverGoal.model.PersonalRewardDefinition
import ym.serverGoal.model.StageDefinition
import ym.serverGoal.platform.PlatformScheduler
import ym.serverGoal.platform.TaskHandle
import ym.serverGoal.service.ActivityService
import ym.serverGoal.util.ColorText
import ym.serverGoal.util.ItemUtil
import kotlin.math.roundToInt

class GoalGuiService(
    private val activityService: ActivityService,
    private val messages: MessageService,
    private val resources: ResourceService,
    private val scheduler: PlatformScheduler
) {
    @Volatile
    private var gui: YamlConfiguration = YamlConfiguration()

    @Synchronized
    fun reload() {
        gui = resources.loadMerged("main.yml")
    }

    fun openMain(player: Player) {
        openMenu(player, "main", 0)
    }

    fun openRewards(player: Player, page: Int = 0) {
        openMenu(player, "main", page.coerceAtLeast(0))
    }

    fun reopen(player: Player, holder: GoalMenuHolder) {
        openMenu(player, holder.menuId, holder.page)
    }

    private fun openMenu(player: Player, menuId: String, page: Int) {
        val guiSnapshot = gui
        val section = guiSnapshot.getConfigurationSection("menus.$menuId") ?: return openMain(player)
        val shape = shape(section)
        val rows = shape.size.coerceIn(1, 6)
        val holder = GoalMenuHolder(menuId, page)
        val basePlaceholders = placeholders(player)
        val title = ColorText.render(
            localizedText(section, "Title", listOf("Title", "title")) ?: messages.raw("gui.default-title"),
            basePlaceholders
        )
        val inventory = Bukkit.createInventory(holder, rows * 9, title)
        holder.menuInventory = inventory

        renderShape(player, holder, inventory, section, shape, page)
        player.openInventory(inventory)
        startItemProgressRefresh(player, holder)
    }

    private fun renderShape(
        player: Player,
        holder: GoalMenuHolder,
        inventory: org.bukkit.inventory.Inventory,
        menuSection: ConfigurationSection,
        shape: List<String>,
        page: Int
    ) {
        val buttons = menuSection.getConfigurationSection("Buttons")
            ?: menuSection.getConfigurationSection("GuiKey")
            ?: menuSection.getConfigurationSection("buttons")
        var itemProgressIndex = 0
        var stageSlotIndex = 0
        var personalSlotIndex = 0

        for ((row, line) in shape.withIndex()) {
            for (column in 0 until 9) {
                val symbol = line.getOrNull(column) ?: ' '
                if (symbol == ' ') {
                    continue
                }
                val slot = row * 9 + column
                val button = buttons?.getConfigurationSection(symbol.toString()) ?: continue
                when ((button.getString("Action") ?: button.getString("action") ?: "").lowercase()) {
                    "item-progress" -> {
                        val index = itemProgressIndex++
                        holder.itemProgressSlots[slot] = ItemProgressSlot(symbol.toString(), index)
                        val itemKey = activityService.displayTemplate()?.acceptedItems?.getOrNull(index)?.key
                        if (itemKey != null) {
                            holder.actions[slot] = GuiAction(GuiActionType.SUBMIT, itemKey)
                            renderItemProgress(player, inventory, slot, button, index)
                        } else {
                            holder.actions.remove(slot)
                            renderMissingItemProgress(player, inventory, slot, menuSection, button)
                        }
                    }
                    "claim-stage" -> renderStageReward(player, holder, inventory, slot, button, page, stageSlotIndex++)
                    "claim-personal" -> renderPersonalReward(player, holder, inventory, slot, button, page, personalSlotIndex++)
                    else -> {
                        val placeholders = placeholders(player)
                        inventory.setItem(slot, itemFromButton(button, placeholders))
                        actionFrom(button)?.let { holder.actions[slot] = it }
                    }
                }
            }
        }
    }

    private fun startItemProgressRefresh(player: Player, holder: GoalMenuHolder) {
        if (holder.menuId != "main" || holder.itemProgressSlots.isEmpty()) {
            return
        }
        var handle: TaskHandle? = null
        handle = scheduler.runRepeatingForPlayer(player, 4L, 4L) {
            if (!player.isOnline || player.openInventory.topInventory.holder !== holder) {
                handle?.cancel()
                holder.refreshTask = null
                return@runRepeatingForPlayer
            }
            refreshItemProgressSlots(player, holder)
        }
        holder.refreshTask = handle
    }

    fun refreshItemProgressSlots(player: Player, holder: GoalMenuHolder) {
        if (holder.menuId != "main") {
            return
        }
        val section = gui.getConfigurationSection("menus.${holder.menuId}") ?: return
        val buttons = section.getConfigurationSection("Buttons")
            ?: section.getConfigurationSection("GuiKey")
            ?: section.getConfigurationSection("buttons")
            ?: return
        for ((slot, progressSlot) in holder.itemProgressSlots) {
            val button = buttons.getConfigurationSection(progressSlot.symbol) ?: continue
            if (slot in 0 until holder.inventory.size) {
                val itemKey = activityService.displayTemplate()?.acceptedItems?.getOrNull(progressSlot.index)?.key
                if (itemKey != null) {
                    holder.actions[slot] = GuiAction(GuiActionType.SUBMIT, itemKey)
                    renderItemProgress(player, holder.inventory, slot, button, progressSlot.index)
                } else {
                    holder.actions.remove(slot)
                    renderMissingItemProgress(player, holder.inventory, slot, section, button)
                }
            }
        }
    }

    fun sendTopToChat(player: Player, limit: Int = 10) {
        val ranking = activityService.ranking(limit)
        player.sendMessage(ColorText.colorize(messages.raw("messages.top-chat-header")))
        if (ranking.isEmpty()) {
            player.sendMessage(ColorText.colorize(messages.raw("messages.top-chat-empty")))
            return
        }
        ranking.forEachIndexed { index, entry ->
            player.sendMessage(
                ColorText.colorize(
                    messages.raw(
                        "messages.top-chat-line",
                        mapOf(
                            "rank" to (index + 1).toString(),
                            "player" to entry.first,
                            "amount" to entry.second.toString()
                        )
                    )
                )
            )
        }
    }

    private fun renderItemProgress(
        player: Player,
        inventory: org.bukkit.inventory.Inventory,
        slot: Int,
        button: ConfigurationSection,
        index: Int
    ) {
        val active = activityService.activeActivity()
        val template = activityService.displayTemplate()
        val item = template?.acceptedItems?.getOrNull(index)
        if (template == null || item == null) {
            inventory.setItem(slot, itemFromButton(button, placeholders(player)))
            return
        }
        val collected = active?.collectedByItem?.get(item.key) ?: 0
        val map = placeholders(player) + mapOf(
            "item" to item.displayName,
            "item_collected" to collected.toString(),
            "item_target" to item.targetAmount.toString(),
            "item_remaining" to (item.targetAmount - collected).coerceAtLeast(0).toString()
        )
        val rendered = itemFromButton(button, map, item.displayItem)
        val meta = rendered.itemMeta
        if (meta != null) {
            val collectionLore = item.displayItem.itemMeta?.lore.orEmpty()
            val progressLore = localizedList(button, "Progress-Lore", listOf("Progress-Lore", "progress-lore"))
                .ifEmpty { localizedList(button, "Lore", listOf("Lore", "lore")) }
            meta.lore = collectionLore + ColorText.renderList(progressLore, map)
            rendered.itemMeta = meta
        }
        inventory.setItem(slot, rendered)
    }

    private fun renderMissingItemProgress(
        player: Player,
        inventory: org.bukkit.inventory.Inventory,
        slot: Int,
        menuSection: ConfigurationSection,
        button: ConfigurationSection
    ) {
        val fallback = menuSection.getConfigurationSection("Buttons")
            ?: menuSection.getConfigurationSection("GuiKey")
            ?: menuSection.getConfigurationSection("buttons")
        val placeholderButton = fallback?.getConfigurationSection("-") ?: button
        inventory.setItem(slot, itemFromButton(placeholderButton, placeholders(player)))
    }

    private fun renderStageReward(
        player: Player,
        holder: GoalMenuHolder,
        inventory: org.bukkit.inventory.Inventory,
        slot: Int,
        button: ConfigurationSection,
        page: Int,
        index: Int
    ) {
        val active = activityService.activeActivity()
        val template = activityService.currentTemplate()
        val slotsPerPage = countActionSlots(holder.menuId, "claim-stage").coerceAtLeast(1)
        val stage = template?.stages?.getOrNull(page * slotsPerPage + index)
        if (active == null || template == null || stage == null) {
            inventory.setItem(
                slot,
                itemFromButton(button, placeholders(player) + mapOf("stage_status" to messages.raw("gui.no-reward")))
            )
            return
        }
        val contribution = active.contributionOf(player.uniqueId)
        val claimed = active.claimedStageRewards[player.uniqueId]?.contains(stage.index) == true
        val unlocked = active.unlockedStage >= stage.index
        val status = when {
            claimed -> messages.raw("gui.reward-status.claimed")
            unlocked && contribution >= stage.minContribution -> messages.raw("gui.reward-status.available")
            unlocked -> messages.raw("gui.reward-status.contribution-too-low")
            else -> messages.raw("gui.reward-status.locked")
        }
        val map = placeholders(player) + stagePlaceholders(stage, contribution, status)
        inventory.setItem(slot, itemFromButton(button, map, stage.displayItem))
        holder.actions[slot] = GuiAction(GuiActionType.CLAIM_STAGE, stage.index.toString())
    }

    private fun renderPersonalReward(
        player: Player,
        holder: GoalMenuHolder,
        inventory: org.bukkit.inventory.Inventory,
        slot: Int,
        button: ConfigurationSection,
        page: Int,
        index: Int
    ) {
        val active = activityService.activeActivity()
        val template = activityService.currentTemplate()
        val slotsPerPage = countActionSlots(holder.menuId, "claim-personal").coerceAtLeast(1)
        val reward = template?.personalRewards?.getOrNull(page * slotsPerPage + index)
        if (active == null || template == null || reward == null) {
            inventory.setItem(
                slot,
                itemFromButton(button, placeholders(player) + mapOf("personal_status" to messages.raw("gui.no-reward")))
            )
            return
        }
        val contribution = active.contributionOf(player.uniqueId)
        val claimed = active.claimedPersonalRewards[player.uniqueId]?.contains(reward.id) == true
        val status = when {
            claimed -> messages.raw("gui.reward-status.claimed")
            contribution >= reward.threshold -> messages.raw("gui.reward-status.available")
            else -> messages.raw("gui.reward-status.contribution-too-low")
        }
        val map = placeholders(player) + personalPlaceholders(reward, contribution, status)
        inventory.setItem(slot, itemFromButton(button, map, reward.displayItem))
        holder.actions[slot] = GuiAction(GuiActionType.CLAIM_PERSONAL, reward.id)
    }

    private fun placeholders(player: Player): Map<String, String> {
        val active = activityService.activeActivity()
        val template = activityService.displayTemplate()
        val now = System.currentTimeMillis()
        val total = active?.totalCollected ?: 0
        val target = template?.targetTotal ?: 0
        val contribution = active?.contributionOf(player.uniqueId) ?: 0
        val rank = activityService.rankOf(player.uniqueId)
        val nextStage = nextStage(active, template)
        val contributionReward = template?.contributionReward
        val contributionRewardPool = contributionReward?.poolAmount ?: 0
        val totalContribution = active?.contributions?.values?.sum() ?: 0
        val rewardPlayers = active?.contributions?.values?.count { it > 0 } ?: 0
        val estimatedReward = if (contributionRewardPool > 0 && totalContribution > 0 && contribution > 0) {
            (contributionRewardPool.toLong() * contribution.toLong() / totalContribution.toLong()).toInt()
        } else {
            0
        }
        val contributionPercent = if (totalContribution > 0 && contribution > 0) {
            (contribution.toDouble() * 10000.0 / totalContribution.toDouble()).roundToInt() / 100.0
        } else {
            0.0
        }
        val contributionRewardStatus = when {
            contributionReward == null || !contributionReward.enabled || contributionReward.poolAmount <= 0 -> {
                messages.raw("gui.contribution-reward.none")
            }
            active?.contributionRewardDistributed == true -> messages.raw("gui.contribution-reward.distributed")
            else -> messages.raw("gui.contribution-reward.enabled")
        }
        val percent = if (target <= 0) 0 else (total * 100 / target).coerceIn(0, 100)
        return mapOf(
            "player" to player.name,
            "activity" to (template?.displayName ?: active?.displayName ?: messages.raw("gui.no-activity")),
            "state" to activityState(active),
            "remaining" to messages.formatDuration((active?.endsAt ?: now) - now),
            "total" to total.toString(),
            "target" to target.toString(),
            "percent" to percent.toString(),
            "stage" to (active?.unlockedStage ?: 0).toString(),
            "stage_total" to (template?.stages?.size ?: 0).toString(),
            "next_stage" to (nextStage?.index?.toString() ?: "-"),
            "next_stage_name" to (nextStage?.displayName ?: messages.raw("gui.no-next-stage")),
            "next_stage_threshold" to (nextStage?.threshold?.toString() ?: "0"),
            "next_stage_remaining" to ((nextStage?.threshold ?: total) - total).coerceAtLeast(0).toString(),
            "contribution" to contribution.toString(),
            "contribution_reward_pool" to contributionRewardPool.toString(),
            "contribution_reward_status" to contributionRewardStatus,
            "contribution_reward_estimated" to estimatedReward.toString(),
            "contribution_percent" to String.format("%.2f", contributionPercent),
            "total_contribution" to totalContribution.toString(),
            "contribution_players" to rewardPlayers.toString(),
            "rank" to if (rank > 0) rank.toString() else messages.raw("gui.not-ranked")
        )
    }

    private fun activityState(active: ActiveActivity?): String {
        return when {
            active == null -> messages.raw("gui.activity-state.none")
            active.active -> messages.raw("gui.activity-state.running")
            active.completed -> messages.raw("gui.activity-state.completed")
            else -> messages.raw("gui.activity-state.ended")
        }
    }

    private fun nextStage(active: ActiveActivity?, template: ActivityTemplate?): StageDefinition? {
        if (active == null || template == null) {
            return null
        }
        return template.stages.firstOrNull { it.index > active.unlockedStage }
    }

    private fun stagePlaceholders(stage: StageDefinition, contribution: Int, status: String): Map<String, String> {
        return mapOf(
            "stage" to stage.index.toString(),
            "stage_name" to stage.displayName,
            "stage_threshold" to stage.threshold.toString(),
            "stage_min_contribution" to stage.minContribution.toString(),
            "stage_status" to status,
            "contribution" to contribution.toString()
        )
    }

    private fun personalPlaceholders(
        reward: PersonalRewardDefinition,
        contribution: Int,
        status: String
    ): Map<String, String> {
        return mapOf(
            "personal_id" to reward.id,
            "personal_name" to reward.displayName,
            "personal_threshold" to reward.threshold.toString(),
            "personal_status" to status,
            "contribution" to contribution.toString()
        )
    }

    private fun actionFrom(button: ConfigurationSection): GuiAction? {
        val action = (button.getString("Action") ?: button.getString("action") ?: "").lowercase()
        return when (action) {
            "submit" -> GuiAction(GuiActionType.SUBMIT)
            "rewards" -> GuiAction(GuiActionType.REWARDS)
            "top" -> GuiAction(GuiActionType.TOP)
            "start-default", "admin-start" -> GuiAction(GuiActionType.START_DEFAULT)
            "back", "main" -> GuiAction(GuiActionType.BACK)
            "close" -> GuiAction(GuiActionType.CLOSE)
            "refresh" -> GuiAction(GuiActionType.REFRESH)
            "previous-page", "prev-page" -> GuiAction(GuiActionType.PREVIOUS_PAGE)
            "next-page" -> GuiAction(GuiActionType.NEXT_PAGE)
            else -> null
        }
    }

    private fun itemFromButton(
        section: ConfigurationSection,
        placeholders: Map<String, String>,
        baseItem: ItemStack? = null
    ): ItemStack {
        val hasMaterial = section.contains("Material") || section.contains("material")
        val item = if (baseItem != null && !hasMaterial) baseItem.clone() else ItemUtil.itemFromSection(section, placeholders)
        val meta = item.itemMeta ?: return item
        applyMeta(meta, section, placeholders)
        item.itemMeta = meta
        return item
    }

    private fun applyMeta(meta: ItemMeta, section: ConfigurationSection, placeholders: Map<String, String>) {
        val name = localizedText(section, "Name", listOf("Name", "name"))
        if (name != null) {
            meta.setDisplayName(ColorText.render(name, placeholders))
        }
        val lore = localizedList(section, "Lore", listOf("Lore", "lore"))
        if (lore.isNotEmpty()) {
            meta.lore = ColorText.renderList(lore, placeholders)
        }
    }

    private fun localizedText(section: ConfigurationSection, keyName: String, literalKeys: List<String>): String? {
        val languageKey = section.getString("$keyName-Key") ?: section.getString("${keyName.lowercase()}-key")
        if (languageKey != null) {
            return messages.raw(languageKey)
        }
        for (key in literalKeys) {
            val value = section.getString(key)
            if (value != null) {
                return value
            }
        }
        return null
    }

    private fun localizedList(section: ConfigurationSection, keyName: String, literalKeys: List<String>): List<String> {
        val languageKey = section.getString("$keyName-Key") ?: section.getString("${keyName.lowercase()}-key")
        if (languageKey != null) {
            return messages.rawList(languageKey)
        }
        for (key in literalKeys) {
            val value = section.getStringList(key)
            if (value.isNotEmpty()) {
                return value
            }
        }
        return emptyList()
    }

    private fun countActionSlots(menuId: String, action: String): Int {
        val guiSnapshot = gui
        val section = guiSnapshot.getConfigurationSection("menus.$menuId") ?: return 0
        val shape = shape(section)
        val buttons = section.getConfigurationSection("Buttons")
            ?: section.getConfigurationSection("GuiKey")
            ?: return 0
        var count = 0
        for (line in shape) {
            for (symbol in line) {
                val button = buttons.getConfigurationSection(symbol.toString()) ?: continue
                val buttonAction = (button.getString("Action") ?: "").lowercase()
                if (buttonAction == action) {
                    count++
                }
            }
        }
        return count
    }

    private fun shape(section: ConfigurationSection): List<String> {
        return section.getStringList("Shape")
            .ifEmpty { section.getStringList("GuiPlain") }
            .ifEmpty { listOf("#########", "#       #", "#########") }
            .map { line -> line.padEnd(9).take(9) }
            .take(6)
    }
}
