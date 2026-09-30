package com.telecall.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.telecall.app.BankAppFormMode
import com.telecall.app.UiState
import com.telecall.app.data.ApprovalStatus
import com.telecall.app.data.BankApplication
import com.telecall.app.data.CallStatus
import com.telecall.app.data.Lead
import com.telecall.app.data.VkycStatus
import com.telecall.app.ui.theme.StatusLead

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
    onSwitchCampaign: () -> Unit,
    onOpenBankAppsTab: () -> Unit,
    onStartNewBankApp: () -> Unit,
    onOpenBankAppDetail: (BankApplication) -> Unit,
    onCancelBankAppForm: () -> Unit,
    onBankAppBank: (String) -> Unit,
    onBankAppCustomerName: (String) -> Unit,
    onBankAppPhone: (String) -> Unit,
    onBankAppApplicationId: (String) -> Unit,
    onBankAppCardName: (String) -> Unit,
    onBankAppVkycStatus: (VkycStatus) -> Unit,
    onSaveBankApp: () -> Unit
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

    // No lead required — see CardPickerDialog's doc comment in
    // WhatsAppShare.kt. Available any time the agent is signed in,
    // regardless of calling hours or queue state.
    var showWhatsAppPicker by remember { mutableStateOf(false) }
    if (showWhatsAppPicker) {
        CardPickerDialog(
            leadMobile = null,
            onSend = { target, cards ->
                showWhatsAppPicker = false
                launchWhatsAppChat(context, target, cards)
            },
            onDismiss = { showWhatsAppPicker = false }
        )
    }

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
                    IconButton(onClick = { showWhatsAppPicker = true }) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "WhatsApp any number")
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
            // Only meaningful on Queue/CallBacks — My Leads is a read-only
            // overview and Bank Apps has its own "+ Log application" action.
            if (selectedTab == 0 || selectedTab == 1) {
                ExtendedFloatingActionButton(
                    onClick = onClaimNext,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Next lead") }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // "My Leads" is this agent's own logged bank applications, not
            // a second view of the calling queue — Queue/CallBacks already
            // show that. Its data lives on the server, unlike Queue/
            // CallBacks (already-loaded state.queue split two ways), so
            // it's loaded once, the first time the agent actually opens
            // this tab.
            LaunchedEffect(selectedTab) {
                if (selectedTab == 2) onOpenBankAppsTab()
            }

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
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("My Leads") }
                )
                // Not a real content tab — tapping it sends the agent straight
                // to cardadda.in in the browser and leaves selectedTab alone,
                // so it never renders as "selected" (there is no fourth list).
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
                    selectedTab == 2 -> {
                        BankAppsTab(
                            state = state,
                            onStartNewBankApp = onStartNewBankApp,
                            onOpenBankAppDetail = onOpenBankAppDetail,
                            onCancelBankAppForm = onCancelBankAppForm,
                            onBankAppBank = onBankAppBank,
                            onBankAppCustomerName = onBankAppCustomerName,
                            onBankAppPhone = onBankAppPhone,
                            onBankAppApplicationId = onBankAppApplicationId,
                            onBankAppCardName = onBankAppCardName,
                            onBankAppVkycStatus = onBankAppVkycStatus,
                            onSaveBankApp = onSaveBankApp
                        )
                    }
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

/**
 * What happens to a lead after this agent gets them to apply for a
 * card (backend/36_bank_applications.sql). The list itself is a summary
 * only — date, customer name, bank, nothing else, green when approved —
 * tapping a row opens BankAppDetailScreen, where the rest (mobile,
 * card, application id, VKYC/approval/activation) and editing live.
 * The only form ever shown inline here is "log a new one"; see
 * [BankAppForm]'s doc comment for why editing an existing entry never
 * touches bank or VKYC status.
 */
@Composable
private fun BankAppsTab(
    state: UiState,
    onStartNewBankApp: () -> Unit,
    onOpenBankAppDetail: (BankApplication) -> Unit,
    onCancelBankAppForm: () -> Unit,
    onBankAppBank: (String) -> Unit,
    onBankAppCustomerName: (String) -> Unit,
    onBankAppPhone: (String) -> Unit,
    onBankAppApplicationId: (String) -> Unit,
    onBankAppCardName: (String) -> Unit,
    onBankAppVkycStatus: (VkycStatus) -> Unit,
    onSaveBankApp: () -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            if (state.bankAppFormMode == BankAppFormMode.NEW) {
                BankAppForm(
                    state = state,
                    onBankAppBank = onBankAppBank,
                    onBankAppCustomerName = onBankAppCustomerName,
                    onBankAppPhone = onBankAppPhone,
                    onBankAppApplicationId = onBankAppApplicationId,
                    onBankAppCardName = onBankAppCardName,
                    onBankAppVkycStatus = onBankAppVkycStatus,
                    onCancel = onCancelBankAppForm,
                    onSave = onSaveBankApp
                )
            } else {
                Button(onClick = onStartNewBankApp) { Text("+ Log application") }
            }
        }
        when {
            state.bankAppsLoading -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.bankApps.isEmpty() -> item {
                Text(
                    "No applications logged yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            else -> items(state.bankApps, key = { it.id }) { row ->
                BankAppCard(row = row, onClick = { onOpenBankAppDetail(row) })
            }
        }
    }
}

