package com.telecall.app.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.telecall.app.CallStateTracker
import java.io.File

/**
 * Records the agent's side of a call: the microphone, exactly as it is — no
 * speakerphone. Started when the agent taps Call (while the app is still on
 * screen, which Android requires before it will let a service use the
 * microphone), stopped when the phone's call state says the call ended.
 *
 * Some handsets mute the microphone for apps during a call (Realme and Lava phones
 * did in 1.18.0), which shows up as silence. The phone's loudness reading is not
 * trustworthy enough to throw audio away on, so EVERY recording is kept and
 * uploaded together with its measured peak level and the microphone source used.
 * When a recording comes back quiet, the next call on that phone tries the next
 * source (standard mic, voice recognition, camcorder, ...); the dashboard shows
 * which source works on which phone.
 *
 * Android REQUIRES a foreground-service notification for background microphone
 * use and does not let an app hide it. It is made as quiet as the system
 * allows — minimum importance (no sound, no status-bar icon, not on the lock
 * screen) — and its wording stays factual.
 */
class CallRecordingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var target: Pair<String, File>? = null
    private var leadId = -1L
    private var mobile = ""
    private var startedAtWall = 0L
    private var startedAtElapsed = 0L
    private var peak = 0
    private var sourceIdx = 0

    private val pollAmplitude = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            peak = maxOf(peak, runCatching { r.maxAmplitude }.getOrDefault(0))
            handler.postDelayed(this, 500)
        }
    }
    private val maxDuration = Runnable { finish() }
    private val noCallGuard = Runnable {
        // Dialling never started (e.g. the dialer was only opened): don't keep the mic open.
        if (!CallStateTracker.sawOffhook()) finish()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
        } else {
            begin(intent)
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent?) {
        showForegroundNotification()
        if (recorder != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        leadId = intent?.getLongExtra(EXTRA_LEAD_ID, -1L) ?: -1L
        mobile = intent?.getStringExtra(EXTRA_MOBILE).orEmpty()
        val store = CallRecordingStore(this)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val firstIdx = prefs.getInt(KEY_SOURCE_IDX, 0).mod(SOURCES.size)
        // Start from the source remembered for this phone; if a source can't even start
        // (some phones lack UNPROCESSED, say) fall through to the next one.
        for (k in SOURCES.indices) {
            val idx = (firstIdx + k) % SOURCES.size
            val t = store.newTarget()
            var r: MediaRecorder? = null
            try {
                @Suppress("DEPRECATION")
                r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
                r.setAudioSource(SOURCES[idx].id)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(22_050)
                r.setAudioEncodingBitRate(32_000)   // speech only: ~0.24 MB a minute
                r.setOutputFile(t.second.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                target = t
                sourceIdx = idx
                startedAtWall = System.currentTimeMillis()
                startedAtElapsed = SystemClock.elapsedRealtime()
                peak = 0
                handler.postDelayed(pollAmplitude, 500)
                handler.postDelayed(maxDuration, MAX_DURATION_MS)
                handler.postDelayed(noCallGuard, NO_CALL_GUARD_MS)
                return
            } catch (e: Exception) {
                runCatching { r?.release() }
                t.second.delete()
            }
        }
        // No source could start: the call itself must never be affected.
        recorder = null
        stopForegroundCompat()
        stopSelf()
    }

    private fun finish() {
        handler.removeCallbacks(pollAmplitude)
        handler.removeCallbacks(maxDuration)
        handler.removeCallbacks(noCallGuard)
        val r = recorder
        val t = target
        recorder = null
        target = null
        if (r != null && t != null) {
            val elapsed = SystemClock.elapsedRealtime() - startedAtElapsed
            peak = maxOf(peak, runCatching { r.maxAmplitude }.getOrDefault(0))
            val stopped = try { r.stop(); true } catch (e: RuntimeException) { false }
            runCatching { r.release() }
            if (stopped && elapsed >= MIN_DURATION_MS && t.second.length() > MIN_FILE_BYTES) {
                // Keep every recording: the loudness reading is only a hint (it can read 0 even
                // when audio was captured), so the file is uploaded either way with that hint.
                CallRecordingStore(this).add(
                    CallRecording(
                        id = t.first,
                        leadId = leadId,
                        mobile = mobile,
                        startedAt = startedAtWall,
                        durationMs = elapsed,
                        fileName = t.second.name,
                        hasAudio = true,
                        peak = peak,
                        source = SOURCES[sourceIdx].name
                    )
                )
                // A quiet recording suggests this phone mutes that source during calls: try
                // the next one on the next call. A loud one means this source works — keep it.
                val next = if (peak < SILENCE_PEAK) (sourceIdx + 1) % SOURCES.size else sourceIdx
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SOURCE_IDX, next).apply()
                CallRecordingUploader.syncAsync(applicationContext)
            } else {
                t.second.delete()
            }
        }
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        // Torn down mid-recording (e.g. the system killed us): keep what we have.
        if (recorder != null) finish()
        super.onDestroy()
    }

    private fun showForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Call recording", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Call recording on")
            .setContentText("Your voice is being recorded during this call.")
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
    }

    companion object {
        private const val PREFS = "app_prefs"
        private const val KEY_SOURCE_IDX = "rec_source_idx"
        private const val MIN_FILE_BYTES = 1024L

        private class MicSource(val id: Int, val name: String)

        /** Tried in this order; see the class comment. */
        private val SOURCES = listOf(
            MicSource(MediaRecorder.AudioSource.MIC, "mic"),
            MicSource(MediaRecorder.AudioSource.VOICE_RECOGNITION, "voice_recognition"),
            MicSource(MediaRecorder.AudioSource.CAMCORDER, "camcorder"),
            MicSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "voice_communication"),
            MicSource(MediaRecorder.AudioSource.UNPROCESSED, "unprocessed")
        )

        private const val ACTION_STOP = "com.telecall.app.STOP_CALL_RECORDING"
        private const val EXTRA_LEAD_ID = "lead_id"
        private const val EXTRA_MOBILE = "mobile"
        private const val CHANNEL_ID = "call_recording_quiet"
        private const val NOTIFICATION_ID = 4710
        private const val MAX_DURATION_MS = 60 * 60 * 1000L
        private const val NO_CALL_GUARD_MS = 3 * 60 * 1000L
        private const val MIN_DURATION_MS = 3_000L
        /** Below this loudest-sample level (of 32767) the whole recording is treated as silence. */
        private const val SILENCE_PEAK = 60
        /** Stop recording new calls when this much audio is already waiting to upload. */
        private const val MAX_PENDING_BYTES = 500L * 1024 * 1024

        /**
         * Starts recording the call about to be placed. Does nothing (and never
         * throws) when the microphone permission is missing or too much audio is
         * still waiting to upload — the call proceeds regardless.
         */
        fun start(context: Context, leadId: Long, mobile: String) {
            val app = context.applicationContext
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            if (CallRecordingStore(app).pendingBytes() > MAX_PENDING_BYTES) return
            runCatching {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, CallRecordingService::class.java)
                        .putExtra(EXTRA_LEAD_ID, leadId)
                        .putExtra(EXTRA_MOBILE, mobile)
                )
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching {
                app.startService(Intent(app, CallRecordingService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
