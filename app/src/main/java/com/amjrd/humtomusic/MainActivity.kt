package com.amjrd.humtomusic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private lateinit var currentViewModel: AudioViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentViewModel = AudioViewModel()

        setContent {
            HumToMusicTheme {
                HumToMusicApp(
                    vm = currentViewModel,
                    onRequestMicrophone = { requestMicrophone() },
                    onOpenSystemSettings = {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    },
                    onExportWav = {
                        currentViewModel.state.file?.takeIf { it.exists() }?.let {
                            saveWav.launch("hum-to-music-melody.wav")
                        }
                    }
                )
            }
        }
    }

    private fun requestMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private val saveWav = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        uri ?: return@registerForActivityResult
        val file = currentViewModel.state.file ?: return@registerForActivityResult
        if (!file.exists()) return@registerForActivityResult
        contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        }
    }
}

@Composable
private fun HumToMusicTheme(content: @Composable () -> Unit) {
    val dark = darkColorScheme(
        background = androidx.compose.ui.graphics.Color(0xFF0B0B0D),
        surface = androidx.compose.ui.graphics.Color(0xFF151518),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF202024),
        primary = androidx.compose.ui.graphics.Color(0xFFEDEDED),
        onPrimary = androidx.compose.ui.graphics.Color(0xFF101012),
        onBackground = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
        onSurface = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFB8B8BE)
    )
    MaterialTheme(colorScheme = dark, content = content)
}

data class UiState(
    val recording: Boolean = false,
    val generatingMelody: Boolean = false,
    val seconds: Int = 0,
    val level: Float = 0f,
    val note: String = "—",
    val hz: Float = 0f,
    val style: String = "Piano",
    val file: File? = null,
    val message: String = "Ready — hum a melody"
)

class AudioViewModel : ViewModel() {
    var state by mutableStateOf(UiState())
        private set

    private var record: AudioRecord? = null
    private var job: Job? = null
    private val recording = AtomicBoolean(false)
    private val rate = 44100
    private val melodyFrames = mutableListOf<Pair<Long, Int>>()

    fun toggle() {
        if (recording.get()) stop() else start()
    }

    private fun start() {
        if (recording.get()) return

        val minBuffer = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val created = try {
            File.createTempFile("hum_", ".wav")
        } catch (_: Exception) {
            return
        }
        melodyFrames.clear()

        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer
            )
            if (record?.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                record = null
                return
            }

            record!!.startRecording()
            recording.set(true)
            state = state.copy(
                recording = true,
                generatingMelody = false,
                seconds = 0,
                file = null,
                message = "Listening… hum your melody"
            )

