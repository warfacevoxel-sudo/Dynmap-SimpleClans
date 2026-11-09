package net.sacredlabyrinth.phaed.dynmap.simpleclans.layers

import net.sacredlabyrinth.phaed.dynmap.simpleclans.DynmapSimpleClans.debug
import net.sacredlabyrinth.phaed.dynmap.simpleclans.Helper
import net.sacredlabyrinth.phaed.dynmap.simpleclans.Helper.HEXColor
import net.sacredlabyrinth.phaed.simpleclans.Clan
import net.sacredlabyrinth.phaed.simpleclans.hooks.protection.Coordinate
import net.sacredlabyrinth.phaed.simpleclans.hooks.protection.Land
import net.sacredlabyrinth.phaed.simpleclans.managers.ClanManager
import net.sacredlabyrinth.phaed.simpleclans.managers.ProtectionManager
import org.bukkit.Bukkit
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
        val oldMarkers = markerSet.polyLineMarkers.filter { it.markerID.startsWith("$tag:") }
        if (isHidden(tag)) {
            oldMarkers.forEach { it.deleteMarker() }
            return
        }

        val world = clan.homeLocation?.world
        if (world == null) {
            oldMarkers.forEach { it.deleteMarker() }
            return
        }

        // Only draw claims owned by clan members. Looking up claims at the home location
        // could accidentally select an administrator's private when the home is inside it.
        val lands = clan.members.asSequence()
            .map { Bukkit.getOfflinePlayer(it.uniqueId) }
            .flatMap { protectionManager.getLandsOf(it, world).asSequence() }
            .distinct()
            .toList()
        val boundaries = exteriorEdges(lands)
        if (boundaries.isEmpty()) {
            oldMarkers.forEach { it.deleteMarker() }
            debug("No member-owned coordinates found for $tag clan; removed stale land markers.")
            return
        }

        var lineColor = config.getString("style.line.color", "#2d682d")
        if (config.getBoolean("style.based-on-tag", false)) lineColor = HEXColor.of(clan.color).code
        val color = Integer.valueOf(lineColor.replace("#", ""), 16)
        val lineOpacity = config.getDouble("style.line.opacity", 0.8)
        val lineWeight = config.getInt("style.line.weight", 3)
        val label = Helper.getClanLabel(config, clan)

        // Draw only exposed claim edges. Shared edges between touching claims disappear.
        val wantedIds = boundaries.indices.map { "$tag:$it" }.toSet()
        oldMarkers.filter { it.markerID !in wantedIds }.forEach { it.deleteMarker() }
        boundaries.forEachIndexed { index, edge ->
            val id = "$tag:$index"
            val marker = markerSet.findPolyLineMarker(id) ?: markerSet.createPolyLineMarker(
                id, label, true, world.name,
                doubleArrayOf(edge.first.x, edge.second.x),
                doubleArrayOf(0.0, 0.0),
                doubleArrayOf(edge.first.z, edge.second.z), true
            )
            marker.setLabel(label, true)
            marker.setLineStyle(lineWeight, lineOpacity, color)
            marker.setCornerLocations(
                doubleArrayOf(edge.first.x, edge.second.x),
                doubleArrayOf(0.0, 0.0),
                doubleArrayOf(edge.first.z, edge.second.z)
            )
        }
    }

    private data class Point(val x: Double, val z: Double)
    private data class Edge(val first: Point, val second: Point)

    private fun exteriorEdges(lands: List<Land>): List<Edge> {
        val edges = linkedSetOf<Edge>()
        for (land in lands) {
            val points = land.coordinates.map(Coordinate::point)
            if (points.size < 2) continue
            for (i in points.indices) {
                val edge = Edge(points[i], points[(i + 1) % points.size])
                val reverse = Edge(edge.second, edge.first)
                if (!edges.remove(reverse)) edges.add(edge)
            }
        }
        return edges.toList()
    }

    private fun Coordinate.point() = Point(x, z)
    private fun isHidden(tag: String) = config.getStringList("hidden-lands").contains(tag)
}
