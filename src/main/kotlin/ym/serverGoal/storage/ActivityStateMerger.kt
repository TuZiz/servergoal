package ym.serverGoal.storage

import ym.serverGoal.model.ActiveActivity
import kotlin.math.max

object ActivityStateMerger {
    fun merge(left: ActiveActivity?, right: ActiveActivity?, conflictPolicy: String): ActiveActivity? {
        if (left == null) return clone(right)
        if (right == null) return clone(left)

        if (left.templateId != right.templateId || left.startedAt != right.startedAt) {
            return clone(if (left.updatedAt >= right.updatedAt) left else right)
        }

        if (conflictPolicy == "latest-writer") {
            return clone(if (left.updatedAt >= right.updatedAt) left else right)
        }

        val primary = if (left.updatedAt >= right.updatedAt) left else right
        val merged = clone(primary) ?: return null
        merged.endsAt = max(left.endsAt, right.endsAt)
        merged.active = left.active && right.active
        merged.completed = left.completed || right.completed
        merged.totalCollected = max(left.totalCollected, right.totalCollected)
        merged.unlockedStage = max(left.unlockedStage, right.unlockedStage)
        merged.contributionRewardDistributed = left.contributionRewardDistributed || right.contributionRewardDistributed
        merged.contributionRewardDistributedBy = chooseDistributedBy(left, right)
        merged.contributionRewardDistributedAt = chooseDistributedAt(left, right)
        merged.revision = max(left.revision, right.revision)
        merged.updatedAt = max(left.updatedAt, right.updatedAt)
        merged.updatedBy = primary.updatedBy

        mergeMax(merged.collectedByItem, left.collectedByItem)
        mergeMax(merged.collectedByItem, right.collectedByItem)
        mergeMax(merged.contributions, left.contributions)
        mergeMax(merged.contributions, right.contributions)
        mergeNestedMax(merged.serverCollectedByItem, left.serverCollectedByItem)
        mergeNestedMax(merged.serverCollectedByItem, right.serverCollectedByItem)
        mergeNestedMax(merged.serverContributions, left.serverContributions)
        mergeNestedMax(merged.serverContributions, right.serverContributions)
        mergeShardTotals(merged)
        merged.unlockedStage = max(left.unlockedStage, right.unlockedStage)
        merged.playerNames.putAll(left.playerNames)
        merged.playerNames.putAll(right.playerNames)
        mergeSets(merged.claimedStageRewards, left.claimedStageRewards)
        mergeSets(merged.claimedStageRewards, right.claimedStageRewards)
        mergeSets(merged.claimedPersonalRewards, left.claimedPersonalRewards)
        mergeSets(merged.claimedPersonalRewards, right.claimedPersonalRewards)
        return merged
    }

    private fun <K> mergeMax(target: MutableMap<K, Int>, source: Map<K, Int>) {
        for ((key, amount) in source) {
            target[key] = max(target[key] ?: 0, amount)
        }
    }

    private fun <K> mergeNestedMax(
        target: MutableMap<String, MutableMap<K, Int>>,
        source: Map<String, MutableMap<K, Int>>
    ) {
        for ((serverId, values) in source) {
            val merged = target.getOrPut(serverId) { linkedMapOf() }
            for ((key, amount) in values) {
                merged[key] = max(merged[key] ?: 0, amount)
            }
        }
    }

    private fun mergeShardTotals(activity: ActiveActivity) {
        val collectedFromShards = linkedMapOf<String, Int>()
        for (values in activity.serverCollectedByItem.values) {
            for ((key, amount) in values) {
                collectedFromShards[key] = (collectedFromShards[key] ?: 0) + amount
            }
        }
        for ((key, amount) in collectedFromShards) {
            activity.collectedByItem[key] = max(activity.collectedByItem[key] ?: 0, amount)
        }

        val contributionsFromShards = linkedMapOf<java.util.UUID, Int>()
        for (values in activity.serverContributions.values) {
            for ((uuid, amount) in values) {
                contributionsFromShards[uuid] = (contributionsFromShards[uuid] ?: 0) + amount
            }
        }
        for ((uuid, amount) in contributionsFromShards) {
            activity.contributions[uuid] = max(activity.contributions[uuid] ?: 0, amount)
        }
        activity.totalCollected = max(activity.totalCollected, activity.collectedByItem.values.sum())
    }

    private fun chooseDistributedBy(left: ActiveActivity, right: ActiveActivity): String {
        val candidates = listOf(left, right).filter { it.contributionRewardDistributed }
        if (candidates.isEmpty()) {
            return ""
        }
        return candidates
            .minByOrNull { candidate ->
                val at = candidate.contributionRewardDistributedAt
                if (at > 0L) at else Long.MAX_VALUE
            }
            ?.contributionRewardDistributedBy
            .orEmpty()
            .ifBlank {
                candidates.first().updatedBy
            }
    }

    private fun chooseDistributedAt(left: ActiveActivity, right: ActiveActivity): Long {
        val candidates = listOf(left, right).filter { it.contributionRewardDistributed }
        if (candidates.isEmpty()) {
            return 0L
        }
        return candidates
            .minByOrNull { candidate ->
                val at = candidate.contributionRewardDistributedAt
                if (at > 0L) at else Long.MAX_VALUE
            }
            ?.contributionRewardDistributedAt
            ?: 0L
    }

    private fun <K, V> mergeSets(target: MutableMap<K, MutableSet<V>>, source: Map<K, Set<V>>) {
        for ((key, values) in source) {
            target.getOrPut(key) { linkedSetOf() }.addAll(values)
        }
    }

    private fun clone(activity: ActiveActivity?): ActiveActivity? {
        if (activity == null) {
            return null
        }
        val yaml = ActivityCodec.encode(activity)
        return ActivityCodec.decode(yaml)
    }
}
