package com.doro.organizer.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.doro.organizer.R
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.ui.common.SecureDialogWindow
import kotlinx.coroutines.withContext

/**
 * The open-source notices (ui.md § 9.7): a full-screen dialog with the licenses of every third-party component in the
 * release APK, read from `res/raw/open_source_notices.txt` off the main thread; the text is selectable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenSourceNoticesDialog(dispatchers: AppDispatchers, onClose: () -> Unit) {
    val resources = LocalResources.current
    val notices by produceState("", resources) {
        value = withContext(dispatchers.io) {
            resources.openRawResource(R.raw.open_source_notices).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
    val title = stringResource(R.string.open_source_notices)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SecureDialogWindow {
            Scaffold(
                modifier = Modifier.fillMaxSize().semantics { paneTitle = title },
                topBar = {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close))
                            }
                        },
                    )
                },
            ) { padding ->
                SelectionContainer(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text(notices, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
