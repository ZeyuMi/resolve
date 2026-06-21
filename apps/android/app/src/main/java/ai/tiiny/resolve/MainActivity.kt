package ai.tiiny.resolve

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private var latestIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncWorker.schedule(this)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        latestIntent = intent
        setContent {
            ResolveAndroidApp(
                initialCapture = sharedText,
                latestIntent = latestIntent,
                onIntentHandled = { latestIntent = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        latestIntent = intent
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
private fun ResolveAndroidApp(
    initialCapture: String,
    latestIntent: Intent?,
    onIntentHandled: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { ResolveRepository(context) }
    val secureVault = remember { SecureVault(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(repository.load()) }
    var tab by remember { mutableStateOf(Tab.Todo) }
    var capture by remember { mutableStateOf(initialCapture) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedTodoId by remember { mutableStateOf<String?>(null) }
    var selectedThreadId by remember { mutableStateOf(state.threads.firstOrNull()?.id.orEmpty()) }
    var calendarDraft by remember { mutableStateOf(CalendarDraft()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var calendarViewMode by remember { mutableStateOf(CalendarViewMode.Month) }
    var selectedCalendarEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    var editingCalendarEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    var expandedCalendarDate by remember { mutableStateOf<LocalDate?>(null) }
    var showCalendarDraft by remember { mutableStateOf(false) }
    var showCompleted by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var hasBackendSession by remember { mutableStateOf(secureVault.loadBackendSession() != null) }
    var attemptedBackendCalendarAuth by remember { mutableStateOf(false) }

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
        notice = null
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
                    notice = null
                } else {
                    notice = null
                }
            } catch (error: Throwable) {
                if (error.needsCalendarAuthorization()) {
                    persist(
                        state.copy(
                            backendSettings = state.backendSettings.copy(
                                status = BackendStatus.Connected,
                                feishuConnected = false,
                                lastError = "Calendar needs attention"
                            ),
                            feishuSettings = state.feishuSettings.copy(
                                status = FeishuStatus.NotConnected,
                                lastError = "Calendar needs attention"
                            )
                        )
                    )
                    notice = null
                } else {
                    if (hasBackendSession) {
                        patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                    }
                    patchFeishu(state.feishuSettings.copy(status = FeishuStatus.PermissionError, lastError = error.message))
                    notice = "Calendar sync failed${error.message?.let { ": $it" }.orEmpty()}"
                }
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
        notice = null

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
                    notice = null
                } catch (error: Throwable) {
                    if (error.needsCalendarAuthorization()) {
                        persist(
                            state.copy(
                                backendSettings = state.backendSettings.copy(
                                    status = BackendStatus.Connected,
                                    feishuConnected = false,
                                    lastError = "Calendar needs attention"
                                ),
                                feishuSettings = state.feishuSettings.copy(
                                    status = FeishuStatus.NotConnected,
                                    lastError = "Calendar needs attention"
                                )
                            )
                        )
                        notice = null
                    } else {
                        patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                        notice = "Saved locally; sync failed${error.message?.let { ": $it" }.orEmpty()}"
                    }
                }
            }
            return
        }

        notice = null
    }

    fun updateCalendarEvent(event: CalendarEvent, draft: CalendarDraft) {
        if (draft.title.isBlank()) return
        val startsAt = draft.date.atTime(draft.time).atZone(ZoneId.systemDefault()).toInstant()
        val durationSeconds = event.endsAt?.epochSecond?.minus(event.startsAt.epochSecond)?.coerceAtLeast(0) ?: 3600
        val optimistic = event.copy(
            title = draft.title.trim(),
            description = draft.description.trim(),
            startsAt = startsAt,
            endsAt = startsAt.plusSeconds(durationSeconds),
            status = if (event.provider == "feishu" && event.canEdit) "local_pending_update" else event.status
        )
        persist(
            state.copy(calendarEvents = replaceCalendarEvent(state.calendarEvents, event, optimistic))
        )
        selectedCalendarEvent = optimistic
        editingCalendarEvent = null
        selectedDate = optimistic.startsAt.atZone(ZoneId.systemDefault()).toLocalDate()
        expandedCalendarDate = selectedDate
        notice = null

        if (event.provider != "feishu" || !event.canEdit || event.externalEventId.isNullOrBlank()) return

        if (hasBackendSession && state.backendSettings.feishuConnected) {
            scope.launch {
                try {
                    val client = connectedBackendClient()
                    val remote = withContext(Dispatchers.IO) { client.updateEvent(event, draft) }
                    persist(
                        state.copy(
                            calendarEvents = replaceCalendarEvent(state.calendarEvents, event, remote),
                            backendSettings = state.backendSettings.copy(lastSyncedAt = Instant.now(), lastError = null),
                            feishuSettings = state.feishuSettings.copy(status = FeishuStatus.Connected, lastSyncedAt = Instant.now(), lastError = null)
                        )
                    )
                    selectedCalendarEvent = remote
                } catch (error: Throwable) {
                    if (error.needsCalendarAuthorization()) {
                        persist(
                            state.copy(
                                backendSettings = state.backendSettings.copy(
                                    status = BackendStatus.Connected,
                                    feishuConnected = false,
                                    lastError = "Calendar needs attention"
                                ),
                                feishuSettings = state.feishuSettings.copy(
                                    status = FeishuStatus.NotConnected,
                                    lastError = "Calendar needs attention"
                                )
                            )
                        )
                        notice = null
                    } else {
                        notice = "Saved locally; Feishu update failed${error.message?.let { ": $it" }.orEmpty()}"
                        patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                    }
                }
            }
            return
        }

        notice = null
    }

    fun startBackendFeishuAuth() {
        if (!hasBackendSession) {
            notice = "Sign in first"
            return
        }
        isConnecting = true
        attemptedBackendCalendarAuth = true
        scope.launch {
            try {
                val client = connectedBackendClient()
                val oauth = withContext(Dispatchers.IO) { client.startFeishuOAuth() }
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(oauth.authorizeUrl)))
                patchBackend(state.backendSettings.copy(status = BackendStatus.Connected, feishuConnected = false, lastError = null))
                patchFeishu(state.feishuSettings.copy(status = FeishuStatus.NotConnected, lastError = null))
                notice = null

                repeat(30) {
                    delay(3_000)
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
                        tab = Tab.Calendar
                        notice = null
                        syncFeishu()
                        return@launch
                    }
                }
                notice = null
            } catch (error: Throwable) {
                patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                notice = "Calendar authorization failed${error.message?.let { ": $it" }.orEmpty()}"
            } finally {
                isConnecting = false
            }
        }
    }

    fun signInBackend(password: String) {
        if (isConnecting) return
        isConnecting = true
        scope.launch {
            var startFeishuAuthAfterSignIn = false
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
                val calendarConnected = connectorStatus?.connected == true
                val calendarNeedsAuth = connectorStatus?.status == "needs_auth"
                persist(
                    state.copy(
                        backendSettings = state.backendSettings.copy(
                            status = BackendStatus.Connected,
                            feishuConnected = calendarConnected,
                            lastSyncedAt = connectorStatus?.lastServerSyncAt ?: state.backendSettings.lastSyncedAt,
                            lastError = if (calendarNeedsAuth) "Calendar needs attention" else null
                        ),
                        feishuSettings = state.feishuSettings.copy(
                            status = if (calendarConnected) FeishuStatus.Connected else FeishuStatus.NotConnected,
                            lastSyncedAt = connectorStatus?.lastServerSyncAt ?: state.feishuSettings.lastSyncedAt,
                            lastError = if (calendarNeedsAuth) "Calendar needs attention" else null
                        )
                    )
                )
                notice = null
                if (calendarConnected) syncFeishu()
                else if (connectorStatus?.configured == true) startFeishuAuthAfterSignIn = true
            } catch (error: Throwable) {
                patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                notice = "Sign in failed${error.message?.let { ": $it" }.orEmpty()}"
            } finally {
                isConnecting = false
                if (startFeishuAuthAfterSignIn) startBackendFeishuAuth()
            }
        }
    }

    fun connectBackendFeishu() {
        startBackendFeishuAuth()
    }

    fun disconnectBackend() {
        secureVault.clearBackendSession()
        hasBackendSession = false
        attemptedBackendCalendarAuth = false
        patchBackend(state.backendSettings.copy(status = BackendStatus.SignedOut, feishuConnected = false, lastError = null))
        notice = null
    }

    LaunchedEffect(hasBackendSession) {
        if (hasBackendSession) {
            runCatching {
                val client = connectedBackendClient()
                val status = withContext(Dispatchers.IO) { client.status() }
                val calendarNeedsAuth = status.status == "needs_auth"
                persist(
                    state.copy(
                        backendSettings = state.backendSettings.copy(
                            status = BackendStatus.Connected,
                            feishuConnected = status.connected,
                            lastSyncedAt = status.lastServerSyncAt ?: state.backendSettings.lastSyncedAt,
                            lastError = if (calendarNeedsAuth) "Calendar needs attention" else null
                        ),
                        feishuSettings = state.feishuSettings.copy(
                            status = if (status.connected) FeishuStatus.Connected else FeishuStatus.NotConnected,
                            lastSyncedAt = status.lastServerSyncAt ?: state.feishuSettings.lastSyncedAt,
                            lastError = if (calendarNeedsAuth) "Calendar needs attention" else null
                        )
                    )
                )
                if (status.connected) {
                    syncFeishu()
                } else if (status.configured && !attemptedBackendCalendarAuth && !isConnecting) {
                    startBackendFeishuAuth()
                }
            }.onFailure { error ->
                patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
            }
        }
    }

    LaunchedEffect(latestIntent) {
        val uri = latestIntent?.data
        if (uri?.scheme == "resolve" && uri.host == "oauth" && uri.path == "/feishu") {
            tab = Tab.Calendar
            notice = null
            onIntentHandled()
            if (hasBackendSession) {
                scope.launch {
                    isSyncing = true
                    try {
                        val client = connectedBackendClient()
                        val status = withContext(Dispatchers.IO) { client.status() }
                        val calendarNeedsAuth = status.status == "needs_auth"
                        persist(
                            state.copy(
                                backendSettings = state.backendSettings.copy(
                                    status = BackendStatus.Connected,
                                    feishuConnected = status.connected,
                                    lastSyncedAt = status.lastServerSyncAt ?: state.backendSettings.lastSyncedAt,
                                    lastError = if (calendarNeedsAuth) "Calendar needs attention" else null
                                ),
                                feishuSettings = state.feishuSettings.copy(
                                    status = if (status.connected) FeishuStatus.Connected else FeishuStatus.NotConnected,
                                    lastSyncedAt = status.lastServerSyncAt ?: state.feishuSettings.lastSyncedAt,
                                    lastError = if (calendarNeedsAuth) "Calendar needs attention" else null
                                )
                            )
                        )
                        if (status.connected) {
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
                            notice = null
                        }
                    } catch (error: Throwable) {
                        if (error.needsCalendarAuthorization()) {
                            persist(
                                state.copy(
                                    backendSettings = state.backendSettings.copy(
                                        status = BackendStatus.Connected,
                                        feishuConnected = false,
                                        lastError = "Calendar needs attention"
                                    ),
                                    feishuSettings = state.feishuSettings.copy(
                                        status = FeishuStatus.NotConnected,
                                        lastError = "Calendar needs attention"
                                    )
                                )
                            )
                            notice = null
                        } else {
                            patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                            notice = "Calendar sync failed${error.message?.let { ": $it" }.orEmpty()}"
                        }
                    } finally {
                        isSyncing = false
                    }
                }
            }
        }
    }

    LaunchedEffect(tab, hasBackendSession, state.backendSettings.feishuConnected) {
        if (tab == Tab.Calendar && hasBackendSession && state.backendSettings.feishuConnected) {
            syncFeishu()
        }
    }

    ResolveTheme {
        Scaffold(
            containerColor = ResolveColors.Bg,
            bottomBar = { BottomTabs(tab = tab, onTab = { tab = it }) },
            floatingActionButton = {
                if (tab == Tab.Calendar && selectedCalendarEvent == null && editingCalendarEvent == null && !showCalendarDraft) {
                    FloatingActionButton(
                        onClick = {
                            calendarDraft = CalendarDraft(date = selectedDate, time = LocalTime.of(9, 0))
                            selectedCalendarEvent = null
                            expandedCalendarDate = selectedDate
                            showCalendarDraft = true
                        },
                        containerColor = ResolveColors.Accent,
                        contentColor = Color.White,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add event", modifier = Modifier.size(30.dp))
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(
                        horizontal = if (tab == Tab.Calendar) 4.dp else 16.dp,
                        vertical = if (tab == Tab.Calendar) 2.dp else 12.dp
                    )
            ) {
                TopHeader(
                    title = tab.label,
                    settings = state.feishuSettings,
                    backend = state.backendSettings,
                    isSyncing = isSyncing,
                    compact = tab == Tab.Calendar,
                    onSettings = { tab = Tab.Settings }
                )
                Spacer(Modifier.height(if (tab == Tab.Calendar) 3.dp else 10.dp))
                notice?.let {
                    InlineNotice(message = it, onDismiss = { notice = null })
                    Spacer(Modifier.height(if (tab == Tab.Calendar) 4.dp else 10.dp))
                }
                if (tab == Tab.Todo) {
                    CaptureBox(value = capture, onChange = { capture = it }, onSave = { saveCapture() })
                    Spacer(Modifier.height(12.dp))
                }
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
                            selectedDate = selectedDate,
                            expandedDate = expandedCalendarDate,
                            selectedEvent = selectedCalendarEvent,
                            editingEvent = editingCalendarEvent,
                            draft = calendarDraft.takeIf { showCalendarDraft },
                            viewMode = calendarViewMode,
                            onDate = {
                                selectedDate = it
                                calendarDraft = calendarDraft.copy(date = it)
                                expandedCalendarDate = null
                            },
                            onViewMode = { calendarViewMode = it },
                            onSelectEvent = {
                                selectedDate = it.startsAt.atZone(ZoneId.systemDefault()).toLocalDate()
                                selectedCalendarEvent = it
                                editingCalendarEvent = null
                                showCalendarDraft = false
                            },
                            onCloseEvent = {
                                selectedCalendarEvent = null
                                editingCalendarEvent = null
                            },
                            onEditEvent = {
                                editingCalendarEvent = it
                                selectedCalendarEvent = null
                            },
                            onCloseEdit = {
                                selectedCalendarEvent = editingCalendarEvent
                                editingCalendarEvent = null
                            },
                            onUpdateEvent = { event, draft ->
                                updateCalendarEvent(event, draft)
                            },
                            onDraft = { calendarDraft = it },
                            onCloseDraft = { showCalendarDraft = false },
                            onCreateDraft = {
                                createCalendarEvent(it)
                                selectedDate = it.date
                                expandedCalendarDate = it.date
                                showCalendarDraft = false
                            },
                            onExpandDay = {
                                selectedDate = it
                                expandedCalendarDate = if (expandedCalendarDate == it) null else it
                                selectedCalendarEvent = null
                                editingCalendarEvent = null
                                showCalendarDraft = false
                            }
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
                            isConnecting = isConnecting,
                            isSyncing = isSyncing,
                            onBackendSettings = { patchBackend(it) },
                            onBackendSignIn = { signInBackend(it) },
                            onBackendDisconnect = { disconnectBackend() },
                            onBackendFeishuConnect = { connectBackendFeishu() }
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
                        expandedCalendarDate = calendarDraft.date
                        selectedTodoId = null
                        tab = Tab.Calendar
                        showCalendarDraft = true
                    }
                )
            }
        }

    }
}

