package com.telecall.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Downloads the release APK and hands it to the system installer.
 *
 * Android will not let a sideloaded app silently replace itself — the agent
 * must tap through the system "Install unknown apps" / package-installer
 * screen no matter what this code does. That is an OS gate, not a bug: this
 * gets the download and the install prompt to appear with one tap instead
 * of the agent hunting down a browser and a Downloads folder themselves.
 *
 * Uses the app's own external-files directory (no WRITE_EXTERNAL_STORAGE
 * needed on any supported API level) and DownloadManager rather than a
 * hand-rolled OkHttp download, so a dropped connection retries and the
 * agent sees real progress in the system notification shade.
 */
class UpdateInstaller(private val context: Context) {

    fun downloadAndInstall(info: UpdateInfo) {
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("Telecalling update")
            .setDescription("Downloading v${info.version}")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, FILE_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        // DownloadManager runs in the system process, so this broadcast
        // genuinely comes from outside the app — RECEIVER_EXPORTED, unlike
        // SimManager's own self-sent SMS-result broadcast.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val finishedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (finishedId != downloadId) return
                ctx.unregisterReceiver(this)
                promptInstall()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun promptInstall() {
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FILE_NAME)
        if (!apkFile.exists()) return

        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(installIntent)
        } catch (e: Exception) {
            // No package installer available to handle it — nothing useful
            // to do beyond not crashing; the agent still has a working app.
        }
    }

    companion object {
        private const val FILE_NAME = "telecalling-update.apk"
    }
}
