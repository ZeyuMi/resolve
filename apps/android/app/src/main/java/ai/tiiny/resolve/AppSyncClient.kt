package ai.tiiny.resolve

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class AppSyncClient(
    private val settings: BackendSettings,
    private val session: BackendSession,
    syncSecret: String
) {
    private val projectUrl = settings.supabaseUrl.trim().ifBlank { ResolveSupabaseUrl }.removeSuffix("/")
    private val userId = userIdFromAccessToken(session.accessToken)
    private val key = deriveSyncKey(syncSecret, settings.email)

    fun pullState(includeCalendarEvents: Boolean = true, changedSince: Instant? = null): ResolveState {
        val changedFilter = changedSince?.let { "&updated_at=gt.${encode(it.toString())}" }.orEmpty()
        val itemRows = getArray("/rest/v1/resolve_items?select=*&user_id=eq.${encode(userId)}$changedFilter&order=updated_at.desc")
        val threadRows = getArray("/rest/v1/resolve_strategy_threads?select=*&user_id=eq.${encode(userId)}$changedFilter&order=updated_at.desc")
        val eventRows = if (includeCalendarEvents) {
            getArray("/rest/v1/resolve_calendar_events?select=*&user_id=eq.${encode(userId)}&encryption_scheme=eq.vault_v1$changedFilter&order=updated_at.desc")
        } else {
            JSONArray()
        }

        return ResolveState(
            items = (0 until itemRows.length()).mapNotNull { itemRows.optJSONObject(it)?.let(::itemFromRow) },
            threads = (0 until threadRows.length()).mapNotNull { threadRows.optJSONObject(it)?.let(::threadFromRow) },
            calendarEvents = (0 until eventRows.length()).mapNotNull { eventRows.optJSONObject(it)?.let(::calendarFromRow) }
        )
    }

    fun pushState(state: ResolveState, changedSince: Instant? = null) {
        val changedItems = changedSince?.let { since -> state.items.filter { it.updatedAt > since } } ?: state.items
        val changedThreads = changedSince?.let { since -> state.threads.filter { it.updatedAt > since } } ?: state.threads
        val items = JSONArray(changedItems.map(::itemToRow))
        val threads = JSONArray(changedThreads.map(::threadToRow))
        val localCalendarRows = state.calendarEvents
            .filter { it.provider != "feishu" || it.externalEventId == null }
            .map(::calendarToRow)

        if (items.length() > 0) upsert("resolve_items", items)
        if (threads.length() > 0) upsert("resolve_strategy_threads", threads)
        if (localCalendarRows.isNotEmpty()) upsert("resolve_calendar_events", JSONArray(localCalendarRows))
    }

    fun deleteRemoteItems(itemIds: Collection<String>) {
        val deletedAt = Instant.now().toString()
        val body = JSONObject()
            .put("status", "deleted")
            .put("deleted_at", deletedAt)
            .put("updated_at", deletedAt)
        itemIds
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { itemId ->
                val connection = open(
                    "/rest/v1/resolve_items?user_id=eq.${encode(userId)}&id=eq.${encode(itemId)}",
                    "PATCH"
                ).apply {
                    setRequestProperty("prefer", "return=minimal")
                    doOutput = true
                    outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                connection.readTextOrThrow()
            }
    }

    private fun itemToRow(item: ResolveItem): JSONObject {
        val encrypted = encryptJson(
            JSONObject()
                .put("title", item.title)
                .put("notes", item.notes.takeIf { it.isNotBlank() })
                .put("sortOrder", item.sortOrder)
                .put("statusChangedAt", item.statusChangedAt.toString())
        )
        return JSONObject()
            .put("user_id", userId)
            .put("id", item.id)
            .put("type", when (item.type) {
                ItemType.Task -> "task"
                ItemType.StrategyNote -> "strategy_note"
            })
            .put("status", item.status.remoteName())
            .put("source", item.source)
            .put("created_at", item.createdAt.toString())
            .put("updated_at", item.updatedAt.toString())
            .putNullable("due_at", item.dueAt?.toString())
            .putNullable("strategy_thread_id", item.strategyThreadId)
            .putNullable("parent_item_id", item.parentItemId)
            .putNullable("source_item_id", item.sourceItemId)
            .putNullable("deleted_at", item.deletedAt?.toString())
            .put("encrypted_payload", encrypted.payload)
            .put("payload_nonce", encrypted.nonce)
            .put("payload_version", 1)
    }

    private fun threadToRow(thread: StrategyThread): JSONObject {
        val encrypted = encryptJson(
            JSONObject()
                .put("title", thread.title)
                .put("currentHypothesis", thread.currentHypothesis.takeIf { it.isNotBlank() })
                .put("sortOrder", thread.sortOrder)
        )
        return JSONObject()
            .put("user_id", userId)
            .put("id", thread.id)
            .put("status", thread.status.ifBlank { "active" })
            .put("created_at", thread.createdAt.toString())
            .put("updated_at", thread.updatedAt.toString())
            .put("encrypted_payload", encrypted.payload)
            .put("payload_nonce", encrypted.nonce)
            .put("payload_version", 1)
    }

    private fun calendarToRow(event: CalendarEvent): JSONObject {
        val encrypted = encryptJson(
            JSONObject()
                .put("title", event.title)
                .put("description", event.description.takeIf { it.isNotBlank() })
                .put("meetingUrl", event.meetingUrl)
                .put("recurrence", event.recurrence)
        )
        return JSONObject()
            .put("user_id", userId)
            .put("id", event.id)
            .put("encryption_scheme", "vault_v1")
            .put("provider", event.provider)
            .put("status", event.status)
            .put("starts_at", event.startsAt.toString())
            .putNullable("ends_at", event.endsAt?.toString())
            .put("is_all_day", false)
            .put("created_at", event.startsAt.toString())
            .put("updated_at", Instant.now().toString())
            .putNullable("external_calendar_id", event.externalCalendarId)
            .putNullable("external_event_id", event.externalEventId)
            .putNullable("source_item_id", event.sourceItemId)
            .putNullable("strategy_thread_id", event.strategyThreadId)
            .put("can_edit", event.canEdit)
            .put("can_delete", event.canDelete)
            .put("encrypted_payload", encrypted.payload)
            .put("payload_nonce", encrypted.nonce)
            .put("payload_version", 1)
    }

    private fun itemFromRow(row: JSONObject): ResolveItem {
        val payload = decryptJson(row)
        val remoteStatus = row.optString("status")
        val status = itemStatusFromRemote(remoteStatus)
        val createdAt = instantOrNow(row.optString("created_at"))
        val updatedAt = instantOrNow(row.optString("updated_at"))
        val deletedAt = row.optNullableString("deleted_at")?.let(::instantOrNull)
            ?: if (remoteStatus == "deleted") updatedAt else null
        return ResolveItem(
            id = row.optString("id"),
            type = if (row.optString("type") == "strategy_note") ItemType.StrategyNote else ItemType.Task,
            status = status,
            title = payload.optString("title").ifBlank { "Untitled" },
            notes = payload.optString("notes"),
            source = row.optString("source", "sync"),
            createdAt = createdAt,
            updatedAt = updatedAt,
            statusChangedAt = payload.optNullableString("statusChangedAt")?.let(::instantOrNull)
                ?: fallbackStatusChangedAt(status, createdAt, updatedAt),
            deletedAt = deletedAt,
            dueAt = row.optNullableString("due_at")?.let(::instantOrNull),
            strategyThreadId = row.optNullableString("strategy_thread_id"),
            sourceItemId = row.optNullableString("source_item_id"),
            parentItemId = row.optNullableString("parent_item_id"),
            sortOrder = payload.optNullableDouble("sortOrder")
        )
    }

    private fun threadFromRow(row: JSONObject): StrategyThread {
        val payload = decryptJson(row)
        return StrategyThread(
            id = row.optString("id"),
            title = payload.optString("title").ifBlank { "Untitled strategy" },
            currentHypothesis = payload.optString("currentHypothesis"),
            status = row.optString("status", "active"),
            createdAt = instantOrNow(row.optString("created_at")),
            updatedAt = instantOrNow(row.optString("updated_at")),
            sortOrder = payload.optNullableDouble("sortOrder")
        )
    }

    private fun calendarFromRow(row: JSONObject): CalendarEvent {
        val payload = decryptJson(row)
        return CalendarEvent(
            id = row.optString("id"),
            provider = row.optString("provider", "local"),
            status = row.optString("status", "local"),
            title = payload.optString("title").ifBlank { "Untitled event" },
            description = payload.optString("description"),
            recurrence = payload.optNullableString("recurrence"),
            meetingUrl = payload.optNullableString("meetingUrl"),
            startsAt = instantOrNow(row.optString("starts_at")),
            endsAt = row.optNullableString("ends_at")?.let(::instantOrNull),
            externalCalendarId = row.optNullableString("external_calendar_id"),
            externalEventId = row.optNullableString("external_event_id"),
            sourceItemId = row.optNullableString("source_item_id"),
            strategyThreadId = row.optNullableString("strategy_thread_id"),
            canEdit = row.optBoolean("can_edit", true),
            canDelete = row.optBoolean("can_delete", true)
        )
    }

    private fun getArray(path: String): JSONArray {
        val connection = open(path, "GET")
        return connection.readJsonArray()
    }

    private fun upsert(table: String, rows: JSONArray) {
        val connection = open("/rest/v1/$table?on_conflict=user_id,id", "POST").apply {
            setRequestProperty("prefer", "resolution=merge-duplicates,return=minimal")
            doOutput = true
            outputStream.use { it.write(rows.toString().toByteArray(Charsets.UTF_8)) }
        }
        connection.readTextOrThrow()
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL("$projectUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            doInput = true
            setRequestProperty("apikey", settings.anonKey.trim().ifBlank { ResolveSupabasePublishableKey })
            setRequestProperty("authorization", "Bearer ${session.accessToken}")
            setRequestProperty("content-type", "application/json")
        }

    private fun encryptJson(json: JSONObject): EncryptedJson {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        return EncryptedJson(b64(ciphertext), b64(nonce))
    }

    private fun decryptJson(row: JSONObject): JSONObject {
        val nonce = Base64.getDecoder().decode(row.optString("payload_nonce"))
        val ciphertext = Base64.getDecoder().decode(row.optString("encrypted_payload"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
    }
}

fun mergeEncryptedRemoteState(local: ResolveState, remote: ResolveState): ResolveState {
    val items = linkedMapOf<String, ResolveItem>()
    (local.items + remote.items).forEach { item ->
        val existing = items[item.id]
        items[item.id] = mergeResolveItem(existing, item)
    }

    val threads = linkedMapOf<String, StrategyThread>()
    (local.threads + remote.threads).forEach { thread ->
        val existing = threads[thread.id]
        if (existing == null || thread.updatedAt >= existing.updatedAt) threads[thread.id] = thread
    }

    val remoteLocalCalendars = remote.calendarEvents.filter { it.provider != "feishu" || it.externalEventId == null }
    val localServerCalendars = local.calendarEvents.filter { it.provider == "feishu" && it.externalEventId != null }
    val localCalendars = local.calendarEvents.filter { it.provider != "feishu" || it.externalEventId == null }
    return local.copy(
        items = items.values.filter { it.deletedAt == null }.toList(),
        threads = threads.values.toList(),
        calendarEvents = normalizeCalendarEvents(localServerCalendars + newestCalendarById(localCalendars + remoteLocalCalendars))
    )
}

private fun mergeResolveItem(existing: ResolveItem?, candidate: ResolveItem): ResolveItem {
    if (existing == null) return candidate
    if (existing.deletedAt != null || candidate.deletedAt != null) {
        return listOf(existing, candidate)
            .filter { it.deletedAt != null }
            .maxByOrNull { it.deletedAt ?: Instant.EPOCH }
            ?: candidate
    }
    val newest = if (candidate.updatedAt >= existing.updatedAt) candidate else existing
    val statusWinner = if (candidate.statusChangedAt >= existing.statusChangedAt) candidate else existing
    return newest.copy(
        status = statusWinner.status,
        statusChangedAt = statusWinner.statusChangedAt
    )
}

private fun newestCalendarById(events: List<CalendarEvent>): List<CalendarEvent> =
    events.associateBy { it.id }.values.toList()

private data class EncryptedJson(val payload: String, val nonce: String)

private fun deriveSyncKey(secret: String, email: String): SecretKey {
    val salt = "resolve:${email.trim().lowercase()}:vault:v1".toByteArray(Charsets.UTF_8)
    val spec = PBEKeySpec(secret.toCharArray(), salt, 310_000, 256)
    val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    return SecretKeySpec(raw, "AES")
}

private fun userIdFromAccessToken(accessToken: String): String {
    val payload = accessToken.split(".").getOrNull(1) ?: error("Invalid backend session.")
    val padded = payload.padEnd(((payload.length + 3) / 4) * 4, '=')
    val json = JSONObject(String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8))
    return json.optString("sub").ifBlank { error("Backend session has no user id.") }
}

private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun encode(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

private fun ItemStatus.remoteName(): String = when (this) {
    ItemStatus.Active -> "active"
    ItemStatus.Done -> "done"
    ItemStatus.Archived -> "archived"
}

private fun itemStatusFromRemote(value: String): ItemStatus = when (value) {
    "done" -> ItemStatus.Done
    "archived", "deleted", "killed" -> ItemStatus.Archived
    else -> ItemStatus.Active
}

private fun HttpURLConnection.readJsonArray(): JSONArray {
    val text = readTextOrThrow()
    return JSONArray(text.ifBlank { "[]" })
}

private fun HttpURLConnection.readTextOrThrow(): String {
    val stream = if (responseCode in 200..299) inputStream else errorStream
    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    if (responseCode !in 200..299) {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val message = json?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json?.optString("msg")?.takeIf { it.isNotBlank() }
            ?: json?.optString("error")?.takeIf { it.isNotBlank() }
            ?: "HTTP $responseCode"
        throw BackendApiException(json?.optString("error"), responseCode, message)
    }
    return text
}

private fun JSONObject.putNullable(key: String, value: String?): JSONObject =
    if (value == null) put(key, JSONObject.NULL) else put(key, value)

private fun instantOrNow(value: String): Instant = instantOrNull(value) ?: Instant.now()

private fun instantOrNull(value: String): Instant? =
    value.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() && !it.isInfinite() } else null