@Composable
private fun TopHeader(
    title: String,
    settings: FeishuSettings,
    backend: BackendSettings,
    isSyncing: Boolean,
    compact: Boolean = false,
    onSettings: () -> Unit
) {
    if (compact) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(title, color = ResolveColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Surface(color = Color.Transparent, shape = RoundedCornerShape(999.dp)) {
                Text(
                    if (isSyncing) "Syncing" else calendarStatusLabel(settings, backend),
                    color = ResolveColors.Muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Surface(
                color = Color.Transparent,
                shape = CircleShape,
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onSettings)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = ResolveColors.Secondary, modifier = Modifier.size(19.dp))
                }
            }
        }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Resolve", color = ResolveColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(title, color = ResolveColors.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        }
        Surface(color = ResolveColors.Pill, shape = RoundedCornerShape(999.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(15.dp), tint = ResolveColors.Muted)
                Spacer(Modifier.width(5.dp))
                Text(if (isSyncing) "Syncing" else calendarStatusLabel(settings, backend), color = ResolveColors.Secondary, fontSize = 12.sp)
            }
        }
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
    selectedDate: LocalDate,
    expandedDate: LocalDate?,
    selectedEvent: CalendarEvent?,
    editingEvent: CalendarEvent?,
    draft: CalendarDraft?,
    viewMode: CalendarViewMode,
    onDate: (LocalDate) -> Unit,
    onViewMode: (CalendarViewMode) -> Unit,
    onSelectEvent: (CalendarEvent) -> Unit,
    onCloseEvent: () -> Unit,
    onEditEvent: (CalendarEvent) -> Unit,
    onCloseEdit: () -> Unit,
    onUpdateEvent: (CalendarEvent, CalendarDraft) -> Unit,
    onDraft: (CalendarDraft) -> Unit,
    onCloseDraft: () -> Unit,
    onCreateDraft: (CalendarDraft) -> Unit,
    onExpandDay: (LocalDate) -> Unit
) {
    if (selectedEvent != null) {
        CalendarEventDetailPage(event = selectedEvent, onClose = onCloseEvent, onEdit = { onEditEvent(selectedEvent) })
        return
    }
    if (editingEvent != null) {
        CalendarEditPage(
            event = editingEvent,
            onClose = onCloseEdit,
            onSave = { draft -> onUpdateEvent(editingEvent, draft) }
        )
        return
    }
    if (draft != null) {
        CalendarDraftPage(
            draft = draft,
            onDraft = onDraft,
            onCreate = onCreateDraft,
            onClose = onCloseDraft
        )
        return
    }

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val normalizedViewMode = if (viewMode == CalendarViewMode.Day) CalendarViewMode.Week else viewMode
    val weekDays = (0..6).map { weekStart(selectedDate).plusDays(it.toLong()) }
    val monthDays = monthGridDates(selectedDate)
    val monthWeeks = monthDays.chunked(7).take(6)
    val rangeStartDate = if (normalizedViewMode == CalendarViewMode.Week) weekDays.first() else monthWeeks.flatten().first()
    val rangeEndDate = if (normalizedViewMode == CalendarViewMode.Week) weekDays.last().plusDays(1) else monthWeeks.flatten().last().plusDays(1)
    val rangeStart = rangeStartDate.atStartOfDay(zone).toInstant()
    val rangeEnd = rangeEndDate.atStartOfDay(zone).toInstant()
    val displayEvents = expandRecurringCalendarEvents(
        events = state.calendarEvents.filter { calendarEventVisible(it) },
        rangeStart = rangeStart,
        rangeEnd = rangeEnd,
        zone = zone
    )
    val eventsByDate = displayEvents
        .groupBy { it.startsAt.atZone(zone).toLocalDate() }
        .mapValues { (_, events) -> events.sortedBy { it.startsAt } }
    val inlineExpandedDate = expandedDate?.takeIf { date ->
        (normalizedViewMode == CalendarViewMode.Week && date in weekDays) ||
            (normalizedViewMode == CalendarViewMode.Month && monthWeeks.any { date in it })
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        CalendarToolbar(
            selectedDate = selectedDate,
            viewMode = normalizedViewMode,
            onDate = onDate,
            onViewMode = onViewMode
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(18.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                Text(
                    it,
                    style = TextStyle(
                        color = ResolveColors.Muted,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val availableRowHeight = ((maxHeight - 5.dp) / 6f).coerceIn(58.dp, 126.dp)
            val monthSlots = when {
                availableRowHeight < 72.dp -> 3
                availableRowHeight < 94.dp -> 4
                availableRowHeight < 112.dp -> 5
                else -> 6
            }
            val weekRowHeight = if (inlineExpandedDate == null) {
                maxHeight
            } else {
                (maxHeight * 0.46f).coerceIn(180.dp, 280.dp)
            }
            val weekSlots = when {
                weekRowHeight < 190.dp -> 10
                weekRowHeight < 250.dp -> 14
                else -> 24
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                if (normalizedViewMode == CalendarViewMode.Week) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(weekRowHeight),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        weekDays.forEach { date ->
                            CalendarGridCell(
                                date = date,
                                events = eventsByDate[date].orEmpty(),
                                selected = date == selectedDate,
                                isToday = date == today,
                                isPast = date < today,
                                slots = weekSlots,
                                modifier = Modifier.weight(1f).fillMaxSize(),
                                onSelectDate = { onExpandDay(date) },
                                onExpandDay = { onExpandDay(date) }
                            )
                        }
                    }
                    inlineExpandedDate?.let { date ->
                        ExpandedDayInlinePanel(
                            date = date,
                            events = eventsByDate[date].orEmpty(),
                            onSelectEvent = onSelectEvent
                        )
                    }
                } else {
                    monthWeeks.forEach { week ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(availableRowHeight),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            week.forEach { date ->
                                CalendarGridCell(
                                    date = date,
                                    events = eventsByDate[date].orEmpty(),
                                    selected = date == selectedDate,
                                    isToday = date == today,
                                    isPast = date < today,
                                    slots = monthSlots,
                                    modifier = Modifier.weight(1f).fillMaxSize(),
                                    onSelectDate = { onExpandDay(date) },
                                    onExpandDay = { onExpandDay(date) }
                                )
                            }
                        }
                        if (inlineExpandedDate != null && inlineExpandedDate in week) {
                            ExpandedDayInlinePanel(
                                date = inlineExpandedDate,
                                events = eventsByDate[inlineExpandedDate].orEmpty(),
                                onSelectEvent = onSelectEvent
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarToolbar(
    selectedDate: LocalDate,
    viewMode: CalendarViewMode,
    onDate: (LocalDate) -> Unit,
    onViewMode: (CalendarViewMode) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
    ) {
        Surface(
            color = Color.Transparent,
            shape = CircleShape,
            modifier = Modifier
                .size(26.dp)
                .clickable { onDate(shiftCalendarDate(selectedDate, viewMode, -1)) }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Previous",
                    tint = ResolveColors.Secondary,
                    modifier = Modifier.rotate(180f).size(19.dp)
                )
            }
        }
        Text(
            calendarRangeTitle(selectedDate, viewMode),
            color = ResolveColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        Box {
            Surface(
                color = ResolveColors.Pill,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .height(24.dp)
                    .clickable { menuOpen = true }
            ) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(calendarViewModeLabel(viewMode), color = ResolveColors.Secondary, fontSize = 11.sp)
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = ResolveColors.Muted, modifier = Modifier.size(14.dp))
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                listOf(CalendarViewMode.Week, CalendarViewMode.Month).forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(calendarViewModeLabel(mode)) },
                        onClick = {
                            onViewMode(mode)
                            menuOpen = false
                        }
                    )
                }
            }
        }
        Surface(
            color = Color.Transparent,
            shape = CircleShape,
            modifier = Modifier
                .size(26.dp)
                .clickable { onDate(shiftCalendarDate(selectedDate, viewMode, 1)) }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next", tint = ResolveColors.Secondary, modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun CalendarNavigator(
    selectedDate: LocalDate,
    viewMode: CalendarViewMode,
    onDate: (LocalDate) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = { onDate(shiftCalendarDate(selectedDate, viewMode, -1)) }) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Previous",
                tint = ResolveColors.Secondary,
                modifier = Modifier.rotate(180f)
            )
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(calendarRangeTitle(selectedDate, viewMode), color = ResolveColors.Text, fontWeight = FontWeight.SemiBold)
            Text("Today ${LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日"))}", color = ResolveColors.Muted, fontSize = 12.sp)
        }
        IconButton(onClick = { onDate(shiftCalendarDate(selectedDate, viewMode, 1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next", tint = ResolveColors.Secondary)
        }
    }
}

@Composable
private fun CalendarGridCell(
    date: LocalDate,
    events: List<CalendarEvent>,
    selected: Boolean,
    isToday: Boolean,
    isPast: Boolean,
    slots: Int,
    modifier: Modifier = Modifier,
    onSelectDate: () -> Unit,
    onExpandDay: () -> Unit
) {
    val visibleCount = events.size.coerceAtMost(slots)
    val hiddenCount = events.size - visibleCount
    val color = when {
        isToday -> Color(0xFFEAF2FF)
        selected -> Color(0xFFF5F9FF)
        else -> Color.Transparent
    }
    val cellShape = RoundedCornerShape(3.dp)
    Surface(
        color = color,
        shape = cellShape,
        modifier = modifier
            .border(
                width = if (isToday || selected) 1.dp else 0.dp,
                color = when {
                    isToday -> Color(0xFF8CB7FF)
                    selected -> Color(0xFFC7DAFF)
                    else -> Color.Transparent
                },
                shape = cellShape
            )
            .clip(cellShape)
            .clickable {
                onSelectDate()
            }
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 1.dp, vertical = 1.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(
                modifier = Modifier.height(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = when {
                        isToday -> ResolveColors.Accent
                        selected -> Color(0xFFDCE9FF)
                        else -> Color.Transparent
                    },
                    shape = CircleShape
                ) {
                    Text(
                        date.dayOfMonth.toString(),
                        style = TextStyle(
                            color = when {
                                isToday -> Color.White
                                isPast -> Color(0xFFADB4C0)
                                selected -> ResolveColors.Accent
                                else -> ResolveColors.Text
                            },
                            fontSize = 11.sp,
                            lineHeight = 12.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        fontWeight = if (selected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = if (isToday || selected) 4.dp else 1.dp, vertical = 1.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (hiddenCount > 0) {
                    Text(
                        "+$hiddenCount",
                        style = TextStyle(
                            color = ResolveColors.Muted,
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFFE8EBF0))
                            .clickable(onClick = onExpandDay)
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                    )
                }
            }
            events.take(visibleCount).forEach { event ->
                CalendarEventChip(event = event, isPast = isPast, onClick = onExpandDay)
            }
        }
    }
}

@Composable
private fun CalendarEventChip(event: CalendarEvent, isPast: Boolean, onClick: () -> Unit) {
    Surface(
        color = when {
            isPast -> Color(0xFFE9ECF1)
            event.provider == "feishu" -> Color(0xFFDCE9FF)
            else -> Color(0xFFE7ECF5)
        },
        shape = RoundedCornerShape(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.height(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(15.dp)
                    .background(if (isPast) Color(0xFFB7C0CC) else ResolveColors.Accent)
            )
            Text(
                event.title,
                color = if (isPast) Color(0xFF7A8493) else ResolveColors.Text,
                style = TextStyle(
                    color = if (isPast) Color(0xFF7A8493) else ResolveColors.Text,
                    fontSize = 9.5.sp,
                    lineHeight = 10.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp, end = 2.dp)
            )
        }
    }
}

@Composable
private fun ExpandedDayInlinePanel(
    date: LocalDate,
    events: List<CalendarEvent>,
    onSelectEvent: (CalendarEvent) -> Unit
) {
    Surface(
        color = Color(0xFFF0F2F5),
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    date.format(DateTimeFormatter.ofPattern("M月d日")),
                    color = ResolveColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text("${events.size} events", color = ResolveColors.Muted, fontSize = 11.sp)
            }
            if (events.isEmpty()) {
                Text("这一天还没有日程", color = ResolveColors.Muted, fontSize = 12.sp)
            } else {
                events.forEach { event ->
                    CalendarExpandedEventRow(event = event, onClick = { onSelectEvent(event) })
                }
            }
        }
    }
}

@Composable
private fun CalendarExpandedEventRow(event: CalendarEvent, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ResolveColors.Accent)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                event.title,
                color = ResolveColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                calendarExpandedEventMeta(event),
                color = ResolveColors.Secondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CalendarEventDetailPage(event: CalendarEvent, onClose: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    Surface(color = ResolveColors.Bg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = ResolveColors.Pill,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(34.dp)
                        .clickable(onClick = onClose)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Back",
                            tint = ResolveColors.Secondary,
                            modifier = Modifier.rotate(180f).size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text("Event", color = ResolveColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                MetaPill(if (event.status == "readonly" || !event.canEdit) "Readonly" else event.provider)
            }

            Surface(color = ResolveColors.Surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(event.title, color = ResolveColors.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    CalendarEventInfoRow(label = "Time", value = calendarEventDateTimeLabel(event))
                    if (event.description.isNotBlank()) {
                        CalendarEventInfoRow(label = "Comment", value = event.description)
                    } else {
                        CalendarEventInfoRow(label = "Comment", value = "No comment")
                    }
                    CalendarEventInfoRow(
                        label = "Source",
                        value = if (event.status == "readonly" || !event.canEdit) "Readonly from Feishu" else "${event.provider} · ${event.status}"
                    )
                    event.meetingUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        Button(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEAF2FF), contentColor = ResolveColors.Accent),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open meeting link")
                        }
                    }
                    Button(
                        onClick = onEdit,
                        enabled = event.provider != "feishu" || event.canEdit,
                        colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (event.provider == "feishu" && !event.canEdit) "Readonly" else "Edit")
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarEditPage(
    event: CalendarEvent,
    onClose: () -> Unit,
    onSave: (CalendarDraft) -> Unit
) {
    val zone = ZoneId.systemDefault()
    var draft by remember(event.id, event.startsAt) {
        val start = event.startsAt.atZone(zone)
        mutableStateOf(
            CalendarDraft(
                title = event.title,
                date = start.toLocalDate(),
                time = start.toLocalTime().withSecond(0).withNano(0),
                description = event.description,
                sourceItemId = event.sourceItemId,
                strategyThreadId = event.strategyThreadId
            )
        )
    }
    CalendarDraftEditorPage(
        title = "Edit Event",
        draft = draft,
        primaryAction = "Save",
        onDraft = { draft = it },
        onSubmit = { onSave(draft) },
        onClose = onClose
    )
}

@Composable
private fun CalendarEventInfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = ResolveColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = ResolveColors.Secondary, fontSize = 14.sp)
    }
}

