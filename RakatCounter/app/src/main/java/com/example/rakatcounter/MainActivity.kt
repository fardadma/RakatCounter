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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

enum class PrayerType(val title: String, val totalRakats: Int) {
    FAJR("نماز صبح", 2),
    DHUHR("نماز ظهر", 4),
    ASR("نماز عصر", 4),
    MAGHRIB("نماز مغرب", 3),
    ISHA("نماز عشاء", 4),
    NIGHT_PRAYER("نماز شب", 11)
}

private enum class DisplayMode {
    ALLAH_AKBAR,
    RAKAT_ONLY,
    SUJOOD_FIRST,
    SUJOOD_SECOND,
    FINISHED
}

private data class NightPartInfo(
    val partIndex: Int, // 0..5 (0..3: اول..چهارم، 4: شفع، 5: وتر)
    val title: String,
    val rakatCount: Int,
    val startRakatGlobal: Int,
    val hasTashahhudOrSalamAfterSecondSujood: Boolean
)

private val NightParts = listOf(
    NightPartInfo(0, "نماز اول", 2, 1, true),
    NightPartInfo(1, "نماز دوم", 2, 3, true),
    NightPartInfo(2, "نماز سوم", 2, 5, true),
    NightPartInfo(3, "نماز چهارم", 2, 7, true),
    NightPartInfo(4, "نماز شفع", 2, 9, true),
    NightPartInfo(5, "نماز وتر", 1, 11, true)
)

private data class PrayerDisplayState(
    val mode: DisplayMode,
    val rakatInSection: Int,
    val totalRakatGlobal: Int,
    val sectionTitle: String?,
    val waitAfterSecondSujoodSeconds: Int?
)

private fun ordinalPersian(num: Int): String = when (num) {
    1 -> "اول"
    2 -> "دوم"
    3 -> "سوم"
    4 -> "چهارم"
    else -> num.toString()
}

private fun hasTashahhudOrSalam(prayer: PrayerType, rakatInSection: Int): Boolean {
    return when (prayer) {
        PrayerType.FAJR -> rakatInSection == 2
        PrayerType.DHUHR, PrayerType.ASR, PrayerType.ISHA -> rakatInSection == 2 || rakatInSection == 4
        PrayerType.MAGHRIB -> rakatInSection == 2 || rakatInSection == 3
        PrayerType.NIGHT_PRAYER -> rakatInSection == 2 || rakatInSection == 1
    }
}

