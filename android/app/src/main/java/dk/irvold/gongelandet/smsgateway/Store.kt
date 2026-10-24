package dk.irvold.gongelandet.smsgateway

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** SIM-valg for en server: følg appens fælles valg (standard). Ellers -1 = telefonens standard-SIM, eller et abonnements-id. */
const val SIM_COMMON = -2

/** En server, telefonen er gateway for. Nøglen ligger kun i appens eget lager. */
data class Server(
    val id: String,
    val name: String,
    val url: String,
    val key: String,
    val enabled: Boolean = true,
    val simSub: Int = SIM_COMMON,
) {
    val label get() = name.ifBlank { url.substringAfter("://").substringBefore('/') }
    val usable get() = enabled && key.isNotBlank() && url.isNotBlank()
}

/** Indstillinger (servere, om gatewayen skal køre m.m.). */
object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("gw", Context.MODE_PRIVATE)

    /** Serverne. Har appen kun den ene server fra før flere servere, overtages den som "s1". */
    fun servers(c: Context): List<Server> {
        val raw = sp(c).getString("servers", null)
        if (raw == null) {
            val url = sp(c).getString("server", "") ?: ""
            val key = sp(c).getString("key", "") ?: ""
            if (url.isBlank() || key.isBlank()) return emptyList()
            return listOf(Server("s1", "", url, key)).also { setServers(c, it) }
        }
        return try {
            val a = JSONArray(raw)
            List(a.length()) {
                val o = a.getJSONObject(it)
                Server(o.getString("id"), o.optString("name"), o.getString("url"), o.getString("key"), o.optBoolean("enabled", true), o.optInt("simSub", SIM_COMMON))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setServers(c: Context, list: List<Server>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("key", it.key).put("enabled", it.enabled).put("simSub", it.simSub)) }
        sp(c).edit().putString("servers", a.toString()).apply()
    }

    fun server(c: Context, id: String): Server? = servers(c).firstOrNull { it.id == id }
    fun newServerId(c: Context): String {
        val used = servers(c).map { it.id }.toSet()
        var n = 1
        while ("s$n" in used) n++
        return "s$n"
    }

    fun enabled(c: Context): Boolean = sp(c).getBoolean("enabled", false)
    fun maxPerHour(c: Context): Int = sp(c).getInt("maxPerHour", 60)
    fun wakeLock(c: Context): Boolean = sp(c).getBoolean("wakeLock", true)
    /** Abonnements-id på det SIM-kort, der sender; -1 = telefonens standard-SIM til SMS. */
    fun simSub(c: Context): Int = sp(c).getInt("simSub", -1)
    fun setSimSub(c: Context, v: Int) = sp(c).edit().putInt("simSub", v).apply()

    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()
    fun setMaxPerHour(c: Context, v: Int) = sp(c).edit().putInt("maxPerHour", v).apply()
    fun setWakeLock(c: Context, v: Boolean) = sp(c).edit().putBoolean("wakeLock", v).apply()
}

/** Gatewayens tilstand, som skærmen og notifikationen viser. */
object Status {
    data class ServerState(val connected: Boolean = false, val text: String = "", val lastContact: Long? = null)

    data class State(
        val running: Boolean = false,
        /** Pr. server-id. */
        val servers: Map<String, ServerState> = emptyMap(),
    )

    val state = MutableStateFlow(State())
    fun set(f: (State) -> State) { state.value = f(state.value) }

    fun setServer(id: String, connected: Boolean, text: String) = set { s ->
        val old = s.servers[id]
        s.copy(servers = s.servers + (id to ServerState(connected, text, if (connected) System.currentTimeMillis() else old?.lastContact)))
    }

    fun removeServer(id: String) = set { it.copy(servers = it.servers - id) }
}

/**
 * Husker hver SMS, telefonen har fået fra serveren — både til loggen på skærmen og for at undgå, at den
 * samme SMS sendes to gange, hvis serveren (efter en timeout) giver den videre igen.
 * Telefonnumre gemmes kun maskeret (fx "+45 ••••5678"), og selve teksten gemmes slet ikke.
 */
data class Entry(
    val id: String,
    val to: String,
    val kind: String,
    val at: Long,
    /** sending → sent → delivered, eller failed. */
    var status: String,
    var error: String? = null,
    var parts: Int = 0,
    var sentParts: Int = 0,
    var deliveredParts: Int = 0,
    /** Hvilken status serveren sidst har fået at vide. */
    var reported: String? = null,
    /** Tæller med i timegrænsen (er faktisk forsøgt sendt). */
    val counted: Boolean = true,
    /** Testbesked fra skærmen — rapporteres ikke til serveren. */
    val local: Boolean = false,
    /** Hvilken server beskeden kom fra, og dens id dér (id'er fra forskellige servere kan være ens, så [id] er "server-id"). */
    val server: String = "",
    val remote: String = id,
)

