package com.lean.iptv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

interface PlayerControl {
    fun pause()
}

object PlayerHolder {
    @Volatile
    var player: PlayerControl? = null
}

private val liveSteps = listOf(300, 500, 800, 1200, 2000, 3000, 5000)   // milliseconds
private const val MOVIE_BUFFER_S = 20

private fun clock(ms: Long): String {
    val s = maxOf(ms, 0L) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}

private fun Context.findComponentActivity(): ComponentActivity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}

private class ViewState {
    var layout: VLCVideoLayout? = null
    var attached = false
    var stopped = false
}

private class VlcSession(context: Context) : PlayerControl {
    val libVlc = LibVLC(context.applicationContext, arrayListOf("--http-reconnect"))
    val mediaPlayer = MediaPlayer(libVlc)

    @Volatile
    var userPaused = false

    @Volatile
    var released = false

    override fun pause() {
        userPaused = true
        try {
            mediaPlayer.pause()
        } catch (_: Exception) {
        }
    }

    fun start(url: String, live: Boolean, bufferMs: Int, startSec: Int) {
        userPaused = false
        val media = Media(libVlc, Uri.parse(url))
        media.addOption(":network-caching=$bufferMs")
        if (live) {
            media.addOption(":live-caching=$bufferMs")
        } else {
            media.addOption(":file-caching=$bufferMs")
            if (startSec > 0) media.addOption(":start-time=$startSec")
        }
        media.addOption(":http-reconnect")
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }

