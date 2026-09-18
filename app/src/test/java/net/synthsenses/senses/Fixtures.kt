package net.synthsenses.senses

/**
 * A fully-populated percept, and the means to knock any part of it out.
 *
 * Every sense block is nullable on purpose — the project's first invariant is
 * that sensors degrade rather than fabricate — so tests need to build both the
 * complete article and the version where the hardware isn't there.
 */
internal object Fixtures {

    fun vision(
        lens: String = "back",
        brightness: Float = 0.4f,
        labels: List<Scored> = listOf(Scored("Room", 0.9f), Scored("Furniture", 0.7f)),
        objects: List<Seen> = listOf(Seen("Person", listOf(0.1f, 0.1f, 0.4f, 0.9f), 7)),
        text: String? = null
    ) = Vision(lens, brightness, labels, objects, text)

    fun hearing(
        levelDb: Float = -40f,
        peakDb: Float = -30f,
        events: List<Scored> = listOf(Scored("Speech", 0.8f)),
        transcript: String? = null
    ) = Hearing(levelDb, peakDb, events, transcript)

    fun radio(
        bleCount: Int = 5,
        bleNamed: List<Beacon> = listOf(Beacon("abc1234567", "Headphones", -55)),
        wifiConnected: String? = "home-wifi",
        placeId: String? = "p1",
        placeName: String? = "desk",
        placeIsNew: Boolean = false
    ) = Radio(
        bleCount = bleCount, bleNamed = bleNamed, bleStrongestRssi = -55,
        wifiCount = 9, wifiConnected = wifiConnected, wifiStrongestRssi = -48,
        cell = Cell("lte", "12345", -95), placeId = placeId, placeName = placeName,
        placeSimilarity = 0.92f, placeIsNew = placeIsNew
    )

    fun body(
        motion: String = "still",
        posture: String = "face_up",
        lux: Float? = 320f,
        magneticAnomaly: Float? = null,
        pressureDeltaPerMin: Float? = null,
        headingDeg: Int? = 90,
        stepsDelta: Long? = null
    ) = Body(
        motion = motion, posture = posture, accelRms = 0.01f, gyroRms = 0.01f,
        headingDeg = headingDeg, steps = 1234L, stepsDelta = stepsDelta, lux = lux,
        covered = false, magneticUt = 48f, magneticAnomaly = magneticAnomaly,
        pressureHpa = 1013f, pressureDeltaPerMin = pressureDeltaPerMin
    )

    fun self(
        thermal: String = "NONE",
        charging: Boolean = false,
        batteryPct: Float = 0.8f,
        net: String = "wifi",
        memLow: Boolean = false
    ) = SelfState(
        batteryPct = batteryPct, charging = charging, batteryTempC = 30f, currentMa = -250f,
        voltageV = 4.1f, thermal = thermal, thermalHeadroom = 0.3f, memFreePct = 0.5f,
        memLow = memLow, storageFreePct = 0.6f, screenOn = false, net = net, uptimeS = 1200
    )

    fun tempo(
        partOfDay: String = "midday",
        solarElevationDeg: Float? = 42f,
        isDaylight: Boolean? = true,
        minutesToSunset: Int? = 300
    ) = Tempo(
        localTime = "12:00", tzOffsetMin = 60, dayOfWeek = "Friday", partOfDay = partOfDay,
        solarElevationDeg = solarElevationDeg, isDaylight = isDaylight,
        minutesToSunset = minutesToSunset, minutesSinceSunrise = 240, dayLengthMin = 540
    )

    /** Everything present. */
    fun full(
        vision: Vision? = vision(),
        hearing: Hearing? = hearing(),
        radio: Radio? = radio(),
        body: Body = body(),
        self: SelfState = self(),
        tempo: Tempo = tempo(),
        gps: Place? = Place(55.68, 12.57, 12f),
        attention: Attention? = Attention(0.7f, listOf("v:Room"), 12, false, null),
        trigger: String = "salient"
    ) = Percept(
        deviceId = "testnode", seq = 42, tsMs = 1_600_000_000_000L, trigger = trigger,
        vision = vision, hearing = hearing, radio = radio, body = body, self = self,
        tempo = tempo, gps = gps, attention = attention
    )

    /** A phone with no camera, no mic, no radios and no location fix. */
    fun bare() = Percept(
        deviceId = "testnode", seq = 1, tsMs = 1_600_000_000_000L, trigger = "first",
        vision = null, hearing = null, radio = null,
        body = Body(
            motion = "still", posture = "unknown", accelRms = 0f, gyroRms = 0f,
            headingDeg = null, steps = null, stepsDelta = null, lux = null,
            covered = null, magneticUt = null, magneticAnomaly = null,
            pressureHpa = null, pressureDeltaPerMin = null
        ),
        self = SelfState(
            batteryPct = 0.5f, charging = false, batteryTempC = null, currentMa = null,
            voltageV = null, thermal = "UNKNOWN", thermalHeadroom = null, memFreePct = 0.4f,
            memLow = false, storageFreePct = null, screenOn = false, net = "none", uptimeS = 5
        ),
        tempo = Tempo(
            localTime = "03:00", tzOffsetMin = 0, dayOfWeek = "Monday",
            partOfDay = "deep night", solarElevationDeg = null, isDaylight = null,
            minutesToSunset = null, minutesSinceSunrise = null, dayLengthMin = null
        ),
        gps = null,
        attention = null
    )
}
