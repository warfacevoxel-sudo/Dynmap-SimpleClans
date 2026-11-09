package net.sacredlabyrinth.phaed.dynmap.simpleclans.layers

import net.sacredlabyrinth.phaed.dynmap.simpleclans.DynmapSimpleClans.debug
import net.sacredlabyrinth.phaed.dynmap.simpleclans.Helper
import net.sacredlabyrinth.phaed.dynmap.simpleclans.Helper.HEXColor
import net.sacredlabyrinth.phaed.simpleclans.Clan
import net.sacredlabyrinth.phaed.simpleclans.hooks.protection.Land
import net.sacredlabyrinth.phaed.simpleclans.managers.ClanManager
import net.sacredlabyrinth.phaed.simpleclans.managers.ProtectionManager
import org.dynmap.markers.MarkerAPI

class LandsLayer(
    clanManager: ClanManager,
    private val protectionManager: ProtectionManager,
    config: LayerConfig,
    markerAPI: MarkerAPI
) : Layer("simpleclans.layers.lands", config, markerAPI) {
    init {
        markerSet.areaMarkers.forEach { it.deleteMarker() }
        markerSet.polyLineMarkers.forEach { it.deleteMarker() }
        clanManager.clans.forEach(this::upsertMarker)
    }

    fun upsertMarker(clan: Clan) {
        val tag = clan.tag
        val home = clan.homeLocation
        val world = home?.world
        val old = markerSet.findPolyLineMarker(tag)
        if (world == null || isHidden(tag)) { old?.deleteMarker(); return }
        val land = protectionManager.getLandsAt(home).firstOrNull { it.coordinates.size >= 2 }
        if (land == null) { old?.deleteMarker(); debug("No claim found at the home of $tag clan."); return }
        val points = claimCorners(land)
        val lineColor = clanColor(clan.color)
        val marker = old ?: markerSet.createPolyLineMarker(tag, Helper.getClanLabel(config, clan), true, world.name,
            points.map { it.first }.toDoubleArray(), DoubleArray(points.size), points.map { it.second }.toDoubleArray(), true)
        marker.setLabel(Helper.getClanLabel(config, clan), true)
        marker.setLineStyle(config.getInt("style.line.weight", 3), config.getDouble("style.line.opacity", 0.8), Integer.valueOf(lineColor.removePrefix("#"), 16))
        marker.setCornerLocations(points.map { it.first }.toDoubleArray(), DoubleArray(points.size), points.map { it.second }.toDoubleArray())
    }

    private fun claimCorners(land: Land): List<Pair<Double, Double>> {
        val c = land.coordinates
        if (c.size == 2) {
            val minX = minOf(c[0].x, c[1].x); val maxX = maxOf(c[0].x, c[1].x) + 1.0
            val minZ = minOf(c[0].z, c[1].z); val maxZ = maxOf(c[0].z, c[1].z) + 1.0
            return listOf(minX to minZ, maxX to minZ, maxX to maxZ, minX to maxZ, minX to minZ)
        }
        return c.map { it.x to it.z } + listOf(c.first().let { it.x to it.z })
    }

    private fun clanColor(value: String): String {
        val color = value.trim()
        val directHex = color.removePrefix("&#").removePrefix("#")
        if (directHex.matches(Regex("[0-9a-fA-F]{6}"))) return "#$directHex"

        val legacyHex = color.replace("§", "&")
            .replace(Regex("&x(?:&[0-9a-fA-F]){6}"), "")
        val expanded = Regex("&x(&[0-9a-fA-F]){6}").find(color.replace("§", "&"))
        if (expanded != null) return "#" + expanded.value.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

        return HEXColor.of(legacyHex).code
    }

    private fun isHidden(tag: String) = config.getStringList("hidden-lands").contains(tag)
}