            val r = record!!
            job = viewModelScope.launch(Dispatchers.IO) {
                var total = 0
                try {
                    FileOutputStream(created).use { out ->
                        writeWavHeader(out, 0)
                        val buffer = ShortArray(minBuffer)

                        while (isActive && recording.get()) {
                            val n = r.read(buffer, 0, buffer.size)
                            if (n > 0) {
                                for (i in 0 until n) {
                                    val value = buffer[i].toInt()
                                    out.write(value and 255)
                                    out.write((value shr 8) and 255)
                                }
                                total += n
                                val level = buffer.take(n)
                                    .maxOfOrNull { abs(it.toInt()) }
                                    ?.div(32768f) ?: 0f
                                val hz = pitch(buffer, n)
                                val midi = hzToMidi(hz)
                                if (midi != null) {
                                    synchronized(melodyFrames) {
                                        melodyFrames.add(System.currentTimeMillis() to midi)
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    state = state.copy(
                                        level = level,
                                        hz = hz,
                                        note = note(hz),
                                        seconds = total / rate
                                    )
                                }
                            }
                        }
                        out.flush()
                    }
                } finally {
                    if (created.exists()) rewriteSize(created, total * 2)
                    withContext(Dispatchers.Main) {
                        state = state.copy(recording = false, level = 0f)
                    }
                }
            }
        } catch (_: Exception) {
            recording.set(false)
            record?.release()
            record = null
            state = state.copy(recording = false, message = "Microphone could not be started")
        }
    }

    fun stop() {
        if (!recording.get()) return
        recording.set(false)
        try { record?.stop() } catch (_: IllegalStateException) { }
        record?.release()
        record = null

        val finishingJob = job
        viewModelScope.launch {
            finishingJob?.join()
            generateMelodyFromHum()
        }
    }

    private suspend fun generateMelodyFromHum() {
        val frames = synchronized(melodyFrames) { melodyFrames.toList() }
        if (frames.size < 3) {
            state = state.copy(message = "لم نلقاوش نوتات كافية. جرّب تدندن بصوت أوضح وأطول.")
            return
        }

        state = state.copy(generatingMelody = true, message = "Creating your melody…")
        withContext(Dispatchers.IO) {
            val notes = compressMelody(frames)
            if (notes.isEmpty()) return@withContext
            val melodyFile = File.createTempFile("melody_", ".wav")
            createMelodyWav(melodyFile, notes, state.style)
            withContext(Dispatchers.Main) {
                state = state.copy(
                    generatingMelody = false,
                    file = melodyFile,
                    message = "Melody ready — created from your hum"
                )
            }
        }
    }

    private fun compressMelody(frames: List<Pair<Long, Int>>): List<Int> {
        val first = frames.firstOrNull()?.first ?: return emptyList()
        val buckets = linkedMapOf<Long, MutableList<Int>>()
        for ((time, midi) in frames) {
            val bucket = (time - first) / 220L
            buckets.getOrPut(bucket) { mutableListOf() }.add(midi)
        }

        val raw = buckets.values.mapNotNull { values ->
            values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        }

        val simplified = mutableListOf<Int>()
        for (n in raw) if (simplified.lastOrNull() != n) simplified.add(n)

        return simplified.take(32)
    }

    private fun createMelodyWav(file: File, notes: List<Int>, style: String) {
        val samplesPerNote = (rate * 0.42).toInt()
        val totalSamples = samplesPerNote * notes.size

        FileOutputStream(file).use { out ->
            writeWavHeader(out, totalSamples * 2)
            for (i in 0 until totalSamples) {
                val noteIndex = (i / samplesPerNote).coerceIn(0, notes.lastIndex)
                val midi = notes[noteIndex]
                val frequency = 440.0 * 2.0.pow((midi - 69) / 12.0)
                val local = i % samplesPerNote
                val t = local.toDouble() / rate

                val attack = (local / (rate * 0.035)).coerceAtMost(1.0)
                val release = ((samplesPerNote - local) / (rate * 0.09)).coerceAtMost(1.0)
                val envelope = minOf(attack, release).coerceAtLeast(0.0)

                val harmonic = when (style) {
                    "Acoustic" -> sin(2.0 * PI * frequency * t) + 0.28 * sin(2.0 * PI * frequency * 2.0 * t)
                    "Cinematic" -> sin(2.0 * PI * frequency * t) + 0.18 * sin(2.0 * PI * frequency * 0.5 * t)
                    "Lo-Fi" -> sin(2.0 * PI * frequency * t) + 0.12 * sin(2.0 * PI * frequency * 2.0 * t)
                    else -> sin(2.0 * PI * frequency * t) +
                        0.22 * sin(2.0 * PI * frequency * 2.0 * t) +
                        0.08 * sin(2.0 * PI * frequency * 3.0 * t)
                }

                val sample = (harmonic * 0.16 * envelope * 32767.0).toInt().coerceIn(-32768, 32767)
                out.write(sample and 255)
                out.write((sample shr 8) and 255)
            }
        }
    }

    fun createTextDemo(lyrics: String) {
        if (state.generatingMelody) return
        if (lyrics.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                state = state.copy(generatingMelody = true, message = "Creating a local demo…")
            }
            val seed = lyrics.length
            val notes = listOf(60, 62, 64, 67, 64, 62, 60, 55).map {
                it + if (seed % 3 == 0) 0 else 0
            }
            val file = File.createTempFile("text_demo_", ".wav")
            createMelodyWav(file, notes, state.style)
            withContext(Dispatchers.Main) {
                state = state.copy(
                    generatingMelody = false,
                    file = file,
                    message = "Demo ready — no API key required"
                )
            }
        }
    }

    fun setStyle(style: String) {
        state = state.copy(style = style)
    }

    override fun onCleared() {
        stop()
        job?.cancel()
        super.onCleared()
    }

    private fun pitch(samples: ShortArray, count: Int): Float {
        if (count < 512) return 0f
        var crossings = 0
        var previous = samples[0]
        for (i in 1 until count) {
            if ((previous < 0 && samples[i] >= 0) || (previous >= 0 && samples[i] < 0)) crossings++
            previous = samples[i]
        }
        val hz = crossings * rate / (2f * count)
        return if (hz in 60f..1200f) hz else 0f
    }

    private fun hzToMidi(hz: Float): Int? {
        if (hz <= 0f) return null
        return (69 + 12 * log2(hz / 440f)).roundToInt().coerceIn(24, 96)
    }

    private fun note(hz: Float): String {
        val midi = hzToMidi(hz) ?: return "—"
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        return "${names[(midi % 12 + 12) % 12]}${midi / 12 - 1}"
    }

    private fun writeWavHeader(out: FileOutputStream, size: Int) {
        val header = ByteArray(44)
        fun intAt(position: Int, value: Int) {
            header[position] = (value and 255).toByte()
            header[position + 1] = (value shr 8 and 255).toByte()
            header[position + 2] = (value shr 16 and 255).toByte()
            header[position + 3] = (value shr 24 and 255).toByte()
        }
        "RIFF".forEachIndexed { i, c -> header[i] = c.code.toByte() }
        intAt(4, size + 36)
        "WAVEfmt ".forEachIndexed { i, c -> header[8 + i] = c.code.toByte() }
        intAt(16, 16)
        header[20] = 1
        header[22] = 1
        intAt(24, rate)
        intAt(28, rate * 2)
        header[32] = 2
        header[34] = 16
        "data".forEachIndexed { i, c -> header[36 + i] = c.code.toByte() }
        intAt(40, size)
        out.write(header)
    }

    private fun rewriteSize(file: File, dataSize: Int) {
        val bytes = file.readBytes()
        fun setInt(position: Int, value: Int) {
            bytes[position] = (value and 255).toByte()
            bytes[position + 1] = (value shr 8 and 255).toByte()
            bytes[position + 2] = (value shr 16 and 255).toByte()
            bytes[position + 3] = (value shr 24 and 255).toByte()
        }
        setInt(4, dataSize + 36)
        setInt(40, dataSize)
        file.writeBytes(bytes)
    }
}

