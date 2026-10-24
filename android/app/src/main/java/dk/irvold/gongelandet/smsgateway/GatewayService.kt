package dk.irvold.gongelandet.smsgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Selve gatewayen: en forgrundstjeneste, der for hver aktiv server holder en long-poll åben, sender de SMS'er,
 * serveren giver den, og kvitterer tilbage. Telefonen forbinder altid UD — serverne behøver ikke kunne nå den.
 * Ændres serverlisten, mens tjenesten kører, sendes [ACTION_RELOAD], og tjenesten tilpasser sig uden at stoppe de uændrede.
 */
class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Pair<Server, Job>>() // kun rørt fra hovedtråden (onStartCommand)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Gateway-status", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIF_ID, notification(summary()), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (wakeLock == null && Prefs.wakeLock(this)) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glsms:gateway").apply { acquire() }
        }
        sync()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Api.abortAll()
        wakeLock?.let { if (it.isHeld) it.release() }
        Status.set { Status.State() }
        super.onDestroy()
    }

    /** Får serverlisten og de kørende long-polls til at passe sammen: nye startes, fjernede/ændrede/slukkede stoppes. */
    private fun sync() {
        val wanted = Prefs.servers(this).filter { it.usable }.associateBy { it.id }
        for ((id, running) in jobs.toMap()) {
            val want = wanted[id]
            if (want == null || want.url != running.first.url || want.key != running.first.key) {
                running.second.cancel()
                Api.abortPoll(id)
                jobs.remove(id)
                Status.removeServer(id)
            }
        }
        for ((id, server) in wanted) {
            if (id !in jobs) {
                Status.setServer(id, false, "Starter …")
                jobs[id] = server to scope.launch { loop(server) }
            }
        }
        Status.set { it.copy(running = true) }
        refreshNotification()
    }

    private suspend fun loop(server: Server) {
        var backoff = 2_000L
        var first = true // første kald vender straks tilbage, så skærmen hurtigt kan vise "Forbundet"
        // Sin egen jobs tilstand (ikke hele tjenestens): en enkelt server kan stoppes, mens de andre kører videre.
        while (currentCoroutineContext().isActive) {
            try {
                Ledger.sweepStale()
                Reporter.flush(this)
                val messages = Api.poll(server, if (first) 0 else WAIT_SECONDS, battery())
                first = false
                // Stoppet, mens vi ventede: send intet. Serveren giver beskeden videre efter lease-tiden.
                currentCoroutineContext().ensureActive()
                publish(server, true, "Forbundet — venter på SMS'er")
                backoff = 2_000L
                for (m in messages) Sender.handle(this, server.id, m)
                if (messages.isNotEmpty()) Reporter.flush(this)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                if (!currentCoroutineContext().isActive) return // stoppet (afbrudt poll giver en IOException)
                publish(server, false, "Serveren afviser nøglen")
                delay(30_000)
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) return
                val unreachable = e is UnknownHostException || e is ConnectException || e is SocketTimeoutException || e is NoRouteToHostException
                publish(server, false, if (unreachable) "Ingen forbindelse til serveren — prøver igen" else "Serverfejl: ${e.message?.take(80)}")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun publish(server: Server, connected: Boolean, text: String) {
        Status.setServer(server.id, connected, text)
        refreshNotification()
    }

    private fun summary(): String {
        val servers = Status.state.value.servers
        return when {
            servers.isEmpty() -> "Ingen aktive servere"
            servers.size == 1 -> servers.values.first().text
            else -> "${servers.values.count { it.connected }} af ${servers.size} servere forbundet"
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(summary()))
    }

    private fun battery(): Int? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("GL-SMS")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "gateway"
        private const val NOTIF_ID = 1
        private const val WAIT_SECONDS = 25

        fun start(c: Context) = ContextCompat.startForegroundService(c, Intent(c, GatewayService::class.java))
        fun stop(c: Context) { c.stopService(Intent(c, GatewayService::class.java)) }
        /** Serverlisten er ændret — gør det kun, mens gatewayen kører. */
        fun reload(c: Context) { if (Status.state.value.running) start(c) }
    }
}
