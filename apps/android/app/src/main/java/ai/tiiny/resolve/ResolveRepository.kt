package ai.tiiny.resolve

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

class ResolveRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("resolve_state", Context.MODE_PRIVATE)
    private val stateJsonKey = "state_json"
    private val appSyncCursorKey = "last_app_sync_cursor_v2"
    private val pendingQuickCapturesKey = "pending_quick_captures"
    private val appSyncCursorLookback = Duration.ofDays(30)
    private val notesRoot by lazy { context.filesDir.resolve("resolve-vault").resolve("Notes") }

    fun load(): ResolveState {
        return synchronized(prefsLock) {
            drainPendingQuickCaptures(loadStoredState())
        }
    }

    private fun loadStoredState(): ResolveState {
        val raw = prefs.getString(stateJsonKey, null)
        val state = if (raw == null) {
            sampleResolveState().also { save(it) }
        } else {
            runCatching {
                decodeState(JSONObject(raw))
            }.getOrElse {
                sampleResolveState().also { save(it) }
            }
        }

        val normalized = runCatching {
            state.let {
                val normalized = scrubSampleData(state).copy(calendarEvents = normalizeCalendarEvents(state.calendarEvents))
                if (normalized != state) save(normalized)
                normalized
            }
        }.getOrElse { sampleResolveState().also { save(it) } }

        return normalized
    }

    fun save(state: ResolveState) {
        synchronized(prefsLock) {
            val normalized = scrubSampleData(mergeProtectedQuickCaptures(state))
                .copy(calendarEvents = normalizeCalendarEvents(state.calendarEvents))
            prefs.edit().putString(stateJsonKey, encodeState(normalized).toString()).commit()
        }
    }

    fun addQuickCapture(title: String) {
        synchronized(prefsLock) {
            val text = title.trim()
            if (text.isBlank()) return
            val timestamp = Instant.now()
            val item = ResolveItem(
                title = text,
                source = "android_notification",
                createdAt = timestamp,
                updatedAt = timestamp,
                statusChangedAt = timestamp,
                sortOrder = -timestamp.toEpochMilli().toDouble()
            )
            val existing = prefs.getString(pendingQuickCapturesKey, null)
                ?.let { runCatching { JSONArray(it) }.getOrNull() }
                ?: JSONArray()
            existing.put(encodeItem(item))
            prefs.edit().putString(pendingQuickCapturesKey, existing.toString()).commit()

            val current = loadStoredState()
            if (current.items.none { it.id == item.id }) {
                save(current.copy(items = listOf(item) + current.items))
            }
        }
    }

    fun hasPendingQuickCaptures(): Boolean =
        !prefs.getString(pendingQuickCapturesKey, null).isNullOrBlank()

    fun clearPendingQuickCaptures() {
        synchronized(prefsLock) {
            prefs.edit().remove(pendingQuickCapturesKey).commit()
        }
    }

    fun loadAppSyncCursor(): Instant? =
        prefs.getString(appSyncCursorKey, null)
            ?.let(::instantOrNull)
            ?.minus(appSyncCursorLookback)

    fun saveAppSyncCursor(cursor: Instant) {
        prefs.edit().putString(appSyncCursorKey, cursor.toString()).apply()
    }

    private fun encodeState(state: ResolveState) = JSONObject()
        .put("items", JSONArray(state.items.map(::encodeItem)))
        .put("threads", JSONArray(state.threads.map(::encodeThread)))
        .put("calendarEvents", JSONArray(state.calendarEvents.map(::encodeCalendarEvent)))
        .put("notes", JSONArray(state.notes.map(::encodeNote)))
        .put("feishuSettings", encodeFeishuSettings(state.feishuSettings))
        .put("backendSettings", encodeBackendSettings(state.backendSettings))

    private fun decodeState(json: JSONObject) = ResolveState(
        items = json.optJSONArray("items").orEmpty().mapJson(::decodeItem),
        threads = json.optJSONArray("threads").orEmpty().mapJson(::decodeThread),
        calendarEvents = json.optJSONArray("calendarEvents").orEmpty().mapJson(::decodeCalendarEvent),
        notes = json.optJSONArray("notes").orEmpty().mapJson(::decodeNote),
        feishuSettings = decodeFeishuSettings(json.optJSONObject("feishuSettings") ?: JSONObject()),
        backendSettings = decodeBackendSettings(json.optJSONObject("backendSettings") ?: JSONObject())
    )

    fun readNote(note: MarkdownNote): String {
        val file = context.filesDir.resolve("resolve-vault").resolve(note.canonicalPath)
        return if (file.exists()) file.readText() else defaultNoteMarkdown(note, note.markdown)
    }

    fun readNoteBody(note: MarkdownNote): String =
        stripGeneratedNoteTitle(readNote(note), note.title)

    fun writeNote(note: MarkdownNote, markdown: String) {
        val file = context.filesDir.resolve("resolve-vault").resolve(note.canonicalPath)
        file.parentFile?.mkdirs()
        file.writeText(markdown)
    }

    fun writeNoteBody(note: MarkdownNote, body: String) {
        writeNote(note, noteMarkdownDocument(note, body))
    }

    fun withCurrentNoteBodies(state: ResolveState): ResolveState =
        state.copy(notes = state.notes.map { note ->
            note.copy(markdown = readNoteBody(note))
        })

    fun materializeNotes(notes: List<MarkdownNote>) {
        notes
            .filter { it.markdown.isNotBlank() || it.contentHash != null }
            .forEach { note -> writeNoteBody(note, note.markdown) }
    }

    fun renameNote(note: MarkdownNote, title: String, body: String): MarkdownNote {
        val title = title.trim().ifBlank { "Untitled Note" }
        val canonicalPath = notePath(note.id, title, note.createdAt)
        if (note.title == title && note.canonicalPath == canonicalPath) return note

        val oldPath = note.canonicalPath
        val cleanBody = stripGeneratedNoteTitle(body, note.title)
        val next = note.copy(title = title, markdown = cleanBody, canonicalPath = canonicalPath, updatedAt = Instant.now())
        val markdown = noteMarkdownDocument(next, cleanBody)
        writeNote(next, markdown)
        if (oldPath != canonicalPath) {
            context.filesDir.resolve("resolve-vault").resolve(oldPath).delete()
        }
        return next.copy(contentHash = noteContentHash(markdown))
    }

    fun createNoteForTask(item: ResolveItem, strategyTitle: String?): Pair<MarkdownNote, String> {
        val timestamp = Instant.now()
        val id = "note_${java.util.UUID.randomUUID()}"
        val note = MarkdownNote(
            id = id,
            title = item.title,
            canonicalPath = notePath(id, item.title, timestamp),
            markdown = defaultNoteBody(item.notes, strategyTitle),
            createdAt = timestamp,
            updatedAt = timestamp,
            taskId = item.id,
            strategyThreadId = item.strategyThreadId
        )
        val markdown = noteMarkdownDocument(note, note.markdown)
        writeNote(note, markdown)
        return note.copy(contentHash = noteContentHash(markdown)) to markdown
    }

    private fun drainPendingQuickCaptures(state: ResolveState): ResolveState {
        val pendingRaw = prefs.getString(pendingQuickCapturesKey, null) ?: return state
        val pendingItems = runCatching {
            JSONArray(pendingRaw).mapJson(::decodeItem)
        }.getOrDefault(emptyList())
        if (pendingItems.isEmpty()) return state

        val existingIds = state.items.map { it.id }.toSet()
        val freshCaptures = pendingItems
            .filter { it.id !in existingIds && it.title.isNotBlank() }
            .sortedByDescending { it.createdAt }
        if (freshCaptures.isEmpty()) return state

        val merged = state.copy(items = freshCaptures + state.items)
        save(merged)
        return merged
    }

    private fun mergePendingQuickCaptures(state: ResolveState): ResolveState {
        val pendingRaw = prefs.getString(pendingQuickCapturesKey, null) ?: return state
        val pendingItems = runCatching {
            JSONArray(pendingRaw).mapJson(::decodeItem)
        }.getOrDefault(emptyList())
        if (pendingItems.isEmpty()) return state

        val existingIds = state.items.map { it.id }.toSet()
        val freshCaptures = pendingItems
            .filter { it.id !in existingIds && it.title.isNotBlank() }
            .sortedByDescending { it.createdAt }
        return if (freshCaptures.isEmpty()) state else state.copy(items = freshCaptures + state.items)
    }

    private fun mergeProtectedQuickCaptures(state: ResolveState): ResolveState {
        val withPending = mergePendingQuickCaptures(state)
        val existing = prefs.getString(stateJsonKey, null)
            ?.let { raw -> runCatching { decodeState(JSONObject(raw)) }.getOrNull() }
            ?: return withPending
        val existingIds = withPending.items.map { it.id }.toSet()
        val recentCutoff = Instant.now().minus(Duration.ofMinutes(15))
        val recentNotificationItems = existing.items
            .filter {
                it.source == "android_notification" &&
                    it.status == ItemStatus.Active &&
                    it.id !in existingIds &&
                    it.createdAt.isAfter(recentCutoff)
            }
            .sortedByDescending { it.createdAt }
        return if (recentNotificationItems.isEmpty()) {
            withPending
        } else {
            withPending.copy(items = recentNotificationItems + withPending.items)
        }
    }

    private fun encodeItem(item: ResolveItem) = JSONObject()
        .put("id", item.id)
        .put("type", item.type.name)
        .put("status", item.status.name)
        .put("title", item.title)
        .put("notes", item.notes)
        .put("source", item.source)
        .put("createdAt", item.createdAt.toString())
        .put("updatedAt", item.updatedAt.toString())
        .put("statusChangedAt", item.statusChangedAt.toString())
        .putOpt("deletedAt", item.deletedAt?.toString())
        .putOpt("dueAt", item.dueAt?.toString())
        .putOpt("strategyThreadId", item.strategyThreadId)
        .putOpt("sourceItemId", item.sourceItemId)
        .putOpt("parentItemId", item.parentItemId)
        .putOpt("noteId", item.noteId)
        .putOpt("sortOrder", item.sortOrder)

    private fun decodeItem(json: JSONObject): ResolveItem {
        val status = enumValueOrDefault(json.optString("status"), ItemStatus.Active)
        val createdAt = instantOrNow(json.optString("createdAt"))
        val updatedAt = instantOrNow(json.optString("updatedAt"))
        return ResolveItem(
            id = json.optString("id"),
            type = enumValueOrDefault(json.optString("type"), ItemType.Task),
            status = status,
            title = json.optString("title"),
            notes = json.optString("notes"),
            source = json.optString("source", "android"),
            createdAt = createdAt,
            updatedAt = updatedAt,
            statusChangedAt = json.optNullableString("statusChangedAt")?.let(::instantOrNull)
                ?: fallbackStatusChangedAt(status, createdAt, updatedAt),
            deletedAt = json.optNullableString("deletedAt")?.let(::instantOrNull),
            dueAt = json.optNullableString("dueAt")?.let(::instantOrNull),
            strategyThreadId = json.optNullableString("strategyThreadId"),
            sourceItemId = json.optNullableString("sourceItemId"),
            parentItemId = json.optNullableString("parentItemId"),
            noteId = json.optNullableString("noteId"),
            sortOrder = json.optNullableDouble("sortOrder")
        )
    }

    private fun encodeNote(note: MarkdownNote) = JSONObject()
        .put("id", note.id)
        .put("canonicalPath", note.canonicalPath)
        .put("title", note.title)
        .put("markdown", note.markdown)
        .put("status", note.status)
        .put("createdAt", note.createdAt.toString())
        .put("updatedAt", note.updatedAt.toString())
        .putOpt("taskId", note.taskId)
        .putOpt("strategyThreadId", note.strategyThreadId)
        .putOpt("contentHash", note.contentHash)

    private fun decodeNote(json: JSONObject): MarkdownNote {
        val createdAt = instantOrNow(json.optString("createdAt"))
        return MarkdownNote(
            id = json.optString("id"),
            canonicalPath = json.optString("canonicalPath"),
            title = json.optString("title"),
            markdown = json.optString("markdown"),
            status = json.optString("status", "active"),
            createdAt = createdAt,
            updatedAt = instantOrNull(json.optString("updatedAt")) ?: createdAt,
            taskId = json.optNullableString("taskId"),
            strategyThreadId = json.optNullableString("strategyThreadId"),
            contentHash = json.optNullableString("contentHash")
        )
    }

    private fun encodeThread(thread: StrategyThread) = JSONObject()
        .put("id", thread.id)
        .put("title", thread.title)
        .put("description", thread.description)
        .put("currentHypothesis", thread.currentHypothesis)
        .put("keyQuestions", JSONArray(thread.keyQuestions))
        .put("recentThoughts", JSONArray(thread.recentThoughts))
        .put("status", thread.status)
        .put("createdAt", thread.createdAt.toString())
        .put("updatedAt", thread.updatedAt.toString())
        .putOpt("sortOrder", thread.sortOrder)

    private fun decodeThread(json: JSONObject): StrategyThread {
        val createdAt = instantOrNull(json.optString("createdAt")) ?: Instant.EPOCH
        return StrategyThread(
            id = json.optString("id"),
            title = json.optString("title"),
            description = json.optString("description"),
            currentHypothesis = json.optString("currentHypothesis"),
            keyQuestions = json.optJSONArray("keyQuestions").orEmpty().mapStrings(),
            recentThoughts = json.optJSONArray("recentThoughts").orEmpty().mapStrings(),
            status = json.optString("status", "active"),
            createdAt = createdAt,
            updatedAt = instantOrNull(json.optString("updatedAt")) ?: createdAt,
            sortOrder = json.optNullableDouble("sortOrder")
        )
    }

    private fun encodeCalendarEvent(event: CalendarEvent) = JSONObject()
        .put("id", event.id)
        .put("provider", event.provider)
        .put("status", event.status)
        .put("title", event.title)
        .put("description", event.description)
        .putOpt("recurrence", event.recurrence)
        .putOpt("meetingUrl", event.meetingUrl)
        .put("startsAt", event.startsAt.toString())
        .putOpt("endsAt", event.endsAt?.toString())
        .putOpt("externalCalendarId", event.externalCalendarId)
        .putOpt("externalEventId", event.externalEventId)
        .putOpt("sourceItemId", event.sourceItemId)
        .putOpt("strategyThreadId", event.strategyThreadId)
        .put("canEdit", event.canEdit)
        .put("canDelete", event.canDelete)

    private fun decodeCalendarEvent(json: JSONObject): CalendarEvent {
        val description = json.optString("description")
        return CalendarEvent(
            id = json.optString("id"),
            provider = json.optString("provider", "local"),
            status = json.optString("status", "local"),
            title = json.optString("title"),
            description = description,
            recurrence = json.optNullableString("recurrence"),
            meetingUrl = json.optNullableString("meetingUrl") ?: extractMeetingUrlFromText(description),
            startsAt = instantOrNow(json.optString("startsAt")),
            endsAt = json.optNullableString("endsAt")?.let(::instantOrNull),
            externalCalendarId = json.optNullableString("externalCalendarId"),
            externalEventId = json.optNullableString("externalEventId"),
            sourceItemId = json.optNullableString("sourceItemId"),
            strategyThreadId = json.optNullableString("strategyThreadId"),
            canEdit = json.optBoolean("canEdit", true),
            canDelete = json.optBoolean("canDelete", true)
        )
    }

    private fun encodeFeishuSettings(settings: FeishuSettings) = JSONObject()
        .put("appId", settings.appId)
        .put("defaultCalendar", settings.defaultCalendar)
        .put("pastDays", settings.pastDays)
        .put("futureDays", settings.futureDays)
        .put("status", settings.status.name)
        .putOpt("lastSyncedAt", settings.lastSyncedAt?.toString())
        .putOpt("lastError", settings.lastError)

    private fun decodeFeishuSettings(json: JSONObject) = FeishuSettings(
        appId = json.optString("appId"),
        defaultCalendar = json.optString("defaultCalendar", "primary"),
        pastDays = json.optInt("pastDays", FullFeishuSyncPastDays).coerceAtLeast(FullFeishuSyncPastDays),
        futureDays = json.optInt("futureDays", FullFeishuSyncFutureDays).coerceAtLeast(FullFeishuSyncFutureDays),
        status = enumValueOrDefault(json.optString("status"), FeishuStatus.NotConnected),
        lastSyncedAt = json.optNullableString("lastSyncedAt")?.let(::instantOrNull),
        lastError = json.optNullableString("lastError")
    )

    private fun encodeBackendSettings(settings: BackendSettings) = JSONObject()
        .put("supabaseUrl", settings.supabaseUrl)
        .put("anonKey", settings.anonKey)
        .put("email", settings.email)
        .put("status", settings.status.name)
        .put("feishuConnected", settings.feishuConnected)
        .putOpt("lastSyncedAt", settings.lastSyncedAt?.toString())
        .putOpt("lastError", settings.lastError)

    private fun decodeBackendSettings(json: JSONObject) = BackendSettings(
        supabaseUrl = json.optString("supabaseUrl", ResolveSupabaseUrl).ifBlank { ResolveSupabaseUrl },
        anonKey = json.optString("anonKey", ResolveSupabasePublishableKey).ifBlank { ResolveSupabasePublishableKey },
        email = json.optString("email"),
        status = enumValueOrDefault(json.optString("status"), BackendStatus.NotConfigured),
        feishuConnected = json.optBoolean("feishuConnected", false),
        lastSyncedAt = json.optNullableString("lastSyncedAt")?.let(::instantOrNull),
        lastError = json.optNullableString("lastError")
    )

    private fun scrubSampleData(state: ResolveState): ResolveState {
        val sampleThreadIds = state.threads
            .filter(::isSeedThread)
            .map { it.id }
            .toSet()
        return state.copy(
            items = state.items
                .filterNot { it.title in sampleItemTitles }
                .map { item ->
                    if (item.strategyThreadId?.let { it in sampleThreadIds } == true) item.copy(strategyThreadId = null) else item
                },
            threads = state.threads.filterNot { it.id in sampleThreadIds }
        )
    }

    companion object {
        private val prefsLock = Any()

        private val sampleItemTitles = setOf(
            "和 Alex 聊完，感觉融资叙事应该强调速度优势",
            "明天问 Sarah 候选人推进到哪一步了",
            "产品 onboarding 里用户可能卡在第一步",
            "开车时想到，可以做一个 personal radar 自动总结",
            "Investor call 准备",
            "问 Alex 候选人进展",
            "有氧 30 分钟",
            "B 推荐增长负责人",
            "下次和 C 聊 enterprise angle",
            "产品方向：enterprise workflow 可能更强",
            "融资策略：最近投资人更关心执行速度"
        )

        private val sampleThreadTitles = setOf(
            "融资策略",
            "产品方向 / PMF",
            "核心人才招募",
            "组织节奏与授权边界",
            "Vibe Coding / 工具想法"
        )

        private val sampleThreadHypotheses = setOf(
            "融资策略|从 market size 叙事转向 execution velocity + AI-native workflow。",
            "融资策略|强调 execution velocity + AI-native workflow。",
            "产品方向 / PMF|关注 enterprise workflow 是否比 consumer angle 更强。",
            "产品方向 / PMF|继续验证 enterprise workflow。",
            "核心人才招募|把关键候选人的推进节奏放在每天可见的位置。",
            "核心人才招募|把关键候选人推进变成可见节奏。",
            "组织节奏与授权边界|减少创始人上下文切换，明确谁能推进什么。",
            "组织节奏与授权边界|降低创始人上下文切换。",
            "Vibe Coding / 工具想法|把自己真实使用中的工具冲动沉淀为可试的系统。",
            "Vibe Coding / 工具想法|先服务自己真实使用。"
        )

        private fun isSeedThread(thread: StrategyThread): Boolean {
            val title = thread.title.trim()
            val hypothesis = thread.currentHypothesis.trim()
            return title in sampleThreadTitles && "$title|$hypothesis" in sampleThreadHypotheses
        }
    }
}

