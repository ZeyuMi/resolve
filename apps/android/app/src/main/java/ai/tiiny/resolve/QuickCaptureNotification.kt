package ai.tiiny.resolve

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

private const val quickCaptureChannelId = "resolve_quick_capture"
private const val quickCaptureNotificationId = 1001
private const val quickCaptureInputKey = "resolve_quick_capture_text"
private const val quickCaptureExtraText = "resolve_quick_capture_text_extra"

object ResolveQuickCaptureNotification {
    const val actionCapture = "ai.tiiny.resolve.action.QUICK_CAPTURE"
    const val actionOpen = "ai.tiiny.resolve.action.OPEN_FROM_QUICK_CAPTURE"
    const val actionSaved = "ai.tiiny.resolve.action.QUICK_CAPTURE_SAVED"
    const val extraReloadState = "ai.tiiny.resolve.extra.RELOAD_STATE"

    fun show(context: Context, saved: Boolean = false) {
        if (!canPostNotifications(context)) return

        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)

        val openIntent = Intent(context, MainActivity::class.java)
            .setAction(actionOpen)
            .putExtra(extraReloadState, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val captureIntent = Intent(context, QuickCaptureReceiver::class.java).setAction(actionCapture)
        val capturePendingIntent = PendingIntent.getBroadcast(
            context,
            11,
            captureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val input = RemoteInput.Builder(quickCaptureInputKey)
            .setLabel("记一下...")
            .build()
        val captureAction = Notification.Action.Builder(
            R.drawable.ic_resolve_icon_foreground,
            "记一下",
            capturePendingIntent
        )
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()

        val notification = Notification.Builder(context, quickCaptureChannelId)
            .setSmallIcon(R.drawable.ic_resolve_icon_foreground)
            .setContentTitle("Resolve")
            .setContentText(if (saved) "已保存" else "")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setPriority(Notification.PRIORITY_LOW)
            .addAction(captureAction)
            .build()

        manager.notify(quickCaptureNotificationId, notification)
    }

    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = manager.getNotificationChannel(quickCaptureChannelId)
        if (existing != null) return
        val channel = NotificationChannel(
            quickCaptureChannelId,
            "Quick Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keep a quiet Resolve capture action in the notification shade."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}

object ResolveQuickCaptureEvents {
    var version by mutableIntStateOf(0)
        private set

    fun markSaved() {
        version += 1
    }
}

class QuickCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ResolveQuickCaptureNotification.actionCapture -> {
                val text = (RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(quickCaptureInputKey)
                    ?: intent.getStringExtra(quickCaptureExtraText))
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (text.isNotBlank()) {
                    ResolveRepository(context).addQuickCapture(text)
                    ResolveQuickCaptureEvents.markSaved()
                    Log.d("ResolveQuickCapture", "saved quick capture length=${text.length}")
                    ResolveQuickCaptureNotification.show(context, saved = true)
                    context.sendBroadcast(
                        Intent(ResolveQuickCaptureNotification.actionSaved)
                            .setPackage(context.packageName)
                    )
                } else {
                    Log.d("ResolveQuickCapture", "quick capture ignored blank input")
                    ResolveQuickCaptureNotification.show(context)
                }
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ResolveQuickCaptureNotification.show(context)
            }
        }
    }
}
