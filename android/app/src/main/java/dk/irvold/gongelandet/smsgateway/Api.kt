package dk.irvold.gongelandet.smsgateway

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Serveren afviste nøglen — hjælper ikke at prøve igen, før den er rettet. */
class AuthException : IOException("Serveren afviser nøglen")

data class Msg(val id: String, val to: String, val text: String, val kind: String, val expiresAt: Long)

/** Telefonens side af API'et: /api/sms-gateway/{ping,poll,report} (backend/src/routes/sms.js). */
object Api {
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    /** Lokale adresser, hvor klartekst (http://) er forsvarligt: lokalnet, Tailscale, localhost og enkeltstående navne som "min-pc". */
    private fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".ts.net")) return true
        if (!h.contains('.') && !h.contains(':')) return true
        val m = IPV4.matchEntire(h) ?: return false
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }

    /** Giver en fejltekst, hvis adressen ikke må bruges (null = i orden). http:// kun til lokale adresser, ellers https://. */
    fun checkServer(server: String): String? {
        val s = server.trim()
        if (s.isEmpty()) return "Angiv en server-adresse"
        val uri = runCatching { java.net.URI(if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s") }.getOrNull()
        val host = uri?.host ?: return "Ugyldig adresse"
        if (uri.scheme == "http" && !isLocalHost(host)) return "http:// er kun tilladt til servere på lokalnettet (fx 192.168.x.x). Brug https:// til internettet."
        return null
    }

    private fun base(server: String): String {
        val s = server.trim().trimEnd('/')
        return if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
    }

    // Den igangværende long-poll, så den kan afbrydes med det samme, når gatewayen stoppes (en blokeret
    // læsning kan ellers ikke afbrydes, og beskeden ville blive sendt efter, at brugeren trykkede stop).
    private val pollConns = java.util.concurrent.ConcurrentHashMap<String, HttpURLConnection>()

    fun abortPoll(serverId: String) { pollConns.remove(serverId)?.disconnect() }
    fun abortAll() { pollConns.keys.toList().forEach { abortPoll(it) } }

    private fun request(server: String, key: String, path: String, post: JSONObject? = null, readTimeoutMs: Int = 20_000, pollOf: String? = null): JSONObject {
        checkServer(server)?.let { throw IOException(it) }
        val conn = URL(base(server) + path).openConnection() as HttpURLConnection
        if (pollOf != null) pollConns[pollOf] = conn
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("User-Agent", "GL-SMS/${BuildConfig.VERSION_NAME}")
            conn.setRequestProperty("Accept", "application/json")
            if (post != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(post.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code == 401) throw AuthException()
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("Serveren svarede $code")
            return JSONObject(body)
        } finally {
            conn.disconnect()
            if (pollOf != null) pollConns.remove(pollOf, conn)
        }
    }

    /** Prøver nøglen mod serveren. Giver telefonens navn, som det står i serverens liste. */
    fun ping(server: String, key: String): String =
        request(server, key, "/api/sms-gateway/ping?v=${BuildConfig.VERSION_NAME}").getJSONObject("gateway").getString("name")

    /** Long-poll: svarer først, når der er en SMS — eller efter [wait] sekunder med en tom liste. */
    fun poll(server: Server, wait: Int, battery: Int?): List<Msg> {
        val q = buildString {
            append("/api/sms-gateway/poll?wait=$wait&v=${BuildConfig.VERSION_NAME}")
            if (battery != null && battery in 0..100) append("&battery=$battery")
        }
        val arr: JSONArray = request(server.url, server.key, q, readTimeoutMs = (wait + 15) * 1000, pollOf = server.id).getJSONArray("messages")
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Msg(
                id = o.getString("id"), to = o.getString("to"), text = o.getString("text"), kind = o.optString("kind", "free"),
                expiresAt = runCatching { Instant.parse(o.getString("expiresAt")).toEpochMilli() }.getOrDefault(Long.MAX_VALUE),
            )
        }
    }

    fun report(server: Server, reports: List<Entry>) {
        val arr = JSONArray()
        reports.forEach {
            arr.put(JSONObject().put("id", it.remote).put("status", it.status).apply { it.error?.let { e -> put("error", e.take(300)) } })
        }
        request(server.url, server.key, "/api/sms-gateway/report", post = JSONObject().put("reports", arr))
    }
}

/** Sender kvitteringer (sendt / leveret / fejlet) til serveren; de, der ikke lykkes, prøves igen næste gang. */
object Reporter {
    private val mutex = Mutex()

    suspend fun flush(c: Context): Boolean = mutex.withLock {
        val pending = Ledger.pendingReports()
        if (pending.isEmpty()) return@withLock true
        val servers = Prefs.servers(c).associateBy { it.id }
        var allOk = true
        // Hver servers kvitteringer går til netop den server — en fejl hos én må ikke stoppe de andre.
        pending.groupBy { it.server }.forEach { (serverId, entries) ->
            val server = servers[serverId]
            if (server == null) {
                // Serveren er fjernet fra appen: ingen at kvittere til.
                entries.forEach { Ledger.markReported(it.id, it.status) }
                return@forEach
            }
            try {
                // Højst 50 ad gangen — det er serverens loft pr. kald.
                entries.chunked(50).forEach { chunk ->
                    Api.report(server, chunk)
                    chunk.forEach { Ledger.markReported(it.id, it.status) }
                }
            } catch (_: Exception) {
                allOk = false
            }
        }
        allOk
    }
}
