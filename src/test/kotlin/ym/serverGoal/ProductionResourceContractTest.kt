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
        assertTrue(config.contains("max-items-per-submit: 2304"))
        assertTrue(config.contains("use-ssl: false"))
        assertTrue(config.contains("require-ssl: false"))
        assertTrue(config.contains("allow-public-key-retrieval: true"))
        assertTrue(config.contains("database-failure-strategy: fail-fast"))
    }

    @Test
    fun `default resources use split activity and collection layout`() {
        assertTrue(Files.exists(root.resolve("src/main/resources/main.yml")))
        assertFalse(Files.exists(root.resolve("src/main/resources/gui/main.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/activities/default.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/wood.yml")))
        assertTrue(Files.exists(root.resolve("src/main/resources/collections/stone.yml")))

        val activity = Files.readString(root.resolve("src/main/resources/activities/default.yml"))
        assertTrue(activity.contains("collections:"))
        assertTrue(activity.contains("  - wood"))
        assertTrue(activity.contains("  - stone"))
        assertTrue(activity.contains("stages:"))
        assertFalse(activity.contains("accepted-items:"))

        val gui = Files.readString(root.resolve("src/main/resources/main.yml"))
        assertTrue(gui.contains("Action: item-progress"))
        assertTrue(gui.contains("多个 P 会按活动 collections 顺序显示"))
        assertTrue(gui.contains("Action: top"))
        assertTrue(gui.contains("奖励进度和个人排行信息统一放在 C 按钮里显示"))
        assertFalse(gui.contains("Action: claim-stage"))
        assertFalse(gui.contains("Action: claim-personal"))
        assertFalse(gui.contains("  rewards:"))

        val wood = Files.readString(root.resolve("src/main/resources/collections/wood.yml"))
        assertTrue(wood.contains("materials:"))
        assertTrue(wood.contains("OAK_LOG"))
        assertTrue(wood.contains("STRIPPED_WARPED_HYPHAE"))
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
