package com.amjrd.humtomusic

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private lateinit var currentViewModel: AudioViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentViewModel = AudioViewModel()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent {
            HumToMusicApp(
                vm = currentViewModel,
                onRecordClick = {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        currentViewModel.toggle()
                    } else {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onExportWav = {
                    currentViewModel.state.file?.takeIf { it.exists() }?.let { file ->
                        saveWav.launch("hum-to-music.wav")
                    }
                }
            )
        }
    }

    private val saveWav = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        uri ?: return@registerForActivityResult
        val file = currentViewModel.state.file ?: return@registerForActivityResult
        if (!file.exists()) return@registerForActivityResult
        contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        }
    }
}

data class UiState(
    val recording: Boolean = false,
    val seconds: Int = 0,
    val level: Float = 0f,
    val note: String = "—",
    val hz: Float = 0f,
    val style: String = "Acoustic",
    val file: File? = null
)

class AudioViewModel : ViewModel() {
    var state by mutableStateOf(UiState())
        private set

    private var record: AudioRecord? = null
    private var job: Job? = null
    private var output: File? = null
    private val recording = AtomicBoolean(false)
    private val rate = 44100

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

        val created = File.createTempFile("hum_", ".wav")
        output = created

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
            state = state.copy(recording = true, seconds = 0, file = created)

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
            state = state.copy(recording = false)
        }
    }

    fun stop() {
        recording.set(false)
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
        }
        record?.release()
        record = null
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

    private fun note(hz: Float): String {
        if (hz <= 0f) return "—"
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val midi = (69 + 12 * (kotlin.math.log2(hz / 440f))).roundToInt()
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
    vm: AudioViewModel = viewModel(),
    onRecordClick: () -> Unit,
    onExportWav: () -> Unit
) {
    MaterialTheme {
        var tab by remember { mutableIntStateOf(0) }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    listOf("Record", "Analyze", "Arrange", "Export").forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Text(listOf("●", "♫", "♪", "⇩")[index]) },
                            label = { Text(title) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    0 -> RecordScreen(vm, onRecordClick)
                    1 -> AnalyzeScreen(vm)
                    2 -> ArrangeScreen(vm)
                    3 -> ExportScreen(vm, onExportWav)
                }
            }
        }
    }
}

@Composable
fun RecordScreen(vm: AudioViewModel, onRecordClick: () -> Unit) {
    val state = vm.state
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Hum to Music AI", style = MaterialTheme.typography.headlineMedium)
        Text("v1.0.1 • Hum-to-Music & AI Arranger")
        Spacer(Modifier.height(30.dp))
        Wave(state.level, Modifier.fillMaxWidth().height(100.dp))
        Spacer(Modifier.height(30.dp))
        Text(if (state.recording) "Recording… ${state.seconds}s" else "Ready to record")
        Spacer(Modifier.height(18.dp))
        Button(onClick = onRecordClick, modifier = Modifier.size(150.dp)) {
            Text(if (state.recording) "STOP" else "RECORD")
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (state.note == "—") "Sing or hum a melody"
            else "Detected: ${state.note} • ${"%.1f".format(state.hz)} Hz"
        )
    }
}

@Composable
fun Wave(level: Float, modifier: Modifier) {
    Canvas(modifier) {
        val path = Path()
        val mid = size.height / 2
        path.moveTo(0f, mid)
        for (i in 0..100) {
            val x = size.width * i / 100f
            val y = mid + kotlin.math.sin(i * .5f) * level * size.height * .4f
            path.lineTo(x, y)
        }
        drawPath(path = path, color = MaterialTheme.colorScheme.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
    }
}

@Composable
fun AnalyzeScreen(vm: AudioViewModel) {
    val state = vm.state
    Column(Modifier.padding(24.dp)) {
        Text("Analysis", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))
        Card {
            Column(Modifier.padding(20.dp)) {
                Text("Detected note: ${state.note}")
                Text("Frequency: ${"%.1f".format(state.hz)} Hz")
                Text("BPM: Auto analysis ready")
                Text("Key: Pending multi-note analysis")
                Text("Chords: Pending AI analysis")
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("The v1 engine uses lightweight on-device pitch detection. The full AI arranger is prepared for the next engine integration.")
    }
}

@Composable
fun ArrangeScreen(vm: AudioViewModel) {
    val styles = listOf("Acoustic", "Piano", "Pop", "Lo-Fi", "Cinematic", "Traditional")
    Column(Modifier.padding(24.dp)) {
        Text("Arrange", style = MaterialTheme.typography.headlineMedium)
        Text("Choose an arrangement style")
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(styles) { style ->
                Card(onClick = { vm.setStyle(style) }) {
                    Box(
                        Modifier.padding(22.dp).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (vm.state.style == style) "✓ $style" else style)
                    }
                }
            }
        }
    }
}

@Composable
fun ExportScreen(vm: AudioViewModel, onExportWav: () -> Unit) {
    Column(Modifier.padding(24.dp)) {
        Text("Export", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))
        Text("Current style: ${vm.state.style}")
        Spacer(Modifier.height(20.dp))
        Button(onClick = onExportWav, enabled = vm.state.file?.exists() == true) {
            Text("Export WAV")
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = {}, enabled = false) {
            Text("Export MIDI — next engine")
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = {}, enabled = false) {
            Text("Chord Sheet — next engine")
        }
    }
}
