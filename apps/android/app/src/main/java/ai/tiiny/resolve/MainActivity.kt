package ai.tiiny.resolve

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncWorker.schedule(this)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        setContent {
            ResolveAndroidApp(initialCapture = sharedText)
        }
    }
}

private enum class Tab(
    val label: String,
    val icon: ImageVector
) {
    Todo("Todo", Icons.Filled.TaskAlt),
    Calendar("Calendar", Icons.Filled.CalendarMonth),
    Strategy("Strategy", Icons.Filled.Psychology),
    Settings("Settings", Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolveAndroidApp(initialCapture: String) {
    val context = LocalContext.current
    val repository = remember { ResolveRepository(context) }
    val secureVault = remember { SecureVault(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(repository.load()) }
    var tab by remember { mutableStateOf(Tab.Todo) }
    var capture by remember { mutableStateOf(initialCapture) }
    var toast by remember { mutableStateOf<String?>(null) }
    var selectedTodoId by remember { mutableStateOf<String?>(null) }
    var selectedThreadId by remember { mutableStateOf(state.threads.firstOrNull()?.id.orEmpty()) }
    var calendarDraft by remember { mutableStateOf(CalendarDraft()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showCompleted by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var showAdvancedSettings by remember { mutableStateOf(state.feishuSettings.appId.isBlank()) }
    var isSyncing by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var hasBackendSession by remember { mutableStateOf(secureVault.loadBackendSession() != null) }

    fun persist(next: ResolveState) {
        state = next
        repository.save(next)
    }

    fun updateItem(item: ResolveItem) {
        persist(state.copy(items = state.items.map { if (it.id == item.id) item.copy(updatedAt = Instant.now()) else it }))
    }

    fun patchFeishu(settings: FeishuSettings) {
        persist(state.copy(feishuSettings = settings))
    }

    fun patchBackend(settings: BackendSettings) {
        persist(state.copy(backendSettings = settings))
    }

    fun saveCapture() {
        val text = capture.trim()
        if (text.isBlank()) return
        val item = ResolveItem(
            title = text,
            strategyThreadId = if (routeSuggestion(text) == ItemRoute.Strategy) selectedThreadId.takeIf { it.isNotBlank() } else null,
            dueAt = if (routeSuggestion(text) == ItemRoute.Calendar) Instant.now().plusSeconds(24 * 3600) else null
        )
        persist(state.copy(items = listOf(item) + state.items))
        capture = ""
        tab = Tab.Todo
        toast = "Saved to Todo"
    }

    suspend fun connectedClient(): FeishuAndroidClient {
        val settings = state.feishuSettings
        val secret = secureVault.loadFeishuSecret().orEmpty()
        var token = secureVault.loadFeishuTokens() ?: error("Connect Feishu first.")
        var client = FeishuAndroidClient(settings.appId, secret, token)
        if (token.shouldRefresh()) {
            token = withContext(Dispatchers.IO) { client.refreshToken() }
            secureVault.saveFeishuTokens(token.accessToken, token.refreshToken, token.expiresAtEpochMillis)
            client = FeishuAndroidClient(settings.appId, secret, token)
        }
        return client
    }

    suspend fun connectedBackendClient(): BackendClient {
        val settings = state.backendSettings
        var session = secureVault.loadBackendSession() ?: error("Sign in to Resolve backend first.")
        var client = BackendClient(settings, session)
        if (session.shouldRefresh()) {
            session = withContext(Dispatchers.IO) { client.refreshSession() }
            secureVault.saveBackendSession(session.accessToken, session.refreshToken, session.expiresAtEpochMillis)
            client = BackendClient(settings, session)
            hasBackendSession = true
        }
        return client
    }

    suspend fun syncBackendCalendar() {
        val client = connectedBackendClient()
        val syncedAt = withContext(Dispatchers.IO) { client.syncFeishuNow() }
        val remoteEvents = withContext(Dispatchers.IO) { client.listEvents(state.feishuSettings) }
        persist(
            state.copy(
                calendarEvents = mergeBackendCalendarEvents(state.calendarEvents, remoteEvents),
                backendSettings = state.backendSettings.copy(
                    status = BackendStatus.Connected,
                    feishuConnected = true,
                    lastSyncedAt = syncedAt,
                    lastError = null
                ),
                feishuSettings = state.feishuSettings.copy(
                    status = FeishuStatus.Connected,
                    lastSyncedAt = syncedAt,
                    lastError = null
                )
            )
        )
    }

    fun syncFeishu() {
        if (isSyncing) return
        isSyncing = true
        scope.launch {
            try {
                if (hasBackendSession && state.backendSettings.supabaseUrl.isNotBlank() && state.backendSettings.feishuConnected) {
                    syncBackendCalendar()
                    toast = "Backend calendar synced"
                } else {
                    val client = connectedClient()
                    val remoteEvents = withContext(Dispatchers.IO) { client.listEvents(state.feishuSettings) }
                    val nextSettings = state.feishuSettings.copy(
                        status = FeishuStatus.Connected,
                        lastSyncedAt = Instant.now(),
                        lastError = null
                    )
                    persist(
                        state.copy(
                            calendarEvents = mergeBackendCalendarEvents(state.calendarEvents, remoteEvents),
                            feishuSettings = nextSettings
                        )
                    )
                    toast = "Feishu synced"
                }
            } catch (error: Throwable) {
                if (hasBackendSession) {
                    patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                }
                patchFeishu(state.feishuSettings.copy(status = FeishuStatus.PermissionError, lastError = error.message))
                toast = "Calendar sync failed"
            } finally {
                isSyncing = false
            }
        }
    }

    fun createCalendarEvent(draft: CalendarDraft) {
        if (draft.title.isBlank()) return
        val startsAt = draft.date.atTime(draft.time).atZone(ZoneId.systemDefault()).toInstant()
        val localEvent = CalendarEvent(
            title = draft.title.trim(),
            description = draft.description.trim(),
            status = if (hasBackendSession || state.feishuSettings.status == FeishuStatus.Connected) "local_pending_create" else "local",
            startsAt = startsAt,
            endsAt = startsAt.plusSeconds(3600),
            sourceItemId = draft.sourceItemId,
            strategyThreadId = draft.strategyThreadId
        )
        persist(state.copy(calendarEvents = (state.calendarEvents + localEvent).sortedBy { it.startsAt }))
        calendarDraft = CalendarDraft(date = draft.date, time = draft.time.plusHours(1))
        toast = "Saved locally"

        if (hasBackendSession && state.backendSettings.feishuConnected) {
            scope.launch {
                try {
                    val client = connectedBackendClient()
                    val remote = withContext(Dispatchers.IO) { client.createEvent(draft) }
                    persist(
                        state.copy(
                            calendarEvents = state.calendarEvents
                                .filterNot { it.id == localEvent.id }
                                .plus(remote)
                                .sortedBy { it.startsAt },
                            backendSettings = state.backendSettings.copy(lastSyncedAt = Instant.now(), lastError = null),
                            feishuSettings = state.feishuSettings.copy(status = FeishuStatus.Connected, lastSyncedAt = Instant.now(), lastError = null)
                        )
                    )
                    toast = "Created in Feishu"
                } catch (error: Throwable) {
                    patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                    toast = "Saved locally; backend create failed"
                }
            }
            return
        }

        val settings = state.feishuSettings
        if (settings.status == FeishuStatus.Connected && settings.appId.isNotBlank()) {
            scope.launch {
                try {
                    val client = connectedClient()
                    val remote = withContext(Dispatchers.IO) { client.createEvent(settings, draft) }
                    persist(
                        state.copy(
                            calendarEvents = state.calendarEvents
                                .filterNot { it.id == localEvent.id }
                                .plus(remote)
                                .sortedBy { it.startsAt }
                        )
                    )
                    toast = "Created in Feishu"
                } catch (error: Throwable) {
                    toast = "Saved locally; Feishu create failed"
                    patchFeishu(settings.copy(lastError = error.message))
                }
            }
        }
    }

    fun connectFeishu() {
        val settings = state.feishuSettings
        val secret = secureVault.loadFeishuSecret().orEmpty()
        if (settings.appId.isBlank() || secret.isBlank()) {
            showAdvancedSettings = true
            toast = "Add App ID and Secret once"
            return
        }
        isConnecting = true
        scope.launch {
            try {
                val (url, expectedState) = FeishuAndroidClient.buildAuthorizeUrl(settings.appId)
                val codeDeferred = async(Dispatchers.IO) { FeishuAndroidClient.waitForOAuthCode(expectedState) }
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                val code = codeDeferred.await()
                val token = withContext(Dispatchers.IO) { FeishuAndroidClient.exchangeCode(settings.appId, secret, code) }
                secureVault.saveFeishuTokens(token.accessToken, token.refreshToken, token.expiresAtEpochMillis)
                patchFeishu(settings.copy(status = FeishuStatus.Connected, lastError = null))
                toast = "Feishu connected"
                syncFeishu()
            } catch (error: Throwable) {
                patchFeishu(settings.copy(status = FeishuStatus.PermissionError, lastError = error.message))
                toast = "Feishu connection failed"
            } finally {
                isConnecting = false
            }
        }
    }

    fun signInBackend(password: String) {
        if (isConnecting) return
        isConnecting = true
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    BackendClient.signInWithPassword(state.backendSettings, password)
                }
                secureVault.saveBackendSession(session.accessToken, session.refreshToken, session.expiresAtEpochMillis)
                hasBackendSession = true
                val client = BackendClient(state.backendSettings, session)
                val connectorStatus = runCatching {
                    withContext(Dispatchers.IO) { client.status() }
                }.getOrNull()
                persist(
                    state.copy(
                        backendSettings = state.backendSettings.copy(
                            status = BackendStatus.Connected,
                            feishuConnected = connectorStatus?.connected ?: state.backendSettings.feishuConnected,
                            lastSyncedAt = connectorStatus?.lastServerSyncAt ?: state.backendSettings.lastSyncedAt,
                            lastError = null
                        ),
                        feishuSettings = state.feishuSettings.copy(
                            status = if (connectorStatus?.connected == true) FeishuStatus.Connected else state.feishuSettings.status,
                            lastSyncedAt = connectorStatus?.lastServerSyncAt ?: state.feishuSettings.lastSyncedAt
                        )
                    )
                )
                toast = "Backend signed in"
                if (connectorStatus?.connected == true) syncFeishu()
            } catch (error: Throwable) {
                patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                toast = "Backend sign in failed"
            } finally {
                isConnecting = false
            }
        }
    }

    fun connectBackendFeishu() {
        val settings = state.feishuSettings
        val secret = secureVault.loadFeishuSecret().orEmpty()
        if (settings.appId.isBlank() || secret.isBlank()) {
            showAdvancedSettings = true
            toast = "Add Feishu App ID and Secret once"
            return
        }
        isConnecting = true
        scope.launch {
            try {
                val client = connectedBackendClient()
                val oauth = withContext(Dispatchers.IO) { client.configureFeishu(settings.appId, secret) }
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(oauth.authorizeUrl)))
                patchBackend(state.backendSettings.copy(status = BackendStatus.Connected, feishuConnected = false, lastError = null))
                patchFeishu(settings.copy(status = FeishuStatus.NotConnected, lastError = null))
                toast = "Authorize Feishu, then return to Resolve"

                repeat(24) {
                    delay(5_000)
                    val status = runCatching { withContext(Dispatchers.IO) { client.status() } }.getOrNull()
                    if (status?.connected == true) {
                        persist(
                            state.copy(
                                backendSettings = state.backendSettings.copy(
                                    status = BackendStatus.Connected,
                                    feishuConnected = true,
                                    lastSyncedAt = status.lastServerSyncAt,
                                    lastError = null
                                ),
                                feishuSettings = state.feishuSettings.copy(status = FeishuStatus.Connected, lastError = null)
                            )
                        )
                        syncFeishu()
                        return@launch
                    }
                }
                toast = "Feishu auth pending; tap Sync after approving"
            } catch (error: Throwable) {
                patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                toast = "Backend Feishu connection failed"
            } finally {
                isConnecting = false
            }
        }
    }

    fun disconnectBackend() {
        secureVault.clearBackendSession()
        hasBackendSession = false
        patchBackend(state.backendSettings.copy(status = BackendStatus.SignedOut, feishuConnected = false, lastError = null))
        toast = "Backend signed out"
    }

    LaunchedEffect(Unit) {
        if (hasBackendSession && state.backendSettings.feishuConnected) {
            syncFeishu()
        }
    }

    ResolveTheme {
        Scaffold(
            containerColor = ResolveColors.Bg,
            bottomBar = { BottomTabs(tab = tab, onTab = { tab = it }) },
            floatingActionButton = {
                if (tab == Tab.Calendar) {
                    FloatingActionButton(
                        onClick = { createCalendarEvent(calendarDraft.copy(date = selectedDate)) },
                        containerColor = ResolveColors.Accent
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add event", tint = Color.White)
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                TopHeader(
                    title = tab.label,
                    settings = state.feishuSettings,
                    backend = state.backendSettings,
                    canSync = (hasBackendSession && state.backendSettings.feishuConnected) ||
                        state.feishuSettings.status == FeishuStatus.Connected,
                    isSyncing = isSyncing,
                    onSettings = { tab = Tab.Settings },
                    onSync = { syncFeishu() }
                )
                Spacer(Modifier.height(10.dp))
                CaptureBox(value = capture, onChange = { capture = it }, onSave = { saveCapture() })
                Spacer(Modifier.height(12.dp))
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.Todo -> TodoScreen(
                            state = state,
                            showCompleted = showCompleted,
                            showArchived = showArchived,
                            onToggleDone = { item ->
                                updateItem(item.copy(status = if (item.status == ItemStatus.Done) ItemStatus.Active else ItemStatus.Done))
                            },
                            onSelect = { selectedTodoId = it.id },
                            onShowCompleted = { showCompleted = !showCompleted },
                            onShowArchived = { showArchived = !showArchived }
                        )

                        Tab.Calendar -> CalendarScreen(
                            state = state,
                            draft = calendarDraft,
                            selectedDate = selectedDate,
                            onDate = {
                                selectedDate = it
                                calendarDraft = calendarDraft.copy(date = it)
                            },
                            onDraft = { calendarDraft = it },
                            onCreate = { createCalendarEvent(it) }
                        )

                        Tab.Strategy -> StrategyScreen(
                            state = state,
                            selectedThreadId = selectedThreadId,
                            onThread = { selectedThreadId = it },
                            onAddThread = { title, hypothesis ->
                                if (title.isNotBlank()) {
                                    val thread = StrategyThread(title = title.trim(), currentHypothesis = hypothesis.trim())
                                    persist(state.copy(threads = listOf(thread) + state.threads))
                                    selectedThreadId = thread.id
                                }
                            },
                            onAddTask = { title ->
                                if (title.isNotBlank()) {
                                    val item = ResolveItem(title = title.trim(), strategyThreadId = selectedThreadId)
                                    persist(state.copy(items = listOf(item) + state.items))
                                    tab = Tab.Todo
                                }
                            },
                            onSelectTodo = { selectedTodoId = it.id },
                            onToggleDone = { item -> updateItem(item.copy(status = ItemStatus.Done)) }
                        )

                        Tab.Settings -> SettingsScreen(
                            state = state,
                            secureVault = secureVault,
                            showAdvanced = showAdvancedSettings,
                            isConnecting = isConnecting,
                            isSyncing = isSyncing,
                            onShowAdvanced = { showAdvancedSettings = !showAdvancedSettings },
                            onSettings = { patchFeishu(it) },
                            onBackendSettings = { patchBackend(it) },
                            onBackendSignIn = { signInBackend(it) },
                            onBackendDisconnect = { disconnectBackend() },
                            onBackendFeishuConnect = {
                                if (hasBackendSession && state.backendSettings.supabaseUrl.isNotBlank()) {
                                    connectBackendFeishu()
                                } else {
                                    connectFeishu()
                                }
                            },
                            onSync = { syncFeishu() },
                            onDisconnect = {
                                secureVault.clearFeishu()
                                patchFeishu(state.feishuSettings.copy(status = FeishuStatus.NotConnected, lastError = null, lastSyncedAt = null))
                            }
                        )
                    }
                }
            }
        }

        val selectedTodo = state.items.find { it.id == selectedTodoId }
        if (selectedTodo != null) {
            ModalBottomSheet(onDismissRequest = { selectedTodoId = null }, containerColor = ResolveColors.Surface) {
                TodoDetailSheet(
                    item = selectedTodo,
                    threads = state.threads,
                    onClose = { selectedTodoId = null },
                    onUpdate = { updateItem(it) },
                    onArchive = {
                        updateItem(it.copy(status = ItemStatus.Archived))
                        selectedTodoId = null
                    },
                    onCalendar = {
                        calendarDraft = CalendarDraft(
                            title = it.title,
                            date = it.dueAt?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: LocalDate.now(),
                            time = it.dueAt?.atZone(ZoneId.systemDefault())?.toLocalTime()?.withSecond(0)?.withNano(0) ?: LocalTime.of(9, 0),
                            description = it.notes,
                            sourceItemId = it.id,
                            strategyThreadId = it.strategyThreadId
                        )
                        selectedDate = calendarDraft.date
                        selectedTodoId = null
                        tab = Tab.Calendar
                    }
                )
            }
        }

        toast?.let {
            ToastChip(message = it, onDismiss = { toast = null })
        }
    }
}

