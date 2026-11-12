package com.lean.iptv

import android.app.PictureInPictureParams
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    var inPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent { App(this) }
    }

    fun enterPip() {
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        } catch (e: Exception) {
            // PiP not available on this screen
        }
    }

    // Pressing Home while a video is open floats it in a small window
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PlayerHolder.player != null) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    // Also runs when the little PiP window is closed, so the video stops
    override fun onStop() {
        super.onStop()
        PlayerHolder.player?.pause()
    }
}

sealed class Screen {
    object Login : Screen()
    object Home : Screen()
    data class Items(val kind: Kind, val category: Category) : Screen()
    data class Episodes(val series: Item) : Screen()
    data class Play(val title: String, val url: String, val live: Boolean) : Screen()
}

@Composable
fun App(activity: MainActivity) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lean", Context.MODE_PRIVATE) }
    val state = remember { AppState(prefs) }
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<Screen>(Screen.Login) }

    BackHandler(enabled = stack.size > 1) {
        stack.removeAt(stack.lastIndex)
    }

    fun open(item: Item, kind: Kind) {
        val api = state.api ?: return
        when (kind) {
            Kind.LIVE -> stack.add(Screen.Play(item.name, api.liveUrl(item), true))
            Kind.MOVIES -> stack.add(Screen.Play(item.name, api.movieUrl(item), false))
            Kind.SERIES -> stack.add(Screen.Episodes(item))
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .safeDrawingPadding()
        ) {
            when (val s = stack.last()) {
                is Screen.Login -> LoginScreen(state) {
                    scope.launch {
                        if (state.login()) {
                            stack.clear()
                            stack.add(Screen.Home)
                        }
                    }
                }
                is Screen.Home -> HomeScreen(
                    state = state,
                    onCategory = { stack.add(Screen.Items(state.kind, it)) },
                    onItem = { open(it, state.kind) },
                    onLogout = {
                        state.logout()
                        stack.clear()
                        stack.add(Screen.Login)
                    }
                )
                is Screen.Items -> ItemsScreen(state, s.kind, s.category) { open(it, s.kind) }
                is Screen.Episodes -> EpisodesScreen(state, s.series) { ep ->
                    val api = state.api
                    if (api != null) {
                        stack.add(
                            Screen.Play(
                                "${s.series.name} · S${ep.season}E${ep.number}",
                                api.episodeUrl(ep),
                                false
                            )
                        )
                    }
                }
                is Screen.Play -> PlayerScreen(
                    title = s.title,
                    url = s.url,
                    live = s.live,
                    inPip = activity.inPip,
                    onEnterPip = { activity.enterPip() }
                )
            }
        }
    }
}

// END OF FILE
