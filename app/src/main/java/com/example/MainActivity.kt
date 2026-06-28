package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.api.GeminiClient
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Force Dark Theme for a premium video-editing suite feel (just like CapCut)
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F1013) // Modern cinematic dark backdrop
                ) {
                    VideoEditorScreen()
                }
            }
        }
    }
}

// --- Data Models ---
data class TextOverlay(
    val id: String,
    val text: String,
    val startTime: Float,
    val endTime: Float,
    val color: Color,
    val size: Float, // sp
    val yOffset: Float // dp
)

data class Subtitle(
    val id: String,
    val text: String,
    val startTime: Float,
    val endTime: Float
)

sealed class ActiveTab {
    object Trim : ActiveTab()
    object AiMagic : ActiveTab()
    object TextOverlays : ActiveTab()
    object Filters : ActiveTab()
    object TechCore : ActiveTab()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEditorScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // --- Video Editing States ---
    var videoName by remember { mutableStateOf("Vlog_Summer_Adventures.mp4") }
    var duration by remember { mutableStateOf(30.0f) }
    var currentTime by remember { mutableStateOf(0.0f) }
    var isPlaying by remember { mutableStateOf(false) }

    // Trimming State
    var trimStart by remember { mutableStateOf(0.0f) }
    var trimEnd by remember { mutableStateOf(30.0f) }

    // Text Overlays State
    val textOverlays = remember {
        mutableStateListOf(
            TextOverlay("1", "Summer Vibes ☀️", 1.0f, 6.0f, Color(0xFFFFD700), 24f, -40f),
            TextOverlay("2", "Cozy Coffee Break ☕", 11.0f, 15.0f, Color.White, 20f, 40f)
        )
    }
    var showAddTextDialog by remember { mutableStateOf(false) }

    // Subtitle / Captioning State
    val autoCaptions = remember {
        mutableStateListOf<Subtitle>()
    }
    var isGeneratingCaptions by remember { mutableStateOf(false) }

    // Filters State
    val filters = listOf("None", "Cinema Gold", "Cyberpunk Neon", "Classic B&W", "Vintage Retro", "Forest Cool")
    var selectedFilter by remember { mutableStateOf("None") }

    // AI Magic State
    var isBackgroundRemoved by remember { mutableStateOf(false) }
    var isAutoCutEnabled by remember { mutableStateOf(false) }
    var isAiProcessing by remember { mutableStateOf(false) }
    var aiLogText by remember { mutableStateOf("Ready to enhance video. Try 'Auto-Cut' or 'Auto-Caption'.") }

    // Waveform: High levels representing speech, low levels (below 15) representing silences
    // Silences are located at: 5s-8s, 14s-17s, 24s-26s
    val waveform = remember {
        listOf(
            45f, 52f, 48f, 60f, 35f, // 0-4s
            8f, 5f, 4f, 10f, 42f,    // 5-9s (Silence 5-8s)
            55f, 50f, 62f, 40f, 6f,  // 10-14s
            4f, 5f, 12f, 48f, 53f,   // 15-19s (Silence 14-17s)
            50f, 46f, 58f, 30f, 5f,  // 20-24s
            4f, 7f, 35f, 52f, 48f    // 25-29s (Silence 24-26s)
        )
    }

    // Silent intervals to skip if Auto-Cut is active
    val cutRanges = listOf(
        5.0f..8.0f,
        14.0f..17.0f,
        24.0f..26.0f
    )

    // Current Active Editing Tab
    var activeTab by remember { mutableStateOf<ActiveTab>(ActiveTab.Trim) }

