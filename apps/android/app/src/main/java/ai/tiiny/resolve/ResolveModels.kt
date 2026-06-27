package ai.tiiny.resolve

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

const val FullFeishuSyncPastDays = 3650 * 2
const val FullFeishuSyncFutureDays = 3650

enum class ItemType { Task, StrategyNote }
enum class ItemStatus { Active, Done, Archived }
enum class ItemRoute { Calendar, Strategy, Archive, Delete, Task }
enum class CalendarViewMode { Day, Week, Month }
enum class FeishuStatus { NotConnected, Connected, TokenExpired, PermissionError }
enum class BackendStatus { NotConfigured, SignedOut, Connected, Error }

data class ResolveItem(
    val id: String = "item_${UUID.randomUUID()}",
    val type: ItemType = ItemType.Task,
    val status: ItemStatus = ItemStatus.Active,
    val title: String,
    val notes: String = "",
    val source: String = "android",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val statusChangedAt: Instant = if (status == ItemStatus.Active) createdAt else updatedAt,
    val deletedAt: Instant? = null,
    val dueAt: Instant? = null,
    val strategyThreadId: String? = null,
    val sourceItemId: String? = null,
    val parentItemId: String? = null,
    val noteId: String? = null,
    val sortOrder: Double? = null
)

fun fallbackStatusChangedAt(status: ItemStatus, createdAt: Instant, updatedAt: Instant): Instant =
    if (status == ItemStatus.Active) createdAt else updatedAt

data class StrategyThread(
    val id: String = "thread_${UUID.randomUUID()}",
    val title: String,
    val description: String = "",
    val currentHypothesis: String = "",
    val keyQuestions: List<String> = emptyList(),
    val recentThoughts: List<String> = emptyList(),
    val status: String = "active",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val sortOrder: Double? = null
)

data class CalendarEvent(
    val id: String = "cal_${UUID.randomUUID()}",
    val provider: String = "local",
    val status: String = "local",
    val title: String,
    val description: String = "",
    val recurrence: String? = null,
    val meetingUrl: String? = null,
    val startsAt: Instant,
    val endsAt: Instant? = null,
    val externalCalendarId: String? = null,
    val externalEventId: String? = null,
    val sourceItemId: String? = null,
    val strategyThreadId: String? = null,
    val canEdit: Boolean = true,
    val canDelete: Boolean = true
)

data class MarkdownNote(
    val id: String = "note_${UUID.randomUUID()}",
    val canonicalPath: String,
    val title: String,
    val markdown: String = "",
    val status: String = "active",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val taskId: String? = null,
    val strategyThreadId: String? = null,
    val contentHash: String? = null
)

data class FeishuSettings(
    val appId: String = "",
    val defaultCalendar: String = "primary",
    val pastDays: Int = FullFeishuSyncPastDays,
    val futureDays: Int = FullFeishuSyncFutureDays,
    val status: FeishuStatus = FeishuStatus.NotConnected,
    val lastSyncedAt: Instant? = null,
    val lastError: String? = null
)

data class FeishuTokenSet(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long?
) {
    fun shouldRefresh(): Boolean {
        val expiresAt = expiresAtEpochMillis ?: return false
        return expiresAt < System.currentTimeMillis() + 60_000
    }
}

data class BackendSettings(
    val supabaseUrl: String = ResolveSupabaseUrl,
    val anonKey: String = ResolveSupabasePublishableKey,
    val email: String = "",
    val status: BackendStatus = BackendStatus.NotConfigured,
    val feishuConnected: Boolean = false,
    val lastSyncedAt: Instant? = null,
    val lastError: String? = null
)

data class BackendSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long?
) {
    fun shouldRefresh(): Boolean {
        val expiresAt = expiresAtEpochMillis ?: return false
        return expiresAt < System.currentTimeMillis() + 60_000
    }
}

data class ResolveState(
    val items: List<ResolveItem>,
    val threads: List<StrategyThread>,
    val calendarEvents: List<CalendarEvent>,
    val notes: List<MarkdownNote> = emptyList(),
    val feishuSettings: FeishuSettings = FeishuSettings(),
    val backendSettings: BackendSettings = BackendSettings()
)

data class CalendarDraft(
    val title: String = "",
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.of(9, 0),
    val description: String = "",
    val sourceItemId: String? = null,
    val strategyThreadId: String? = null
)

fun routeSuggestion(text: String): ItemRoute {
    val strategy = Regex("战略|融资|产品|组织|人才|PMF|叙事|市场|enterprise|workflow", RegexOption.IGNORE_CASE)
    val tracker = Regex("等|回复|问|跟进|推进|下次|提醒|观察|看看")
    val calendar = Regex("明天|后天|今天|周一|周二|周三|周四|周五|周六|周日|下午|上午|晚上|\\d+\\s*点|\\d+:\\d+")
    return when {
        strategy.containsMatchIn(text) -> ItemRoute.Strategy
        tracker.containsMatchIn(text) -> ItemRoute.Task
        calendar.containsMatchIn(text) -> ItemRoute.Calendar
        else -> ItemRoute.Task
    }
}

fun sampleResolveState(): ResolveState {
    val now = Instant.now()
    return ResolveState(
        items = emptyList(),
        threads = emptyList(),
        notes = emptyList(),
        calendarEvents = listOf(
            CalendarEvent(
                provider = "feishu",
                status = "readonly",
                title = "Investor call",
                startsAt = now.plusSeconds(3600),
                endsAt = now.plusSeconds(5400),
                canEdit = false,
                canDelete = false
            ),
            CalendarEvent(
                provider = "feishu",
                status = "synced",
                title = "Cardio",
                startsAt = now.plusSeconds(6 * 3600),
                endsAt = now.plusSeconds(7 * 3600)
            )
        )
    )
}
