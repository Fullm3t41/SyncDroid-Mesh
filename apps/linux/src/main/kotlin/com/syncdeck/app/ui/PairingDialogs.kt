package com.syncdeck.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syncdeck.app.mesh.VisiblePairingOffer
import kotlinx.coroutines.delay

@Composable
fun CreateMeshDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("My mesh") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a mesh") },
        text = {
            Column {
                Text("This device becomes the first equal member. There is no host device.")
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
            Column {
                Text("Enter this code on the nearby device. It expires in ${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')}.")
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    offer.code.forEach { digit -> CodeBox(digit.toString(), onChange = {}, enabled = false) }
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
    val digits = remember { mutableStateListOf("", "", "", "", "", "") }
    val focus = remember { List(6) { FocusRequester() } }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Join a mesh") },
        text = {
            Column {
                Text("Enter the six-digit code shown by an existing trusted device.")
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    digits.indices.forEach { index ->
                        CodeBox(
                            value = digits[index],
                            enabled = !busy,
                            modifier = Modifier
                                .focusRequester(focus[index])
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && digits[index].isEmpty() && index > 0) {
                                        digits[index - 1] = ""
                                        focus[index - 1].requestFocus()
                                        true
                                    } else false
                                },
                            onChange = { entered ->
                                val number = entered.filter(Char::isDigit).takeLast(1)
                                digits[index] = number
                                if (number.isNotEmpty() && index < 5) focus[index + 1].requestFocus()
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    error ?: "$attemptsRemaining attempts remaining.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onJoin(digits.joinToString("")) },
                enabled = digits.all(String::isNotEmpty) && attemptsRemaining > 0 && !busy,
            ) { Text(if (busy) "Pairing…" else "Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { focus.first().requestFocus() }
}

@Composable
private fun CodeBox(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        modifier = modifier.size(width = 48.dp, height = 58.dp),
        textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}
