package com.nuvio.app.features.streams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
internal actual fun BrowserStreamResolver(
    url: String,
    onResolved: (ResolvedBrowserStream) -> Unit,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    LaunchedEffect(url) {
        onError("La risoluzione Browser integrata è disponibile solo su Android.")
    }
}