object Ledger {
    private const val MAX = 300
    private const val STALE_MS = 3 * 60 * 1000L

    private val lock = Any()
    private lateinit var file: File
    private var entries = mutableListOf<Entry>()
    val log = MutableStateFlow<List<Entry>>(emptyList())

    fun init(c: Context) = synchronized(lock) {
        file = File(c.applicationContext.filesDir, "ledger.json")
        entries = try {
            val arr = JSONArray(file.takeIf { it.exists() }?.readText() ?: "[]")
            MutableList(arr.length()) { fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            mutableListOf()
        }
        log.value = entries.toList()
    }

    private fun toJson(e: Entry) = JSONObject().apply {
        put("id", e.id); put("to", e.to); put("kind", e.kind); put("at", e.at); put("status", e.status)
        put("error", e.error ?: JSONObject.NULL); put("parts", e.parts); put("sentParts", e.sentParts)
        put("deliveredParts", e.deliveredParts); put("reported", e.reported ?: JSONObject.NULL)
        put("counted", e.counted); put("local", e.local); put("server", e.server); put("remote", e.remote)
    }

    private fun fromJson(o: JSONObject) = Entry(
        id = o.getString("id"), to = o.getString("to"), kind = o.getString("kind"), at = o.getLong("at"),
        status = o.getString("status"), error = o.optString("error").takeIf { it.isNotEmpty() && it != "null" },
        parts = o.optInt("parts"), sentParts = o.optInt("sentParts"), deliveredParts = o.optInt("deliveredParts"),
        reported = o.optString("reported").takeIf { it.isNotEmpty() && it != "null" },
        counted = o.optBoolean("counted", true), local = o.optBoolean("local", false),
        // Poster fra før flere servere hørte til den ene server, "s1".
        server = o.optString("server").ifEmpty { if (o.optBoolean("local", false)) "" else "s1" },
        remote = o.optString("remote").ifEmpty { o.getString("id") },
    )

    // Kaldes med låsen taget: gemmer på disken (via en temp-fil, så et nedbrud ikke ødelægger loggen)
    // og opdaterer skærmen.
    private fun commit() {
        while (entries.size > MAX) entries.removeAt(0)
        try {
            val tmp = File(file.parentFile, "ledger.json.tmp")
            tmp.writeText(JSONArray().also { a -> entries.forEach { a.put(toJson(it)) } }.toString())
            tmp.renameTo(file)
        } catch (_: Exception) { /* loggen er en bekvemmelighed — SMS'en er allerede afsted */ }
        log.value = entries.map { it.copy() }
    }

    fun get(id: String): Entry? = synchronized(lock) { entries.firstOrNull { it.id == id }?.copy() }

    fun add(e: Entry) = synchronized(lock) { entries.add(e); commit() }

    fun update(id: String, f: (Entry) -> Unit) = synchronized(lock) {
        entries.firstOrNull { it.id == id }?.let { f(it); commit() }
    }

    fun fail(id: String, error: String) = update(id) {
        if (it.status != "failed") { it.status = "failed"; it.error = error }
    }

    /** En del af SMS'en er afsendt; er alle dele det, er den "sent". */
    fun partSent(id: String) = update(id) {
        it.sentParts += 1
        if (it.status == "sending" && it.parts > 0 && it.sentParts >= it.parts) it.status = "sent"
    }

    /** Modtagerens telefon har kvitteret for en del; er alle dele leveret, er den "delivered". */
    fun partDelivered(id: String) = update(id) {
        it.deliveredParts += 1
        if (it.status != "failed" && it.parts > 0 && it.deliveredParts >= it.parts) it.status = "delivered"
    }

    /** Kvitteringer, serveren endnu ikke har fået. */
    fun pendingReports(): List<Entry> = synchronized(lock) {
        entries.filter { !it.local && it.status != "sending" && it.reported != it.status }.map { it.copy() }
    }

    fun markReported(id: String, status: String) = update(id) { it.reported = status }

    /** Serveren gav en SMS videre, vi allerede kender: send ikke igen, men kvittér for den på ny. */
    fun forceReport(id: String) = update(id) { if (it.status != "sending") it.reported = null }

    fun countLastHour(): Int = synchronized(lock) {
        val since = System.currentTimeMillis() - 3_600_000L
        entries.count { it.counted && !it.local && it.at > since }
    }

    fun sentToday(): Int = synchronized(lock) {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        entries.count { !it.local && it.at >= cal.timeInMillis && (it.status == "sent" || it.status == "delivered") }
    }

    /** Hvis mobilnettet aldrig svarer på en SMS, så den ikke bliver stående som "sender" for evigt. */
    fun sweepStale() = synchronized(lock) {
        val limit = System.currentTimeMillis() - STALE_MS
        var changed = false
        entries.filter { it.status == "sending" && it.at < limit }.forEach {
            it.status = "failed"; it.error = "Intet svar fra mobilnettet"; changed = true
        }
        if (changed) commit()
    }
}