@Composable
private fun SelectedDayPreview(
    date: LocalDate,
    events: List<CalendarEvent>,
    modifier: Modifier = Modifier,
    onOpenDraft: () -> Unit,
    onSelectEvent: (CalendarEvent) -> Unit,
    onViewAll: () -> Unit
) {
    Surface(
        color = ResolveColors.Surface,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        date.format(DateTimeFormatter.ofPattern("M月d日 EEE")),
                        color = ResolveColors.Text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("${events.size} events", color = ResolveColors.Muted, fontSize = 12.sp)
                }
                TextButton(onClick = onOpenDraft) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("New")
                }
            }
            if (events.isEmpty()) {
                Surface(color = ResolveColors.Pill, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "这一天还没有日程",
                        color = ResolveColors.Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            } else {
                events.take(3).forEach { event ->
                    CalendarSelectedDayEventRow(event = event, onClick = { onSelectEvent(event) })
                }
                if (events.size > 3) {
                    TextButton(onClick = onViewAll, modifier = Modifier.fillMaxWidth()) {
                        Text("View all · 还有 ${events.size - 3} 项")
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarSelectedDayEventRow(event: CalendarEvent, onClick: () -> Unit) {
    Surface(
        color = if (event.provider == "feishu") Color(0xFFF2FAFF) else ResolveColors.Pill,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                eventTimeLabel(event.startsAt),
                color = ResolveColors.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(event.title, color = ResolveColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = if (event.status == "readonly" || !event.canEdit) "Readonly from Feishu" else "${event.provider} · ${event.status}"
                Text(status, color = ResolveColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CalendarDraftPage(
    draft: CalendarDraft,
    onDraft: (CalendarDraft) -> Unit,
    onCreate: (CalendarDraft) -> Unit,
    onClose: () -> Unit
) {
    CalendarDraftEditorPage(
        title = "New Event",
        draft = draft,
        primaryAction = "Create",
        onDraft = onDraft,
        onSubmit = { onCreate(draft) },
        onClose = onClose
    )
}

@Composable
private fun CalendarDraftEditorPage(
    title: String,
    draft: CalendarDraft,
    primaryAction: String,
    onDraft: (CalendarDraft) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    Surface(color = ResolveColors.Bg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = ResolveColors.Pill,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(34.dp)
                        .clickable(onClick = onClose)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Back",
                            tint = ResolveColors.Secondary,
                            modifier = Modifier.rotate(180f).size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = ResolveColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(draft.date.format(DateTimeFormatter.ofPattern("M月d日 EEE")), color = ResolveColors.Muted, fontSize = 12.sp)
                }
            }

            Surface(color = ResolveColors.Surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = { onDraft(draft.copy(title = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("日程标题") },
                        colors = inputColors()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
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
                        minLines = 4,
                        placeholder = { Text("备注，可选") },
                        colors = inputColors()
                    )
                    Button(
                        onClick = onSubmit,
                        enabled = draft.title.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(primaryAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarEventDetailSheet(event: CalendarEvent, onClose: () -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(event.title, color = ResolveColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(calendarEventDateTimeLabel(event), color = ResolveColors.Secondary, fontSize = 13.sp)
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }
        MetaPill(if (event.status == "readonly" || !event.canEdit) "Readonly from Feishu" else "${event.provider} · ${event.status}")
        Surface(color = ResolveColors.Pill, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Comment", color = ResolveColors.Muted, fontSize = 12.sp)
                Text(event.description.ifBlank { "No comment" }, color = ResolveColors.Secondary, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun CalendarDayListSheet(
    date: LocalDate,
    events: List<CalendarEvent>,
    onClose: () -> Unit,
    onSelect: (CalendarEvent) -> Unit
) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(date.format(DateTimeFormatter.ofPattern("M月d日 EEE")), color = ResolveColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("${events.size} events", color = ResolveColors.Muted, fontSize = 13.sp)
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }
        events.forEach { event ->
            CalendarRow(event = event, onClick = { onSelect(event) })
        }
    }
}

@Composable
private fun CalendarRow(event: CalendarEvent, onClick: (() -> Unit)? = null) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        colors = CardDefaults.outlinedCardColors(containerColor = ResolveColors.Surface)
    ) {
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
    isConnecting: Boolean,
    isSyncing: Boolean,
    onBackendSettings: (BackendSettings) -> Unit,
    onBackendSignIn: (String) -> Unit,
    onBackendDisconnect: () -> Unit,
    onBackendFeishuConnect: () -> Unit
) {
    var email by remember(state.backendSettings.email) { mutableStateOf(state.backendSettings.email) }
    var password by remember { mutableStateOf("") }
    val backendReady = state.backendSettings.status == BackendStatus.Connected || state.backendSettings.feishuConnected
    val calendarConnected = state.backendSettings.feishuConnected || state.feishuSettings.status == FeishuStatus.Connected
    val lastSyncedAt = state.backendSettings.lastSyncedAt ?: state.feishuSettings.lastSyncedAt
    val calendarStatus = when {
        isSyncing -> "Updating"
        calendarConnected && lastSyncedAt != null -> "Synced ${relativeTime(lastSyncedAt)}"
        calendarConnected -> "Ready"
        state.backendSettings.lastError != null || state.feishuSettings.lastError != null -> "Needs attention"
        backendReady -> "Authorization needed"
        else -> "Sign in first"
    }
    val calendarError = state.backendSettings.lastError ?: state.feishuSettings.lastError
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = ResolveColors.Surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.TaskAlt, contentDescription = null, tint = ResolveColors.Accent)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Account", color = ResolveColors.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                            Text(backendStatusLabel(state.backendSettings), color = ResolveColors.Secondary)
                        }
                    }
                    if (backendReady) {
                        Surface(
                            color = ResolveColors.Pill,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ResolveColors.Accent, modifier = Modifier.size(19.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Signed in", color = ResolveColors.Text, fontWeight = FontWeight.SemiBold)
                                    Text(state.backendSettings.email.ifBlank { "Resolve account" }, color = ResolveColors.Secondary, fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = email,
                            onValueChange = {
                                email = it.trim()
                                onBackendSettings(state.backendSettings.copy(email = email))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Email") },
                            colors = inputColors()
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            colors = inputColors()
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        if (!backendReady) {
                            Button(
                                onClick = { onBackendSignIn(password) },
                                enabled = !isConnecting && email.isNotBlank() && password.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent)
                            ) {
                                Icon(Icons.Filled.Key, contentDescription = null, Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isConnecting) "Signing in" else "Sign in")
                            }
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
                            Text("Calendar", color = ResolveColors.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                            Text(calendarStatus, color = ResolveColors.Secondary)
                        }
                    }
                    if (calendarConnected) {
                        Surface(
                            color = ResolveColors.Pill,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ResolveColors.Accent, modifier = Modifier.size(19.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Authorized", color = ResolveColors.Text, fontWeight = FontWeight.SemiBold)
                                    Text(calendarStatus, color = ResolveColors.Secondary, fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            Button(
                                onClick = onBackendFeishuConnect,
                                enabled = !isConnecting && backendReady,
                                colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent)
                            ) {
                                Icon(Icons.Filled.OpenInBrowser, contentDescription = null, Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isConnecting) "Opening" else if (backendReady) "Authorize Calendar" else "Sign in first")
                            }
                        }
                    }
                    calendarError?.let { Text(it, color = ResolveColors.Danger, fontSize = 12.sp) }
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
private fun InlineNotice(message: String, onDismiss: () -> Unit) {
    Surface(color = Color(0xFFFFF4E4), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(message, color = ResolveColors.Secondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = ResolveColors.Muted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun calendarViewModeLabel(mode: CalendarViewMode): String = when (mode) {
    CalendarViewMode.Day -> "Day"
    CalendarViewMode.Week -> "Week"
    CalendarViewMode.Month -> "Month"
}

private fun weekStart(date: LocalDate): LocalDate =
    date.minusDays((date.dayOfWeek.value % 7).toLong())

private fun shiftCalendarDate(date: LocalDate, mode: CalendarViewMode, direction: Int): LocalDate = when (mode) {
    CalendarViewMode.Day -> date.plusDays(direction.toLong())
    CalendarViewMode.Week -> date.plusDays(7L * direction)
    CalendarViewMode.Month -> date.plusMonths(direction.toLong())
}

private fun calendarRangeTitle(date: LocalDate, mode: CalendarViewMode): String = when (mode) {
    CalendarViewMode.Day -> date.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    CalendarViewMode.Week -> {
        val start = weekStart(date)
        val end = start.plusDays(6)
        "${start.format(DateTimeFormatter.ofPattern("M月d日"))} - ${end.format(DateTimeFormatter.ofPattern("M月d日"))}"
    }
    CalendarViewMode.Month -> date.format(DateTimeFormatter.ofPattern("yyyy年M月"))
}

private fun monthGridDates(date: LocalDate): List<LocalDate> {
    val firstDay = date.withDayOfMonth(1)
    val start = weekStart(firstDay)
    return (0 until 42).map { start.plusDays(it.toLong()) }
}

private fun calendarEventVisible(event: CalendarEvent): Boolean =
    event.status !in setOf("archived_locally", "remote_deleted", "local_pending_delete", "cancelled")

private fun expandRecurringCalendarEvents(
    events: List<CalendarEvent>,
    rangeStart: Instant,
    rangeEnd: Instant,
    zone: ZoneId
): List<CalendarEvent> =
    events
        .flatMap { event ->
            val recurrence = event.recurrence?.takeIf { it.isNotBlank() }
            if (recurrence == null) {
                if (eventOverlapsWindow(event, rangeStart, rangeEnd)) listOf(event) else emptyList()
            } else {
                expandRecurringCalendarEvent(event, recurrence, rangeStart, rangeEnd, zone)
            }
        }
        .distinctBy(::calendarDisplayKey)
        .sortedBy { it.startsAt }

private fun eventOverlapsWindow(event: CalendarEvent, rangeStart: Instant, rangeEnd: Instant): Boolean {
    val endsAt = event.endsAt ?: event.startsAt
    return endsAt >= rangeStart && event.startsAt < rangeEnd
}

private fun expandRecurringCalendarEvent(
    event: CalendarEvent,
    recurrence: String,
    rangeStart: Instant,
    rangeEnd: Instant,
    zone: ZoneId
): List<CalendarEvent> {
    val rule = parseRRule(recurrence)
    val freq = rule["FREQ"] ?: return if (eventOverlapsWindow(event, rangeStart, rangeEnd)) listOf(event) else emptyList()
    val interval = rule["INTERVAL"]?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
    val count = rule["COUNT"]?.toIntOrNull()
    val until = rule["UNTIL"]?.let { parseRRuleUntil(it, zone) }
    val start = event.startsAt.atZone(zone)
    val end = (event.endsAt ?: event.startsAt).atZone(zone)
    val duration = Duration.between(start, end)
    val byDays = parseByDay(rule["BYDAY"])
    val byMonthDays = parseByMonthDay(rule["BYMONTHDAY"])
    val occurrences = mutableListOf<CalendarEvent>()
    var cursor = start
    var generated = 0
    var guard = 0

    while (cursor.toInstant() < rangeEnd && guard < 2500) {
        guard += 1
        if (until != null && cursor.toInstant() > until) break
        if (count != null && generated >= count) break

        val starts = occurrenceStartsForCursor(cursor, start, freq, byDays, byMonthDays)
            .filter { it.toInstant() >= event.startsAt }
            .sortedBy { it.toInstant() }

        for (occurrenceStart in starts) {
            if (until != null && occurrenceStart.toInstant() > until) continue
            if (count != null && generated >= count) break
            generated += 1
            val occurrenceEnd = occurrenceStart.plus(duration)
            if (occurrenceEnd.toInstant() < rangeStart || occurrenceStart.toInstant() >= rangeEnd) continue
            occurrences += event.copy(
                id = "${event.id}_${occurrenceStart.toInstant()}",
                startsAt = occurrenceStart.toInstant(),
                endsAt = occurrenceEnd.toInstant()
            )
        }

        cursor = advanceRecurringCursor(cursor, freq, interval)
    }

    return occurrences.ifEmpty {
        if (eventOverlapsWindow(event, rangeStart, rangeEnd)) listOf(event) else emptyList()
    }
}

private fun parseRRule(recurrence: String): Map<String, String> {
    val cleaned = recurrence.substringAfter("RRULE:", recurrence)
    return cleaned
        .split(";")
        .mapNotNull { part ->
            val index = part.indexOf("=")
            if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
        }
        .toMap()
}

private fun parseRRuleUntil(value: String, zone: ZoneId): Instant? =
    when {
        Regex("^\\d{8}T\\d{6}Z$").matches(value) -> runCatching {
            Instant.parse("${value.slice(0..3)}-${value.slice(4..5)}-${value.slice(6..7)}T${value.slice(9..10)}:${value.slice(11..12)}:${value.slice(13..14)}Z")
        }.getOrNull()
        Regex("^\\d{8}$").matches(value) -> runCatching {
            LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).plusDays(1).atStartOfDay(zone).toInstant()
        }.getOrNull()
        else -> runCatching { Instant.parse(value) }.getOrNull()
    }

private fun parseByDay(value: String?): List<Int>? {
    if (value.isNullOrBlank()) return null
    val map = mapOf("SU" to 0, "MO" to 1, "TU" to 2, "WE" to 3, "TH" to 4, "FR" to 5, "SA" to 6)
    val days = value.split(",").mapNotNull { day ->
        map[day.replace(Regex("^-?\\d+"), "")]
    }
    return days.takeIf { it.isNotEmpty() }
}

private fun parseByMonthDay(value: String?): List<Int>? {
    if (value.isNullOrBlank()) return null
    val days = value.split(",").mapNotNull { it.toIntOrNull() }.filter { it > 0 }
    return days.takeIf { it.isNotEmpty() }
}

private fun occurrenceStartsForCursor(
    cursor: ZonedDateTime,
    start: ZonedDateTime,
    freq: String,
    byDays: List<Int>?,
    byMonthDays: List<Int>?
): List<ZonedDateTime> {
    if (freq == "WEEKLY" && !byDays.isNullOrEmpty()) {
        return byDays.map { day ->
            val offset = (day - cursor.dayOfWeek.value % 7 + 7) % 7
            cursor.plusDays(offset.toLong()).withHour(start.hour).withMinute(start.minute).withSecond(start.second).withNano(start.nano)
        }
    }
    if (freq == "MONTHLY" && !byMonthDays.isNullOrEmpty()) {
        return byMonthDays.mapNotNull { day ->
            runCatching {
                cursor.withDayOfMonth(day).withHour(start.hour).withMinute(start.minute).withSecond(start.second).withNano(start.nano)
            }.getOrNull()
        }.filter { it.month == cursor.month }
    }
    return listOf(cursor)
}

private fun advanceRecurringCursor(cursor: ZonedDateTime, freq: String, interval: Long): ZonedDateTime =
    when (freq) {
        "DAILY" -> cursor.plusDays(interval)
        "WEEKLY" -> cursor.plusWeeks(interval)
        "MONTHLY" -> cursor.plusMonths(interval)
        "YEARLY" -> cursor.plusYears(interval)
        else -> cursor.plusDays(interval)
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
    FeishuStatus.Connected -> settings.lastSyncedAt?.let { "Synced ${relativeTime(it)}" } ?: "Ready"
    FeishuStatus.PermissionError -> "Needs attention"
    FeishuStatus.TokenExpired -> "Authorization expired"
    FeishuStatus.NotConnected -> "Authorization needed"
}

private fun backendStatusLabel(settings: BackendSettings): String = when (settings.status) {
    BackendStatus.Connected -> settings.lastSyncedAt?.let { "Synced ${relativeTime(it)}" } ?: "Signed in"
    BackendStatus.Error -> "Needs attention"
    BackendStatus.SignedOut -> "Signed out"
    BackendStatus.NotConfigured -> "Not signed in"
}

private fun calendarStatusLabel(feishu: FeishuSettings, backend: BackendSettings): String = when {
    backend.lastError != null || feishu.lastError != null -> "Needs attention"
    backend.status == BackendStatus.Connected && backend.feishuConnected -> backend.lastSyncedAt?.let { "Synced ${relativeTime(it)}" } ?: "Connected"
    backend.status == BackendStatus.Connected -> "Authorization needed"
    else -> feishuStatusLabel(feishu)
}

private fun eventTimeLabel(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun dateLabel(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

private fun calendarEventDateTimeLabel(event: CalendarEvent): String {
    val startsAt = event.startsAt.atZone(ZoneId.systemDefault())
    val startText = startsAt.format(DateTimeFormatter.ofPattern("M月d日 EEE HH:mm"))
    val endText = event.endsAt?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm"))
    return if (endText != null) "$startText - $endText" else startText
}

private fun calendarExpandedEventMeta(event: CalendarEvent): String {
    val startsAt = event.startsAt.atZone(ZoneId.systemDefault())
    val startText = startsAt.format(DateTimeFormatter.ofPattern("HH:mm"))
    val endText = event.endsAt?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm"))
    val timeText = if (endText != null) "$startText - $endText" else startText
    val note = event.description.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
    return if (note == null) timeText else "$timeText $note"
}

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
