package ai.tiiny.resolve

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId

data class BackendConnectorStatus(
    val configured: Boolean,
    val connected: Boolean,
    val status: String,
    val defaultCalendarId: String?,
    val lastServerSyncAt: Instant?
)

data class BackendOAuthStart(
    val authorizeUrl: String,
    val redirectUri: String,
    val expiresAt: Instant?
)

class BackendClient(
    private val settings: BackendSettings,
    private val session: BackendSession? = null
) {
    private val projectUrl = settings.supabaseUrl.trim().removeSuffix("/")

    fun refreshSession(): BackendSession {
        val refreshToken = session?.refreshToken ?: error("Sign in again.")
        val response = postJson(
            url = "$projectUrl/auth/v1/token?grant_type=refresh_token",
            body = JSONObject().put("refresh_token", refreshToken),
            headers = authHeaders(includeBearer = false)
        )
        return parseSession(response)
    }

    fun status(): BackendConnectorStatus {
        val response = connector(JSONObject().put("action", "status"))
        return BackendConnectorStatus(
            configured = response.optBoolean("configured", false),
            connected = response.optBoolean("connected", false),
            status = response.optString("status", "not_connected"),
            defaultCalendarId = response.optNullableString("defaultCalendarId"),
            lastServerSyncAt = response.optNullableString("lastServerSyncAt")?.let(::instantOrNull)
        )
    }

    fun configureFeishu(appId: String, appSecret: String): BackendOAuthStart {
        val response = connector(
            JSONObject()
                .put("action", "configure")
                .put("appId", appId.trim())
                .put("appSecret", appSecret.trim())
                .put("startOAuth", true)
        )
        return oauthStartFrom(response)
    }

    fun startFeishuOAuth(): BackendOAuthStart {
        return oauthStartFrom(connector(JSONObject().put("action", "start_oauth")))
    }

    fun syncFeishuNow(): Instant {
        val response = connector(JSONObject().put("action", "sync_now"))
        return response.optNullableString("syncedAt")?.let(::instantOrNull) ?: Instant.now()
    }

    fun listEvents(feishuSettings: FeishuSettings): List<CalendarEvent> {
        val now = Instant.now()
        val startsAt = now.minusSeconds(feishuSettings.pastDays.toLong() * 24 * 3600)
        val endsAt = now.plusSeconds(feishuSettings.futureDays.toLong() * 24 * 3600)
        val response = connector(
            JSONObject()
                .put("action", "list_events")
                .put("startsAt", startsAt.toString())
                .put("endsAt", endsAt.toString())
        )
        val events = response.optJSONArray("events") ?: return emptyList()
        return (0 until events.length())
            .mapNotNull { events.optJSONObject(it)?.let(::mapServerCalendarEvent) }
            .sortedBy { it.startsAt }
    }

    fun createEvent(draft: CalendarDraft): CalendarEvent {
        val startsAt = draft.date.atTime(draft.time).atZone(ZoneId.systemDefault()).toInstant()
        val endsAt = startsAt.plusSeconds(3600)
        val response = connector(
            JSONObject()
                .put("action", "create_event")
                .put("title", draft.title.trim())
                .put("description", draft.description.trim())
                .put("startsAt", startsAt.toString())
                .put("endsAt", endsAt.toString())
        )
        val event = response.optJSONObject("event") ?: response
        return mapServerCalendarEvent(event).copy(
            sourceItemId = draft.sourceItemId,
            strategyThreadId = draft.strategyThreadId
        )
    }

    private fun oauthStartFrom(response: JSONObject): BackendOAuthStart {
        val authorizeUrl = response.optString("authorizeUrl")
        if (authorizeUrl.isBlank()) {
            error(response.optString("message", response.optString("error", "Feishu OAuth did not start.")))
        }
        return BackendOAuthStart(
            authorizeUrl = authorizeUrl,
            redirectUri = response.optString("redirectUri"),
            expiresAt = response.optNullableString("expiresAt")?.let(::instantOrNull)
        )
    }

    private fun connector(body: JSONObject): JSONObject {
        val activeSession = session ?: error("Sign in to Resolve backend first.")
        return postJson(
            url = "$projectUrl/functions/v1/feishu-connector",
            body = body,
            headers = authHeaders(includeBearer = true, accessToken = activeSession.accessToken)
        )
    }

    private fun authHeaders(includeBearer: Boolean, accessToken: String? = null): Map<String, String> {
        val anonKey = settings.anonKey.trim()
        if (projectUrl.isBlank() || anonKey.isBlank()) error("Add Supabase URL and anon key.")
        return buildMap {
            put("apikey", anonKey)
            put("content-type", "application/json")
            if (includeBearer) put("authorization", "Bearer ${accessToken ?: session?.accessToken.orEmpty()}")
        }
    }

    companion object {
        fun signInWithPassword(settings: BackendSettings, password: String): BackendSession {
            val projectUrl = settings.supabaseUrl.trim().removeSuffix("/")
            val anonKey = settings.anonKey.trim()
            if (projectUrl.isBlank() || anonKey.isBlank()) error("Add Supabase URL and anon key.")
            if (settings.email.isBlank() || password.isBlank()) error("Add email and password.")
            val response = postJson(
                url = "$projectUrl/auth/v1/token?grant_type=password",
                body = JSONObject()
                    .put("email", settings.email.trim())
                    .put("password", password),
                headers = mapOf(
                    "apikey" to anonKey,
                    "content-type" to "application/json"
                )
            )
            return parseSession(response)
        }

        fun defaultFeishuRedirectUri(settings: BackendSettings): String? {
            val projectUrl = settings.supabaseUrl.trim().removeSuffix("/")
            return projectUrl.takeIf { it.isNotBlank() }?.let { "$it/functions/v1/feishu-oauth-callback" }
        }
    }
}

