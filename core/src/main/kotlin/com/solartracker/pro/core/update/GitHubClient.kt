package com.solartracker.pro.core.update

/** Minimal GitHub REST client for releases. */
class GitHubClient(
    private val http: HttpClient,
    private val apiBase: String = "https://api.github.com",
    private val userAgent: String = "SolarTrackerPRO-Updater",
) {
    fun headers(token: String, accept: String): Map<String, String> = buildMap {
        put("Accept", accept)
        put("User-Agent", userAgent)
        put("X-GitHub-Api-Version", "2022-11-28")
        if (token.isNotBlank()) put("Authorization", "Bearer ${token.trim()}")
    }

    fun releasesUrl(owner: String, repo: String) = "$apiBase/repos/$owner/$repo/releases?per_page=30"

    /** @throws UpdateException with a user-readable (Polish) message */
    fun listReleases(config: UpdateConfig): List<GitHubRelease> {
        val response = try {
            http.getFollowingRedirects(releasesUrl(config.owner, config.repo), headers(config.token, "application/vnd.github+json"))
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException("Brak połączenia z GitHubem (${e.message ?: e.javaClass.simpleName})", e)
        }
        response.use {
            val body = it.body.bufferedReader().readText()
            when (it.code) {
                200 -> return try {
                    GitHubReleaseParser.parseList(body)
                } catch (e: IllegalArgumentException) {
                    throw UpdateException("Nieprawidłowa odpowiedź GitHuba: ${e.message}", e)
                }
                401 -> throw UpdateException("Token GitHub jest nieprawidłowy lub wygasł (HTTP 401)")
                403, 429 -> throw UpdateException(forbiddenMessage(it, body, config))
                404 -> throw UpdateException(
                    "Nie znaleziono repozytorium ${config.owner}/${config.repo}. " +
                        "Prywatne repozytorium wymaga tokena z dostępem do odczytu.",
                )
                else -> throw UpdateException("Błąd GitHuba (HTTP ${it.code})")
            }
        }
    }

    /** Explains a 403/429 using GitHub's own message and rate-limit headers. */
    internal fun forbiddenMessage(response: HttpResponse, body: String, config: UpdateConfig): String {
        val githubMessage = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject)
                ?.get("message")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull()
        val rateLimited = response.code == 429 || response.header("X-RateLimit-Remaining") == "0" ||
            githubMessage?.contains("rate limit", ignoreCase = true) == true
        return when {
            rateLimited -> "Przekroczono limit zapytań GitHuba (HTTP ${response.code}) – spróbuj później" +
                if (config.token.isBlank()) " albo dodaj token" else ""
            githubMessage?.contains("personal access token", ignoreCase = true) == true ->
                "Token nie ma uprawnienia do repozytorium ${config.owner}/${config.repo}: w ustawieniach tokena na GitHubie " +
                    "dodaj Repository permissions → Contents: Read-only (HTTP 403: $githubMessage)"
            else -> "GitHub odmówił dostępu (HTTP ${response.code})" + (githubMessage?.let { ": $it" } ?: "")
        }
    }

    /** Headers for downloading a release asset through the API URL (works for private repos). */
    fun assetHeaders(config: UpdateConfig) = headers(config.token, "application/octet-stream")
}
