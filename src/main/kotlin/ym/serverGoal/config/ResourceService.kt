package ym.serverGoal.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ym.serverGoal.platform.MainThreadIoGuard
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

class ResourceService(private val plugin: JavaPlugin) {
    private val bundledFiles = listOf(
        "config.yml",
        "gui/main.yml",
        "lang/zh_cn.yml",
        "activities/default.yml"
    )

    fun releaseDefaults() {
        MainThreadIoGuard.reject("release default resources")
        for (path in bundledFiles) {
            releaseIfMissing(path)
        }
    }

    fun loadMerged(path: String): YamlConfiguration {
        MainThreadIoGuard.reject("load merged resource: $path")
        val bundled = loadBundled(path)
        val customFile = customFile(path)
        if (!customFile.exists()) {
            return bundled
        }
        val custom = YamlConfiguration.loadConfiguration(customFile)
        if (custom.getBoolean("inherit-defaults", true).not()) {
            return custom
        }
        val merged = YamlConfiguration()
        mergeInto(merged, bundled)
        mergeInto(merged, custom)
        return merged
    }

    fun loadCustom(path: String): YamlConfiguration {
        MainThreadIoGuard.reject("load custom resource: $path")
        val file = customFile(path)
        return if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
    }

    fun saveCustom(path: String, yaml: YamlConfiguration) {
        MainThreadIoGuard.reject("save custom resource: $path")
        val file = customFile(path)
        file.parentFile?.mkdirs()
        yaml.save(file)
    }

    fun customFile(path: String): File = File(plugin.dataFolder, path)

    fun activityTemplateIds(): Set<String> {
        MainThreadIoGuard.reject("enumerate activity templates")
        val result = linkedSetOf<String>()
        for (path in bundledFiles) {
            if (path.startsWith("activities/") && path.endsWith(".yml", ignoreCase = true)) {
                val name = File(path).nameWithoutExtension
                if (name != "_index") {
                    result += name.lowercase(Locale.ROOT)
                }
            }
        }

        val customIndex = customFile("activities/_index.yml")
        if (customIndex.exists()) {
            result += YamlConfiguration.loadConfiguration(customIndex)
                .getStringList("templates")
                .map { it.lowercase(Locale.ROOT) }
        }

        val customActivities = customFile("activities")
        if (customActivities.isDirectory) {
            customActivities.listFiles { file ->
                file.isFile && file.extension.equals("yml", ignoreCase = true) && file.nameWithoutExtension != "_index"
            }?.forEach { result += it.nameWithoutExtension.lowercase(Locale.ROOT) }
        }
        return result
    }

    private fun releaseIfMissing(path: String) {
        MainThreadIoGuard.reject("release resource: $path")
        val target = customFile(path)
        if (target.exists()) {
            return
        }
        val input = bundledInput(path) ?: return
        target.parentFile?.mkdirs()
        input.use { source ->
            target.outputStream().use { output ->
                source.copyTo(output)
            }
        }
    }

    private fun loadBundled(path: String): YamlConfiguration {
        MainThreadIoGuard.reject("load bundled resource: $path")
        val input = bundledInput(path) ?: return YamlConfiguration()
        input.use { source ->
            InputStreamReader(source, StandardCharsets.UTF_8).use { reader ->
                return YamlConfiguration.loadConfiguration(reader)
            }
        }
    }

    private fun bundledInput(path: String) = plugin.getResource(path) ?: plugin.getResource("resources/$path")

    private fun mergeInto(target: YamlConfiguration, source: ConfigurationSection) {
        mergeSection(target, source, "")
    }

    private fun mergeSection(target: YamlConfiguration, sourceSection: ConfigurationSection, path: String) {
        for (key in sourceSection.getKeys(false)) {
            val fullPath = if (path.isEmpty()) key else "$path.$key"
            val section = sourceSection.getConfigurationSection(key)
            if (section != null) {
                if (!target.isConfigurationSection(fullPath)) {
                    target.createSection(fullPath)
                }
                mergeSection(target, section, fullPath)
            } else {
                target.set(fullPath, sourceSection.get(key))
            }
        }
    }
}