@Composable
private fun TopHeader(
    title: String,
    settings: FeishuSettings,
    backend: BackendSettings,
    canSync: Boolean,
    isSyncing: Boolean,
    onSettings: () -> Unit,
    onSync: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Resolve", color = ResolveColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(title, color = ResolveColors.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        }
        AssistChip(
            onClick = onSync,
            enabled = canSync && !isSyncing,
            leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(16.dp)) },
            label = { Text(if (isSyncing) "Syncing" else calendarStatusLabel(settings, backend)) }
        )
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = ResolveColors.Secondary)
        }
    }
}

@Composable
private fun CaptureBox(value: String, onChange: (String) -> Unit, onSave: () -> Unit) {
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = ResolveColors.Surface)) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
                placeholder = { Text("记一下，直接进 Todo") },
                colors = inputColors()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onSave,
                    colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent),
                    enabled = value.isNotBlank()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save")
                }
                Text("手机打开先捕捉，整理稍后做。", color = ResolveColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TodoScreen(
    state: ResolveState,
    showCompleted: Boolean,
    showArchived: Boolean,
    onToggleDone: (ResolveItem) -> Unit,
    onSelect: (ResolveItem) -> Unit,
    onShowCompleted: () -> Unit,
    onShowArchived: () -> Unit
) {
    val active = state.items.filter { it.type == ItemType.Task && it.status == ItemStatus.Active }
    val completed = state.items.filter { it.type == ItemType.Task && it.status == ItemStatus.Done }
    val archived = state.items.filter { it.type == ItemType.Task && it.status == ItemStatus.Archived }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionLabel("Active", "${active.size} items") }
        items(active, key = { it.id }) { item ->
            TodoRow(
                item = item,
                thread = state.threads.find { it.id == item.strategyThreadId },
                onToggleDone = { onToggleDone(item) },
                onSelect = { onSelect(item) }
            )
        }
        item {
            CollapsedHeader("Completed", completed.size, showCompleted, onShowCompleted)
        }
        if (showCompleted) {
            items(completed, key = { it.id }) { item ->
                TodoRow(item = item, thread = state.threads.find { it.id == item.strategyThreadId }, onToggleDone = { onToggleDone(item) }, onSelect = { onSelect(item) })
            }
        }
        item {
            CollapsedHeader("Archived", archived.size, showArchived, onShowArchived)
        }
        if (showArchived) {
            items(archived, key = { it.id }) { item ->
                TodoRow(item = item, thread = state.threads.find { it.id == item.strategyThreadId }, onToggleDone = {}, onSelect = { onSelect(item) })
            }
        }
    }
}

