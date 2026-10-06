package com.syncdows.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syncdows.app.mesh.VisiblePairingOffer
import com.syncdows.app.platform.WindowsTouchKeyboard
import kotlinx.coroutines.delay

@Composable
fun CreateMeshDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("My mesh") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a mesh") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("This PC becomes the first equal member. There is no host device.")
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 64) name = it },
                    label = { Text("Mesh name") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PairingOfferDialog(offer: VisiblePairingOffer, onDismiss: () -> Unit) {
    var remainingSeconds by remember(offer.expiresAtMillis) {
        mutableStateOf(((offer.expiresAtMillis - System.currentTimeMillis()) / 1_000).coerceAtLeast(0))
    }
    LaunchedEffect(offer.expiresAtMillis) {
        while (remainingSeconds > 0) {
            delay(1_000)
            remainingSeconds = ((offer.expiresAtMillis - System.currentTimeMillis()) / 1_000).coerceAtLeast(0)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a trusted device") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (remainingSeconds > 0) {
                    "Enter this code on the nearby device. It expires in ${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')}."
                } else {
                    "This code has expired. Close this dialog and choose Add a device to generate a new code."
                })
                Spacer(Modifier.height(18.dp))
                if (remainingSeconds > 0) SelectionContainer {
                    Text(offer.code.chunked(3).joinToString("  "),
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineLarge)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Pairing is authenticated locally and never contacts an internet service.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
fun JoinMeshDialog(
    attemptsRemaining: Int,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val canJoin = code.length == 6 && attemptsRemaining > 0 && !busy
    fun submit() { if (canJoin) onJoin(code) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Join a mesh") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("On an existing device, open Devices and choose Add a device. Enter its six-digit code here while both devices are on the same Wi-Fi.")
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { entered -> code = entered.filter { it in '0'..'9' }.take(6) },
                    enabled = !busy,
                    label = { Text("Six-digit code") },
                    placeholder = { Text("123456") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).showWindowsTouchKeyboardOnTap(!busy)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                submit()
                                true
                            } else false
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    error ?: if (attemptsRemaining > 0) "$attemptsRemaining attempts remaining." else "Pairing is temporarily locked. Try again after the lockout ends.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = canJoin) { Text(if (busy) "Pairing…" else "Join") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { if (!busy) focus.requestFocus() }
}

private fun Modifier.showWindowsTouchKeyboardOnTap(enabled: Boolean): Modifier {
    if (!enabled) return this
    return pointerInput(enabled) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.type == PointerEventType.Release) {
                    WindowsTouchKeyboard.show()
                }
            }
        }
    }
}
