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

class BackendApiException(
    val code: String?,
    override val message: String
) : IllegalStateException(message)

fun Throwable.needsCalendarAuthorization(): Boolean {
    val text = message.orEmpty().lowercase()
    return (this is BackendApiException && code == "feishu_needs_auth") ||
        text.contains("calendar needs authorization") ||
        text.contains("connect calendar again") ||
        text.contains("missing feishu refresh token") ||
        text.contains("missing feishu token set")
}

class BackendClient(
    private val settings: BackendSettings,
    private val session: BackendSession? = null
) {
    private val projectUrl = settings.supabaseUrl.trim().ifBlank { ResolveSupabaseUrl }.removeSuffix("/")

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

    fun startFeishuOAuth(): BackendOAuthStart {
        return oauthStartFrom(connector(JSONObject().put("action", "start_oauth")))
    }

    fun syncFeishuNow(): Instant {
        val response = connector(JSONObject().put("action", "sync_now"))
        return response.optNullableString("syncedAt")?.let(::instantOrNull) ?: Instant.now()
    }

    fun listEvents(feishuSettings: FeishuSettings): List<CalendarEvent> {
        val now = Instant.now()
        val startsAt = now.minusSeconds(feishuSettings.pastDays.coerceAtLeast(FullFeishuSyncPastDays).toLong() * 24 * 3600)
        val endsAt = now.plusSeconds(feishuSettings.futureDays.coerceAtLeast(FullFeishuSyncFutureDays).toLong() * 24 * 3600)
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

    fun updateEvent(event: CalendarEvent, draft: CalendarDraft): CalendarEvent {
        val calendarId = event.externalCalendarId ?: error("Missing Feishu calendar id.")
        val eventId = event.externalEventId ?: error("Missing Feishu event id.")
        val startsAt = draft.date.atTime(draft.time).atZone(ZoneId.systemDefault()).toInstant()
        val durationSeconds = event.endsAt?.epochSecond?.minus(event.startsAt.epochSecond)?.coerceAtLeast(0) ?: 3600
        val endsAt = startsAt.plusSeconds(durationSeconds)
        val response = connector(
            JSONObject()
                .put("action", "update_event")
                .put("calendarId", calendarId)
                .put("eventId", eventId)
                .put("title", draft.title.trim())
                .put("description", draft.description.trim())
                .put("startsAt", startsAt.toString())
                .put("endsAt", endsAt.toString())
        )
        val updated = response.optJSONObject("event") ?: response
        return mapServerCalendarEvent(updated).copy(
            sourceItemId = event.sourceItemId,
            strategyThreadId = event.strategyThreadId
        )
    }

    fun deleteEvent(event: CalendarEvent): Instant {
        val calendarId = event.externalCalendarId ?: error("Missing Feishu calendar id.")
        val eventId = event.externalEventId ?: error("Missing Feishu event id.")
        val response = connector(
            JSONObject()
                .put("action", "delete_event")
                .put("calendarId", calendarId)
                .put("eventId", eventId)
        )
        return response.optNullableString("syncedAt")?.let(::instantOrNull) ?: Instant.now()
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
        val activeSession = session ?: error("Sign in first.")
        return postJson(
            url = "$projectUrl/functions/v1/feishu-connector",
            body = body,
            headers = authHeaders(includeBearer = true, accessToken = activeSession.accessToken)
        )
    }

    private fun authHeaders(includeBearer: Boolean, accessToken: String? = null): Map<String, String> {
        val anonKey = settings.anonKey.trim().ifBlank { ResolveSupabasePublishableKey }
        if (projectUrl.isBlank() || anonKey.isBlank()) error("Sync is not configured.")
        return buildMap {
            put("apikey", anonKey)
            put("content-type", "application/json")
            if (includeBearer) put("authorization", "Bearer ${accessToken ?: session?.accessToken.orEmpty()}")
        }
    }

    companion object {
        fun signInWithPassword(settings: BackendSettings, password: String): BackendSession {
            val projectUrl = settings.supabaseUrl.trim().ifBlank { ResolveSupabaseUrl }.removeSuffix("/")
            val anonKey = settings.anonKey.trim().ifBlank { ResolveSupabasePublishableKey }
            if (projectUrl.isBlank() || anonKey.isBlank()) error("Sync is not configured.")
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
            val projectUrl = settings.supabaseUrl.trim().ifBlank { ResolveSupabaseUrl }.removeSuffix("/")
            return projectUrl.takeIf { it.isNotBlank() }?.let { "$it/functions/v1/feishu-oauth-callback" }
        }
    }
}

fun mergeBackendCalendarEvents(existing: List<CalendarEvent>, remoteEvents: List<CalendarEvent>): List<CalendarEvent> {
    val localEvents = existing.filter { it.provider != "feishu" }
    return normalizeCalendarEvents(remoteEvents + localEvents)
}

fun normalizeCalendarEvents(events: List<CalendarEvent>): List<CalendarEvent> {
    val byRemoteIdentity = linkedMapOf<String, CalendarEvent>()
    val localEvents = mutableListOf<CalendarEvent>()

    for (event in events) {
        val key = calendarRemoteIdentityKey(event)
        if (key == null) {
            localEvents.add(event)
            continue
        }
        val existing = byRemoteIdentity[key]
        if (existing == null || preferCalendarEvent(event, existing)) {
            byRemoteIdentity[key] = event
        }
    }

    val visibleRemote = linkedMapOf<String, CalendarEvent>()
    for (event in byRemoteIdentity.values) {
        val key = calendarDisplayKey(event)
        val existing = visibleRemote[key]
        if (existing == null || preferCalendarEvent(event, existing)) {
            visibleRemote[key] = event
        }
    }

    return (visibleRemote.values + localEvents)
        .distinctBy { calendarStorageKey(it) }
        .sortedBy { it.startsAt }
}

fun replaceCalendarEvent(events: List<CalendarEvent>, original: CalendarEvent, replacement: CalendarEvent): List<CalendarEvent> {
    var replaced = false
    val next = events.map { event ->
        if (sameCalendarEventIdentity(event, original)) {
            replaced = true
            replacement
        } else {
            event
        }
    }
    return (if (replaced) next else next + replacement).sortedBy { it.startsAt }
}

private fun sameCalendarEventIdentity(left: CalendarEvent, right: CalendarEvent): Boolean {
    val leftExternal = left.externalCalendarId?.let { calendarId ->
        left.externalEventId?.let { eventId -> "$calendarId:$eventId" }
    }
    val rightExternal = right.externalCalendarId?.let { calendarId ->
        right.externalEventId?.let { eventId -> "$calendarId:$eventId" }
    }
    return when {
        leftExternal != null && rightExternal != null -> leftExternal == rightExternal
        else -> left.id == right.id
    }
}

private fun calendarRemoteIdentityKey(event: CalendarEvent): String? {
    val eventId = event.externalEventId ?: return null
    return listOf(
        eventId,
        event.startsAt.toString(),
        event.endsAt?.toString().orEmpty()
    ).joinToString("|")
}

fun calendarDisplayKey(event: CalendarEvent): String =
    listOf(
        event.startsAt.toString(),
        event.endsAt?.toString().orEmpty(),
        event.title.trim().lowercase()
    ).joinToString("|")

private fun calendarStorageKey(event: CalendarEvent): String =
    calendarRemoteIdentityKey(event) ?: event.id

private fun preferCalendarEvent(candidate: CalendarEvent, existing: CalendarEvent): Boolean {
    val candidateScore = calendarPreferenceScore(candidate)
    val existingScore = calendarPreferenceScore(existing)
    if (candidateScore != existingScore) return candidateScore > existingScore
    return candidate.id >= existing.id
}

private fun calendarPreferenceScore(event: CalendarEvent): Int {
    var score = 0
    if (event.externalCalendarId == "primary") score += 8
    if (event.canEdit) score += 2
    if (event.canDelete) score += 1
    if (event.provider == "feishu") score += 1
    return score
}

private fun mapServerCalendarEvent(json: JSONObject): CalendarEvent {
    val meta = json.optJSONObject("meta") ?: JSONObject()
    val payload = json.optJSONObject("payload") ?: JSONObject()
    val description = payload.optString("description")
    val id = meta.optString("id").ifBlank {
        "feishu_${meta.optString("externalCalendarId")}_${meta.optString("externalEventId")}"
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
    }
    return CalendarEvent(
        id = id,
        provider = meta.optString("provider", "feishu"),
        status = meta.optString("status", "synced"),
        title = payload.optString("title", "Untitled Feishu event").ifBlank { "Untitled Feishu event" },
        description = description,
        recurrence = payload.optNullableString("recurrence"),
        meetingUrl = payload.optNullableString("meetingUrl")
            ?: extractMeetingUrlFromText(description)
            ?: extractMeetingUrlFromJson(payload),
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
        val code = json.optString("error").takeIf { it.isNotBlank() }
        val message = json.optString("message")
            .ifBlank { json.optString("msg") }
            .ifBlank { json.optString("error_description") }
            .ifBlank { json.optString("error") }
            .ifBlank { "HTTP $responseCode" }
        throw BackendApiException(code, message)
    }
    return json
}

private fun instantOrNull(value: String): Instant? =
    value.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
