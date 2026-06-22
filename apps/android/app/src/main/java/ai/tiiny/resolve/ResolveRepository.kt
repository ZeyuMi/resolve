package ai.tiiny.resolve

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

class ResolveRepository(context: Context) {
    private val prefs = context.getSharedPreferences("resolve_state", Context.MODE_PRIVATE)
    private val appSyncCursorKey = "last_app_sync_cursor_v2"
    private val appSyncCursorLookback = Duration.ofDays(30)

    fun load(): ResolveState {
        val raw = prefs.getString("state_json", null) ?: return sampleResolveState().also { save(it) }
        return runCatching {
            decodeState(JSONObject(raw)).let { state ->
                val normalized = scrubSampleData(state).copy(calendarEvents = normalizeCalendarEvents(state.calendarEvents))
                if (normalized != state) save(normalized)
                normalized
            }
        }.getOrElse { sampleResolveState().also { save(it) } }
    }

    fun save(state: ResolveState) {
        val normalized = scrubSampleData(state).copy(calendarEvents = normalizeCalendarEvents(state.calendarEvents))
        prefs.edit().putString("state_json", encodeState(normalized).toString()).apply()
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
        .put("feishuSettings", encodeFeishuSettings(state.feishuSettings))
        .put("backendSettings", encodeBackendSettings(state.backendSettings))

    private fun decodeState(json: JSONObject) = ResolveState(
        items = json.optJSONArray("items").orEmpty().mapJson(::decodeItem),
        threads = json.optJSONArray("threads").orEmpty().mapJson(::decodeThread),
        calendarEvents = json.optJSONArray("calendarEvents").orEmpty().mapJson(::decodeCalendarEvent),
        feishuSettings = decodeFeishuSettings(json.optJSONObject("feishuSettings") ?: JSONObject()),
        backendSettings = decodeBackendSettings(json.optJSONObject("backendSettings") ?: JSONObject())
    )

    private fun encodeItem(item: ResolveItem) = JSONObject()
        .put("id", item.id)
        .put("type", item.type.name)
        .put("status", item.status.name)
        .put("title", item.title)
        .put("notes", item.notes)
        .put("source", item.source)
        .put("createdAt", item.createdAt.toString())
        .put("updatedAt", item.updatedAt.toString())
        .putOpt("dueAt", item.dueAt?.toString())
        .putOpt("strategyThreadId", item.strategyThreadId)
        .putOpt("sourceItemId", item.sourceItemId)
        .putOpt("parentItemId", item.parentItemId)
        .putOpt("sortOrder", item.sortOrder)

    private fun decodeItem(json: JSONObject) = ResolveItem(
        id = json.optString("id"),
        type = enumValueOrDefault(json.optString("type"), ItemType.Task),
        status = enumValueOrDefault(json.optString("status"), ItemStatus.Active),
        title = json.optString("title"),
        notes = json.optString("notes"),
        source = json.optString("source", "android"),
        createdAt = instantOrNow(json.optString("createdAt")),
        updatedAt = instantOrNow(json.optString("updatedAt")),
        dueAt = json.optNullableString("dueAt")?.let(::instantOrNull),
        strategyThreadId = json.optNullableString("strategyThreadId"),
        sourceItemId = json.optNullableString("sourceItemId"),
        parentItemId = json.optNullableString("parentItemId"),
        sortOrder = json.optNullableDouble("sortOrder")
    )

    private fun encodeThread(thread: StrategyThread) = JSONObject()
        .put("id", thread.id)
        .put("title", thread.title)
        .put("currentHypothesis", thread.currentHypothesis)
        .put("status", thread.status)
        .put("createdAt", thread.createdAt.toString())
        .put("updatedAt", thread.updatedAt.toString())
        .putOpt("sortOrder", thread.sortOrder)

    private fun decodeThread(json: JSONObject): StrategyThread {
        val createdAt = instantOrNull(json.optString("createdAt")) ?: Instant.EPOCH
        return StrategyThread(
            id = json.optString("id"),
            title = json.optString("title"),
            currentHypothesis = json.optString("currentHypothesis"),
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

private fun JSONArray?.orEmpty() = this ?: JSONArray()

private fun <T> JSONArray.mapJson(transform: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(transform) }

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(default)

private fun instantOrNow(value: String): Instant = instantOrNull(value) ?: Instant.now()

private fun instantOrNull(value: String): Instant? =
    value.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() && !it.isInfinite() } else null
