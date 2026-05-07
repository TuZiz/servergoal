package ym.serverGoal.config

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import ym.serverGoal.model.ActivityTemplate
import ym.serverGoal.model.CollectionItem
import ym.serverGoal.model.ContributionRewardDefinition
import ym.serverGoal.model.DatabasePoolSettings
import ym.serverGoal.model.DatabaseSettings
import ym.serverGoal.model.DynamicTargetSettings
import ym.serverGoal.model.MatchRule
import ym.serverGoal.model.PersonalRewardDefinition
import ym.serverGoal.model.NotificationSettings
import ym.serverGoal.model.PluginSettings
import ym.serverGoal.model.ProgressNotificationSettings
import ym.serverGoal.model.RotationSettings
import ym.serverGoal.model.StageDefinition
import ym.serverGoal.model.SubmissionSettings
import ym.serverGoal.model.StorageSettings
import ym.serverGoal.model.SyncSettings
import ym.serverGoal.util.ColorText
import ym.serverGoal.util.ItemUtil
import java.util.Locale

class ConfigService(
    private val resources: ResourceService,
    private val messages: MessageService
) {
    @Volatile
    var settings: PluginSettings = PluginSettings(
        serverId = "server-1",
        debug = false,
        failOpenOnDatabaseError = true,
        protectionEnabled = true,
        adminTestMode = false,
        endWhenFinalStageComplete = true,
            saveOnSubmit = true,
            schedulerCheckSeconds = 30L,
            defaultTemplate = "default"
        )
        private set

    @Volatile
    private lateinit var config: YamlConfiguration
    @Volatile
    private var templates: Map<String, ActivityTemplate> = emptyMap()

    @Synchronized
    fun reload() {
        resources.releaseDefaults()
        config = resources.loadMerged("config.yml")
        loadSettings()
        templates = loadTemplates()
    }

    fun template(id: String): ActivityTemplate? = templates[id.lowercase(Locale.ROOT)]

    fun allTemplates(): Collection<ActivityTemplate> = templates.values

    fun templateIds(): List<String> = templates.keys.sorted()

    fun validateStartupSettings() {
        val serverId = settings.serverId.trim()
        require(serverId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) {
            "ServerGoal server-id must match [A-Za-z0-9][A-Za-z0-9_.-]{0,63}"
        }
        if (!settings.databaseStorageEnabled) {
            return
        }

        val database = settings.database
        require(database.host.isNotBlank()) {
            "ServerGoal database.host must not be blank when storage.type=database"
        }
        require(database.database.isNotBlank()) {
            "ServerGoal database.database must not be blank when storage.type=database"
        }
        require(database.username.isNotBlank()) {
            "ServerGoal database.username must not be blank when storage.type=database"
        }
        require(database.password.isNotBlank()) {
            "ServerGoal database.password must not be blank when storage.type=database"
        }
        require(database.tablePrefix.matches(Regex("[A-Za-z0-9_]+"))) {
            "ServerGoal database.table-prefix may only contain letters, numbers, and underscores"
        }
        if (database.jdbcUrl.isNotBlank()) {
            val jdbc = database.jdbcUrl.lowercase(Locale.ROOT)
            require(jdbc.startsWith("jdbc:mysql:") || jdbc.startsWith("jdbc:mariadb:")) {
                "ServerGoal database.jdbc-url must start with jdbc:mysql: or jdbc:mariadb:"
            }
        }
    }

    @Synchronized
    fun createTemplate(id: String, minutes: Int, targetTotal: Int) {
        val normalized = id.lowercase(Locale.ROOT)
        val yaml = YamlConfiguration()
        yaml.set("inherit-defaults", false)
        yaml.set("display-name", messages.raw("template.defaults.activity-display-name", mapOf("id" to normalized)))
        yaml.set("duration-minutes", minutes.coerceAtLeast(1))
        yaml.set("target-total", targetTotal.coerceAtLeast(1))
        yaml.set("collections", emptyList<String>())
        yaml.set("personal-rewards", emptyMap<String, Any>())

        yaml.set("contribution-reward.enabled", false)
        yaml.set("contribution-reward.pool-amount", 0)
        yaml.set("contribution-reward.min-contribution", 0)
        yaml.set("contribution-reward.broadcast-message-key", "activity-contribution-distributed")
        yaml.set("contribution-reward.commands", emptyList<String>())
        resources.saveCustom("activities/$normalized.yml", yaml)
        reload()
    }

    @Synchronized
    fun addHeldItemToTemplate(id: String, item: ItemStack, targetAmount: Int): String? {
        val normalized = id.lowercase(Locale.ROOT)
        if (!resources.activityTemplateIds().contains(normalized)) {
            return null
        }
        val materialKey = item.type.name.lowercase(Locale.ROOT)
        val key = "$materialKey-${System.currentTimeMillis()}"
        val activity = resources.loadMerged("activities/$normalized.yml")
        val collection = YamlConfiguration()
        val clone = ItemUtil.cloneOne(item)
        collection.set("inherit-defaults", false)
        collection.set(
            "display-name",
            messages.raw("template.defaults.detected-item-display-name", mapOf("material" to item.type.name))
        )
        collection.set("target", targetAmount.coerceAtLeast(1))
        collection.set("item-stack", clone)
        collection.set("match.material", true)
        collection.set("match.item-meta", true)
        collection.set("match.display-name", false)
        collection.set("match.custom-model-data", true)
        collection.set("match.item-model", true)
        resources.saveCustom("collections/$key.yml", collection)

        val collections = activity.getStringList("collections").toMutableList()
        if (collections.none { it.equals(key, ignoreCase = true) }) {
            collections += key
        }
        activity.set("inherit-defaults", activity.getBoolean("inherit-defaults", true))
        activity.set("collections", collections)
        resources.saveCustom("activities/$normalized.yml", activity)
        reload()
        return key
    }

    private fun loadSettings() {
        settings = PluginSettings(
            serverId = config.getString("server-id")
                ?: config.getString("sync.server-id", "server-1")
                ?: "server-1",
            debug = config.getBoolean("debug", false),
            failOpenOnDatabaseError = config.getBoolean("fail-open-on-database-error", true),
            protectionEnabled = config.getBoolean(
                "settings.protection.enabled",
                config.getBoolean("protection.enabled", true)
            ),
            adminTestMode = config.getBoolean("settings.admin-test-mode", config.getBoolean("admin-test-mode", false)),
            endWhenFinalStageComplete = config.getBoolean(
                "settings.end-when-final-stage-complete",
                config.getBoolean("end-when-final-stage-complete", true)
            ),
            saveOnSubmit = config.getBoolean("settings.save-on-submit", config.getBoolean("save-on-submit", true)),
            schedulerCheckSeconds = config.getLong(
                "settings.scheduler-check-seconds",
                config.getLong("scheduler.check-seconds", 30L)
            ).coerceAtLeast(5L),
            defaultTemplate = config.getString("default-template")
                ?: config.getString("settings.default-template", "default")
                ?: "default",
            notifications = loadNotificationSettings(),
            rotation = loadRotationSettings(),
            submission = loadSubmissionSettings(),
            storage = loadStorageSettings(),
            database = loadDatabaseSettings(),
            sync = loadSyncSettings()
        )
    }

    private fun loadSubmissionSettings(): SubmissionSettings {
        return SubmissionSettings(
            cooldownSeconds = config.getLong("settings.submission.cooldown-seconds", 2L).coerceAtLeast(0L),
            maxItemsPerSubmit = config.getInt("settings.submission.max-items-per-submit", 2304).coerceAtLeast(1)
        )
    }

    private fun loadNotificationSettings(): NotificationSettings {
        val progress = config.getConfigurationSection("notifications.progress")
        return NotificationSettings(
            progress = ProgressNotificationSettings(
                enabled = progress?.getBoolean("enabled", true) ?: true,
                intervalSeconds = progress?.getLong("interval-seconds", 300L)?.coerceAtLeast(10L) ?: 300L
            )
        )
    }

    private fun loadRotationSettings(): RotationSettings {
        val section = config.getConfigurationSection("rotation")
        return RotationSettings(
            enabled = section?.getBoolean("enabled", false) ?: false,
            autoStart = section?.getBoolean("auto-start", false) ?: false,
            intervalDays = section?.getInt("interval-days", 7)?.coerceAtLeast(1) ?: 7,
            checkIntervalSeconds = section?.getLong("check-interval-seconds", 300L)?.coerceAtLeast(60L) ?: 300L,
            pool = section?.getStringList("pool")
                ?.map { it.trim().lowercase(Locale.ROOT) }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
        )
    }

    private fun loadStorageSettings(): StorageSettings {
        val rawType = config.getString("storage.type", "yaml")
            ?.lowercase(Locale.ROOT)
            ?: "yaml"
        val type = when (rawType) {
            "database", "mysql", "mariadb" -> "database"
            else -> "yaml"
        }
        val failureStrategy = config.getString("storage.database-failure-strategy", "fail-fast")
            ?.lowercase(Locale.ROOT)
            ?: "fail-fast"
        return StorageSettings(
            type = type,
            databaseFailureStrategy = when (failureStrategy) {
                "fallback-yaml" -> "fallback-yaml"
                else -> "fail-fast"
            }
        )
    }

    private fun loadDatabaseSettings(): DatabaseSettings {
        val rawType = config.getString("database.type", "mysql")
            ?.lowercase(Locale.ROOT)
            ?: "mysql"
        val type = when (rawType) {
            "mariadb" -> "mariadb"
            else -> "mysql"
        }
        return DatabaseSettings(
            type = type,
            host = config.getString("database.host", "127.0.0.1") ?: "127.0.0.1",
            port = config.getInt("database.port", if (type == "mariadb") 3306 else 3306).coerceAtLeast(1),
            database = config.getString("database.database", "servergoal") ?: "servergoal",
            username = config.getString("database.username", "") ?: "",
            password = config.getString("database.password", "") ?: "",
            tablePrefix = config.getString("database.table-prefix", "servergoal_") ?: "servergoal_",
            jdbcUrl = config.getString("database.jdbc-url", "") ?: "",
            useSsl = config.getBoolean("database.use-ssl", false),
            requireSsl = config.getBoolean("database.require-ssl", false),
            verifyServerCertificate = config.getBoolean("database.verify-server-certificate", false),
            allowPublicKeyRetrieval = config.getBoolean("database.allow-public-key-retrieval", true),
            pool = DatabasePoolSettings(
                maximumPoolSize = config.getInt("database.pool.maximum-pool-size", 4).coerceAtLeast(1),
                minimumIdle = config.getInt("database.pool.minimum-idle", 1).coerceAtLeast(0),
                connectionTimeoutMs = config.getLong("database.pool.connection-timeout-ms", 30_000L).coerceAtLeast(250L),
                maxLifetimeMs = config.getLong("database.pool.max-lifetime-ms", 1_800_000L).coerceAtLeast(30_000L)
            )
        )
    }

    private fun loadSyncSettings(): SyncSettings {
        val sync = config.getConfigurationSection("sync")
        val legacyPollSeconds = sync?.getLong("poll-interval-seconds", 1L)?.coerceAtLeast(1L) ?: 1L
        val pollTicks = sync?.getLong("poll-interval-ticks", legacyPollSeconds * 20L)?.coerceAtLeast(20L) ?: 20L
        return SyncSettings(
            pollIntervalTicks = pollTicks,
            retryIntervalSeconds = sync?.getLong("retry-interval-seconds", 10L)?.coerceAtLeast(1L) ?: 10L,
            maxRetries = sync?.getInt("max-retries", 3)?.coerceAtLeast(0) ?: 3,
            conflictPolicy = sync?.getString("conflict-policy", "merge-max")?.lowercase(Locale.ROOT) ?: "merge-max",
            eventRetentionDays = sync?.getInt("event-retention-days", 7)?.coerceAtLeast(1) ?: 7,
            processOwnEvents = sync?.getBoolean("process-own-events", false) ?: false,
            submissionReservationExpireSeconds = sync?.getLong("submission-reservation-expire-seconds", 45L)?.coerceAtLeast(5L) ?: 45L,
            outboxClaimTimeoutSeconds = sync?.getLong("outbox-claim-timeout-seconds", 45L)?.coerceAtLeast(5L) ?: 45L,
            onlineHeartbeatExpireSeconds = sync?.getLong("online-heartbeat-expire-seconds", 90L)?.coerceAtLeast(10L) ?: 90L
        )
    }

    private fun loadTemplates(): Map<String, ActivityTemplate> {
        val result = linkedMapOf<String, ActivityTemplate>()
        for (id in resources.activityTemplateIds()) {
            val section = resources.loadMerged("activities/$id.yml")
            if (section.getKeys(false).isEmpty()) {
                continue
            }
            val acceptedItems = loadAcceptedItems(section)
            val stages = loadStages(section)
            val fallbackTarget = acceptedItems.sumOf { it.targetAmount }
                .coerceAtLeast(1)
            val targetTotal = if (section.contains("target-total")) {
                section.getInt("target-total", fallbackTarget).coerceAtLeast(1)
            } else {
                fallbackTarget
            }
            val template = ActivityTemplate(
                id = id.lowercase(Locale.ROOT),
                displayName = ColorText.colorize(
                    section.getString("display-name")
                        ?: messages.raw("template.defaults.activity-display-name", mapOf("id" to id))
                ),
                durationMinutes = section.getInt("duration-minutes", 60).coerceAtLeast(1),
                targetTotal = targetTotal,
                dynamicTarget = loadDynamicTarget(section),
                acceptedItems = acceptedItems,
                stages = stages,
                personalRewards = loadPersonalRewards(section),
                contributionReward = loadContributionReward(section)
            )
            result[template.id] = template
        }
        return result
    }

    private fun loadAcceptedItems(templateSection: ConfigurationSection): List<CollectionItem> {
        val collectionIds = templateSection.getStringList("collections")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return collectionIds.mapNotNull { collectionId ->
            val collection = resources.loadMerged("collections/$collectionId.yml")
            if (collection.getKeys(false).isEmpty()) {
                null
            } else {
                val override = collectionOverrideSection(templateSection, collectionId)
                loadCollectionItem(collectionId, collection, override)
            }
        }
    }

    private fun collectionOverrideSection(templateSection: ConfigurationSection, collectionId: String): ConfigurationSection? {
        return templateSection.getConfigurationSection("collection-overrides.$collectionId")
            ?: templateSection.getConfigurationSection("collections-options.$collectionId")
            ?: findNestedSection(templateSection.getConfigurationSection("collection-overrides"), collectionId)
            ?: findNestedSection(templateSection.getConfigurationSection("collections-options"), collectionId)
    }

    private fun findNestedSection(root: ConfigurationSection?, path: String): ConfigurationSection? {
        if (root == null) {
            return null
        }
        val parts = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            return null
        }
        var current: ConfigurationSection = root
        for (part in parts) {
            current = current.getConfigurationSection(part) ?: return null
        }
        return current
    }

    private fun loadCollectionItem(
        key: String,
        section: ConfigurationSection,
        overrideSection: ConfigurationSection? = null
    ): CollectionItem {
        val itemStack = section.getItemStack("item-stack")
        val displayItem = itemStack?.clone() ?: ItemUtil.itemFromSection(
            overrideSection?.getConfigurationSection("item") ?: section.getConfigurationSection("item"),
            fallbackMaterial = Material.matchMaterial(section.getString("material") ?: "DIAMOND") ?: Material.DIAMOND
        )
        displayItem.amount = 1
        val matchItem = itemStack?.clone() ?: ItemUtil.itemFromSection(
            section.getConfigurationSection("item"),
            fallbackMaterial = Material.matchMaterial(section.getString("material") ?: displayItem.type.name) ?: displayItem.type
        )
        matchItem.amount = 1
        val match = section.getConfigurationSection("match")
        val activityLore = overrideSection?.getStringList("lore")
            ?.takeIf { it.isNotEmpty() }
            ?: overrideSection?.getStringList("Lore")
            ?.takeIf { it.isNotEmpty() }
            ?: overrideSection?.getConfigurationSection("item")?.getStringList("Lore")
            ?.takeIf { it.isNotEmpty() }
            ?: emptyList()
        val overrideTarget = overrideSection?.let { override ->
            when {
                override.contains("target") -> override.getInt("target")
                override.contains("target-amount") -> override.getInt("target-amount")
                else -> null
            }
        }
        return CollectionItem(
            key = section.getString("id", key)?.lowercase(Locale.ROOT) ?: key.lowercase(Locale.ROOT),
            displayName = ColorText.colorize(
                overrideSection?.getString("display-name")
                    ?: section.getString("display-name")
                    ?: messages.raw("template.defaults.detected-item-display-name", mapOf("material" to displayItem.type.name))
            ),
            targetAmount = (overrideTarget ?: section.getInt("target", section.getInt("target-amount", 1)))
                .coerceAtLeast(1),
            displayItem = displayItem,
            matchItem = matchItem,
            matchRule = MatchRule(
                material = match?.getBoolean("material", true) ?: true,
                materials = match?.getStringList("materials")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.map { it.uppercase(Locale.ROOT) }
                    ?: emptyList(),
                itemMeta = match?.getBoolean("item-meta", false) ?: false,
                displayName = match?.getBoolean("display-name", false) ?: false,
                customModelData = match?.getBoolean("custom-model-data", true) ?: true,
                itemModel = match?.getBoolean("item-model", true) ?: true
            ),
            activityLore = activityLore
        )
    }

    private fun loadStages(templateSection: ConfigurationSection): List<StageDefinition> {
        val root = templateSection.getConfigurationSection("stages") ?: return emptyList()
        return root.getKeys(false).mapNotNull { key ->
            val section = root.getConfigurationSection(key) ?: return@mapNotNull null
            val index = key.toIntOrNull() ?: section.getInt("index", 0)
            if (index <= 0) return@mapNotNull null
            StageDefinition(
                index = index,
                threshold = section.getInt("threshold", 1).coerceAtLeast(1),
                displayName = ColorText.colorize(
                    section.getString("display-name")
                        ?: messages.raw("template.defaults.stage-display-name", mapOf("index" to index.toString()))
                ),
                minContribution = section.getInt("min-contribution", 1).coerceAtLeast(0),
                displayItem = ItemUtil.itemFromSection(section.getConfigurationSection("item"), fallbackMaterial = Material.NETHER_STAR),
                lore = section.getStringList("lore"),
                commands = section.getStringList("commands")
            )
        }.sortedBy { it.threshold }
    }

    private fun loadPersonalRewards(templateSection: ConfigurationSection): List<PersonalRewardDefinition> {
        val root = templateSection.getConfigurationSection("personal-rewards") ?: return emptyList()
        return root.getKeys(false).mapNotNull { key ->
            val section = root.getConfigurationSection(key) ?: return@mapNotNull null
            PersonalRewardDefinition(
                id = key.lowercase(Locale.ROOT),
                threshold = section.getInt("threshold", 1).coerceAtLeast(1),
                displayName = ColorText.colorize(
                    section.getString("display-name") ?: messages.raw("template.defaults.personal-display-name")
                ),
                displayItem = ItemUtil.itemFromSection(section.getConfigurationSection("item"), fallbackMaterial = Material.EMERALD),
                lore = section.getStringList("lore"),
                commands = section.getStringList("commands")
            )
        }.sortedBy { it.threshold }
    }

    private fun loadContributionReward(templateSection: ConfigurationSection): ContributionRewardDefinition? {
        val section = templateSection.getConfigurationSection("contribution-reward") ?: return null
        return ContributionRewardDefinition(
            enabled = section.getBoolean("enabled", true),
            poolAmount = section.getInt("pool-amount", section.getInt("amount", 0)).coerceAtLeast(0),
            minContribution = section.getInt("min-contribution", section.getInt("minimum-contribution", 0)).coerceAtLeast(0),
            commands = section.getStringList("commands"),
            broadcastMessageKey = section.getString("broadcast-message-key", "activity-contribution-distributed")
                ?: "activity-contribution-distributed"
        )
    }

    private fun loadDynamicTarget(templateSection: ConfigurationSection): DynamicTargetSettings {
        val section = templateSection.getConfigurationSection("dynamic-target") ?: return DynamicTargetSettings()
        return DynamicTargetSettings(
            enabled = section.getBoolean("enabled", false),
            basePlayers = section.getInt("base-players", 20).coerceAtLeast(1),
            minMultiplier = section.getDouble("min-multiplier", 1.0).coerceAtLeast(0.1),
            maxMultiplier = section.getDouble("max-multiplier", 1.0).coerceAtLeast(0.1)
        ).let { settings ->
            if (settings.maxMultiplier < settings.minMultiplier) {
                settings.copy(maxMultiplier = settings.minMultiplier)
            } else {
                settings
            }
        }
    }
}
