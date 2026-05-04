package ym.serverGoal.util

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

object ItemUtil {
    fun itemFromSection(
        section: ConfigurationSection?,
        placeholders: Map<String, String> = emptyMap(),
        fallbackMaterial: Material = Material.PAPER
    ): ItemStack {
        if (section == null) {
            return ItemStack(fallbackMaterial)
        }

        val material = Material.matchMaterial(
            section.getString("Material")
                ?: section.getString("material")
                ?: fallbackMaterial.name
        ) ?: fallbackMaterial
        val amount = section.getInt("Amount", section.getInt("amount", 1)).coerceIn(1, 64)
        val item = ItemStack(material, amount)
        val meta = item.itemMeta ?: return item

        val name = section.getString("Name") ?: section.getString("name")
        if (name != null) {
            meta.setDisplayName(ColorText.render(name, placeholders))
        }

        val lore = section.getStringList("Lore").ifEmpty { section.getStringList("lore") }
        if (lore.isNotEmpty()) {
            meta.lore = ColorText.renderList(lore, placeholders)
        }

        if (section.contains("CustomModelData")) {
            meta.setCustomModelData(section.getInt("CustomModelData"))
        } else if (section.contains("custom-model-data")) {
            meta.setCustomModelData(section.getInt("custom-model-data"))
        }

        if (section.getBoolean("Hide-Flags", section.getBoolean("hide-flags", false))) {
            meta.addItemFlags(*ItemFlag.values())
        }

        item.itemMeta = meta
        return item
    }

    fun cloneOne(item: ItemStack): ItemStack {
        val clone = item.clone()
        clone.amount = 1
        return clone
    }
}
