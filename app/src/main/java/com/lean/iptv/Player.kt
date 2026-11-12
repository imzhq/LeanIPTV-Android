package com.lean.iptv

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

// Lets the activity pause the video and float it when you press Home
object PlayerHolder {
    @Volatile
    var player: ExoPlayer? = null
}

private val liveSteps = listOf(200, 500, 800, 1000, 1500, 2000, 3000, 5000)   // milliseconds
private val movieSteps = listOf(10, 20, 30, 45, 60)                           // seconds

@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, live: Boolean, liveMs: Int, movieSec: Int): ExoPlayer {
    val loadControl = if (live) {
        // Small buffer = small delay. Raise it if the picture stutters.
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(liveMs, liveMs * 3, liveMs, liveMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    } else {
        // Big buffer = smooth playback on a slow connection.
        // Starts after 5 s are ready, and after a stall waits until 10 s are ready.
        val bufMs = movieSec * 1000
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(bufMs, bufMs + 10_000, 5_000, 10_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    val http = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15000)
        .setReadTimeoutMs(15000)

    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(http))
        .setLoadControl(loadControl)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    title: String,
    url: String,
    live: Boolean,
    inPip: Boolean,
    onEnterPip: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lean", Context.MODE_PRIVATE) }

    var liveMs by remember { mutableIntStateOf(prefs.getInt("live_buf_ms", 1000)) }
    var movieSec by remember { mutableIntStateOf(prefs.getInt("movie_buf_s", 20)) }
    var restart by remember { mutableIntStateOf(0) }
    var resumeMs by remember { mutableLongStateOf(0L) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showLiveControls by remember { mutableStateOf(true) }

    val player = remember(url, liveMs, movieSec, restart) {
        val p = buildPlayer(context, live, liveMs, movieSec)
        p.setMediaItem(MediaItem.fromUri(url))
        if (!live && resumeMs > 0) p.seekTo(resumeMs)
        p.prepare()
        p.playWhenReady = true
        p
    }

    DisposableEffect(player) {
        errorText = null
        PlayerHolder.player = player
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorText = "${error.errorCodeName}: ${error.message ?: ""}"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            if (PlayerHolder.player === player) PlayerHolder.player = null
            player.release()
        }
    }

    // Keep the screen on while watching
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    fun stepLive(dir: Int) {
        val found = liveSteps.indexOfFirst { it >= liveMs }
        val idx = if (found < 0) liveSteps.lastIndex else found
        liveMs = liveSteps[(idx + dir).coerceIn(0, liveSteps.lastIndex)]
        prefs.edit().putInt("live_buf_ms", liveMs).apply()
    }

    fun stepMovie(dir: Int) {
        resumeMs = player.currentPosition   // carry on from the same spot
        val found = movieSteps.indexOfFirst { it >= movieSec }
        val idx = if (found < 0) movieSteps.lastIndex else found
        movieSec = movieSteps[(idx + dir).coerceIn(0, movieSteps.lastIndex)]
        prefs.edit().putInt("movie_buf_s", movieSec).apply()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                }
            },
            update = { view ->
                view.player = player
                view.useController = !live && !inPip
            },
            modifier = Modifier.fillMaxSize()
        )

        // Live TV has no seek bar, so a tap shows or hides my own controls
        if (live) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { showLiveControls = !showLiveControls }
                    }
            )
        }

        if (!inPip && (!live || showLiveControls)) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                    TextButton(onClick = onEnterPip) { Text("PiP", color = Color.White) }
                }
                if (!live) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { stepMovie(-1) }) {
                            Text("Less buffer", color = Color.White)
                        }
                        Text("Buffer: $movieSec s", color = Color.White, fontSize = 12.sp)
                        TextButton(onClick = { stepMovie(1) }) {
                            Text("More buffer", color = Color.White)
                        }
                    }
                }
            }
        }

        if (live && !inPip && showLiveControls) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Live buffer: ${liveMs / 1000.0} s",
                    color = Color.White,
                    fontSize = 13.sp
                )
                Row {
                    TextButton(onClick = { stepLive(-1) }) { Text("Lower delay", color = Color.White) }
                    TextButton(onClick = { stepLive(1) }) { Text("More stable", color = Color.White) }
                    TextButton(onClick = { restart++ }) { Text("Reconnect", color = Color.White) }
                }
                Text(
                    "Lower = closer to live. Raise it if the picture stutters. Reconnect jumps back to live.",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
        }

        errorText?.let {
            Text(
                "Playback error\n$it",
                color = Color.Red,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000))
                    .padding(12.dp)
            )
        }
    }
}

// END OF FILE
