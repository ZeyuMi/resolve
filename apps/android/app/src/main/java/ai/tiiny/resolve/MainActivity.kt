package ai.tiiny.resolve

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

private data class TodoTreeEntry(
    val item: ResolveItem,
    val depth: Int
)

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
    var openedStrategyThreadId by remember { mutableStateOf<String?>(null) }
    var showStrategyDraft by remember { mutableStateOf(false) }
    var todoReturnStrategyThreadId by remember { mutableStateOf<String?>(null) }
    var pendingTodoArchive by remember { mutableStateOf<ResolveItem?>(null) }
    var pendingTodoDelete by remember { mutableStateOf<ResolveItem?>(null) }
    var pendingTodoArchiveClear by remember { mutableStateOf(false) }
    var pendingCalendarDelete by remember { mutableStateOf<CalendarEvent?>(null) }
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

    fun archiveItem(item: ResolveItem) {
        updateItem(item.copy(status = ItemStatus.Archived))
        if (selectedTodoId == item.id) selectedTodoId = null
        notice = null
    }

    fun deleteItemPermanently(item: ResolveItem) {
        val childIds = state.items.filter { it.parentItemId == item.id }.map { it.id }.toSet()
        persist(state.copy(items = state.items.filterNot { it.id == item.id || it.id in childIds || it.parentItemId == item.id }))
        if (selectedTodoId == item.id) selectedTodoId = null
        notice = null
    }

    fun clearArchivedItems() {
        val archivedIds = state.items.filter { it.status == ItemStatus.Archived }.map { it.id }.toSet()
        persist(state.copy(items = state.items.filterNot { it.id in archivedIds || it.parentItemId in archivedIds }))
        notice = null
    }

    fun restoreCalendarEvent(event: CalendarEvent) {
        val restored = event.copy(
            status = when (event.provider) {
                "feishu" -> if (event.canEdit) "synced" else "readonly"
                else -> "local"
            }
        )
        persist(state.copy(calendarEvents = replaceCalendarEvent(state.calendarEvents, event, restored)))
        notice = null
    }

    fun addSubtask(parent: ResolveItem, title: String) {
        val text = title.trim()
        if (text.isBlank()) return
        val child = ResolveItem(
            title = text,
            strategyThreadId = parent.strategyThreadId,
            parentItemId = parent.id
        )
        persist(state.copy(items = listOf(child) + state.items))
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

    fun deleteCalendarEvent(event: CalendarEvent) {
        val canDeleteRemote = event.provider == "feishu" &&
            event.canDelete &&
            !event.externalCalendarId.isNullOrBlank() &&
            !event.externalEventId.isNullOrBlank() &&
            hasBackendSession &&
            state.backendSettings.feishuConnected
        val hiddenEvent = event.copy(status = if (canDeleteRemote) "local_pending_delete" else "archived_locally")

        persist(
            state.copy(
                calendarEvents = when {
                    event.provider == "local" -> replaceCalendarEvent(state.calendarEvents, event, hiddenEvent.copy(status = "archived_locally"))
                    else -> replaceCalendarEvent(state.calendarEvents, event, hiddenEvent)
                }
            )
        )
        selectedCalendarEvent = null
        editingCalendarEvent = null
        expandedCalendarDate = event.startsAt.atZone(ZoneId.systemDefault()).toLocalDate()
        notice = null

        if (!canDeleteRemote) return

        scope.launch {
            try {
                val client = connectedBackendClient()
                val syncedAt = withContext(Dispatchers.IO) { client.deleteEvent(event) }
                persist(
                    state.copy(
                        calendarEvents = replaceCalendarEvent(state.calendarEvents, hiddenEvent, hiddenEvent.copy(status = "remote_deleted")),
                        backendSettings = state.backendSettings.copy(lastSyncedAt = syncedAt, lastError = null),
                        feishuSettings = state.feishuSettings.copy(status = FeishuStatus.Connected, lastSyncedAt = syncedAt, lastError = null)
                    )
                )
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
                } else {
                    persist(
                        state.copy(
                            calendarEvents = replaceCalendarEvent(state.calendarEvents, hiddenEvent, event.copy(status = "error")),
                            backendSettings = state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message),
                            feishuSettings = state.feishuSettings.copy(lastError = error.message)
                        )
                    )
                }
            }
        }
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

    fun closeTodoDetail() {
        selectedTodoId = null
        todoReturnStrategyThreadId?.let { threadId ->
            selectedThreadId = threadId
            openedStrategyThreadId = threadId
            showStrategyDraft = false
            tab = Tab.Strategy
            todoReturnStrategyThreadId = null
        }
    }

    fun canNavigateBack(): Boolean =
        pendingTodoArchive != null ||
            pendingTodoDelete != null ||
            pendingTodoArchiveClear ||
            pendingCalendarDelete != null ||
            selectedTodoId != null ||
            (tab == Tab.Calendar && (editingCalendarEvent != null || selectedCalendarEvent != null || showCalendarDraft || expandedCalendarDate != null)) ||
            (tab == Tab.Strategy && (showStrategyDraft || openedStrategyThreadId != null))

    fun navigateBack() {
        when {
            pendingTodoArchive != null -> pendingTodoArchive = null
            pendingTodoDelete != null -> pendingTodoDelete = null
            pendingTodoArchiveClear -> pendingTodoArchiveClear = false
            pendingCalendarDelete != null -> pendingCalendarDelete = null
            selectedTodoId != null -> closeTodoDetail()
            tab == Tab.Calendar && editingCalendarEvent != null -> {
                selectedCalendarEvent = editingCalendarEvent
                editingCalendarEvent = null
            }
            tab == Tab.Calendar && selectedCalendarEvent != null -> {
                selectedCalendarEvent = null
                editingCalendarEvent = null
            }
            tab == Tab.Calendar && showCalendarDraft -> showCalendarDraft = false
            tab == Tab.Calendar && expandedCalendarDate != null -> expandedCalendarDate = null
            tab == Tab.Strategy && showStrategyDraft -> showStrategyDraft = false
            tab == Tab.Strategy && openedStrategyThreadId != null -> openedStrategyThreadId = null
        }
    }

    BackHandler(enabled = canNavigateBack()) {
        navigateBack()
    }

    val isFullPageDetail =
        selectedTodoId != null ||
            selectedCalendarEvent != null ||
            editingCalendarEvent != null ||
            showCalendarDraft ||
            (tab == Tab.Strategy && (openedStrategyThreadId != null || showStrategyDraft))

    ResolveTheme {
        Scaffold(
            containerColor = ResolveColors.Bg,
            bottomBar = {
                BottomTabs(
                    tab = tab,
                    onTab = {
                        tab = it
                        if (it == Tab.Strategy) openedStrategyThreadId = null
                        if (it == Tab.Strategy) showStrategyDraft = false
                        if (it == Tab.Todo) todoReturnStrategyThreadId = null
                    }
                )
            },
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
                } else if (tab == Tab.Strategy && openedStrategyThreadId == null && !showStrategyDraft) {
                    FloatingActionButton(
                        onClick = { showStrategyDraft = true },
                        containerColor = Color(0xFF6857D9),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add strategy", modifier = Modifier.size(28.dp))
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
                if (!isFullPageDetail) {
                    TopHeader(
                        title = tab.label,
                        settings = state.feishuSettings,
                        backend = state.backendSettings,
                        isSyncing = isSyncing,
                        compact = tab == Tab.Calendar,
                        showSettings = false,
                        onSettings = { tab = Tab.Settings }
                    )
                    Spacer(Modifier.height(if (tab == Tab.Calendar) 3.dp else 10.dp))
                }
                notice?.let {
                    InlineNotice(message = it, onDismiss = { notice = null })
                    Spacer(Modifier.height(if (tab == Tab.Calendar) 4.dp else 10.dp))
                }
                if (tab == Tab.Todo && selectedTodoId == null) {
                    CaptureBox(value = capture, onChange = { capture = it }, onSave = { saveCapture() })
                    Spacer(Modifier.height(12.dp))
                }
                Box(
                    Modifier
                        .weight(1f)
                        .swipeBack(enabled = canNavigateBack(), onBack = { navigateBack() })
                ) {
                    when (tab) {
                        Tab.Todo -> {
                            val selectedTodo = state.items.find { it.id == selectedTodoId }
                            if (selectedTodo != null) {
                                TodoDetailPage(
                                    item = selectedTodo,
                                    threads = state.threads,
                                    subtasks = descendantsOf(state.items, selectedTodo.id),
                                    onClose = { closeTodoDetail() },
                                    onUpdate = { updateItem(it) },
                                    onToggleSubtask = { item ->
                                        updateItem(item.copy(status = if (item.status == ItemStatus.Done) ItemStatus.Active else ItemStatus.Done))
                                    },
                                    onAddSubtask = { title -> addSubtask(selectedTodo, title) },
                                    onArchive = { pendingTodoArchive = it },
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
                            } else {
                                TodoScreen(
                                    state = state,
                                    showCompleted = showCompleted,
                                    showArchived = showArchived,
                                    onToggleDone = { item ->
                                        updateItem(item.copy(status = if (item.status == ItemStatus.Done) ItemStatus.Active else ItemStatus.Done))
                                    },
                                    onRestore = { item -> updateItem(item.copy(status = ItemStatus.Active)) },
                                    onArchive = { pendingTodoArchive = it },
                                    onSelect = { selectedTodoId = it.id },
                                    onShowCompleted = { showCompleted = !showCompleted },
                                    onShowArchived = { showArchived = !showArchived },
                                    onDeleteArchived = { pendingTodoDelete = it },
                                    onClearArchived = { pendingTodoArchiveClear = true }
                                )
                            }
                        }

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
                            onDeleteEvent = { pendingCalendarDelete = it },
                            onRestoreEvent = { restoreCalendarEvent(it) },
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
                            openedThreadId = openedStrategyThreadId,
                            showNewThread = showStrategyDraft,
                            onThread = { selectedThreadId = it },
                            onOpenThread = {
                                selectedThreadId = it
                                openedStrategyThreadId = it
                            },
                            onCloseThread = { openedStrategyThreadId = null },
                            onCloseNewThread = { showStrategyDraft = false },
                            onAddThread = { title, hypothesis ->
                                if (title.isNotBlank()) {
                                    val thread = StrategyThread(title = title.trim(), currentHypothesis = hypothesis.trim())
                                    persist(state.copy(threads = listOf(thread) + state.threads))
                                    selectedThreadId = thread.id
                                    openedStrategyThreadId = thread.id
                                    showStrategyDraft = false
                                }
                            },
                            onAddTask = { threadId, title ->
                                if (title.isNotBlank()) {
                                    val item = ResolveItem(title = title.trim(), strategyThreadId = threadId)
                                    persist(state.copy(items = listOf(item) + state.items))
                                }
                            },
                            onSelectTodo = {
                                selectedTodoId = it.id
                                todoReturnStrategyThreadId = openedStrategyThreadId ?: selectedThreadId
                                tab = Tab.Todo
                            },
                            onToggleDone = { item ->
                                updateItem(item.copy(status = if (item.status == ItemStatus.Done) ItemStatus.Active else ItemStatus.Done))
                            },
                            onArchiveTodo = { pendingTodoArchive = it }
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

        pendingTodoArchive?.let { item ->
            ConfirmActionDialog(
                title = "Archive todo?",
                message = item.title,
                confirmLabel = "Archive",
                danger = false,
                onDismiss = { pendingTodoArchive = null },
                onConfirm = {
                    archiveItem(item)
                    pendingTodoArchive = null
                }
            )
        }
        pendingTodoDelete?.let { item ->
            ConfirmActionDialog(
                title = "Delete forever?",
                message = item.title,
                confirmLabel = "Delete",
                danger = true,
                onDismiss = { pendingTodoDelete = null },
                onConfirm = {
                    deleteItemPermanently(item)
                    pendingTodoDelete = null
                }
            )
        }
        if (pendingTodoArchiveClear) {
            ConfirmActionDialog(
                title = "Clear archive?",
                message = "Permanently delete every archived todo.",
                confirmLabel = "Clear",
                danger = true,
                onDismiss = { pendingTodoArchiveClear = false },
                onConfirm = {
                    clearArchivedItems()
                    pendingTodoArchiveClear = false
                }
            )
        }
        pendingCalendarDelete?.let { event ->
            val action = calendarDeleteActionLabel(event)
            ConfirmActionDialog(
                title = "$action event?",
                message = event.title,
                confirmLabel = action,
                danger = true,
                onDismiss = { pendingCalendarDelete = null },
                onConfirm = {
                    deleteCalendarEvent(event)
                    pendingCalendarDelete = null
                }
            )
        }
    }
}

private fun Modifier.swipeBack(enabled: Boolean, onBack: () -> Unit): Modifier {
    if (!enabled) return this
    return pointerInput(onBack) {
        var drag = 0f
        detectHorizontalDragGestures(
            onDragEnd = {
                if (drag > 120f) onBack()
                drag = 0f
            },
            onDragCancel = { drag = 0f },
            onHorizontalDrag = { _, dragAmount ->
                drag = (drag + dragAmount).coerceAtLeast(0f)
            }
        )
    }
}

@Composable
private fun ResolveMark(size: Dp) {
    Surface(
        color = ResolveColors.Accent,
        shape = CircleShape,
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size((size.value * 0.58f).dp)
            )
        }
    }
}

@Composable
private fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    danger: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = ResolveColors.Text, fontSize = ResolveType.CardTitle, fontWeight = FontWeight.SemiBold) },
        text = { Text(message, color = ResolveColors.Secondary, fontSize = ResolveType.BodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = if (danger) ResolveColors.Danger else ResolveColors.Accent)
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = ResolveColors.Secondary)
            }
        },
        containerColor = ResolveColors.Surface
    )
}

