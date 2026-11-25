package com.example.rakatcounter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val GoldAccent = Color(0xFFD4AF37)
private val GoldSoft = Color(0xFFE6C86E)
private val PureBlack = Color(0xFF000000)
private val CardDark = Color(0xFF1A1A1A)

private const val PREFS_NAME = "rakat_counter_prefs"
private const val KEY_NO_TASHAHHUD = "no_tashahhud_seconds"
private const val KEY_WITH_TASHAHHUD = "with_tashahhud_seconds"
private const val KEY_CUSTOM_PRAYERS = "custom_prayer_count"
private const val KEY_CUSTOM_RAKATS = "custom_rakats_per_prayer"

enum class PrayerType(val title: String, val totalRakats: Int) {
    FAJR("نماز صبح", 2),
    DHUHR("نماز ظهر", 4),
    ASR("نماز عصر", 4),
    MAGHRIB("نماز مغرب", 3),
    ISHA("نماز عشاء", 4),
    NIGHT_PRAYER("نماز شب", 11),
    CUSTOM("نماز دلخواه", 12)
}

private enum class DisplayMode {
    ALLAH_AKBAR,
    RAKAT_ONLY,
    SUJOOD_FIRST,
    SUJOOD_SECOND,
    FINISHED
}

private data class MultiPartSection(
    val partIndex: Int,
    val title: String,
    val rakatCount: Int,
    val startRakatGlobal: Int
)

private val NightParts = listOf(
    MultiPartSection(0, "نماز اول", 2, 1),
    MultiPartSection(1, "نماز دوم", 2, 3),
    MultiPartSection(2, "نماز سوم", 2, 5),
    MultiPartSection(3, "نماز چهارم", 2, 7),
    MultiPartSection(4, "نماز شفع", 2, 9),
    MultiPartSection(5, "نماز وتر", 1, 11)
)

private fun buildCustomParts(prayerCount: Int, rakatsPerPrayer: Int): List<MultiPartSection> {
    val safePrayers = prayerCount.coerceIn(1, 30)
    val safeRakats = rakatsPerPrayer.coerceIn(1, 8)
    val list = mutableListOf<MultiPartSection>()
    var currentStart = 1
    for (i in 0 until safePrayers) {
        list.add(
            MultiPartSection(
                partIndex = i,
                title = "نماز ${ordinalPersian(i + 1)}",
                rakatCount = safeRakats,
                startRakatGlobal = currentStart
            )
        )
        currentStart += safeRakats
    }
    return list
}

private data class PrayerDisplayState(
    val mode: DisplayMode,
    val rakatInSection: Int,
    val totalRakatGlobal: Int,
    val totalRakatsOverall: Int,
    val sectionTitle: String?,
    val waitAfterSecondSujoodSeconds: Int?,
    val isLastSujoodOfPrayer: Boolean
)

private fun ordinalPersian(num: Int): String = when (num) {
    1 -> "اول"
    2 -> "دوم"
    3 -> "سوم"
    4 -> "چهارم"
    5 -> "پنجم"
    6 -> "ششم"
    7 -> "هفتم"
    8 -> "هشتم"
    9 -> "نهم"
    10 -> "دهم"
    else -> num.toString()
}

private fun hasTashahhudOrSalam(prayer: PrayerType, rakatInSection: Int, rakatsInPart: Int): Boolean {
    return when (prayer) {
        PrayerType.FAJR -> rakatInSection == 2
        PrayerType.DHUHR, PrayerType.ASR, PrayerType.ISHA -> rakatInSection == 2 || rakatInSection == 4
        PrayerType.MAGHRIB -> rakatInSection == 2 || rakatInSection == 3
        PrayerType.NIGHT_PRAYER, PrayerType.CUSTOM -> rakatInSection == rakatsInPart
    }
}