/**
 * Bank and VKYC status only ever get set in the NEW form, at creation —
 * the EDIT form shows them as plain text instead of fields, matching
 * update_bank_application()'s narrowed signature server-side (name/
 * application id/card/mobile only). Approval/activation don't appear
 * on this screen at all; only admin sets those, from the dashboard.
 */
@Composable
private fun BankAppForm(
    state: UiState,
    onBankAppBank: (String) -> Unit,
    onBankAppCustomerName: (String) -> Unit,
    onBankAppPhone: (String) -> Unit,
    onBankAppApplicationId: (String) -> Unit,
    onBankAppCardName: (String) -> Unit,
    onBankAppVkycStatus: (VkycStatus) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    val isNew = state.bankAppFormMode == BankAppFormMode.NEW
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = if (isNew) "Log a new application" else "Edit application",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(10.dp))
            if (isNew) {
                OutlinedTextField(
                    value = state.bankAppFormBank,
                    onValueChange = onBankAppBank,
                    label = { Text("Bank") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            } else {
                Text("Bank: ${state.bankAppFormBank}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = state.bankAppFormCustomerName,
                onValueChange = onBankAppCustomerName,
                label = { Text("Customer name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.bankAppFormPhone,
                onValueChange = onBankAppPhone,
                label = { Text("Phone") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.bankAppFormApplicationId,
                onValueChange = onBankAppApplicationId,
                label = { Text("Application ID (if known)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.bankAppFormCardName,
                onValueChange = onBankAppCardName,
                label = { Text("Card (if this bank names one)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (isNew) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "VKYC status",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VkycStatus.entries.forEach { v ->
                        FilterChip(
                            selected = state.bankAppFormVkycStatus == v,
                            onClick = { onBankAppVkycStatus(v) },
                            label = { Text(v.label) }
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Bank and VKYC status can only be set when logging a new " +
                        "application. Approval and activation are set by admin only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.bankAppsError != null) {
                Spacer(Modifier.height(8.dp))
                Text(state.bankAppsError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f), enabled = !state.bankAppSaving) {
                    Text("Cancel")
                }
                Button(onClick = onSave, modifier = Modifier.weight(1f), enabled = !state.bankAppSaving) {
                    if (state.bankAppSaving) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun BankAppCard(row: BankApplication, onClick: () -> Unit) {
    val approved = row.approvalStatus == ApprovalStatus.APPROVED
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (approved) StatusLead else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = "${row.customerName} · ${row.bank}",
                style = MaterialTheme.typography.titleMedium,
                color = if (approved) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatDob(row.appliedAt),
                style = MaterialTheme.typography.labelMedium,
                color = if (approved) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Full-page detail for a single logged application — lead, bank and
 * status, plus editing. Reached by tapping a row on the My Leads tab
 * above, mirroring LeadDetailScreen's own summary-then-tap-for-detail
 * pattern (minus the call-attempt/assignment logic that's specific to
 * leads). The approved-green treatment lives on the list row only;
 * this page stays plain regardless of status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankAppDetailScreen(
    state: UiState,
    row: BankApplication,
    onBack: () -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onBankAppCustomerName: (String) -> Unit,
    onBankAppPhone: (String) -> Unit,
    onBankAppApplicationId: (String) -> Unit,
    onBankAppCardName: (String) -> Unit,
    onSaveBankApp: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(row.customerName, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    if (state.bankAppFormMode == BankAppFormMode.EDIT) {
                        BankAppForm(
                            state = state,
                            onBankAppBank = {}, // bank is never editable — see BankAppForm's own doc comment
                            onBankAppCustomerName = onBankAppCustomerName,
                            onBankAppPhone = onBankAppPhone,
                            onBankAppApplicationId = onBankAppApplicationId,
                            onBankAppCardName = onBankAppCardName,
                            onBankAppVkycStatus = {}, // VKYC likewise fixed once logged
                            onCancel = onCancelEdit,
                            onSave = onSaveBankApp
                        )
                        return@Column
                    }

                    BaSectionLabel("Lead")
                    BaField("Customer", row.customerName)
                    BaField("Mobile", row.phone)
                    BaSectionLabel("Bank")
                    BaField("Bank", row.bank)
                    BaField("Card", row.cardName ?: "—")
                    BaField("Application ID", row.applicationId ?: "—")
                    BaField("VKYC status", row.vkycStatus.label)
                    BaSectionLabel("Status")
                    BaField("Approval", row.approvalStatus.label)
                    BaField(
                        "Activation",
                        row.activationStatus.label +
                            (row.activationNote?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
                    )
                    BaField("Applied", formatDob(row.appliedAt))

                    if (state.bankAppsError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.bankAppsError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    TextButton(onClick = onStartEdit, modifier = Modifier.align(Alignment.End)) {
                        Text("Edit")
                    }
                }
            }
        }
    }
}

@Composable
private fun BaSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun BaField(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
