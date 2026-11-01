package com.kendang.realpads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val KNOB_H = 84

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

@Composable
private fun RowScope.K(label: String, v: Float, display: String, onChange: (Float) -> Unit) {
    KnobTile(label, v, display, 1f, Modifier.weight(1f).height(KNOB_H.dp), onChange = onChange)
}

@Composable
private fun SectionHeader(title: String, on: Boolean?, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = ThemeState.accentColor, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (on != null) {
            Text(
                if (on) "ON" else "OFF",
                color = if (on) IndicatorGreenText else TextDim,
                fontWeight = FontWeight.Bold, fontSize = 11.sp,
                modifier = Modifier
                    .background(if (on) IndicatorGreen else PanelColor, RoundedCornerShape(10.dp))
                    .clickable { onToggle() }
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Panel REVERB AUDIO: reverb + delay manual untuk suara app lain, dengan kirim (send) per band frekuensi.
 * Mesinnya SysReverbService (capture audio) + SysReverb.cpp. [onToggleRequest] dipanggil saat tombol NYALAKAN/MATIKAN
 * ditekan - yang menentukan minta izin rekam layar (nyala) atau menghentikan service (mati) adalah pemanggil.
 */
@Composable
fun SysReverbDialog(onDismiss: () -> Unit, onToggleRequest: () -> Unit) {
    val context = LocalContext.current
    val running by SysReverbService.running.collectAsState()
    val error by SysReverbService.error.collectAsState()
    var s by remember { mutableStateOf(SysReverbStore.load(context)) }
    var level by remember { mutableStateOf(0f) }

    // Simpan selalu; kirim ke native hanya saat mesin hidup (saat mati, nilai dikirim ulang waktu service mulai).
    fun update(f: (SysReverbSettings) -> SysReverbSettings) {
        val n = f(s)
        s = n
        SysReverbStore.save(context, n)
        if (SysReverbService.running.value) SysReverbEngine.push(n)
    }
    fun setBand(list: List<Float>, i: Int, v: Float): List<Float> = list.toMutableList().also { it[i] = v }

    LaunchedEffect(running) {
        while (running) {
            val amp = sqrt(SysReverbEngine.nativeGetMeanSquare())
            level = ((20f * log10(amp + 1e-6f) + 60f) / 60f).coerceIn(0f, 1f)
            delay(33)
        }
        level = 0f
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .background(CtrlPanelColor, RoundedCornerShape(10.dp))
                .padding(8.dp)
        ) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "REVERB AUDIO", color = ThemeState.accentColor, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onToggleRequest,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) IndicatorGreen else PanelColor,
                        contentColor = if (running) IndicatorGreenText else Color.White
                    ),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.height(36.dp)
                ) { Text(if (running) "AKTIF - MATIKAN" else "NYALAKAN", fontWeight = FontWeight.SemiBold, fontSize = AppTextSize, maxLines = 1) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = PanelColor, contentColor = Color.White),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.height(36.dp)
                ) { Text("TUTUP", fontWeight = FontWeight.SemiBold, fontSize = AppTextSize, maxLines = 1) }
            }
            Text(
                error
                    ?: if (running) "Menangkap audio app lain; yang diputar hanya efeknya, suara asli tetap dari app-nya."
                    else "Mati. Setelah NYALAKAN, izinkan di dialog sistem (rekam layar) - dipakai hanya untuk menangkap audio.",
                color = if (error != null) Color(0xFFFF8A80) else TextDim,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            )

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SectionHeader("REVERB", s.reverbOn) { update { it.copy(reverbOn = !it.reverbOn) } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    K("WET", s.wet, pct(s.wet)) { v -> update { it.copy(wet = v) } }
                    K("ROOM", s.room, pct(s.room)) { v -> update { it.copy(room = v) } }
                    K("DAMP", s.damp, pct(s.damp)) { v -> update { it.copy(damp = v) } }
                    K("WIDTH", s.width, pct(s.width)) { v -> update { it.copy(width = v) } }
                    K("CESS", s.cess, pct(s.cess)) { v -> update { it.copy(cess = v) } }
                    K("PRE-DLY", s.preDelay, "${(s.preDelay * 80).roundToInt()} ms") { v -> update { it.copy(preDelay = v) } }
                }

                SectionHeader("DELAY / ECHO", s.echoOn) { update { it.copy(echoOn = !it.echoOn) } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    K("DELAY", s.echoDelay, "${(50 + s.echoDelay * 550).roundToInt()} ms") { v -> update { it.copy(echoDelay = v) } }
                    K("FEEDBACK", s.echoFeedback, pct(s.echoFeedback)) { v -> update { it.copy(echoFeedback = v) } }
                    K("MIX", s.echoMix, pct(s.echoMix)) { v -> update { it.copy(echoMix = v) } }
                    K("OUTPUT", s.gain, "${(s.gain * 200).roundToInt()}%") { v -> update { it.copy(gain = v) } }
                    MeterTile("OUT", { level }, 1f, Modifier.weight(2f).height(KNOB_H.dp))
                }

                SectionHeader("KIRIM PER BAND  (SUB <90Hz | LOW 90-400 | MID 400-4k | HIGH >4k)", null) {}
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("KE REVERB", color = TextDim, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("SUB", "LOW", "MID", "HIGH").forEachIndexed { i, name ->
                                K(name, s.revSend[i], pct(s.revSend[i])) { v -> update { it.copy(revSend = setBand(it.revSend, i, v)) } }
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("KE DELAY", color = TextDim, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("SUB", "LOW", "MID", "HIGH").forEachIndexed { i, name ->
                                K(name, s.echoSend[i], pct(s.echoSend[i])) { v -> update { it.copy(echoSend = setBand(it.echoSend, i, v)) } }
                            }
                        }
                    }
                }
            }
        }
    }
}
