package com.telecall.app.call

import android.content.Context
import com.telecall.app.TelecallApplication
import com.telecall.app.data.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Uploads finished call recordings (backend/48_call_recordings.sql). Runs on
 * its own process-wide scope so it keeps going after the recording service
 * stops; whatever fails (no connection, signed out) simply stays queued and is
 * retried the next time the app comes to the foreground.
 */
object CallRecordingUploader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()

    fun syncAsync(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (!running.tryLock()) return@launch   // one run at a time
            try {
                val repo = (app as TelecallApplication).repository
                if (!repo.isSignedIn) return@launch
                val store = CallRecordingStore(app)
                for (rec in store.pending()) {
                    val file = store.fileFor(rec)
                    // A file that has gone missing can't be uploaded: report it as "no audio".
                    val toSend = if (rec.hasAudio && file == null) rec.copy(hasAudio = false) else rec
                    if (repo.uploadCallRecording(toSend, file) is Outcome.Ok) store.remove(rec.id) else break
                }
            } finally {
                running.unlock()
            }
        }
    }
}