@Composable
private fun TopHeader(
    title: String,
    settings: FeishuSettings,
    backend: BackendSettings,
    isSyncing: Boolean,
    compact: Boolean = false,
    showSettings: Boolean = true,
    onSettings: () -> Unit
) {
    if (compact) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            ResolveMark(size = 24.dp)
            Spacer(Modifier.width(7.dp))
            Text(title, color = ResolveColors.Text, fontSize = ResolveType.CardTitle, fontWeight = FontWeight.SemiBold)
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
            if (showSettings) {
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
        }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ResolveMark(size = 28.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Resolve", color = ResolveColors.Muted, fontSize = ResolveType.Caption, fontWeight = FontWeight.SemiBold)
            Text(title, color = ResolveColors.Text, fontSize = ResolveType.PageTitle, fontWeight = FontWeight.SemiBold)
        }
        if (showSettings) {
            Surface(color = ResolveColors.Pill, shape = RoundedCornerShape(999.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(15.dp), tint = ResolveColors.Muted)
                    Spacer(Modifier.width(5.dp))
                    Text(if (isSyncing) "Syncing" else calendarStatusLabel(settings, backend), color = ResolveColors.Secondary, fontSize = ResolveType.Caption)
                }
            }
        }
        if (showSettings) {
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = ResolveColors.Secondary)
            }
        }
    }
}