fun mergeBackendCalendarEvents(existing: List<CalendarEvent>, remoteEvents: List<CalendarEvent>): List<CalendarEvent> {
    val localEvents = existing.filter { it.provider != "feishu" }
    return (remoteEvents + localEvents).sortedBy { it.startsAt }
}

private fun mapServerCalendarEvent(json: JSONObject): CalendarEvent {
    val meta = json.optJSONObject("meta") ?: JSONObject()
    val payload = json.optJSONObject("payload") ?: JSONObject()
    val id = meta.optString("id").ifBlank {
        "feishu_${meta.optString("externalCalendarId")}_${meta.optString("externalEventId")}"
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
    }
    return CalendarEvent(
        id = id,
        provider = meta.optString("provider", "feishu"),
        status = meta.optString("status", "synced"),
        title = payload.optString("title", "Untitled Feishu event").ifBlank { "Untitled Feishu event" },
        description = payload.optString("description"),
        startsAt = instantOrNull(meta.optString("startsAt")) ?: Instant.now(),
        endsAt = meta.optNullableString("endsAt")?.let(::instantOrNull),
        externalCalendarId = meta.optNullableString("externalCalendarId"),
        externalEventId = meta.optNullableString("externalEventId"),
        canEdit = meta.optBoolean("canEdit", true),
        canDelete = meta.optBoolean("canDelete", true)
    )
}

private fun postJson(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 30_000
        doInput = true
        doOutput = true
        headers.forEach { (key, value) -> setRequestProperty(key, value) }
        outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    }
    return connection.readJsonObject()
}

private fun parseSession(json: JSONObject): BackendSession {
    val accessToken = json.optString("access_token")
    if (accessToken.isBlank()) {
        error(json.optString("msg", json.optString("error_description", "Supabase sign in failed.")))
    }
    val expiresAtMillis = json.optLong("expires_at", 0L)
        .takeIf { it > 0L }
        ?.let { it * 1000 }
        ?: json.optLong("expires_in", 0L)
            .takeIf { it > 0L }
            ?.let { System.currentTimeMillis() + it * 1000 }
    return BackendSession(
        accessToken = accessToken,
        refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
        expiresAtEpochMillis = expiresAtMillis
    )
}

private fun HttpURLConnection.readJsonObject(): JSONObject {
    val stream = if (responseCode in 200..299) inputStream else errorStream
    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    val json = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("message", text) }
    if (responseCode !in 200..299) {
        val message = json.optString("message")
            .ifBlank { json.optString("msg") }
            .ifBlank { json.optString("error_description") }
            .ifBlank { json.optString("error") }
            .ifBlank { "HTTP $responseCode" }
        error(message)
    }
    return json
}

private fun instantOrNull(value: String): Instant? =
    value.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
