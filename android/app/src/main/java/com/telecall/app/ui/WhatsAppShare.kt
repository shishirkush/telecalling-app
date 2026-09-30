package com.telecall.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.telecall.app.data.CardCatalog
import com.telecall.app.data.CardLink

/** Keeps the WhatsApp message short and focused on what was discussed. */
private const val MAX_CARDS = 3

/**
 * *bold* is WhatsApp's own markdown, rendered as real bold in the chat.
 * Falls back to a plain name + link line for any card missing from
 * [CardCatalog.pitches] (shouldn't happen for anything in
 * [CardCatalog.bundled], but keeps this from ever producing a
 * broken-looking message if it does).
 */
private fun cardBlock(card: CardLink): String {
    val pitch = CardCatalog.pitches[card.slug]
    return if (pitch != null) "*${pitch.title}*\n" + pitch.bullets.joinToString("\n") + "\n${card.url}"
    else "${card.name}\n${card.url}"
}

/**
 * Opens WhatsApp addressed to [target] with a message built from [cards].
 * wa.me is the officially supported deep link (Android 11+ package
 * visibility already covers it — see the manifest's VIEW/https <queries>
 * entry, added for the same reason for the Apply Card tab). Opens
 * straight to the chat; the agent still taps Send themselves on whatever
 * they type or attach there, same as every other outbound message this
 * app hands off rather than sends silently.
 */
fun launchWhatsAppChat(context: Context, target: String, cards: List<CardLink>) {
    var url = "https://wa.me/" + waNumber(target)
    if (cards.isNotEmpty()) {
        val text = cards.joinToString("\n\n") { cardBlock(it) }
        url += "?text=" + Uri.encode(text)
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, "No app available to open WhatsApp.", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Lets the agent tick up to [MAX_CARDS] card pages that fit the conversation with the
 * customer; the chosen cardadda.in links go into the WhatsApp message, which
 * the agent can still edit before sending. "Chat only" skips the links.
 * [leadMobile] defaults the target when opened from a specific lead's detail
 * screen, but this also opens with no lead at all (the queue screen's
 * WhatsApp icon) — there's no "claim a lead first" gate on messaging
 * someone, e.g. outside calling hours or for a number that isn't in the
 * queue yet. Without a lead the agent must type a number; with one, they
 * can still redirect to a different number instead — e.g. the same
 * customer calling back later from another phone.
 */
@Composable
fun CardPickerDialog(
    leadMobile: String?,
    onSend: (target: String, cards: List<CardLink>) -> Unit,
    onDismiss: () -> Unit
) {
    val hasLead = leadMobile != null
    var cards by remember { mutableStateOf(CardCatalog.bundled) }
    val selected = remember { mutableStateListOf<String>() }
    var useOtherNumber by remember { mutableStateOf(!hasLead) } // no lead number to default to — must type one
    var otherNumber by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { cards = CardCatalog.load() }

    // 10 bare digits is "good enough" — same bar waNumber() itself applies
    // before deciding whether to prepend the 91 country code.
    val otherValid = otherNumber.count { it.isDigit() } >= 10
    val canProceed = !useOtherNumber || otherValid
    val target = if (useOtherNumber) otherNumber else leadMobile.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which cards to send?") },
        text = {
            Column {
                Text(
                    if (hasLead) "Send to" else "Customer's WhatsApp number",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(4.dp))
                if (hasLead) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { useOtherNumber = false }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = !useOtherNumber, onClick = { useOtherNumber = false })
                        Spacer(Modifier.width(6.dp))
                        Text("$leadMobile (this lead)", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { useOtherNumber = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = useOtherNumber, onClick = { useOtherNumber = true })
                        Spacer(Modifier.width(6.dp))
                        Text("Another number", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (useOtherNumber) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = otherNumber,
                        onValueChange = { otherNumber = it },
                        placeholder = { Text("10-digit mobile number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Pick up to $MAX_CARDS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(cards, key = { it.slug }) { card ->
                        val checked = card.slug in selected
                        val enabled = checked || selected.size < MAX_CARDS
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled) {
                                    if (checked) selected.remove(card.slug) else selected.add(card.slug)
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    card.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                                if (card.issuer.isNotEmpty()) {
                                    Text(
                                        card.issuer,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty() && canProceed,
                onClick = { onSend(target, cards.filter { it.slug in selected }) }
            ) { Text(if (selected.isEmpty()) "Send" else "Send (${selected.size})") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(enabled = canProceed, onClick = { onSend(target, emptyList()) }) { Text("Chat only") }
            }
        }
    )
}