    // Custom Export Dialog
    var showExportDialog by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }

    // Launcher for Custom Video Upload / Pick
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            videoName = uri.lastPathSegment ?: "Imported_Clip.mp4"
            currentTime = 0.0f
            trimStart = 0.0f
            trimEnd = 30.0f
            isPlaying = false
            Toast.makeText(context, "Loaded: $videoName", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Playback Controller Loop ---
    LaunchedEffect(isPlaying, isAutoCutEnabled, currentTime, trimStart, trimEnd) {
        if (isPlaying) {
            val updateIntervalMs = 100
            val advanceSec = updateIntervalMs / 1000.0f
            while (isPlaying) {
                delay(updateIntervalMs.toLong())
                var nextTime = currentTime + advanceSec

                // If Auto-Cut is enabled, check if nextTime falls in a silence range and skip it
                if (isAutoCutEnabled) {
                    for (range in cutRanges) {
                        if (nextTime in range) {
                            nextTime = range.endInclusive + 0.1f // Jump past silence
                            break
                        }
                    }
                }

                // Keep playback within trimmed boundaries
                if (nextTime > trimEnd) {
                    nextTime = trimStart
                } else if (nextTime < trimStart) {
                    nextTime = trimStart
                }

                currentTime = nextTime
            }
        }
    }

    // --- Main Layout ---
    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F1013))
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFFE91E63), Color(0xFF9C27B0)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Movie,
                                contentDescription = "Logo",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "CapCut AI Studio",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isPlaying) Color(0xFF00FFCC) else Color(0xFFFF9800))
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isPlaying) "Playing" else "Paused",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Import Button
                        OutlinedButton(
                            onClick = { videoPickerLauncher.launch("video/*") },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFF2C2D35)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp).testTag("upload_button")
                        ) {
                            Icon(Icons.Filled.CloudUpload, contentDescription = "Import", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import", fontSize = 12.sp)
                        }

                        // Export Button
                        Button(
                            onClick = { showExportDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00FFCC),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp).testTag("export_button")
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = "Export", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Divider(color = Color(0xFF1E2026), thickness = 1.dp)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF090A0C))
        ) {
            // Screen split: Upper half is Preview, Lower half is Timeline + Controls
            Column(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Video Preview Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF1E2026), RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .testTag("preview_window"),
                    contentAlignment = Alignment.Center
                ) {
                    // Render dynamically animated scene inside
                    SimulatedVideoCanvas(
                        currentTime = currentTime,
                        selectedFilter = selectedFilter,
                        isBackgroundRemoved = isBackgroundRemoved
                    )

                    // Render dynamic text overlays at their current timeframe
                    textOverlays.forEach { overlay ->
                        if (currentTime >= overlay.startTime && currentTime <= overlay.endTime) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(y = overlay.yOffset.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = overlay.text,
                                    color = overlay.color,
                                    fontSize = overlay.size.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // Render auto-captions/subtitles
                    autoCaptions.forEach { sub ->
                        if (currentTime >= sub.startTime && currentTime <= sub.endTime) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 24.dp)
                                    .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                                    .border(1.dp, Color(0xFF00FFCC).copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = sub.text,
                                    color = Color(0xFF00FFCC),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // Quick Overlay Playback Control Hover
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { isPlaying = !isPlaying }
                    ) {
                        if (!isPlaying) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .align(Alignment.Center),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }

                    // Watermark / Filter indicator
                    Text(
                        text = if (selectedFilter != "None") "FILTER: ${selectedFilter.uppercase()}" else "RAW FOOTAGE",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                    )

                    // Clip Name Overlay
                    Text(
                        text = videoName,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Playback controls row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(currentTime),
                        color = Color(0xFF00FFCC),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { currentTime = trimStart },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = "Restart", tint = Color.White)
                        }

                        IconButton(
                            onClick = { isPlaying = !isPlaying },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E2026))
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "PlayToggle",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        IconButton(
                            onClick = { currentTime = trimEnd },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "End", tint = Color.White)
                        }
                    }

                    Text(
                        text = formatTime(duration),
                        color = Color.Gray,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Divider(color = Color(0xFF14151A), thickness = 1.dp)

            // Lower half: Timeline & Editor Controls
            Column(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxWidth()
            ) {
                // Interactive Timeline Component
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(115.dp)
                        .background(Color(0xFF0C0D10))
                        .padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Time markings at the top of the timeline
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            for (i in 0..6) {
                                val markedSec = i * 5
                                Text(
                                    text = "${markedSec}s",
                                    color = Color.DarkGray,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Tracks container
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 16.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF13151B))
                                .border(1.dp, Color(0xFF1E2026), RoundedCornerShape(8.dp))
                        ) {
                            val trackWidth = maxWidth
                            // Trim highlights and waveforms
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val width = size.width
                                val height = size.height

                                // 1. Draw Waveform bars (representing audio levels)
                                val barCount = waveform.size
                                val barWidth = (width / barCount) * 0.85f
                                val spacing = (width / barCount) * 0.15f

                                for (i in 0 until barCount) {
                                    val level = waveform[i]
                                    val barHeight = (level / 100f) * (height * 0.5f)
                                    val x = i * (barWidth + spacing) + spacing
                                    val y = (height / 2f) - (barHeight / 2f)

                                    // Check if this bar is inside a cut/silence region and Auto-Cut is enabled
                                    val isCutActive = isAutoCutEnabled && cutRanges.any { (i.toFloat()) in it }
                                    val barColor = when {
                                        isCutActive -> Color(0xFFE91E63).copy(alpha = 0.3f) // Highlight trimmed silences
                                        level < 15f -> Color(0xFFFF9800).copy(alpha = 0.5f) // Potential silence detected
                                        else -> Color(0xFF00FFCC).copy(alpha = 0.7f) // Good active speech/sound
                                    }

                                    drawRect(
                                        color = barColor,
                                        topLeft = Offset(x, y),
                                        size = Size(barWidth, barHeight)
                                    )
                                }

                                // 2. Draw subtitle time regions if they exist
                                autoCaptions.forEach { subtitle ->
                                    val subStartPx = (subtitle.startTime / duration) * width
                                    val subEndPx = (subtitle.endTime / duration) * width
                                    drawRect(
                                        color = Color(0xFF00FFCC).copy(alpha = 0.15f),
                                        topLeft = Offset(subStartPx, height - 12f),
                                        size = Size(subEndPx - subStartPx, 8f)
                                    )
                                }

                                // 3. Draw text overlay visual spans
                                textOverlays.forEach { overlay ->
                                    val overlayStartPx = (overlay.startTime / duration) * width
                                    val overlayEndPx = (overlay.endTime / duration) * width
                                    drawRect(
                                        color = overlay.color.copy(alpha = 0.25f),
                                        topLeft = Offset(overlayStartPx, 4f),
                                        size = Size(overlayEndPx - overlayStartPx, 6f)
                                    )
                                }

                                // 4. Draw out-of-trim shadows (shading trimmed parts in dark grey overlay)
                                val trimStartPx = (trimStart / duration) * width
                                val trimEndPx = (trimEnd / duration) * width

                                // Left trimmed part
                                if (trimStartPx > 0) {
                                    drawRect(
                                        color = Color.Black.copy(alpha = 0.65f),
                                        topLeft = Offset(0f, 0f),
                                        size = Size(trimStartPx, height)
                                    )
                                }
                                // Right trimmed part
                                if (trimEndPx < width) {
                                    drawRect(
                                        color = Color.Black.copy(alpha = 0.65f),
                                        topLeft = Offset(trimEndPx, 0f),
                                        size = Size(width - trimEndPx, height)
                                    )
                                }

                                // Draw boundaries/borders around trimmed active zone
                                drawLine(
                                    color = Color(0xFFFFD700),
                                    start = Offset(trimStartPx, 0f),
                                    end = Offset(trimStartPx, height),
                                    strokeWidth = 3f
                                )
                                drawLine(
                                    color = Color(0xFFFFD700),
                                    start = Offset(trimEndPx, 0f),
                                    end = Offset(trimEndPx, height),
                                    strokeWidth = 3f
                                )
                            }

                            // 5. Draw active orange scrubbing playhead
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(2.dp)
                                    .offset(x = trackWidth * (currentTime / duration))
                                    .background(Color(0xFFFF5252))
                            ) {
                                // Mini playhead dot
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF5252))
                                        .align(Alignment.TopCenter)
                                )
                            }
                        }
                    }
                }

                // Scrolling/Scrubbing Slider Controller
                Slider(
                    value = currentTime,
                    onValueChange = {
                        isPlaying = false
                        currentTime = it.coerceIn(trimStart, trimEnd)
                    },
                    valueRange = 0.0f..30.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFF5252),
                        activeTrackColor = Color(0xFF1E2026),
                        inactiveTrackColor = Color(0xFF0C0D10)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .height(18.dp)
                )

                // Navigation Tabs of Editing Options
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1013))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    val tabs = listOf(
                        Triple(ActiveTab.Trim, Icons.Filled.ContentCut, "Trim"),
                        Triple(ActiveTab.AiMagic, Icons.Filled.AutoAwesome, "AI Magic"),
                        Triple(ActiveTab.TextOverlays, Icons.Filled.TextFields, "Text"),
                        Triple(ActiveTab.Filters, Icons.Filled.Palette, "Filters"),
                        Triple(ActiveTab.TechCore, Icons.Filled.Code, "Tech Explanations")
                    )

                    tabs.forEach { (tab, icon, label) ->
                        val isSelected = activeTab == tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { activeTab = tab }
                                .background(if (isSelected) Color(0xFF1C1D24) else Color.Transparent)
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = if (isSelected) Color(0xFF00FFCC) else Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = label,
                                    color = if (isSelected) Color.White else Color.Gray,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                Divider(color = Color(0xFF1C1D24), thickness = 1.dp)

                // Sub-panel details corresponding to activeTab
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF0C0D10))
                        .padding(12.dp)
                ) {
                    when (activeTab) {
                        is ActiveTab.Trim -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("TRIM LIMITS", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Start Offset (sec)", color = Color.Gray, fontSize = 10.sp)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            IconButton(
                                                onClick = { trimStart = (trimStart - 0.5f).coerceAtLeast(0.0f) },
                                                modifier = Modifier.size(28.dp).background(Color(0xFF1C1D24), RoundedCornerShape(4.dp))
                                            ) {
                                                Icon(Icons.Filled.Remove, contentDescription = "Dec", tint = Color.White, modifier = Modifier.size(14.dp))
                                            }
                                            Text(
                                                text = String.format("%.1fs", trimStart),
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            IconButton(
                                                onClick = { trimStart = (trimStart + 0.5f).coerceAtMost(trimEnd - 1.0f) },
                                                modifier = Modifier.size(28.dp).background(Color(0xFF1C1D24), RoundedCornerShape(4.dp))
                                            ) {
                                                Icon(Icons.Filled.Add, contentDescription = "Inc", tint = Color.White, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("End Offset (sec)", color = Color.Gray, fontSize = 10.sp)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            IconButton(
                                                onClick = { trimEnd = (trimEnd - 0.5f).coerceAtLeast(trimStart + 1.0f) },
                                                modifier = Modifier.size(28.dp).background(Color(0xFF1C1D24), RoundedCornerShape(4.dp))
                                            ) {
                                                Icon(Icons.Filled.Remove, contentDescription = "Dec", tint = Color.White, modifier = Modifier.size(14.dp))
                                            }
                                            Text(
                                                text = String.format("%.1fs", trimEnd),
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            IconButton(
                                                onClick = { trimEnd = (trimEnd + 0.5f).coerceAtMost(duration) },
                                                modifier = Modifier.size(28.dp).background(Color(0xFF1C1D24), RoundedCornerShape(4.dp))
                                            ) {
                                                Icon(Icons.Filled.Add, contentDescription = "Inc", tint = Color.White, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF14151A), RoundedCornerShape(8.dp))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Info, contentDescription = "Info", tint = Color(0xFF00FFCC), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Only the segments inside the gold boundaries (currently ${String.format("%.1fs", trimEnd - trimStart)} total) will play and export.",
                                        color = Color.LightGray,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }

                        is ActiveTab.AiMagic -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // 1. Auto-Cut Silences Button
                                    Button(
                                        onClick = {
                                            isAiProcessing = true
                                            aiLogText = "Analyzing audio tracks via Gemini 3.5 Flash..."
                                            coroutineScope.launch {
                                                // Convert waveform representation to string format
                                                val waveformString = waveform.joinToString(", ")
                                                val result = GeminiClient.requestSmartCut(waveformString)

                                                isAiProcessing = false
                                                if (result == "MOCK_SILENCE_TRIM" || BuildConfig.GEMINI_API_KEY.isEmpty()) {
                                                    isAutoCutEnabled = true
                                                    aiLogText = "Local offline Auto-Cut activated!\nDetected 3 silent blocks at [5-8s, 14-17s, 24-26s] and trimmed them successfully."
                                                } else {
                                                    isAutoCutEnabled = true
                                                    aiLogText = "AI Response: $result\nSuccessfully trimmed the recommended silent frames!"
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isAutoCutEnabled) Color(0xFFE91E63) else Color(0xFF1E2026),
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).testTag("autocut_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isAutoCutEnabled) Icons.Filled.ContentCut else Icons.Outlined.ContentCut,
                                            contentDescription = "Cut",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isAutoCutEnabled) "Auto-Cut ON" else "Auto-Cut", fontSize = 11.sp)
                                    }

                                    // 2. Delete Background Button
                                    Button(
                                        onClick = {
                                            isBackgroundRemoved = !isBackgroundRemoved
                                            aiLogText = if (isBackgroundRemoved) {
                                                "AI background mask generated. Displaying foreground actor on composite grid canvas."
                                            } else {
                                                "Restored full background stream."
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isBackgroundRemoved) Color(0xFF00FFCC) else Color(0xFF1E2026),
                                            contentColor = if (isBackgroundRemoved) Color.Black else Color.White
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).testTag("delete_bg_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isBackgroundRemoved) Icons.Filled.Portrait else Icons.Outlined.Portrait,
                                            contentDescription = "Matte",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isBackgroundRemoved) "BG Removed" else "Remove BG", fontSize = 11.sp)
                                    }

                                    // 3. Auto-Caption Button
                                    Button(
                                        onClick = {
                                            isGeneratingCaptions = true
                                            aiLogText = "Analyzing dialog track to generate speech-to-text..."
                                            coroutineScope.launch {
                                                delay(1500) // Simulated processing latency
                                                autoCaptions.clear()
                                                autoCaptions.addAll(
                                                    listOf(
                                                        Subtitle("1", "Hey guys! Welcome to my vlog! 🌊", 0.0f, 4.5f),
                                                        Subtitle("2", "Let's make a beautiful brew ☕", 10.0f, 13.5f),
                                                        Subtitle("3", "Walking down this deep forest track... 🌲", 18.0f, 22.0f),
                                                        Subtitle("4", "Wow! Look at this massive workspace! 💻", 27.0f, 30.0f)
                                                    )
                                                )
                                                isGeneratingCaptions = false
                                                aiLogText = "Generated 4 perfectly timed subtitle tracks using AI Speech-to-Text!"
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (autoCaptions.isNotEmpty()) Color(0xFF9C27B0) else Color(0xFF1E2026),
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).testTag("caption_button")
                                    ) {
                                        Icon(Icons.Filled.Subtitles, contentDescription = "Captions", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (autoCaptions.isNotEmpty()) "Captioned" else "Auto-Caption", fontSize = 11.sp)
                                    }
                                }

                                // AI logs/response box
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF14151A), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0xFF1E2026), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isAiProcessing || isGeneratingCaptions) {
                                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Color(0xFF00FFCC))
                                            } else {
                                                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF00FFCC)))
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("CO-PILOT CONSOLE LOGS", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = aiLogText,
                                            color = Color.LightGray,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }

                        is ActiveTab.TextOverlays -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("ACTIVE TEXT SLICES", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Button(
                                        onClick = { showAddTextDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2026)),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add Overlay", fontSize = 10.sp)
                                    }
                                }

                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxHeight()
                                ) {
                                    items(textOverlays) { overlay ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF14151A))
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(overlay.color)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(overlay.text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                    Text(
                                                        text = "Active: ${String.format("%.1fs", overlay.startTime)} to ${String.format("%.1fs", overlay.endTime)}",
                                                        color = Color.Gray,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                            }

                                            IconButton(
                                                onClick = { textOverlays.remove(overlay) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        is ActiveTab.Filters -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("CINEMATIC FILTERS", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(filters) { filter ->
                                        val isSelected = selectedFilter == filter
                                        Box(
                                            modifier = Modifier
                                                .width(90.dp)
                                                .height(60.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .border(
                                                    width = if (isSelected) 2.dp else 1.dp,
                                                    color = if (isSelected) Color(0xFF00FFCC) else Color(0xFF2C2D35),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .background(getFilterThumbnailBrush(filter))
                                                .clickable { selectedFilter = filter }
                                                .padding(6.dp),
                                            contentAlignment = Alignment.BottomStart
                                        ) {
                                            Text(
                                                text = filter,
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is ActiveTab.TechCore -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                item {
                                    Text(
                                        text = "AI VIDEO EDITOR PRODUCTION STACK",
                                        color = Color(0xFF00FFCC),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                item {
                                    TechItem(
                                        title = "1. Client-Side Rendering (FFmpeg.wasm / FFmpeg-Kit)",
                                        desc = "For rendering trims, adding audio layers, overlaying text, and applying filters directly in-app: Use FFmpeg-Kit for Android or FFmpeg.wasm in Web applications. It compiles standard command lines like:\n`ffmpeg -i input.mp4 -ss 00:00:05 -to 00:00:25 -vf drawtext=\"text='Hello':y=100\" -c:v h264 output.mp4`"
                                    )
                                }

                                item {
                                    TechItem(
                                        title = "2. Smart Trim (Auto-Cut Silence)",
                                        desc = "Process the audio track of the video to calculate the RMS amplitude per millisecond. Identify valleys below -35dB. The coordinates of these valleys are calculated as time-spans, which are then either sliced out using a fast stream copy in FFmpeg (`-filter_complex '[0:v]trim=start=0:end=5...[0:v]trim=start=8:end=14...'`) or skipped in playback."
                                    )
                                }

                                item {
                                    TechItem(
                                        title = "3. Auto-Captioning (Whisper / Gemini Speech)",
                                        desc = "Extract the audio stream into a lightweight AAC file, send it to OpenAI's Whisper API or Google Speech-to-Text API. The response returns an array of segments containing `start`, `end`, and `text` properties, which are converted to WebVTT/SRT format and layered over the renderer."
                                    )
                                }

                                item {
                                    TechItem(
                                        title = "4. Background Deletion (MediaPipe Segmenter / SAM)",
                                        desc = "Perform segmentation on every video frame. MediaPipe Selfie Segmenter provides real-time client-side segmenting (extracting humans/subjects) directly in-app. For high-precision production editing, run Segment Anything (SAM) or U-2-Net on a GPU server, extracting alpha matte masks for compositing."
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Dynamic Add Text Dialog ---
    if (showAddTextDialog) {
        var enteredText by remember { mutableStateOf("") }
        var startS by remember { mutableStateOf("0") }
        var endS by remember { mutableStateOf("5") }
        var selectedColor by remember { mutableStateOf(Color.White) }

        val colorOptions = listOf(
            Color.White to "White",
            Color(0xFFFFD700) to "Gold",
            Color(0xFFFF5252) to "Red",
            Color(0xFF00FFCC) to "Cyan",
            Color(0xFFFF4081) to "Pink"
        )

        AlertDialog(
            onDismissRequest = { showAddTextDialog = false },
            containerColor = Color(0xFF14151A),
            title = { Text("Add Text Overlay", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = enteredText,
                        onValueChange = { enteredText = it },
                        label = { Text("Enter Overlay Text", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00FFCC),
                            unfocusedBorderColor = Color(0xFF2C2D35),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startS,
                            onValueChange = { startS = it },
                            label = { Text("Start (s)", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endS,
                            onValueChange = { endS = it },
                            label = { Text("End (s)", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text("Text Color", color = Color.Gray, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        colorOptions.forEach { (color, name) ->
                            val isColorSelected = selectedColor == color
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isColorSelected) 2.dp else 0.dp,
                                        color = if (isColorSelected) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = color }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (enteredText.isNotEmpty()) {
                            val startFloat = startS.toFloatOrNull() ?: 0f
                            val endFloat = endS.toFloatOrNull() ?: 5f
                            textOverlays.add(
                                TextOverlay(
                                    id = System.currentTimeMillis().toString(),
                                    text = enteredText,
                                    startTime = startFloat,
                                    endTime = endFloat,
                                    color = selectedColor,
                                    size = 22f,
                                    yOffset = ((-40..40).random()).toFloat()
                                )
                            )
                            showAddTextDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                ) {
                    Text("Add Slice", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTextDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    // --- Dynamic Export Dialog ---
    if (showExportDialog) {
        var exportQuality by remember { mutableStateOf("1080p (60 FPS)") }

        AlertDialog(
            onDismissRequest = { if (!isExporting) showExportDialog = false },
            containerColor = Color(0xFF14151A),
            title = { Text("Export Video Studio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!isExporting) {
                        Text("Select final video rendering profile:", color = Color.LightGray, fontSize = 12.sp)

                        listOf("1080p (60 FPS) - Recommended", "4K Ultra HD (30 FPS)", "720p (Mobile Draft)").forEach { quality ->
                            val isSelected = exportQuality.startsWith(quality.take(4))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF1E2026) else Color.Transparent)
                                    .clickable { exportQuality = quality }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { exportQuality = quality },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00FFCC))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(quality, color = Color.White, fontSize = 13.sp)
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(color = Color(0xFF00FFCC), progress = exportProgress)
                            Text(
                                "Rendering frame buffers... ${ (exportProgress * 100).toInt() }%",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                "Applying cuts, rendering text matrices, baking filters...",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!isExporting) {
                    Button(
                        onClick = {
                            isExporting = true
                            exportProgress = 0f
                            coroutineScope.launch {
                                while (exportProgress < 1f) {
                                    delay(200)
                                    exportProgress += 0.1f
                                }
                                isExporting = false
                                showExportDialog = false
                                Toast.makeText(context, "Export Complete! Saved to Gallery.", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                    ) {
                        Text("Render Project", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!isExporting) {
                    TextButton(onClick = { showExportDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            }
        )
    }
}

// --- Dynamic Canvas Simulated Video Player ---
@Composable
fun SimulatedVideoCanvas(
    currentTime: Float,
    selectedFilter: String,
    isBackgroundRemoved: Boolean
) {
    // Local animation ticks to animate the video simulation
    val transitionTick = rememberInfiniteTransition(label = "video_frames")
    val animatedProgress by transitionTick.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "vlog_subject_move"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
    ) {
        val width = size.width
        val height = size.height

        // 1. Draw Checkerboard background if "Background Removed" is active
        if (isBackgroundRemoved) {
            val sqSize = 30f
            for (x in 0.. (width / sqSize).toInt()) {
                for (y in 0.. (height / sqSize).toInt()) {
                    val col = if ((x + y) % 2 == 0) Color(0xFF202228) else Color(0xFF14151B)
                    drawRect(
                        color = col,
                        topLeft = Offset(x * sqSize, y * sqSize),
                        size = Size(sqSize, sqSize)
                    )
                }
            }
        }

        // 2. Render dynamic cinematic background scene based on current playtime
        if (!isBackgroundRemoved) {
            when {
                currentTime < 5.0f -> {
                    // Sunset Beach Scene
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color(0xFFFF5E62), Color(0xFFFF9966))),
                        topLeft = Offset(0f, 0f),
                        size = size
                    )
                    // Golden Sun
                    drawCircle(
                        color = Color(0xFFFFD700),
                        radius = 80f,
                        center = Offset(width / 2f, height / 2f + 40f + (currentTime * 4f))
                    )
                    // Ocean Waves
                    val wavePath = Path().apply {
                        moveTo(0f, height * 0.7f)
                        for (i in 0..width.toInt() step 20) {
                            val waveY = height * 0.7f + sin(i * 0.05f + animatedProgress * 0.1f) * 15f
                            lineTo(i.toFloat(), waveY)
                        }
                        lineTo(width, height)
                        lineTo(0f, height)
                        close()
                    }
                    drawPath(wavePath, color = Color(0xFF1E3C72).copy(alpha = 0.85f))
                }
                currentTime < 10.0f -> {
                    // City Traffic Scene
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color(0xFF2C3E50), Color(0xFF000000))),
                        topLeft = Offset(0f, 0f),
                        size = size
                    )
                    // Skyscrapers silhouette
                    drawRect(Color(0xFF1A252F), topLeft = Offset(50f, height * 0.3f), size = Size(100f, height * 0.7f))
                    drawRect(Color(0xFF1C2833), topLeft = Offset(180f, height * 0.2f), size = Size(140f, height * 0.8f))
                    drawRect(Color(0xFF212F3D), topLeft = Offset(360f, height * 0.4f), size = Size(120f, height * 0.6f))
                    drawRect(Color(0xFF1A252F), topLeft = Offset(520f, height * 0.25f), size = Size(150f, height * 0.75f))

                    // Dynamic street headlights
                    for (i in 0..3) {
                        val headX = (animatedProgress * 6f + (i * 200f)) % width
                        drawCircle(
                            color = Color(0xFFFFEE55).copy(alpha = 0.8f),
                            radius = 12f,
                            center = Offset(headX, height * 0.85f)
                        )
                        drawCircle(
                            color = Color(0xFFFF3366).copy(alpha = 0.8f),
                            radius = 12f,
                            center = Offset(width - headX, height * 0.9f)
                        )
                    }
                }
                currentTime < 15.0f -> {
                    // Cozy Cafe Mug Scene
                    drawRect(Color(0xFF2D1B10), topLeft = Offset(0f, 0f), size = size)
                    // Wooden table
                    drawRect(Color(0xFF1F1107), topLeft = Offset(0f, height * 0.65f), size = Size(width, height * 0.35f))
                    // Mug Base
                    drawRoundRect(
                        color = Color(0xFFD35400),
                        topLeft = Offset(width / 2f - 60f, height / 2f - 40f),
                        size = Size(120f, 130f),
                        cornerRadius = CornerRadius(16f, 16f)
                    )
                    // Mug Handle
                    drawArc(
                        color = Color(0xFFD35400),
                        startAngle = -90f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(width / 2f + 40f, height / 2f - 10f),
                        size = Size(40f, 60f),
                        style = Stroke(width = 15f)
                    )
                    // Coffee liquid
                    drawOval(
                        color = Color(0xFF42210B),
                        topLeft = Offset(width / 2f - 52f, height / 2f - 50f),
                        size = Size(104f, 24f)
                    )
                    // Steam loops
                    for (i in 0..2) {
                        val steamPath = Path().apply {
                            val startX = width / 2f - 30f + (i * 30f)
                            moveTo(startX, height / 2f - 60f)
                            cubicTo(
                                startX - 15f, height / 2f - 90f,
                                startX + 15f, height / 2f - 120f,
                                startX, height / 2f - 150f - (animatedProgress * 0.5f)
                            )
                        }
                        drawPath(steamPath, color = Color.White.copy(alpha = 0.3f), style = Stroke(width = 4f))
                    }
                }
                currentTime < 20.0f -> {
                    // Forest Cool Scene
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color(0xFF1B4F72), Color(0xFF196F3D))),
                        topLeft = Offset(0f, 0f),
                        size = size
                    )
                    // Pine trees
                    drawTree(width * 0.2f, height * 0.75f, 130f)
                    drawTree(width * 0.5f, height * 0.7f, 160f)
                    drawTree(width * 0.8f, height * 0.85f, 120f)
                }
                currentTime < 25.0f -> {
                    // Mountain Peak Scene
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color(0xFF34495E), Color(0xFF7F8C8D))),
                        topLeft = Offset(0f, 0f),
                        size = size
                    )
                    // Peak Silhouette
                    val mountainPath = Path().apply {
                        moveTo(0f, height)
                        lineTo(width * 0.35f, height * 0.4f)
                        lineTo(width * 0.5f, height * 0.6f)
                        lineTo(width * 0.75f, height * 0.3f)
                        lineTo(width, height)
                        close()
                    }
                    drawPath(mountainPath, color = Color(0xFFBDC3C7))
                    // Snowy caps
                    val capPath1 = Path().apply {
                        moveTo(width * 0.35f, height * 0.4f)
                        lineTo(width * 0.28f, height * 0.5f)
                        lineTo(width * 0.42f, height * 0.52f)
                        close()
                    }
                    drawPath(capPath1, color = Color.White)
                }
                else -> {
                    // Tech Studio Workspace Scene
                    drawRect(Color(0xFF0C0D10), topLeft = Offset(0f, 0f), size = size)
                    // Screen glow lines
                    for (i in 0..4) {
                        val pulseWidth = (animatedProgress * 4f + (i * 150f)) % width
                        drawLine(
                            color = Color(0xFF00FFCC).copy(alpha = 0.25f),
                            start = Offset(pulseWidth, 0f),
                            end = Offset(pulseWidth, height),
                            strokeWidth = 3f
                        )
                    }
                    // Main workspace setup box
                    drawRoundRect(
                        color = Color(0xFF1C1D24),
                        topLeft = Offset(width / 2f - 140f, height / 2f - 60f),
                        size = Size(280f, 140f),
                        cornerRadius = CornerRadius(12f, 12f)
                    )
                    // Screen content grid
                    drawRect(
                        color = Color(0xFF090A0C),
                        topLeft = Offset(width / 2f - 130f, height / 2f - 50f),
                        size = Size(260f, 100f)
                    )
                    // Glowing code columns
                    drawRect(Color(0xFFE91E63).copy(alpha = 0.6f), topLeft = Offset(width / 2f - 110f, height / 2f - 30f), size = Size(40f, 10f))
                    drawRect(Color(0xFF00FFCC).copy(alpha = 0.6f), topLeft = Offset(width / 2f - 60f, height / 2f - 30f), size = Size(90f, 10f))
                    drawRect(Color(0xFFFFD700).copy(alpha = 0.6f), topLeft = Offset(width / 2f - 110f, height / 2f - 10f), size = Size(120f, 10f))
                    drawRect(Color(0xFF00FFCC).copy(alpha = 0.6f), topLeft = Offset(width / 2f - 110f, height / 2f + 10f), size = Size(70f, 10f))
                }
            }
        }

        // 3. Render Foreground subject (always draw this to show background segmentation result!)
        val subjectX = width / 2f + sin(animatedProgress * 0.05f) * 60f
        val subjectY = height * 0.6f

        // Draw animated main character/avatar overlay representing the vlogger
        // Head
        drawCircle(
            color = Color(0xFFFFCC80),
            radius = 32f,
            center = Offset(subjectX, subjectY - 50f)
        )
        // Hair (Vlogger style)
        drawCircle(
            color = Color(0xFF4E342E),
            radius = 18f,
            center = Offset(subjectX, subjectY - 65f)
        )
        // Stylish dark glasses
        drawRoundRect(
            color = Color(0xFF1A1A1A),
            topLeft = Offset(subjectX - 25f, subjectY - 55f),
            size = Size(50f, 12f),
            cornerRadius = CornerRadius(4f, 4f)
        )
        // Smile
        drawArc(
            color = Color(0xFFFF5252),
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(subjectX - 10f, subjectY - 45f),
            size = Size(20f, 15f),
            style = Stroke(width = 3f)
        )
        // Hoodie body
        val bodyPath = Path().apply {
            moveTo(subjectX - 45f, subjectY)
            lineTo(subjectX + 45f, subjectY)
            lineTo(subjectX + 60f, height)
            lineTo(subjectX - 60f, height)
            close()
        }
        drawPath(bodyPath, color = Color(0xFF7E57C2))

        // 4. Draw/Layer final filter screen bounds over the canvas frame
        when (selectedFilter) {
            "Cinema Gold" -> {
                drawRect(
                    color = Color(0xFFFFD700).copy(alpha = 0.18f),
                    topLeft = Offset(0f, 0f),
                    size = size
                )
            }
            "Cyberpunk Neon" -> {
                drawRect(
                    color = Color(0xFFFF007F).copy(alpha = 0.12f),
                    topLeft = Offset(0f, 0f),
                    size = size
                )
                drawRect(
                    color = Color(0xFF00FFFF).copy(alpha = 0.08f),
                    topLeft = Offset(0f, 0f),
                    size = size
                )
            }
            "Classic B&W" -> {
                // Approximate B&W filter overlay via a tinted dark grey blending shade
                drawRect(
                    color = Color(0xFF333333).copy(alpha = 0.35f),
                    topLeft = Offset(0f, 0f),
                    size = size
                )
            }
            "Vintage Retro" -> {
                // Sepia tone
                drawRect(
                    color = Color(0xFF8B5A2B).copy(alpha = 0.22f),
                    topLeft = Offset(0f, 0f),
                    size = size
                )
            }
            "Forest Cool" -> {
                // Jade/greenish tone
                drawRect(
                    color = Color(0xFF00FF88).copy(alpha = 0.12f),
                    topLeft = Offset(0f, 0f),
                    size = size
                )
            }
        }
    }
}

// Draw tree helper for forest scene
fun DrawScope.drawTree(x: Float, y: Float, treeHeight: Float) {
    // Tree Trunk
    drawRect(
        color = Color(0xFF5D4037),
        topLeft = Offset(x - 10f, y - 20f),
        size = Size(20f, 20f)
    )
    // Pine layers (triangles)
    val path = Path().apply {
        moveTo(x, y - treeHeight)
        lineTo(x - 45f, y - 20f)
        lineTo(x + 45f, y - 20f)
        close()
    }
    drawPath(path, color = Color(0xFF1B5E20))
    
    val path2 = Path().apply {
        moveTo(x, y - treeHeight * 0.7f)
        lineTo(x - 35f, y - treeHeight * 0.15f)
        lineTo(x + 35f, y - treeHeight * 0.15f)
        close()
    }
    drawPath(path2, color = Color(0xFF2E7D32))
}

// --- Style Helpers ---
@Composable
fun getFilterThumbnailBrush(filter: String): Brush {
    val colors = when (filter) {
        "Cinema Gold" -> listOf(Color(0xFFFFA700), Color(0xFFFF5100))
        "Cyberpunk Neon" -> listOf(Color(0xFFFF007F), Color(0xFF00FFFF))
        "Classic B&W" -> listOf(Color(0xFF555555), Color(0xFF111111))
        "Vintage Retro" -> listOf(Color(0xFF8B5A2B), Color(0xFF3E2723))
        "Forest Cool" -> listOf(Color(0xFF00FF88), Color(0xFF004D40))
        else -> listOf(Color(0xFF1E2026), Color(0xFF0C0D10))
    }
    return Brush.verticalGradient(colors)
}

fun formatTime(seconds: Float): String {
    val mins = (seconds / 60).toInt()
    val secs = (seconds % 60).toInt()
    val mills = ((seconds - seconds.toInt()) * 10).toInt()
    return String.format("%02d:%02d.%d", mins, secs, mills)
}

@Composable
fun TechItem(title: String, desc: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF14151A))
            .border(1.dp, Color(0xFF1E2026), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = desc,
            color = Color.LightGray,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