private fun resolvePrayerDisplayState(
    prayer: PrayerType,
    sujoodCount: Int,
    autoAdvancedRakat: Int,
    noTashahhudSec: Int,
    withTashahhudSec: Int
): PrayerDisplayState {
    val maxSujood = prayer.totalRakats * 2

    if (sujoodCount >= maxSujood) {
        return PrayerDisplayState(
            mode = DisplayMode.FINISHED,
            rakatInSection = prayer.totalRakats,
            totalRakatGlobal = prayer.totalRakats,
            sectionTitle = null,
            waitAfterSecondSujoodSeconds = null
        )
    }

    if (sujoodCount == 0 && autoAdvancedRakat <= 1) {
        return PrayerDisplayState(
            mode = DisplayMode.ALLAH_AKBAR,
            rakatInSection = 1,
            totalRakatGlobal = 1,
            sectionTitle = null,
            waitAfterSecondSujoodSeconds = null
        )
    }

    val lastSujoodRakat = ((sujoodCount - 1).coerceAtLeast(0) / 2) + 1
    val isSecondSujood = sujoodCount > 0 && sujoodCount % 2 == 0

    // اگر تایمر خودکار رکعت بعدی را جلو برده و هنوز سجده جدیدی برای آن ثبت نشده است
    if (autoAdvancedRakat > lastSujoodRakat && sujoodCount % 2 == 0) {
        val targetRakat = autoAdvancedRakat.coerceAtMost(prayer.totalRakats)
        if (prayer == PrayerType.NIGHT_PRAYER) {
            val part = NightParts.firstOrNull {
                targetRakat >= it.startRakatGlobal &&
                    targetRakat < it.startRakatGlobal + it.rakatCount
            } ?: NightParts.last()
            val rakatInPart = targetRakat - part.startRakatGlobal + 1
            return PrayerDisplayState(
                mode = DisplayMode.RAKAT_ONLY,
                rakatInSection = rakatInPart,
                totalRakatGlobal = targetRakat,
                sectionTitle = part.title,
                waitAfterSecondSujoodSeconds = null
            )
        } else {
            return PrayerDisplayState(
                mode = DisplayMode.RAKAT_ONLY,
                rakatInSection = targetRakat,
                totalRakatGlobal = targetRakat,
                sectionTitle = null,
                waitAfterSecondSujoodSeconds = null
            )
        }
    }

    val activeRakat = lastSujoodRakat.coerceAtMost(prayer.totalRakats)
    val mode = if (isSecondSujood) DisplayMode.SUJOOD_SECOND else DisplayMode.SUJOOD_FIRST

    if (prayer == PrayerType.NIGHT_PRAYER) {
        val part = NightParts.firstOrNull {
            activeRakat >= it.startRakatGlobal &&
                activeRakat < it.startRakatGlobal + it.rakatCount
        } ?: NightParts.last()
        val rakatInPart = activeRakat - part.startRakatGlobal + 1

        val waitSec = if (isSecondSujood) {
            if (part.hasTashahhudOrSalamAfterSecondSujood && rakatInPart == part.rakatCount) {
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
            sectionTitle = part.title,
            waitAfterSecondSujoodSeconds = waitSec
        )
    } else {
        val waitSec = if (isSecondSujood) {
            if (hasTashahhudOrSalam(prayer, activeRakat)) {
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
            sectionTitle = null,
            waitAfterSecondSujoodSeconds = waitSec
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

    // صدای تیک کیبورد (بدون نیاز به فایل خارجی)
    val toneGenerator = remember {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
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

    // شماره رکعتی که پس از اتمام تایمر به‌صورت خودکار (بدون فلش سجده) نمایش داده می‌شود
    var autoAdvancedRakat by remember {
        mutableIntStateOf(0)
    }

    // تنظیمات زمان انتظار (ثانیه)
    var noTashahhudSeconds by remember {
        mutableIntStateOf(5)
    }

    var withTashahhudSeconds by remember {
        mutableIntStateOf(15)
    }

    var showSettingsDialog by remember {
        mutableStateOf(false)
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

    val maxSujood = selectedPrayer.totalRakats * 2
    val isPrayerFinished = sujoodCount >= maxSujood

    val displayState = remember(
        selectedPrayer,
        sujoodCount,
        autoAdvancedRakat,
        noTashahhudSeconds,
        withTashahhudSeconds
    ) {
        resolvePrayerDisplayState(
            prayer = selectedPrayer,
            sujoodCount = sujoodCount,
            autoAdvancedRakat = autoAdvancedRakat,
            noTashahhudSec = noTashahhudSeconds,
            withTashahhudSec = withTashahhudSeconds
        )
    }

    fun resetPrayerState() {
        sujoodCount = 0
        autoAdvancedRakat = 0
        lastCountSource = "هنوز ثبت نشده"
    }

    // تایمر خودکار پس از سجده دوم هر رکعت
    LaunchedEffect(
        selectedPrayer,
        sujoodCount,
        noTashahhudSeconds,
        withTashahhudSeconds
    ) {
        val waitSec = displayState.waitAfterSecondSujoodSeconds
        if (!isPrayerFinished && sujoodCount > 0 && sujoodCount % 2 == 0 && waitSec != null) {
            delay(waitSec * 1000L)

            val currentRakat = sujoodCount / 2
            if (currentRakat >= selectedPrayer.totalRakats) {
                sujoodCount = maxSujood
                playKeyboardClick()
            } else {
                autoAdvancedRakat = currentRakat + 1
                playKeyboardClick()
            }
        }
    }

    val countLock = remember { Any() }
    val lastCountTime = remember { AtomicLong(0L) }
    val nearAlreadyCounted = remember { AtomicBoolean(false) }

    val countSujood: (String) -> Unit = { source ->
        synchronized(countLock) {
            val now = System.currentTimeMillis()
            val previous = lastCountTime.get()

            if (!isPrayerFinished &&
                sujoodCount < maxSujood &&
                now - previous >= 1500L
            ) {
                sujoodCount = (sujoodCount + 1).coerceAtMost(maxSujood)
                autoAdvancedRakat = 0
                lastCountTime.set(now)
                lastCountSource = source
                playKeyboardClick()
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

    if (showSettingsDialog) {
        TimingSettingsDialog(
            initialNoTashahhud = noTashahhudSeconds,
            initialWithTashahhud = withTashahhudSeconds,
            onDismiss = { showSettingsDialog = false },
            onSave = { newNoTashahhud, newWithTashahhud ->
                noTashahhudSeconds = newNoTashahhud
                withTashahhudSeconds = newWithTashahhud
                showSettingsDialog = false
            }
        )
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (sensorActive) "سنسور فعال" else "سنسور غیرفعال",
                    color = if (sensorActive) GoldSoft else Color(0xFFEF5350),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, GoldAccent.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        .background(CardDark)
                        .clickable { showSettingsDialog = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "تنظیمات",
                        color = GoldSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
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
                when (displayState.mode) {
                    DisplayMode.FINISHED -> {
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

                    DisplayMode.ALLAH_AKBAR -> {
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

                    DisplayMode.RAKAT_ONLY -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (displayState.sectionTitle != null) {
                                Text(
                                    text = displayState.sectionTitle,
                                    color = GoldSoft,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }

                            Text(
                                text = "رکعت ${ordinalPersian(displayState.rakatInSection)}",
                                color = Color.White,
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            RakatArrowRow(count = displayState.rakatInSection)

                            if (selectedPrayer == PrayerType.NIGHT_PRAYER) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "کل: رکعت ${displayState.totalRakatGlobal} از ۱۱",
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    DisplayMode.SUJOOD_FIRST, DisplayMode.SUJOOD_SECOND -> {
                        val sujoodNum = if (displayState.mode == DisplayMode.SUJOOD_FIRST) 1 else 2
                        val sujoodText = if (sujoodNum == 1) "سجده اول" else "سجده دوم"

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (displayState.sectionTitle != null) {
                                Text(
                                    text = displayState.sectionTitle,
                                    color = GoldSoft,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }

                            Text(
                                text = "رکعت ${ordinalPersian(displayState.rakatInSection)}",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            RakatArrowRow(count = displayState.rakatInSection)

                            Spacer(modifier = Modifier.height(18.dp))

                            SujoodTriangleRow(count = sujoodNum)

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = sujoodText,
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )

                            if (selectedPrayer == PrayerType.NIGHT_PRAYER) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "کل: رکعت ${displayState.totalRakatGlobal} از ۱۱",
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }
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
                            resetPrayerState()
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
                text = "بدون تشهد/سلام: ${noTashahhudSeconds}s • با تشهد/سلام: ${withTashahhudSeconds}s",
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
                        autoAdvancedRakat = 0
                        lastCountSource = "ثبت دستی"
                        playKeyboardClick()
                    }
                },
                border = BorderStroke(1.dp, GoldAccent),
                modifier = Modifier.weight(1f)
            ) {
                Text("ثبت دستی", color = Color.White, fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = {
                    if (autoAdvancedRakat > 0) {
                        autoAdvancedRakat = 0
                    } else if (sujoodCount > 0) {
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
                    resetPrayerState()
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
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "تنظیمات زمان پس از سجده دوم",
                    color = GoldSoft,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // زمان رکعت بدون تشهد یا سلام
                Text(
                    text = "رکعت بدون تشهد یا سلام: $noTashahhud ثانیه",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { if (noTashahhud > 1) noTashahhud-- },
                        border = BorderStroke(1.dp, GoldAccent)
                    ) {
                        Text("-", color = Color.White, fontSize = 18.sp)
                    }
                    Text(
                        text = "$noTashahhud s",
                        color = GoldSoft,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = { if (noTashahhud < 60) noTashahhud++ },
                        border = BorderStroke(1.dp, GoldAccent)
                    ) {
                        Text("+", color = Color.White, fontSize = 18.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // زمان رکعت با تشهد یا سلام
                Text(
                    text = "رکعت دارای تشهد یا سلام: $withTashahhud ثانیه",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { if (withTashahhud > 1) withTashahhud-- },
                        border = BorderStroke(1.dp, GoldAccent)
                    ) {
                        Text("-", color = Color.White, fontSize = 18.sp)
                    }
                    Text(
                        text = "$withTashahhud s",
                        color = GoldSoft,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = { if (withTashahhud < 120) withTashahhud++ },
                        border = BorderStroke(1.dp, GoldAccent)
                    ) {
                        Text("+", color = Color.White, fontSize = 18.sp)
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
                        Text("لغو", color = Color.LightGray)
                    }

                    Button(
                        onClick = { onSave(noTashahhud, withTashahhud) },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("ذخیره", color = PureBlack, fontWeight = FontWeight.Bold)
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
    val safeCount = count.coerceIn(1, 4)
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
    Canvas(modifier = Modifier.size(width = 24.dp, height = 44.dp)) {
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
    Canvas(modifier = Modifier.size(width = 28.dp, height = 24.dp)) {
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
