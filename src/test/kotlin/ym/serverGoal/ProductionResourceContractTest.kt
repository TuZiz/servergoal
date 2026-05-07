package ym.serverGoal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ProductionResourceContractTest {
    private val root: Path = Path.of("").toAbsolutePath()

    @Test
    fun `maven build targets Java 17`() {
        val pom = Files.readString(root.resolve("pom.xml"))
        assertTrue(pom.contains("<java.version>17</java.version>"))
        assertTrue(pom.contains("<jvmTarget>\${java.version}</jvmTarget>"))
    }

    @Test
    fun `plugin yml uses production compatible metadata`() {
        val plugin = Files.readString(root.resolve("src/main/resources/plugin.yml"))
        assertTrue(plugin.contains("api-version: '1.20'"))
        assertTrue(plugin.contains("folia-supported: true"))
        assertTrue(plugin.contains("softdepend:"))
        assertTrue(plugin.contains("  - EcoLink"))
        assertFalse(plugin.contains("api-version: '26.1'"))
    }

    @Test
    fun `default config keeps production safety defaults`() {
        val config = Files.readString(root.resolve("src/main/resources/config.yml"))
        assertTrue(config.contains("cooldown-seconds: 2"))
        assertTrue(config.contains("default-template: survival"))
        assertTrue(config.contains("max-items-per-submit: 2304"))
        assertTrue(config.contains("use-ssl: false"))
        assertTrue(config.contains("require-ssl: false"))
        assertTrue(config.contains("allow-public-key-retrieval: true"))
        assertTrue(config.contains("database-failure-strategy: fail-fast"))
        assertTrue(config.contains("notifications:"))
        assertTrue(config.contains("interval-seconds: 300"))
    }

    @Test
    fun `default resources use split activity and collection layout`() {
        assertTrue(Files.exists(root.resolve("src/main/resources/main.yml")))
        assertFalse(Files.exists(root.resolve("src/main/resources/gui/main.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/activities/default.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/activities/survival.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/wood.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/stone.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/iron/helmet.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/iron/chestplate.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/iron/leggings.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/iron/boots.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/iron/sword.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/iron/pickaxe.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/iron/axe.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/iron/shovel.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/gold/helmet.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/gold/chestplate.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/gold/leggings.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/gold/boots.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/gold/sword.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/gold/pickaxe.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/gold/axe.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/gear/gold/shovel.yml")))

        val activity = Files.readString(root.resolve("src/main/resources/activities/default.yml"))
        val survival = Files.readString(root.resolve("src/main/resources/activities/survival.yml"))
        assertTrue(activity.contains("collections:"))
        assertTrue(survival.contains("collections:"))
        assertTrue(survival.contains("  - gear/iron/helmet"))
        assertTrue(survival.contains("  - gear/gold/shovel"))
        assertTrue(survival.contains("tags admin coin give %player% %amount%"))
        assertTrue(activity.contains("  - wood"))
        assertTrue(activity.contains("  - stone"))
        assertFalse(activity.contains("  - gear/iron/helmet"))
        assertFalse(activity.contains("  - gear/gold/shovel"))
        assertTrue(activity.contains("collection-overrides:"))
        assertTrue(activity.contains("可提交木材类物资"))
        assertTrue(activity.contains("可提交石料类物资"))
        assertTrue(activity.contains("目标: <#FFFFFF>%item_target%"))
        assertTrue(activity.contains("pool-amount: 6"))
        assertTrue(activity.contains("min-contribution: 50"))
        assertTrue(activity.contains("tags admin coin give %player% %amount%"))
        assertFalse(activity.contains("目标量: <#FFFFFF>%item_target%"))
        assertFalse(activity.contains("stages:"))
        assertTrue(activity.contains("personal-rewards: {}"))
        assertTrue(activity.contains("contribution-reward:"))
        assertFalse(activity.contains("accepted-items:"))

        val gui = Files.readString(root.resolve("src/main/resources/main.yml"))
        assertTrue(gui.contains("Action: item-progress"))
        assertTrue(gui.contains("#PPPPPPP#"))
        assertTrue(gui.contains("多个 P 会按活动 collections 顺序显示"))
        assertTrue(gui.contains("Progress-Lore:"))
        assertTrue(gui.contains("Action: history"))
        assertTrue(gui.contains("Action: history-entry"))
        assertTrue(gui.contains("Action: start-default"))
        assertTrue(gui.contains("点击启动默认收集活动"))
        assertFalse(gui.contains("next_stage"))
        assertFalse(gui.contains("当前阶段"))
        assertFalse(gui.contains("可提交石料类物资"))
        assertFalse(gui.contains("目标量: <#FFFFFF>%item_target%"))
        assertFalse(gui.contains("Action: claim-stage"))
        assertFalse(gui.contains("Action: claim-personal"))
        assertFalse(gui.contains("  rewards:"))

        val lang = Files.readString(root.resolve("src/main/resources/lang/zh_cn.yml"))
        assertTrue(lang.contains("activity-progress-periodic:"))
        assertFalse(lang.contains("stage-unlocked"))
        assertFalse(lang.contains("stage-unlocked-detailed"))

        val wood = Files.readString(root.resolve("src/main/resources/collections/wood.yml"))
        assertTrue(wood.contains("materials:"))
        assertTrue(wood.contains("OAK_LOG"))
        assertTrue(wood.contains("STRIPPED_WARPED_HYPHAE"))
        assertFalse(wood.contains("主世界木材:"))

        val stone = Files.readString(root.resolve("src/main/resources/collections/stone.yml"))
        assertTrue(stone.contains("materials:"))
        assertTrue(stone.contains("COBBLESTONE"))
        assertTrue(stone.contains("COBBLED_DEEPSLATE"))
        assertTrue(stone.contains("TUFF"))
        assertFalse(stone.contains("基础石材:"))

        val ironHelmet = Files.readString(root.resolve("src/main/resources/collections/gear/iron/helmet.yml"))
        assertTrue(ironHelmet.contains("Material: IRON_HELMET"))
        assertTrue(ironHelmet.contains("target: 300"))

        val goldPickaxe = Files.readString(root.resolve("src/main/resources/collections/gear/gold/pickaxe.yml"))
        assertTrue(goldPickaxe.contains("Material: GOLDEN_PICKAXE"))
        assertTrue(goldPickaxe.contains("target: 500"))

        val command = Files.readString(root.resolve("src/main/kotlin/ym/serverGoal/command/ServerGoalCommand.kt"))
        assertTrue(command.contains("sendHelp(sender)"))
        assertTrue(command.contains("openTemplate(sender, args)"))
        assertTrue(command.contains("openHistory(sender)"))
        assertTrue(command.contains("startRotatedTemplate"))
        assertTrue(command.contains("\"usage-open\""))
    }

    @Test
    fun `collection override target falls back to collection target when omitted`() {
        val source = Files.readString(root.resolve("src/main/kotlin/ym/serverGoal/config/ConfigService.kt"))
        assertTrue(source.contains("override.contains(\"target\")"))
        assertTrue(source.contains("override.contains(\"target-amount\")"))
        assertFalse(source.contains("overrideSection?.getInt(\"target\")\n                ?.coerceAtLeast(1)"))
    }

    @Test
    fun `production activity features are configurable`() {
        val config = Files.readString(root.resolve("src/main/resources/config.yml"))
        assertTrue(config.contains("rotation:"))
        assertTrue(config.contains("notifications:"))
        assertTrue(config.contains("interval-seconds: 300"))
        assertTrue(config.contains("interval-days: 7"))
        assertTrue(config.contains("  pool:"))
        assertTrue(config.contains("online-heartbeat-expire-seconds: 90"))

        val defaultActivity = Files.readString(root.resolve("src/main/resources/activities/default.yml"))
        assertTrue(defaultActivity.contains("dynamic-target:"))
        assertTrue(defaultActivity.contains("base-players: 20"))
        assertTrue(defaultActivity.contains("min-contribution: 50"))

        val survivalActivity = Files.readString(root.resolve("src/main/resources/activities/survival.yml"))
        assertTrue(survivalActivity.contains("min-contribution: 80"))
        assertTrue(survivalActivity.contains("pool-amount: 20"))

        val models = Files.readString(root.resolve("src/main/kotlin/ym/serverGoal/model/Models.kt"))
        assertTrue(models.contains("data class ActivityHistoryEntry"))
        assertTrue(models.contains("effectiveTargetTotal"))
        assertTrue(models.contains("serverHeartbeatTableName"))

        val service = Files.readString(root.resolve("src/main/kotlin/ym/serverGoal/service/ActivityService.kt"))
        assertTrue(service.contains("startRotatedTemplate"))
        assertTrue(service.contains("autoStartRotationIfDue"))
        assertTrue(service.contains("activity-progress-periodic"))
        assertFalse(service.contains("announceStageNotificationsLocked"))
        assertTrue(service.contains("storage.networkOnlinePlayers"))
        assertTrue(service.contains("template.acceptedItems.all"))
        assertTrue(service.contains("template.acceptedItems.isEmpty()"))
        assertTrue(service.contains("reward.minContribution"))
        assertTrue(service.contains("targetMultiplier"))

        val storage = Files.readString(root.resolve("src/main/kotlin/ym/serverGoal/storage/MysqlActivitySyncBackend.kt"))
        assertTrue(storage.contains("heartbeatTableName"))
        assertTrue(storage.contains("reportOnlinePlayers"))
        assertTrue(storage.contains("networkOnlinePlayers"))

        val resourceService = Files.readString(root.resolve("src/main/kotlin/ym/serverGoal/config/ResourceService.kt"))
        assertTrue(resourceService.contains("activities/survival.yml"))
        assertTrue(resourceService.contains("collections/gear/iron/helmet.yml"))
        assertTrue(resourceService.contains("collections/gear/gold/shovel.yml"))

        val configService = Files.readString(root.resolve("src/main/kotlin/ym/serverGoal/config/ConfigService.kt"))
        assertTrue(configService.contains("findNestedSection"))
        assertTrue(configService.contains("path.split('/')"))

        val guiService = Files.readString(root.resolve("src/main/kotlin/ym/serverGoal/gui/GoalGuiService.kt"))
        assertTrue(guiService.contains("rawCollectionLore"))
        assertTrue(guiService.contains("ColorText.renderList(rawCollectionLore, map)"))
    }

    @Test
    fun `reward history service writes to expected directory`() {
        val source = Files.readString(root.resolve("src/main/kotlin/ym/serverGoal/service/RewardAuditService.kt"))
        assertTrue(source.contains("data/reward-history"))
        assertTrue(source.contains("commands.executed-count"))
        assertTrue(source.contains("broadcast.sent"))
    }

    @Test
    fun `pom uses Spigot 1_20 API for Java 17 production target`() {
        val pom = Files.readString(root.resolve("pom.xml"))
        assertTrue(pom.contains("<version>1.20.4-R0.1-SNAPSHOT</version>"))
        assertEquals(1, Regex("<artifactId>spigot-api</artifactId>").findAll(pom).count())
    }
}
