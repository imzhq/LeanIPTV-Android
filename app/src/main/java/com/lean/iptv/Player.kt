package com.lean.iptv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

// Lets the activity and the background service reach the current player
object PlayerHolder {
    @Volatile
    var player: ExoPlayer? = null
}

// 0.1 s was too small for the picture to hold together, so the lowest step is now 0.3 s
private val liveSteps = listOf(300, 500, 800, 1200, 2000, 3000, 5000)   // milliseconds
private val movieSteps = listOf(10, 20, 30, 45, 60)                     // seconds

@OptIn(UnstableApi::class)
private fun httpFactory(): DefaultHttpDataSource.Factory =
    DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15000)
        .setReadTimeoutMs(15000)

// Live source: checks the buffer every 32 KB instead of every 1 MB, so the buffer setting is accurate
@OptIn(UnstableApi::class)
private fun liveSource(url: String): MediaSource =
    ProgressiveMediaSource.Factory(httpFactory(), DefaultExtractorsFactory())
        .setContinueLoadingCheckIntervalBytes(32 * 1024)
        .createMediaSource(MediaItem.fromUri(url))

@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, live: Boolean, liveMs: Int, movieSec: Int): ExoPlayer {
    val loadControl = if (live) {
        // Small buffer = small delay. Raise it if the picture stutters.
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(liveMs, liveMs * 2, liveMs, liveMs)
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

    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory()))
        .setLoadControl(loadControl)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        // Keeps the processor and Wi-Fi awake while playing, so it works with the screen locked
        .setWakeMode(C.WAKE_MODE_NETWORK)
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

    // New saved name, so the new default applies even if you saved 0.1 s before
    var liveMs by remember { mutableIntStateOf(prefs.getInt("live_buf_ms_v3", 800)) }
    var movieSec by remember { mutableIntStateOf(prefs.getInt("movie_buf_s", 20)) }
    var restart by remember { mutableIntStateOf(0) }
    var resumeMs by remember { mutableLongStateOf(0L) }
    var stalledSince by remember { mutableLongStateOf(0L) }
    var lastAutoReconnect by remember { mutableLongStateOf(0L) }
    var errorStamp by remember { mutableLongStateOf(0L) }
    var retryCount by remember { mutableIntStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var liveInfo by remember { mutableStateOf("") }
    var showLiveControls by remember { mutableStateOf(true) }

    val player = remember(url, liveMs, movieSec, restart) {
        val p = buildPlayer(context, live, liveMs, movieSec)
        if (live) {
            p.setMediaSource(liveSource(url))
        } else {
            p.setMediaItem(MediaItem.fromUri(url))
            if (resumeMs > 0) p.seekTo(resumeMs)
        }
        p.prepare()
        p.playWhenReady = true
        p
    }

    DisposableEffect(player) {
        errorText = null
        stalledSince = 0L
        PlayerHolder.player = player
        var hasPlayed = false
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Show the real status code the server sent back, if there was one
                val http = error.cause as? HttpDataSource.InvalidResponseCodeException
                errorText = if (http != null) {
                    "The server answered with HTTP ${http.responseCode}"
                } else {
                    "${error.errorCodeName}: ${error.message ?: ""}"
                }
                errorStamp = SystemClock.elapsedRealtime()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    hasPlayed = true
                    retryCount = 0
                }
                // Remember when a stall started (only after playback had begun)
                stalledSince =
                    if (playbackState == Player.STATE_BUFFERING && hasPlayed) SystemClock.elapsedRealtime()
                    else 0L
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            if (PlayerHolder.player === player) PlayerHolder.player = null
            player.release()
        }
    }

    // Live only: catch-up. When extra video has piled up beyond the chosen buffer,
    // play a little faster until the delay is back down. No pausing, no seeking.
    LaunchedEffect(player, liveMs) {
        if (live) {
            var lastSpeed = 1f
            while (true) {
                delay(300)
                val ready = player.playbackState == Player.STATE_READY && player.playWhenReady
                var want = 1f
                if (ready) {
                    val excess = player.totalBufferedDuration - liveMs
                    want = when {
                        excess > 2500 -> 1.3f
                        excess > 1200 -> 1.15f
                        excess > 600 -> 1.05f
                        else -> 1f
                    }
                }
                if (want != lastSpeed) {
                    player.setPlaybackSpeed(want)
                    lastSpeed = want
                }
                liveInfo = String.format(
                    "Buffered %.1f s  |  speed %.2fx",
                    player.totalBufferedDuration / 1000.0,
                    lastSpeed
                )
            }
        }
    }

    // Live only: if the video stays frozen a long time, reconnect (at most once every 15 seconds)
    LaunchedEffect(player) {
        if (live) {
            while (true) {
                delay(500)
                val now = SystemClock.elapsedRealtime()
                val since = stalledSince
                if (since > 0L && now - since > 6000L && now - lastAutoReconnect > 15000L) {
                    lastAutoReconnect = now
                    stalledSince = 0L
                    restart++
                }
            }
        }
    }

    // Live only: after an error, wait 3 seconds and try again (up to 3 times in a row)
    LaunchedEffect(errorStamp) {
        if (live && errorStamp != 0L && retryCount < 3) {
            delay(3000)
            retryCount++
            restart++
        }
    }

    // Background service: keeps playing with the screen locked, shows a "Playing" notification
    DisposableEffect(Unit) {
        try {
            context.startForegroundService(
                Intent(context, PlaybackService::class.java)
                    .putExtra(PlaybackService.EXTRA_TITLE, title)
            )
        } catch (e: Exception) {
            // The video still plays with the screen on if the service can't start
        }
        onDispose {
            context.stopService(Intent(context, PlaybackService::class.java))
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
        prefs.edit().putInt("live_buf_ms_v3", liveMs).apply()
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

        // Title bar: live TV only, movies keep a clean picture
        if (live && !inPip && showLiveControls) {
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
                Text(liveInfo, color = Color.Yellow, fontSize = 11.sp)
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