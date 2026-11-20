package com.example.rakatcounter

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class PrayerType(val title: String, val totalRakats: Int) {
    FAJR("نماز صبح", 2), DHUHR("نماز ظهر", 4), ASR("نماز عصر", 4),
    MAGHRIB("نماز مغرب", 3), ISHA("نماز عشاء", 4), NIGHT_PRAYER("نماز شب", 11)
}

data class NightPrayerState(
    val prayerName: String,
    val rakatInPart: String,
    val sujoodInRakat: String,
    val totalRakatNumber: Int,
    val isPartEnd: Boolean,
    val isAllFinished: Boolean
)

private fun ordinalPersian(num: Int): String = when (num) {
    1 -> "اول"; 2 -> "دوم"; 3 -> "سوم"; 4 -> "چهارم"; else -> num.toString()
}

private fun resolveNightPrayerState(totalSujood: Int): NightPrayerState {
    val maxSujood = 22
    val clampedSujood = totalSujood.coerceIn(0, maxSujood)
    val isAllFinished = clampedSujood >= maxSujood
    val effectiveSujood = if (isAllFinished) maxSujood - 1 else clampedSujood
    val rakatIndexZeroBased = effectiveSujood / 2
    val sujoodIndexInRakat = effectiveSujood % 2
    val totalRakatNumber = rakatIndexZeroBased + 1
    val sujoodLabel = if (sujoodIndexInRakat == 0) "سجده اول" else "سجده دوم"

    return when {
        rakatIndexZeroBased < 8 -> {
            val partIndex = (rakatIndexZeroBased / 2) + 1
            val rakatInPartIndex = (rakatIndexZeroBased % 2) + 1
            NightPrayerState("نماز ${ordinalPersian(partIndex)}", "رکعت ${ordinalPersian(rakatInPartIndex)}",
                sujoodLabel, totalRakatNumber, rakatInPartIndex == 2 && sujoodIndexInRakat == 1, false)
        }
        rakatIndexZeroBased < 10 -> {
            val rakatInShafa = (rakatIndexZeroBased - 8) + 1
            NightPrayerState("نماز شفع", "رکعت ${ordinalPersian(rakatInShafa)}", sujoodLabel,
                totalRakatNumber, rakatInShafa == 2 && sujoodIndexInRakat == 1, false)
        }
        else -> NightPrayerState("نماز وتر", "رکعت واحد", sujoodLabel, totalRakatNumber,
            sujoodIndexInRakat == 1, isAllFinished)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = darkColorScheme(
                    primary = Color(0xFF2E7D32), secondary = Color(0xFF81C784),
                    background = Color(0xFF121212), surface = Color(0xFF1E1E1E)
                )) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        RakatCounterApp()
                    }
                }
            }
        }
    }
}

