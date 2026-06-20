package ai.tiiny.resolve

import java.time.Instant
import java.util.UUID

enum class ItemType { Capture, Task, Tracker, StrategyNote, CalendarDraft }
enum class ItemStatus { Inbox, Triaged, Active, Waiting, Watching, Discuss, ReviewLater, Done, Archived, Deleted, Killed }
enum class ItemRoute { Calendar, Strategy, Archive, Delete }

data class ResolveItem(
    val id: String = "item_${UUID.randomUUID()}",
    val type: ItemType,
    val status: ItemStatus,
    val title: String,
    val source: String = "android",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val reviewAt: Instant? = null,
    val strategyThreadId: String? = null,
    val sourceItemId: String? = null
)

data class StrategyThread(
    val id: String = "thread_${UUID.randomUUID()}",
    val title: String,
    val currentHypothesis: String,
    val status: String = "active"
)

data class CalendarEvent(
    val id: String = "cal_${UUID.randomUUID()}",
    val provider: String,
    val status: String,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant? = null,
    val canEdit: Boolean = true,
    val canDelete: Boolean = true
)

data class ResolveState(
    val items: List<ResolveItem>,
    val threads: List<StrategyThread>,
    val calendarEvents: List<CalendarEvent>
)

fun routeSuggestion(text: String): ItemRoute {
    val strategy = Regex("战略|融资|产品|组织|人才|PMF|叙事|市场|enterprise|workflow", RegexOption.IGNORE_CASE)
    val tracker = Regex("等|回复|问|跟进|推进|下次|提醒|观察|看看")
    val calendar = Regex("明天|后天|今天|周一|周二|周三|周四|周五|周六|周日|下午|上午|晚上|\\d+\\s*点|\\d+:\\d+")
    return when {
        strategy.containsMatchIn(text) -> ItemRoute.Strategy
        tracker.containsMatchIn(text) -> ItemRoute.Tracker
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
            ResolveItem(type = ItemType.Task, status = ItemStatus.Active, title = "和 Alex 聊完，感觉融资叙事应该强调速度优势", strategyThreadId = threads[0].id),
            ResolveItem(type = ItemType.Task, status = ItemStatus.Active, title = "明天问 Sarah 候选人推进到哪一步了"),
            ResolveItem(type = ItemType.Task, status = ItemStatus.Active, title = "Investor call 准备", strategyThreadId = threads[0].id),
            ResolveItem(type = ItemType.Task, status = ItemStatus.Active, title = "B 推荐增长负责人", reviewAt = now, strategyThreadId = threads[2].id),
            ResolveItem(type = ItemType.StrategyNote, status = ItemStatus.Active, title = "产品方向：enterprise workflow 可能更强", strategyThreadId = threads[1].id)
        ),
        threads = threads,
        calendarEvents = listOf(
            CalendarEvent(provider = "feishu", status = "readonly", title = "Investor call", startsAt = now.plusSeconds(3600), canEdit = false, canDelete = false),
            CalendarEvent(provider = "feishu", status = "synced", title = "Cardio", startsAt = now.plusSeconds(6 * 3600))
        )
    )
}
