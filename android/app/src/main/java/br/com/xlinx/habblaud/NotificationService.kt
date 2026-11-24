package br.com.xlinx.habblaud

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.*

/**
 * Mantém o /api/stream aberto com o app fechado e avisa quando um agente pede aprovação/pergunta ou termina o turno.
 * Roda como serviço em primeiro plano (ícone fixo na barra), enquanto houver login e as notificações estiverem ligadas.
 */
class NotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var multipleServers = false
    private val pending = linkedMapOf<SessionKey, AgentEvent>()
    private val summarized = linkedSetOf<SessionKey>()
    private var batchJob: Job? = null
    private var summaryJob: Job? = null
    private var summaryPublished = false
    private val manager by lazy { NotificationManagerCompat.from(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels(this)
        initializeStorage(applicationContext)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val ongoing = NotificationCompat.Builder(this, CHANNEL_CONNECTION)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(getString(R.string.notifications_running))
            .setContentIntent(open).setOngoing(true).setSilent(true).build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, ONGOING_ID, ongoing, type)
        scope.launch { MainActivity.visibleSession.collect { it?.let(::discard) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true && intent?.getBooleanExtra(EXTRA_RESTART, false) != true) return START_STICKY
        val previous = job
        previous?.cancel()
        job = scope.launch { previous?.join(); watch() }
        return START_STICKY
    }

    private suspend fun watch() {
        try {
            watchAlerts(LocalStateStore(applicationContext), onServers = { multipleServers = it > 1 }, notify = ::enqueue, withdraw = ::withdraw)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Could not watch notifications", e)
        }
        withContext(Dispatchers.Main.immediate) { stopSelf() }
    }

    private fun enqueue(event: AgentEvent) {
        val key = event.session.key
        if (MainActivity.isShowing(key) || !allowed(this)) return
        pending[key] = event
        scheduleBatch()
    }

    private fun scheduleBatch() {
        if (batchJob != null || pending.isEmpty()) return
        batchJob = scope.launch {
            delay(ALERT_BATCH_WINDOW_MS)
            val ready = pending.filterKeys { !MainActivity.isShowing(it) }
            pending.keys.retainAll(ready.keys)
            if (mergeAlertBatch(summarized, ready.keys)) {
                ready.keys.forEach { cancelAlert(this@NotificationService, it); pending.remove(it) }
                updateSummary()
            } else {
                for ((key, event) in ready) {
                    val (id, notification) = alertNotification(this@NotificationService, event, multipleServers)
                    if (pending[key] != event) continue
                    pending.remove(key)
                    if (!MainActivity.isShowing(key)) {
                        removeFromSummary(key)
                        post(id, notification)
                    }
                }
            }
            batchJob = null
            scheduleBatch()
        }
    }

    private fun withdraw(key: SessionKey) {
        cancelAlert(this, key)
        discard(key)
    }

    private fun discard(key: SessionKey) {
        pending.remove(key)
        removeFromSummary(key)
    }

    private fun removeFromSummary(key: SessionKey) {
        if (!summarized.remove(key)) return
        // Um resumo dispensado pelo usuário não volta quando a situação de um agente muda.
        if (summaryPublished && manager.activeNotifications.none { it.tag == SUMMARY_TAG && it.id == SUMMARY_ID }) summarized.clear()
        updateSummary()
    }

    private fun updateSummary() {
        summaryJob?.cancel()
        val count = summarized.size
        if (count == 0) {
            manager.cancel(SUMMARY_TAG, SUMMARY_ID)
            summaryPublished = false
            return
        }
        summaryJob = scope.launch {
            val text = batchAlertText(count)
            val tap = Intent(this@NotificationService, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_NOTIFICATION_LIST, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val notification = privateAlert(this@NotificationService)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this@NotificationService, SUMMARY_ID, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setOnlyAlertOnce(true)
                .build()
            post(SUMMARY_ID, notification, SUMMARY_TAG)
            summaryPublished = true
        }
    }

    private fun post(id: Int, notification: Notification, tag: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try { manager.notify(tag, id, notification) } catch (_: SecurityException) { }
    }

    override fun onDestroy() {
        scope.cancel()
        manager.activeNotifications.forEach { alert ->
            if (alert.notification.channelId == CHANNEL_ALERTS) manager.cancel(alert.tag, alert.id)
        }
        super.onDestroy()
    }

    companion object {
        internal const val CHANNEL_ALERTS = "alerts"
        private const val CHANNEL_CONNECTION = "connection"
        private const val ONGOING_ID = 1
        private const val EXTRA_RESTART = "restart"
        private const val SUMMARY_TAG = "agent-alerts"
        private const val SUMMARY_ID = -1
        internal fun cancelAlert(context: Context, key: SessionKey) =
            NotificationManagerCompat.from(context).cancel(alertId(key))
        private const val TAG = "NotificationService"

        private fun channels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_MIN))
        }

        internal suspend fun startIfConfigured(
            context: Context,
            store: LocalStateStore,
            canStart: () -> Boolean = { true },
        ) {
            if (store.notificationsEnabled() && store.servers().isNotEmpty() && canStart()) {
                start(context)
            }
        }

        fun start(context: Context, restart: Boolean = false): Boolean = try {
            if (!allowed(context)) false
            else {
                val intent = Intent(context, NotificationService::class.java).putExtra(EXTRA_RESTART, restart)
                ContextCompat.startForegroundService(context, intent)
                true
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start notification service", e)
            false
        }

        fun allowed(context: Context): Boolean = try {
            val permissionGranted = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val manager = NotificationManagerCompat.from(context)
            permissionGranted && manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL_ALERTS)?.importance != NotificationManager.IMPORTANCE_NONE
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not check notification delivery", e)
            false
        }
        fun stop(context: Context) = context.stopService(Intent(context, NotificationService::class.java))
    }
}

private fun alertId(key: SessionKey) = key.serialize().hashCode()

private suspend fun privateAlert(context: Context): NotificationCompat.Builder {
    val publicVersion = NotificationCompat.Builder(context, NotificationService.CHANNEL_ALERTS)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(publicAlertText())
        .build()
    return NotificationCompat.Builder(context, NotificationService.CHANNEL_ALERTS)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion)
}

internal suspend fun alertNotification(context: Context, event: AgentEvent, multipleServers: Boolean = false): Pair<Int, Notification> {
    val session = event.session
    val requestId = (event as? AgentEvent.Permission)?.requestId
    val tap = Intent(context, MainActivity::class.java)
        .setData(Uri.Builder().scheme("zaplaud").authority("alert").appendPath(session.key.serialize()).apply {
            requestId?.let { appendPath(it) }
        }.build())
        .putExtra(MainActivity.EXTRA_SESSION, session.key.serialize())
        .putExtra(MainActivity.EXTRA_NOTIFICATION_REQUEST, requestId)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val id = alertId(session.key)
    val text = alertBody(event)
    return id to privateAlert(context)
        .setContentTitle(session.characterName ?: session.title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setSubText(alertSubtitle(event, multipleServers))
        .setContentIntent(PendingIntent.getActivity(context, id, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()
}
