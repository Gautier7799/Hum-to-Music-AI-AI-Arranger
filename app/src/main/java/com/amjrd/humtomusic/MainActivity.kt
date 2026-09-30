package com.amjrd.humtomusic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
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
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        microphoneGranted = granted
    }

    private var microphoneGranted by mutableStateOf(false)
    private lateinit var currentViewModel: AudioViewModel
    private var sharedLyrics by mutableStateOf("")
    private var mediaPlayer: MediaPlayer? = null

    private val openLyricsFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) { null }
        if (!text.isNullOrBlank()) sharedLyrics = text
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentViewModel = AudioViewModel()
        microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        handleIncomingLyrics(intent)

        setContent {
            HumToMusicTheme {
                HumToMusicApp(
                    vm = currentViewModel,
                    importedLyrics = sharedLyrics,
                    onImportLyrics = { openLyricsFile.launch(arrayOf("text/*", "application/json", "application/rtf")) },
                    microphoneGranted = microphoneGranted,
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
                    },
                    onPlayAudio = { playAudio() },
                    onStopAudio = { stopAudio() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingLyrics(intent)
    }

    override fun onResume() {
        super.onResume()
        microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun playAudio() {
        val file = currentViewModel.state.file?.takeIf { it.exists() } ?: return
        stopAudio()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { stopAudio() }
            prepare()
            start()
        }
    }

    private fun stopAudio() {
        try { mediaPlayer?.stop() } catch (_: Exception) { }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onDestroy() {
        stopAudio()
        super.onDestroy()
    }

    private fun handleIncomingLyrics(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) sharedLyrics = text
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
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    val light = lightColorScheme(
        background = androidx.compose.ui.graphics.Color(0xFFF7F7F5),
        surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE8E8E6),
        primary = androidx.compose.ui.graphics.Color(0xFF171719),
        onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
        onBackground = androidx.compose.ui.graphics.Color(0xFF111113),
        onSurface = androidx.compose.ui.graphics.Color(0xFF111113),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF6F6F74),
        outline = androidx.compose.ui.graphics.Color(0xFF9A9A9F)
    )

    val dark = darkColorScheme(
        background = androidx.compose.ui.graphics.Color(0xFF08090B),
        surface = androidx.compose.ui.graphics.Color(0xFF111216),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1C1D22),
        primary = androidx.compose.ui.graphics.Color(0xFFF2F2F2),
        onPrimary = androidx.compose.ui.graphics.Color(0xFF0A0A0C),
        onBackground = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
        onSurface = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFA9A9B0),
        outline = androidx.compose.ui.graphics.Color(0xFF55565D)
    )

    MaterialTheme(
        colorScheme = if (isDark) dark else light,
        content = content
    )
}

data class MelodyNote(val midi: Int, val durationMs: Int)

