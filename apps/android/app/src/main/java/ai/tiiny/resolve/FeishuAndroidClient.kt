package ai.tiiny.resolve

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.concurrent.thread

const val AndroidFeishuRedirectUri = "http://127.0.0.1:36321/oauth/feishu/callback"

private const val FeishuApiBase = "https://open.feishu.cn/open-apis"

class FeishuAndroidClient(
    private val appId: String,
    private val appSecret: String,
    private val tokenSet: FeishuTokenSet
) {
    fun listEvents(settings: FeishuSettings): List<CalendarEvent> {
        val calendarId = resolveCalendarId(settings.defaultCalendar)
        val now = Instant.now()
        val startsAt = now.minusSeconds(settings.pastDays.toLong() * 24 * 3600)
        val endsAt = now.plusSeconds(settings.futureDays.toLong() * 24 * 3600)
        val items = mutableListOf<CalendarEvent>()
        var pageToken: String? = null

        do {
            val query = mutableMapOf(
                "page_size" to "1000",
                "start_time" to startsAt.epochSecond.toString(),
                "end_time" to endsAt.epochSecond.toString()
            )
            if (!pageToken.isNullOrBlank()) query["page_token"] = pageToken
            val response = request(
                path = "/calendar/v4/calendars/${encode(calendarId)}/events?${query.toQueryString()}",
                method = "GET"
            )
            val data = response.optJSONObject("data") ?: JSONObject()
            val eventItems = data.optJSONArray("items")
            if (eventItems != null) {
                for (index in 0 until eventItems.length()) {
                    eventItems.optJSONObject(index)?.let { eventJson ->
                        val event = mapEvent(calendarId, eventJson)
                        if (event.externalEventId?.isNotBlank() == true && event.status != "cancelled") {
                            items += event
                        }
                    }
                }
            }
            pageToken = data.optString("page_token").takeIf { data.optBoolean("has_more") && it.isNotBlank() }
        } while (!pageToken.isNullOrBlank())

        return items.sortedBy { it.startsAt }
    }

    fun createEvent(settings: FeishuSettings, draft: CalendarDraft): CalendarEvent {
        val calendarId = resolveCalendarId(settings.defaultCalendar)
        val startsAt = draft.date.atTime(draft.time).atZone(ZoneId.systemDefault()).toInstant()
        val endsAt = startsAt.plusSeconds(3600)
        val payload = JSONObject()
            .put("summary", draft.title)
            .put("description", draft.description)
            .put("start_time", feishuTime(startsAt))
            .put("end_time", feishuTime(endsAt))
        val response = request(
            path = "/calendar/v4/calendars/${encode(calendarId)}/events",
            method = "POST",
            body = payload
        )
        val eventJson = response.optJSONObject("data")?.optJSONObject("event") ?: JSONObject()
        return mapEvent(calendarId, eventJson).copy(
            sourceItemId = draft.sourceItemId,
            strategyThreadId = draft.strategyThreadId
        )
    }

    fun refreshToken(): FeishuTokenSet {
        val refreshToken = tokenSet.refreshToken ?: error("Missing refresh token. Reconnect Feishu.")
        val body = JSONObject()
            .put("grant_type", "refresh_token")
            .put("client_id", appId)
            .put("client_secret", appSecret)
            .put("refresh_token", refreshToken)
        return parseTokenResponse(postToken(body))
    }

    private fun resolveCalendarId(defaultCalendar: String): String {
        if (defaultCalendar.isNotBlank() && defaultCalendar != "primary") return defaultCalendar
        val response = request("/calendar/v4/calendars/primary", "GET")
        val calendar = response.optJSONObject("data")?.optJSONObject("calendar")
        return calendar?.optString("calendar_id")?.takeIf { it.isNotBlank() } ?: "primary"
    }

    private fun request(path: String, method: String, body: JSONObject? = null): JSONObject {
        val connection = (URL("$FeishuApiBase$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer ${tokenSet.accessToken}")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15_000
            readTimeout = 20_000
            doInput = true
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val response = connection.readJson()
        val code = response.optInt("code", -1)
        if (code != 0) error(response.optString("msg", "Feishu API failed: $code"))
        return response
    }

    companion object {
        fun buildAuthorizeUrl(appId: String): Pair<String, String> {
            val state = "resolve-${UUID.randomUUID()}"
            val scopes = FeishuCalendarScopes
                .map { it.key }
                .filter { it != "contact:user.email:readonly" }
                .joinToString(" ")
            val query = mapOf(
                "app_id" to appId,
                "redirect_uri" to AndroidFeishuRedirectUri,
                "state" to state,
                "scope" to scopes
            ).toQueryString()
            return "$FeishuApiBase/authen/v1/index?$query" to state
        }

        fun waitForOAuthCode(expectedState: String, timeoutMillis: Long = 180_000): String {
            ServerSocket(36321).use { server ->
                server.soTimeout = timeoutMillis.toInt()
                val socket = server.accept()
                socket.use {
                    val firstLine = BufferedReader(InputStreamReader(it.getInputStream())).readLine().orEmpty()
                    val target = firstLine.split(" ").getOrNull(1).orEmpty()
                    val query = target.substringAfter("?", "")
                    val params = query.split("&")
                        .filter { part -> part.contains("=") }
                        .associate { part ->
                            val key = part.substringBefore("=")
                            val value = URLDecoder.decode(part.substringAfter("="), "UTF-8")
                            key to value
                        }
                    val state = params["state"].orEmpty()
                    val code = params["code"].orEmpty()
                    val html = "<html><body style='font-family:sans-serif;padding:32px'><h3>Resolve connected</h3><p>You can return to Resolve.</p></body></html>"
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.toByteArray().size}\r\n\r\n$html"
                    it.getOutputStream().write(response.toByteArray())
                    if (state != expectedState) error("Feishu OAuth state mismatch.")
                    if (code.isBlank()) error("Feishu did not return an authorization code.")
                    return code
                }
            }
        }

        fun exchangeCode(appId: String, appSecret: String, code: String): FeishuTokenSet {
            val body = JSONObject()
                .put("grant_type", "authorization_code")
                .put("client_id", appId)
                .put("client_secret", appSecret)
                .put("code", code)
                .put("redirect_uri", AndroidFeishuRedirectUri)
            return parseTokenResponse(postToken(body))
        }

        private fun postToken(body: JSONObject): JSONObject {
            val connection = (URL("$FeishuApiBase/authen/v2/oauth/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            return connection.readJson()
        }

        private fun parseTokenResponse(response: JSONObject): FeishuTokenSet {
            val directAccessToken = response.optString("access_token").takeIf { it.isNotBlank() }
            val data = response.optJSONObject("data")
            val accessToken = directAccessToken ?: data?.optString("access_token")?.takeIf { it.isNotBlank() }
            val refreshToken = response.optString("refresh_token").takeIf { it.isNotBlank() }
                ?: data?.optString("refresh_token")?.takeIf { it.isNotBlank() }
            val expiresIn = response.optLong("expires_in", 0L).takeIf { it > 0L }
                ?: data?.optLong("expires_in", 0L)?.takeIf { it > 0L }
            if (accessToken.isNullOrBlank()) {
                error(response.optString("msg", "Feishu token exchange failed."))
            }
            return FeishuTokenSet(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtEpochMillis = expiresIn?.let { System.currentTimeMillis() + it * 1000 }
            )
        }
    }
}

private fun mapEvent(calendarId: String, json: JSONObject): CalendarEvent {
    val eventId = json.optString("event_id", json.optString("id"))
    val start = json.optJSONObject("start_time") ?: JSONObject()
    val end = json.optJSONObject("end_time") ?: JSONObject()
    val permissions = json.optJSONObject("permissions") ?: JSONObject()
    val startsAt = feishuInstant(start) ?: Instant.now()
    return CalendarEvent(
        id = "feishu_${calendarId}_${eventId}".replace(Regex("[^a-zA-Z0-9_-]"), "_"),
        provider = "feishu",
        status = if (permissions.optBoolean("editable", true)) "synced" else "readonly",
        title = json.optString("summary", "Untitled Feishu event"),
        description = json.optString("description"),
        startsAt = startsAt,
        endsAt = feishuInstant(end),
        externalCalendarId = calendarId,
        externalEventId = eventId,
        canEdit = permissions.optBoolean("editable", true),
        canDelete = permissions.optBoolean("deletable", true)
    )
}

private fun feishuInstant(json: JSONObject): Instant? {
    val timestamp = json.optString("timestamp").takeIf { it.isNotBlank() }
    if (timestamp != null) return Instant.ofEpochSecond(timestamp.toLong())
    val date = json.optString("date").takeIf { it.isNotBlank() }
    return date?.let { java.time.LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant() }
}

private fun feishuTime(instant: Instant) = JSONObject()
    .put("timestamp", instant.epochSecond.toString())
    .put("timezone", ZoneId.systemDefault().id)

private fun Map<String, String>.toQueryString(): String =
    entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun HttpURLConnection.readJson(): JSONObject {
    val stream = if (responseCode in 200..299) inputStream else errorStream
    val text = stream.bufferedReader().use { it.readText() }
    return JSONObject(text)
}
