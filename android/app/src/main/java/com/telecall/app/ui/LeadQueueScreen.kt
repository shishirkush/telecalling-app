package com.telecall.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.telecall.app.UiState
import com.telecall.app.data.CallStatus
import com.telecall.app.data.Lead

/** Where "Apply Card" sends the agent — cardadda.in, not part of this app. */
private const val APPLY_CARD_URL = "https://www.cardadda.in/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeadQueueScreen(
    state: UiState,
    onOpenLead: (Lead) -> Unit,
    onClaimNext: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onSearch: () -> Unit,
    onEditContactNumber: () -> Unit,
    onDismissContactNumberDialog: () -> Unit,
    onContactNumberInputChange: (String) -> Unit,
    onSaveContactNumber: () -> Unit,
    onSwitchCampaign: () -> Unit
) {
    // Split, not two separate queries: myQueue() already returns every open
    // assigned lead in one call, so this is just "which half are we looking
    // at" — a fresh claim with no callback_at, or a Call Later still due.
    // Re-dispositioning from either tab is the same LeadDetailScreen + save
    // flow as today; nothing new was needed there.
    val callbackLeads = state.queue.filter { it.callbackAt != null }
    val freshLeads = state.queue.filter { it.callbackAt == null }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val visibleLeads = if (selectedTab == 1) callbackLeads else freshLeads

    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("My queue", style = MaterialTheme.typography.titleLarge)
                        val name = state.profile?.fullName
                        if (!name.isNullOrBlank()) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onEditContactNumber) {
                        Icon(Icons.Filled.Phone, contentDescription = "My calling number")
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search customer")
                    }
                    IconButton(onClick = onSwitchCampaign) {
                        Icon(Icons.Filled.Folder, contentDescription = "Switch campaign")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onClaimNext,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Next lead") }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Queue") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(if (callbackLeads.isEmpty()) "CallBacks" else "CallBacks (${callbackLeads.size})") }
                )
                // Not a real content tab — tapping it sends the agent straight
                // to cardadda.in in the browser and leaves selectedTab alone,
                // so it never renders as "selected" (there is no third list).
                Tab(
                    selected = false,
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APPLY_CARD_URL)))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "No browser app found.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    text = { Text("Apply Card") }
                )
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading && state.queue.isEmpty() -> {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    visibleLeads.isEmpty() -> {
                        EmptyQueue(
                            message = state.info ?: state.error ?: if (selectedTab == 1)
                                "No callbacks pending."
                            else
                                "Nothing assigned to you yet.",
                            showNextLeadHint = selectedTab == 0,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    selectedTab == 1 -> {
                        // Callbacks are a bounded, already-worked set — the
                        // agent has spoken to every one of these before and
                        // committed to calling back, often needing several
                        // reattempts before it's resolved. Full visibility
                        // here is what lets them triage which overdue
                        // callback to prioritize; the one-at-a-time
                        // restriction below is specifically about the fresh,
                        // never-yet-touched pool, where the "many strangers'
                        // numbers visible in one glance" concern actually
                        // applies. Same LeadCard, same tap-through to detail.
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 88.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(visibleLeads, key = { it.id }) { lead ->
                                LeadCard(lead = lead, onClick = { onOpenLead(lead) })
                            }
                        }
                    }
                    else -> {
                        // Deliberately one card, not a list: visibleLeads is
                        // already server-ordered by priority (never-attempted
                        // before retried, then least-recently-touched — see
                        // claim_next_lead's ORDER BY), so .first() is the
                        // right lead to work next. Showing the whole assigned
                        // batch at once meant every customer's name and
                        // mobile number was visible — and copyable — in a
                        // single glance; this shows only the one the agent
                        // is about to call. Saving its disposition refreshes
                        // the queue and reveals the next one in its place.
                        val currentLead = visibleLeads.first()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp, 8.dp, 12.dp, 88.dp)
                        ) {
                            Text(
                                text = "Your next lead",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            LeadCard(lead = currentLead, onClick = { onOpenLead(currentLead) })
                            if (visibleLeads.size > 1) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "${visibleLeads.size - 1} more waiting — " +
                                        "save this one to see the next.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (state.error != null && state.queue.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .fillMaxWidth(0.72f)
                ) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                }
            }
        }
    }

    if (state.showContactNumberDialog) {
        ContactNumberDialog(
            value = state.contactNumberInput,
            saving = state.savingContactNumber,
            error = state.error,
            onValueChange = onContactNumberInputChange,
            onSave = onSaveContactNumber,
            onDismiss = onDismissContactNumberDialog
        )
    }
}

/**
 * One reliable fact per agent rather than a per-call auto-detect —
 * Android can't dependably read a SIM's own number (many Indian
 * carriers/prepaid SIMs never expose it), so this asks once instead.
 * See backend/26_agent_contact_number.sql. Dismissible, not a hard gate
 * on working the queue — [AppViewModel] re-offers it on the next
 * relaunch if it's still blank.
 */
@Composable
private fun ContactNumberDialog(
    value: String,
    saving: Boolean,
    error: String?,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My calling number") },
        text = {
            Column {
                Text(
                    "The number you call customers from, so your supervisor can " +
                        "reach you or trace a call back to you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text("Your mobile number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !saving) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Later") }
        }
    )
}

@Composable
private fun EmptyQueue(
    message: String,
    showNextLeadHint: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (showNextLeadHint) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Tap \"Next lead\" to pull one from the pool.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LeadCard(lead: Lead, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = lead.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!lead.company.isNullOrBlank()) {
                    Text(
                        text = lead.company,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                // The raw mobile number used to render here as plain text —
                // same "many customers' details visible in one glance"
                // exposure as the full queue list this card replaced. Opening
                // the lead (via this same onClick) still shows the real
                // number and the actual tap-to-call banner in
                // LeadDetailScreen; this is just the list-level affordance.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable(onClick = onClick)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Call",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (lead.callbackAt != null) {
                    val overdue = isPast(lead.callbackAt)
                    val tint = if (overdue) MaterialTheme.colorScheme.error else statusColor(CallStatus.CALL_LATER)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Callback ${formatTimestamp(lead.callbackAt)}" +
                                if (overdue) " · Overdue" else "",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (overdue) FontWeight.SemiBold else FontWeight.Normal,
                            color = tint
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                lead.status?.let { StatusChip(it) }
                if (lead.attempts > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (lead.attempts == 1) "1 attempt" else "${lead.attempts} attempts",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun StatusChip(status: CallStatus) {
    val c = statusColor(status)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelMedium,
            color = c
        )
    }
}
