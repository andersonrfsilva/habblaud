package br.com.xlinx.habblaud

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var api: HabblaudApi? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val ongoing = NotificationCompat.Builder(this, CHANNEL_CONNECTION)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(getString(R.string.notifications_running))
            .setContentIntent(open).setOngoing(true).setSilent(true).build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, ONGOING_ID, ongoing, type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive != true) job = scope.launch { watch() }
        return START_STICKY
    }

    private suspend fun watch() {
        val store = LocalStateStore(applicationContext)
        val origin = store.connection()?.origin
        if (origin == null || UrlPolicy.origin(origin) != origin || !store.notificationsEnabled()) { stopSelf(); return }
        val api = HabblaudApi(applicationContext, origin).also { this.api = it }
        val sessions = SessionRepository(api)
        var unauthorized = false
        val streams = ConversationRepository(origin, { api.client() }, { unauthorized = true })
        var previous: Map<SessionKey, AgentWatch>? = null
        while (currentCoroutineContext().isActive) {
            streams.stream("/api/stream").collect { event ->
                // O snapshot depois de uma reconexão compara com o último antes da queda: o que mudou nela também avisa.
                if (event is StreamEvent.Data && event.type == "snapshot") {
                    val current = sessions.snapshot(event.data)
                    previous?.let { before -> agentEvents(before, current).forEach(::notify) }
                    previous = watchOf(current)
                }
            }
            // Fim do stream: login vencido (401) ou erro definitivo. Sem login, para; senão tenta de novo daqui a pouco.
            if (unauthorized) { stopSelf(); return }
            delay(30_000)
        }
    }

    private fun notify(event: AgentEvent) {
        if (MainActivity.visible) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val session = event.session
        val tap = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, session.key.serialize())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = session.key.serialize().hashCode()
        val text = getString(when (event) {
            is AgentEvent.Permission -> if (session.permissionQuestion) R.string.notify_question else R.string.notify_permission
            is AgentEvent.TurnDone -> R.string.notify_done
        })
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(session.characterName ?: session.title)
            .setContentText(text)
            .setSubText(session.project?.substringAfterLast('/'))
            .setContentIntent(PendingIntent.getActivity(this, id, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try { NotificationManagerCompat.from(this).notify(id, notification) } catch (_: SecurityException) { }
    }

    override fun onDestroy() {
        scope.cancel()
        api?.close()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ALERTS = "alerts"
        private const val CHANNEL_CONNECTION = "connection"
        private const val ONGOING_ID = 1

        private fun channels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_MIN))
        }

        fun start(context: Context) { if (allowed(context)) ContextCompat.startForegroundService(context, Intent(context, NotificationService::class.java)) }
        fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        fun stop(context: Context) = context.stopService(Intent(context, NotificationService::class.java))
    }
}
