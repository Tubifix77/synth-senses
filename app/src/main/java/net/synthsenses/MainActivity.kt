package net.synthsenses

import android.Manifest
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.synthsenses.senses.Live
import net.synthsenses.senses.Prefs
import net.synthsenses.senses.SenseService
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
        val wanted = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31 && p.ble) wanted += Manifest.permission.BLUETOOTH_SCAN
        if (Build.VERSION.SDK_INT >= 29) wanted += Manifest.permission.ACTIVITY_RECOGNITION
        // WiFi scan results and cell info both require FINE location on Android 10+
        if (p.wifi || p.cell || p.location) {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
        }
        permissionLauncher.launch(wanted.toTypedArray())
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

    var endpoint by remember { mutableStateOf(prefs.endpoint) }
    var token by remember { mutableStateOf(prefs.token) }
    var interval by remember { mutableFloatStateOf(prefs.intervalMs / 1000f) }
    var heartbeat by remember { mutableFloatStateOf(prefs.heartbeatMs / 1000f) }
    var threshold by remember { mutableFloatStateOf(prefs.threshold) }
    var vision by remember { mutableStateOf(prefs.vision) }
    var ocr by remember { mutableStateOf(prefs.ocr) }
    var hearing by remember { mutableStateOf(prefs.hearing) }
    var speech by remember { mutableStateOf(prefs.speech) }
    var ble by remember { mutableStateOf(prefs.ble) }
    var wifi by remember { mutableStateOf(prefs.wifi) }
    var cell by remember { mutableStateOf(prefs.cell) }
    var location by remember { mutableStateOf(prefs.location) }
    var voice by remember { mutableStateOf(prefs.voice) }
    var lens by remember { mutableStateOf(prefs.lens) }

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
                    Text(s.linkState, style = MaterialTheme.typography.bodySmall)
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
                Text("uplink: ${s.lastUplink}", style = MaterialTheme.typography.bodySmall)

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
                onClick = { prefs.endpoint = endpoint; prefs.token = token; onStart() },
                enabled = !s.running
            ) { Text("Start") }
            Button(onClick = onStop, enabled = s.running) { Text("Stop") }
            Button(onClick = onPermissions) { Text("Perms") }
        }

        HorizontalDivider()

        Text("Link", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it; prefs.endpoint = it },
            label = { Text("Endpoint") },
            placeholder = { Text("ws://192.168.1.20:8077/link") },
            supportingText = { Text("ws:// for two-way, http:// for POST only") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
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
        if (wifi || cell || location) {
            Text(
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
