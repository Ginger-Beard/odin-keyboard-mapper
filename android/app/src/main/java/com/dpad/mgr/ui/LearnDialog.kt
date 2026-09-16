package com.dpad.mgr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpad.mgr.core.Learn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Press a control" dialog: by default runs `dpadkeys --learn` and reports the first control
 * pressed; pass [learn] (e.g. [Learn.runLearnChord]) and [instructions] to repurpose it for a
 * different capture mode. Cancel / dismiss cancels the coroutine, which kills the learn process.
 */
@Composable
fun LearnDialog(
    title: String,
    onLearned: (String) -> Unit,
    onDismiss: () -> Unit,
    instructions: String = "Press the button, D-pad direction, stick direction or trigger on the pad to bind to $title.",
    learn: suspend (android.content.Context, (String) -> Unit) -> Result<String> = Learn::runLearn,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Starting…") }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(attempt) {
        error = null
        status = "Starting…"
        job = scope.launch {
            val r = learn(ctx.applicationContext) { status = it }
            r.onSuccess { src -> onLearned(src) }
                .onFailure { e -> error = e.message ?: "learn failed" }
        }
    }
    val cancel: () -> Unit = { Learn.requestCancel(); job?.cancel(); onDismiss() }

    AlertDialog(
        onDismissRequest = cancel,
        // Gamepad B falls back to BACK in Android's generic key map; while we are listening for a
        // press, Back must not dismiss the dialog (that cancelled every attempt to bind B).
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Press a control") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(instructions)
                val err = error
                if (err == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (error != null) TextButton(onClick = { attempt++ }) { Text("Retry") }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } },
    )
}
