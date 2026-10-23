package me.csepregi.pkmrec.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.update.UpdateManager
import me.csepregi.pkmrec.update.UpdateState

/** Shows the update offer, download progress and errors on top of any screen. */
@Composable
fun UpdateDialog() {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsState()
    val current = UpdateManager.installedVersion?.toString() ?: "?"

    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { UpdateManager.dismiss() },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(stringResource(R.string.update_available_text, s.release.version.toString(), current))
                    if (s.release.notes.isNotBlank()) {
                        Text(
                            stringResource(R.string.update_release_notes),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(s.release.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { UpdateManager.startUpdate(context, s.release) }) {
                    Text(stringResource(R.string.update_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { UpdateManager.dismiss() }) {
                    Text(stringResource(R.string.update_later))
                }
            },
        )

        is UpdateState.NeedsInstallPermission -> AlertDialog(
            onDismissRequest = { UpdateManager.dismiss() },
            title = { Text(stringResource(R.string.update_permission_title)) },
            text = { Text(stringResource(R.string.update_permission_text)) },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + context.packageName),
                        ),
                    )
                }) { Text(stringResource(R.string.update_permission_open)) }
            },
            dismissButton = {
                TextButton(onClick = { UpdateManager.dismiss() }) { Text(stringResource(R.string.cancel)) }
            },
        )

        is UpdateState.Downloading -> ProgressDialog(
            text = stringResource(R.string.update_downloading),
            progress = s.progress,
        )

        UpdateState.Verifying -> ProgressDialog(stringResource(R.string.update_verifying), progress = -1f)

        UpdateState.Installing -> ProgressDialog(stringResource(R.string.update_installing), progress = -1f)

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { UpdateManager.dismiss() },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = { Text(stringResource(R.string.update_failed, s.message)) },
            confirmButton = {
                TextButton(onClick = { UpdateManager.dismiss() }) { Text(stringResource(R.string.ok)) }
            },
        )

        else -> Unit
    }
}

@Composable
private fun ProgressDialog(text: String, progress: Float) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(text) },
        text = {
            if (progress in 0f..1f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
    )
}