    fun release() {
        if (released) return
        released = true
        try {
            mediaPlayer.setEventListener(null)
            mediaPlayer.detachViews()
        } catch (_: Exception) {
        }
        Thread {
            try {
                mediaPlayer.stop()
                mediaPlayer.release()
                libVlc.release()
            } catch (_: Exception) {
            }
        }.start()
    }
}

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
    val session = remember { VlcSession(context) }
    val viewState = remember { ViewState() }

    var liveMs by remember { mutableIntStateOf(prefs.getInt("live_buf_ms_v4", 800)) }
    var restartKey by remember { mutableIntStateOf(0) }
    var layoutKey by remember { mutableIntStateOf(0) }
    var resumeMs by remember { mutableLongStateOf(0L) }
    var hasPlayed by remember { mutableStateOf(false) }
    var bufferingPct by remember { mutableFloatStateOf(100f) }
    var lastBufferEvent by remember { mutableLongStateOf(0L) }
    var errorStamp by remember { mutableLongStateOf(0L) }
    var errorCount by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var showControls by remember { mutableStateOf(true) }
    var touchStamp by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var timeMs by remember { mutableLongStateOf(0L) }
    var lengthMs by remember { mutableLongStateOf(0L) }
    var progress by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }

    fun touch() {
        touchStamp++
        showControls = true
    }

    DisposableEffect(session) {
        PlayerHolder.player = session
        session.mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    hasPlayed = true
                    errorCount = 0
                    errorStamp = 0L
                    status = ""
                }
                MediaPlayer.Event.Buffering -> {
                    bufferingPct = event.buffering
                    lastBufferEvent = SystemClock.elapsedRealtime()
                }
                MediaPlayer.Event.EncounteredError -> {
                    status = "Playback error"
                    errorStamp = SystemClock.elapsedRealtime()
                }
                MediaPlayer.Event.EndReached -> {
                    if (live) {
                        status = "Stream ended"
                        errorStamp = SystemClock.elapsedRealtime()
                    }
                }
                else -> {}
            }
        }
        onDispose {
            if (PlayerHolder.player === session) PlayerHolder.player = null
            session.release()
        }
    }

    DisposableEffect(session) {
        val owner = context.findComponentActivity()
        val observer = LifecycleEventObserver { _, event ->
            if (!session.released) {
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        viewState.stopped = true
                        if (viewState.attached) {
                            try {
                                session.mediaPlayer.detachViews()
                            } catch (_: Exception) {
                            }
                            viewState.attached = false
                        }
                    }
                    Lifecycle.Event.ON_START -> {
                        if (viewState.stopped) {
                            viewState.stopped = false
                            layoutKey++
                        }
                    }
                    else -> {}
                }
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(layoutKey) {
        if (layoutKey > 0) {
            delay(500)
            if (!session.released) {
                if (!live) {
                    try {
                        val t = session.mediaPlayer.time
                        if (t > 0) resumeMs = t
                    } catch (_: Exception) {
                    }
                }
                errorCount = 0
                restartKey++
            }
        }
    }

    LaunchedEffect(restartKey) {
        if (restartKey > 0) {
            withContext(Dispatchers.IO) {
                try {
                    session.mediaPlayer.stop()
                } catch (_: Exception) {
                }
            }
            delay(600)
        }
        if (session.released) return@LaunchedEffect
        hasPlayed = false
        val bufferMs = if (live) liveMs else MOVIE_BUFFER_S * 1000
        val startSec = if (live) 0 else (resumeMs / 1000).toInt()
        session.start(url, live, bufferMs, startSec)
    }

    LaunchedEffect(errorStamp) {
        if (errorStamp != 0L) {
            if (errorCount < 5) {
                delay(3000)
                errorCount++
                if (!live) {
                    try {
                        val t = session.mediaPlayer.time
                        if (t > 0) resumeMs = t
                    } catch (_: Exception) {
                    }
                }
                restartKey++
            } else {
                status = "Playback failed. Try Reconnect, and check that no other device is using the account."
            }
        }
    }

    LaunchedEffect(session) {
        var lastTime = -1L
        var lastMove = SystemClock.elapsedRealtime()
        var lastRecover = 0L
        while (true) {
            delay(500)
            if (session.released) break
            try {
                val mp = session.mediaPlayer
                val now = SystemClock.elapsedRealtime()
                val t = mp.time
                playing = mp.isPlaying
                timeMs = t
                lengthMs = mp.length
                if (!scrubbing && lengthMs > 0) progress = mp.position
                if (t != lastTime) {
                    lastTime = t
                    lastMove = now
                }

                val lastSign = maxOf(lastMove, lastBufferEvent)
                val limit = if (hasPlayed) 10_000L else 60_000L
                val atEnd = !live && lengthMs > 0 && t >= lengthMs - 2000
                if (!session.userPaused && !atEnd && now - lastSign > limit && now - lastRecover > 15_000L) {
                    lastRecover = now
                    lastMove = now
                    if (!live && t > 0) resumeMs = t
                    restartKey++
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    LaunchedEffect(touchStamp, showControls) {
        if (showControls) {
            delay(5000)
            showControls = false
        }
    }

    DisposableEffect(Unit) {
        try {
            context.startForegroundService(
                Intent(context, PlaybackService::class.java)
                    .putExtra(PlaybackService.EXTRA_TITLE, title)
            )
        } catch (_: Exception) {
        }
        onDispose {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    DisposableEffect(Unit) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leaniptv:play")
        cpuLock.setReferenceCounted(false)
        cpuLock.acquire()

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "leaniptv:wifi")
        wifiLock.setReferenceCounted(false)
        wifiLock.acquire()

        onDispose {
            if (cpuLock.isHeld) cpuLock.release()
            if (wifiLock.isHeld) wifiLock.release()
        }
    }

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    fun stepLive(dir: Int) {
        val found = liveSteps.indexOfFirst { it >= liveMs }
        val idx = if (found < 0) liveSteps.lastIndex else found
        liveMs = liveSteps[(idx + dir).coerceIn(0, liveSteps.lastIndex)]
        prefs.edit().putInt("live_buf_ms_v4", liveMs).apply()
        errorCount = 0
        restartKey++
    }

    fun reconnect() {
        errorCount = 0
        if (!live) {
            try {
                resumeMs = maxOf(session.mediaPlayer.time, 0L)
            } catch (_: Exception) {
            }
        }
        restartKey++
    }

    fun seekBy(deltaMs: Long) {
        val mp = session.mediaPlayer
        val len = mp.length
        var target = maxOf(mp.time + deltaMs, 0L)
        if (len > 2000) target = minOf(target, len - 1000)
        mp.time = target
    }

    fun togglePlay() {
        val mp = session.mediaPlayer
        if (mp.isPlaying) {
            session.userPaused = true
            mp.pause()
        } else {
            session.userPaused = false
            mp.play()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(layoutKey) {
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                        viewState.layout = layout
                        try {
                            session.mediaPlayer.attachViews(layout, null, false, false)
                            viewState.attached = true
                        } catch (_: Exception) {
                            viewState.attached = false
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        if (showControls) showControls = false else touch()
                    }
                }
        )

        if (!inPip && errorCount == 0 && (!hasPlayed || bufferingPct < 100f)) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color.White)
                Text(
                    if (hasPlayed) "Buffering ${bufferingPct.toInt()}%" else "Connecting...",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        if (!inPip && errorStamp != 0L && status.isNotEmpty()) {
            Text(
                status,
                color = Color.Red,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000))
                    .padding(12.dp)
            )
        }

        if (live && !inPip && showControls) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                    TextButton(onClick = { touch(); onEnterPip() }) { Text("PiP", color = Color.White) }
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Live buffer: ${liveMs / 1000.0} s", color = Color.White, fontSize = 13.sp)
                Row {
                    TextButton(onClick = { touch(); stepLive(-1) }) { Text("Lower delay", color = Color.White) }
                    TextButton(onClick = { touch(); stepLive(1) }) { Text("More stable", color = Color.White) }
                    TextButton(onClick = { touch(); reconnect() }) { Text("Reconnect", color = Color.White) }
                }
                Text(
                    "Lower = closer to live. Raise it if the picture stutters. Reconnect jumps back to live.",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
        }

        if (!live && !inPip && showControls) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Slider(
                    value = progress.coerceIn(0f, 1f),
                    onValueChange = {
                        progress = it
                        scrubbing = true
                        touchStamp++
                    },
                    onValueChangeFinished = {
                        session.mediaPlayer.position = progress
                        scrubbing = false
                    }
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(clock(timeMs), color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(clock(lengthMs), color = Color.White, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { touch(); seekBy(-30_000L) }) { Text("-30 s", color = Color.White) }
                    TextButton(onClick = { touch(); togglePlay() }) {
                        Text(if (playing) "Pause" else "Play", color = Color.White)
                    }
                    TextButton(onClick = { touch(); seekBy(30_000L) }) { Text("+30 s", color = Color.White) }
                    TextButton(onClick = { touch(); onEnterPip() }) { Text("PiP", color = Color.White) }
                }
            }
        }
    }
}
