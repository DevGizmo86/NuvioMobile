package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.proxy.ProxyPreferences
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun IntegratedProxySettings(isTablet: Boolean) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(ProxyPreferences.mode(context)) }
    SettingsSection(title = stringResource(Res.string.proxy_title), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(Res.string.proxy_description))
                listOf(
                    "off" to Res.string.proxy_off,
                    "hls" to Res.string.proxy_hls,
                    "all" to Res.string.proxy_all,
                ).forEach { (value, label) ->
                    TextButton(onClick = {
                        ProxyPreferences.preferences(context).edit().putString("mode", value).apply()
                        mode = value
                    }) {
                        Text((if (mode == value) "✓ " else "") + stringResource(label))
                    }
                }
                Text(stringResource(Res.string.proxy_limitations))
            }
        }
    }
}