@Composable
private fun TodoRow(
    item: ResolveItem,
    thread: StrategyThread?,
    onToggleDone: () -> Unit,
    onSelect: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.outlinedCardColors(containerColor = ResolveColors.Surface)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            IconButton(onClick = onToggleDone, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (item.status == ItemStatus.Done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = "Toggle done",
                    tint = if (item.status == ItemStatus.Done) ResolveColors.Accent else ResolveColors.Muted
                )
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    color = if (item.status == ItemStatus.Archived) ResolveColors.Muted else ResolveColors.Text,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (item.status == ItemStatus.Done) TextDecoration.LineThrough else TextDecoration.None
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    item.dueAt?.let { MetaPill(dateLabel(it)) }
                    thread?.let { MetaPill(it.title) }
                    if (item.notes.isNotBlank()) MetaPill("Comment")
                }
            }
        }
    }
}

@Composable
private fun CalendarScreen(
    state: ResolveState,
    draft: CalendarDraft,
    selectedDate: LocalDate,
    onDate: (LocalDate) -> Unit,
    onDraft: (CalendarDraft) -> Unit,
    onCreate: (CalendarDraft) -> Unit
) {
    val context = LocalContext.current
    val events = state.calendarEvents.filter {
        it.startsAt.atZone(ZoneId.systemDefault()).toLocalDate() == selectedDate
    }.sortedBy { it.startsAt }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((-3..10).map { LocalDate.now().plusDays(it.toLong()) }) { date ->
                    FilterChip(
                        selected = date == selectedDate,
                        onClick = { onDate(date) },
                        label = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(date.dayOfMonth.toString())
                                Text(date.dayOfWeek.name.take(3), fontSize = 11.sp)
                            }
                        }
                    )
                }
            }
        }
        item { SectionLabel(selectedDate.format(DateTimeFormatter.ofPattern("M月d日")), "${events.size} events") }
        items(events, key = { it.id }) { event -> CalendarRow(event) }
        item {
            OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = ResolveColors.Surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Add Event", color = ResolveColors.Text, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = { onDraft(draft.copy(title = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("日程标题") },
                        colors = inputColors()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {
                                DatePickerDialog(
                                    context,
                                    { _, year, month, day -> onDraft(draft.copy(date = LocalDate.of(year, month + 1, day))) },
                                    draft.date.year,
                                    draft.date.monthValue - 1,
                                    draft.date.dayOfMonth
                                ).show()
                            },
                            leadingIcon = { Icon(Icons.Filled.Event, contentDescription = null, Modifier.size(16.dp)) },
                            label = { Text(draft.date.format(DateTimeFormatter.ofPattern("M月d日"))) }
                        )
                        AssistChip(
                            onClick = {
                                TimePickerDialog(
                                    context,
                                    { _, hour, minute -> onDraft(draft.copy(time = LocalTime.of(hour, minute))) },
                                    draft.time.hour,
                                    draft.time.minute,
                                    true
                                ).show()
                            },
                            leadingIcon = { Icon(Icons.Filled.EditCalendar, contentDescription = null, Modifier.size(16.dp)) },
                            label = { Text(draft.time.format(DateTimeFormatter.ofPattern("HH:mm"))) }
                        )
                    }
                    OutlinedTextField(
                        value = draft.description,
                        onValueChange = { onDraft(draft.copy(description = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        placeholder = { Text("备注，可选") },
                        colors = inputColors()
                    )
                    Button(
                        onClick = { onCreate(draft.copy(date = selectedDate)) },
                        enabled = draft.title.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent)
                    ) {
                        Text("Create")
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarRow(event: CalendarEvent) {
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = ResolveColors.Surface)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Text(eventTimeLabel(event.startsAt), color = ResolveColors.Accent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(event.title, color = ResolveColors.Text, fontSize = 15.sp)
                Text("${event.provider} · ${event.status}", color = ResolveColors.Muted, fontSize = 12.sp)
                if (event.description.isNotBlank()) Text(event.description, color = ResolveColors.Secondary, fontSize = 13.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun StrategyScreen(
    state: ResolveState,
    selectedThreadId: String,
    onThread: (String) -> Unit,
    onAddThread: (String, String) -> Unit,
    onAddTask: (String) -> Unit,
    onSelectTodo: (ResolveItem) -> Unit,
    onToggleDone: (ResolveItem) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var hypothesis by remember { mutableStateOf("") }
    var task by remember { mutableStateOf("") }
    val selected = state.threads.find { it.id == selectedThreadId } ?: state.threads.firstOrNull()
    val subtasks = state.items.filter { it.strategyThreadId == selected?.id && it.type == ItemType.Task && it.status == ItemStatus.Active }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.threads, key = { it.id }) { thread ->
                    FilterChip(selected = thread.id == selected?.id, onClick = { onThread(thread.id) }, label = { Text(thread.title) })
                }
            }
        }
        item {
            OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = ResolveColors.Surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected?.title.orEmpty(), color = ResolveColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(selected?.currentHypothesis.orEmpty(), color = ResolveColors.Secondary)
                    OutlinedTextField(value = task, onValueChange = { task = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("添加战略子任务") }, colors = inputColors())
                    Button(
                        onClick = {
                            onAddTask(task)
                            task = ""
                        },
                        enabled = task.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add Subtask")
                    }
                }
            }
        }
        item { SectionLabel("Subtasks in Todo", "${subtasks.size} active") }
        items(subtasks, key = { it.id }) { item ->
            TodoRow(item = item, thread = null, onToggleDone = { onToggleDone(item) }, onSelect = { onSelectTodo(item) })
        }
        item {
            OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = ResolveColors.Surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("New Direction", color = ResolveColors.Text, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("战略方向") }, colors = inputColors())
                    OutlinedTextField(value = hypothesis, onValueChange = { hypothesis = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("当前假设，可选") }, colors = inputColors())
                    TextButton(onClick = {
                        onAddThread(title, hypothesis)
                        title = ""
                        hypothesis = ""
                    }) {
                        Text("Create Direction")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: ResolveState,
    secureVault: SecureVault,
    showAdvanced: Boolean,
    isConnecting: Boolean,
    isSyncing: Boolean,
    onShowAdvanced: () -> Unit,
    onSettings: (FeishuSettings) -> Unit,
    onBackendSettings: (BackendSettings) -> Unit,
    onBackendSignIn: (String) -> Unit,
    onBackendDisconnect: () -> Unit,
    onBackendFeishuConnect: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit
) {
    var appId by remember(state.feishuSettings.appId) { mutableStateOf(state.feishuSettings.appId) }
    var appSecret by remember { mutableStateOf(secureVault.loadFeishuSecret().orEmpty()) }
    var supabaseUrl by remember(state.backendSettings.supabaseUrl) { mutableStateOf(state.backendSettings.supabaseUrl) }
    var anonKey by remember(state.backendSettings.anonKey) { mutableStateOf(state.backendSettings.anonKey) }
    var email by remember(state.backendSettings.email) { mutableStateOf(state.backendSettings.email) }
    var password by remember { mutableStateOf("") }
    val backendReady = state.backendSettings.status == BackendStatus.Connected || state.backendSettings.feishuConnected
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = ResolveColors.Surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.TaskAlt, contentDescription = null, tint = ResolveColors.Accent)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Resolve Backend", color = ResolveColors.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                            Text(backendStatusLabel(state.backendSettings), color = ResolveColors.Secondary)
                        }
                    }
                    Text(
                        "手机和 Mac 共享同一个后端。Todo/Strategy 后续仍可做端到端加密；飞书日历由后端连接。",
                        color = ResolveColors.Secondary
                    )
                    OutlinedTextField(
                        value = supabaseUrl,
                        onValueChange = {
                            supabaseUrl = it.trim()
                            onBackendSettings(state.backendSettings.copy(supabaseUrl = supabaseUrl))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Supabase URL") },
                        colors = inputColors()
                    )
                    OutlinedTextField(
                        value = anonKey,
                        onValueChange = {
                            anonKey = it.trim()
                            onBackendSettings(state.backendSettings.copy(anonKey = anonKey))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Supabase anon key") },
                        colors = inputColors()
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it.trim()
                            onBackendSettings(state.backendSettings.copy(email = email))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Login email") },
                        colors = inputColors()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(if (backendReady) "Password, only when signing in again" else "Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        colors = inputColors()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        Button(
                            onClick = {
                                onBackendSignIn(password)
                                password = ""
                            },
                            enabled = !isConnecting && supabaseUrl.isNotBlank() && anonKey.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent)
                        ) {
                            Icon(Icons.Filled.Key, contentDescription = null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (backendReady) "Re-sign in" else "Sign in")
                        }
                        Button(onClick = onSync, enabled = state.backendSettings.feishuConnected && !isSyncing) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isSyncing) "Syncing" else "Sync backend")
                        }
                        TextButton(onClick = onBackendDisconnect, enabled = backendReady) {
                            Text("Sign out")
                        }
                    }
                    state.backendSettings.lastError?.let { Text(it, color = ResolveColors.Danger, fontSize = 12.sp) }
                }
            }
        }
        item {
            OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = ResolveColors.Surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = ResolveColors.Accent)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Feishu Calendar", color = ResolveColors.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                            Text(feishuStatusLabel(state.feishuSettings), color = ResolveColors.Secondary)
                        }
                    }
                    Text(
                        if (backendReady) "Backend will hold the Feishu token and keep calendar sync available to Mac and Android."
                        else if (state.feishuSettings.status == FeishuStatus.Connected) "Calendar changes sync in the background and on manual refresh."
                        else "Add your custom app credentials once, then connect with Feishu.",
                        color = ResolveColors.Secondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        Button(onClick = onBackendFeishuConnect, enabled = !isConnecting, colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent)) {
                            Icon(Icons.Filled.OpenInBrowser, contentDescription = null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isConnecting) "Waiting" else if (backendReady) "Connect via Backend" else "Connect")
                        }
                        Button(
                            onClick = onSync,
                            enabled = (state.backendSettings.feishuConnected ||
                                state.feishuSettings.status == FeishuStatus.Connected) && !isSyncing
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isSyncing) "Syncing" else "Sync")
                        }
                        TextButton(onClick = onDisconnect) {
                            Icon(Icons.Filled.Close, contentDescription = null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Disconnect")
                        }
                    }
                    HorizontalDivider(color = ResolveColors.Line)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onShowAdvanced)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (showAdvanced) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ResolveColors.Muted)
                        Text("Custom App Configuration", color = ResolveColors.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(if (appId.isNotBlank() && appSecret.isNotBlank()) "Configured" else "Required", color = ResolveColors.Muted, fontSize = 12.sp)
                    }
                    AnimatedVisibility(showAdvanced) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = appId,
                                onValueChange = {
                                    appId = it.trim()
                                    onSettings(state.feishuSettings.copy(appId = appId))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                                placeholder = { Text("Feishu App ID") },
                                colors = inputColors()
                            )
                            OutlinedTextField(
                                value = appSecret,
                                onValueChange = {
                                    appSecret = it.trim()
                                    secureVault.saveFeishuSecret(appSecret)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                                placeholder = { Text("Feishu App Secret") },
                                colors = inputColors()
                            )
                            MetaPill(AndroidFeishuRedirectUri)
                            BackendClient.defaultFeishuRedirectUri(state.backendSettings)?.let {
                                MetaPill(it)
                            }
                            Text(
                                if (backendReady) "Use the Supabase callback URI in Feishu console. The 127 callback is only for local fallback."
                                else "This callback is for local fallback. Backend mode uses the Supabase callback URI above after you add Supabase URL.",
                                color = ResolveColors.Muted,
                                fontSize = 12.sp
                            )
                        }
                    }
                    state.feishuSettings.lastError?.let { Text(it, color = ResolveColors.Danger, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun TodoDetailSheet(
    item: ResolveItem,
    threads: List<StrategyThread>,
    onClose: () -> Unit,
    onUpdate: (ResolveItem) -> Unit,
    onArchive: (ResolveItem) -> Unit,
    onCalendar: (ResolveItem) -> Unit
) {
    val context = LocalContext.current
    var title by remember(item.id) { mutableStateOf(item.title) }
    var notes by remember(item.id) { mutableStateOf(item.notes) }
    var expanded by remember { mutableStateOf(false) }
    var strategyThreadId by remember(item.id) { mutableStateOf(item.strategyThreadId) }
    var dueAt by remember(item.id) { mutableStateOf(item.dueAt) }
    val dueDate = dueAt?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: LocalDate.now()
    val dueTime = dueAt?.atZone(ZoneId.systemDefault())?.toLocalTime()?.withSecond(0)?.withNano(0) ?: LocalTime.of(9, 0)

    fun currentItem() = item.copy(title = title.trim(), notes = notes.trim(), strategyThreadId = strategyThreadId, dueAt = dueAt)

    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Todo Detail", color = ResolveColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }
        OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Title") }, colors = inputColors())
        OutlinedTextField(value = notes, onValueChange = { notes = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("Comment") }, colors = inputColors())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {
                    DatePickerDialog(context, { _, year, month, day ->
                        dueAt = LocalDate.of(year, month + 1, day).atTime(dueTime).atZone(ZoneId.systemDefault()).toInstant()
                    }, dueDate.year, dueDate.monthValue - 1, dueDate.dayOfMonth).show()
                },
                label = { Text(dueAt?.let { dateLabel(it) } ?: "Set date") }
            )
            AssistChip(
                onClick = {
                    TimePickerDialog(context, { _, hour, minute ->
                        dueAt = dueDate.atTime(LocalTime.of(hour, minute)).atZone(ZoneId.systemDefault()).toInstant()
                    }, dueTime.hour, dueTime.minute, true).show()
                },
                label = { Text(dueTime.format(DateTimeFormatter.ofPattern("HH:mm"))) }
            )
        }
        Box {
            AssistChip(onClick = { expanded = true }, label = { Text(threads.find { it.id == strategyThreadId }?.title ?: "No strategy") })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("No strategy") }, onClick = { strategyThreadId = null; expanded = false })
                threads.forEach { thread ->
                    DropdownMenuItem(text = { Text(thread.title) }, onClick = { strategyThreadId = thread.id; expanded = false })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Button(onClick = { onUpdate(currentItem()); onClose() }, colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent)) {
                Text("Save")
            }
            Button(onClick = { onCalendar(currentItem()) }) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Calendar")
            }
            TextButton(onClick = { onArchive(currentItem()) }) {
                Icon(Icons.Filled.Archive, contentDescription = null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Archive")
            }
        }
    }
}

