package com.solartracker.pro.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** A downloadable file attached to a release. [apiUrl] works for public and private repositories. */
data class ReleaseAsset(
    val id: Long,
    val name: String,
    val size: Long,
    val apiUrl: String,
    val browserDownloadUrl: String,
)

data class GitHubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val draft: Boolean,
    val preRelease: Boolean,
    val publishedAt: String?,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>,
) {
    val version: SemanticVersion? get() = SemanticVersion.parse(tagName)
}

/** Parses the GitHub REST API `GET /repos/{owner}/{repo}/releases` response. */
object GitHubReleaseParser {

    /** @throws IllegalArgumentException for a response that is not a release list */
    fun parseList(json: String): List<GitHubRelease> {
        val root = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON", e)
        }
        val array = root as? JsonArray ?: run {
            val message = ((root as? JsonObject)?.get("message") as? JsonPrimitive)?.content
            throw IllegalArgumentException("Expected a release list" + (message?.let { ": $it" } ?: ""))
        }
        return array.mapNotNull { (it as? JsonObject)?.let(::parseRelease) }
    }

    private fun parseRelease(o: JsonObject): GitHubRelease? {
        val tag = o.string("tag_name") ?: return null
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
            val obj = a as? JsonObject ?: return@mapNotNull null
            ReleaseAsset(
                id = obj.long("id") ?: return@mapNotNull null,
                name = obj.string("name") ?: return@mapNotNull null,
                size = obj.long("size") ?: -1,
                apiUrl = obj.string("url") ?: return@mapNotNull null,
                browserDownloadUrl = obj.string("browser_download_url").orEmpty(),
            )
        }
        return GitHubRelease(
            tagName = tag,
            name = o.string("name") ?: tag,
            body = o.string("body").orEmpty(),
            draft = o.bool("draft") ?: false,
            preRelease = o.bool("prerelease") ?: false,
            publishedAt = o.string("published_at"),
            htmlUrl = o.string("html_url").orEmpty(),
            assets = assets,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
