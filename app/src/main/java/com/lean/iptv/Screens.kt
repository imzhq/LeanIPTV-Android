package com.lean.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

fun isUhd(key: String): Boolean =
    key.contains("4k") || key.contains("uhd") || key.contains("2160")

@Composable
fun ListRow(text: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF262626))
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Text(text, color = Color.White)
    }
}

// MARK: Login

@Composable
fun LoginScreen(state: AppState, onLogin: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Lean IPTV", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = state.host,
            onValueChange = { state.host = it },
            label = { Text("URL to Xtream server") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.username,
            onValueChange = { state.username = it },
            label = { Text("Your username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = { state.password = it },
            label = { Text("Your password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        state.error?.let { Text(it, color = Color.Red, fontSize = 13.sp) }

        Button(
            onClick = onLogin,
            enabled = !state.loading && state.host.isNotBlank() &&
                state.username.isNotBlank() && state.password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            }
            Text(if (state.loading) "  Logging in..." else "Log in")
        }
    }
}

// MARK: Home (tabs, search, categories)

@Composable
fun HomeScreen(
    state: AppState,
    onCategory: (Category) -> Unit,
    onItem: (Item) -> Unit,
    onLogout: () -> Unit
) {
    val kind = state.kind
    val cats = state.categories[kind] ?: emptyList()
    val q = state.query.trim()

    LaunchedEffect(kind) { state.loadCategories(kind) }
    LaunchedEffect(kind, q.isNotEmpty()) {
        if (q.isNotEmpty()) state.loadLibrary(kind)
    }

    val lib = state.library[kind]
    val words = q.lowercase().split(" ").filter { it.isNotEmpty() }
    val found = remember(lib, q, state.dataSaver, kind) {
        if (q.isEmpty() || lib == null) {
            emptyList()
        } else {
            lib.filter { item ->
                (kind == Kind.LIVE || !state.dataSaver || !isUhd(item.key)) &&
                    words.all { w -> item.key.contains(w) }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Lean IPTV",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onLogout) { Text("Log out", color = Color.Red) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in Kind.entries) {
                FilterChip(
                    selected = k == kind,
                    onClick = {
                        state.kind = k
                        state.query = ""
                    },
                    label = { Text(k.label) }
                )
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = { state.query = it },
            singleLine = true,
            label = { Text("Search all ${kind.label.lowercase()}") },
            modifier = Modifier.fillMaxWidth()
        )

        if (kind != Kind.LIVE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Data saver", color = Color.White)
                    Text("Hides 4K/UHD titles", color = Color.Gray, fontSize = 12.sp)
                }
                Switch(checked = state.dataSaver, onCheckedChange = { state.setSaver(it) })
            }
        }

        if (q.isEmpty()) {
            state.error?.let { Text(it, color = Color.Red, fontSize = 12.sp) }
            Text(
                "${kind.label.uppercase()} CATEGORIES (${cats.size})",
                color = Color.Gray,
                fontSize = 13.sp
            )
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(cats) { c -> ListRow(c.name) { onCategory(c) } }
            }
        } else {
            if (state.libraryLoading[kind] == true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Text(
                        "  Loading the full list (first search only)...",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            } else if (state.libraryError != null && lib == null) {
                Text(state.libraryError ?: "", color = Color.Red, fontSize = 12.sp)
            } else {
                Text(
                    if (found.size > 300) "${found.size} RESULTS · showing the first 300, keep typing to narrow"
                    else "${found.size} RESULTS",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(found.take(300)) { item -> ListRow(item.name) { onItem(item) } }
            }
        }
    }
}

// MARK: One category

@Composable
fun ItemsScreen(state: AppState, kind: Kind, category: Category, onItem: (Item) -> Unit) {
    LaunchedEffect(category.id) { state.loadItems(kind, category) }

    val q = state.itemsQuery.trim().lowercase()
    val shown = remember(state.items, q, state.dataSaver) {
        state.items.filter { item ->
            (kind == Kind.LIVE || !state.dataSaver || !isUhd(item.key)) &&
                (q.isEmpty() || item.key.contains(q))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(category.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${shown.size} ITEMS", color = Color.Gray, fontSize = 13.sp)
            if (state.loading) {
                CircularProgressIndicator(
                    Modifier
                        .padding(start = 8.dp)
                        .size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            }
        }
        OutlinedTextField(
            value = state.itemsQuery,
            onValueChange = { state.itemsQuery = it },
            singleLine = true,
            label = { Text("Search this category") },
            modifier = Modifier.fillMaxWidth()
        )
        state.error?.let { Text(it, color = Color.Red, fontSize = 12.sp) }
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(shown) { item -> ListRow(item.name) { onItem(item) } }
        }
    }
}

// MARK: Episodes

@Composable
fun EpisodesScreen(state: AppState, series: Item, onEpisode: (Episode) -> Unit) {
    var loading by remember { mutableStateOf(state.episodeCache[series.id] == null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(series.id) {
        if (state.episodeCache[series.id] == null) {
            loading = true
            error = null
            try {
                state.episodeCache[series.id] = state.api?.episodes(series.id) ?: emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Episodes failed: ${e.message}"
            }
            loading = false
        }
    }

    val episodes = state.episodeCache[series.id] ?: emptyList()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(series.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("EPISODES (${episodes.size})", color = Color.Gray, fontSize = 13.sp)
            if (loading) {
                CircularProgressIndicator(
                    Modifier
                        .padding(start = 8.dp)
                        .size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            }
        }
        if (!loading && episodes.isEmpty() && error == null) {
            Text("No episodes found", color = Color.Gray, fontSize = 13.sp)
        }
        error?.let { Text(it, color = Color.Red, fontSize = 12.sp) }
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(episodes) { ep ->
                ListRow("S${ep.season} E${ep.number} · ${ep.title}") { onEpisode(ep) }
            }
        }
    }
}

// END OF FILE