private fun resolvePrayerDisplayState(
    prayer: PrayerType,
    sujoodCount: Int,
    autoAdvancedRakat: Int,
    noTashahhudSec: Int,
    withTashahhudSec: Int,
    customParts: List<MultiPartSection>,
    prayerFinishedFlag: Boolean
): PrayerDisplayState {
    val totalRakatsOverall = when (prayer) {
        PrayerType.CUSTOM -> customParts.sumOf { it.rakatCount }.coerceAtLeast(1)
        else -> prayer.totalRakats
    }
    val maxSujood = totalRakatsOverall * 2

    if (prayerFinishedFlag || sujoodCount >= maxSujood) {
        return PrayerDisplayState(
            mode = DisplayMode.FINISHED,
            rakatInSection = totalRakatsOverall,
            totalRakatGlobal = totalRakatsOverall,
            totalRakatsOverall = totalRakatsOverall,
            sectionTitle = prayer.title,
            waitAfterSecondSujoodSeconds = null,
            isLastSujoodOfPrayer = false
        )
    }

    if (sujoodCount == 0 && autoAdvancedRakat <= 1) {
        return PrayerDisplayState(
            mode = DisplayMode.ALLAH_AKBAR,
            rakatInSection = 1,
            totalRakatGlobal = 1,
            totalRakatsOverall = totalRakatsOverall,
            sectionTitle = prayer.title,
            waitAfterSecondSujoodSeconds = null,
            isLastSujoodOfPrayer = false
        )
    }

    val lastSujoodRakat = ((sujoodCount - 1).coerceAtLeast(0) / 2) + 1
    val isSecondSujood = sujoodCount > 0 && sujoodCount % 2 == 0

    // نمایش خودکار رکعت بعدی بدون فلش سجده پس از اتمام تایمر
    if (autoAdvancedRakat > lastSujoodRakat && sujoodCount % 2 == 0) {
        val targetRakat = autoAdvancedRakat.coerceAtMost(totalRakatsOverall)
        if (prayer == PrayerType.NIGHT_PRAYER || prayer == PrayerType.CUSTOM) {
            val sections = if (prayer == PrayerType.NIGHT_PRAYER) NightParts else customParts
            val part = sections.firstOrNull {
                targetRakat >= it.startRakatGlobal &&
                    targetRakat < it.startRakatGlobal + it.rakatCount
            } ?: sections.last()
            val rakatInPart = targetRakat - part.startRakatGlobal + 1
            return PrayerDisplayState(
                mode = DisplayMode.RAKAT_ONLY,
                rakatInSection = rakatInPart,
                totalRakatGlobal = targetRakat,
                totalRakatsOverall = totalRakatsOverall,
                sectionTitle = part.title,
                waitAfterSecondSujoodSeconds = null,
                isLastSujoodOfPrayer = false
            )
        } else {
            return PrayerDisplayState(
                mode = DisplayMode.RAKAT_ONLY,
                rakatInSection = targetRakat,
                totalRakatGlobal = targetRakat,
                totalRakatsOverall = totalRakatsOverall,
                sectionTitle = prayer.title,
                waitAfterSecondSujoodSeconds = null,
                isLastSujoodOfPrayer = false
            )
        }
    }

    val activeRakat = lastSujoodRakat.coerceAtMost(totalRakatsOverall)
    val mode = if (isSecondSujood) DisplayMode.SUJOOD_SECOND else DisplayMode.SUJOOD_FIRST
    val isLastSujood = isSecondSujood && sujoodCount == maxSujood

    if (prayer == PrayerType.NIGHT_PRAYER || prayer == PrayerType.CUSTOM) {
        val sections = if (prayer == PrayerType.NIGHT_PRAYER) NightParts else customParts
        val part = sections.firstOrNull {
            activeRakat >= it.startRakatGlobal &&
                activeRakat < it.startRakatGlobal + it.rakatCount
        } ?: sections.last()
        val rakatInPart = activeRakat - part.startRakatGlobal + 1

        val waitSec = if (isSecondSujood) {
            if (isLastSujood || hasTashahhudOrSalam(prayer, rakatInPart, part.rakatCount)) {
                withTashahhudSec
            } else {
                noTashahhudSec
            }
        } else {
            null
        }

        return PrayerDisplayState(
            mode = mode,
            rakatInSection = rakatInPart,
            totalRakatGlobal = activeRakat,
            totalRakatsOverall = totalRakatsOverall,
            sectionTitle = part.title,
            waitAfterSecondSujoodSeconds = waitSec,
            isLastSujoodOfPrayer = isLastSujood
        )
    } else {
        val waitSec = if (isSecondSujood) {
            if (isLastSujood || hasTashahhudOrSalam(prayer, activeRakat, totalRakatsOverall)) {
                withTashahhudSec
            } else {
                noTashahhudSec
            }
        } else {
            null
        }

        return PrayerDisplayState(
            mode = mode,
            rakatInSection = activeRakat,
            totalRakatGlobal = activeRakat,
            totalRakatsOverall = totalRakatsOverall,
            sectionTitle = prayer.title,
            waitAfterSecondSujoodSeconds = waitSec,
            isLastSujoodOfPrayer = isLastSujood
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
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    val proximitySensor = remember {
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    // صدای تیک کیبورد
    val toneGenerator = remember {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        } catch (_: Exception) {
            null
        }
    }

    DisposableEffect(toneGenerator) {
        onDispose {
            try {
                toneGenerator?.release()
            } catch (_: Exception) {
            }
        }
    }

    fun playKeyboardClick() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 45)
        } catch (_: Exception) {
        }
    }

    var selectedPrayer by remember {
        mutableStateOf(PrayerType.DHUHR)
    }

    var sujoodCount by remember {
        mutableIntStateOf(0)
    }

    var autoAdvancedRakat by remember {
        mutableIntStateOf(0)
    }

    var prayerFinishedFlag by remember {
        mutableStateOf(false)
    }

    // زمان‌های ذخیره‌شده در حافظه گوشی
    var noTashahhudSeconds by remember {
        mutableIntStateOf(prefs.getInt(KEY_NO_TASHAHHUD, 5).coerceIn(1, 120))
    }

    var withTashahhudSeconds by remember {
        mutableIntStateOf(prefs.getInt(KEY_WITH_TASHAHHUD, 15).coerceIn(1, 180))
    }

    // تنظیمات نماز دلخواه (پیش‌فرض: ۶ نماز ۲ رکعتی = ۱۲ رکعت)
    var customPrayerCount by remember {
        mutableIntStateOf(prefs.getInt(KEY_CUSTOM_PRAYERS, 6).coerceIn(1, 30))
    }

    var customRakatsPerPrayer by remember {
        mutableIntStateOf(prefs.getInt(KEY_CUSTOM_RAKATS, 2).coerceIn(1, 8))
    }

    val customParts = remember(customPrayerCount, customRakatsPerPrayer) {
        buildCustomParts(customPrayerCount, customRakatsPerPrayer)
    }

    var showSettingsDialog by remember {
        mutableStateOf(false)
    }

    var showCustomPrayerDialog by remember {
        mutableStateOf(false)
    }

    var sensorActive by remember {
        mutableStateOf(false)
    }

    val totalRakatsOverall = when (selectedPrayer) {
        PrayerType.CUSTOM -> customParts.sumOf { it.rakatCount }.coerceAtLeast(1)
        else -> selectedPrayer.totalRakats
    }
    val maxSujood = totalRakatsOverall * 2

    val displayState = remember(
        selectedPrayer,
        sujoodCount,
        autoAdvancedRakat,
        noTashahhudSeconds,
        withTashahhudSeconds,
        customParts,
        prayerFinishedFlag
    ) {
        resolvePrayerDisplayState(
            prayer = selectedPrayer,
            sujoodCount = sujoodCount,
            autoAdvancedRakat = autoAdvancedRakat,
            noTashahhudSec = noTashahhudSeconds,
            withTashahhudSec = withTashahhudSeconds,
            customParts = customParts,
            prayerFinishedFlag = prayerFinishedFlag
        )
    }

    fun resetPrayerState() {
        sujoodCount = 0
        autoAdvancedRakat = 0
        prayerFinishedFlag = false
    }

    // تایمر خودکار پس از سجده دوم هر رکعت (شامل انتظار تشهد/سلام پس از آخرین سجده نماز)
    LaunchedEffect(
        selectedPrayer,
        sujoodCount,
        noTashahhudSeconds,
        withTashahhudSeconds,
        prayerFinishedFlag
    ) {
        val waitSec = displayState.waitAfterSecondSujoodSeconds
        if (!prayerFinishedFlag && sujoodCount > 0 && sujoodCount % 2 == 0 && waitSec != null) {
            delay(waitSec * 1000L)

            if (displayState.isLastSujoodOfPrayer) {
                prayerFinishedFlag = true
                playKeyboardClick()
            } else {
                val currentRakat = sujoodCount / 2
                if (currentRakat < totalRakatsOverall) {
                    autoAdvancedRakat = currentRakat + 1
                    playKeyboardClick()
                }
            }
        }
    }

    val countLock = remember { Any() }
    val lastCountTime = remember { AtomicLong(0L) }
    val nearAlreadyCounted = remember { AtomicBoolean(false) }

    val countSujood: () -> Unit = {
        synchronized(countLock) {
            val now = System.currentTimeMillis()
            val previous = lastCountTime.get()

            if (!prayerFinishedFlag &&
                sujoodCount < maxSujood &&
                now - previous >= 1500L
            ) {
                sujoodCount = (sujoodCount + 1).coerceAtMost(maxSujood)
                autoAdvancedRakat = 0
                lastCountTime.set(now)
                playKeyboardClick()
            }
        }
    }

    DisposableEffect(
        lifecycleOwner,
        proximitySensor,
        selectedPrayer,
        prayerFinishedFlag
    ) {
        val sensor = proximitySensor

        if (sensor == null) {
            sensorActive = false
            return@DisposableEffect onDispose {}
        }

        var lastNear: Boolean? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isEmpty()) return

                val value = event.values[0]
                val isNear = value < sensor.maximumRange

                if (lastNear == null) {
                    lastNear = isNear
                    return
                }

                val wasNear = lastNear!!
                lastNear = isNear

                if (!wasNear && isNear) {
                    if (nearAlreadyCounted.compareAndSet(false, true)) {
                        countSujood()
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
                    if (nearAlreadyCounted.compareAndSet(false, true)) {
                        countSujood()
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
        } catch (_: Exception) {
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

                    if (!prayerFinishedFlag &&
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

            if (!prayerFinishedFlag && wakeLock != null) {
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

    if (showSettingsDialog) {
        TimingSettingsDialog(
            initialNoTashahhud = noTashahhudSeconds,
            initialWithTashahhud = withTashahhudSeconds,
            onDismiss = { showSettingsDialog = false },
            onSave = { newNoTashahhud, newWithTashahhud ->
                noTashahhudSeconds = newNoTashahhud
                withTashahhudSeconds = newWithTashahhud
                prefs.edit()
                    .putInt(KEY_NO_TASHAHHUD, newNoTashahhud)
                    .putInt(KEY_WITH_TASHAHHUD, newWithTashahhud)
                    .apply()
                showSettingsDialog = false
            }
        )
    }

    if (showCustomPrayerDialog) {
        CustomPrayerDialog(
            initialPrayerCount = customPrayerCount,
            initialRakatsPerPrayer = customRakatsPerPrayer,
            onDismiss = { showCustomPrayerDialog = false },
            onConfirm = { newPrayers, newRakats ->
                customPrayerCount = newPrayers
                customRakatsPerPrayer = newRakats
                prefs.edit()
                    .putInt(KEY_CUSTOM_PRAYERS, newPrayers)
                    .putInt(KEY_CUSTOM_RAKATS, newRakats)
                    .apply()
                selectedPrayer = PrayerType.CUSTOM
                resetPrayerState()
                nearAlreadyCounted.set(false)
                lastCountTime.set(0L)
                showCustomPrayerDialog = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .safeDrawingPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // نوار بالای صفحه
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "→",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 10.dp)
                )
                Text(
                    text = "رکعت شمار",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (sensorActive) "سنسور فعال" else "سنسور غیرفعال",
                    color = if (sensorActive) GoldSoft else Color(0xFFEF5350),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, GoldAccent.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                        .background(CardDark)
                        .clickable { showSettingsDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "تنظیمات",
                        color = GoldSoft,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // قاب محراب طلایی وسط صفحه با جای ثابت برای هر بخش
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 8.dp)
        ) {
            MehrabGoldFrame(modifier = Modifier.fillMaxSize())

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 48.dp)
            ) {
                when (displayState.mode) {
                    DisplayMode.FINISHED -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = displayState.sectionTitle ?: selectedPrayer.title,
                                color = GoldSoft,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "التماس دعا",
                                color = Color.White,
                                fontSize = 42.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "نماز به پایان رسید",
                                color = GoldSoft,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    DisplayMode.ALLAH_AKBAR -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = displayState.sectionTitle ?: selectedPrayer.title,
                                color = GoldSoft,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = "الله اکبر",
                                color = Color.White,
                                fontSize = 44.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "${displayState.totalRakatsOverall} رکعت",
                                color = GoldSoft,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    DisplayMode.RAKAT_ONLY, DisplayMode.SUJOOD_FIRST, DisplayMode.SUJOOD_SECOND -> {
                        val hasSujood = displayState.mode == DisplayMode.SUJOOD_FIRST ||
                            displayState.mode == DisplayMode.SUJOOD_SECOND
                        val sujoodNum = if (displayState.mode == DisplayMode.SUJOOD_FIRST) 1 else 2

                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // جایگاه ثابت عنوان نماز / بخش
                            Text(
                                text = displayState.sectionTitle ?: selectedPrayer.title,
                                color = GoldSoft,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )

                            // جایگاه ثابت متن و فلش رکعت
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "رکعت ${ordinalPersian(displayState.rakatInSection)}",
                                    color = Color.White,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                RakatArrowRow(count = displayState.rakatInSection)
                            }

                            // جایگاه ثابت مثلث و متن سجده (حتی وقتی فلش سجده نمایش داده نمی‌شود)
                            Box(
                                modifier = Modifier.height(86.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (hasSujood) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        SujoodTriangleRow(count = sujoodNum)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = if (sujoodNum == 1) "سجده اول" else "سجده دوم",
                                            color = Color.White,
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            // جایگاه ثابت رکعت کل در نمازهای چندبخشی
                            if (selectedPrayer == PrayerType.NIGHT_PRAYER || selectedPrayer == PrayerType.CUSTOM) {
                                Text(
                                    text = "کل: رکعت ${displayState.totalRakatGlobal} از ${displayState.totalRakatsOverall}",
                                    color = Color.LightGray,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            } else {
                                Spacer(modifier = Modifier.height(18.dp))
                            }
                        }
                    }
                }
            }
        }

        // بخش پایینی صفحه: دکمه‌های کمکی، انتخاب نماز و نماز دلخواه
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // نوار زمان‌های تنظیم‌شده
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(CardDark)
                    .padding(vertical = 7.dp, horizontal = 10.dp)
            ) {
                Text(
                    text = "بدون تشهد/سلام: ${noTashahhudSeconds}s  •  با تشهد/سلام: ${withTashahhudSeconds}s",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }

            // دکمه‌های کمکی (ثبت دستی / برگشت / شروع مجدد)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (!prayerFinishedFlag) {
                            sujoodCount = (sujoodCount + 1).coerceAtMost(maxSujood)
                            autoAdvancedRakat = 0
                            playKeyboardClick()
                        }
                    },
                    border = BorderStroke(1.dp, GoldAccent),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("ثبت دستی", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }

                OutlinedButton(
                    onClick = {
                        if (prayerFinishedFlag) {
                            prayerFinishedFlag = false
                        } else if (autoAdvancedRakat > 0) {
                            autoAdvancedRakat = 0
                        } else if (sujoodCount > 0) {
                            sujoodCount--
                            nearAlreadyCounted.set(false)
                        }
                    },
                    border = BorderStroke(1.dp, GoldAccent.copy(alpha = 0.75f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("برگشت", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }

                Button(
                    onClick = {
                        resetPrayerState()
                        nearAlreadyCounted.set(false)
                        lastCountTime.set(0L)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8E2424)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("شروع مجدد", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }

            // دکمه‌های انتخاب نماز در پایین صفحه
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
                            .height(42.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isSelected) GoldAccent.copy(alpha = 0.24f)
                                else Color.Transparent
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) GoldSoft else GoldAccent.copy(alpha = 0.75f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                selectedPrayer = prayer
                                resetPrayerState()
                                nearAlreadyCounted.set(false)
                                lastCountTime.set(0L)
                            }
                    ) {
                        Text(
                            text = prayer.title.replace("نماز ", ""),
                            color = if (isSelected) GoldSoft else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            // دکمه بزرگ نماز دلخواه در زیر دکمه‌های انتخاب نماز
            val isCustomSelected = selectedPrayer == PrayerType.CUSTOM
            Button(
                onClick = { showCustomPrayerDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCustomSelected) GoldAccent else CardDark
                ),
                border = BorderStroke(
                    width = if (isCustomSelected) 2.dp else 1.2.dp,
                    color = GoldSoft
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = if (isCustomSelected) {
                        "نماز دلخواه ($customPrayerCount نماز × $customRakatsPerPrayer رکعت)"
                    } else {
                        "نماز دلخواه"
                    },
                    color = if (isCustomSelected) PureBlack else GoldSoft,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CustomPrayerDialog(
    initialPrayerCount: Int,
    initialRakatsPerPrayer: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var prayerCount by remember(initialPrayerCount) {
        mutableIntStateOf(initialPrayerCount)
    }
    var rakatsPerPrayer by remember(initialRakatsPerPrayer) {
        mutableIntStateOf(initialRakatsPerPrayer)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            border = BorderStroke(1.dp, GoldAccent),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "تنظیم نماز دلخواه",
                    color = GoldSoft,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "چند نماز؟",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = { if (prayerCount > 1) prayerCount-- },
                        border = BorderStroke(1.dp, GoldAccent)
                    ) {
                        Text("-", color = Color.White, fontSize = 20.sp)
                    }
                    Text(
                        text = "$prayerCount نماز",
                        color = GoldSoft,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = { if (prayerCount < 30) prayerCount++ },
                        border = BorderStroke(1.dp, GoldAccent)
                    ) {
                        Text("+", color = Color.White, fontSize = 20.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "هر نماز چند رکعت؟",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = { if (rakatsPerPrayer > 1) rakatsPerPrayer-- },
                        border = BorderStroke(1.dp, GoldAccent)
                    ) {
                        Text("-", color = Color.White, fontSize = 20.sp)
                    }
                    Text(
                        text = "$rakatsPerPrayer رکعتی",
                        color = GoldSoft,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = { if (rakatsPerPrayer < 8) rakatsPerPrayer++ },
                        border = BorderStroke(1.dp, GoldAccent)
                    ) {
                        Text("+", color = Color.White, fontSize = 20.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "مجموع: ${prayerCount * rakatsPerPrayer} رکعت",
                    color = Color.LightGray,
                    fontSize = 15.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        border = BorderStroke(1.dp, Color.Gray),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("لغو", color = Color.LightGray, fontSize = 15.sp)
                    }

                    Button(
                        onClick = { onConfirm(prayerCount, rakatsPerPrayer) },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("شروع", color = PureBlack, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimingSettingsDialog(
    initialNoTashahhud: Int,
    initialWithTashahhud: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit
) {
    var noTashahhud by remember(initialNoTashahhud) {
        mutableIntStateOf(initialNoTashahhud)
    }
    var withTashahhud by remember(initialWithTashahhud) {
        mutableIntStateOf(initialWithTashahhud)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            border = BorderStroke(1.dp, GoldAccent),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "تنظیمات زمان پس از سجده دوم",
                    color = GoldSoft,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "رکعت بدون تشهد یا سلام",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = { if (noTashahhud > 1) noTashahhud-- },
                        border = BorderStroke(1.dp, GoldAccent)
                    ) {
                        Text("-", color = Color.White, fontSize = 20.sp)
                    }
                    Text(
                        text = "$noTashahhud ثانیه",
                        color = GoldSoft,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = { if (noTashahhud < 120) noTashahhud++ },
                        border = BorderStroke(1.dp, GoldAccent)
                    ) {
                        Text("+", color = Color.White, fontSize = 20.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "رکعت دارای تشهد یا سلام",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = { if (withTashahhud > 1) withTashahhud-- },
                        border = BorderStroke(1.dp, GoldAccent)
                    ) {
                        Text("-", color = Color.White, fontSize = 20.sp)
                    }
                    Text(
                        text = "$withTashahhud ثانیه",
                        color = GoldSoft,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = { if (withTashahhud < 180) withTashahhud++ },
                        border = BorderStroke(1.dp, GoldAccent)
                    ) {
                        Text("+", color = Color.White, fontSize = 20.sp)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        border = BorderStroke(1.dp, Color.Gray),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("لغو", color = Color.LightGray, fontSize = 15.sp)
                    }

                    Button(
                        onClick = { onSave(noTashahhud, withTashahhud) },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("ذخیره", color = PureBlack, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
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
private fun RakatArrowRow(count: Int) {
    val safeCount = count.coerceIn(1, 8)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(safeCount) {
            RakatUpArrowIcon()
        }
    }
}

@Composable
private fun RakatUpArrowIcon() {
    Canvas(modifier = Modifier.size(width = 34.dp, height = 62.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val headH = h * 0.34f
        val shaftTop = headH * 0.85f
        val shaftWidth = w * 0.28f

        drawRect(
            color = Color.White,
            topLeft = Offset(cx - shaftWidth / 2f, shaftTop),
            size = Size(shaftWidth, h - shaftTop)
        )
        val head = Path().apply {
            moveTo(cx, 0f)
            lineTo(w * 0.92f, headH)
            lineTo(w * 0.08f, headH)
            close()
        }
        drawPath(path = head, color = Color.White)
    }
}

@Composable
private fun SujoodTriangleRow(count: Int) {
    val safeCount = count.coerceIn(1, 2)
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(safeCount) {
            SujoodDownTriangleIcon()
        }
    }
}

@Composable
private fun SujoodDownTriangleIcon() {
    Canvas(modifier = Modifier.size(width = 34.dp, height = 28.dp)) {
        val w = size.width
        val h = size.height
        val triangle = Path().apply {
            moveTo(0f, 0f)
            lineTo(w, 0f)
            lineTo(w / 2f, h)
            close()
        }
        drawPath(path = triangle, color = Color.White)
    }
}
