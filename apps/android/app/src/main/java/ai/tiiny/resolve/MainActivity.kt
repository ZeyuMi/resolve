package ai.tiiny.resolve

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
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
    private var quickCaptureReloadTick by mutableStateOf(0)
    private var quickCaptureReceiverRegistered = false
    private val quickCaptureSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ResolveQuickCaptureNotification.actionSaved) {
                quickCaptureReloadTick += 1
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SyncWorker.cancel(this)
        ensureQuickCaptureNotification()
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        latestIntent = intent
        setContent {
            ResolveAndroidApp(
                initialCapture = sharedText,
                latestIntent = latestIntent,
                quickCaptureReloadTick = quickCaptureReloadTick,
                onIntentHandled = { latestIntent = null }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (!quickCaptureReceiverRegistered) {
            val filter = IntentFilter(ResolveQuickCaptureNotification.actionSaved)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(quickCaptureSavedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(quickCaptureSavedReceiver, filter)
            }
            quickCaptureReceiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        ensureQuickCaptureNotification()
        quickCaptureReloadTick += 1
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            quickCaptureReloadTick += 1
        }
    }

    override fun onStop() {
        if (quickCaptureReceiverRegistered) {
            unregisterReceiver(quickCaptureSavedReceiver)
            quickCaptureReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        latestIntent = intent
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == quickCaptureNotificationPermissionRequest &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            ResolveQuickCaptureNotification.show(this)
        }
    }

    private fun ensureQuickCaptureNotification() {
        if (ResolveQuickCaptureNotification.canPostNotifications(this)) {
            ResolveQuickCaptureNotification.show(this)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                quickCaptureNotificationPermissionRequest
            )
        }
    }

    private companion object {
        const val quickCaptureNotificationPermissionRequest = 4201
    }
}

private enum class Tab(
    val label: String,
    val icon: ImageVector
) {
    Todo("Todo", Icons.Filled.TaskAlt),
    Calendar("Calendar", Icons.Filled.CalendarMonth),
    Strategy("Strategy", Icons.Filled.Psychology),
    Vault("Vault", Icons.Filled.OpenInBrowser),
    Settings("Settings", Icons.Filled.Settings)
}

