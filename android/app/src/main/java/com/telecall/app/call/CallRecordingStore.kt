package com.telecall.app.call

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * One recorded call, waiting on this phone until it has been uploaded.
 *
 * @param fileName the audio file, or null when the microphone captured only
 *                 silence (many phones mute it during a call) — then only the
 *                 metadata is reported, so the admin can see which handsets
 *                 don't record.
 */
data class CallRecording(
    val id: String,
    val leadId: Long,
    val mobile: String,
    val startedAt: Long,   // epoch millis
    val durationMs: Long,
    val fileName: String?,
    val hasAudio: Boolean,
    val uploaded: Boolean = false,
    /** Loudest level the phone reported (0..32767), or -1 when unknown. Low = probably a muted mic. */
    val peak: Int = -1,
    /** Which microphone source recorded it (mic, voice_recognition, ...). */
    val source: String = ""
)

/**
 * Index of call recordings awaiting upload, kept in the app's private
 * storage (filesDir/call_recordings). An entry and its audio file are removed
 * as soon as the upload succeeds — the server holds the copy.
 */
class CallRecordingStore(context: Context) {

    private val dir = File(context.filesDir, "call_recordings").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    fun newTarget(): Pair<String, File> {
        val id = UUID.randomUUID().toString()
        return id to File(dir, "$id.m4a")
    }

    fun fileFor(rec: CallRecording): File? = rec.fileName?.let { File(dir, it) }?.takeIf { it.exists() }

    @Synchronized
    fun pending(): List<CallRecording> = readIndex().filter { !it.uploaded }.sortedBy { it.startedAt }

    @Synchronized
    fun add(rec: CallRecording) {
        writeIndex(readIndex() + rec)
    }

    /** Upload done: forget the entry and free the phone's storage. */
    @Synchronized
    fun remove(id: String) {
        val all = readIndex()
        all.firstOrNull { it.id == id }?.fileName?.let { File(dir, it).delete() }
        writeIndex(all.filterNot { it.id == id })
    }

    /** Bytes of audio still waiting to upload. */
    fun pendingBytes(): Long = dir.listFiles { f -> f.name.endsWith(".m4a") }?.sumOf { it.length() } ?: 0L

    private fun readIndex(): List<CallRecording> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CallRecording(
                    id = o.getString("id"),
                    leadId = o.getLong("leadId"),
                    mobile = o.getString("mobile"),
                    startedAt = o.getLong("startedAt"),
                    durationMs = o.getLong("durationMs"),
                    fileName = if (o.isNull("fileName")) null else o.getString("fileName"),
                    hasAudio = o.getBoolean("hasAudio"),
                    uploaded = o.optBoolean("uploaded", false),
                    peak = o.optInt("peak", -1),
                    source = o.optString("source", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(items: List<CallRecording>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("leadId", it.leadId)
                    .put("mobile", it.mobile)
                    .put("startedAt", it.startedAt)
                    .put("durationMs", it.durationMs)
                    .put("fileName", it.fileName ?: JSONObject.NULL)
                    .put("hasAudio", it.hasAudio)
                    .put("uploaded", it.uploaded)
                    .put("peak", it.peak)
                    .put("source", it.source)
            )
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(arr.toString())
            tmp.delete()
        }
    }
}
