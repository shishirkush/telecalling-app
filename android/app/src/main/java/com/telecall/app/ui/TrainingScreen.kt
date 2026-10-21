package com.telecall.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.telecall.app.UiState
import com.telecall.app.data.Outcome
import com.telecall.app.data.TrainingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "T" — Training material. Whatever the admin uploads from the dashboard:
 * PDFs (calling script, card features / benefits) opened in the app, and
 * audio recordings played in the app. Read-only for agents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingScreen(
    state: UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onGetFile: suspend (TrainingItem) -> Outcome<File>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openPdf by remember { mutableStateOf<Pair<TrainingItem, File>?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayerHolder() }

    DisposableEffect(Unit) { onDispose { player.stop() } }

    // Back closes an open PDF first, then leaves the page.
    BackHandler { if (openPdf != null) openPdf = null else onBack() }

    openPdf?.let { (item, file) ->
        PdfViewer(title = item.title, file = file, onClose = { openPdf = null })
        return
    }

    fun open(item: TrainingItem) {
        if (busyId != null) return
        message = null
        if (item.kind == "audio" && playingId == item.id) {
            player.stop()
            playingId = null
            return
        }
        player.stop()
        playingId = null
        busyId = item.id
        scope.launch {
            when (val r = onGetFile(item)) {
                is Outcome.Err -> message = r.message
                is Outcome.Ok -> if (item.kind == "pdf") {
                    openPdf = item to r.value
                } else if (player.play(r.value) { playingId = null }) {
                    playingId = item.id
                } else {
                    message = "Could not play this recording."
                }
            }
            busyId = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Training material") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.trainingLoading && state.trainingItems.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.trainingError != null && state.trainingItems.isEmpty() ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(state.trainingError, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRefresh) { Text("Try again") }
                    }

                state.trainingItems.isEmpty() ->
                    Text(
                        "Nothing here yet. Your admin will add the calling script, card details and example calls.",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                else -> {
                    val pdfs = state.trainingItems.filter { it.kind == "pdf" }
                    val audios = state.trainingItems.filter { it.kind == "audio" }
                    LazyColumn(Modifier.fillMaxSize()) {
                        message?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
                        if (pdfs.isNotEmpty()) {
                            item { SectionHeader("Scripts and guides") }
                            items(pdfs, key = { it.id }) { TrainingRow(it, busyId == it.id, false) { open(it) } }
                        }
                        if (audios.isNotEmpty()) {
                            item { SectionHeader("Call recordings") }
                            items(audios, key = { it.id }) { TrainingRow(it, busyId == it.id, playingId == it.id) { open(it) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun TrainingRow(item: TrainingItem, busy: Boolean, playing: Boolean, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    item.kind == "pdf" -> Icon(Icons.Filled.Description, contentDescription = "PDF")
                    playing -> Icon(Icons.Filled.Pause, contentDescription = "Stop")
                    else -> Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                }
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(
                    (if (item.kind == "pdf") "PDF" else "Audio") + if (item.sizeBytes > 0) "  ·  " + formatSize(item.sizeBytes) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider()
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1_048_576) "%.1f MB".format(bytes / 1_048_576.0) else "%d KB".format((bytes / 1024).coerceAtLeast(1))

/** One MediaPlayer at a time, for the audio items. */
private class MediaPlayerHolder {
    private var player: MediaPlayer? = null

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

// ---------------------------------------------------------------------
// In-app PDF reader (Android's PdfRenderer — no PDF app needed)
// ---------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfViewer(title: String, file: File, onClose: () -> Unit) {
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    // PdfRenderer allows one open page at a time.
    val lock = remember { Mutex() }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        if (renderer == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Could not open this PDF.", color = MaterialTheme.colorScheme.error)
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(renderer.pageCount) { index -> PdfPage(renderer, lock, index, widthPx) }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, lock: Mutex, index: Int, widthPx: Int) {
    val bitmap by produceState<Bitmap?>(initialValue = null, index, widthPx) {
        value = withContext(Dispatchers.IO) {
            try {
                lock.withLock {
                    val page = renderer.openPage(index)
                    try {
                        val h = (widthPx.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    } finally {
                        page.close()
                    }
                }
            } catch (e: Exception) {
                null // renderer closed while scrolling away
            }
        }
    }
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = "Page ${index + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
    } else {
        Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
    HorizontalDivider()
}