@Composable
fun HumToMusicApp(
    vm: AudioViewModel,
    onRequestMicrophone: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onExportWav: () -> Unit
) {
    var screen by remember { mutableStateOf("home") }

    when (screen) {
        "home" -> HomeScreen(
            vm = vm,
            onCreate = { screen = "create" },
            onHum = { screen = "record" },
            onInstrumental = { screen = "create" },
            onSongs = { screen = "songs" },
            onSettings = { screen = "settings" }
        )
        "create" -> CreateSongScreen(vm, onBack = { screen = "home" })
        "record" -> RecordScreen(vm, onBack = { screen = "home" }, onRequestMicrophone = onRequestMicrophone)
        "songs" -> SongsScreen(vm, onBack = { screen = "home" }, onExportWav)
        "settings" -> SettingsScreen(
            onBack = { screen = "home" },
            onRequestMicrophone = onRequestMicrophone,
            onOpenSystemSettings = onOpenSystemSettings
        )
    }
}

@Composable
private fun HomeScreen(
    vm: AudioViewModel,
    onCreate: () -> Unit,
    onHum: () -> Unit,
    onInstrumental: () -> Unit,
    onSongs: () -> Unit,
    onSettings: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Hum to Music AI", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Turn an idea into music", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("⚙", fontSize = 25.sp, modifier = Modifier.clickable { onSettings() })
        }

        Spacer(Modifier.height(28.dp))
        Text("What do you want to create?", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))

        HomeAction("✍️", "Create Song", "Write lyrics and build a song", onCreate)
        HomeAction("🎙️", "Hum a Melody", "Hum your idea and turn it into music", onHum)
        HomeAction("🎧", "Instrumental", "Start with a simple instrumental", onInstrumental)

        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onSongs, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("🎼  My Songs")
        }

        Spacer(Modifier.weight(1f))
        Text("v1.0.2 • Local melody engine", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun HomeAction(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onClick() },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 30.sp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Text("›", fontSize = 28.sp)
        }
    }
}

