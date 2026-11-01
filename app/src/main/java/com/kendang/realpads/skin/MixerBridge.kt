package com.kendang.realpads.skin

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kendang.realpads.AudioEngine
import com.kendang.realpads.GlobalEqEngine
import com.kendang.realpads.GlobalEqService
import com.kendang.realpads.MicStateBus
import com.kendang.realpads.MusicMeter
import com.kendang.realpads.ReverbSettings
import com.kendang.realpads.SettingsBus
import com.kendang.realpads.SettingsStore
import com.kendang.realpads.eqKnobToDb
import com.kendang.realpads.pushAllSettingsToEngine
import com.kendang.realpads.startGlobalEq
import com.kendang.realpads.stopGlobalEq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Jembatan skin -> mixer. Pola SAMA dengan KaraokeControls & panel floating: ubah ReverbSettings, kirim ke engine native,
 * simpan (SettingsStore), umumkan (SettingsBus / MicStateBus) supaya UI klasik & floating ikut sinkron.
 * Penerima bus hanya menyalin nilai ke [SkinUiState] (tidak menyimpan ulang -> tidak ada loop).
 */
class MixerBridge(private val context: Context, val ui: SkinUiState) : SkinActions {
    fun context(): Context = context

    var hasMicPermission: () -> Boolean = { false }
    var requestMicPermission: () -> Unit = {}
    var onFloating: () -> Unit = {}
    var onTheme: () -> Unit = {}
    var onSkinPick: () -> Unit = {}

    private var cur: ReverbSettings = SettingsStore.load(context)
    private var micRunning: Boolean = AudioEngine.nativeIsRunning()

    private class V(val get: (ReverbSettings) -> Float, val set: (ReverbSettings, Float) -> ReverbSettings)

    private val values: Map<String, V> = mapOf(
        "mic.volume" to V({ it.micVol }, { s, v -> s.copy(micVol = v) }),
        "mic.gate" to V({ it.gate }, { s, v -> s.copy(gate = v) }),
        "reverb.wet" to V({ it.wet }, { s, v -> s.copy(wet = v) }),
        "reverb.dry" to V({ it.dry }, { s, v -> s.copy(dry = v) }),
        "reverb.width" to V({ it.width }, { s, v -> s.copy(width = v) }),
        "reverb.damp" to V({ it.damp }, { s, v -> s.copy(damp = v) }),
        "reverb.room" to V({ it.roomSize }, { s, v -> s.copy(roomSize = v) }),
        "reverb.cess" to V({ it.cess }, { s, v -> s.copy(cess = v) }),
        "echo.delay" to V({ it.echoDelay }, { s, v -> s.copy(echoDelay = v) }),
        "echo.feedback" to V({ it.echoFeedback }, { s, v -> s.copy(echoFeedback = v) }),
        "echo.mix" to V({ it.echoMix }, { s, v -> s.copy(echoMix = v) }),
        "eqmic.low" to V({ it.eqLow }, { s, v -> s.copy(eqLow = v) }),
        "eqmic.mid" to V({ it.eqMid }, { s, v -> s.copy(eqMid = v) }),
        "eqmic.high" to V({ it.eqHigh }, { s, v -> s.copy(eqHigh = v) }),
        "eqaudio.gain" to V({ it.gEqGain }, { s, v -> s.copy(gEqGain = v) }),
        "eqaudio.sub" to V({ it.gEqSub }, { s, v -> s.copy(gEqSub = v) }),
        "eqaudio.low" to V({ it.gEqLow }, { s, v -> s.copy(gEqLow = v) }),
        "eqaudio.mid" to V({ it.gEqMid }, { s, v -> s.copy(gEqMid = v) }),
        "eqaudio.high" to V({ it.gEqHigh }, { s, v -> s.copy(gEqHigh = v) }),
    )

    init { syncFrom(cur) }   // nilai awal langsung tersedia (tanpa kedip 0 sebelum SkinBinding berjalan)

