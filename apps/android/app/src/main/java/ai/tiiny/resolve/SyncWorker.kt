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

            if (!backend.feishuConnected || session == null) {
                return@withContext Result.success()
            }

            try {
                var client = BackendClient(backend, session)
                if (session.shouldRefresh()) {
                    session = client.refreshSession()
                    vault.saveBackendSession(session.accessToken, session.refreshToken, session.expiresAtEpochMillis)
                    client = BackendClient(backend, session)
                }

                val syncedAt = client.syncFeishuNow()
                val events = client.listEvents(state.feishuSettings)
                repository.save(
                    state.copy(
                        calendarEvents = mergeBackendCalendarEvents(state.calendarEvents, events),
                        backendSettings = backend.copy(
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
                Result.success()
            } catch (error: Throwable) {
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
