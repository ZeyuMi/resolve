package ai.tiiny.resolve

data class FeishuScope(val key: String, val label: String)

val FeishuCalendarScopes = listOf(
    FeishuScope("offline_access", "离线访问已授权数据"),
    FeishuScope("calendar:calendar:readonly", "读取主日历"),
    FeishuScope("calendar:calendar", "管理日历"),
    FeishuScope("calendar:event:readonly", "读取日程列表"),
    FeishuScope("calendar:event", "创建、更新、删除日程"),
    FeishuScope("contact:user.email:readonly", "读取参与人邮箱（可选）")
)
