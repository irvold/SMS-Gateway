package dk.irvold.gongelandet.smsgateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF2F7A3D)
private val Red = Color(0xFFB5202F)
private val Orange = Color(0xFFC27C0E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(primary = Color(0xFF7BC98A)) else lightColorScheme(primary = Green),
            ) {
                Column(Modifier.background(MaterialTheme.colorScheme.background).safeDrawingPadding()) { Screen() }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale("da", "DK"))
private val shortFmt = SimpleDateFormat("d. MMM HH:mm", Locale("da", "DK"))

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Status.state.collectAsStateWithLifecycle()
    val log by Ledger.log.collectAsStateWithLifecycle()

    // Tilladelserne kan ændres i Indstillinger, så de læses igen, hver gang appen kommer frem.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val smsOk = remember(tick) { Sender.hasPermission(ctx) }
    val phoneOk = remember(tick) { ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED }
    val notifOk = remember(tick) { Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    val batteryOk = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }

    // Alle tekstfelter er låst, til man trykker «Ret» — så man ikke kommer til at rette noget ved et uheld.
    val servers = remember { mutableStateListOf<Server>().apply { addAll(Prefs.servers(ctx)) } }
    var adding by remember { mutableStateOf(false) }
    val sims = remember(tick) { Sender.sims(ctx) }
    // Serverlisten gemmes, og kører gatewayen, tilpasser den sig med det samme.
    fun persist() { Prefs.setServers(ctx, servers.toList()); GatewayService.reload(ctx) }
    var editMax by remember { mutableStateOf(false) }
    var savedMax by remember { mutableIntStateOf(Prefs.maxPerHour(ctx)) }
    var maxText by remember { mutableStateOf(savedMax.toString()) }
    var maxError by remember { mutableStateOf<String?>(null) }
    var wake by remember { mutableStateOf(Prefs.wakeLock(ctx)) }
    var simSel by remember { mutableIntStateOf(Prefs.simSub(ctx)) }
    var editSim by remember { mutableStateOf(false) }
    var simDraft by remember { mutableIntStateOf(simSel) }
    var editTest by remember { mutableStateOf(false) }
    var testTo by remember { mutableStateOf("") }
    var testDraft by remember { mutableStateOf("") }
    var testMsg by remember { mutableStateOf<String?>(null) }

    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val phoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GL-SMS", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("SMS-gateway: serveren bestemmer hvad der sendes og til hvem — telefonen sender det fra sit eget SIM-kort.", fontSize = 14.sp)

        // ---- Status ----
        val usable = servers.count { it.usable }
        val connected = status.servers.values.count { it.connected }
        val dot = when { !status.running -> Color.Gray; usable > 0 && connected == usable -> Green; connected > 0 -> Orange; else -> Red }
        val headline = when {
            !status.running -> "Stoppet"
            usable == 0 -> "Ingen aktive servere"
            usable == 1 -> status.servers.values.firstOrNull()?.text ?: "Starter …"
            else -> "Forbundet til $connected af $usable servere"
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(12.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.size(10.dp))
                    Text(headline, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("Sendt i dag: ${log.count { !it.local && it.at >= startOfToday() && (it.status == "sent" || it.status == "delivered") }} · seneste time: ${log.count { it.counted && !it.local && it.at > System.currentTimeMillis() - 3_600_000L }} af højst $savedMax", fontSize = 13.sp)
                if (status.running) {
                    OutlinedButton(onClick = { Prefs.setEnabled(ctx, false); GatewayService.stop(ctx) }) { Text("Stop gateway") }
                } else {
                    Button(
                        enabled = usable > 0 && smsOk,
                        onClick = { Prefs.setEnabled(ctx, true); GatewayService.start(ctx) },
                    ) { Text("Start gateway") }
                    if (usable == 0) Text("Tryk «Tilføj server» under «Servere», og indtast server-adresse og nøgle først.", fontSize = 13.sp)
                    else if (!smsOk) Text("Giv appen tilladelse til at sende SMS herunder først.", fontSize = 13.sp)
                }
            }
        }

        // ---- Servere ----
        Section("Servere") {
            Text("Telefonen kan være gateway for flere servere på én gang. Hver server får sin egen forbindelse og sine egne kvitteringer.", fontSize = 12.sp)
            servers.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider()
                key(s.id) {
                    ServerBlock(
                        server = s, state = status.servers[s.id], running = status.running, others = servers.filter { it.id != s.id },
                        sims = sims, phoneOk = phoneOk,
                        onSave = { updated -> servers[i] = updated; persist() },
                        onRemove = { servers.removeAt(i); persist() },
                        onCancelNew = {},
                    )
                }
            }
            if (adding) {
                if (servers.isNotEmpty()) HorizontalDivider()
                ServerBlock(
                    server = null, state = null, running = status.running, others = servers.toList(), sims = sims, phoneOk = phoneOk,
                    onSave = { added -> servers.add(added); adding = false; persist() },
                    onRemove = {}, onCancelNew = { adding = false },
                )
            } else {
                OutlinedButton(onClick = { adding = true }) { Text("Tilføj server") }
            }
        }

        // ---- Indstillinger ----
        Section("Indstillinger") {
            LockedField(
                rows = listOf("Højst så mange SMS'er pr. time" to savedMax.toString()),
                editing = editMax,
                onEdit = { maxError = null; editMax = true },
                okEnabled = maxText.isNotBlank(),
                onOk = {
                    val n = maxText.toIntOrNull()
                    if (n == null || n !in 1..1000) maxError = "Skriv et tal mellem 1 og 1000."
                    else { Prefs.setMaxPerHour(ctx, n); savedMax = n; maxError = null; editMax = false }
                },
                onCancel = { maxText = savedMax.toString(); maxError = null; editMax = false },
            ) { focus ->
                InputField(
                    value = maxText, onValueChange = { maxText = it.filter { c -> c.isDigit() }.take(4) },
                    label = "Højst så mange SMS'er pr. time", keyboardType = KeyboardType.Number, focus = focus,
                    onDone = {
                        val n = maxText.toIntOrNull()
                        if (n == null || n !in 1..1000) maxError = "Skriv et tal mellem 1 og 1000."
                        else { Prefs.setMaxPerHour(ctx, n); savedMax = n; maxError = null; editMax = false }
                    },
                )
            }
            maxError?.let { Text(it, fontSize = 13.sp, color = Red) }
            Text("Sikkerhedsnet mod en fejl på serveren, der sender i ring. Resten afvises.", fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hold telefonen vågen", fontWeight = FontWeight.Medium)
                    Text("Anbefalet, når telefonen sidder i en lader — ellers kan SMS'er komme sent, når skærmen er slukket. Gælder næste gang gatewayen startes.", fontSize = 12.sp)
                }
                Switch(checked = wake, onCheckedChange = { wake = it; Prefs.setWakeLock(ctx, it) })
            }
        }

        // ---- SIM-kort ----
        Section("Fælles SIM-kort til afsendelse") {
            Text("Bruges af alle servere, der ikke har valgt deres eget.", fontSize = 12.sp)
            if (!phoneOk) {
                Text("Giv tilladelse til telefonstatus herunder for at kunne vælge SIM. Indtil da sendes der fra telefonens standard-SIM til SMS.", fontSize = 13.sp)
            } else if (sims.isEmpty()) {
                Text("Ingen SIM-kort fundet.", fontSize = 13.sp)
            } else {
                val shown = if (simSel < 0) "Telefonens standard-SIM til SMS" else sims.firstOrNull { it.subId == simSel }?.label ?: "Det valgte SIM-kort sidder ikke i telefonen"
                LockedField(
                    rows = listOf("Sender fra" to shown),
                    editing = editSim,
                    onEdit = { simDraft = simSel; editSim = true },
                    onOk = { simSel = simDraft; Prefs.setSimSub(ctx, simDraft); editSim = false },
                    onCancel = { editSim = false },
                ) { _ ->
                    SimOption("Telefonens standard-SIM til SMS", simDraft < 0) { simDraft = -1 }
                    sims.forEach { sim -> SimOption(sim.label, simDraft == sim.subId) { simDraft = sim.subId } }
                }
                if (!editSim && simSel >= 0 && sims.none { it.subId == simSel }) Text("Der sendes intet, før du trykker «Ret» og vælger et SIM-kort, der sidder i telefonen.", fontSize = 13.sp, color = Red)
            }
        }

        // ---- Tilladelser ----
        Section("Tilladelser") {
            PermissionRow("Send SMS", smsOk, "Giv tilladelse") { smsLauncher.launch(Manifest.permission.SEND_SMS) }
            if (!smsOk) {
                Text(
                    "Vises der ingen dialog? Så har Android spærret en app, der ikke kommer fra Play: Indstillinger → Apps → GL-SMS → ⋮ (øverst til højre) → «Tillad begrænsede indstillinger», og prøv igen.",
                    fontSize = 12.sp,
                )
                TextButton(onClick = { openAppSettings(ctx) }) { Text("Åbn app-indstillinger") }
            }
            PermissionRow("Telefonstatus (til tekster med emoji og andre særlige tegn)", phoneOk, "Giv tilladelse") { phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE) }
            if (Build.VERSION.SDK_INT >= 33) PermissionRow("Notifikation (viser at gatewayen kører)", notifOk, "Giv tilladelse") { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            PermissionRow("Undtaget fra batterioptimering", batteryOk, "Undtag") {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }
        }

        // ---- Test ----
        Section("Test SIM-kortet") {
            LockedField(
                rows = listOf("Telefonnummer" to testTo.ifBlank { "Ikke angivet" }),
                editing = editTest,
                onEdit = { testDraft = testTo; editTest = true },
                onOk = { testTo = testDraft.trim(); editTest = false },
                onCancel = { editTest = false },
            ) { focus ->
                InputField(
                    value = testDraft, onValueChange = { testDraft = it }, label = "Telefonnummer", keyboardType = KeyboardType.Phone,
                    focus = focus, onDone = { testTo = testDraft.trim(); editTest = false },
                )
            }
            Button(
                enabled = testTo.isNotBlank() && !editTest,
                onClick = { testMsg = Sender.sendTest(ctx, testTo, "Test fra GL-SMS.") ?: "Sendt — se loggen herunder." },
            ) { Text("Send test-SMS") }
            testMsg?.let { Text(it, fontSize = 13.sp) }
        }

        // ---- Log ----
        Section("Seneste SMS'er") {
            if (log.isEmpty()) Text("Ingen endnu.", fontSize = 13.sp)
            log.asReversed().take(30).forEach { e ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("${e.to} · ${if (e.local) "test" else e.kind}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(statusLabel(e.status), fontSize = 14.sp, color = if (e.status == "failed") Red else Green, fontWeight = FontWeight.SemiBold)
                    }
                    Text(shortFmt.format(Date(e.at)) + (e.error?.let { " — $it" } ?: ""), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("GL-SMS ${BuildConfig.VERSION_NAME}", fontSize = 11.sp)
    }
}

private fun statusLabel(s: String) = when (s) {
    "sending" -> "Sender …"
    "sent" -> "Sendt"
    "delivered" -> "Leveret"
    "failed" -> "Fejlet"
    else -> s
}

private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

private fun openAppSettings(c: Context) {
    c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")))
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/**
 * Et inputfelt, der er låst, til man trykker «Ret». Så er feltet åbent og har fokus; «OK» gemmer og låser igen,
 * «Annullér» kasserer rettelsen. Låst vises kun værdien som tekst, så intet felt har fokus, mens man blot kigger i appen.
 */
@Composable
private fun LockedField(
    rows: List<Pair<String, String>>,
    editing: Boolean,
    onEdit: () -> Unit,
    onOk: () -> Unit,
    onCancel: () -> Unit,
    okLabel: String = "OK",
    okEnabled: Boolean = true,
    editor: @Composable (FocusRequester) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(editing) { if (editing) runCatching { focus.requestFocus() } }
    if (!editing) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (label, value) ->
                    Column {
                        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, fontSize = 16.sp)
                    }
                }
            }
            OutlinedButton(onClick = onEdit) { Text("Ret") }
        }
    } else {
        editor(focus)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = okEnabled, onClick = onOk) { Text(okLabel) }
            TextButton(onClick = onCancel) { Text("Annullér") }
        }
    }
}