    private fun toggleValue(function: String, s: ReverbSettings): Boolean = when (function) {
        "mic.toggle" -> micRunning
        "reverb.toggle" -> s.reverbEnabled
        "echo.toggle" -> s.echoEnabled
        "eqmic.toggle" -> s.eqEnabled
        "eqaudio.toggle" -> s.gEqEnabled
        else -> false
    }

    // ---- aplikasi -> skin ----

    fun syncFrom(s: ReverbSettings) {
        cur = s
        for ((fn, v) in values) {
            val nv = v.get(s)
            if (abs((ui.values[fn] ?: -1f) - nv) > 1e-4f) ui.values[fn] = nv
        }
        for (fn in FunctionRegistry.toggleFunctions) ui.toggles[fn] = toggleValue(fn, s)
    }

    fun syncAll() {
        micRunning = AudioEngine.nativeIsRunning()
        syncFrom(SettingsStore.load(context))
    }

    fun setMicRunning(running: Boolean) {
        micRunning = running
        syncFrom(cur)
    }

    // ---- skin -> aplikasi ----

    override fun setValue(function: String, value: Float) {
        val entry = values[function] ?: return
        val v = value.coerceIn(0f, 1f)
        val s = entry.set(cur, v)
        cur = s
        ui.values[function] = v
        pushValue(function, s)
        persist(s)
    }

    private fun pushValue(function: String, s: ReverbSettings) {
        when {
            function == "mic.volume" -> AudioEngine.nativeSetMicGain(s.micVol * 2f)
            function == "mic.gate" -> AudioEngine.nativeSetGate(s.gate)
            function == "reverb.cess" -> AudioEngine.nativeSetCess(s.cess)
            function.startsWith("reverb.") -> AudioEngine.nativeSetReverbParams(s.wet, s.dry, s.width, s.damp, s.roomSize)
            function.startsWith("echo.") -> AudioEngine.nativeSetEchoParams(50f + s.echoDelay * 550f, s.echoFeedback * 0.75f, s.echoMix)
            function.startsWith("eqmic.") -> AudioEngine.nativeSetEQParams(eqKnobToDb(s.eqLow), eqKnobToDb(s.eqMid), eqKnobToDb(s.eqHigh))
            function.startsWith("eqaudio.") -> GlobalEqEngine.applySettings(s)
        }
    }

    /** Kembalikan kumpulan knob/fader ke nilai awal (tombol RESET di layar utama). */
    fun resetValues(functions: List<String>) {
        for (fn in functions) DEFAULT_VALUES[fn]?.let { setValue(fn, it) }
    }

    override fun setToggle(function: String, on: Boolean) {
        when (function) {
            "mic.toggle" -> toggleMic()
            "reverb.toggle" -> { cur = cur.copy(reverbEnabled = on); AudioEngine.nativeSetReverbEnabled(on); persist(cur) }
            "echo.toggle" -> { cur = cur.copy(echoEnabled = on); AudioEngine.nativeSetEchoEnabled(on); persist(cur) }
            "eqmic.toggle" -> { cur = cur.copy(eqEnabled = on); AudioEngine.nativeSetEQEnabled(on); persist(cur) }
            "eqaudio.toggle" -> {
                cur = cur.copy(gEqEnabled = on)
                persist(cur)   // simpan dulu: service membaca status ON dari SettingsStore
                if (on) startGlobalEq(context) else stopGlobalEq(context)
            }
        }
        syncFrom(cur)
    }

    override fun press(function: String) {
        when (function) {
            "floating.toggle" -> onFloating()
            "theme.open" -> onTheme()
            "skin.pick" -> onSkinPick()
        }
    }

    /** Sama dengan toggleEngine() di KaraokeControls. Status saklar dikoreksi dari kenyataan engine. */
    private fun toggleMic() {
        if (!hasMicPermission()) {
            requestMicPermission()
            return
        }
        if (micRunning) {
            AudioEngine.nativeStop()
            micRunning = false
            MicStateBus.publish(false)
        } else if (AudioEngine.nativeStart()) {
            pushAllSettingsToEngine(cur)
            micRunning = true
            MicStateBus.publish(true)
        }
    }

