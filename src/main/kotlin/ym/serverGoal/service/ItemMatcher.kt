package ym.serverGoal.service

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import ym.serverGoal.model.CollectionItem

class ItemMatcher {
    fun matches(candidate: ItemStack?, item: CollectionItem): Boolean {
        if (candidate == null || candidate.type.isAir) {
            return false
        }
        val prototype = item.matchItem
        val rule = item.matchRule

        if (rule.materials.isNotEmpty()) {
            if (rule.materials.none { it.equals(candidate.type.name, ignoreCase = true) }) {
                return false
            }
        } else if (rule.material && candidate.type != prototype.type) {
            return false
        }

        if (rule.itemMeta) {
            val candidateOne = candidate.clone()
            candidateOne.amount = 1
            val prototypeOne = prototype.clone()
            prototypeOne.amount = 1
            return candidateOne.isSimilar(prototypeOne)
        }

        val candidateMeta = candidate.itemMeta
        val prototypeMeta = prototype.itemMeta
        if (rule.displayName && displayName(candidateMeta) != displayName(prototypeMeta)) {
            return false
        }
        if (rule.customModelData && customModelData(candidateMeta) != customModelData(prototypeMeta)) {
            return false
        }
        if (rule.itemModel && itemModel(candidateMeta) != itemModel(prototypeMeta)) {
            return false
        }
        return true
    }

    private fun displayName(meta: ItemMeta?): String? {
        if (meta == null || !meta.hasDisplayName()) {
            return null
        }
        return meta.displayName
    }

    private fun customModelData(meta: ItemMeta?): Int? {
        if (meta == null || !meta.hasCustomModelData()) {
            return null
        }
        return meta.customModelData
    }

    private fun itemModel(meta: ItemMeta?): String? {
        if (meta == null) {
            return null
        }
        return runCatching {
            val hasMethod = meta.javaClass.methods.firstOrNull {
                it.name == "hasItemModel" && it.parameterCount == 0
            }
            if (hasMethod != null && hasMethod.invoke(meta) == false) {
                return null
            }
            val method = meta.javaClass.methods.firstOrNull {
                it.name == "getItemModel" && it.parameterCount == 0
            } ?: return null
            method.invoke(meta)?.toString()
        }.getOrNull()
    }
}
