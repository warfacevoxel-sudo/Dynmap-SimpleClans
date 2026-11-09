package net.sacredlabyrinth.phaed.dynmap.simpleclans.layers

import com.google.gson.JsonParser
import net.sacredlabyrinth.phaed.dynmap.simpleclans.DynmapSimpleClans
import net.sacredlabyrinth.phaed.dynmap.simpleclans.DynmapSimpleClans.debug
import net.sacredlabyrinth.phaed.dynmap.simpleclans.Helper
import net.sacredlabyrinth.phaed.dynmap.simpleclans.IconStorage
import net.sacredlabyrinth.phaed.simpleclans.Clan
import net.sacredlabyrinth.phaed.simpleclans.managers.ClanManager
import org.bukkit.Bukkit
import org.bukkit.Location
import org.dynmap.markers.Marker
import org.dynmap.markers.MarkerAPI
import org.dynmap.markers.MarkerIcon
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HomesLayer(
    private val clanManager: ClanManager,
    val iconStorage: IconStorage,
    config: LayerConfig,
    private val markerAPI: MarkerAPI
) : Layer("simpleclans.layers.home", config, markerAPI) {

    init {
        // Delete old markers
        markerSet.markers.forEach(Marker::deleteMarker)

        // Create/update new markers
        getClansWithHome().forEach(this::upsertMarker)
    }

    fun upsertMarker(clan: Clan) {
        val tag = clan.tag
        val loc = clan.homeLocation ?: return
        val world = loc.world ?: return
        val worldName = world.name

        if (isHidden(tag, worldName)) {
            debug("[HomesLayer] Marker can't be updated/created, because clan($tag) or world($worldName) is hidden!")
            return
        }

        val flagsJson = JsonParser().parse(clan.flags).asJsonObject
        val emblemUrl = flagsJson.get("emblemurl")?.asString

        if (!emblemUrl.isNullOrBlank()) {
            val iconId = "emblem_$tag"
            val existing = markerAPI.getMarkerIcon(iconId)
            if (existing != null && existing.markerIconLabel == emblemUrl) {
                // Cache hit: URL unchanged – update marker immediately on current thread
                applyMarker(clan, tag, loc, worldName, existing)
            } else {
                // Need to (re)download – do it off the main thread to avoid server lag
                existing?.deleteIcon()
                // Show the default icon in the meantime while the emblem is loading
                applyMarker(clan, tag, loc, worldName, iconStorage.getIcon(iconStorage.defaultIconName))
                val plugin = DynmapSimpleClans.getInstance()
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    val imageBytes = downloadImageBytes(tag, emblemUrl) ?: return@Runnable
                    // Switch back to the main thread to register the icon and update the marker.
                    // Guard against the plugin being unloaded (e.g. PlugManX) while the
                    // download was in progress.
                    if (!plugin.isEnabled) return@Runnable
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        try {
                            val icon = markerAPI.createMarkerIcon(iconId, emblemUrl,
                                ByteArrayInputStream(imageBytes))
                            if (icon != null) {
                                applyMarker(clan, tag, loc, worldName, icon)
                            } else {
                                debug("[HomesLayer] markerAPI.createMarkerIcon returned null for clan $tag")
                            }
                        } catch (e: Exception) {
                            debug("[HomesLayer] Failed to register emblem icon for clan $tag: ${e.message}")
                        }
                    })
                })
            }
        } else {
            val iconName = flagsJson.get("defaulticon")?.asString ?: iconStorage.defaultIconName
            applyMarker(clan, tag, loc, worldName, iconStorage.getIcon(iconName))
        }
    }

    /**
     * Creates or updates a Dynmap marker for the given clan at the given location.
     * Must be called on the main server thread.
     */
    private fun applyMarker(clan: Clan, tag: String, loc: Location, worldName: String, icon: MarkerIcon) {
        val label = Helper.getClanLabel(config, clan)
        val marker = markerSet.findMarker(tag) ?: markerSet.createMarker(
            tag, label, true, worldName, loc.x, loc.y, loc.z, icon, true
        )
        marker.setLocation(worldName, loc.x, loc.y, loc.z)
        marker.setLabel(label, true)
        marker.markerIcon = icon
    }

    /**
     * Removes the cached emblem [MarkerIcon] for the given clan tag from Dynmap.
     * Should be called before [upsertMarker] when the emblem is being cleared,
     * so the marker falls back to the default icon.
     *
     * @param tag the clan tag whose emblem icon should be deleted
     */
    fun clearEmblemIcon(tag: String) {
        markerAPI.getMarkerIcon("emblem_$tag")?.deleteIcon()
    }

    /**
     * Downloads the raw bytes of an image from the given HTTPS URL.
     *
     * Runs entirely off the main thread. Validates that the server responds with an
     * `image/*` Content-Type so that arbitrary non-image URLs are rejected gracefully.
     * Follows up to [MAX_REDIRECTS] HTTP redirects. Rejects responses larger than
     * [MAX_IMAGE_SIZE_BYTES] to prevent abuse.
     * Returns `null` on any network, I/O, or content-type error.
     *
     * @param tag  clan tag used only for debug logging
     * @param url  the HTTPS image URL (already validated before storage)
     * @return image bytes, or `null` if the download failed or the response is not an image
     */
    private fun downloadImageBytes(tag: String, url: String): ByteArray? {
        return try {
            var currentUrl = url
            var redirects = 0
            var connection: java.net.HttpURLConnection

            // Follow redirects manually so we can handle https→https cross-domain redirects
            while (true) {
                connection = java.net.URL(currentUrl).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = false
                // Identify as a browser-like client so CDNs (e.g. VK) don't block the request
                connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (compatible; Dynmap-SimpleClans)")
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    if (++redirects > MAX_REDIRECTS) {
                        debug("[HomesLayer] Too many redirects for emblem of clan $tag")
                        connection.disconnect()
                        return null
                    }
                    val location = connection.getHeaderField("Location") ?: run {
                        debug("[HomesLayer] Redirect with no Location header for clan $tag")
                        connection.disconnect()
                        return null
                    }
                    connection.disconnect()
                    // Resolve relative redirects against the current URL
                    currentUrl = java.net.URL(java.net.URL(currentUrl), location).toString()
                    continue
                }
                break
            }

            val contentType = connection.contentType ?: ""
            if (!contentType.startsWith("image/")) {
                debug("[HomesLayer] Emblem URL for clan $tag returned non-image Content-Type: $contentType")
                connection.disconnect()
                return null
            }

            connection.inputStream.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var totalRead = 0
                var read: Int
                while (input.read(chunk).also { read = it } != -1) {
                    totalRead += read
                    if (totalRead > MAX_IMAGE_SIZE_BYTES) {
                        debug("[HomesLayer] Emblem image for clan $tag exceeds ${MAX_IMAGE_SIZE_BYTES / 1024} KB limit")
                        return null
                    }
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        } catch (e: Exception) {
            debug("[HomesLayer] Failed to download emblem for clan $tag from $url: ${e.message}")
            null
        }
    }

    companion object {
        /** Maximum number of HTTP redirects to follow when downloading an emblem. */
        private const val MAX_REDIRECTS = 5
        /** Maximum allowed emblem image size (512 KB). */
        private const val MAX_IMAGE_SIZE_BYTES = 512 * 1024
    }

    private fun isHidden(tag: String, worldName: String): Boolean {
        val hidden = config.getStringList("hidden-markers")
        return hidden.contains(tag) || hidden.contains("world:$worldName")
    }

    private fun getClansWithHome(): Set<Clan> {
        return clanManager.clans
            .filter { clan -> clan.homeLocation != null }
            .filter { clan -> clan.homeLocation!!.world != null }
            .toSet()
    }
}