    private fun persist(s: ReverbSettings) {
        SettingsStore.save(context, s)
        SettingsBus.publish(s)
    }
}

private val DEFAULT_VALUES: Map<String, Float> = mapOf(
    "mic.volume" to 0.5f, "mic.gate" to 0.5f,
    "reverb.wet" to 0.35f, "reverb.dry" to 0.85f, "reverb.width" to 1.0f,
    "reverb.damp" to 0.3f, "reverb.room" to 0.6f, "reverb.cess" to 0.6f,
    "echo.delay" to 0.45f, "echo.feedback" to 0.45f, "echo.mix" to 0.3f,
    "eqmic.low" to 0.5f, "eqmic.mid" to 0.5f, "eqmic.high" to 0.5f,
    "eqaudio.gain" to 0.5f, "eqaudio.sub" to 0.5f, "eqaudio.low" to 0.5f, "eqaudio.mid" to 0.5f, "eqaudio.high" to 0.5f,
)

private fun levelFromAmp(amp: Float): Float =
    ((20f * log10(amp + 1e-6f) + 60f) / 60f).coerceIn(0f, 1f)

/**
 * Pasang jembatan ke siklus hidup layar: sinkron awal, dengar SettingsBus/MicStateBus (UI klasik & floating),
 * dan polling meter ~30 fps hanya saat layar RESUMED. Hasil meter masuk ke ui.meters["meter.mic" / "meter.output"].
 */
@Composable
fun SkinBinding(bridge: MixerBridge, hasMicPermission: Boolean) {
    val context = bridge.context()
    val lifecycleOwner = LocalLifecycleOwner.current
    var active by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner, bridge) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { active = true; bridge.syncAll() }
            else if (event == Lifecycle.Event.ON_PAUSE) active = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(bridge) {
        bridge.syncAll()
        // EQ Audio tersimpan ON tapi proses sempat mati -> nyalakan lagi (sama dengan KaraokeControls).
        if (SettingsStore.load(context).gEqEnabled && !GlobalEqService.running.value) startGlobalEq(context)
    }
    LaunchedEffect(bridge) { SettingsBus.updates.collect { bridge.syncFrom(it) } }
    LaunchedEffect(bridge) { MicStateBus.updates.collect { bridge.setMicRunning(it) } }

    LaunchedEffect(bridge, active, hasMicPermission) {
        val meters = bridge.ui.meters
        if (!active) {
            meters["meter.mic"] = 0f
            meters["meter.output"] = 0f
            return@LaunchedEffect
        }
        if (hasMicPermission) MusicMeter.acquire()
        try {
            while (true) {
                delay(33)
                val (micRms, outRms) = withContext(Dispatchers.Default) {
                    val running = AudioEngine.nativeIsRunning()
                    val mic = if (running) sqrt(AudioEngine.nativeGetMicMeanSquare()) else 0f
                    val engineOut = if (running) sqrt(AudioEngine.nativeGetOutputMeanSquare()) else 0f
                    // output = campuran akhir (musik + mic) bila Visualizer tersedia; kalau tidak, keluaran engine.
                    val mix = if (hasMicPermission) MusicMeter.readMixRms() else -1f
                    mic to (if (mix >= 0f) mix else engineOut)
                }
                val nowMic = levelFromAmp(micRms)
                val nowOut = levelFromAmp(outRms)
                val oldMic = meters["meter.mic"] ?: 0f
                val oldOut = meters["meter.output"] ?: 0f
                // naik langsung, turun cepat -> lampu "mukul" mengikuti suara
                meters["meter.mic"] = if (nowMic > oldMic) nowMic else max(nowMic, oldMic - 0.09f)
                meters["meter.output"] = if (nowOut > oldOut) nowOut else max(nowOut, oldOut - 0.09f)
            }
        } finally {
            if (hasMicPermission) MusicMeter.release()
        }
    }
}