@Composable
private fun CaptureBox(value: String, onChange: (String) -> Unit, onSave: () -> Unit) {
    OutlinedCard(
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = ResolveColors.Surface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = ResolveColors.Muted, modifier = Modifier.size(19.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = ResolveColors.Text,
                        fontSize = ResolveType.Body,
                        lineHeight = 17.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (value.isBlank()) {
                    Text(
                        "记一下",
                        color = ResolveColors.Muted,
                        fontSize = ResolveType.Body,
                        lineHeight = 17.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
            Surface(
                color = if (value.isBlank()) ResolveColors.Pill else ResolveColors.Accent,
                shape = CircleShape,
                onClick = { if (value.isNotBlank()) onSave() },
                modifier = Modifier.size(30.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Save", tint = if (value.isBlank()) ResolveColors.Muted else Color.White, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable
private fun CompactInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: Dp = 38.dp,
    textStyle: TextStyle = TextStyle(
        color = ResolveColors.Text,
        fontSize = ResolveType.Body,
        lineHeight = 17.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(shape)
            .border(1.dp, ResolveColors.Line, shape)
            .padding(horizontal = 11.dp, vertical = if (singleLine) 8.dp else 10.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = textStyle,
            modifier = Modifier.fillMaxWidth()
        )
        if (value.isBlank()) {
            Text(
                placeholder,
                color = ResolveColors.Muted,
                fontSize = textStyle.fontSize,
                lineHeight = textStyle.lineHeight,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                maxLines = if (singleLine) 1 else 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TodoScreen(
    state: ResolveState,
    showCompleted: Boolean,
    showArchived: Boolean,
    onToggleDone: (ResolveItem) -> Unit,
    onRestore: (ResolveItem) -> Unit,
    onArchive: (ResolveItem) -> Unit,
    onSelect: (ResolveItem) -> Unit,
    onShowCompleted: () -> Unit,
    onShowArchived: () -> Unit,
    onDeleteArchived: (ResolveItem) -> Unit,
    onClearArchived: () -> Unit
) {
    val tasks = state.items.filter { it.type == ItemType.Task }
    val activeIds = tasks.filter { it.status == ItemStatus.Active }.map { it.id }.toSet()
    val completedIds = tasks.filter { it.status == ItemStatus.Done }.map { it.id }.toSet()
    val archivedIds = tasks.filter { it.status == ItemStatus.Archived }.map { it.id }.toSet()
    val active = tasks.filter { it.status == ItemStatus.Active && (it.parentItemId == null || it.parentItemId !in activeIds) }
    val completed = tasks.filter { it.status == ItemStatus.Done && (it.parentItemId == null || it.parentItemId !in completedIds) }
    val archived = tasks.filter { it.status == ItemStatus.Archived && (it.parentItemId == null || it.parentItemId !in archivedIds) }
    val activeTree = flattenTodoTree(active, tasks.filter { it.status == ItemStatus.Active })
    val completedTree = flattenTodoTree(completed, tasks.filter { it.status == ItemStatus.Done })
    LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items(activeTree, key = { it.item.id }) { entry ->
            val item = entry.item
            TodoRow(
                item = item,
                subtaskCount = tasks.count { it.parentItemId == item.id },
                thread = state.threads.find { it.id == item.strategyThreadId },
                depth = entry.depth,
                onToggleDone = { onToggleDone(item) },
                onArchive = { onArchive(item) },
                onSelect = { onSelect(item) }
            )
        }
        if (completed.isNotEmpty()) {
            item {
                Spacer(Modifier.height(if (active.isEmpty()) 22.dp else 10.dp))
                TodoDisclosureRow(
                    title = "Show completed",
                    count = completed.size,
                    open = showCompleted,
                    onClick = onShowCompleted
                )
            }
        }
        if (showCompleted) {
            items(completedTree, key = { it.item.id }) { entry ->
                val item = entry.item
                TodoRow(
                    item = item,
                    subtaskCount = tasks.count { it.parentItemId == item.id },
                    thread = state.threads.find { it.id == item.strategyThreadId },
                    depth = entry.depth,
                    onToggleDone = { onRestore(item) },
                    onArchive = { onArchive(item) },
                    onSelect = { onSelect(item) }
                )
            }
        }
        if (archived.isNotEmpty()) {
            item {
                Spacer(Modifier.height(6.dp))
                ArchiveDisclosureRow(count = archived.size, open = showArchived, onClick = onShowArchived)
            }
        }
        if (showArchived) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClearArchived) {
                        Text("Clear archive", color = ResolveColors.Danger, fontSize = ResolveType.Caption)
                    }
                }
            }
            items(archived, key = { it.id }) { item ->
                ArchivedTodoRow(
                    item = item,
                    thread = state.threads.find { it.id == item.strategyThreadId },
                    onRestore = { onRestore(item) },
                    onDelete = { onDeleteArchived(item) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodoRow(
    item: ResolveItem,
    subtaskCount: Int,
    thread: StrategyThread?,
    depth: Int = 0,
    onToggleDone: () -> Unit,
    onArchive: () -> Unit,
    onSelect: () -> Unit
) {
    val isChild = depth > 0
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (isChild) {
            Spacer(Modifier.width(((depth - 1).coerceAtLeast(0) * 14).dp))
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(34.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(1.dp)
                        .height(28.dp)
                        .background(ResolveColors.Line)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(12.dp)
                        .height(1.dp)
                        .background(ResolveColors.Line)
                )
            }
        }
        OutlinedCard(
            modifier = (if (isChild) Modifier.weight(1f) else Modifier.fillMaxWidth())
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = onArchive
                ),
            shape = RoundedCornerShape(if (isChild) 11.dp else 13.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = when {
                    item.status == ItemStatus.Archived -> Color(0xFFFAFBFD)
                    isChild -> Color(0xFFFBFCFF)
                    else -> ResolveColors.Surface
                }
            )
        ) {
            Row(Modifier.padding(horizontal = if (isChild) 8.dp else 10.dp, vertical = if (isChild) 3.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleDone, modifier = Modifier.size(if (isChild) 24.dp else 27.dp)) {
                    Icon(
                        if (item.status == ItemStatus.Done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (item.status == ItemStatus.Archived) "Restore" else "Toggle done",
                        tint = if (item.status == ItemStatus.Done) ResolveColors.Accent else ResolveColors.Muted,
                        modifier = Modifier.size(if (isChild) 17.dp else 19.dp)
                    )
                }
                Spacer(Modifier.width(if (isChild) 4.dp else 6.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        item.title,
                        color = if (item.status == ItemStatus.Archived) ResolveColors.Muted else ResolveColors.Text,
                        fontSize = ResolveType.Body,
                        lineHeight = 17.sp,
                        fontWeight = if (isChild) FontWeight.Normal else FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (item.status == ItemStatus.Done) TextDecoration.LineThrough else TextDecoration.None
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        item.dueAt?.let { MetaPill(dateLabel(it), tone = "accent") }
                        thread?.let { MetaPill(it.title) }
                        if (subtaskCount > 0) MetaPill("$subtaskCount subtasks", tone = "soft")
                        if (item.notes.isNotBlank()) MetaPill("Comment")
                        if (item.status == ItemStatus.Archived) MetaPill("Archived")
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivedTodoRow(
    item: ResolveItem,
    thread: StrategyThread?,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    OutlinedCard(
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = Color(0xFFFAFBFD)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, color = ResolveColors.Muted, fontSize = ResolveType.BodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    thread?.let { MetaPill(it.title) }
                    item.dueAt?.let { MetaPill(dateLabel(it), tone = "accent") }
                    MetaPill("Archived")
                }
            }
            TextButton(onClick = onRestore) {
                Text("Restore", fontSize = ResolveType.Caption)
            }
            TextButton(onClick = onDelete) {
                Text("Delete", color = ResolveColors.Danger, fontSize = ResolveType.Caption)
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
    onDeleteEvent: (CalendarEvent) -> Unit,
    onRestoreEvent: (CalendarEvent) -> Unit,
    onEditEvent: (CalendarEvent) -> Unit,
    onCloseEdit: () -> Unit,
    onUpdateEvent: (CalendarEvent, CalendarDraft) -> Unit,
    onDraft: (CalendarDraft) -> Unit,
    onCloseDraft: () -> Unit,
    onCreateDraft: (CalendarDraft) -> Unit,
    onExpandDay: (LocalDate) -> Unit
) {
    if (selectedEvent != null) {
        CalendarEventDetailPage(
            event = selectedEvent,
            onClose = onCloseEvent,
            onEdit = { onEditEvent(selectedEvent) },
            onDelete = { onDeleteEvent(selectedEvent) }
        )
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
    val archivedEvents = state.calendarEvents
        .filter(::calendarEventArchived)
        .sortedByDescending { it.startsAt }
    val inlineExpandedDate = expandedDate?.takeIf { date ->
        (normalizedViewMode == CalendarViewMode.Week && date in weekDays) ||
            (normalizedViewMode == CalendarViewMode.Month && monthWeeks.any { date in it })
    }
    var archiveOpen by remember { mutableStateOf(false) }

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
                            onSelectEvent = onSelectEvent,
                            onDeleteEvent = onDeleteEvent
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
                                onSelectEvent = onSelectEvent,
                                onDeleteEvent = onDeleteEvent
                            )
                        }
                    }
                }
            }
        }
        if (archivedEvents.isNotEmpty()) {
            CalendarArchivePanel(
                events = archivedEvents,
                open = archiveOpen,
                onToggle = { archiveOpen = !archiveOpen },
                onRestoreEvent = onRestoreEvent
            )
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
            fontSize = ResolveType.CardTitle,
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
                    Text(calendarViewModeLabel(viewMode), color = ResolveColors.Secondary, fontSize = ResolveType.Caption)
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
    onSelectEvent: (CalendarEvent) -> Unit,
    onDeleteEvent: (CalendarEvent) -> Unit
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
                    fontSize = ResolveType.Body,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text("${events.size} 项", color = ResolveColors.Muted, fontSize = ResolveType.Pill)
            }
            if (events.isEmpty()) {
                Text("这一天还没有日程", color = ResolveColors.Muted, fontSize = ResolveType.BodySmall)
            } else {
                events.forEach { event ->
                    CalendarExpandedEventRow(
                        event = event,
                        onClick = { onSelectEvent(event) },
                        onDelete = { onDeleteEvent(event) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarExpandedEventRow(event: CalendarEvent, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .combinedClickable(onClick = onClick, onLongClick = onDelete)
            .background(Color(0xFFF0F2F5))
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
                fontSize = ResolveType.BodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                calendarExpandedEventMeta(event),
                color = ResolveColors.Muted,
                fontSize = ResolveType.Caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CalendarArchivePanel(
    events: List<CalendarEvent>,
    open: Boolean,
    onToggle: () -> Unit,
    onRestoreEvent: (CalendarEvent) -> Unit
) {
    Surface(color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ResolveColors.Muted,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text("Calendar archive", color = ResolveColors.Muted, fontSize = ResolveType.Caption, modifier = Modifier.weight(1f))
                Text(events.size.toString(), color = ResolveColors.Muted, fontSize = ResolveType.Pill)
            }
            if (open) {
                events.take(8).forEach { event ->
                    Surface(color = ResolveColors.Surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(event.title, color = ResolveColors.Text, fontSize = ResolveType.BodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(calendarEventDateTimeLabel(event), color = ResolveColors.Muted, fontSize = ResolveType.Pill, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { onRestoreEvent(event) }) {
                                Text("Restore", fontSize = ResolveType.Caption)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarEventDetailPage(
    event: CalendarEvent,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val canEdit = event.provider != "feishu" || event.canEdit
    val deleteAction = calendarDeleteActionLabel(event)
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
                Text("Event", color = ResolveColors.Muted, fontSize = ResolveType.Caption, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                MetaPill(if (!canEdit) "Readonly" else "Editable", tone = if (!canEdit) "muted" else "accent")
            }

            Surface(color = ResolveColors.Surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(event.title, color = ResolveColors.Text, fontSize = ResolveType.DetailTitle, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                    CalendarEventInfoRow(label = "Time", value = calendarEventDateTimeLabel(event))
                    if (event.description.isNotBlank()) {
                        CalendarEventInfoRow(label = "Comment", value = event.description)
                    } else {
                        CalendarEventInfoRow(label = "Comment", value = "No comment")
                    }
                    CalendarEventInfoRow(label = "Calendar", value = calendarEventSourceLabel(event))
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
                        enabled = canEdit,
                        colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (canEdit) "Edit" else "Readonly")
                    }
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = ResolveColors.Danger)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(deleteAction)
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
        Text(label, color = ResolveColors.Muted, fontSize = ResolveType.Caption, fontWeight = FontWeight.SemiBold)
        Text(value, color = ResolveColors.Secondary, fontSize = ResolveType.Body)
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
                    Text(title, color = ResolveColors.Text, fontSize = ResolveType.CardTitle, fontWeight = FontWeight.SemiBold)
                    Text(draft.date.format(DateTimeFormatter.ofPattern("M月d日 EEE")), color = ResolveColors.Muted, fontSize = ResolveType.Caption)
                }
            }

            Surface(color = ResolveColors.Surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompactInputField(
                        value = draft.title,
                        onValueChange = { onDraft(draft.copy(title = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "日程标题",
                        minHeight = 40.dp,
                        textStyle = TextStyle(
                            color = ResolveColors.Text,
                            fontSize = ResolveType.Body,
                            lineHeight = 17.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
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
                    CompactInputField(
                        value = draft.description,
                        onValueChange = { onDraft(draft.copy(description = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "备注，可选",
                        singleLine = false,
                        minHeight = 82.dp,
                        textStyle = TextStyle(
                            color = ResolveColors.Text,
                            fontSize = ResolveType.Body,
                            lineHeight = 17.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
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
private fun DetailPageHeader(title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = ResolveColors.Pill,
            shape = CircleShape,
            modifier = Modifier
                .size(32.dp)
                .clickable(onClick = onClose)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Back",
                    tint = ResolveColors.Secondary,
                    modifier = Modifier.rotate(180f).size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(title, color = ResolveColors.Muted, fontSize = ResolveType.BodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DetailSectionTitle(title: String, count: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, color = ResolveColors.Text, fontSize = ResolveType.CardTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(count, color = ResolveColors.Muted, fontSize = ResolveType.Caption)
    }
}

@Composable
private fun SubtaskRow(item: ResolveItem, depth: Int, onToggle: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (depth > 0) {
            Spacer(Modifier.width(((depth - 1).coerceAtLeast(0) * 13).dp))
            Box(
                modifier = Modifier
                    .width(15.dp)
                    .height(30.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(1.dp)
                        .height(24.dp)
                        .background(ResolveColors.Line)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(10.dp)
                        .height(1.dp)
                        .background(ResolveColors.Line)
                )
            }
        }
        Surface(
            color = if (depth > 0) Color(0xFFFBFCFF) else ResolveColors.Surface,
            shape = RoundedCornerShape(12.dp),
            modifier = if (depth > 0) Modifier.weight(1f) else Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggle, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (item.status == ItemStatus.Done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = "Toggle subtask",
                        tint = if (item.status == ItemStatus.Done) ResolveColors.Accent else ResolveColors.Muted,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    item.title,
                    color = if (item.status == ItemStatus.Done) ResolveColors.Muted else ResolveColors.Text,
                    fontSize = ResolveType.Body,
                    lineHeight = 17.sp,
                    textDecoration = if (item.status == ItemStatus.Done) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StrategyOverviewCard(
    thread: StrategyThread,
    total: Int,
    done: Int,
    onClick: () -> Unit
) {
    Surface(
        color = ResolveColors.Surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    thread.title,
                    color = ResolveColors.Text,
                    fontSize = ResolveType.CardTitle,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ResolveColors.Muted, modifier = Modifier.size(18.dp))
            }
            Text(
                thread.currentHypothesis.ifBlank { "No brief yet." },
                color = ResolveColors.Secondary,
                fontSize = ResolveType.BodySmall,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                MetaPill("$done/$total subtasks", tone = if (total > 0) "accent" else "muted")
                MetaPill("Review not set")
            }
        }
    }
}

@Composable
private fun NewStrategyDirectionPage(
    onClose: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var hypothesis by remember { mutableStateOf("") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { DetailPageHeader(title = "New strategy", onClose = onClose) }
        item {
            Surface(color = ResolveColors.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompactInputField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "战略方向",
                        singleLine = true,
                        minHeight = 44.dp,
                        textStyle = TextStyle(
                            color = ResolveColors.Text,
                            fontSize = ResolveType.CardTitle,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                    CompactInputField(
                        value = hypothesis,
                        onValueChange = { hypothesis = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "简要介绍 / 当前假设",
                        singleLine = false,
                        minHeight = 92.dp,
                        textStyle = TextStyle(
                            color = ResolveColors.Text,
                            fontSize = ResolveType.Body,
                            lineHeight = 17.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                    Button(
                        onClick = { onCreate(title, hypothesis) },
                        enabled = title.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create strategy")
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategyScreen(
    state: ResolveState,
    selectedThreadId: String,
    openedThreadId: String?,
    showNewThread: Boolean,
    onThread: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onCloseThread: () -> Unit,
    onCloseNewThread: () -> Unit,
    onAddThread: (String, String) -> Unit,
    onAddTask: (String, String) -> Unit,
    onSelectTodo: (ResolveItem) -> Unit,
    onToggleDone: (ResolveItem) -> Unit,
    onArchiveTodo: (ResolveItem) -> Unit
) {
    var task by remember { mutableStateOf("") }
    val opened = state.threads.find { it.id == openedThreadId }
    val allTasks = state.items.filter { it.type == ItemType.Task }

    if (showNewThread) {
        NewStrategyDirectionPage(
            onClose = onCloseNewThread,
            onCreate = { title, hypothesis -> onAddThread(title, hypothesis) }
        )
        return
    }

    if (opened == null) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { DetailSectionTitle("Strategy", "${state.threads.size}") }
            items(state.threads, key = { it.id }) { thread ->
                val linked = allTasks.filter { it.strategyThreadId == thread.id && it.status != ItemStatus.Archived }
                StrategyOverviewCard(
                    thread = thread,
                    total = linked.size,
                    done = linked.count { it.status == ItemStatus.Done },
                    onClick = {
                        onThread(thread.id)
                        onOpenThread(thread.id)
                    }
                )
            }
        }
        return
    }

    val subtasks = state.items
        .filter { it.strategyThreadId == opened.id && it.type == ItemType.Task && it.status != ItemStatus.Archived }
        .sortedWith(compareBy<ResolveItem> { it.status == ItemStatus.Done }.thenByDescending { it.createdAt })
    val subtaskRoots = subtasks.filter { task -> task.parentItemId == null || subtasks.none { it.id == task.parentItemId } }
    val subtaskTree = flattenTodoTree(subtaskRoots, subtasks)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { DetailPageHeader(title = "Strategy", onClose = onCloseThread) }
        item {
            Surface(color = ResolveColors.Surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = ResolveColors.Muted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    CompactInputField(
                        value = task,
                        onValueChange = { task = it },
                        modifier = Modifier.weight(1f),
                        placeholder = "Add task...",
                        singleLine = true,
                        minHeight = 36.dp,
                        textStyle = TextStyle(
                            color = ResolveColors.Text,
                            fontSize = ResolveType.BodySmall,
                            lineHeight = 16.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = if (task.isBlank()) ResolveColors.Pill else ResolveColors.Accent,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(34.dp)
                            .clickable(enabled = task.isNotBlank()) {
                                onAddTask(opened.id, task)
                                task = ""
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Add", tint = if (task.isBlank()) ResolveColors.Muted else Color.White, modifier = Modifier.size(19.dp))
                        }
                    }
                }
            }
        }
        item {
            Surface(
                color = Color(0xFFFCFBFF),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFE6E0F4), RoundedCornerShape(20.dp))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(opened.title, color = ResolveColors.Text, fontSize = ResolveType.CardTitle, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        opened.currentHypothesis.ifBlank { "No brief yet." },
                        color = ResolveColors.Secondary,
                        fontSize = ResolveType.BodySmall,
                        lineHeight = 16.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        MetaPill("${subtasks.count { it.status == ItemStatus.Done }}/${subtasks.size} done", tone = "accent")
                        MetaPill("Review not set")
                    }
                }
            }
        }
        item { DetailSectionTitle("Subtasks", "${subtasks.size}") }
        items(subtaskTree, key = { it.item.id }) { entry ->
            val item = entry.item
            TodoRow(
                item = item,
                subtaskCount = allTasks.count { it.parentItemId == item.id },
                thread = null,
                depth = entry.depth,
                onToggleDone = { onToggleDone(item) },
                onArchive = { onArchiveTodo(item) },
                onSelect = { onSelectTodo(item) }
            )
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
                            Text("Account", color = ResolveColors.Text, fontSize = ResolveType.SectionTitle, fontWeight = FontWeight.SemiBold)
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
                                    Text(state.backendSettings.email.ifBlank { "Resolve account" }, color = ResolveColors.Secondary, fontSize = ResolveType.BodySmall)
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
                    state.backendSettings.lastError?.let { Text(it, color = ResolveColors.Danger, fontSize = ResolveType.BodySmall) }
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
                            Text("Calendar", color = ResolveColors.Text, fontSize = ResolveType.SectionTitle, fontWeight = FontWeight.SemiBold)
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
                                    Text(calendarStatus, color = ResolveColors.Secondary, fontSize = ResolveType.BodySmall)
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
                    calendarError?.let { Text(it, color = ResolveColors.Danger, fontSize = ResolveType.BodySmall) }
                }
            }
        }
    }
}

@Composable
private fun TodoDetailPage(
    item: ResolveItem,
    threads: List<StrategyThread>,
    subtasks: List<ResolveItem>,
    onClose: () -> Unit,
    onUpdate: (ResolveItem) -> Unit,
    onToggleSubtask: (ResolveItem) -> Unit,
    onAddSubtask: (String) -> Unit,
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
    var subtaskTitle by remember(item.id) { mutableStateOf("") }

    fun currentItem() = item.copy(title = title.trim(), notes = notes.trim(), strategyThreadId = strategyThreadId, dueAt = dueAt)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            DetailPageHeader(title = "Todo", onClose = onClose)
        }
        item {
            Surface(color = ResolveColors.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactInputField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Title",
                        singleLine = false,
                        minHeight = 42.dp,
                        textStyle = TextStyle(
                            color = ResolveColors.Text,
                            fontSize = ResolveType.CardTitle,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                    CompactInputField(
                        value = notes,
                        onValueChange = { notes = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Comment",
                        singleLine = false,
                        minHeight = 70.dp,
                        textStyle = TextStyle(
                            color = ResolveColors.Text,
                            fontSize = ResolveType.Body,
                            lineHeight = 17.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        AssistChip(
                            onClick = {
                                DatePickerDialog(context, { _, year, month, day ->
                                    dueAt = LocalDate.of(year, month + 1, day).atTime(dueTime).atZone(ZoneId.systemDefault()).toInstant()
                                }, dueDate.year, dueDate.monthValue - 1, dueDate.dayOfMonth).show()
                            },
                            modifier = Modifier.height(32.dp),
                            leadingIcon = { Icon(Icons.Filled.Event, contentDescription = null, Modifier.size(14.dp)) },
                            label = { Text(dueAt?.let { dateLabel(it) } ?: "Set date", fontSize = ResolveType.Caption) }
                        )
                        AssistChip(
                            onClick = {
                                TimePickerDialog(context, { _, hour, minute ->
                                    dueAt = dueDate.atTime(LocalTime.of(hour, minute)).atZone(ZoneId.systemDefault()).toInstant()
                                }, dueTime.hour, dueTime.minute, true).show()
                            },
                            modifier = Modifier.height(32.dp),
                            leadingIcon = { Icon(Icons.Filled.EditCalendar, contentDescription = null, Modifier.size(14.dp)) },
                            label = { Text(dueTime.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = ResolveType.Caption) }
                        )
                        Box {
                            AssistChip(
                                onClick = { expanded = true },
                                modifier = Modifier.height(32.dp),
                                leadingIcon = { Icon(Icons.Filled.Psychology, contentDescription = null, Modifier.size(14.dp)) },
                                label = { Text(threads.find { it.id == strategyThreadId }?.title ?: "No strategy", fontSize = ResolveType.Caption) }
                            )
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text("No strategy") }, onClick = { strategyThreadId = null; expanded = false })
                                threads.forEach { thread ->
                                    DropdownMenuItem(text = { Text(thread.title) }, onClick = { strategyThreadId = thread.id; expanded = false })
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        Button(
                            onClick = { onUpdate(currentItem()); onClose() },
                            colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Text("Save", fontSize = ResolveType.BodySmall)
                        }
                        Button(
                            onClick = { onCalendar(currentItem()) },
                            colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.InkSoft, contentColor = ResolveColors.Accent),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Icon(Icons.Filled.CalendarMonth, contentDescription = null, Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Calendar", fontSize = ResolveType.BodySmall)
                        }
                    }
                }
            }
        }
        item {
            DetailSectionTitle("Subtasks", "${subtasks.size}")
        }
        val subtaskRoots = subtasks.filter { it.parentItemId == item.id }
        val subtaskTree = flattenTodoTree(subtaskRoots, subtasks)
        items(subtaskTree, key = { it.item.id }) { entry ->
            SubtaskRow(item = entry.item, depth = entry.depth, onToggle = { onToggleSubtask(entry.item) })
        }
        item {
            Surface(color = ResolveColors.Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CompactInputField(
                        value = subtaskTitle,
                        onValueChange = { subtaskTitle = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = "Add subtask",
                        minHeight = 36.dp,
                        textStyle = TextStyle(
                            color = ResolveColors.Text,
                            fontSize = ResolveType.Body,
                            lineHeight = 17.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onAddSubtask(subtaskTitle)
                            subtaskTitle = ""
                        },
                        enabled = subtaskTitle.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { onArchive(currentItem()) }, colors = ButtonDefaults.textButtonColors(contentColor = ResolveColors.Muted)) {
                    Icon(Icons.Filled.Archive, contentDescription = null, Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Archive todo", fontSize = ResolveType.Caption)
                }
            }
        }
    }
}

@Composable
private fun BottomTabs(tab: Tab, onTab: (Tab) -> Unit) {
    Surface(
        color = ResolveColors.Surface,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .border(width = 1.dp, color = ResolveColors.Line.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(Tab.Todo, Tab.Calendar, Tab.Strategy, Tab.Settings).forEach { item ->
                val selected = tab == item
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onTab(item) }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Surface(
                        color = if (selected) ResolveColors.InkSoft else Color.Transparent,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = if (selected) 18.dp else 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (selected) ResolveColors.Text else ResolveColors.Secondary,
                        modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Text(
                        item.label,
                        color = if (selected) ResolveColors.Text else ResolveColors.Secondary,
                        fontSize = ResolveType.Caption,
                        lineHeight = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TodoDisclosureRow(title: String, count: Int, open: Boolean, onClick: () -> Unit) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ResolveColors.Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Text(title, color = ResolveColors.Secondary, fontSize = ResolveType.BodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Surface(color = ResolveColors.Pill, shape = RoundedCornerShape(999.dp)) {
                Text(count.toString(), color = ResolveColors.Muted, fontSize = ResolveType.Pill, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
            }
        }
    }
}

@Composable
private fun ArchiveDisclosureRow(count: Int, open: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            color = if (open) Color(0xFFF5F6F9) else Color.Transparent,
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Archive, contentDescription = null, tint = ResolveColors.Muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("Archive", color = ResolveColors.Muted, fontSize = ResolveType.Caption)
                if (count > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(count.toString(), color = ResolveColors.Muted, fontSize = ResolveType.Caption)
                }
            }
        }
    }
}

@Composable
private fun MetaPill(label: String, tone: String = "muted") {
    val background = when (tone) {
        "accent" -> Color(0xFFEAF2FF)
        "danger" -> Color(0xFFFFECEA)
        "soft" -> ResolveColors.InkSoft
        else -> ResolveColors.Pill
    }
    val content = when (tone) {
        "accent" -> ResolveColors.Accent
        "danger" -> ResolveColors.Danger
        "soft" -> Color(0xFF4D5B78)
        else -> ResolveColors.Secondary
    }
    Surface(color = background, shape = RoundedCornerShape(999.dp)) {
        Text(
            label,
            color = content,
            fontSize = ResolveType.Pill,
            lineHeight = 11.sp,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InlineNotice(message: String, onDismiss: () -> Unit) {
    Surface(color = Color(0xFFFFF6E8), shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 7.dp, end = 6.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                color = ResolveColors.Secondary,
                fontSize = ResolveType.Caption,
                lineHeight = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = ResolveColors.Muted, modifier = Modifier.size(14.dp))
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

private fun calendarEventArchived(event: CalendarEvent): Boolean =
    event.status in setOf("archived_locally", "local_pending_delete")

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

private fun flattenTodoTree(
    roots: List<ResolveItem>,
    pool: List<ResolveItem>
): List<TodoTreeEntry> {
    val childrenByParent = pool
        .filter { it.parentItemId != null }
        .groupBy { it.parentItemId }
    val result = mutableListOf<TodoTreeEntry>()

    fun visit(item: ResolveItem, depth: Int) {
        result += TodoTreeEntry(item, depth)
        childrenByParent[item.id]
            .orEmpty()
            .sortedWith(compareBy<ResolveItem> { it.status == ItemStatus.Done }.thenByDescending { it.createdAt })
            .forEach { visit(it, depth + 1) }
    }

    roots.forEach { visit(it, 0) }
    return result
}

private fun descendantsOf(items: List<ResolveItem>, parentId: String): List<ResolveItem> {
    val childrenByParent = items.groupBy { it.parentItemId }
    val result = mutableListOf<ResolveItem>()

    fun collect(id: String) {
        childrenByParent[id].orEmpty().forEach { child ->
            result += child
            collect(child.id)
        }
    }

    collect(parentId)
    return result
}

private fun calendarEventSourceLabel(event: CalendarEvent): String = when {
    event.provider == "feishu" && (event.status == "readonly" || !event.canEdit) -> "Feishu Calendar · Readonly"
    event.provider == "feishu" -> "Feishu Calendar"
    else -> "Local"
}

private fun calendarDeleteActionLabel(event: CalendarEvent): String = when {
    event.provider == "feishu" && event.canDelete -> "Delete"
    event.provider == "local" -> "Archive"
    else -> "Hide"
}

private fun relativeTime(instant: Instant): String {
    val minutes = ((System.currentTimeMillis() - instant.toEpochMilli()) / 60_000).coerceAtLeast(0)
    return if (minutes < 1) "just now" else "${minutes}m ago"
}

private object ResolveColors {
    val Bg = Color(0xFFF6F7FA)
    val Surface = Color(0xFFFFFFFF)
    val Pill = Color(0xFFF0F2F6)
    val InkSoft = Color(0xFFEAF2FF)
    val Line = Color(0xFFE0E3EA)
    val Text = Color(0xFF1C2430)
    val Secondary = Color(0xFF596274)
    val Muted = Color(0xFF929AAA)
    val Accent = Color(0xFF2F66DD)
    val Danger = Color(0xFFC7362F)
}

private object ResolveType {
    val PageTitle = 20.sp
    val DetailTitle = 17.sp
    val SectionTitle = 15.sp
    val CardTitle = 14.sp
    val Body = 13.sp
    val BodySmall = 12.sp
    val Caption = 10.sp
    val Pill = 9.sp
    val Micro = 8.sp
}
