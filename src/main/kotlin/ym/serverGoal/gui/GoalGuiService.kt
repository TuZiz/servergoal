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
import ym.serverGoal.service.ActivityService
import ym.serverGoal.util.ColorText
import ym.serverGoal.util.ItemUtil

class GoalGuiService(
    private val activityService: ActivityService,
    private val messages: MessageService,
    private val resources: ResourceService
) {
    @Volatile
    private var gui: YamlConfiguration = YamlConfiguration()

    @Synchronized
    fun reload() {
        gui = resources.loadMerged("gui/main.yml")
    }

    fun openMain(player: Player) {
        openMenu(player, "main", 0)
    }

    fun openRewards(player: Player, page: Int = 0) {
        openMenu(player, "rewards", page.coerceAtLeast(0))
    }

    fun openTop(player: Player, page: Int = 0) {
        openMenu(player, "top", page.coerceAtLeast(0))
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
        var topSlotIndex = 0

        for ((row, line) in shape.withIndex()) {
            for (column in 0 until 9) {
                val symbol = line.getOrNull(column) ?: ' '
                if (symbol == ' ') {
                    continue
                }
                val slot = row * 9 + column
                val button = buttons?.getConfigurationSection(symbol.toString()) ?: continue
                when ((button.getString("Action") ?: button.getString("action") ?: "").lowercase()) {
                    "item-progress" -> renderItemProgress(player, inventory, slot, button, itemProgressIndex++)
                    "claim-stage" -> renderStageReward(player, holder, inventory, slot, button, page, stageSlotIndex++)
                    "claim-personal" -> renderPersonalReward(player, holder, inventory, slot, button, page, personalSlotIndex++)
                    "top-entry" -> renderTopEntry(player, inventory, slot, button, page, topSlotIndex++)
                    else -> {
                        val placeholders = placeholders(player)
                        inventory.setItem(slot, itemFromButton(button, placeholders))
                        actionFrom(button)?.let { holder.actions[slot] = it }
                    }
                }
            }
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
        val template = activityService.currentTemplate()
        val item = template?.acceptedItems?.getOrNull(index)
        if (active == null || template == null || item == null) {
            inventory.setItem(slot, itemFromButton(button, placeholders(player)))
            return
        }
        val collected = active.collectedByItem[item.key] ?: 0
        val map = placeholders(player) + mapOf(
            "item" to item.displayName,
            "item_collected" to collected.toString(),
            "item_target" to item.targetAmount.toString(),
            "item_remaining" to (item.targetAmount - collected).coerceAtLeast(0).toString()
        )
        inventory.setItem(slot, itemFromButton(button, map, item.displayItem))
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
        val slotsPerPage = countActionSlots("rewards", "claim-stage").coerceAtLeast(1)
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
        val slotsPerPage = countActionSlots("rewards", "claim-personal").coerceAtLeast(1)
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

    private fun renderTopEntry(
        player: Player,
        inventory: org.bukkit.inventory.Inventory,
        slot: Int,
        button: ConfigurationSection,
        page: Int,
        index: Int
    ) {
        val slotsPerPage = countActionSlots("top", "top-entry").coerceAtLeast(1)
        val rank = page * slotsPerPage + index + 1
        val entry = activityService.ranking(rank).getOrNull(rank - 1)
        val map = placeholders(player) + if (entry != null) {
            mapOf(
                "top_rank" to rank.toString(),
                "top_player" to entry.first,
                "top_amount" to entry.second.toString()
            )
        } else {
            mapOf(
                "top_rank" to rank.toString(),
                "top_player" to messages.raw("gui.top-empty-player"),
                "top_amount" to "0"
            )
        }
        inventory.setItem(slot, itemFromButton(button, map))
    }

    private fun placeholders(player: Player): Map<String, String> {
        val active = activityService.activeActivity()
        val template = activityService.currentTemplate()
        val now = System.currentTimeMillis()
        val total = active?.totalCollected ?: 0
        val target = template?.targetTotal ?: 0
        val contribution = active?.contributionOf(player.uniqueId) ?: 0
        val rank = activityService.rankOf(player.uniqueId)
        val nextStage = nextStage(active, template)
        val contributionReward = template?.contributionReward
        val contributionRewardPool = contributionReward?.poolAmount ?: 0
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
