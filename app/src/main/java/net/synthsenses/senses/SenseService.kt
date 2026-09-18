package net.synthsenses.senses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.synthsenses.MainActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * The loop.
 *
 * Reads every enabled sense, assembles a percept, asks the habituation model
 * whether it's worth saying, and ships it. Between samples it waits on a command
 * channel rather than a plain delay, so anything the box sends interrupts the
 * rhythm immediately instead of at the next tick.
 *
 * Foreground service with camera + microphone types, because Android 14 won't
 * let a background process touch either. The persistent notification isn't
 * negotiable and that's correct: a thing that watches a room should be visibly
 * watching the room.
 */
class SenseService : LifecycleService() {

    companion object {
        private const val TAG = "SenseService"
        private const val CHANNEL = "sensing"
        private const val NOTIF_ID = 42

        const val ACTION_START = "net.synthsenses.START"
        const val ACTION_STOP = "net.synthsenses.STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(
                Intent(ctx, SenseService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SenseService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var spool: Spool
    private lateinit var link: Link
    private lateinit var body: BodySensor
    private lateinit var selfSensor: SelfSensor
    private lateinit var audio: AudioSensor
    private lateinit var speech: SpeechSensor
    private lateinit var habituation: Habituation
    private var radio: RadioSensor? = null
    private var vision: VisionSensor? = null
    private var voice: Voice? = null

    private val commands = Channel<Link.Command>(capacity = 32)

    private var loop: Job? = null
    private var seq = 0L

    /** One-shot overrides set by inbound commands, consumed by the next tick. */
    private var forceSample = false
    private var lensOverride: String? = null
    private var forceOcr = false
    private var forceTranscribe = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        spool = Spool(this)
        body = BodySensor(this)
        selfSensor = SelfSensor(this)
        audio = AudioSensor(this)
        speech = SpeechSensor(this)
        habituation = Habituation(this)
        link = Link(spool) { cmd ->
            // called off the loop's thread; just queue it
            commands.trySend(cmd)
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSensing()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground("waking up")
        startSensing()
        return START_STICKY
    }

    // ---- lifecycle ----

    private fun startSensing() {
        if (loop?.isActive == true) return

        body.start()
        if (prefs.vision) vision = VisionSensor(this, this)
        if (prefs.ble || prefs.wifi || prefs.cell) radio = RadioSensor(this)
        if (prefs.voice) voice = Voice(this)

        link.connect(prefs.endpoint, prefs.token.ifBlank { null })

        Live.update {
            it.copy(running = true, places = radio?.places?.count() ?: 0)
        }

        loop = lifecycleScope.launch {
            while (isActive) {
                val startedAt = System.currentTimeMillis()
                runCatching { tick() }.onFailure { Log.w(TAG, "tick failed", it) }

                val spent = System.currentTimeMillis() - startedAt
                val wait = (prefs.intervalMs - spent).coerceAtLeast(300L)

                // wait, but let a command cut the wait short
                val cmd = withTimeoutOrNull(wait) { commands.receive() }
                if (cmd != null) {
                    runCatching { handle(cmd) }.onFailure {
                        Log.w(TAG, "command ${cmd.name} failed", it)
                    }
                    // drain anything else queued behind it
                    while (true) {
                        val next = commands.tryReceive().getOrNull() ?: break
                        runCatching { handle(next) }.onFailure {
                            Log.w(TAG, "command ${next.name} failed", it)
                        }
                    }
                }
            }
        }
    }

    private fun stopSensing() {
        loop?.cancel()
        loop = null
        body.stop()
        vision?.close(); vision = null
        radio = null
        audio.close()
        voice?.close(); voice = null
        link.close()
        Live.update { Live.Status(running = false) }
    }

    override fun onDestroy() {
        stopSensing()
        super.onDestroy()
    }

    // ---- one sample ----

    private suspend fun tick() {
        val now = System.currentTimeMillis()

        val v = if (prefs.vision) {
            vision?.sample(lensOverride ?: prefs.lens, prefs.ocr || forceOcr)
        } else null
        lensOverride = null
        forceOcr = false

        var h = if (prefs.hearing) audio.sample() else null

        if (h != null && (prefs.speech || forceTranscribe)) {
            val voiceHeard = h.events.any {
                it.name in AudioSensor.SPEECHY && it.conf > 0.45f
            } || (!audio.modelAvailable && h.peakDb > -20f)

            if (forceTranscribe || (voiceHeard && speech.dueFor(now))) {
                val said = speech.transcribeOnce()
                if (!said.isNullOrBlank()) h = h.copy(transcript = said)
            }
        }
        forceTranscribe = false

        val r = radio?.sample(prefs.ble, prefs.wifi, prefs.cell)
        val b = body.sample()
        val s = selfSensor.sample()
        val gps = if (prefs.location) lastKnownPlace() else null
        val tempo = TempoSensor.sample(now, gps)

        val candidate = Percept(
            deviceId = prefs.deviceId,
            seq = seq,
            tsMs = now,
            trigger = "pending",
            vision = v,
            hearing = h,
            radio = r,
            body = b,
            self = s,
            tempo = tempo,
            gps = gps
        )

        val verdict = habituation.evaluate(candidate, prefs.threshold, prefs.heartbeatMs)
        val trigger = if (forceSample) "requested" else verdict.trigger
        forceSample = false

        val (known, quiet, meanH) = habituation.stats()
        Live.update {
            it.copy(
                samples = it.samples + 1,
                salience = verdict.attention.salience,
                lastTrigger = trigger ?: "quiet",
                lastNarration = candidate.narration,
                lastUplink = link.lastDetail,
                linkState = link.state.name,
                soundModelLoaded = audio.modelAvailable,
                knownTokens = known,
                quietTokens = quiet,
                meanHabituation = meanH,
                places = radio?.places?.count() ?: 0,
                placeId = r?.placeId,
                placeName = r?.placeName,
                spooled = spool.size(),
                novel = verdict.attention.novelTokens
            )
        }

        updateNotification(candidate.narration)
        if (trigger == null) return

        seq += 1
        val toSend = candidate.copy(
            trigger = trigger,
            seq = seq,
            attention = verdict.attention
        )
        val ok = link.send(toSend)

        Live.update {
            it.copy(
                posted = if (ok) it.posted + 1 else it.posted,
                spooled = spool.size(),
                lastUplink = link.lastDetail,
                linkState = link.state.name
            )
        }
    }

    // ---- commands from the box ----

    private fun handle(cmd: Link.Command) {
        Log.i(TAG, "command: ${cmd.name}")
        when (cmd.name) {

            Commands.SAMPLE -> forceSample = true

            Commands.LOOK -> {
                cmd.str("lens")?.takeIf { it == "front" || it == "back" }?.let {
                    lensOverride = it
                }
                if (cmd.bool("persist", false)) {
                    lensOverride?.let { prefs.lens = it }
                }
                forceSample = true
            }

            Commands.READ -> {
                forceOcr = true
                forceSample = true
            }

            Commands.LISTEN -> {
                forceTranscribe = true
                forceSample = true
            }

            Commands.SPEAK -> {
                val text = cmd.str("text")
                if (text.isNullOrBlank()) {
                    ack(cmd, false, "no text")
                } else {
                    if (voice == null) voice = Voice(this)
                    val spoke = voice?.speak(text) ?: false
                    ack(cmd, spoke, if (spoke) "spoke" else "tts unavailable")
                }
            }

            /** attend {"modality":"s","gain":2.0} — weight a whole token prefix. */
            Commands.ATTEND -> {
                val modality = cmd.str("modality")
                if (modality.isNullOrBlank()) {
                    habituation.clearGains()
                    ack(cmd, true, "gains cleared")
                } else {
                    habituation.setGain(modality, cmd.float("gain", 1f))
                    ack(cmd, true, "gain ${modality}=${cmd.float("gain", 1f)}")
                }
            }

            Commands.SET -> {
                cmd.args.keys().forEach { key ->
                    when (key) {
                        "interval_ms" -> prefs.intervalMs =
                            cmd.long("interval_ms", prefs.intervalMs).coerceIn(1_000, 600_000)

                        "heartbeat_ms" -> prefs.heartbeatMs =
                            cmd.long("heartbeat_ms", prefs.heartbeatMs).coerceIn(5_000, 3_600_000)

                        "threshold" -> prefs.threshold =
                            cmd.float("threshold", prefs.threshold).coerceIn(0f, 1f)

                        "vision" -> prefs.vision = cmd.bool("vision", prefs.vision)
                        "hearing" -> prefs.hearing = cmd.bool("hearing", prefs.hearing)
                        "speech" -> prefs.speech = cmd.bool("speech", prefs.speech)
                        "ocr" -> prefs.ocr = cmd.bool("ocr", prefs.ocr)
                        "ble" -> prefs.ble = cmd.bool("ble", prefs.ble)
                        "wifi" -> prefs.wifi = cmd.bool("wifi", prefs.wifi)
                        "cell" -> prefs.cell = cmd.bool("cell", prefs.cell)
                        "lens" -> cmd.str("lens")?.let { prefs.lens = it }
                    }
                }
                // senses may have been switched on or off underneath us
                if (prefs.vision && vision == null) vision = VisionSensor(this, this)
                if (!prefs.vision) { vision?.close(); vision = null }
                if ((prefs.ble || prefs.wifi || prefs.cell) && radio == null) {
                    radio = RadioSensor(this)
                }
                ack(cmd, true, "settings applied")
            }

            Commands.PLACES -> link.sendReply(
                "places",
                JSONObject().apply {
                    put("places", radio?.places?.listJson() ?: JSONArray())
                }
            )

            Commands.NAME_PLACE -> {
                val id = cmd.str("id")
                val name = cmd.str("name")
                val ok = if (id != null && name != null) {
                    radio?.places?.name(id, name) ?: false
                } else false
                ack(cmd, ok, if (ok) "named $id = $name" else "unknown place or missing name")
            }

            /** forget {"what":"habituation"|"places"|"all"} */
            Commands.FORGET -> {
                when (cmd.str("what", "habituation")) {
                    "places" -> radio?.places?.forget()
                    "all" -> {
                        habituation.forget(); radio?.places?.forget()
                    }
                    else -> habituation.forget()
                }
                ack(cmd, true, "forgotten")
            }

            Commands.STATUS -> {
                val (known, quiet, meanH) = habituation.stats()
                link.sendReply("status", JSONObject().apply {
                    put("device_id", prefs.deviceId)
                    put("schema", SCHEMA_VERSION)
                    put("seq", seq)
                    put("interval_ms", prefs.intervalMs)
                    put("heartbeat_ms", prefs.heartbeatMs)
                    put("threshold", prefs.threshold.toDouble())
                    put("senses", JSONObject().apply {
                        put("vision", prefs.vision)
                        put("lens", prefs.lens)
                        put("ocr", prefs.ocr)
                        put("hearing", prefs.hearing)
                        put("sound_model", audio.modelAvailable)
                        put("speech", prefs.speech)
                        put("ble", prefs.ble)
                        put("wifi", prefs.wifi)
                        put("cell", prefs.cell)
                        put("location", prefs.location)
                        put("voice", voice?.ready ?: false)
                    })
                    put("habituation", JSONObject().apply {
                        put("tokens", known)
                        put("quiet", quiet)
                        put("mean", meanH.toDouble())
                        put("gains", JSONObject(habituation.gainSnapshot().mapValues {
                            it.value.toDouble()
                        }))
                        put("most_habituated", JSONArray().apply {
                            habituation.mostHabituated().forEach { (tok, n) ->
                                put(JSONObject().apply { put("token", tok); put("exposures", n) })
                            }
                        })
                    })
                    put("places", radio?.places?.count() ?: 0)
                    put("spooled", spool.size())
                })
            }

            else -> ack(cmd, false, "unknown command")
        }
    }

    private fun ack(cmd: Link.Command, ok: Boolean, detail: String) {
        link.sendReply("ack", JSONObject().apply {
            put("cmd", cmd.name)
            put("ok", ok)
            put("detail", detail)
        })
    }

    private fun lastKnownPlace(): Place? = runCatching {
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        listOf(
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.GPS_PROVIDER
        ).asSequence()
            .mapNotNull { p ->
                @Suppress("MissingPermission")
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?.let { Place(it.latitude, it.longitude, it.accuracy) }
    }.getOrNull()

    // ---- notification ----

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Sensing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the phone is acting as a sensor"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SenseService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Sensing · ${link.state.name.lowercase()}")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    private fun goForeground(text: String) {
        var type = 0
        if (prefs.vision) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (prefs.hearing || prefs.speech) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (prefs.location) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (type == 0) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
        } else {
            startForeground(NOTIF_ID, buildNotification(text))
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