@Composable
private fun BottomTabs(tab: Tab, onTab: (Tab) -> Unit) {
    NavigationBar(containerColor = ResolveColors.Surface) {
        listOf(Tab.Todo, Tab.Calendar, Tab.Strategy, Tab.Settings).forEach { item ->
            NavigationBarItem(
                selected = tab == item,
                onClick = { onTab(item) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) }
            )
        }
    }
}

@Composable
private fun SectionLabel(title: String, count: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, color = ResolveColors.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(count, color = ResolveColors.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun CollapsedHeader(title: String, count: Int, open: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ResolveColors.Muted)
        Text(title, color = ResolveColors.Secondary, modifier = Modifier.weight(1f))
        Text(count.toString(), color = ResolveColors.Muted)
    }
}

@Composable
private fun MetaPill(label: String) {
    Surface(color = ResolveColors.Pill, shape = RoundedCornerShape(999.dp)) {
        Text(label, color = ResolveColors.Secondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), maxLines = 1)
    }
}

@Composable
private fun ToastChip(message: String, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            color = Color(0xE61D1D1F),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier
                .padding(bottom = 94.dp)
                .clickable(onClick = onDismiss)
        ) {
            Text(message, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun ResolveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            background = ResolveColors.Bg,
            surface = ResolveColors.Surface,
            primary = ResolveColors.Accent
        ),
        content = content
    )
}

