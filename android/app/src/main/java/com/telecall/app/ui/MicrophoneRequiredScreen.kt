package com.telecall.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown instead of the whole app until the microphone permission is granted:
 * the app records the agent's side of calls, so it can't be used without it.
 * It disappears by itself the moment the permission is on (MainActivity
 * re-checks whenever the app returns to the foreground).
 */
@Composable
fun MicrophoneRequiredScreen(
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Microphone access required",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "This app records your voice during calls for training. " +
                    "You need to allow microphone access before you can continue.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
                Text("Allow microphone")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open app settings")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "If the permission prompt doesn't appear, open app settings → Permissions → Microphone → Allow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
