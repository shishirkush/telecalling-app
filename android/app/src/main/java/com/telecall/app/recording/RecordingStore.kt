package com.telecall.app.recording

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One saved voice recording: the agent's own label plus where the audio lives. */
data class Recording(
    val id: String,
    val label: String,
    val createdAt: Long,   // epoch millis
    val durationMs: Long,
    val fileName: String
)

/**
 * Voice recordings kept in the app's PRIVATE storage (filesDir/recordings) —
 * other apps can't read them, and nothing is uploaded. The label/date/length
 * of each one sits in a small index.json next to the audio files.
 *
 * Plain synchronous file I/O: the index is tiny, and every call happens on a
 * user tap, not in a loop.
 */
class RecordingStore(context: Context) {

    private val dir = File(context.filesDir, "recordings").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    /** A fresh, empty target for the recorder. Not in the index until [add]. */
    fun newTarget(): Pair<String, File> {
        val id = UUID.randomUUID().toString()
        return id to File(dir, "$id.m4a")
    }

    fun fileFor(recording: Recording): File = File(dir, recording.fileName)

    /** Newest first. Entries whose audio file has vanished are skipped. */
    @Synchronized
    fun list(): List<Recording> =
        readIndex().filter { File(dir, it.fileName).exists() }.sortedByDescending { it.createdAt }

    @Synchronized
    fun add(recording: Recording) {
        writeIndex(readIndex() + recording)
    }

    @Synchronized
    fun rename(id: String, label: String) {
        writeIndex(readIndex().map { if (it.id == id) it.copy(label = label) else it })
    }

    @Synchronized
    fun delete(id: String) {
        val all = readIndex()
        all.firstOrNull { it.id == id }?.let { File(dir, it.fileName).delete() }
        writeIndex(all.filterNot { it.id == id })
    }

    /** Throws away a half-made recording that never got a label. */
    fun discard(file: File) {
        file.delete()
    }

    private fun readIndex(): List<Recording> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Recording(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    createdAt = o.getLong("createdAt"),
                    durationMs = o.getLong("durationMs"),
                    fileName = o.getString("fileName")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(items: List<Recording>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("label", it.label)
                    .put("createdAt", it.createdAt)
                    .put("durationMs", it.durationMs)
                    .put("fileName", it.fileName)
            )
        }
        // Write-then-rename so a crash mid-write can't leave a half-written index.
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(arr.toString())
            tmp.delete()
        }
    }
}