@Composable
fun RakatCounterApp() {
    val context = LocalContext.current
    var selectedPrayer by remember { mutableStateOf(PrayerType.NIGHT_PRAYER) }
    var sujoodCount by remember { mutableIntStateOf(0) }
    var isNear by remember { mutableStateOf(false) }
    var sensorAvailable by remember { mutableStateOf(true) }
    val isNightPrayer = selectedPrayer == PrayerType.NIGHT_PRAYER
    val maxSujood = selectedPrayer.totalRakats * 2
    val completedRakats = sujoodCount / 2
    val currentRakat = if (completedRakats < selectedPrayer.totalRakats) completedRakats + 1 else selectedPrayer.totalRakats
    val sujoodInCurrentRakat = sujoodCount % 2
    val isPrayerFinished = sujoodCount >= maxSujood
    val nightState = remember(sujoodCount) { resolveNightPrayerState(sujoodCount) }

    fun triggerVibration(isStrongAlert: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isStrongAlert) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 100, 180, 100, 180), -1))
            else vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(if (isStrongAlert) 350 else 120)
        }
    }

    DisposableEffect(selectedPrayer, isPrayerFinished) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proximitySensor == null) {
            sensorAvailable = false
            return@DisposableEffect onDispose {}
        }
        sensorAvailable = true
        var lastSujoodTimestamp = 0L
        val minDelayBetweenSujoods = 2500L
        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return
                val currentlyNear = event.values[0] < proximitySensor.maximumRange
                if (currentlyNear && !isNear && !isPrayerFinished) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastSujoodTimestamp > minDelayBetweenSujoods) {
                        sujoodCount++
                        lastSujoodTimestamp = currentTime
                        val strongAlert = if (isNightPrayer) resolveNightPrayerState(sujoodCount).isPartEnd else sujoodCount % 2 == 0
                        triggerVibration(strongAlert)
                    }
                }
                isNear = currentlyNear
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(sensorListener, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sensorManager.unregisterListener(sensorListener) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("رکعت‌شمار هوشمند نماز", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = Color.White, modifier = Modifier.padding(bottom = 12.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(PrayerType.entries) { prayer ->
                    val isSelected = prayer == selectedPrayer
                    FilterChip(selected = isSelected, onClick = { selectedPrayer = prayer; sujoodCount = 0 },
                        label = { Text(prayer.title.replace("نماز ", ""), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White))
                }
            }
        }
        if (!sensorAvailable) {
            Text("خطا: سنسور مجاورت در این گوشی یافت نشد!", color = Color.Red, fontWeight = FontWeight.Bold)
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(285.dp).clip(CircleShape)
                .background(when { isPrayerFinished -> Color(0xFF1B5E20); isNear -> Color(0xFF2E7D32).copy(alpha = 0.45f); else -> MaterialTheme.colorScheme.surface })
                .padding(16.dp)) {
                if (isPrayerFinished) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("تقبل الله", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA5D6A7))
                        Spacer(Modifier.height(8.dp))
                        Text("${selectedPrayer.title} (${selectedPrayer.totalRakats} رکعت) تمام شد", fontSize = 15.sp,
                            color = Color.White, textAlign = TextAlign.Center)
                    }
                } else if (isNightPrayer) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(nightState.prayerName, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFA5D6A7), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text("${nightState.rakatInPart} • ${nightState.sujoodInRakat}", fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text("رکعت ${nightState.totalRakatNumber} از ۱۱", fontSize = 13.sp, color = Color.LightGray)
                        if (nightState.isPartEnd) {
                            Spacer(Modifier.height(8.dp))
                            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF4CAF50).copy(alpha = 0.25f)) {
                                Text("پایان این بخش (سلام و شروع بخش بعد)", fontSize = 12.sp, color = Color(0xFFC8E6C9),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("رکعت", fontSize = 20.sp, color = Color.LightGray)
                        Text("$currentRakat از ${selectedPrayer.totalRakats}", fontSize = 48.sp,
                            fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SujoodIndicator("سجده ۱", sujoodInCurrentRakat >= 1)
                            SujoodIndicator("سجده ۲", false)
                        }
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(if (isNightPrayer) "ترتیب خودکار: نماز اول تا چهارم (هر کدام ۲ رکعت) ← نماز شفع (۲ رکعت) ← نماز وتر (۱ رکعت)"
                else "گوشی را کنار مُهر قرار دهید تا هنگام سجده، صورت یا چانه بالای سنسور قرار گیرد.",
                fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { if (!isPrayerFinished) sujoodCount++ }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)) { Text("ثبت دستی سجده", fontSize = 15.sp) }
                Button(onClick = { sujoodCount = 0 }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))) {
                    Text("شروع مجدد", color = Color.White, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
fun SujoodIndicator(label: String, isDone: Boolean) {
    Surface(shape = RoundedCornerShape(8.dp), color = if (isDone) Color(0xFF4CAF50) else Color(0xFF333333)) {
        Text(if (isDone) "$label ✓" else label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
