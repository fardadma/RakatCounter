
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
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import java.util.Locale

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
    val clampedSujood = totalSujood.coerceIn(0, maxSujood)
    val isAllFinished = clampedSujood >= maxSujood

    val effectiveSujood =
        if (isAllFinished) maxSujood - 1 else clampedSujood

    val rakatIndexZeroBased = effectiveSujood / 2
    val sujoodIndexInRakat = effectiveSujood % 2
    val totalRakatNumber = rakatIndexZeroBased + 1

    val sujoodLabel =
        if (sujoodIndexInRakat == 0) "سجده اول" else "سجده دوم"

    return when {
        rakatIndexZeroBased < 8 -> {
            val partIndex = (rakatIndexZeroBased / 2) + 1
            val rakatInPartIndex = (rakatIndexZeroBased % 2) + 1
            val isPartEnd =
                rakatInPartIndex == 2 && sujoodIndexInRakat == 1

            NightPrayerState(
                prayerName = "نماز ${ordinalPersian(partIndex)}",
                rakatInPart = "رکعت ${ordinalPersian(rakatInPartIndex)}",
                sujoodInRakat = sujoodLabel,
                totalRakatNumber = totalRakatNumber,
                isPartEnd = isPartEnd,
                isAllFinished = false
            )
        }

        rakatIndexZeroBased < 10 -> {
            val rakatInShafa = (rakatIndexZeroBased - 8) + 1
            val isPartEnd =
                rakatInShafa == 2 && sujoodIndexInRakat == 1

            NightPrayerState(
                prayerName = "نماز شفع",
                rakatInPart = "رکعت ${ordinalPersian(rakatInShafa)}",
                sujoodInRakat = sujoodLabel,
                totalRakatNumber = totalRakatNumber,
                isPartEnd = isPartEnd,
                isAllFinished = false
            )
        }

        else -> {
            NightPrayerState(
                prayerName = "نماز وتر",
                rakatInPart = "رکعت واحد",
                sujoodInRakat = sujoodLabel,
                totalRakatNumber = totalRakatNumber,
                isPartEnd = sujoodIndexInRakat == 1,
                isAllFinished = isAllFinished
            )
        }
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen on
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

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

    var selectedPrayer by remember {
        mutableStateOf(PrayerType.NIGHT_PRAYER)
    }

    var sujoodCount by remember {
        mutableIntStateOf(0)
    }

    var sensorAvailable by remember {
        mutableStateOf(true)
    }

    var sensorActive by remember {
        mutableStateOf(false)
    }

    var showDebug by remember {
        mutableStateOf(true)
    }

    // Sensor manager

    val sensorManager = remember {
        context.getSystemService(
            Context.SENSOR_SERVICE
        ) as SensorManager
    }

    val proximitySensor = remember {
        sensorManager.getDefaultSensor(
            Sensor.TYPE_PROXIMITY
        )
    }

    // Debug values

    var sensorDistance by remember {
        mutableFloatStateOf(Float.NaN)
    }

    var sensorMaxRange by remember {
        mutableFloatStateOf(0f)
    }

    var sensorResolution by remember {
        mutableFloatStateOf(0f)
    }

    var sensorName by remember {
        mutableStateOf("Unknown")
    }

    var sensorVendor by remember {
        mutableStateOf("Unknown")
    }

    var sensorEventCount by remember {
        mutableIntStateOf(0)
    }

    var sensorNear by remember {
        mutableStateOf(false)
    }

    var lastRegisteredTime by remember {
        mutableStateOf("هنوز ثبت نشده")
    }

    val isNightPrayer =
        selectedPrayer == PrayerType.NIGHT_PRAYER

    val maxSujood =
        selectedPrayer.totalRakats * 2

    val completedRakats =
        sujoodCount / 2

    val currentRakat =
        if (completedRakats < selectedPrayer.totalRakats)
            completedRakats + 1
        else
            selectedPrayer.totalRakats

    val sujoodInCurrentRakat =
        sujoodCount % 2

    val isPrayerFinished =
        sujoodCount >= maxSujood

    val nightState = remember(sujoodCount) {
        resolveNightPrayerState(sujoodCount)
    }

    // Vibration

    fun triggerVibration(isStrongAlert: Boolean) {

        val vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                val manager = context.getSystemService(
                    Context.VIBRATOR_MANAGER_SERVICE
                ) as VibratorManager

                manager.defaultVibrator

            } else {

                @Suppress("DEPRECATION")
                context.getSystemService(
                    Context.VIBRATOR_SERVICE
                ) as Vibrator
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            if (isStrongAlert) {
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
            vibrator.vibrate(
                if (isStrongAlert) 350 else 120
            )
        }
    }

    // Direct proximity sensor counting + Debug

    DisposableEffect(
        sensorManager,
        proximitySensor,
        lifecycleOwner,
        selectedPrayer,
        isPrayerFinished
    ) {

        if (proximitySensor == null) {
            sensorAvailable = false
            sensorActive = false
            return@DisposableEffect onDispose {}
        }

        sensorAvailable = true

        sensorMaxRange = proximitySensor.maximumRange
        sensorResolution = proximitySensor.resolution
        sensorName = proximitySensor.name
        sensorVendor = proximitySensor.vendor

        var isListening = false
        var hasInitialValue = false
        var lastNear = false
        var lastSujoodTimestamp = 0L

        val minDelayBetweenSujoods = 2200L

        val listener = object : SensorEventListener {

            override fun onSensorChanged(event: SensorEvent) {

                if (event.values.isEmpty()) return

                val value = event.values[0]

                val currentlyNear =
                    value < proximitySensor.maximumRange

                // Update Debug values

                sensorDistance = value
                sensorNear = currentlyNear
                sensorEventCount++

                // First event initializes state only

                if (!hasInitialValue) {
                    lastNear = currentlyNear
                    hasInitialValue = true
                    return
                }

                val currentTime = System.currentTimeMillis()

                // FAR -> NEAR transition

                if (currentlyNear && !lastNear) {

                    if (
                        !isPrayerFinished &&
                        currentTime - lastSujoodTimestamp >=
                        minDelayBetweenSujoods
                    ) {

                        sujoodCount++

                        lastSujoodTimestamp = currentTime

                        lastRegisteredTime =
                            java.text.SimpleDateFormat(
                                "HH:mm:ss",
                                Locale.US
                            ).format(java.util.Date())

                        val strongAlert =
                            if (isNightPrayer) {
                                resolveNightPrayerState(
                                    sujoodCount
                                ).isPartEnd
                            } else {
                                sujoodCount % 2 == 0
                            }

                        triggerVibration(strongAlert)
                    }
                }

                lastNear = currentlyNear
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int
            ) {
            }
        }

        fun startListening() {

            if (!isListening) {

                isListening = sensorManager.registerListener(
                    listener,
                    proximitySensor,
                    SensorManager.SENSOR_DELAY_GAME
                )

                sensorActive = isListening
            }
        }

        fun stopListening() {

            if (isListening) {
                sensorManager.unregisterListener(listener)
                isListening = false
            }

            sensorActive = false
        }

        val observer = LifecycleEventObserver { _, event ->

            when (event) {

                Lifecycle.Event.ON_RESUME -> {
                    startListening()
                }

                Lifecycle.Event.ON_PAUSE -> {
                    stopListening()
                }

                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        if (
            lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.RESUMED)
        ) {
            startListening()
        }

        onDispose {
            stopListening()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Main screen

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // Header

        Text(
            text = "رکعت‌شمار هوشمند نماز",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = if (isNightPrayer) {
                "با نزدیک‌شدن پیشانی به سنسور، یک سجده ثبت می‌شود."
            } else {
                "گوشی را کنار مُهر قرار دهید و با نزدیک‌شدن پیشانی به سنسور سجده ثبت کنید."
            },
            fontSize = 12.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = if (sensorActive)
                "سنسور مجاورت: فعال (شمارش مستقیم)"
            else
                "سنسور مجاورت: غیرفعال",
            fontSize = 12.sp,
            color = if (sensorActive)
                Color(0xFF81C784)
            else
                Color(0xFFFFB74D)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Debug button

        OutlinedButton(
            onClick = { showDebug = !showDebug },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (showDebug)
                    "مخفی کردن Debug"
                else
                    "نمایش Debug"
            )
        }

        // Debug panel

        if (showDebug) {

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp),
                color = Color(0xFF263238),
                shape = RoundedCornerShape(12.dp)
            ) {

                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {

                    Text(
                        "PROXIMITY SENSOR DEBUG",
                        color = Color(0xFF80CBC4),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )

                    HorizontalDivider(color = Color.Gray)

                    DebugRow("Sensor", sensorName)
                    DebugRow("Vendor", sensorVendor)

                    DebugRow(
                        "Distance",
                        if (sensorDistance.isNaN())
                            "Waiting..."
                        else
                            String.format(
                                Locale.US,
                                "%.3f",
                                sensorDistance
                            )
                    )

                    DebugRow(
                        "Maximum Range",
                        String.format(
                            Locale.US,
                            "%.3f",
                            sensorMaxRange
                        )
                    )

                    DebugRow(
                        "Resolution",
                        String.format(
                            Locale.US,
                            "%.4f",
                            sensorResolution
                        )
                    )

                    DebugRow(
                        "Sensor State",
                        if (sensorDistance.isNaN())
                            "WAITING"
                        else if (sensorNear)
                            "NEAR"
                        else
                            "FAR",
                        if (sensorNear)
                            Color(0xFFFFB74D)
                        else
                            Color(0xFF81C784)
                    )

                    DebugRow(
                        "Event Count",
                        sensorEventCount.toString()
                    )

                    DebugRow(
                        "Registered",
                        sujoodCount.toString()
                    )

                    DebugRow(
                        "Last Sujood",
                        lastRegisteredTime
                    )

                    DebugRow(
                        "ADC",
                        "Unavailable via standard API",
                        Color.LightGray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Sensor availability

        if (!sensorAvailable) {

            Text(
                text = "خطا: سنسور مجاورت در گوشی یافت نشد!",
                color = Color.Red,
                fontWeight = FontWeight.Bold
            )

        } else {

            // Main counter

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(275.dp)
                    .clip(CircleShape)
                    .background(
                        if (isPrayerFinished)
                            Color(0xFF1B5E20)
                        else
                            MaterialTheme.colorScheme.surface
                    )
                    .padding(16.dp)
            ) {

                if (isPrayerFinished) {

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {

                        Text(
                            "تقبل الله",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFA5D6A7)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            "${selectedPrayer.title} (${selectedPrayer.totalRakats} رکعت) تمام شد",
                            fontSize = 15.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    }

                } else if (isNightPrayer) {

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {

                        Text(
                            nightState.prayerName,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFA5D6A7),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            "${nightState.rakatInPart} • ${nightState.sujoodInRakat}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            "رکعت ${nightState.totalRakatNumber} از ۱۱",
                            fontSize = 13.sp,
                            color = Color.LightGray
                        )

                        if (nightState.isPartEnd) {

                            Spacer(modifier = Modifier.height(8.dp))

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF4CAF50).copy(alpha = 0.25f)
                            ) {

                                Text(
                                    "پایان این بخش (سلام)",
                                    fontSize = 12.sp,
                                    color = Color(0xFFC8E6C9),
                                    modifier = Modifier.padding(
                                        horizontal = 10.dp,
                                        vertical = 4.dp
                                    )
                                )
                            }
                        }
                    }

                } else {

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {

                        Text(
                            "رکعت",
                            fontSize = 20.sp,
                            color = Color.LightGray
                        )

                        Text(
                            "$currentRakat از ${selectedPrayer.totalRakats}",
                            fontSize = 48.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {

                            SujoodIndicator(
                                label = "سجده ۱",
                                isDone = sujoodInCurrentRakat >= 1
                            )

                            SujoodIndicator(
                                label = "سجده ۲",
                                isDone = false
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Prayer selection

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp),
            userScrollEnabled = false
        ) {

            items(PrayerType.entries) { prayer ->

                val isSelected = prayer == selectedPrayer

                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedPrayer = prayer
                        sujoodCount = 0
                        lastRegisteredTime = "هنوز ثبت نشده"
                    },
                    label = {
                        Text(
                            text = prayer.title.replace("نماز ", ""),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontWeight = if (isSelected)
                                FontWeight.Bold
                            else
                                FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor =
                            MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Manual controls

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            OutlinedButton(
                onClick = {
                    if (!isPrayerFinished) {
                        sujoodCount++
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("ثبت دستی", fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = {
                    if (sujoodCount > 0) {
                        sujoodCount--
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("برگشت", fontSize = 13.sp)
            }

            Button(
                onClick = {
                    sujoodCount = 0
                    lastRegisteredTime = "هنوز ثبت نشده"
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFC62828)
                )
            ) {
                Text(
                    "شروع مجدد",
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

// Debug row

@Composable
fun DebugRow(
    label: String,
    value: String,
    valueColor: Color = Color.White
) {

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            text = label,
            color = Color.LightGray,
            fontSize = 12.sp
        )

        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// Sujood indicator

@Composable
fun SujoodIndicator(
    label: String,
    isDone: Boolean
) {

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isDone)
            Color(0xFF4CAF50)
        else
            Color(0xFF333333)
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