@Composable
private fun CreateSongScreen(vm: AudioViewModel, onBack: () -> Unit) {
    var lyrics by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        BackTitle("Create Song", onBack)
        Spacer(Modifier.height(18.dp))
        Text("Lyrics", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = lyrics,
            onValueChange = { lyrics = it },
            modifier = Modifier.fillMaxWidth().height(190.dp),
            placeholder = { Text("اكتب كلمات الأغنية هنا…") },
            shape = RoundedCornerShape(18.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("Style", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(170.dp)
        ) {
            items(listOf("Piano", "Acoustic", "Pop", "Lo-Fi", "Cinematic", "Traditional")) { style ->
                FilterChip(
                    selected = vm.state.style == style,
                    onClick = { vm.setStyle(style) },
                    label = { Text(style) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.createTextDemo(lyrics) },
            enabled = lyrics.isNotBlank() && !vm.state.generatingMelody,
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) {
            Text(if (vm.state.generatingMelody) "Creating…" else "✨ Create Song")
        }
        Spacer(Modifier.height(10.dp))
        Text(vm.state.message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun RecordScreen(vm: AudioViewModel, onBack: () -> Unit, onRequestMicrophone: () -> Unit) {
    val state = vm.state
    val context = androidx.compose.ui.platform.LocalContext.current
    val micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BackTitle("Hum a Melody", onBack)
        Spacer(Modifier.height(26.dp))
        Text(
            if (state.recording) "Listening…" else if (state.generatingMelody) "Creating melody…" else "Hum your idea",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Card(Modifier.fillMaxWidth().height(150.dp), shape = RoundedCornerShape(24.dp)) {
            Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
                Wave(state.level, Modifier.fillMaxWidth().height(100.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (state.note == "—") "—" else "${state.note}  •  ${"%.1f".format(state.hz)} Hz",
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (!micGranted) onRequestMicrophone() else vm.toggle()
            },
            enabled = !state.generatingMelody,
            modifier = Modifier.size(150.dp),
            shape = RoundedCornerShape(75.dp)
        ) {
            Text(if (state.recording) "STOP" else if (!micGranted) "ALLOW MIC" else "RECORD", fontSize = 16.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text("أفضل نتيجة: دندن 5–15 ثواني بصوت واضح", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SongsScreen(vm: AudioViewModel, onBack: () -> Unit, onExport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        BackTitle("My Songs", onBack)
        Spacer(Modifier.height(22.dp))
        if (vm.state.file?.exists() == true) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Latest melody", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(vm.state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                        Text("Export WAV")
                    }
                }
            }
        } else {
            Text("Your generated melodies will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onOpenSystemSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    Column(Modifier.fillMaxSize().padding(22.dp)) {
        BackTitle("Settings", onBack)
        Spacer(Modifier.height(24.dp))
        Text("Permissions", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        SettingRow("🎙️", "Microphone", if (granted) "Allowed" else "Not allowed") {
            onRequestMicrophone()
        }
        Spacer(Modifier.height(10.dp))
        Text("Android controls the actual permission dialog.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onOpenSystemSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Open Android App Settings")
        }
        Spacer(Modifier.height(28.dp))
        Text("About", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("Hum to Music AI – AI Arranger", fontWeight = FontWeight.Medium)
        Text("v1.0.2", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Text("Microphone access is requested through Android's native permission system.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingRow(icon: String, title: String, status: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 24.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Text("›", fontSize = 26.sp)
        }
    }
}

@Composable
private fun BackTitle(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹", fontSize = 34.sp, modifier = Modifier.clickable { onBack() })
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Wave(level: Float, modifier: Modifier) {
    val waveColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val path = Path()
        val mid = size.height / 2
        path.moveTo(0f, mid)
        for (i in 0..100) {
            val x = size.width * i / 100f
            val y = mid + sin(i * .5f) * level * size.height * .4f
            path.lineTo(x, y)
        }
        drawPath(path = path, color = waveColor, style = Stroke(width = 3f))
    }
}
