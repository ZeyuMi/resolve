package ai.tiiny.resolve

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncWorker.schedule(this)
        setContent {
            ResolveApp()
        }
    }
}

private enum class Tab { Todo, Calendar, Strategy, Settings }

@Composable
private fun ResolveApp() {
    var state by remember { mutableStateOf(sampleResolveState()) }
    var tab by remember { mutableStateOf(Tab.Todo) }
    var capture by remember { mutableStateOf("") }

    ResolveTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Colors.Bg)
                .padding(16.dp)
        ) {
            Text("Resolve", color = Colors.Muted)
            Text(tab.name, color = Colors.Text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            CaptureBox(
                value = capture,
                onChange = { capture = it },
                onSave = {
                    if (capture.isNotBlank()) {
                        state = state.copy(
                            items = listOf(
                                ResolveItem(type = ItemType.Task, status = ItemStatus.Active, title = capture.trim())
                            ) + state.items
                        )
                        capture = ""
                        tab = Tab.Todo
                    }
                }
            )
            Spacer(Modifier.height(14.dp))
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.Todo -> TodoScreen(state)
                    Tab.Calendar -> CalendarScreen(state)
                    Tab.Strategy -> StrategyScreen(state)
                    Tab.Settings -> SettingsScreen()
                }
            }
            BottomTabs(tab = tab, onTab = { tab = it })
        }
    }
}

@Composable
private fun CaptureBox(value: String, onChange: (String) -> Unit, onSave: () -> Unit) {
    SurfaceBlock {
        Text("快速加入 Todo", color = Colors.Secondary)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            placeholder = { Text("记一下，自动进入 Todo") }
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = Colors.Accent)) {
                Text("Save")
            }
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Colors.SurfaceElevated)) {
                Text("Voice")
            }
        }
    }
}

@Composable
private fun TodoScreen(state: ResolveState) {
    val todos = state.items.filter { it.type == ItemType.Task && it.status == ItemStatus.Active }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Todo") }
        items(todos) { CompactItem(it) }
    }
}

@Composable
private fun CalendarScreen(state: ResolveState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Feishu Calendar") }
        items(state.calendarEvents) { CalendarRow(it) }
        item {
            SurfaceBlock {
                Text("Add Event", color = Colors.Text, fontWeight = FontWeight.SemiBold)
                Text("原生版会在这里创建日程并同步到飞书。", color = Colors.Secondary)
            }
        }
    }
}

@Composable
private fun StrategyScreen(state: ResolveState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.threads) { thread ->
            val subtasks = state.items.filter { it.type == ItemType.Task && it.strategyThreadId == thread.id && it.status == ItemStatus.Active }
            SurfaceBlock {
                Text(thread.title, color = Colors.Text, fontWeight = FontWeight.SemiBold)
                Text(thread.currentHypothesis, color = Colors.Secondary)
                Spacer(Modifier.height(8.dp))
                Text("Subtasks in Todo: ${subtasks.size}", color = Colors.Calendar)
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SurfaceBlock {
                Text("Feishu Calendar", color = Colors.Text, fontWeight = FontWeight.SemiBold)
                Text("Status: Not connected", color = Colors.Muted)
                FeishuCalendarScopes.forEach {
                    Text("${it.label} · ${it.key}", color = Colors.Secondary)
                }
            }
        }
    }
}

@Composable
private fun CalendarRow(event: CalendarEvent) {
    val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    SurfaceBlock {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatter.format(event.startsAt), color = Colors.Calendar)
            Column {
                Text(event.title, color = Colors.Text)
                Text("${event.provider} · ${event.status}", color = Colors.Muted)
            }
        }
    }
}

@Composable
private fun CompactItem(item: ResolveItem) {
    SurfaceBlock {
        Text(item.title, color = Colors.Text)
        Text(item.status.name, color = Colors.Muted)
    }
}

@Composable
private fun SmallRoute(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, Colors.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, color = Colors.Secondary)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = Colors.Secondary, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SurfaceBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Colors.Surface, RoundedCornerShape(20.dp))
            .border(1.dp, Colors.Border, RoundedCornerShape(20.dp))
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun BottomTabs(tab: Tab, onTab: (Tab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(Tab.Todo, Tab.Calendar, Tab.Strategy).forEach {
            Button(
                modifier = Modifier.weight(1f),
                onClick = { onTab(it) },
                colors = ButtonDefaults.buttonColors(containerColor = if (tab == it) Colors.SurfaceElevated else Colors.Surface)
            ) {
                Text(it.name)
            }
        }
    }
}

@Composable
private fun ResolveTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

private object Colors {
    val Bg = Color(0xFF0B0D10)
    val Surface = Color(0xFF11141A)
    val SurfaceElevated = Color(0xFF171B22)
    val Border = Color.White.copy(alpha = 0.08f)
    val Text = Color(0xFFF4F6FA)
    val Secondary = Color(0xFFA8AFBD)
    val Muted = Color(0xFF6F7787)
    val Accent = Color(0xFF7C5CFF)
    val Calendar = Color(0xFF4CC9F0)
}
