
package com.example.rakatcounter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class PrayerType(val title: String, val totalRakats: Int) {
    FAJR("نماز صبح", 2),
    DHUHR("نماز ظهر", 4),
    ASR("نماز عصر", 4),
    MAGHRIB("نماز مغرب", 3),
    ISHA("نماز عشاء", 4),
    NIGHT_PRAYER("نماز شب", 11)
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
    1 -> "اول"
    2 -> "دوم"
    3 -> "سوم"
    4 -> "چهارم"
    else -> num.toString()
}

private fun resolveNightPrayerState(totalSujood: Int): NightPrayerState {
    val maxSujood = 22
    val count = totalSujood.coerceIn(0, maxSujood)
    val finished = count >= maxSujood
    val effective = if (finished) maxSujood - 1 else count

    val rakatIndex = effective / 2
    val sujoodIndex = effective % 2
    val totalRakat = rakatIndex + 1
    val sujoodLabel = if (sujoodIndex == 0) "سجده اول" else "سجده دوم"

    return when {
        rakatIndex < 8 -> {
            val part = (rakatIndex / 2) + 1
            val rakat = (rakatIndex % 2) + 1

            NightPrayerState(
                prayerName = "نماز ${ordinalPersian(part)}",
                rakatInPart = "رکعت ${ordinalPersian(rakat)}",
                sujoodInRakat = sujoodLabel,
                totalRakatNumber = totalRakat,
                isPartEnd = rakat == 2 && sujoodIndex == 1,
                isAllFinished = false
            )
        }

        rakatIndex < 10 -> {
            val rakat = (rakatIndex - 8) + 1

            NightPrayerState(
                prayerName = "نماز شفع",
                rakatInPart = "رکعت ${ordinalPersian(rakat)}",
                sujoodInRakat = sujoodLabel,
                totalRakatNumber = totalRakat,
                isPartEnd = rakat == 2 && sujoodIndex == 1,
                isAllFinished = false
            )
        }

        else -> NightPrayerState(
            prayerName = "نماز وتر",
            rakatInPart = "رکعت واحد",
            sujoodInRakat = sujoodLabel,
            totalRakatNumber = totalRakat,
            isPartEnd = sujoodIndex == 1,
            isAllFinished = finished
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl
            ) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = Color(0xFF2E7D32),
                        secondary = Color(0xFF81C784),
                        background = Color(0xFF121212),
                        surface = Color(0xFF1E1E1E)
                    )
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
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
    val lifecycleOwner = LocalLifecycleOwner.current

    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    val proximitySensor = remember {
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    var selectedPrayer by remember {
        mutableStateOf(PrayerType.NIGHT_PRAYER)
    }

    var sujoodCount by remember {
        mutableIntStateOf(0)
    }

    var sensorActive by remember {
        mutableStateOf(false)
    }

    var sensorDistance by remember {
        mutableFloatStateOf(Float.NaN)
    }

    var sensorEvents by remember {
        mutableIntStateOf(0)
    }

    var sensorStatus by remember {
        mutableStateOf("در انتظار فعال‌سازی")
    }

    var nearStatus by remember {
        mutableStateOf("نامشخص")
    }

    var lastCountSource by remember {
        mutableStateOf("هنوز ثبت نشده")
    }

    val isNightPrayer = selectedPrayer == PrayerType.NIGHT_PRAYER
    val maxSujood = selectedPrayer.totalRakats * 2
    val completedRakats = sujoodCount / 2

    val currentRakat = if (completedRakats < selectedPrayer.totalRakats) {
        completedRakats + 1
    } else {
        selectedPrayer.totalRakats
    }

    val sujoodInCurrentRakat = sujoodCount % 2
    val isPrayerFinished = sujoodCount >= maxSujood

    val nightState = remember(sujoodCount) {
        resolveNightPrayerState(sujoodCount)
    }

    fun triggerVibration(strong: Boolean) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(
                    Context.VIBRATOR_MANAGER_SERVICE
                ) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (strong) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0, 180, 100, 180, 100, 180),
                            -1
                        )
                    )
                } else {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(
                            100,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(if (strong) 350 else 120)
            }
        } catch (_: Exception) {
        }
    }

    /*
     * قفل مشترک برای جلوگیری از ثبت دوباره یک سجده
     * توسط سنسور و رویداد خاموش شدن صفحه
     */
    val countLock = remember { Any() }
    val lastCountTime = remember { AtomicLong(0L) }
    val nearAlreadyCounted = remember { AtomicBoolean(false) }

    val countSujood: (String) -> Unit = { source ->
        synchronized(countLock) {
            val now = System.currentTimeMillis()
            val previous = lastCountTime.get()

            if (!isPrayerFinished &&
                sujoodCount < maxSujood &&
                now - previous >= 2200L
            ) {
                sujoodCount = (sujoodCount + 1).coerceAtMost(maxSujood)
                lastCountTime.set(now)
                lastCountSource = source

                val strong = if (isNightPrayer) {
                    resolveNightPrayerState(sujoodCount).isPartEnd
                } else {
                    sujoodCount % 2 == 0
                }

                triggerVibration(strong)
            }
        }
    }

    DisposableEffect(
        lifecycleOwner,
        proximitySensor,
        selectedPrayer,
        isPrayerFinished
    ) {
        val sensor = proximitySensor

        if (sensor == null) {
            sensorStatus = "سنسور مجاورت پیدا نشد"
            sensorActive = false
            return@DisposableEffect onDispose {}
        }

        var lastNear: Boolean? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isEmpty()) return

                val value = event.values[0]
                sensorDistance = value
                sensorEvents++

                val isNear = value < sensor.maximumRange
                nearStatus = if (isNear) "NEAR - نزدیک" else "FAR - دور"

                if (lastNear == null) {
                    lastNear = isNear
                    return
                }

                val wasNear = lastNear!!
                lastNear = isNear

                if (!wasNear && isNear) {
                    if (nearAlreadyCounted.compareAndSet(false, true)) {
                        countSujood("سنسور NEAR")
                    }
                } else if (wasNear && !isNear) {
                    nearAlreadyCounted.set(false)
                }
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int
            ) {
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    /*
                     * مسیر پشتیبان:
                     * اگر سنسور هنوز NEAR را ثبت نکرده باشد،
                     * خاموش شدن صفحه یک بار شمارش را انجام می‌دهد.
                     */
                    if (nearAlreadyCounted.compareAndSet(false, true)) {
                        countSujood("خاموش شدن صفحه")
                    }
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                    receiver,
                    filter,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            sensorStatus = "خطا در ثبت گیرنده: ${e.message}"
        }

        val powerManager = context.getSystemService(
            Context.POWER_SERVICE
        ) as PowerManager

        val wakeLock = try {
            powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "RakatCounter:ProximitySujood"
            )
        } catch (_: Exception) {
            null
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    val registered = sensorManager.registerListener(
                        listener,
                        sensor,
                        SensorManager.SENSOR_DELAY_GAME
                    )

                    sensorActive = registered
                    sensorStatus = if (registered) {
                        "سنسور فعال است"
                    } else {
                        "ثبت سنسور ناموفق بود"
                    }

                    if (!isPrayerFinished &&
                        wakeLock != null &&
                        !wakeLock.isHeld
                    ) {
                        try {
                            wakeLock.acquire()
                        } catch (_: Exception) {
                        }
                    }
                }

                Lifecycle.Event.ON_PAUSE -> {
                    sensorManager.unregisterListener(listener)
                    sensorActive = false

                    if (wakeLock?.isHeld == true) {
                        try {
                            wakeLock.release()
                        } catch (_: Exception) {
                        }
                    }
                }

                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        if (
            lifecycleOwner.lifecycle.currentState.isAtLeast(
                Lifecycle.State.RESUMED
            )
        ) {
            val registered = sensorManager.registerListener(
                listener,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )

            sensorActive = registered
            sensorStatus = if (registered) {
                "سنسور فعال است"
            } else {
                "ثبت سنسور ناموفق بود"
            }

            if (!isPrayerFinished && wakeLock != null) {
                try {
                    if (!wakeLock.isHeld) wakeLock.acquire()
                } catch (_: Exception) {
                }
            }
        }

        onDispose {
            sensorManager.unregisterListener(listener)

            if (wakeLock?.isHeld == true) {
                try {
                    wakeLock.release()
                } catch (_: Exception) {
                }
            }

            lifecycleOwner.lifecycle.removeObserver(observer)

            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }

            sensorActive = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "رکعت‌شمار هوشمند نماز",
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            "با نزدیک شدن انگشت به سنسور، صفحه خاموش شده و سجده ثبت می‌شود.",
            fontSize = 13.sp,
            color = Color.LightGray,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            if (sensorActive) "سنسور: فعال" else "سنسور: غیرفعال",
            color = if (sensorActive) Color(0xFF81C784) else Color.Red,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(260.dp)
                .clip(CircleShape)
                .background(
                    if (isPrayerFinished) Color(0xFF1B5E20)
                    else MaterialTheme.colorScheme.surface
                )
                .padding(16.dp)
        ) {
            if (isPrayerFinished) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "تقبل الله",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFA5D6A7)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${selectedPrayer.title} تمام شد",
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (isNightPrayer) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        nightState.prayerName,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFA5D6A7)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "${nightState.rakatInPart} • ${nightState.sujoodInRakat}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "رکعت ${nightState.totalRakatNumber} از ۱۱",
                        fontSize = 14.sp,
                        color = Color.LightGray
                    )

                    if (nightState.isPartEnd) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "پایان این بخش (سلام)",
                            color = Color(0xFFC8E6C9),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("رکعت", fontSize = 20.sp, color = Color.LightGray)

                    Text(
                        "$currentRakat از ${selectedPrayer.totalRakats}",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SujoodIndicator(
                            "سجده ۱",
                            sujoodInCurrentRakat >= 1
                        )
                        SujoodIndicator("سجده ۲", false)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text("انتخاب نماز", color = Color.White, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(8.dp))

        PrayerType.entries.chunked(3).forEach { rowPrayers ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowPrayers.forEach { prayer ->
                    FilterChip(
                        selected = selectedPrayer == prayer,
                        onClick = {
                            selectedPrayer = prayer
                            sujoodCount = 0
                            lastCountSource = "هنوز ثبت نشده"
                            nearAlreadyCounted.set(false)
                        },
                        label = {
                            Text(
                                prayer.title.replace("نماز ", ""),
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                repeat(3 - rowPrayers.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    if (!isPrayerFinished) sujoodCount++
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("ثبت دستی")
            }

            OutlinedButton(
                onClick = {
                    if (sujoodCount > 0) sujoodCount--
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("برگشت")
            }

            Button(
                onClick = {
                    sujoodCount = 0
                    lastCountSource = "هنوز ثبت نشده"
                    nearAlreadyCounted.set(false)
                    lastCountTime.set(0L)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFC62828)
                )
            ) {
                Text("شروع مجدد")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF252525)
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "اطلاعات سنسور",
                    color = Color(0xFF81C784),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                DebugRow("نام سنسور", proximitySensor?.name ?: "-")
                DebugRow("سازنده", proximitySensor?.vendor ?: "-")
                DebugRow(
                    "Distance",
                    if (sensorDistance.isNaN()) "-" else sensorDistance.toString()
                )
                DebugRow(
                    "Maximum Range",
                    proximitySensor?.maximumRange?.toString() ?: "-"
                )
                DebugRow(
                    "Resolution",
                    proximitySensor?.resolution?.toString() ?: "-"
                )
                DebugRow("وضعیت", nearStatus)
                DebugRow("تعداد رویدادها", sensorEvents.toString())
                DebugRow("وضعیت ثبت", sensorStatus)
                DebugRow("آخرین مسیر شمارش", lastCountSource)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun DebugRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.LightGray, fontSize = 13.sp)
        Text(
            value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SujoodIndicator(label: String, isDone: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isDone) Color(0xFF4CAF50) else Color(0xFF333333)
    ) {
        Text(
            text = if (isDone) "$label ✓" else label,
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = 6.dp
            ),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
