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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

private val GoldAccent = Color(0xFFD4AF37)
private val GoldSoft = Color(0xFFE6C86E)
private val PureBlack = Color(0xFF000000)
private val CardDark = Color(0xFF1A1A1A)

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
    val rakatNumberInPart: Int,
    val sujoodInRakat: String,
    val sujoodNumberInRakat: Int,
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
                rakatNumberInPart = rakat,
                sujoodInRakat = sujoodLabel,
                sujoodNumberInRakat = sujoodIndex + 1,
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
                rakatNumberInPart = rakat,
                sujoodInRakat = sujoodLabel,
                sujoodNumberInRakat = sujoodIndex + 1,
                totalRakatNumber = totalRakat,
                isPartEnd = rakat == 2 && sujoodIndex == 1,
                isAllFinished = false
            )
        }

        else -> NightPrayerState(
            prayerName = "نماز وتر",
            rakatInPart = "رکعت اول",
            rakatNumberInPart = 1,
            sujoodInRakat = sujoodLabel,
            sujoodNumberInRakat = sujoodIndex + 1,
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
                        primary = GoldAccent,
                        secondary = GoldSoft,
                        background = PureBlack,
                        surface = PureBlack
                    )
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = PureBlack
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
        mutableStateOf(PrayerType.DHUHR)
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

    val isPrayerFinished = sujoodCount >= maxSujood

    val currentSujoodStepInRakat = if (sujoodCount == 0) {
        0
    } else if (sujoodCount % 2 == 1) {
        1
    } else {
        2
    }

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
            .background(PureBlack)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // نوار بالای صفحه
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "→",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 10.dp)
                )
                Text(
                    text = "رکعت شمار",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = if (sensorActive) "سنسور فعال" else "سنسور غیرفعال",
                color = if (sensorActive) GoldSoft else Color(0xFFEF5350),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // قاب محراب طلایی وسط صفحه
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(430.dp)
        ) {
            MehrabGoldFrame(modifier = Modifier.fillMaxSize())

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 36.dp, vertical = 56.dp)
            ) {
                when {
                    isPrayerFinished -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "التماس دعا",
                                color = Color.White,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "${selectedPrayer.title} به پایان رسید",
                                color = GoldSoft,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    sujoodCount == 0 -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "الله اکبر",
                                color = Color.White,
                                fontSize = 40.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "${selectedPrayer.title} • ${selectedPrayer.totalRakats} رکعت",
                                color = GoldSoft,
                                fontSize = 15.sp
                            )
                        }
                    }

                    isNightPrayer -> {
                        // در تمام طول هر ۲ رکعت (و رکعت واحدی وتر)، نام بخش بالای عبارت «رکعت چندم» ثابت می‌ماند
                        val displayRakatLabel = if (sujoodCount % 2 == 0 && !nightState.isPartEnd) {
                            "رکعت ${ordinalPersian(nightState.rakatNumberInPart + 1)}"
                        } else {
                            nightState.rakatInPart
                        }
                        val upArrowCount = if (sujoodCount % 2 == 0 && !nightState.isPartEnd) {
                            nightState.rakatNumberInPart + 1
                        } else {
                            nightState.rakatNumberInPart
                        }
                        val downArrowCount = if (sujoodCount % 2 == 0 && !nightState.isPartEnd) {
                            1
                        } else {
                            nightState.sujoodNumberInRakat
                        }
                        val sujoodText = if (downArrowCount == 1) "سجده اول" else "سجده دوم"

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = nightState.prayerName,
                                color = GoldSoft,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )

                            Text(
                                text = displayRakatLabel,
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            ArrowRow(count = upArrowCount, pointingUp = true)

                            Spacer(modifier = Modifier.height(18.dp))

                            ArrowRow(count = downArrowCount, pointingUp = false)

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = sujoodText,
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "کل: رکعت ${nightState.totalRakatNumber} از ۱۱",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                    }

                    else -> {
                        val arrowRakat = if (sujoodCount % 2 == 0) {
                            (completedRakats + 1).coerceAtMost(selectedPrayer.totalRakats)
                        } else {
                            currentRakat
                        }
                        val arrowSujood = if (sujoodCount % 2 == 0) 1 else currentSujoodStepInRakat
                        val rakatText = "رکعت ${ordinalPersian(arrowRakat)}"
                        val sujoodText = if (arrowSujood == 1) "سجده اول" else "سجده دوم"

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = rakatText,
                                color = Color.White,
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            ArrowRow(count = arrowRakat, pointingUp = true)

                            Spacer(modifier = Modifier.height(20.dp))

                            ArrowRow(count = arrowSujood, pointingUp = false)

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = sujoodText,
                                color = Color.White,
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // نوار انتخاب نماز در پایین صفحه
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                PrayerType.FAJR,
                PrayerType.DHUHR,
                PrayerType.ASR,
                PrayerType.MAGHRIB,
                PrayerType.ISHA,
                PrayerType.NIGHT_PRAYER
            ).forEach { prayer ->
                val isSelected = selectedPrayer == prayer
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isSelected) GoldAccent.copy(alpha = 0.22f)
                            else Color.Transparent
                        )
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) GoldSoft else GoldAccent.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .clickable {
                            selectedPrayer = prayer
                            sujoodCount = 0
                            lastCountSource = "هنوز ثبت نشده"
                            nearAlreadyCounted.set(false)
                        }
                ) {
                    Text(
                        text = prayer.title.replace("نماز ", ""),
                        color = if (isSelected) GoldSoft else Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // نوار راهنمای پایین صفحه
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(CardDark)
                .padding(vertical = 8.dp, horizontal = 10.dp)
        ) {
            Text(
                text = "با نزدیک شدن به سنسور یا لمس ثبت دستی، رکعت و سجده ثبت می‌شود.",
                color = Color.LightGray,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // دکمه‌های کمکی (ثبت دستی / برگشت / شروع مجدد)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    if (!isPrayerFinished) {
                        sujoodCount = (sujoodCount + 1).coerceAtMost(maxSujood)
                        lastCountSource = "ثبت دستی"
                    }
                },
                border = BorderStroke(1.dp, GoldAccent),
                modifier = Modifier.weight(1f)
            ) {
                Text("ثبت دستی", color = Color.White, fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = {
                    if (sujoodCount > 0) {
                        sujoodCount--
                        nearAlreadyCounted.set(false)
                    }
                },
                border = BorderStroke(1.dp, GoldAccent.copy(alpha = 0.7f)),
                modifier = Modifier.weight(1f)
            ) {
                Text("برگشت", color = Color.White, fontSize = 13.sp)
            }

            Button(
                onClick = {
                    sujoodCount = 0
                    lastCountSource = "هنوز ثبت نشده"
                    nearAlreadyCounted.set(false)
                    lastCountTime.set(0L)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8E2424)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("شروع مجدد", color = Color.White, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // کارت اطلاعات سنسور
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = CardDark
            ),
            border = BorderStroke(1.dp, GoldAccent.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "اطلاعات سنسور",
                    color = GoldSoft,
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

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun MehrabGoldFrame(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = 3.dp.toPx()
        val archTopY = h * 0.04f
        val shoulderY = h * 0.24f
        val stepInX = w * 0.045f
        val leftX = w * 0.05f
        val rightX = w * 0.95f
        val centerX = w * 0.5f

        val path = Path().apply {
            moveTo(leftX + stepInX, h * 0.98f)
            lineTo(leftX + stepInX, shoulderY)
            lineTo(leftX, shoulderY)
            quadraticTo(
                leftX,
                archTopY + (shoulderY - archTopY) * 0.35f,
                centerX,
                archTopY
            )
            quadraticTo(
                rightX,
                archTopY + (shoulderY - archTopY) * 0.35f,
                rightX,
                shoulderY
            )
            lineTo(rightX - stepInX, shoulderY)
            lineTo(rightX - stepInX, h * 0.98f)
        }

        drawPath(
            path = path,
            color = GoldAccent,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun ArrowRow(count: Int, pointingUp: Boolean) {
    val safeCount = count.coerceIn(1, 4)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(safeCount) {
            SingleArrowIcon(pointingUp = pointingUp)
        }
    }
}

@Composable
private fun SingleArrowIcon(pointingUp: Boolean) {
    Canvas(modifier = Modifier.size(width = 24.dp, height = 44.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        if (pointingUp) {
            val headH = h * 0.34f
            val shaftTop = headH * 0.85f
            val shaftWidth = w * 0.28f

            drawRect(
                color = Color.White,
                topLeft = Offset(cx - shaftWidth / 2f, shaftTop),
                size = androidx.compose.ui.geometry.Size(shaftWidth, h - shaftTop)
            )
            val head = Path().apply {
                moveTo(cx, 0f)
                lineTo(w * 0.92f, headH)
                lineTo(w * 0.08f, headH)
                close()
            }
            drawPath(path = head, color = Color.White)
        } else {
            val headH = h * 0.34f
            val shaftBottom = h - headH * 0.85f
            val shaftWidth = w * 0.28f

            drawRect(
                color = Color.White,
                topLeft = Offset(cx - shaftWidth / 2f, 0f),
                size = androidx.compose.ui.geometry.Size(shaftWidth, shaftBottom)
            )
            val head = Path().apply {
                moveTo(cx, h)
                lineTo(w * 0.92f, h - headH)
                lineTo(w * 0.08f, h - headH)
                close()
            }
            drawPath(path = head, color = Color.White)
        }
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
