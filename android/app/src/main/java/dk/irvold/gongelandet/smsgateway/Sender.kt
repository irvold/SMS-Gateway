package dk.irvold.gongelandet.smsgateway

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GlSmsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Ledger.init(this)
    }
}

/** Sender SMS'er fra telefonens eget SIM-kort og holder styr på, hvad der er sendt. */
object Sender {
    const val ACTION_SENT = "dk.irvold.gongelandet.smsgateway.SENT"
    const val ACTION_DELIVERED = "dk.irvold.gongelandet.smsgateway.DELIVERED"

    fun hasPermission(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun mask(to: String) = if (to.length > 6) "${to.take(3)} ••••${to.takeLast(4)}" else "••••"

    class Sim(val subId: Int, val label: String)

    /** De SIM-kort, der sidder i telefonen nu. Kræver tilladelsen til telefonstatus — ellers en tom liste. */
    fun sims(c: Context): List<Sim> {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return try {
            c.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
                .sortedBy { it.simSlotIndex }
                .map { Sim(it.subscriptionId, "SIM ${it.simSlotIndex + 1} — ${it.displayName}") }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    // Er et bestemt SIM-kort valgt, sendes der kun derfra — findes det ikke længere, fejler beskeden i stedet for
    // i det stille at blive sendt fra et andet nummer (og en anden regning).
    private fun manager(c: Context, serverId: String?): SmsManager {
        // En server kan have sit eget SIM-valg; ellers gælder appens fælles valg.
        val own = serverId?.let { Prefs.server(c, it)?.simSub } ?: SIM_COMMON
        val sub = if (own == SIM_COMMON) Prefs.simSub(c) else own
        if (sub >= 0) {
            if (sims(c).none { it.subId == sub }) throw IllegalStateException("Det valgte SIM-kort findes ikke — vælg SIM igen i appen")
            return if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java).createForSubscriptionId(sub)
            else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(sub)
        }
        return if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
    }

    /** En besked fra serveren. Sendes højst én gang, uanset hvor mange gange serveren leverer den. */
    fun handle(c: Context, serverId: String, m: Msg) {
        // Serverne nummererer hver for sig (fx begge "m000001"), så posten hedder "server-id".
        val local = "$serverId-${m.id}"
        val known = Ledger.get(local) ?: Ledger.get(m.id)?.takeIf { it.server == serverId } // sidstnævnte: poster fra før flere servere
        if (known != null) {
            Ledger.forceReport(known.id)
            return
        }
        val now = System.currentTimeMillis()
        fun entry(status: String, error: String? = null, counted: Boolean = true) =
            Entry(local, mask(m.to), m.kind, now, status, error, reported = null, counted = counted, server = serverId, remote = m.id)

        when {
            m.expiresAt < now -> Ledger.add(entry("failed", "Udløbet, før den blev sendt", counted = false))
            !hasPermission(c) -> Ledger.add(entry("failed", "Appen har ikke tilladelse til at sende SMS", counted = false))
            Ledger.countLastHour() >= Prefs.maxPerHour(c) -> Ledger.add(entry("failed", "Timegrænsen i appen er nået (${Prefs.maxPerHour(c)}/time)", counted = false))
            else -> {
                // Først i loggen, så et nedbrud midt i afsendelsen ikke kan give en dobbelt-SMS bagefter.
                Ledger.add(entry("sending"))
                dispatch(c, local, m.to, m.text, serverId)
            }
        }
    }

    /** Testbesked fra skærmen: samme vej ud, men uden server og uden kvittering til den. */
    fun sendTest(c: Context, to: String, text: String): String? {
        if (!hasPermission(c)) return "Giv først appen tilladelse til at sende SMS"
        val number = to.replace(Regex("[\\s().-]"), "")
        if (!Regex("^\\+?\\d{8,15}$").matches(number)) return "Ugyldigt telefonnummer"
        val id = "test-${System.currentTimeMillis()}"
        Ledger.add(Entry(id, mask(number), "test", System.currentTimeMillis(), "sending", counted = false, local = true))
        dispatch(c, id, number, text, null)
        return null
    }

    private fun dispatch(c: Context, id: String, to: String, text: String, serverId: String?) {
        try {
            val sms = manager(c, serverId)
            val parts = sms.divideMessage(text)
            Ledger.update(id) { it.parts = parts.size }
            val sent = ArrayList<PendingIntent>()
            val delivered = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                sent += pending(c, ACTION_SENT, id, i)
                delivered += pending(c, ACTION_DELIVERED, id, i)
            }
            if (parts.size == 1) sms.sendTextMessage(to, null, parts[0], sent[0], delivered[0])
            else sms.sendMultipartTextMessage(to, null, parts, sent, delivered)
        } catch (e: SecurityException) {
            // SmsManager.divideMessage slår telefonens operatør op, når teksten har tegn uden for GSM-alfabetet.
            android.util.Log.w("GlSms", "Afsendelse fejlede", e)
            Ledger.fail(id, "Teksten har særlige tegn — giv appen tilladelse til telefonstatus")
        } catch (e: Exception) {
            android.util.Log.w("GlSms", "Afsendelse fejlede", e)
            Ledger.fail(id, e.message?.take(120) ?: "Kunne ikke sende")
        }
    }

    // Hver del af hver SMS får sin egen PendingIntent (adskilt på data-adressen).
    private fun pending(c: Context, action: String, id: String, part: Int): PendingIntent {
        val intent = Intent(c, SmsResultReceiver::class.java).setAction(action).setData(Uri.parse("glsms://$id/$part"))
        return PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun reason(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Mobilnettet afviste SMS'en"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Flytilstand er slået til"
        SmsManager.RESULT_ERROR_NULL_PDU -> "Ugyldig besked"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "Ingen dækning"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Android har spærret for flere SMS'er (for mange på kort tid)"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "Android afviste kortnummeret"
        else -> "Fejl $code fra mobilnettet"
    }
}

/** "Afsendt" og "leveret" fra mobilnettet — opdaterer loggen og giver straks serveren besked. */
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.host ?: return
        val ok = resultCode == Activity.RESULT_OK
        when (intent.action) {
            Sender.ACTION_SENT -> if (ok) Ledger.partSent(id) else Ledger.fail(id, Sender.reason(resultCode))
            Sender.ACTION_DELIVERED -> if (ok) Ledger.partDelivered(id) else Ledger.fail(id, "Ikke leveret til modtageren")
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { Reporter.flush(context.applicationContext) } finally { pending.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.enabled(context) && Prefs.servers(context).any { it.usable }) GatewayService.start(context)
    }
}
