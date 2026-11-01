package org.levimc.launcher.core.discord

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object DiscordAssets {

    private const val EXTERNAL_ASSETS_URL = "https://discord.com/api/applications/%s/external-assets"

    @JvmStatic
    @Throws(IOException::class)
    fun registerExternalAssets(
        client: OkHttpClient,
        bearerToken: String,
        applicationId: String,
        urls: List<String>
    ): List<String> {
        val body = JSONObject()
            .put("urls", JSONArray(urls))
            .put("content_rating", 0)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(String.format(EXTERNAL_ASSETS_URL, applicationId))
            .header("Authorization", bearerToken)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: "[]"
            if (!response.isSuccessful) {
                throw IOException("External asset registration failed: ${response.code} $text")
            }
            val json = JSONArray(text)
            return (0 until json.length()).map { i ->
                json.getJSONObject(i).optString("external_asset_path", "")
            }
        }
    }
}
