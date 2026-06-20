package ai.tiiny.resolve

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

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
    val dueAt: Instant? = null,
    val strategyThreadId: String? = null,
    val sourceItemId: String? = null
)

data class StrategyThread(
    val id: String = "thread_${UUID.randomUUID()}",
    val title: String,
    val currentHypothesis: String = "",
    val status: String = "active"
)

data class CalendarEvent(
    val id: String = "cal_${UUID.randomUUID()}",
    val provider: String = "local",
    val status: String = "local",
    val title: String,
    val description: String = "",
    val startsAt: Instant,
    val endsAt: Instant? = null,
    val externalCalendarId: String? = null,
    val externalEventId: String? = null,
    val sourceItemId: String? = null,
    val strategyThreadId: String? = null,
    val canEdit: Boolean = true,
    val canDelete: Boolean = true
)

data class FeishuSettings(
    val appId: String = "",
    val defaultCalendar: String = "primary",
    val pastDays: Int = 14,
    val futureDays: Int = 90,
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
    val threads = listOf(
        StrategyThread(title = "融资策略", currentHypothesis = "强调 execution velocity + AI-native workflow。"),
        StrategyThread(title = "产品方向 / PMF", currentHypothesis = "继续验证 enterprise workflow。"),
        StrategyThread(title = "核心人才招募", currentHypothesis = "把关键候选人推进变成可见节奏。"),
        StrategyThread(title = "组织节奏与授权边界", currentHypothesis = "降低创始人上下文切换。"),
        StrategyThread(title = "Vibe Coding / 工具想法", currentHypothesis = "先服务自己真实使用。")
    )
    return ResolveState(
        items = listOf(
            ResolveItem(title = "和 Alex 聊完，感觉融资叙事应该强调速度优势", strategyThreadId = threads[0].id),
            ResolveItem(title = "明天问 Sarah 候选人推进到哪一步了"),
            ResolveItem(title = "Investor call 准备", strategyThreadId = threads[0].id),
            ResolveItem(title = "B 推荐增长负责人", strategyThreadId = threads[2].id),
            ResolveItem(type = ItemType.StrategyNote, title = "产品方向：enterprise workflow 可能更强", strategyThreadId = threads[1].id)
        ),
        threads = threads,
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