private data class TodoTreeEntry(
    val item: ResolveItem,
    val depth: Int,
    val childCount: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolveAndroidApp(
    initialCapture: String,
    latestIntent: Intent?,
    quickCaptureReloadTick: Int,
    onIntentHandled: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { ResolveRepository(context) }
    val secureVault = remember { SecureVault(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(repository.load()) }
    val quickCaptureEventVersion = ResolveQuickCaptureEvents.version
    var tab by rememberSaveable { mutableStateOf(Tab.Todo) }
    var capture by rememberSaveable { mutableStateOf(initialCapture) }
    var captureStrategyId by rememberSaveable { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedTodoId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedThreadId by rememberSaveable { mutableStateOf(state.threads.firstOrNull()?.id.orEmpty()) }
    var calendarDraft by remember { mutableStateOf(CalendarDraft()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var calendarViewMode by rememberSaveable { mutableStateOf(CalendarViewMode.Month) }
    var selectedCalendarEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    var editingCalendarEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    var expandedCalendarDate by remember { mutableStateOf<LocalDate?>(null) }
    var showCalendarDraft by rememberSaveable { mutableStateOf(false) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var openedStrategyThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    var showStrategyDraft by rememberSaveable { mutableStateOf(false) }
    var todoReturnStrategyThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var noteDraft by remember { mutableStateOf("") }
    var pendingNoteTask by remember { mutableStateOf<ResolveItem?>(null) }
    var pendingTodoArchive by remember { mutableStateOf<ResolveItem?>(null) }
    var pendingTodoDelete by remember { mutableStateOf<ResolveItem?>(null) }
    var pendingTodoArchiveClear by remember { mutableStateOf(false) }
    var pendingStrategyArchive by remember { mutableStateOf<StrategyThread?>(null) }
    var pendingCalendarDelete by remember { mutableStateOf<CalendarEvent?>(null) }
    var isSyncing by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var hasBackendSession by remember { mutableStateOf(secureVault.loadBackendSession() != null) }
    var syncSecretReady by remember { mutableStateOf(secureVault.loadSyncSecret() != null) }
    var applyingRemoteState by remember { mutableStateOf(false) }
    var localSaveJob by remember { mutableStateOf<Job?>(null) }
    var todoScrollToTopSignal by remember { mutableStateOf(0) }
    var todoKeepNearArchiveSignal by remember { mutableStateOf(0) }
    val todoListState = rememberLazyListState()
    val latestStateForDispose by rememberUpdatedState(state)

    fun queueLocalSave(next: ResolveState, delayMs: Long = 220L) {
        localSaveJob?.cancel()
        localSaveJob = scope.launch {
            delay(delayMs)
            withContext(Dispatchers.IO) {
                repository.save(next)
            }
        }
    }

    fun persist(next: ResolveState, delayMs: Long = 220L) {
        state = next
        queueLocalSave(next, delayMs)
    }

    fun persistLatest(delayMs: Long = 220L, transform: (ResolveState) -> ResolveState) {
        persist(transform(state), delayMs)
    }

    fun applyPendingQuickCaptures() {
        val hadPendingQuickCaptures = repository.hasPendingQuickCaptures()
        localSaveJob?.cancel()
        val loaded = repository.load()
        val currentById = state.items.associateBy { it.id }
        val notificationItems = loaded.items
            .filter { it.source == "android_notification" && it.title.isNotBlank() }
            .map { currentById[it.id] ?: it }
            .sortedByDescending { it.createdAt }
        if (notificationItems.isEmpty()) {
            if (hadPendingQuickCaptures) {
                repository.clearPendingQuickCaptures()
            }
            return
        }
        val currentWithoutNotificationCaptures = state.items.filter { it.source != "android_notification" }
        val next = state.copy(items = notificationItems + currentWithoutNotificationCaptures)
        state = next
        repository.save(next)
        repository.clearPendingQuickCaptures()
        todoScrollToTopSignal += 1
    }

    fun openNote(note: MarkdownNote) {
        selectedNoteId = note.id
        noteDraft = repository.readNoteBody(note)
        tab = Tab.Vault
    }

    fun openNoteForTask(item: ResolveItem) {
        val existing = item.noteId?.let { noteId -> state.notes.find { it.id == noteId && it.status != "archived" } }
        if (existing != null) {
            openNote(existing)
        } else {
            pendingNoteTask = item
        }
    }

    fun createNoteForTask(item: ResolveItem) {
        val strategyTitle = item.strategyThreadId?.let { threadId ->
            state.threads.find { it.id == threadId }?.title
        }
        val (note, _) = repository.createNoteForTask(item, strategyTitle)
        val now = Instant.now()
        val nextItem = item.copy(noteId = note.id, updatedAt = now)
        val next = state.copy(
            items = state.items.map { if (it.id == item.id) nextItem else it },
            notes = listOf(note) + state.notes
        )
        persist(next)
        noteDraft = repository.readNoteBody(note)
        selectedNoteId = note.id
        pendingNoteTask = null
        tab = Tab.Vault
    }

    fun saveSelectedNote(title: String? = null) {
        val note = state.notes.find { it.id == selectedNoteId } ?: return
        val now = Instant.now()
        val renamedNote = title?.let { repository.renameNote(note, it, noteDraft) } ?: note
        val nextNote = renamedNote.copy(markdown = noteDraft, updatedAt = now)
        repository.writeNoteBody(nextNote, noteDraft)
        persist(state.copy(notes = state.notes.map { if (it.id == note.id) nextNote else it }))
    }

    fun renameSelectedNote(title: String) {
        val note = state.notes.find { it.id == selectedNoteId } ?: return
        val nextNote = repository.renameNote(note, title, noteDraft)
        persist(state.copy(notes = state.notes.map { if (it.id == note.id) nextNote else it }))
    }

    fun archiveSelectedNote(note: MarkdownNote) {
        val nextStatus = if (note.status == "archived") "active" else "archived"
        persist(state.copy(notes = state.notes.map {
            if (it.id == note.id) it.copy(status = nextStatus, updatedAt = Instant.now()) else it
        }))
    }

    DisposableEffect(Unit) {
        onDispose {
            localSaveJob?.cancel()
            repository.save(latestStateForDispose)
        }
    }

    suspend fun backendSession(forceRefresh: Boolean = false): BackendSession {
        val settings = state.backendSettings
        var session = secureVault.loadBackendSession() ?: error("Sign in to Resolve backend first.")
        if (forceRefresh || session.shouldRefresh()) {
            val client = BackendClient(settings, session)
            session = withContext(Dispatchers.IO) { client.refreshSession() }
            secureVault.saveBackendSession(session.accessToken, session.refreshToken, session.expiresAtEpochMillis)
            hasBackendSession = true
        }
        return session
    }

    suspend fun connectedBackendClient(forceRefresh: Boolean = false): BackendClient {
        val settings = state.backendSettings
        val session = backendSession(forceRefresh)
        val client = BackendClient(settings, session)
        return client
    }

    suspend fun <T> withBackendClient(operation: suspend (BackendClient) -> T): T {
        return try {
            operation(connectedBackendClient())
        } catch (error: Throwable) {
            if (!error.isBackendJwtExpired()) throw error
            operation(connectedBackendClient(forceRefresh = true))
        }
    }

    suspend fun syncAppStateWithCloud(pushAfterPull: Boolean = true, includeCalendarEvents: Boolean = true) {
        suspend fun runSync(forceRefresh: Boolean) {
            if (secureVault.loadBackendSession() == null) return
            val syncSecret = secureVault.loadSyncSecret() ?: return
            if (state.backendSettings.email.isBlank()) return
            val localSnapshot = repository.withCurrentNoteBodies(state)
            val session = backendSession(forceRefresh)
            val client = AppSyncClient(localSnapshot.backendSettings, session, syncSecret)
            val changedSince = repository.loadAppSyncCursor()
            val cursorAfterSync = Instant.now()
            val remote = withContext(Dispatchers.IO) {
                client.pullState(includeCalendarEvents = includeCalendarEvents, changedSince = changedSince)
            }
            // A sync request can finish after the user has already added or edited local
            // tasks. Merge into the latest in-memory state so stale snapshots never hide
            // fresh local work until the next manual sync.
            val merged = mergeEncryptedRemoteState(localSnapshot, remote)
            withContext(Dispatchers.IO) {
                repository.materializeNotes(merged.notes)
            }
            applyingRemoteState = true
            persist(merged)
            applyingRemoteState = false
            if (pushAfterPull) {
                withContext(Dispatchers.IO) {
                    client.pushState(merged, changedSince = changedSince)
                    repository.saveAppSyncCursor(cursorAfterSync)
                }
            }
        }

        try {
            runSync(forceRefresh = false)
        } catch (error: Throwable) {
            applyingRemoteState = false
            if (!error.isBackendJwtExpired()) throw error
            runSync(forceRefresh = true)
        }
    }

    fun pullAppStateFromCloud() {
        scope.launch {
            runCatching {
                syncAppStateWithCloud(pushAfterPull = false, includeCalendarEvents = false)
            }.onFailure {
                applyingRemoteState = false
            }
        }
    }

    fun deleteRemoteItems(itemIds: Collection<String>) {
        if (secureVault.loadBackendSession() == null || secureVault.loadSyncSecret() == null || state.backendSettings.email.isBlank()) return
        scope.launch {
            runCatching {
                suspend fun runDelete(forceRefresh: Boolean) {
                    val syncSecret = secureVault.loadSyncSecret() ?: return
                    val session = backendSession(forceRefresh)
                    withContext(Dispatchers.IO) {
                        AppSyncClient(state.backendSettings, session, syncSecret).deleteRemoteItems(itemIds)
                    }
                }
                try {
                    runDelete(forceRefresh = false)
                } catch (error: Throwable) {
                    if (!error.isBackendJwtExpired()) throw error
                    runDelete(forceRefresh = true)
                }
            }
        }
    }

    fun updateItem(item: ResolveItem) {
        val timestamp = Instant.now()
        persist(
            state.copy(
                items = state.items.map { existing ->
                    if (existing.id != item.id) {
                        existing
                    } else {
                        item.copy(
                            updatedAt = timestamp,
                            statusChangedAt = if (item.status != existing.status) timestamp else item.statusChangedAt
                        )
                    }
                }
            )
        )
    }

    fun archiveItem(item: ResolveItem) {
        val archivedIds = descendantsOf(state.items, item.id).map { it.id }.toMutableSet()
        archivedIds += item.id
        val timestamp = Instant.now()
        persist(
            state.copy(
                items = state.items.map { candidate ->
                    if (candidate.id in archivedIds) {
                        candidate.copy(
                            status = ItemStatus.Archived,
                            updatedAt = timestamp,
                            statusChangedAt = timestamp
                        )
                    } else {
                        candidate
                    }
                }
            )
        )
        if (selectedTodoId == item.id) selectedTodoId = null
        notice = null
    }

    fun archiveStrategyThread(thread: StrategyThread) {
        val archivedIds = state.items
            .filter { it.type == ItemType.Task && it.strategyThreadId == thread.id }
            .map { it.id }
            .toMutableSet()
        archivedIds.toList().forEach { rootId ->
            descendantsOf(state.items, rootId).forEach { archivedIds += it.id }
        }
        val timestamp = Instant.now()
        persist(
            state.copy(
                threads = state.threads.map { candidate ->
                    if (candidate.id == thread.id) candidate.copy(status = "archived", updatedAt = timestamp) else candidate
                },
                items = state.items.map { item ->
                    if (item.id in archivedIds) {
                        item.copy(
                            status = ItemStatus.Archived,
                            updatedAt = timestamp,
                            statusChangedAt = timestamp
                        )
                    } else {
                        item
                    }
                }
            )
        )
        if (openedStrategyThreadId == thread.id) openedStrategyThreadId = null
        if (selectedThreadId == thread.id) {
            selectedThreadId = state.threads
                .firstOrNull { it.id != thread.id && !it.status.equals("archived", ignoreCase = true) }
                ?.id
                .orEmpty()
        }
        selectedTodoId = null
        notice = null
    }

    fun deleteItemPermanently(item: ResolveItem) {
        val deletedIds = descendantsOf(state.items, item.id).map { it.id }.toMutableSet()
        deletedIds += item.id
        val timestamp = Instant.now()
        persist(
            state.copy(
                items = state.items.map { candidate ->
                    if (candidate.id in deletedIds) {
                        candidate.copy(deletedAt = timestamp, updatedAt = timestamp)
                    } else {
                        candidate
                    }
                }
            ),
            delayMs = 0L
        )
        todoKeepNearArchiveSignal += 1
        deleteRemoteItems(deletedIds)
        if (selectedTodoId == item.id) selectedTodoId = null
        notice = null
    }

    fun clearArchivedItems() {
        val archivedIds = state.items
            .filter { it.status == ItemStatus.Archived && it.deletedAt == null }
            .map { it.id }
            .toMutableSet()
        archivedIds.toList().forEach { rootId ->
            descendantsOf(state.items, rootId).forEach { archivedIds += it.id }
        }
        val timestamp = Instant.now()
        persist(
            state.copy(
                items = state.items.map { candidate ->
                    if (candidate.id in archivedIds) {
                        candidate.copy(deletedAt = timestamp, updatedAt = timestamp)
                    } else {
                        candidate
                    }
                }
            ),
            delayMs = 0L
        )
        todoKeepNearArchiveSignal += 1
        deleteRemoteItems(archivedIds)
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

    fun reorderTodo(sourceId: String, targetId: String) {
        if (sourceId == targetId) return
        val source = state.items.find { it.id == sourceId && it.deletedAt == null } ?: return
        val target = state.items.find { it.id == targetId && it.deletedAt == null } ?: return
        if (
            source.type != ItemType.Task ||
            target.type != ItemType.Task ||
            source.status != ItemStatus.Active ||
            target.status != ItemStatus.Active ||
            source.parentItemId != target.parentItemId ||
            source.strategyThreadId != target.strategyThreadId
        ) {
            return
        }
        val siblings = state.items
            .filter {
                it.type == ItemType.Task &&
                    it.deletedAt == null &&
                    it.status == ItemStatus.Active &&
                    it.parentItemId == source.parentItemId &&
                    it.strategyThreadId == source.strategyThreadId
            }
            .sortedWith(todoComparator)
            .toMutableList()
        val from = siblings.indexOfFirst { it.id == sourceId }
        val to = siblings.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0 || from == to) return
        val moved = siblings.removeAt(from)
        siblings.add(to, moved)
        val timestamp = Instant.now()
        val nextOrder = siblings.mapIndexed { index, item -> item.id to (index + 1).toDouble() }.toMap()
        persist(
            state.copy(
                items = state.items.map { item ->
                    nextOrder[item.id]?.let { order -> item.copy(sortOrder = order, updatedAt = timestamp) } ?: item
                }
            )
        )
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
        val timestamp = Instant.now()
        val item = ResolveItem(
            title = text,
            createdAt = timestamp,
            updatedAt = timestamp,
            statusChangedAt = timestamp,
            strategyThreadId = captureStrategyId.ifBlank { null },
            sortOrder = -timestamp.toEpochMilli().toDouble()
        )
        persist(state.copy(items = listOf(item) + state.items))
        capture = ""
        tab = Tab.Todo
        todoScrollToTopSignal += 1
        notice = null
    }

    suspend fun syncBackendCalendar() {
        suspend fun runCalendarSync(forceRefresh: Boolean) {
            val client = connectedBackendClient(forceRefresh)
            val syncedAt = withContext(Dispatchers.IO) { client.syncFeishuNow() }
            val remoteEvents = withContext(Dispatchers.IO) { client.listEvents(state.feishuSettings) }
            persistLatest { current ->
                current.copy(
                    calendarEvents = mergeBackendCalendarEvents(current.calendarEvents, remoteEvents),
                    backendSettings = current.backendSettings.copy(
                        status = BackendStatus.Connected,
                        feishuConnected = true,
                        lastSyncedAt = syncedAt,
                        lastError = null
                    ),
                    feishuSettings = current.feishuSettings.copy(
                        status = FeishuStatus.Connected,
                        lastSyncedAt = syncedAt,
                        lastError = null
                    )
                )
            }
        }

        try {
            runCalendarSync(forceRefresh = false)
        } catch (error: Throwable) {
            if (!error.isBackendJwtExpired()) throw error
            runCalendarSync(forceRefresh = true)
        }
    }

    fun syncFeishu() {
        if (isSyncing) return
        isSyncing = true
        scope.launch {
            var appSyncBlocker: String? = null
            try {
                if (!syncSecretReady && secureVault.loadSyncSecret() != null) {
                    syncSecretReady = true
                }
                val shouldSyncAppState = hasBackendSession && state.backendSettings.email.isNotBlank()
                appSyncBlocker = if (shouldSyncAppState && !syncSecretReady) "Sign in again to unlock Todo sync" else null
                if (shouldSyncAppState && syncSecretReady) {
                    syncAppStateWithCloud(pushAfterPull = true, includeCalendarEvents = false)
                } else if (appSyncBlocker != null) {
                    patchBackend(
                        state.backendSettings.copy(
                            status = BackendStatus.Connected,
                            lastError = appSyncBlocker
                        )
                    )
                    notice = appSyncBlocker
                }
                if (hasBackendSession && state.backendSettings.supabaseUrl.isNotBlank() && state.backendSettings.feishuConnected) {
                    syncBackendCalendar()
                    notice = null
                } else {
                    notice = null
                }
                if (appSyncBlocker != null) {
                    patchBackend(
                        state.backendSettings.copy(
                            status = BackendStatus.Connected,
                            lastError = appSyncBlocker
                        )
                    )
                    notice = appSyncBlocker
                }
            } catch (error: Throwable) {
                if (error.needsCalendarAuthorization()) {
                    persistLatest { current ->
                        current.copy(
                            backendSettings = current.backendSettings.copy(
                                status = BackendStatus.Connected,
                                feishuConnected = false,
                                lastError = "Calendar needs attention"
                            ),
                            feishuSettings = current.feishuSettings.copy(
                                status = FeishuStatus.NotConnected,
                                lastError = "Calendar needs attention"
                            )
                        )
                    }
                    notice = null
                } else {
                    if (error.isTransientBackendError()) {
                        if (hasBackendSession) {
                            patchBackend(
                                state.backendSettings.copy(
                                    status = BackendStatus.Connected,
                                    lastError = "Calendar is retrying in the background"
                                )
                            )
                        }
                        patchFeishu(
                            state.feishuSettings.copy(
                                status = FeishuStatus.Connected,
                                lastError = "Calendar is retrying in the background"
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
                }
            } finally {
                isSyncing = false
            }
        }
    }

    fun createCalendarEvent(draft: CalendarDraft) {
        if (draft.title.isBlank()) return
        val strategyTitle = draft.strategyThreadId?.let { id -> state.threads.find { it.id == id }?.title }
        val enrichedDraft = draft.copy(
            description = calendarDescriptionWithStrategy(draft.description, strategyTitle)
        )
        val startsAt = enrichedDraft.date.atTime(enrichedDraft.time).atZone(ZoneId.systemDefault()).toInstant()
        val localEvent = CalendarEvent(
            title = enrichedDraft.title.trim(),
            description = enrichedDraft.description.trim(),
            status = if (hasBackendSession || state.feishuSettings.status == FeishuStatus.Connected) "local_pending_create" else "local",
            startsAt = startsAt,
            endsAt = startsAt.plusSeconds(3600),
            sourceItemId = enrichedDraft.sourceItemId,
            strategyThreadId = enrichedDraft.strategyThreadId
        )
        persist(state.copy(calendarEvents = (state.calendarEvents + localEvent).sortedBy { it.startsAt }))
        calendarDraft = CalendarDraft(date = enrichedDraft.date, time = enrichedDraft.time.plusHours(1))
        notice = null

        if (hasBackendSession && state.backendSettings.feishuConnected) {
            scope.launch {
                try {
                    val remote = withBackendClient { client ->
                        withContext(Dispatchers.IO) { client.createEvent(enrichedDraft) }
                    }
                    val syncedAt = Instant.now()
                    persistLatest { current ->
                        current.copy(
                            calendarEvents = current.calendarEvents
                                .filterNot { it.id == localEvent.id }
                                .plus(remote)
                                .sortedBy { it.startsAt },
                            backendSettings = current.backendSettings.copy(lastSyncedAt = syncedAt, lastError = null),
                            feishuSettings = current.feishuSettings.copy(status = FeishuStatus.Connected, lastSyncedAt = syncedAt, lastError = null)
                        )
                    }
                    notice = null
                } catch (error: Throwable) {
                    if (error.needsCalendarAuthorization()) {
                        persistLatest { current ->
                            current.copy(
                                backendSettings = current.backendSettings.copy(
                                    status = BackendStatus.Connected,
                                    feishuConnected = false,
                                    lastError = "Calendar needs attention"
                                ),
                                feishuSettings = current.feishuSettings.copy(
                                    status = FeishuStatus.NotConnected,
                                    lastError = "Calendar needs attention"
                                )
                            )
                        }
                        notice = null
                    } else {
                        if (error.isTransientBackendError()) {
                            patchBackend(
                                state.backendSettings.copy(
                                    status = BackendStatus.Connected,
                                    lastError = "Calendar is retrying in the background"
                                )
                            )
                            notice = null
                        } else {
                            patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                            notice = "Saved locally; sync failed${error.message?.let { ": $it" }.orEmpty()}"
                        }
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
                    val remote = withBackendClient { client ->
                        withContext(Dispatchers.IO) { client.updateEvent(event, draft) }
                    }
                    val syncedAt = Instant.now()
                    persistLatest { current ->
                        current.copy(
                            calendarEvents = replaceCalendarEvent(current.calendarEvents, event, remote),
                            backendSettings = current.backendSettings.copy(lastSyncedAt = syncedAt, lastError = null),
                            feishuSettings = current.feishuSettings.copy(status = FeishuStatus.Connected, lastSyncedAt = syncedAt, lastError = null)
                        )
                    }
                    selectedCalendarEvent = remote
                } catch (error: Throwable) {
                    if (error.needsCalendarAuthorization()) {
                        persistLatest { current ->
                            current.copy(
                                backendSettings = current.backendSettings.copy(
                                    status = BackendStatus.Connected,
                                    feishuConnected = false,
                                    lastError = "Calendar needs attention"
                                ),
                                feishuSettings = current.feishuSettings.copy(
                                    status = FeishuStatus.NotConnected,
                                    lastError = "Calendar needs attention"
                                )
                            )
                        }
                        notice = null
                    } else {
                        if (error.isTransientBackendError()) {
                            patchBackend(
                                state.backendSettings.copy(
                                    status = BackendStatus.Connected,
                                    lastError = "Calendar is retrying in the background"
                                )
                            )
                            notice = null
                        } else {
                            notice = "Saved locally; Feishu update failed${error.message?.let { ": $it" }.orEmpty()}"
                            patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                        }
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
                val syncedAt = withBackendClient { client ->
                    withContext(Dispatchers.IO) { client.deleteEvent(event) }
                }
                persistLatest { current ->
                    current.copy(
                        calendarEvents = replaceCalendarEvent(current.calendarEvents, hiddenEvent, hiddenEvent.copy(status = "remote_deleted")),
                        backendSettings = current.backendSettings.copy(lastSyncedAt = syncedAt, lastError = null),
                        feishuSettings = current.feishuSettings.copy(status = FeishuStatus.Connected, lastSyncedAt = syncedAt, lastError = null)
                    )
                }
            } catch (error: Throwable) {
                if (error.needsCalendarAuthorization()) {
                    persistLatest { current ->
                        current.copy(
                            backendSettings = current.backendSettings.copy(
                                status = BackendStatus.Connected,
                                feishuConnected = false,
                                lastError = "Calendar needs attention"
                            ),
                            feishuSettings = current.feishuSettings.copy(
                                status = FeishuStatus.NotConnected,
                                lastError = "Calendar needs attention"
                            )
                        )
                    }
                } else {
                    if (error.isTransientBackendError()) {
                        persistLatest { current ->
                            current.copy(
                                calendarEvents = replaceCalendarEvent(current.calendarEvents, hiddenEvent, hiddenEvent),
                                backendSettings = current.backendSettings.copy(
                                    status = BackendStatus.Connected,
                                    lastError = "Calendar is retrying in the background"
                                ),
                                feishuSettings = current.feishuSettings.copy(lastError = "Calendar is retrying in the background")
                            )
                        }
                    } else {
                        persistLatest { current ->
                            current.copy(
                                calendarEvents = replaceCalendarEvent(current.calendarEvents, hiddenEvent, event.copy(status = "error")),
                                backendSettings = current.backendSettings.copy(status = BackendStatus.Error, lastError = error.message),
                                feishuSettings = current.feishuSettings.copy(lastError = error.message)
                            )
                        }
                    }
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
        scope.launch {
            try {
                val oauth = withBackendClient { client ->
                    withContext(Dispatchers.IO) { client.startFeishuOAuth() }
                }
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(oauth.authorizeUrl)))
                patchBackend(state.backendSettings.copy(status = BackendStatus.Connected, feishuConnected = false, lastError = null))
                patchFeishu(state.feishuSettings.copy(status = FeishuStatus.NotConnected, lastError = null))
                notice = null

                repeat(30) {
                    delay(3_000)
                    val status = runCatching {
                        withBackendClient { client -> withContext(Dispatchers.IO) { client.status() } }
                    }.getOrNull()
                    if (status?.connected == true) {
                        persistLatest { current ->
                            current.copy(
                                backendSettings = current.backendSettings.copy(
                                    status = BackendStatus.Connected,
                                    feishuConnected = true,
                                    lastSyncedAt = status.lastServerSyncAt,
                                    lastError = null
                                ),
                                feishuSettings = current.feishuSettings.copy(status = FeishuStatus.Connected, lastError = null)
                            )
                        }
                        tab = Tab.Calendar
                        notice = null
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
            try {
                val session = withContext(Dispatchers.IO) {
                    BackendClient.signInWithPassword(state.backendSettings, password)
                }
                secureVault.saveBackendSession(session.accessToken, session.refreshToken, session.expiresAtEpochMillis)
                secureVault.saveSyncSecret(password)
                hasBackendSession = true
                syncSecretReady = true
                val client = BackendClient(state.backendSettings, session)
                val connectorStatus = runCatching {
                    withContext(Dispatchers.IO) { client.status() }
                }.getOrNull()
                val calendarConnected = connectorStatus?.connected == true
                val calendarNeedsAuth = connectorStatus?.needsAuthorization == true || connectorStatus?.status == "needs_auth"
                persistLatest { current ->
                    current.copy(
                        backendSettings = current.backendSettings.copy(
                            status = BackendStatus.Connected,
                            feishuConnected = calendarConnected,
                            lastSyncedAt = connectorStatus?.lastServerSyncAt ?: current.backendSettings.lastSyncedAt,
                            lastError = if (calendarNeedsAuth) "Calendar needs attention" else null
                        ),
                        feishuSettings = current.feishuSettings.copy(
                            status = if (calendarConnected) FeishuStatus.Connected else FeishuStatus.NotConnected,
                            lastSyncedAt = connectorStatus?.lastServerSyncAt ?: current.feishuSettings.lastSyncedAt,
                            lastError = if (calendarNeedsAuth) "Calendar needs attention" else null
                        )
                    )
                }
                notice = null
            } catch (error: Throwable) {
                patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                notice = "Sign in failed${error.message?.let { ": $it" }.orEmpty()}"
            } finally {
                isConnecting = false
            }
        }
    }

    fun connectBackendFeishu() {
        startBackendFeishuAuth()
    }

    fun disconnectBackend() {
        secureVault.clearBackendSession()
        secureVault.clearSyncSecret()
        hasBackendSession = false
        syncSecretReady = false
        patchBackend(state.backendSettings.copy(status = BackendStatus.SignedOut, feishuConnected = false, lastError = null))
        notice = null
    }

    LaunchedEffect(quickCaptureReloadTick, quickCaptureEventVersion) {
        if (quickCaptureReloadTick > 0 || quickCaptureEventVersion > 0) {
            applyPendingQuickCaptures()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(900)
            if (repository.hasPendingQuickCaptures()) {
                applyPendingQuickCaptures()
            }
        }
    }

    LaunchedEffect(hasBackendSession) {
        if (hasBackendSession) {
            runCatching {
                val status = withBackendClient { client -> withContext(Dispatchers.IO) { client.status() } }
                val calendarNeedsAuth = status.needsAuthorization || status.status == "needs_auth"
                persistLatest { current ->
                    current.copy(
                        backendSettings = current.backendSettings.copy(
                            status = BackendStatus.Connected,
                            feishuConnected = status.connected,
                            lastSyncedAt = status.lastServerSyncAt ?: current.backendSettings.lastSyncedAt,
                            lastError = if (calendarNeedsAuth) "Calendar needs attention" else null
                        ),
                        feishuSettings = current.feishuSettings.copy(
                            status = if (status.connected) FeishuStatus.Connected else FeishuStatus.NotConnected,
                            lastSyncedAt = status.lastServerSyncAt ?: current.feishuSettings.lastSyncedAt,
                            lastError = if (calendarNeedsAuth) "Calendar needs attention" else null
                        )
                    )
                }
            }.onFailure { error ->
                if (error !is CancellationException) {
                    patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                }
            }
        }
    }

    LaunchedEffect(latestIntent) {
        if (latestIntent?.getBooleanExtra(ResolveQuickCaptureNotification.extraReloadState, false) == true) {
            applyPendingQuickCaptures()
            tab = Tab.Todo
            selectedTodoId = null
            onIntentHandled()
            return@LaunchedEffect
        }

        latestIntent?.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { sharedText ->
            capture = sharedText
            tab = Tab.Todo
            onIntentHandled()
            return@LaunchedEffect
        }

        val uri = latestIntent?.data
        if (uri?.scheme == "resolve" && uri.host == "oauth" && uri.path == "/feishu") {
            tab = Tab.Calendar
            notice = null
            onIntentHandled()
            if (hasBackendSession) {
                scope.launch {
                    try {
                        val status = withBackendClient { client -> withContext(Dispatchers.IO) { client.status() } }
                        val calendarNeedsAuth = status.needsAuthorization || status.status == "needs_auth"
                        persistLatest { current ->
                            current.copy(
                                backendSettings = current.backendSettings.copy(
                                    status = BackendStatus.Connected,
                                    feishuConnected = status.connected,
                                    lastSyncedAt = status.lastServerSyncAt ?: current.backendSettings.lastSyncedAt,
                                    lastError = if (calendarNeedsAuth) "Calendar needs attention" else null
                                ),
                                feishuSettings = current.feishuSettings.copy(
                                    status = if (status.connected) FeishuStatus.Connected else FeishuStatus.NotConnected,
                                    lastSyncedAt = status.lastServerSyncAt ?: current.feishuSettings.lastSyncedAt,
                                    lastError = if (calendarNeedsAuth) "Calendar needs attention" else null
                                )
                            )
                        }
                        notice = null
                    } catch (error: Throwable) {
                        if (error.needsCalendarAuthorization()) {
                            persistLatest { current ->
                                current.copy(
                                    backendSettings = current.backendSettings.copy(
                                        status = BackendStatus.Connected,
                                        feishuConnected = false,
                                        lastError = "Calendar needs attention"
                                    ),
                                    feishuSettings = current.feishuSettings.copy(
                                        status = FeishuStatus.NotConnected,
                                        lastError = "Calendar needs attention"
                                    )
                                )
                            }
                            notice = null
                        } else {
                            if (error.isTransientBackendError()) {
                                patchBackend(
                                    state.backendSettings.copy(
                                        status = BackendStatus.Connected,
                                        lastError = "Calendar is retrying in the background"
                                    )
                                )
                                notice = null
                            } else {
                                patchBackend(state.backendSettings.copy(status = BackendStatus.Error, lastError = error.message))
                                notice = "Calendar sync failed${error.message?.let { ": $it" }.orEmpty()}"
                            }
                        }
                    }
                }
            }
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
            pendingStrategyArchive != null ||
            pendingCalendarDelete != null ||
            selectedTodoId != null ||
            (tab == Tab.Calendar && (editingCalendarEvent != null || selectedCalendarEvent != null || showCalendarDraft || expandedCalendarDate != null)) ||
            (tab == Tab.Strategy && (showStrategyDraft || openedStrategyThreadId != null)) ||
            (tab == Tab.Vault && selectedNoteId != null)

    fun navigateBack() {
        when {
            pendingTodoArchive != null -> pendingTodoArchive = null
            pendingTodoDelete != null -> pendingTodoDelete = null
            pendingTodoArchiveClear -> pendingTodoArchiveClear = false
            pendingStrategyArchive != null -> pendingStrategyArchive = null
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
            tab == Tab.Vault && selectedNoteId != null -> selectedNoteId = null
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
            (tab == Tab.Strategy && (openedStrategyThreadId != null || showStrategyDraft)) ||
            (tab == Tab.Vault && selectedNoteId != null)

    ResolveTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ResolveColors.Backdrop)
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    if (!isFullPageDetail) {
                        TopHeader(
                            title = tab.label,
                            settings = state.feishuSettings,
                            backend = state.backendSettings,
                            isSyncing = isSyncing,
                            todoSyncLocked = tab != Tab.Calendar && hasBackendSession && !syncSecretReady,
                            compact = tab == Tab.Calendar,
                            showSettings = false,
                            onSync = { syncFeishu() },
                            onSettings = { tab = Tab.Settings }
                        )
                    }
                },
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
                            containerColor = ResolveColors.Strategy,
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
                        vertical = if (tab == Tab.Calendar) 2.dp else 10.dp
                    )
            ) {
                notice?.let {
                    InlineNotice(message = it, onDismiss = { notice = null })
                    Spacer(Modifier.height(if (tab == Tab.Calendar) 4.dp else 10.dp))
                }
                if (tab == Tab.Todo && selectedTodoId == null) {
                    CaptureBox(
                        value = capture,
                        onChange = { capture = it },
                        threads = state.threads.filter { !it.status.equals("archived", ignoreCase = true) }.sortedWith(strategyThreadComparator),
                        selectedThreadId = captureStrategyId,
                        onThread = { captureStrategyId = it },
                        onSave = { saveCapture() }
                    )
                    Spacer(Modifier.height(12.dp))
                }
                val pageKey = listOf(
                    tab.name,
                    selectedTodoId.orEmpty(),
                    selectedCalendarEvent?.id.orEmpty(),
                    editingCalendarEvent?.id.orEmpty(),
                    showCalendarDraft.toString(),
                    expandedCalendarDate?.toString().orEmpty(),
                    openedStrategyThreadId.orEmpty(),
                    showStrategyDraft.toString(),
                    selectedNoteId.orEmpty()
                ).joinToString(":")
                Box(
                    Modifier
                        .weight(1f)
                        .swipeBack(enabled = canNavigateBack(), onBack = { navigateBack() })
                ) {
                    Crossfade(targetState = pageKey, animationSpec = tween(180), label = "resolve-page") {
                    when (tab) {
                        Tab.Todo -> {
                            val selectedTodo = state.items.find { it.id == selectedTodoId && it.deletedAt == null }
                            if (selectedTodo != null) {
                                TodoDetailPage(
                                    item = selectedTodo,
                                    threads = state.threads,
                                    subtasks = descendantsOf(state.items, selectedTodo.id).filter { it.deletedAt == null },
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
                                    },
                                    onNote = { openNoteForTask(it) }
                                )
                            } else {
                                TodoScreen(
                                    state = state,
                                    listState = todoListState,
                                    showCompleted = showCompleted,
                                    showArchived = showArchived,
                                    scrollToTopSignal = todoScrollToTopSignal,
                                    keepNearArchiveSignal = todoKeepNearArchiveSignal,
                                    onToggleDone = { item ->
                                        updateItem(item.copy(status = if (item.status == ItemStatus.Done) ItemStatus.Active else ItemStatus.Done))
                                    },
                                    onRestore = { item -> updateItem(item.copy(status = ItemStatus.Active)) },
                                    onArchive = { pendingTodoArchive = it },
                                    onSelect = { selectedTodoId = it.id },
                                    onShowCompleted = { showCompleted = !showCompleted },
                                    onShowArchived = { showArchived = !showArchived },
                                    onDeleteArchived = { pendingTodoDelete = it },
                                    onClearArchived = { pendingTodoArchiveClear = true },
                                    onReorder = { sourceId, targetId -> reorderTodo(sourceId, targetId) }
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
                            onArchiveTodo = { pendingTodoArchive = it },
                            onArchiveThread = { pendingStrategyArchive = it }
                        )

                        Tab.Vault -> VaultScreen(
                            state = state,
                            selectedNoteId = selectedNoteId,
                            noteDraft = noteDraft,
                            onSelectNote = { openNote(it) },
                            onNoteDraft = { noteDraft = it },
                            onSave = { saveSelectedNote(it) },
                            onArchive = { note -> archiveSelectedNote(note) },
                            onCloseNote = { selectedNoteId = null }
                        )

                        Tab.Settings -> SettingsScreen(
                            state = state,
                            isConnecting = isConnecting,
                            isSyncing = isSyncing,
                            syncSecretReady = syncSecretReady,
                            onBackendSettings = { patchBackend(it) },
                            onBackendSignIn = { signInBackend(it) },
                            onBackendDisconnect = { disconnectBackend() },
                            onBackendFeishuConnect = { connectBackendFeishu() }
                        )
                    }
                    }
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
        pendingStrategyArchive?.let { thread ->
            ConfirmActionDialog(
                title = "Archive strategy?",
                message = "This will also archive its subtasks.",
                confirmLabel = "Archive",
                danger = false,
                onDismiss = { pendingStrategyArchive = null },
                onConfirm = {
                    archiveStrategyThread(thread)
                    pendingStrategyArchive = null
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
        pendingNoteTask?.let { item ->
            ConfirmActionDialog(
                title = "Create Note?",
                message = item.title,
                confirmLabel = "Create",
                danger = false,
                onDismiss = { pendingNoteTask = null },
                onConfirm = { createNoteForTask(item) }
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
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSize = this.size
            val radius = canvasSize.minDimension * 0.36f
            val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
            drawCircle(
                brush = Brush.linearGradient(
                    listOf(Color(0xFF7DB2FF), Color(0xFF2F66DD), Color(0xFF1E38C8))
                ),
                radius = radius,
                center = center,
                alpha = 0.94f
            )
            drawOval(
                color = Color.White.copy(alpha = 0.22f),
                topLeft = Offset(canvasSize.width * 0.28f, canvasSize.height * 0.22f),
                size = Size(canvasSize.width * 0.42f, canvasSize.height * 0.16f)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.24f),
                radius = radius * 0.95f,
                center = center,
                style = Stroke(width = canvasSize.minDimension * 0.022f)
            )
            val check = Path().apply {
                moveTo(canvasSize.width * 0.335f, canvasSize.height * 0.512f)
                lineTo(canvasSize.width * 0.456f, canvasSize.height * 0.632f)
                lineTo(canvasSize.width * 0.682f, canvasSize.height * 0.384f)
            }
            drawPath(
                path = check,
                color = Color.White,
                style = Stroke(
                    width = canvasSize.width * 0.092f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            drawPath(
                path = check,
                color = Color(0xFFDCEBFF).copy(alpha = 0.42f),
                style = Stroke(
                    width = canvasSize.width * 0.036f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopHeader(
    title: String,
    settings: FeishuSettings,
    backend: BackendSettings,
    isSyncing: Boolean,
    todoSyncLocked: Boolean = false,
    compact: Boolean = false,
    showSettings: Boolean = true,
    onSync: () -> Unit,
    onSettings: () -> Unit
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = ResolveColors.GlassStrong,
            titleContentColor = ResolveColors.Text,
            actionIconContentColor = ResolveColors.Secondary,
            navigationIconContentColor = ResolveColors.Accent
        ),
        navigationIcon = {
            Box(Modifier.padding(start = 16.dp)) {
                ResolveMark(size = if (compact) 28.dp else 32.dp)
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                if (!compact) {
                    Text(
                        "Resolve",
                        color = ResolveColors.Muted,
                        fontSize = ResolveType.Caption,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    title,
                    color = ResolveColors.Text,
                    fontSize = if (compact) ResolveType.SectionTitle else ResolveType.PageTitle,
                    lineHeight = if (compact) 20.sp else 27.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        actions = {
            Surface(
                color = ResolveColors.GlassStrong,
                shape = RoundedCornerShape(999.dp),
                tonalElevation = 0.dp,
                shadowElevation = 1.dp,
                modifier = Modifier
                    .border(1.dp, ResolveColors.GlassStroke, RoundedCornerShape(999.dp))
                    .clickable(enabled = !isSyncing) { onSync() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(14.dp), tint = ResolveColors.Muted)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        when {
                            todoSyncLocked -> "Unlock Todo sync"
                            isSyncing -> "Syncing"
                            else -> calendarStatusLabel(settings, backend)
                        },
                        color = ResolveColors.Secondary,
                        fontSize = ResolveType.Caption,
                        lineHeight = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (showSettings) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
        }
    )
}

@Composable
private fun CaptureBox(
    value: String,
    onChange: (String) -> Unit,
    threads: List<StrategyThread>,
    selectedThreadId: String,
    onThread: (String) -> Unit,
    onSave: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    var strategyMenuOpen by remember { mutableStateOf(false) }
    val selectedThread = threads.find { it.id == selectedThreadId }
    Surface(
        color = ResolveColors.GlassStrong,
        shape = shape,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ResolveColors.GlassStroke, shape)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = ResolveColors.Muted, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
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
                            lineHeight = 20.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (value.isBlank()) {
                        Text(
                            "记一下",
                            color = ResolveColors.Muted,
                            fontSize = ResolveType.Body,
                            lineHeight = 20.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
                Box {
                    Surface(
                        color = ResolveColors.GlassControl,
                        shape = RoundedCornerShape(999.dp),
                        onClick = { strategyMenuOpen = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.Psychology, contentDescription = null, tint = ResolveColors.Muted, modifier = Modifier.size(13.dp))
                            Text(
                                selectedThread?.title ?: "No strategy",
                                color = if (selectedThread == null) ResolveColors.Muted else ResolveColors.Strategy,
                                fontSize = ResolveType.Caption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = ResolveColors.Muted, modifier = Modifier.size(14.dp))
                        }
                    }
                    DropdownMenu(expanded = strategyMenuOpen, onDismissRequest = { strategyMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("No strategy", fontSize = ResolveType.BodySmall) },
                            onClick = {
                                onThread("")
                                strategyMenuOpen = false
                            }
                        )
                        threads.forEach { thread ->
                            DropdownMenuItem(
                                text = { Text(thread.title, fontSize = ResolveType.BodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    onThread(thread.id)
                                    strategyMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }
            Surface(
                color = if (value.isBlank()) ResolveColors.GlassControl else ResolveColors.Accent,
                shape = CircleShape,
                onClick = { if (value.isNotBlank()) onSave() },
                tonalElevation = if (value.isBlank()) 0.dp else 2.dp,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Save", tint = if (value.isBlank()) ResolveColors.Muted else Color.White, modifier = Modifier.size(21.dp))
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
        lineHeight = 20.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(shape)
            .background(ResolveColors.GlassControl)
            .border(1.dp, ResolveColors.GlassStrokeSoft, shape)
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
    listState: LazyListState,
    showCompleted: Boolean,
    showArchived: Boolean,
    scrollToTopSignal: Int,
    keepNearArchiveSignal: Int,
    onToggleDone: (ResolveItem) -> Unit,
    onRestore: (ResolveItem) -> Unit,
    onArchive: (ResolveItem) -> Unit,
    onSelect: (ResolveItem) -> Unit,
    onShowCompleted: () -> Unit,
    onShowArchived: () -> Unit,
    onDeleteArchived: (ResolveItem) -> Unit,
    onClearArchived: () -> Unit,
    onReorder: (String, String) -> Unit
) {
    val tasks = state.items.filter { it.type == ItemType.Task && it.deletedAt == null }
    val activeIds = tasks.filter { it.status == ItemStatus.Active }.map { it.id }.toSet()
    val completedIds = tasks.filter { it.status == ItemStatus.Done }.map { it.id }.toSet()
    val archivedIds = tasks.filter { it.status == ItemStatus.Archived }.map { it.id }.toSet()
    val activePool = tasks.filter { it.status == ItemStatus.Active }.sortedWith(todoComparator)
    val active = activePool.filter { it.parentItemId == null || it.parentItemId !in activeIds }
    val completed = tasks
        .filter { it.status == ItemStatus.Done && (it.parentItemId == null || it.parentItemId !in completedIds) }
        .sortedWith(todoComparator)
    val archived = tasks
        .filter { it.status == ItemStatus.Archived && (it.parentItemId == null || it.parentItemId !in archivedIds) }
        .sortedWith(todoComparator)
    var collapsedTodoIds by remember { mutableStateOf(setOf<String>()) }
    var collapsedStrategyIds by remember { mutableStateOf(setOf<String>()) }
    val completedTree = flattenTodoTree(completed, tasks.filter { it.status == ItemStatus.Done })
    val calendarByTodo = state.calendarEvents
        .filter { calendarEventVisible(it) && it.sourceItemId != null }
        .groupBy { it.sourceItemId.orEmpty() }
        .mapValues { (_, events) ->
            events.sortedWith(compareBy<CalendarEvent> { it.startsAt.isBefore(Instant.now()) }.thenBy { it.startsAt }).first()
        }
    val rootsByStrategy = active.groupBy { it.strategyThreadId.orEmpty() }
    val ungroupedRoots = rootsByStrategy[""].orEmpty()
    val strategyGroups = state.threads
        .filter { !it.status.equals("archived", ignoreCase = true) }
        .sortedWith(strategyThreadComparator)
        .mapNotNull { thread ->
            rootsByStrategy[thread.id]?.takeIf { it.isNotEmpty() }?.let { thread to it }
        }
    val orphanStrategyGroups = rootsByStrategy
        .filterKeys { it.isNotBlank() && state.threads.none { thread -> thread.id == it } }
        .map { (threadId, roots) -> StrategyThread(id = threadId, title = "Strategy") to roots }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(keepNearArchiveSignal, archived.size, showArchived) {
        if (keepNearArchiveSignal > 0) {
            delay(120)
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) {
                val archiveControlIndex = if (showArchived && archived.isNotEmpty()) {
                    total - archived.size - 1
                } else {
                    total - 1
                }
                listState.scrollToItem(archiveControlIndex.coerceIn(0, total - 1))
            }
        }
    }

    fun moveTodo(item: ResolveItem, direction: Int) {
        val siblings = activePool.filter {
            it.parentItemId == item.parentItemId && it.strategyThreadId == item.strategyThreadId
        }
        val index = siblings.indexOfFirst { it.id == item.id }
        val target = siblings.getOrNull(index + direction) ?: return
        onReorder(item.id, target.id)
    }

    @Composable
    fun RenderTodoEntry(entry: TodoTreeEntry) {
        val item = entry.item
        val collapsed = item.id in collapsedTodoIds
        TodoRow(
            item = item,
            subtaskCount = entry.childCount,
            thread = state.threads.find { it.id == item.strategyThreadId },
            calendarEvent = calendarByTodo[item.id],
            depth = entry.depth,
            collapsed = collapsed,
            onToggleCollapse = {
                collapsedTodoIds = if (collapsed) collapsedTodoIds - item.id else collapsedTodoIds + item.id
            },
            onToggleDone = { onToggleDone(item) },
            onArchive = { onArchive(item) },
            onSelect = { onSelect(item) },
            onDragReorder = { direction -> moveTodo(item, direction) }
        )
    }

    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (ungroupedRoots.isNotEmpty()) {
            items(flattenTodoTree(ungroupedRoots, activePool, collapsedTodoIds), key = { it.item.id }) { entry ->
                RenderTodoEntry(entry)
            }
        }
        (strategyGroups + orphanStrategyGroups).forEach { (thread, roots) ->
            item(key = "strategy-${thread.id}") {
                val collapsed = thread.id in collapsedStrategyIds
                StrategyTodoGroupHeader(
                    thread = thread,
                    count = roots.size,
                    collapsed = collapsed,
                    onToggle = {
                        collapsedStrategyIds =
                            if (collapsed) collapsedStrategyIds - thread.id else collapsedStrategyIds + thread.id
                    }
                )
            }
            if (thread.id !in collapsedStrategyIds) {
                items(flattenTodoTree(roots, activePool, collapsedTodoIds), key = { it.item.id }) { entry ->
                    RenderTodoEntry(entry)
                }
            }
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
                    subtaskCount = entry.childCount,
                    thread = state.threads.find { it.id == item.strategyThreadId },
                    calendarEvent = calendarByTodo[item.id],
                    depth = entry.depth,
                    collapsed = false,
                    onToggleCollapse = {},
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onShowArchived) {
                        Text("Hide", color = ResolveColors.Muted, fontSize = ResolveType.Caption)
                    }
                    TextButton(onClick = onClearArchived) {
                        Text("Clear", color = ResolveColors.Danger, fontSize = ResolveType.Caption)
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
    calendarEvent: CalendarEvent?,
    depth: Int = 0,
    collapsed: Boolean = false,
    onToggleCollapse: () -> Unit = {},
    onToggleDone: () -> Unit,
    onArchive: () -> Unit,
    onSelect: () -> Unit,
    onDragReorder: ((Int) -> Unit)? = null
) {
    val isChild = depth > 0
    val scheduledAt = calendarEvent?.startsAt ?: item.dueAt
    val contextLine = todoContextLine(item, thread, calendarEvent)
    val rowShape = RoundedCornerShape(if (isChild) 14.dp else 18.dp)
    var dragDeltaY by remember(item.id) { mutableStateOf(0f) }
    val dragModifier = if (onDragReorder != null && item.status == ItemStatus.Active) {
        Modifier.pointerInput(item.id) {
            detectDragGesturesAfterLongPress(
                onDragStart = { dragDeltaY = 0f },
                onDragCancel = { dragDeltaY = 0f },
                onDragEnd = {
                    val direction = when {
                        dragDeltaY > 34f -> 1
                        dragDeltaY < -34f -> -1
                        else -> 0
                    }
                    if (direction != 0) onDragReorder(direction)
                    dragDeltaY = 0f
                },
                onDrag = { _, dragAmount ->
                    dragDeltaY += dragAmount.y
                }
            )
        }
    } else {
        Modifier
    }
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
        Surface(
            modifier = (if (isChild) Modifier.weight(1f) else Modifier.fillMaxWidth())
                .then(dragModifier)
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = onArchive
                )
                .border(1.dp, if (isChild) ResolveColors.GlassStrokeSoft else ResolveColors.GlassStroke, rowShape),
            shape = rowShape,
            color = when {
                item.status == ItemStatus.Archived -> ResolveColors.GlassMuted
                isChild -> ResolveColors.GlassSoft
                else -> ResolveColors.Glass
            },
            tonalElevation = if (isChild) 0.dp else 1.dp,
            shadowElevation = if (isChild) 0.dp else 1.dp
        ) {
            Row(Modifier.padding(horizontal = if (isChild) 8.dp else 10.dp, vertical = if (isChild) 5.dp else 7.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleDone, modifier = Modifier.size(if (isChild) 32.dp else 36.dp)) {
                    Icon(
                        if (item.status == ItemStatus.Done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (item.status == ItemStatus.Archived) "Restore" else "Toggle done",
                        tint = if (item.status == ItemStatus.Done) ResolveColors.Accent else ResolveColors.Muted,
                        modifier = Modifier.size(if (isChild) 20.dp else 22.dp)
                    )
                }
                Spacer(Modifier.width(if (isChild) 4.dp else 6.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        item.title,
                        color = if (item.status == ItemStatus.Archived || item.status == ItemStatus.Done) ResolveColors.Muted else ResolveColors.Text,
                        fontSize = ResolveType.Body,
                        lineHeight = 20.sp,
                        fontWeight = if (isChild) FontWeight.Normal else FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (item.status == ItemStatus.Done) TextDecoration.LineThrough else TextDecoration.None
                    )
                    if (subtaskCount > 0) {
                        SubtaskToggleChip(
                            count = subtaskCount,
                            collapsed = collapsed,
                            onClick = onToggleCollapse
                        )
                    }
                    if (scheduledAt != null || item.status == ItemStatus.Archived) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            scheduledAt?.let {
                                MetaPill(
                                    if (calendarEvent != null) "Calendar ${dateLabel(it)}" else dateLabel(it),
                                    tone = "accent"
                                )
                            }
                            if (item.status == ItemStatus.Archived) MetaPill("Archived")
                        }
                    }
                    if (contextLine.isNotBlank()) {
                        Text(
                            contextLine,
                            color = ResolveColors.Muted,
                            fontSize = ResolveType.Caption,
                            lineHeight = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = ResolveColors.GlassMuted,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ResolveColors.GlassStrokeSoft, shape)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun StrategyTodoGroupHeader(
    thread: StrategyThread,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 10.dp, bottom = 4.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (collapsed) "Expand strategy tasks" else "Collapse strategy tasks",
            tint = ResolveColors.Muted,
            modifier = Modifier
                .size(16.dp)
                .rotate(if (collapsed) 0f else 90f)
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(ResolveColors.Strategy)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            thread.title,
            color = ResolveColors.Secondary,
            fontSize = ResolveType.Caption,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text("$count", color = ResolveColors.Muted, fontSize = ResolveType.Caption)
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
private fun SubtaskRow(
    item: ResolveItem,
    depth: Int,
    childCount: Int,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onToggle: () -> Unit
) {
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
                if (childCount > 0) {
                    IconButton(onClick = onToggleCollapse, modifier = Modifier.size(24.dp)) {
                        Icon(
                            if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (collapsed) "Expand subtasks" else "Collapse subtasks",
                            tint = ResolveColors.Muted,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
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
    onArchiveTodo: (ResolveItem) -> Unit,
    onArchiveThread: (StrategyThread) -> Unit
) {
    var task by remember { mutableStateOf("") }
    var collapsedTodoIds by remember { mutableStateOf(setOf<String>()) }
    var showCompletedSubtasks by remember(openedThreadId) { mutableStateOf(false) }
    val activeThreads = state.threads
        .filter { !it.status.equals("archived", ignoreCase = true) }
        .sortedWith(strategyThreadComparator)
    val opened = activeThreads.find { it.id == openedThreadId }
    val allTasks = state.items.filter { it.type == ItemType.Task && it.deletedAt == null }

    if (showNewThread) {
        NewStrategyDirectionPage(
            onClose = onCloseNewThread,
            onCreate = { title, hypothesis -> onAddThread(title, hypothesis) }
        )
        return
    }

    if (opened == null) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { DetailSectionTitle("Strategy", "${activeThreads.size}") }
            items(activeThreads, key = { it.id }) { thread ->
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

    val strategyTasks = state.items
        .filter { it.strategyThreadId == opened.id && it.type == ItemType.Task && it.deletedAt == null && it.status != ItemStatus.Archived }
        .sortedWith(
            Comparator { first, second ->
                val statusOrder = compareValues(first.status == ItemStatus.Done, second.status == ItemStatus.Done)
                if (statusOrder != 0) statusOrder else compareTodoItems(first, second)
            }
        )
    val subtasks = strategyTasks.filter { it.status != ItemStatus.Done }
    val completedSubtasks = strategyTasks.filter { it.status == ItemStatus.Done }
    val subtaskRoots = subtasks.filter { task -> task.parentItemId == null || subtasks.none { it.id == task.parentItemId } }
    val completedRoots = completedSubtasks.filter { task -> task.parentItemId == null || completedSubtasks.none { it.id == task.parentItemId } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { DetailPageHeader(title = "Strategy", onClose = onCloseThread) }
        item {
            Surface(
                color = Color(0xFFFCFBFF),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFE6E0F4), RoundedCornerShape(18.dp))
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(opened.title, color = ResolveColors.Text, fontSize = ResolveType.Body, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                            if (opened.currentHypothesis.isNotBlank()) {
                                Text(
                                    opened.currentHypothesis,
                                    color = ResolveColors.Secondary,
                                    fontSize = ResolveType.Caption,
                                    lineHeight = 15.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        AssistChip(
                            onClick = { onArchiveThread(opened) },
                            label = { Text("Archive", fontSize = ResolveType.Caption) },
                            leadingIcon = {
                                Icon(Icons.Filled.Archive, contentDescription = null, modifier = Modifier.size(13.dp))
                            }
                        )
                    }
                    MetaPill("${completedSubtasks.size}/${strategyTasks.size} done", tone = "accent")
                }
            }
        }
        item { DetailSectionTitle("Subtasks", "${subtasks.size}") }
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
        items(flattenTodoTree(subtaskRoots, subtasks, collapsedTodoIds), key = { it.item.id }) { entry ->
            val item = entry.item
            val collapsed = item.id in collapsedTodoIds
            TodoRow(
                item = item,
                subtaskCount = entry.childCount,
                thread = null,
                calendarEvent = null,
                depth = entry.depth,
                collapsed = collapsed,
                onToggleCollapse = {
                    collapsedTodoIds = if (collapsed) collapsedTodoIds - item.id else collapsedTodoIds + item.id
                },
                onToggleDone = { onToggleDone(item) },
                onArchive = { onArchiveTodo(item) },
                onSelect = { onSelectTodo(item) }
            )
        }
        if (completedSubtasks.isNotEmpty()) {
            item {
                TodoDisclosureRow(
                    title = "Completed",
                    count = completedSubtasks.size,
                    open = showCompletedSubtasks,
                    onClick = { showCompletedSubtasks = !showCompletedSubtasks }
                )
            }
            if (showCompletedSubtasks) {
                items(flattenTodoTree(completedRoots, completedSubtasks, collapsedTodoIds), key = { "done-${it.item.id}" }) { entry ->
                    val item = entry.item
                    val collapsed = item.id in collapsedTodoIds
                    TodoRow(
                        item = item,
                        subtaskCount = entry.childCount,
                        thread = null,
                        calendarEvent = null,
                        depth = entry.depth,
                        collapsed = collapsed,
                        onToggleCollapse = {
                            collapsedTodoIds = if (collapsed) collapsedTodoIds - item.id else collapsedTodoIds + item.id
                        },
                        onToggleDone = { onToggleDone(item) },
                        onArchive = { onArchiveTodo(item) },
                        onSelect = { onSelectTodo(item) }
                    )
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
    syncSecretReady: Boolean,
    onBackendSettings: (BackendSettings) -> Unit,
    onBackendSignIn: (String) -> Unit,
    onBackendDisconnect: () -> Unit,
    onBackendFeishuConnect: () -> Unit
) {
    var email by remember(state.backendSettings.email) { mutableStateOf(state.backendSettings.email) }
    var password by remember { mutableStateOf("") }
    val hasAccountSession = state.backendSettings.status == BackendStatus.Connected || state.backendSettings.feishuConnected
    val needsTodoUnlock = hasAccountSession && !syncSecretReady
    val backendReady = hasAccountSession && syncSecretReady
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
            Surface(
                color = ResolveColors.Surface,
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.TaskAlt, contentDescription = null, tint = ResolveColors.Accent)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Account", color = ResolveColors.Text, fontSize = ResolveType.SectionTitle, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (needsTodoUnlock) "Unlock Todo sync on this phone" else backendStatusLabel(state.backendSettings),
                                color = ResolveColors.Secondary
                            )
                        }
                    }
                    if (backendReady) {
                        Surface(
                            color = ResolveColors.SurfaceHigh,
                            shape = RoundedCornerShape(18.dp),
                            tonalElevation = 0.dp,
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
                        if (needsTodoUnlock) {
                            Text(
                                "Enter your password once to sync Todo.",
                                color = ResolveColors.Secondary,
                                fontSize = ResolveType.BodySmall,
                                lineHeight = 16.sp
                            )
                        }
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
                        TextButton(onClick = onBackendDisconnect, enabled = hasAccountSession) {
                            Text("Sign out")
                        }
                    }
                    state.backendSettings.lastError?.let { Text(it, color = ResolveColors.Danger, fontSize = ResolveType.BodySmall) }
                }
            }
        }
        item {
            Surface(
                color = ResolveColors.Surface,
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
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
                            color = ResolveColors.SurfaceHigh,
                            shape = RoundedCornerShape(18.dp),
                            tonalElevation = 0.dp,
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
    onCalendar: (ResolveItem) -> Unit,
    onNote: (ResolveItem) -> Unit
) {
    val context = LocalContext.current
    var title by remember(item.id) { mutableStateOf(item.title) }
    var notes by remember(item.id) { mutableStateOf(item.notes) }
    var expanded by remember { mutableStateOf(false) }
    var strategyThreadId by remember(item.id) { mutableStateOf(item.strategyThreadId) }
    var dueAt by remember(item.id) { mutableStateOf(item.dueAt) }
    var collapsedSubtaskIds by remember(item.id) { mutableStateOf(setOf<String>()) }
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
                            fontSize = ResolveType.BodySmall,
                            lineHeight = 16.sp,
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
                        Button(
                            onClick = { onNote(currentItem()) },
                            colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.InkSoft, contentColor = ResolveColors.Accent),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Icon(Icons.Filled.OpenInBrowser, contentDescription = null, Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Note", fontSize = ResolveType.BodySmall)
                        }
                    }
                }
            }
        }
        item {
            DetailSectionTitle("Subtasks", "${subtasks.size}")
        }
        val subtaskRoots = subtasks.filter { it.parentItemId == item.id }
        val subtaskTree = flattenTodoTree(subtaskRoots, subtasks, collapsedSubtaskIds)
        items(subtaskTree, key = { it.item.id }) { entry ->
            val collapsed = entry.item.id in collapsedSubtaskIds
            SubtaskRow(
                item = entry.item,
                depth = entry.depth,
                childCount = entry.childCount,
                collapsed = collapsed,
                onToggleCollapse = {
                    collapsedSubtaskIds = if (collapsed) collapsedSubtaskIds - entry.item.id else collapsedSubtaskIds + entry.item.id
                },
                onToggle = { onToggleSubtask(entry.item) }
            )
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
private fun VaultScreen(
    state: ResolveState,
    selectedNoteId: String?,
    noteDraft: String,
    onSelectNote: (MarkdownNote) -> Unit,
    onNoteDraft: (String) -> Unit,
    onSave: (String) -> Unit,
    onArchive: (MarkdownNote) -> Unit,
    onCloseNote: () -> Unit
) {
    val selectedNote = state.notes.find { it.id == selectedNoteId }
    if (selectedNote != null) {
        NoteEditorPage(
            note = selectedNote,
            markdown = noteDraft,
            threads = state.threads,
            task = selectedNote.taskId?.let { taskId -> state.items.find { it.id == taskId } },
            onMarkdown = onNoteDraft,
            onSave = onSave,
            onArchive = { onArchive(selectedNote) },
            onClose = onCloseNote
        )
        return
    }

    var view by rememberSaveable { mutableStateOf("recent") }
    val activeNotes = state.notes.filter { it.status != "archived" }
    val archivedNotes = state.notes.filter { it.status == "archived" }
    val visibleNotes = when (view) {
        "archive" -> archivedNotes.sortedByDescending { it.updatedAt }
        else -> activeNotes.sortedByDescending { it.updatedAt }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("recent" to "Recent", "date" to "Date", "strategy" to "Strategy", "archive" to "Archive").forEach { (key, label) ->
                    AssistChip(
                        onClick = { view = key },
                        label = { Text(label, fontSize = ResolveType.Caption) },
                        modifier = Modifier.height(32.dp)
                    )
                }
            }
        }

        if (view == "strategy") {
            val grouped = activeNotes.groupBy { it.strategyThreadId.orEmpty() }
            state.threads.sortedWith(strategyThreadComparator).forEach { thread ->
                val notes = grouped[thread.id].orEmpty().sortedByDescending { it.updatedAt }
                if (notes.isNotEmpty()) {
                    item { DetailSectionTitle(thread.title, notes.size.toString()) }
                    items(notes, key = { it.id }) { note ->
                        NoteListRow(note, state, onClick = { onSelectNote(note) })
                    }
                }
            }
            grouped[""].orEmpty().takeIf { it.isNotEmpty() }?.let { notes ->
                item { DetailSectionTitle("Orphan Notes", notes.size.toString()) }
                items(notes.sortedByDescending { it.updatedAt }, key = { it.id }) { note ->
                    NoteListRow(note, state, onClick = { onSelectNote(note) })
                }
            }
        } else if (view == "date") {
            visibleNotes.groupBy { it.updatedAt.atZone(ZoneId.systemDefault()).toLocalDate() }
                .toSortedMap(compareByDescending<LocalDate> { it })
                .forEach { (date, notes) ->
                    item { DetailSectionTitle(date.format(DateTimeFormatter.ofPattern("M月d日")), notes.size.toString()) }
                    items(notes.sortedByDescending { it.updatedAt }, key = { it.id }) { note ->
                        NoteListRow(note, state, onClick = { onSelectNote(note) })
                    }
                }
        } else {
            item { DetailSectionTitle(if (view == "archive") "Archived Notes" else "Notes", visibleNotes.size.toString()) }
            if (visibleNotes.isEmpty()) {
                item {
                    Surface(color = ResolveColors.Surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Click a task title to create its first Note.",
                            modifier = Modifier.padding(14.dp),
                            color = ResolveColors.Muted,
                            fontSize = ResolveType.BodySmall
                        )
                    }
                }
            }
            items(visibleNotes, key = { it.id }) { note ->
                NoteListRow(note, state, onClick = { onSelectNote(note) })
            }
        }
    }
}

@Composable
private fun NoteListRow(note: MarkdownNote, state: ResolveState, onClick: () -> Unit) {
    val thread = note.strategyThreadId?.let { id -> state.threads.find { it.id == id } }
    val taskMissing = note.taskId != null && state.items.none { it.id == note.taskId }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, ResolveColors.GlassStroke, RoundedCornerShape(18.dp)),
        color = ResolveColors.Glass,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.OpenInBrowser, contentDescription = null, tint = ResolveColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(note.title, color = ResolveColors.Text, fontSize = ResolveType.Body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    MetaPill("note_id ${note.id.takeLast(6)}")
                    thread?.let { MetaPill(it.title, tone = "strategy") }
                    if (taskMissing) MetaPill("Orphan")
                    if (note.status == "archived") MetaPill("Archived")
                }
            }
        }
    }
}

@Composable
private fun NoteEditorPage(
    note: MarkdownNote,
    markdown: String,
    threads: List<StrategyThread>,
    task: ResolveItem?,
    onMarkdown: (String) -> Unit,
    onSave: (String) -> Unit,
    onArchive: () -> Unit,
    onClose: () -> Unit
) {
    val thread = note.strategyThreadId?.let { id -> threads.find { it.id == id } }
    var titleDraft by remember(note.id, note.title) { mutableStateOf(note.title) }
    var editing by remember(note.id) { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            DetailPageHeader(title = "Note", onClose = onClose)
        }
        item {
            Surface(color = ResolveColors.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactInputField(
                        value = titleDraft,
                        onValueChange = { titleDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Note title",
                        singleLine = true,
                        textStyle = TextStyle(
                            color = ResolveColors.Text,
                            fontSize = ResolveType.CardTitle,
                            fontWeight = FontWeight.SemiBold,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        MetaPill("note_id ${note.id.takeLast(6)}")
                        task?.let { MetaPill("Task") }
                        thread?.let { MetaPill(it.title, tone = "strategy") }
                        if (task == null && note.taskId != null) MetaPill("Orphan")
                    }
                }
            }
        }
        item {
            Surface(color = ResolveColors.Glass, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                if (editing) {
                    CompactInputField(
                        value = markdown,
                        onValueChange = onMarkdown,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 420.dp)
                            .padding(12.dp),
                        placeholder = "Write in Markdown...",
                        singleLine = false,
                        minHeight = 420.dp,
                        textStyle = TextStyle(
                            color = ResolveColors.Text,
                            fontSize = ResolveType.Body,
                            lineHeight = 20.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                } else {
                    MarkdownPreview(
                        markdown = markdown,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 420.dp)
                            .padding(18.dp)
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                if (editing) {
                    Button(
                        onClick = {
                            onSave(titleDraft)
                            editing = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text("Save", fontSize = ResolveType.BodySmall)
                    }
                } else {
                    Button(
                        onClick = { editing = true },
                        colors = ButtonDefaults.buttonColors(containerColor = ResolveColors.Accent),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text("Edit", fontSize = ResolveType.BodySmall)
                    }
                }
                TextButton(onClick = onArchive, colors = ButtonDefaults.textButtonColors(contentColor = ResolveColors.Muted)) {
                    Icon(Icons.Filled.Archive, contentDescription = null, Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (note.status == "archived") "Restore Note" else "Archive Note", fontSize = ResolveType.Caption)
                }
            }
        }
    }
}

@Composable
private fun MarkdownPreview(markdown: String, modifier: Modifier = Modifier) {
    val lines = markdown.lineSequence().toList()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (lines.all { it.isBlank() }) {
            Text(
                "Empty Note",
                color = ResolveColors.Muted,
                fontSize = ResolveType.Body,
                lineHeight = 20.sp
            )
            return@Column
        }
        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(5.dp))
                line.startsWith("### ") -> Text(
                    line.removePrefix("### "),
                    color = ResolveColors.Text,
                    fontSize = ResolveType.Body,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp
                )
                line.startsWith("## ") -> Text(
                    line.removePrefix("## "),
                    color = ResolveColors.Text,
                    fontSize = ResolveType.CardTitle,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 24.sp
                )
                line.startsWith("# ") -> Text(
                    line.removePrefix("# "),
                    color = ResolveColors.Text,
                    fontSize = ResolveType.PageTitle,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 30.sp
                )
                line.startsWith("- ") || line.startsWith("* ") -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", color = ResolveColors.Accent, fontSize = ResolveType.Body, lineHeight = 20.sp)
                    Text(
                        line.drop(2),
                        color = ResolveColors.Text,
                        fontSize = ResolveType.Body,
                        lineHeight = 20.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> Text(
                    line,
                    color = ResolveColors.Text,
                    fontSize = ResolveType.Body,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
private fun BottomTabs(tab: Tab, onTab: (Tab) -> Unit) {
    NavigationBar(
        containerColor = ResolveColors.NavBar,
        tonalElevation = 0.dp
    ) {
        listOf(Tab.Todo, Tab.Calendar, Tab.Strategy, Tab.Vault, Tab.Settings).forEach { item ->
            val selected = tab == item
            NavigationBarItem(
                selected = selected,
                onClick = { onTab(item) },
                icon = {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = {
                    Text(
                        item.label,
                        fontSize = ResolveType.Caption,
                        lineHeight = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ResolveColors.Accent,
                    selectedTextColor = ResolveColors.Text,
                    indicatorColor = ResolveColors.GlassControl,
                    unselectedIconColor = ResolveColors.Secondary,
                    unselectedTextColor = ResolveColors.Secondary
                )
            )
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
            Surface(color = ResolveColors.GlassControl, shape = RoundedCornerShape(999.dp)) {
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
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            color = if (open) ResolveColors.GlassControl else Color.Transparent,
            shape = RoundedCornerShape(999.dp)
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
private fun SubtaskToggleChip(count: Int, collapsed: Boolean, onClick: () -> Unit) {
    Surface(
        color = ResolveColors.AccentGlass,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = ResolveColors.Accent,
                modifier = Modifier
                    .size(13.dp)
                    .rotate(if (collapsed) 0f else 90f)
            )
            Text(
                "${if (collapsed) "Show" else "Hide"} $count subtasks",
                color = ResolveColors.Accent,
                fontSize = ResolveType.Pill,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun MetaPill(label: String, tone: String = "muted") {
    val background = when (tone) {
        "accent" -> ResolveColors.AccentGlass
        "danger" -> ResolveColors.DangerGlass
        "soft" -> ResolveColors.InkSoft
        else -> ResolveColors.GlassControl
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
            lineHeight = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InlineNotice(message: String, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(13.dp)
    Surface(
        color = ResolveColors.WarningGlass,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ResolveColors.WarningStroke, shape)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 7.dp, end = 6.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                color = ResolveColors.Secondary,
                fontSize = ResolveType.Caption,
                lineHeight = 15.sp,
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
    val colorScheme = lightColorScheme(
        primary = ResolveColors.Accent,
        onPrimary = Color.White,
        primaryContainer = ResolveColors.AccentGlass,
        onPrimaryContainer = ResolveColors.Text,
        secondary = ResolveColors.Accent,
        background = ResolveColors.Bg,
        onBackground = ResolveColors.Text,
        surface = ResolveColors.Surface,
        onSurface = ResolveColors.Text,
        surfaceVariant = ResolveColors.SurfaceHigh,
        onSurfaceVariant = ResolveColors.Secondary,
        outline = ResolveColors.Line,
        error = ResolveColors.Danger
    )
    MaterialTheme(
        colorScheme = colorScheme,
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

private fun todoSortOrder(item: ResolveItem): Double? =
    item.sortOrder?.takeIf { !it.isNaN() && !it.isInfinite() }

private fun compareTodoItems(first: ResolveItem, second: ResolveItem): Int {
    val firstOrder = todoSortOrder(first)
    val secondOrder = todoSortOrder(second)
    if (firstOrder != null && secondOrder != null && firstOrder != secondOrder) {
        return firstOrder.compareTo(secondOrder)
    }
    if (firstOrder != null && secondOrder == null) return -1
    if (firstOrder == null && secondOrder != null) return 1
    if (first.dueAt != null && second.dueAt == null) return -1
    if (first.dueAt == null && second.dueAt != null) return 1
    val firstDate = first.dueAt ?: first.createdAt
    val secondDate = second.dueAt ?: second.createdAt
    return firstDate.compareTo(secondDate)
}

private val todoComparator = Comparator<ResolveItem> { first, second ->
    compareTodoItems(first, second)
}

private fun strategyThreadSortOrder(thread: StrategyThread): Double? =
    thread.sortOrder?.takeIf { !it.isNaN() && !it.isInfinite() }

private fun compareStrategyThreads(first: StrategyThread, second: StrategyThread): Int {
    val firstOrder = strategyThreadSortOrder(first)
    val secondOrder = strategyThreadSortOrder(second)
    if (firstOrder != null && secondOrder != null && firstOrder != secondOrder) {
        return firstOrder.compareTo(secondOrder)
    }
    if (firstOrder != null && secondOrder == null) return -1
    if (firstOrder == null && secondOrder != null) return 1
    return first.createdAt.compareTo(second.createdAt)
}

private val strategyThreadComparator = Comparator<StrategyThread> { first, second ->
    compareStrategyThreads(first, second)
}

private fun calendarDescriptionWithStrategy(description: String, strategyTitle: String?): String {
    val cleanDescription = description.trim()
    val cleanStrategy = strategyTitle?.trim()
    if (cleanStrategy.isNullOrBlank()) return cleanDescription
    val strategyLine = "Strategy: $cleanStrategy"
    if (cleanDescription.contains(strategyLine)) return cleanDescription
    return listOf(strategyLine, cleanDescription).filter { it.isNotBlank() }.joinToString("\n\n")
}

private fun todoContextLine(
    item: ResolveItem,
    thread: StrategyThread?,
    calendarEvent: CalendarEvent?
): String {
    val parts = mutableListOf<String>()
    parts += "Added ${relativeTime(item.createdAt)}"
    thread?.title?.takeIf { it.isNotBlank() }?.let { parts += it }
    val note = briefContext(item.notes).ifBlank { briefContext(calendarEvent?.description.orEmpty()) }
    if (note.isNotBlank()) parts += note
    return parts.joinToString(" · ")
}

private fun briefContext(text: String): String {
    val firstLine = text
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    return if (firstLine.length > 34) "${firstLine.take(34).trimEnd()}..." else firstLine
}

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
    pool: List<ResolveItem>,
    collapsedIds: Set<String> = emptySet()
): List<TodoTreeEntry> {
    val childrenByParent = pool
        .filter { it.parentItemId != null }
        .groupBy { it.parentItemId }
    val result = mutableListOf<TodoTreeEntry>()

    fun visit(item: ResolveItem, depth: Int) {
        val children = childrenByParent[item.id]
            .orEmpty()
            .sortedWith(todoComparator)
        result += TodoTreeEntry(item, depth, children.size)
        if (item.id in collapsedIds) return
        children.forEach { visit(it, depth + 1) }
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
    if (minutes < 10) return "just now"
    val local = instant.atZone(ZoneId.systemDefault())
    if (minutes < 60) return local.format(DateTimeFormatter.ofPattern("HH:mm"))
    val hours = minutes / 60
    val restMinutes = minutes % 60
    if (hours < 24) return if (restMinutes > 0) "${hours}h ${restMinutes}m ago" else "${hours}h ago"
    val days = hours / 24
    if (days <= 7) return "${days}d ago"
    return local.format(DateTimeFormatter.ofPattern("M月d日"))
}

private object ResolveColors {
    val Bg = Color(0xFFF5F7FA)
    val Backdrop = Brush.linearGradient(
        listOf(
            Color(0xFFF8FAFD),
            Color(0xFFF1F5FA),
            Color(0xFFFFFFFF)
        )
    )
    val Surface = Color(0xFFFFFFFF)
    val SurfaceHigh = Color(0xFFF2F6FC)
    val Glass = Color(0xFFFFFFFF)
    val GlassStrong = Color(0xFFFFFFFF)
    val GlassSoft = Color(0xFFF7FAFD)
    val GlassMuted = Color(0xFFF0F3F8)
    val GlassControl = Color(0xFFEAF2FF)
    val GlassStroke = Color(0xFFE4EAF2)
    val GlassStrokeSoft = Color(0xFFDDE5EF)
    val NavBar = Color(0xFFFFFFFF)
    val Pill = Color(0xFFF0F4FA)
    val InkSoft = Color(0xFFE8F1FF)
    val AccentGlass = Color(0xFFE6F0FF)
    val DangerGlass = Color(0xFFFFF0EF)
    val WarningGlass = Color(0xFFFFF7E8)
    val WarningStroke = Color(0x66E6B45B)
    val Line = Color(0x26516178)
    val Text = Color(0xFF1F2329)
    val Secondary = Color(0xFF646A73)
    val Muted = Color(0xFF8F959E)
    val Accent = Color(0xFF3370FF)
    val Strategy = Color(0xFF6B5CFF)
    val Danger = Color(0xFFD83931)
}

private object ResolveType {
    val PageTitle = 24.sp
    val DetailTitle = 20.sp
    val SectionTitle = 17.sp
    val CardTitle = 16.sp
    val Body = 14.sp
    val BodySmall = 13.sp
    val Caption = 12.sp
    val Pill = 11.sp
    val Micro = 10.sp
}