@Composable
private fun inputColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedTextColor = ResolveColors.Text,
    unfocusedTextColor = ResolveColors.Text,
    focusedIndicatorColor = ResolveColors.Accent,
    unfocusedIndicatorColor = ResolveColors.Line,
    focusedPlaceholderColor = ResolveColors.Muted,
    unfocusedPlaceholderColor = ResolveColors.Muted
)

private fun feishuStatusLabel(settings: FeishuSettings): String = when (settings.status) {
    FeishuStatus.Connected -> settings.lastSyncedAt?.let { "Feishu ${relativeTime(it)}" } ?: "Feishu connected"
    FeishuStatus.PermissionError -> "Feishu needs attention"
    FeishuStatus.TokenExpired -> "Feishu expired"
    FeishuStatus.NotConnected -> "Feishu offline"
}

private fun backendStatusLabel(settings: BackendSettings): String = when (settings.status) {
    BackendStatus.Connected -> settings.lastSyncedAt?.let { "Backend ${relativeTime(it)}" } ?: "Backend connected"
    BackendStatus.Error -> "Backend needs attention"
    BackendStatus.SignedOut -> "Backend signed out"
    BackendStatus.NotConfigured -> "Backend not configured"
}

private fun calendarStatusLabel(feishu: FeishuSettings, backend: BackendSettings): String =
    if (backend.status == BackendStatus.Connected) backendStatusLabel(backend) else feishuStatusLabel(feishu)

private fun eventTimeLabel(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun dateLabel(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

private fun relativeTime(instant: Instant): String {
    val minutes = ((System.currentTimeMillis() - instant.toEpochMilli()) / 60_000).coerceAtLeast(0)
    return if (minutes < 1) "just now" else "${minutes}m ago"
}

private object ResolveColors {
    val Bg = Color(0xFFF5F5F7)
    val Surface = Color(0xFFFFFFFF)
    val Pill = Color(0xFFF0F2F5)
    val Line = Color(0xFFE0E3EA)
    val Text = Color(0xFF1D1D1F)
    val Secondary = Color(0xFF555963)
    val Muted = Color(0xFF8B909A)
    val Accent = Color(0xFF2F64D9)
    val Danger = Color(0xFFC7362F)
}
