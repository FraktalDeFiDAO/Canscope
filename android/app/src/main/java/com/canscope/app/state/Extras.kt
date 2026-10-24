package com.canscope.app.state

/** Trip-computer accumulator — Torque-style trip stats from the speed/fuel PIDs. */
data class TripStats(
    val distanceKm: Double = 0.0,
    val fuelUsedL: Double = 0.0,
    val instantL100: Double = 0.0,
    val avgL100: Double = 0.0,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val movingSecs: Long = 0,
    val totalSecs: Long = 0,
) {
    val avgMpg: Double get() = if (avgL100 > 0.01) 235.214 / avgL100 else 0.0
    val instantMpg: Double get() = if (instantL100 > 0.01) 235.214 / instantL100 else 0.0

    companion object { val ZERO = TripStats() }
}

class TripRecorder {
    private var dist = 0.0
    private var fuel = 0.0
    private var moving = 0L
    private var total = 0L
    private var carryT = 0.0
    private var carryM = 0.0
    private var maxSpeed = 0.0
    private var instant = 0.0

    fun reset() {
        dist = 0.0; fuel = 0.0; moving = 0; total = 0
        carryT = 0.0; carryM = 0.0; maxSpeed = 0.0; instant = 0.0
    }

    /** @param dtSecs seconds since the previous sample. */
    fun sample(speedKmh: Double?, fuelRateLh: Double?, mafGs: Double?, dtSecs: Double): TripStats {
        val v = (speedKmh ?: 0.0).coerceAtLeast(0.0)
        dist += v / 3600.0 * dtSecs
        carryT += dtSecs
        if (v > 1.0) carryM += dtSecs
        if (carryT >= 1.0) { total += carryT.toLong(); carryT -= carryT.toLong() }
        if (carryM >= 1.0) { moving += carryM.toLong(); carryM -= carryM.toLong() }
        if (v > maxSpeed) maxSpeed = v

        // Fuel rate: prefer PID 0x5E; fall back to a MAF-based estimate
        // (stoichiometric 14.7:1, gasoline density 0.72 kg/L).
        val rate = when {
            fuelRateLh != null && fuelRateLh >= 0.0 -> fuelRateLh
            mafGs != null && mafGs > 0.0 -> mafGs * 3600.0 / (14.7 * 720.0)
            else -> 0.0
        }
        fuel += rate / 3600.0 * dtSecs
        instant = if (v > 3.0 && rate > 0.01) rate / v * 100.0 else 0.0

        val avgL100 = if (dist > 0.05) fuel / dist * 100.0 else 0.0
        val avgSpeed = if (moving > 0) dist / (moving / 3600.0) else 0.0
        return TripStats(
            distanceKm = dist, fuelUsedL = fuel, instantL100 = instant, avgL100 = avgL100,
            avgSpeedKmh = avgSpeed, maxSpeedKmh = maxSpeed, movingSecs = moving, totalSecs = total,
        )
    }
}

// ---------- performance timers (0–60, 1/4 mile, braking, …) ----------

enum class PerfPhase { IDLE, ARMED, RUNNING }

data class PerfTarget(
    val label: String,
    val fromKmh: Double,
    val toKmh: Double,
    /** If set, the run finishes after covering this distance instead of reaching toKmh. */
    val distanceM: Double? = null,
)

val PERF_TARGETS = listOf(
    PerfTarget("0–60 mph", 0.0, 96.56),
    PerfTarget("0–100 km/h", 0.0, 100.0),
    PerfTarget("0–200 km/h", 0.0, 200.0),
    PerfTarget("60–120 km/h", 60.0, 120.0),
    PerfTarget("⅛ mile", 0.0, Double.MAX_VALUE, 201.168),
    PerfTarget("¼ mile", 0.0, Double.MAX_VALUE, 402.336),
    PerfTarget("100–0 km/h", 100.0, 0.0),
)

data class PerfResult(val target: String, val seconds: Double, val ts: Long)

class PerfTimer {
    var phase = PerfPhase.IDLE
        private set
    private var target = PERF_TARGETS[0]
    private var startMs = 0L
    private var distAcc = 0.0
    private var exceededFrom = false

    val currentTarget get() = target

    fun arm(t: PerfTarget) {
        target = t
        phase = PerfPhase.ARMED
        distAcc = 0.0
        exceededFrom = false
    }

    fun cancel() { phase = PerfPhase.IDLE }

    /**
     * Feed one sample. Returns the final elapsed seconds when the run
     * completes on this sample, otherwise null.
     */
    fun sample(speedKmh: Double, distDeltaM: Double, nowMs: Long): Double? {
        when (phase) {
            PerfPhase.IDLE -> return null
            PerfPhase.ARMED -> {
                if (target.fromKmh <= 1.0) {
                    // standing start: launch on first movement
                    if (speedKmh > 1.0) { phase = PerfPhase.RUNNING; startMs = nowMs; distAcc = 0.0 }
                } else if (target.toKmh <= target.fromKmh) {
                    // braking test: arm once we exceed the entry speed, start when we drop to it
                    if (speedKmh >= target.fromKmh + 5) exceededFrom = true
                    if (exceededFrom && speedKmh <= target.fromKmh) { phase = PerfPhase.RUNNING; startMs = nowMs }
                } else {
                    // roll race: start when crossing fromKmh
                    if (speedKmh >= target.fromKmh) { phase = PerfPhase.RUNNING; startMs = nowMs; distAcc = 0.0 }
                }
                return null
            }
            PerfPhase.RUNNING -> {
                distAcc += distDeltaM
                val elapsed = (nowMs - startMs) / 1000.0
                if (elapsed > 120.0) { phase = PerfPhase.IDLE; return null } // safety abort
                val done = if (target.distanceM != null) {
                    distAcc >= target.distanceM
                } else if (target.toKmh <= target.fromKmh) {
                    speedKmh <= target.toKmh + 0.5
                } else {
                    speedKmh >= target.toKmh
                }
                if (done) { phase = PerfPhase.IDLE; return elapsed }
                return null
            }
        }
    }

    fun elapsed(nowMs: Long): Double =
        if (phase == PerfPhase.RUNNING) (nowMs - startMs) / 1000.0 else 0.0
}
