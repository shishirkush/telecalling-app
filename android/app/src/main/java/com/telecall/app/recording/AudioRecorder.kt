package com.telecall.app.recording

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * Records the microphone to an AAC/.m4a file (mono, 64 kbps — roughly
 * 0.5 MB a minute, clear for speech).
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /** Starts recording into [file]. Returns an error message, or null on success. */
    fun start(file: File): String? {
        if (recorder != null) return "Already recording."
        return try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            target = file
            startedAt = SystemClock.elapsedRealtime()
            null
        } catch (e: Exception) {
            file.delete()
            "Could not start recording — the microphone may be in use (for example during a call)."
        }
    }

    /**
     * Stops and finalises the file. Returns the length in ms, or null when
     * nothing usable was captured (too short) — the file is deleted then.
     */
    fun stop(): Long? {
        val r = recorder ?: return null
        val file = target
        recorder = null
        target = null
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false // stop() throws when almost nothing was recorded
        } finally {
            runCatching { r.release() }
        }
        if (!ok || elapsed < MIN_MS) {
            file?.delete()
            return null
        }
        return elapsed
    }

    /** Abandons an in-progress recording (screen closed, app torn down). */
    fun cancel() {
        val r = recorder ?: return
        val file = target
        recorder = null
        target = null
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
    }

    companion object {
        private const val MIN_MS = 700L
    }
}

/** Plays one recording at a time. */
class AudioPlayer {

    private var player: MediaPlayer? = null

    /** Starts playing [file]; [onDone] fires when it ends or fails. Returns false if it could not start. */
    fun play(file: File, onDone: () -> Unit): Boolean {
        stop()
        return try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { stop(); onDone() }
            p.setOnErrorListener { _, _, _ -> stop(); onDone(); true }
            p.prepare()
            p.start()
            player = p
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        runCatching { p.release() }
    }
}