data class UiState(
    val recording: Boolean = false,
    val generatingMelody: Boolean = false,
    val seconds: Int = 0,
    val level: Float = 0f,
    val note: String = "—",
    val hz: Float = 0f,
    val style: String = "Pop",
    val sound: String = "Piano",
    val key: String = "C",
    val scale: String = "Major",
    val chord: String = "C",
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
    private var lastHumNotes: List<MelodyNote> = emptyList()

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
            lastHumNotes = notes
            val melodyFile = File.createTempFile("melody_", ".wav")
            createMelodyWav(melodyFile, notes, state.style, state.sound, state.key, state.scale, state.chord)
            withContext(Dispatchers.Main) {
                state = state.copy(
                    generatingMelody = false,
                    file = melodyFile,
                    message = "Music ready — your hum + generated arrangement"
                )
            }
        }
    }

    private fun compressMelody(frames: List<Pair<Long, Int>>): List<MelodyNote> {
        if (frames.isEmpty()) return emptyList()

        val first = frames.first().first
        val bucketMs = 120L
        val buckets = linkedMapOf<Long, MutableList<Int>>()

        for ((time, midi) in frames) {
            val bucket = ((time - first) / bucketMs).coerceAtLeast(0L)
            buckets.getOrPut(bucket) { mutableListOf() }.add(midi)
        }

        val raw = buckets.entries.map { (index, values) ->
            index to (values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key)
        }

        // Preserve real melodic movement. Only replace an isolated one-semitone
        // glitch when both surrounding buckets agree on the same note.
        val smoothed = raw.mapIndexed { i, (_, value) ->
            if (value == null) null
            else if (i > 0 && i < raw.lastIndex) {
                val previous = raw[i - 1].second
                val next = raw[i + 1].second
                if (previous != null && previous == next && abs(value - previous) <= 1) previous else value
            } else value
        }

        val result = mutableListOf<MelodyNote>()
        var current: Int? = null
        var duration = 0

        for (midi in smoothed) {
            if (midi == null) continue
            if (current == null) {
                current = midi
                duration = bucketMs.toInt()
            } else if (midi == current) {
                duration += bucketMs.toInt()
            } else {
                result.add(MelodyNote(current, duration.coerceIn(120, 1200)))
                current = midi
                duration = bucketMs.toInt()
            }
        }
        current?.let { result.add(MelodyNote(it, duration.coerceIn(120, 1200))) }

        val cleaned = mutableListOf<MelodyNote>()
        for (note in result) {
            if (note.durationMs < 180 && cleaned.isNotEmpty()) {
                val previous = cleaned.removeAt(cleaned.lastIndex)
                cleaned.add(previous.copy(durationMs = previous.durationMs + note.durationMs))
            } else {
                cleaned.add(note)
            }
        }

        return cleaned.take(48)
    }

    private fun createMelodyWav(
        file: File,
        notes: List<MelodyNote>,
        style: String,
        sound: String = "Piano",
        key: String = "C",
        scale: String = "Major",
        chord: String = "C"
    ) {
        if (notes.isEmpty()) return

        // v1.7: render the hummed melody as the lead of an evolving song arrangement.
        // The hum supplies the idea; accompaniment is generated independently on-device.
        val samplesPerNote = notes.map {
            (rate * it.durationMs / 1000.0).roundToInt().coerceAtLeast(1)
        }
        val totalSamples = samplesPerNote.sum()
        val keySemitones = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)
        val root = keySemitones[key] ?: 0
        val minor = scale.equals("Minor", ignoreCase = true)
        val chordRoot = chordRootSemitones(chord, root)
        val progression = progressionForStyle(style, chordRoot, minor)
        val beatsPerSecond = 2.0
        val bars = maxOf(1, kotlin.math.ceil(totalSamples.toDouble() / (rate * 2.0)).toInt())
        val phraseBars = maxOf(4, minOf(8, bars / 4))

        FileOutputStream(file).use { out ->
            writeWavHeader(out, totalSamples * 2)

            var noteIndex = 0
            var noteStart = 0

            for (i in 0 until totalSamples) {
                while (noteIndex < notes.lastIndex && i >= noteStart + samplesPerNote[noteIndex]) {
                    noteStart += samplesPerNote[noteIndex]
                    noteIndex++
                }

                val note = notes[noteIndex]
                val local = i - noteStart
                val noteSamples = samplesPerNote[noteIndex]
                val t = i.toDouble() / rate.toDouble()
                val noteT = local.toDouble() / rate.toDouble()
                val beat = t * 2.0
                val bar = beat / 4.0
                val section = arrangementSection(bar.toInt(), bars)
                val sectionLevel = sectionLevel(section, phraseBars)
                val chordShift = when (section) {
                    "Intro" -> 0
                    "Verse" -> 0
                    "Chorus" -> 1
                    "Bridge" -> 2
                    else -> 0
                }

                val attack = (local / (rate * 0.035)).coerceAtMost(1.0)
                val release = ((noteSamples - local) / (rate * 0.10)).coerceAtMost(1.0)
                val leadEnvelope = minOf(attack, release).coerceAtLeast(0.0)

                val melodyFrequency = midiFrequency(note.midi)
                val lead = leadWave(melodyFrequency, noteT, sound)
                val harmony = harmonyWave(melodyFrequency, noteT, style)

                val chordIndex = (bar.toInt() + chordShift) % progression.size
                val chordNotes = progression[chordIndex]
                val chordSound = chordPad(chordNotes, t, style) * sectionLevel

                val bassFrequency = midiFrequency(chordNotes.first() - 12)
                val bass = bassWave(bassFrequency, t, style) * sectionLevel

                val drums = drumWave(t, style) * drumLevel(section, style)
                val arpeggio = arpeggioWave(chordNotes, t, style) * sectionLevel
                val counter = counterMelodyWave(chordNotes, note.midi, t, noteT, section, style)

                // v1.7: the hum remains the lead idea, while the arrangement evolves
                // through song sections instead of repeating one static loop.
                val mix = (
                    lead * leadEnvelope * 0.30 +
                    harmony * leadEnvelope * 0.06 +
                    chordSound * 0.25 +
                    bass * 0.18 +
                    drums * 0.10 +
                    arpeggio * 0.06 +
                    counter * 0.05
                )

                val sample = (mix * 32767.0)
                    .toInt()
                    .coerceIn(-32768, 32767)

                out.write(sample and 255)
                out.write((sample shr 8) and 255)
            }
        }
    }

    private fun arrangementSection(bar: Int, totalBars: Int): String {
        if (totalBars <= 4) return "Verse"
        val progress = bar.toDouble() / totalBars.toDouble()
        return when {
            progress < 0.12 -> "Intro"
            progress < 0.42 -> "Verse"
            progress < 0.68 -> "Chorus"
            progress < 0.84 -> "Bridge"
            else -> "Outro"
        }
    }

    private fun sectionLevel(section: String, phraseBars: Int): Double {
        val phrasePosition = (phraseBars % 4) / 4.0
        return when (section) {
            "Intro" -> 0.50 + phrasePosition * 0.10
            "Verse" -> 0.76 + phrasePosition * 0.08
            "Chorus" -> 0.94 + phrasePosition * 0.06
            "Bridge" -> 0.66 + phrasePosition * 0.08
            "Outro" -> 0.58
            else -> 0.80
        }
    }

    private fun drumLevel(section: String, style: String): Double = when (section) {
        "Intro" -> if (style == "Ballad" || style == "Classical" || style == "Ambient") 0.18 else 0.38
        "Verse" -> 0.72
        "Chorus" -> 1.0
        "Bridge" -> 0.48
        "Outro" -> 0.34
        else -> 0.72
    }

    private fun counterMelodyWave(
        chordNotes: List<Int>,
        leadMidi: Int,
        t: Double,
        noteT: Double,
        section: String,
        style: String
    ): Double {
        if (chordNotes.isEmpty() || (section == "Intro" && noteT < 0.12)) return 0.0

        val stepLength = when (style) {
            "EDM", "Hip-Hop", "Rock", "Reggae", "Latin" -> 0.5
            else -> 0.75
        }
        val step = floor(t / stepLength).toInt()
        val chordTone = chordNotes[(step + if (section == "Bridge") 1 else 0) % chordNotes.size]
        val direction = if (section == "Chorus") 12 else 7
        val target = chordTone + direction
        val adjusted = if (abs(target - leadMidi) <= 2) target + 3 else target

        val local = t - step * stepLength
        val attack = (local / 0.035).coerceAtMost(1.0)
        val release = ((stepLength - local) / 0.12).coerceAtMost(1.0)
        val envelope = minOf(attack, release).coerceAtLeast(0.0)

        val frequency = midiFrequency(adjusted)
        val tone = sin(2.0 * PI * frequency * local) +
            0.12 * sin(2.0 * PI * frequency * 2.0 * local)

        val level = when (section) {
            "Chorus" -> 0.72
            "Bridge" -> 0.55
            "Outro" -> 0.38
            else -> 0.46
        }

        return tone * envelope * level
    }

    private fun chordRootSemitones(chord: String, keyRoot: Int): Int {
        val names = mapOf(
            "C" to 0, "Dm" to 2, "D" to 2, "Em" to 4, "E" to 4,
            "F" to 5, "G" to 7, "Am" to 9, "A" to 9, "B" to 11
        )
        return names[chord.removeSuffix("m")]?.let { (keyRoot + it) % 12 } ?: keyRoot
    }

    private fun progressionForStyle(style: String, root: Int, minor: Boolean): List<List<Int>> {
        val major = listOf(0, 4, 7)
        val minorTriad = listOf(0, 3, 7)
        val pop = if (minor) listOf(0, 8, 5, 10) else listOf(0, 7, 9, 5)
        val rock = if (minor) listOf(0, 5, 8, 10) else listOf(0, 5, 7, 0)
        val ballad = if (minor) listOf(0, 8, 5, 10) else listOf(0, 5, 7, 0)
        val jazz = if (minor) listOf(0, 5, 10, 3) else listOf(0, 5, 7, 9)
        val cinematic = if (minor) listOf(0, 8, 5, 3) else listOf(0, 5, 7, 4)

        val offsets = when (style) {
            "Rock" -> rock
            "Ballad" -> ballad
            "R&B", "Jazz", "Blues" -> jazz
            "Classical", "Cinematic", "Ambient" -> cinematic
            "EDM", "Hip-Hop", "Lo-Fi", "Reggae", "Latin" -> pop
            else -> pop
        }

        return offsets.mapIndexed { index, offset ->
            val chordRootMidi = 48 + ((root + offset) % 12)
            val useMinor = minor || (index == 1 && style in listOf("Pop", "R&B", "Ballad"))
            val intervals = if (useMinor) minorTriad else major
            intervals.map { chordRootMidi + it }
        }
    }

    private fun midiFrequency(midi: Int): Double =
        440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun leadWave(frequency: Double, t: Double, sound: String): Double {
        return when (sound) {
            "Acoustic Guitar" ->
                sin(2.0 * PI * frequency * t) + 0.18 * sin(2.0 * PI * frequency * 2.0 * t)
            "Electric Guitar" ->
                sin(2.0 * PI * frequency * t) + 0.30 * sin(2.0 * PI * frequency * 2.0 * t)
            "Strings" ->
                sin(2.0 * PI * frequency * t) + 0.22 * sin(2.0 * PI * frequency * 2.0 * t)
            "Synth" ->
                sin(2.0 * PI * frequency * t) + 0.35 * sin(2.0 * PI * frequency * 2.0 * t)
            "Orchestra" ->
                sin(2.0 * PI * frequency * t) +
                    0.18 * sin(2.0 * PI * frequency * 2.0 * t) +
                    0.08 * sin(2.0 * PI * frequency * 3.0 * t)
            else ->
                sin(2.0 * PI * frequency * t) + 0.22 * sin(2.0 * PI * frequency * 2.0 * t)
        } * 0.65
    }

    private fun chordPad(chordNotes: List<Int>, t: Double, style: String): Double {
        val brightness = when (style) {
            "EDM", "Synth" -> 0.24
            "Rock", "Hip-Hop" -> 0.18
            "Classical", "Cinematic", "Ambient" -> 0.14
            else -> 0.16
        }
        val beatPhase = (t % 0.5) / 0.5
        val pulse = when (style) {
            "EDM", "Hip-Hop", "Reggae", "Latin" -> if (beatPhase < 0.82) 1.0 else 0.35
            "Rock" -> if (beatPhase < 0.92) 1.0 else 0.45
            else -> 0.85 + 0.15 * kotlin.math.cos(2.0 * PI * beatPhase)
        }
        return chordNotes.sumOf { midi ->
            sin(2.0 * PI * midiFrequency(midi) * t)
        } / chordNotes.size * brightness * pulse
    }

    private fun arpeggioWave(chordNotes: List<Int>, t: Double, style: String): Double {
        if (chordNotes.isEmpty()) return 0.0

        val stepLength = when (style) {
            "Ballad", "Classical", "Cinematic", "Ambient" -> 0.5
            "EDM", "Hip-Hop", "Rock", "Reggae", "Latin" -> 0.25
            else -> 0.375
        }
        val step = floor(t / stepLength).toInt()
        val note = chordNotes[step % chordNotes.size]
        val local = t - step * stepLength
        val attack = (local / 0.025).coerceAtMost(1.0)
        val release = ((stepLength - local) / 0.08).coerceAtMost(1.0)
        val envelope = minOf(attack, release).coerceAtLeast(0.0)

        val frequency = midiFrequency(note + 12)
        val tone = sin(2.0 * PI * frequency * local) +
            0.18 * sin(2.0 * PI * frequency * 2.0 * local)
        return tone * envelope * 0.62
    }

    private fun bassWave(frequency: Double, t: Double, style: String): Double {
        val harmonic = when (style) {
            "Rock", "Hip-Hop", "EDM" -> 0.22
            else -> 0.14
        }
        val beat = t % 0.5
        val gate = when (style) {
            "Ballad", "Classical", "Ambient", "Cinematic" -> 0.72
            "EDM", "Hip-Hop", "Rock", "Reggae", "Latin" -> if (beat < 0.38) 1.0 else 0.30
            else -> if (beat < 0.44) 1.0 else 0.45
        }
        return (
            sin(2.0 * PI * frequency * t) +
                harmonic * sin(2.0 * PI * frequency * 2.0 * t)
            ) * 0.50 * gate
    }

    private fun harmonyWave(frequency: Double, t: Double, style: String): Double {
        val interval = when (style) {
            "Blues", "Jazz", "R&B" -> 3
            "Classical", "Cinematic", "Ambient" -> 7
            else -> 4
        }
        val harmonyFrequency = frequency * 2.0.pow(interval / 12.0)
        val shimmer = if (style == "EDM" || style == "Synth") 0.16 else 0.10
        return (
            sin(2.0 * PI * harmonyFrequency * t) +
                shimmer * sin(2.0 * PI * harmonyFrequency * 2.0 * t)
            ) * 0.45
    }

    private fun drumWave(t: Double, style: String): Double {
        val beatLength = 0.5
        val step = (t / beatLength).toInt()
        val phase = t - step * beatLength
        val decay = (1.0 - phase / 0.18).coerceIn(0.0, 1.0)

        val kick = if (step % 4 == 0 || (style == "EDM" && step % 4 == 2)) {
            sin(2.0 * PI * (70.0 - 28.0 * phase / 0.18).coerceAtLeast(35.0) * phase) * decay
        } else 0.0

        val snare = if (step % 4 == 2) {
            (
                sin(2.0 * PI * 180.0 * phase) +
                    0.35 * sin(2.0 * PI * 330.0 * phase)
            ) * decay * 0.55
        } else 0.0

        val hat = if (style == "Ballad" || style == "Classical") {
            0.0
        } else {
            val hatPhase = t - floor(t * 4.0) / 4.0
            (sin(2.0 * PI * 3100.0 * hatPhase) *
                (1.0 - hatPhase / 0.055).coerceAtLeast(0.0)) * 0.12
        }

        return kick * 0.55 + snare * 0.35 + hat
    }

    fun createSound(lyrics: String) {
        if (lastHumNotes.isNotEmpty()) {
            regenerateHumArrangement()
        } else {
            createTextDemo(lyrics)
        }
    }

    private fun regenerateHumArrangement() {
        val notes = lastHumNotes
        if (notes.isEmpty()) return
        state = state.copy(generatingMelody = true, message = "Creating your arrangement…")
        viewModelScope.launch(Dispatchers.IO) {
            val melodyFile = File.createTempFile("melody_", ".wav")
            createMelodyWav(
                melodyFile,
                notes,
                state.style,
                state.sound,
                state.key,
                state.scale,
                state.chord
            )
            withContext(Dispatchers.Main) {
                state = state.copy(
                    generatingMelody = false,
                    file = melodyFile,
                    message = "Music ready — arrangement built from your hum"
                )
            }
        }
    }

    fun createTextDemo(lyrics: String) {
        if (state.generatingMelody) return

        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                state = state.copy(generatingMelody = true, message = "Creating a local demo…")
            }
            val noteValues = if (lyrics.isBlank()) {
                listOf(60, 64, 67, 72, 67, 64, 60, 55)
            } else {
                listOf(60, 62, 64, 67, 64, 62, 60, 55)
            }
            val notes = noteValues.map { MelodyNote(it, 420) }
            val file = File.createTempFile("text_demo_", ".wav")
            createMelodyWav(file, notes, state.style, state.sound, state.key, state.scale, state.chord)
            withContext(Dispatchers.Main) {
                state = state.copy(
                    generatingMelody = false,
                    file = file,
                    message = if (lyrics.isBlank()) "Instrumental demo ready — no API key required" else "Demo ready — no API key required"
                )
            }
        }
    }

    fun setStyle(style: String) {
        state = state.copy(style = style)
    }

    fun setSound(sound: String) { state = state.copy(sound = sound) }
    fun setKey(key: String) { state = state.copy(key = key) }
    fun setScale(scale: String) { state = state.copy(scale = scale) }
    fun setChord(chord: String) { state = state.copy(chord = chord) }
     private fun pitch(samples: ShortArray, count: Int): Float {
        if (count < 512) return 0f

        var energy = 0.0
        for (i in 0 until count) {
            val value = samples[i].toDouble()
            energy += value * value
        }
        val rms = kotlin.math.sqrt(energy / count) / 32768.0
        if (rms < 0.015) return 0f

        // Use the strongest *early* autocorrelation peak instead of simply taking
        // the global maximum. Global maxima often lock onto a harmonic/subharmonic
        // and can make different hums collapse to the same note.
        val window = minOf(count, 2048)
        val minLag = (rate / 1000f).roundToInt().coerceAtLeast(1)
        val maxLag = (rate / 70f).roundToInt().coerceAtMost(window - 2)

        val correlations = DoubleArray(maxLag + 1)
        var bestCorrelation = 0.0

        for (lag in minLag..maxLag) {
            var sum = 0.0
            var energyA = 0.0
            var energyB = 0.0
            val limit = window - lag
            for (i in 0 until limit) {
                val a = samples[i].toDouble()
                val b = samples[i + lag].toDouble()
                sum += a * b
                energyA += a * a
                energyB += b * b
            }
            val denominator = kotlin.math.sqrt(energyA * energyB)
            if (denominator > 0.0) {
                val correlation = sum / denominator
                correlations[lag] = correlation
                if (correlation > bestCorrelation) bestCorrelation = correlation
            }
        }

        if (bestCorrelation >= 0.45) {
            val threshold = maxOf(0.45, bestCorrelation * 0.82)
            var selectedLag = -1

            for (lag in (minLag + 1) until maxLag) {
                val value = correlations[lag]
                if (value >= threshold &&
                    value >= correlations[lag - 1] &&
                    value >= correlations[lag + 1]
                ) {
                    selectedLag = lag
                    break
                }
            }

            if (selectedLag < 0) {
                selectedLag = (minLag..maxLag).maxByOrNull { correlations[it] } ?: -1
            }

            if (selectedLag > 0) {
                val hz = rate.toFloat() / selectedLag
                if (hz in 70f..1000f) return hz
            }
        }

        return 0f
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
    importedLyrics: String,
    microphoneGranted: Boolean,
    onImportLyrics: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onExportWav: () -> Unit,
    onPlayAudio: () -> Unit,
    onStopAudio: () -> Unit
) {
    var screen by remember { mutableStateOf(if (importedLyrics.isNotBlank()) "create" else "home") }

    LaunchedEffect(importedLyrics) {
        if (importedLyrics.isNotBlank()) screen = "create"
    }

    when (screen) {
        "home" -> HomeScreen(
            vm = vm,
            onCreate = { screen = "create" },
            onHum = { screen = "record" },
            onInstrumental = { screen = "create" },
            onSongs = { screen = "songs" },
            onSettings = { screen = "settings" }
        )
        "create" -> CreateSongScreen(vm, importedLyrics, onImportLyrics, onBack = { screen = "home" }, onPlayAudio = onPlayAudio, onStopAudio = onStopAudio)
        "record" -> RecordScreen(vm, microphoneGranted, onBack = { screen = "home" }, onRequestMicrophone = onRequestMicrophone, onCreateMusicAi = { screen = "create" })
        "songs" -> SongsScreen(vm, onBack = { screen = "home" }, onExportWav, onPlayAudio, onStopAudio)
        "settings" -> SettingsScreen(
            microphoneGranted = microphoneGranted,
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
        Text("v1.7.0 • Hum-to-Music arranger", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
private fun CreateSongScreen(
    vm: AudioViewModel,
    importedLyrics: String,
    onImportLyrics: () -> Unit,
    onBack: () -> Unit,
    onPlayAudio: () -> Unit,
    onStopAudio: () -> Unit
) {
    var lyrics by remember { mutableStateOf(importedLyrics) }
    LaunchedEffect(importedLyrics) {
        if (importedLyrics.isNotBlank() && importedLyrics != lyrics) lyrics = importedLyrics
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val scrollState = rememberScrollState()
    val genres = listOf("Pop", "Rock", "Ballad", "R&B", "Hip-Hop", "EDM", "Jazz", "Blues", "Classical", "Ambient", "Lo-Fi", "Cinematic", "Folk", "Country", "Reggae", "Latin", "Traditional")
    val sounds = listOf("Piano", "Acoustic Guitar", "Electric Guitar", "Bass", "Strings", "Synth", "Drums", "Orchestra")
    val keys = listOf("C", "D", "E", "F", "G", "A", "B")
    val scales = listOf("Major", "Minor")
    val chords = listOf("C", "G", "Am", "F", "Dm", "Em", "E", "A")

    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        BackTitle("Create Song", onBack)
        Spacer(Modifier.height(4.dp))
        Text(
            "Create your music",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        Text("1  •  Lyrics", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Box {
            OutlinedTextField(
                value = lyrics,
                onValueChange = { lyrics = it },
                modifier = Modifier.fillMaxWidth().height(190.dp),
                placeholder = { Text("اكتب كلمات الأغنية هنا…") },
                shape = RoundedCornerShape(18.dp)
            )
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!text.isNullOrBlank()) lyrics = text
                    },
                    label = { Text("📋 Paste") }
                )
                AssistChip(onClick = onImportLyrics, label = { Text("📂 Import") })
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Google Keep: Share → Hum to Music AI  •  أو Import من ملفات الهاتف",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(22.dp))
        Text("2  •  Music style", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Genre", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(210.dp)
        ) {
            items(genres.size) { index ->
                val genre = genres[index]
                FilterChip(
                    selected = vm.state.style == genre,
                    onClick = { vm.setStyle(genre) },
                    label = { Text(genre) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("Sound / Instrument", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lazyRowItems(sounds) { sound ->
                FilterChip(
                    selected = vm.state.sound == sound,
                    onClick = { vm.setSound(sound) },
                    label = { Text(sound) }
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("3  •  Musical settings", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Key", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(5.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    lazyRowItems(keys) { key ->
                        FilterChip(
                            selected = vm.state.key == key,
                            onClick = { vm.setKey(key) },
                            label = { Text(key) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Scale", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    scales.forEach { scale ->
                        FilterChip(
                            selected = vm.state.scale == scale,
                            onClick = { vm.setScale(scale) },
                            label = { Text(scale) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Chord", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(5.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    lazyRowItems(chords) { chord ->
                        FilterChip(
                            selected = vm.state.chord == chord,
                            onClick = { vm.setChord(chord) },
                            label = { Text(chord) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("4  •  Generate", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("🤖 AI Music Generation", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(5.dp))
                Text(
                    "Demo mode الآن — لا يحتاج API key. لاحقًا نفس الزر يشتغل مع AI الحقيقي.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.createSound(lyrics) },
            enabled = !vm.state.generatingMelody,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(
                if (vm.state.generatingMelody) "Creating…" else "✨  CREATE SOUND",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (vm.state.file?.exists() == true && !vm.state.generatingMelody) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPlayAudio, modifier = Modifier.weight(1f)) {
                    Text("▶  Play")
                }
                OutlinedButton(onClick = onStopAudio, modifier = Modifier.weight(1f)) {
                    Text("■  Stop")
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            vm.state.message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RecordScreen(vm: AudioViewModel, microphoneGranted: Boolean, onBack: () -> Unit, onRequestMicrophone: () -> Unit, onCreateMusicAi: () -> Unit) {
    val state = vm.state
    val micGranted = microphoneGranted

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
                if (!micGranted) {
                    onRequestMicrophone()
                } else {
                    vm.toggle()
                }
            },
            enabled = !state.generatingMelody,
            modifier = Modifier.size(150.dp),
            shape = RoundedCornerShape(75.dp)
        ) {
            Text(if (state.recording) "STOP" else if (!micGranted) "ALLOW MIC" else "RECORD", fontSize = 16.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text("أفضل نتيجة: دندن 5–15 ثواني بصوت واضح", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.file?.exists() == true && !state.recording && !state.generatingMelody) {
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onCreateMusicAi,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("🎵  Create Music AI")
            }
        }
    }
}

@Composable
private fun SongsScreen(vm: AudioViewModel, onBack: () -> Unit, onExport: () -> Unit, onPlayAudio: () -> Unit, onStopAudio: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        BackTitle("My Songs", onBack)
        Spacer(Modifier.height(22.dp))
        if (vm.state.file?.exists() == true) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Latest melody", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(vm.state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPlayAudio, modifier = Modifier.weight(1f)) { Text("▶ Play") }
                        OutlinedButton(onClick = onStopAudio, modifier = Modifier.weight(1f)) { Text("■ Stop") }
                    }
                    Spacer(Modifier.height(8.dp))
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
    microphoneGranted: Boolean,
    onBack: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onOpenSystemSettings: () -> Unit
) {

    Column(Modifier.fillMaxSize().padding(22.dp)) {
        BackTitle("Settings", onBack)
        Spacer(Modifier.height(24.dp))
        Text("Permissions", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        SettingRow("🎙️", "Microphone", if (microphoneGranted) "Allowed" else "Not allowed") {
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
        Text("v1.7.0", color = MaterialTheme.colorScheme.onSurfaceVariant)
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