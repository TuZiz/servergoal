package ym.serverGoal.util

import net.md_5.bungee.api.ChatColor

object ColorText {
    private val rgbTag = Regex("<#([A-Fa-f0-9]{6})>")
    private val rgbCloseTag = Regex("</#([A-Fa-f0-9]{6})>")
    private val rgbAmp = Regex("&#([A-Fa-f0-9]{6})")

    fun applyPlaceholders(text: String, placeholders: Map<String, String>): String {
        var result = text
        for ((key, value) in placeholders) {
            result = result.replace("%$key%", value)
        }
        return result
    }

    fun colorize(text: String): String {
        var result = rgbCloseTag.replace(text, "")
        result = rgbTag.replace(result) { match -> ChatColor.of("#${match.groupValues[1]}").toString() }
        result = rgbAmp.replace(result) { match -> ChatColor.of("#${match.groupValues[1]}").toString() }
        return ChatColor.translateAlternateColorCodes('&', result)
    }

    fun render(text: String, placeholders: Map<String, String> = emptyMap()): String {
        return colorize(applyPlaceholders(text, placeholders))
    }

    fun renderList(lines: List<String>, placeholders: Map<String, String> = emptyMap()): List<String> {
        return lines.map { render(it, placeholders) }
    }
}