@Composable
private fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    focus: FocusRequester? = null,
    onDone: () -> Unit = {},
    placeholder: String? = null,
    supporting: String? = null,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true,
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = visual,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().let { if (focus != null) it.focusRequester(focus) else it },
    )
}

/**
 * Én server telefonen er gateway for: status, aktiv/slået fra, forbindelse (navn, adresse, nøgle) og eget SIM-valg.
 * Som alle felter er forbindelsen og SIM-valget låst, til man trykker «Ret». [server] = null er en ny server, der endnu ikke er gemt.
 */
@Composable
private fun ServerBlock(
    server: Server?,
    state: Status.ServerState?,
    running: Boolean,
    others: List<Server>,
    sims: List<Sender.Sim>,
    phoneOk: Boolean,
    onSave: (Server) -> Unit,
    onRemove: () -> Unit,
    onCancelNew: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isNew = server == null
    var editing by remember { mutableStateOf(isNew) }
    var name by remember { mutableStateOf(server?.name ?: "") }
    var url by remember { mutableStateOf(server?.url ?: BuildConfig.API_BASE) }
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var msgOk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var editSim by remember { mutableStateOf(false) }
    var simDraft by remember { mutableIntStateOf(server?.simSub ?: SIM_COMMON) }
    val savedKey = server?.key ?: ""

    // Gem og test: nøglen prøves mod serveren, før noget gemmes. Feltet låses først, når det lykkes.
    fun test() {
        msg = null
        val problem = Api.checkServer(url)
        if (problem != null) { msgOk = false; msg = problem; return }
        val address = url.trim()
        val key = keyInput.trim().ifEmpty { savedKey }
        if (others.any { it.url.trimEnd('/') == address.trimEnd('/') && it.key == key }) { msgOk = false; msg = "Den server er allerede tilføjet."; return }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Api.ping(address, key) } }
            busy = false
            result.onSuccess { gateway ->
                val saved = server?.copy(name = name.trim(), url = address, key = key) ?: Server(Prefs.newServerId(ctx), name.trim(), address, key)
                keyInput = ""; showKey = false; editing = false
                msgOk = true; msg = "Forbundet som «$gateway». Gemt."
                onSave(saved)
            }.onFailure { e ->
                msgOk = false
                msg = if (e is AuthException) "Serveren kender ikke nøglen." else "Kunne ikke nå serveren: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun simLabel(sub: Int) = when {
        sub == SIM_COMMON -> "Fælles valg"
        sub < 0 -> "Telefonens standard-SIM til SMS"
        else -> sims.firstOrNull { it.subId == sub }?.label ?: "Det valgte SIM-kort sidder ikke i telefonen"
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (server != null) {
            val dot = when { !server.enabled || !running -> Color.Gray; state?.connected == true -> Green; else -> Red }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(12.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(server.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            !server.enabled -> "Slået fra"
                            !running -> "Gatewayen er stoppet"
                            else -> state?.text ?: "Starter …"
                        },
                        fontSize = 13.sp,
                    )
                    state?.lastContact?.takeIf { running && server.enabled }?.let { Text("Sidst i kontakt: ${timeFmt.format(Date(it))}", fontSize = 12.sp) }
                }
                Switch(checked = server.enabled, onCheckedChange = { onSave(server.copy(enabled = it)) })
            }
        } else {
            Text("Ny server", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        LockedField(
            rows = listOf(
                "Navn" to (server?.name?.ifBlank { "(bruger adressen)" } ?: ""),
                "Server-adresse" to (server?.url ?: ""),
                "Nøgle" to if (savedKey.isBlank()) "Ikke indstillet" else "••••••••${savedKey.takeLast(4)}",
            ),
            editing = editing,
            onEdit = { msg = null; editing = true },
            okLabel = if (busy) "Prøver …" else "Gem og test",
            okEnabled = !busy && url.isNotBlank() && (keyInput.isNotBlank() || savedKey.isNotBlank()),
            onOk = { test() },
            onCancel = {
                if (isNew) onCancelNew()
                else { name = server?.name ?: ""; url = server?.url ?: ""; keyInput = ""; showKey = false; msg = null; editing = false }
            },
        ) { focus ->
            InputField(value = name, onValueChange = { name = it }, label = "Navn (valgfrit)", imeAction = ImeAction.Next, focus = focus, placeholder = "fx Gøngelandet")
            InputField(
                value = url, onValueChange = { url = it }, label = "Server-adresse", keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next, placeholder = "https://api.eksempel.dk",
                supporting = "https:// — eller http:// til en server på lokalnettet, fx http://192.168.1.20:4100",
            )
            InputField(
                value = keyInput, onValueChange = { keyInput = it },
                label = if (savedKey.isBlank()) "Nøgle (glsms_…)" else "Ny nøgle (tom = behold …${savedKey.takeLast(4)})",
                keyboardType = KeyboardType.Password, onDone = { if (!busy) test() },
                visual = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Skjul" else "Vis") } },
            )
        }
        msg?.let { Text(it, fontSize = 13.sp, color = if (msgOk) Green else Red) }

        if (server != null) {
            LockedField(
                rows = listOf("SIM-kort til denne server" to simLabel(server.simSub)),
                editing = editSim,
                onEdit = { simDraft = server.simSub; editSim = true },
                onOk = { onSave(server.copy(simSub = simDraft)); editSim = false },
                onCancel = { editSim = false },
            ) { _ ->
                SimOption("Fælles valg (følger «Fælles SIM-kort»)", simDraft == SIM_COMMON) { simDraft = SIM_COMMON }
                SimOption("Telefonens standard-SIM til SMS", simDraft == -1) { simDraft = -1 }
                if (phoneOk) sims.forEach { sim -> SimOption(sim.label, simDraft == sim.subId) { simDraft = sim.subId } }
                else Text("Giv tilladelse til telefonstatus (under «Tilladelser») for at kunne vælge et bestemt SIM-kort.", fontSize = 12.sp)
            }
            if (!editSim && server.simSub >= 0 && sims.none { it.subId == server.simSub }) {
                Text("Der sendes intet fra denne server, før du trykker «Ret» og vælger et SIM-kort, der sidder i telefonen.", fontSize = 13.sp, color = Red)
            }
            if (!confirmRemove) {
                TextButton(onClick = { confirmRemove = true }) { Text("Fjern server", color = Red) }
            } else {
                Text("Fjern «${server.label}»? Kvitteringer, der ikke er afsendt til den, går tabt.", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRemove) { Text("Ja, fjern") }
                    TextButton(onClick = { confirmRemove = false }) { Text("Nej") }
                }
            }
        }
    }
}

@Composable
private fun SimOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(10.dp))
        Text(label, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        if (granted) Text("✓ OK", color = Green, fontWeight = FontWeight.SemiBold) else OutlinedButton(onClick = onClick) { Text(action) }
    }
}
