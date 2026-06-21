package ai.tiiny.resolve

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val repository = ResolveRepository(applicationContext)
            val vault = SecureVault(applicationContext)
            val state = repository.load()
            val backend = state.backendSettings
            var session = vault.loadBackendSession()

            if (session == null) {
                return@withContext Result.success()
            }

            try {
                var client = BackendClient(backend, session)
                if (session.shouldRefresh()) {
                    session = client.refreshSession()
                    vault.saveBackendSession(session.accessToken, session.refreshToken, session.expiresAtEpochMillis)
                    client = BackendClient(backend, session)
                }

                val syncSecret = vault.loadSyncSecret()
                val appSyncedState = if (syncSecret != null && backend.email.isNotBlank()) {
                    val appSync = AppSyncClient(backend, session, syncSecret)
                    val remote = appSync.pullState(includeCalendarEvents = false)
                    mergeEncryptedRemoteState(state, remote).also { appSync.pushState(it) }
                } else {
                    state
                }

                if (!backend.feishuConnected) {
                    repository.save(appSyncedState)
                    return@withContext Result.success()
                }

                val syncedAt = client.syncFeishuNow()
                val events = client.listEvents(appSyncedState.feishuSettings)
                repository.save(
                    appSyncedState.copy(
                        calendarEvents = mergeBackendCalendarEvents(appSyncedState.calendarEvents, events),
                        backendSettings = backend.copy(
                            status = BackendStatus.Connected,
                            feishuConnected = true,
                            lastSyncedAt = syncedAt,
                            lastError = null
                        ),
                        feishuSettings = appSyncedState.feishuSettings.copy(
                            status = FeishuStatus.Connected,
                            lastSyncedAt = syncedAt,
                            lastError = null
                        )
                    )
                )
                Result.success()
            } catch (error: Throwable) {
                if (error.needsCalendarAuthorization()) {
                    repository.save(
                        state.copy(
                            backendSettings = backend.copy(
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
                    return@withContext Result.success()
                }
                repository.save(
                    state.copy(
                        backendSettings = backend.copy(status = BackendStatus.Error, lastError = error.message),
                        feishuSettings = state.feishuSettings.copy(
                            status = FeishuStatus.PermissionError,
                            lastError = error.message
                        )
                    )
                )
                Result.retry()
            }
        }
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "resolve-sync",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
