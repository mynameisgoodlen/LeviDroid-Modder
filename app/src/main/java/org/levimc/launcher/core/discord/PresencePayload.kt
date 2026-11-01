package org.levimc.launcher.core.discord

class PresencePayload(val applicationId: String) {

    var sessionId: String? = null
    var type: Int = TYPE_PLAYING
    var name: String? = null
    var details: String? = null
    var state: String? = null
    var startTimestamp: Long? = null
    var endTimestamp: Long? = null
    var largeImage: String? = null
    var largeText: String? = null
    var smallImage: String? = null
    var smallText: String? = null
    var platform: String? = "desktop"

    private val buttons: MutableList<Pair<String, String>> = mutableListOf()

    fun addButton(label: String, url: String): PresencePayload {
        if (buttons.size < 2) buttons.add(label to url)
        return this
    }

    fun toActivityMap(): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to type,
            "state" to state,
            "details" to details,
            "application_id" to applicationId,
            "created_at" to System.currentTimeMillis(),
            "session_id" to sessionId,
            "platform" to platform,
            "timestamps" to linkedMapOf<String, Any?>(
                "start" to startTimestamp,
                "end" to endTimestamp
            ),
            "assets" to linkedMapOf<String, Any?>(
                "large_image" to parseImage(largeImage),
                "large_text" to largeText,
                "small_image" to parseImage(smallImage),
                "small_text" to smallText
            )
        )
        if (buttons.isNotEmpty()) {
            map["buttons"] = buttons.map { it.first }
            map["metadata"] = linkedMapOf<String, Any?>("button_urls" to buttons.map { it.second })
        }
        @Suppress("UNCHECKED_CAST")
        return clearNulls(map) as? Map<String, Any?> ?: emptyMap()
    }

    companion object {
        const val TYPE_PLAYING = 0
        const val TYPE_STREAMING = 1
        const val TYPE_LISTENING = 2
        const val TYPE_WATCHING = 3
        const val TYPE_COMPETING = 5

        @JvmStatic
        fun presence(activities: List<Map<String, Any?>>, status: String, afk: Boolean): Map<String, Any?> = linkedMapOf(
            "activities" to activities,
            "afk" to afk,
            "since" to 0,
            "status" to status
        )

        private fun parseImage(image: String?): String? {
            if (image == null) return null
            if (Regex("^[0-9]{17,19}$").matches(image)) return image
            if (image.startsWith("mp:") || image.startsWith("youtube:") || image.startsWith("spotify:") || image.startsWith("twitch:")) return image
            if (image.startsWith("external/")) return "mp:$image"
            for (prefix in listOf(
                "https://cdn.discordapp.com/",
                "http://cdn.discordapp.com/",
                "https://media.discordapp.net/",
                "http://media.discordapp.net/"
            )) {
                if (image.startsWith(prefix)) return "mp:" + image.substring(prefix.length)
            }
            val isUrl = image.startsWith("http://") || image.startsWith("https://")
            if (isUrl) throw IllegalArgumentException("INVALID_URL: resolve external images via DiscordAssets.registerExternalAssets first")
            return image
        }

        private fun clearNulls(value: Any?): Any? = when (value) {
            null -> null
            is Map<*, *> -> {
                val out = linkedMapOf<String, Any?>()
                value.forEach { (key, item) ->
                    val cleaned = clearNulls(item)
                    if (cleaned != null) out[key.toString()] = cleaned
                }
                if (out.isEmpty()) null else out
            }

            is List<*> -> value.mapNotNull { clearNulls(it) }.ifEmpty { null }
            else -> value
        }
    }
}