private fun notePath(noteId: String, title: String, createdAt: Instant): String {
    val date = createdAt.atZone(java.time.ZoneId.systemDefault())
    val year = date.year.toString()
    val month = date.monthValue.toString().padStart(2, '0')
    val slug = title.trim()
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
        .trim('-')
        .take(60)
        .ifBlank { "note" }
    return "Notes/$year/$month/$noteId-$slug.md"
}

private fun defaultNoteBody(body: String = "", strategyTitle: String? = null): String =
    buildString {
        if (body.isNotBlank()) {
            appendLine(body)
            appendLine()
        }
        if (!strategyTitle.isNullOrBlank()) appendLine("Strategy: $strategyTitle")
    }

private fun defaultNoteMarkdown(note: MarkdownNote, body: String = "", strategyTitle: String? = null): String {
    val content = defaultNoteBody(body, strategyTitle)
    return noteMarkdownDocument(note, content)
}

private fun noteMarkdownDocument(note: MarkdownNote, body: String = ""): String {
    val frontmatter = buildString {
        appendLine("---")
        appendLine("note_id: ${note.id}")
        appendLine("title: \"${note.title.replace("\"", "\\\"")}\"")
        appendLine("status: ${note.status}")
        appendLine("created_at: ${note.createdAt}")
        appendLine("updated_at: ${note.updatedAt}")
        note.taskId?.let { appendLine("task_id: $it") }
        note.strategyThreadId?.let { appendLine("strategy_thread_id: $it") }
        appendLine("---")
        appendLine()
    }
    val content = stripGeneratedNoteTitle(body, note.title).trim()
    return frontmatter + content
}

private fun stripNoteFrontmatter(markdown: String): String =
    markdown.replace(Regex("^---\\r?\\n[\\s\\S]*?\\r?\\n---\\r?\\n?"), "")

private fun stripGeneratedNoteTitle(markdown: String, title: String): String {
    val body = stripNoteFrontmatter(markdown).trimStart()
    val lines = body.lines()
    return if (lines.firstOrNull()?.trim() == "# $title") {
        lines.drop(1).joinToString("\n").trimStart()
    } else {
        body
    }
}

private fun noteContentHash(value: String): String =
    value.fold(0) { hash, char -> hash * 31 + char.code }
        .let { java.lang.Integer.toString(kotlin.math.abs(it), 36) }

private fun JSONArray?.orEmpty() = this ?: JSONArray()

private fun <T> JSONArray.mapJson(transform: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(transform) }

private fun JSONArray.mapStrings(): List<String> =
    (0 until length()).mapNotNull { index -> optString(index).trim().takeIf { it.isNotBlank() } }

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(default)

private fun instantOrNow(value: String): Instant = instantOrNull(value) ?: Instant.now()

private fun instantOrNull(value: String): Instant? =
    value.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() && !it.isInfinite() } else null
