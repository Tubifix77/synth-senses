package net.synthsenses

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.synthsenses.senses.Discovery
import net.synthsenses.senses.Endpoint
import net.synthsenses.senses.Live
import net.synthsenses.senses.Prefs
import net.synthsenses.senses.SenseService
import net.synthsenses.senses.requiredPermissions
import net.synthsenses.senses.willAskForLocation
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askPermissions()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    ControlPanel(
                        prefs = Prefs(this),
                        onStart = { SenseService.start(this) },
                        onStop = { SenseService.stop(this) },
                        onPermissions = { askPermissions() }
                    )
                }
            }
        }
    }

    private fun askPermissions() {
        val p = Prefs(this)
        // The decision lives in senses/Permissions.kt so it can be tested at
        // every API level this app supports. It was wrong below 31 and no test
        // could reach it here.
        permissionLauncher.launch(
            requiredPermissions(
                sdkInt = Build.VERSION.SDK_INT,
                ble = p.ble, wifi = p.wifi, cell = p.cell, location = p.location
            ).toTypedArray()
        )
    }
}

@Composable
private fun ControlPanel(
    prefs: Prefs,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPermissions: () -> Unit
) {
    val s by Live.status.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The phone's own /24. The receiver is almost always on it, so this is the
    // part of the address the phone can supply for itself — what it cannot
    // know is which machine on the subnet is running the receiver.
    val myIp = remember { Discovery.localIPv4(context) }
    val subnet = remember { Endpoint.subnetPrefix(myIp) }

    // On a first run the field used to be empty under a grey example from
    // someone else's subnet, which reads as a value that is already there.
    // Start it containing what is actually true instead.
    var endpoint by remember {
        mutableStateOf(prefs.endpoint.ifBlank { subnet?.let { "ws://$it" }.orEmpty() })
    }
    var finding by remember { mutableStateOf(false) }
    var findNote by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(prefs.token) }
    // Keyed on the revision so a `set` arriving over the link is reflected
    // here. It only changes for remote writes, so dragging a slider does not
    // re-initialise it under the finger.
    var interval by remember(s.settingsRevision) { mutableFloatStateOf(prefs.intervalMs / 1000f) }
    var heartbeat by remember(s.settingsRevision) { mutableFloatStateOf(prefs.heartbeatMs / 1000f) }
    var threshold by remember(s.settingsRevision) { mutableFloatStateOf(prefs.threshold) }
    var vision by remember(s.settingsRevision) { mutableStateOf(prefs.vision) }
    var ocr by remember(s.settingsRevision) { mutableStateOf(prefs.ocr) }
    var hearing by remember(s.settingsRevision) { mutableStateOf(prefs.hearing) }
    var speech by remember(s.settingsRevision) { mutableStateOf(prefs.speech) }
    var ble by remember(s.settingsRevision) { mutableStateOf(prefs.ble) }
    var wifi by remember(s.settingsRevision) { mutableStateOf(prefs.wifi) }
    var cell by remember(s.settingsRevision) { mutableStateOf(prefs.cell) }
    var location by remember(s.settingsRevision) { mutableStateOf(prefs.location) }
    var voice by remember(s.settingsRevision) { mutableStateOf(prefs.voice) }
    var lens by remember(s.settingsRevision) { mutableStateOf(prefs.lens) }

    // What Start would actually dial, or "" if the field is not an address
    // yet. Showing it is the point: the gap between what was typed and what
    // gets connected to should never be a surprise.
    val dialled = Endpoint.normalize(endpoint, subnet)

    /** Running, but nothing is getting out. OPEN and HTTP are the working states. */
    val linkTrouble = s.running && s.linkState != "OPEN" && s.linkState != "HTTP"

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Synth Senses", style = MaterialTheme.typography.headlineSmall)
        Text(
            "node ${prefs.deviceId} · schema 2",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (s.running) "SENSING" else "IDLE",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        s.linkState,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (linkTrouble) MaterialTheme.colorScheme.error
                        else Color.Unspecified
                    )
                }

                Text("salience ${(s.salience * 100).roundToInt()}% → ${s.lastTrigger}",
                    style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { s.salience.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "samples ${s.samples} · posted ${s.posted} · spooled ${s.spooled}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "habituation: ${s.knownTokens} tokens, ${s.quietTokens} gone quiet " +
                        "(mean ${(s.meanHabituation * 100).roundToInt()}%)",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "places known ${s.places}" +
                        (s.placeId?.let { " · here: ${s.placeName ?: it}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )
                // This line has always carried Link.lastDetail, and on the
                // first setup it said "no endpoint set" while nobody saw it.
                // It was the fifth grey line in a stack of grey statistics,
                // prefixed like one, and for the first few seconds it read
                // "—" because nothing published it until the first sample.
                // So: the service publishes it the moment it connects, and
                // when the link is carrying nothing it is coloured like the
                // problem it is.
                Text(
                    "uplink: ${s.lastUplink}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (linkTrouble) MaterialTheme.colorScheme.error else Color.Unspecified
                )

                if (s.running && hearing && !s.soundModelLoaded) {
                    Text(
                        "no yamnet.tflite in assets — loudness only",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (s.novel.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.novel.take(3).forEach {
                            AssistChip(onClick = {}, label = {
                                Text(it, style = MaterialTheme.typography.labelSmall)
                            })
                        }
                    }
                }

                if (s.lastNarration.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(s.lastNarration, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                // Snap the field to the address that is about to be dialled,
                // so what was typed and what was connected to are the same
                // string from here on.
                onClick = {
                    endpoint = dialled
                    prefs.endpoint = dialled
                    prefs.token = token
                    onStart()
                },
                // Pressing Start with nothing in the field was the original
                // bug: Link returns immediately with "no endpoint set" and the
                // panel just looks offline. There is nothing to start without
                // an address, so there is nothing to press.
                enabled = !s.running && dialled.isNotEmpty()
            ) { Text("Start") }
            Button(onClick = onStop, enabled = s.running) { Text("Stop") }
            Button(onClick = onPermissions) { Text("Perms") }
        }

        HorizontalDivider()

        Text("Link", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it; prefs.endpoint = it; findNote = "" },
            label = { Text("Endpoint") },
            // No grey example any more. An example in an empty field reads as
            // a value that is already there — which is how Start came to be
            // pressed with nothing in it — and this one was on a different
            // subnet from the phone's, so copying it by thumb was wrong twice.
            isError = dialled.isEmpty(),
            supportingText = {
                Text(
                    when {
                        findNote.isNotEmpty() -> findNote
                        dialled.isEmpty() && subnet != null && endpoint.trim().endsWith(".") ->
                            "add the last number of the machine running the receiver"
                        dialled.isEmpty() ->
                            "not an address yet — the receiver prints one when it starts"
                        dialled != endpoint.trim() -> "connects to $dialled"
                        else -> "ws:// for two-way, http:// for POST only"
                    }
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Discovery is a button rather than something that happens at
            // launch: a socket listening for broadcasts is a thing running,
            // and nothing here runs unless it was asked for.
            OutlinedButton(
                onClick = {
                    findNote = "listening for a receiver…"
                    finding = true
                    scope.launch {
                        val found = withContext(Dispatchers.IO) { Discovery.listen(context) }
                        finding = false
                        if (found != null) {
                            endpoint = found
                            prefs.endpoint = found
                            findNote = "found it"
                        } else {
                            // This used to read "is tools/receiver.py running on
                            // this network?", which sent people to debug the one
                            // machine that was fine. Found on the P20: it had just
                            // downloaded the APK over TCP from the very host it
                            // then claimed not to see. The beacon is a broadcast
                            // and broadcasts do not cross a router, so on a mesh
                            // behind a second router discovery can never work --
                            // and the field above is prefilled from *this* phone's
                            // subnet, which in that case is the wrong one.
                            findNote = when {
                                myIp == null ->
                                    "nothing answered, and this phone has no IPv4 " +
                                    "address — check it is on WiFi."
                                else ->
                                    "nothing answered. This phone is $myIp, and the " +
                                    "beacon is a broadcast, which cannot cross " +
                                    "between networks. If the receiver is on another " +
                                    "one, the address above is the wrong guess — type " +
                                    "the one the receiver prints when it starts."
                            }
                        }
                    }
                },
                enabled = !finding && !s.running
            ) { Text(if (finding) "Finding…" else "Find receiver") }

            Text(
                "the receiver announces itself on UDP ${Endpoint.BEACON_PORT}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; prefs.token = it },
            label = { Text("Bearer token (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider()

        Text("Senses", style = MaterialTheme.typography.titleMedium)
        Toggle("Vision", vision) { vision = it; prefs.vision = it }
        if (vision) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("back", "front").forEach { o ->
                    FilterChip(
                        selected = lens == o,
                        onClick = { lens = o; prefs.lens = o },
                        label = { Text(o) }
                    )
                }
            }
            Toggle("Read text (OCR)", ocr) { ocr = it; prefs.ocr = it }
        }
        Toggle("Hearing", hearing) { hearing = it; prefs.hearing = it }
        Toggle("Transcribe speech", speech) { speech = it; prefs.speech = it }
        Toggle("Voice (TTS out)", voice) { voice = it; prefs.voice = it }

        Spacer(Modifier.height(4.dp))
        Text("Radio", style = MaterialTheme.typography.titleSmall)
        Toggle("BLE scan — room fingerprint", ble) { ble = it; prefs.ble = it }
        Toggle("WiFi scan — needs fine location", wifi) { wifi = it; prefs.wifi = it }
        Toggle("Cell info — needs fine location", cell) { cell = it; prefs.cell = it }
        Toggle("GPS (for sun times)", location) { location = it; prefs.location = it }
        if (willAskForLocation(Build.VERSION.SDK_INT, ble, wifi, cell, location)) {
            Text(
                if (ble && Build.VERSION.SDK_INT < 31 && !wifi && !cell && !location)
                    "On this Android version a BLE scan needs location permission, " +
                        "and without it the scan is empty and no place is ever learned. " +
                        "Grant location and switch location services on."
                else
                    "Grant location and switch location services on, or these stay empty.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        HorizontalDivider()

        Text("Rhythm", style = MaterialTheme.typography.titleMedium)

        Text("Sample every ${interval.roundToInt()} s")
        Slider(
            value = interval,
            onValueChange = { interval = it; prefs.intervalMs = (it * 1000).toLong() },
            valueRange = 2f..120f
        )

        Text("Heartbeat every ${heartbeat.roundToInt()} s")
        Slider(
            value = heartbeat,
            onValueChange = { heartbeat = it; prefs.heartbeatMs = (it * 1000).toLong() },
            valueRange = 15f..1800f
        )

        Text("Speak above ${(threshold * 100).roundToInt()}% salience")
        Slider(
            value = threshold,
            onValueChange = { threshold = it; prefs.threshold = it },
            valueRange = 0f..1f
        )
        Text(
            "Habituation handles repetition on its own, so this can sit fairly low. " +
                "0% posts every sample.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}
