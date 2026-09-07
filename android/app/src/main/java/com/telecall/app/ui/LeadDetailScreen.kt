package com.telecall.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.telecall.app.UiState
import com.telecall.app.call.SimOption
import com.telecall.app.data.CallStatus
import com.telecall.app.data.Lead
import com.telecall.app.data.LeadQuality
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeadDetailScreen(
    state: UiState,
    lead: Lead,
    onBack: () -> Unit,
    onCall: (String) -> Unit,
    onSimChosen: (SimOption) -> Unit,
    onDismissSimPicker: () -> Unit,
    onStatus: (CallStatus) -> Unit,
    onQuality: (LeadQuality) -> Unit,
    onRemarks: (String) -> Unit,
    onCallbackAt: (Long) -> Unit,
    onSave: () -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onEditName: (String) -> Unit,
    onEditEmail: (String) -> Unit,
    onEditDob: (String) -> Unit,
    onEditCompany: (String) -> Unit,
    onEditIncome: (String) -> Unit,
    onEditAddress: (String) -> Unit,
    onSaveDetails: () -> Unit
) {
    val context = LocalContext.current
    val callPerms = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)

    // Whatever the user answers, we proceed: with permission we dial straight
    // out of the chosen SIM, without it SimManager falls back to opening the
    // system dialer. Denying the permission degrades the flow, it never
    // blocks the agent from working.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onCall(lead.mobile) }

    fun startCall() {
        val allGranted = callPerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) onCall(lead.mobile) else permissionLauncher.launch(callPerms)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lead.name, style = MaterialTheme.typography.titleLarge) },
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

            // ---------- Call action ----------
            CallButton(
                mobile = lead.mobile,
                alreadyCalled = state.hasCalledThisLead,
                onClick = { startCall() }
            )

            Spacer(Modifier.height(16.dp))

            // ---------- Customer record ----------
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    if (state.editing) {
                        EditDetailsForm(
                            state = state,
                            onEditName = onEditName,
                            onEditEmail = onEditEmail,
                            onEditDob = onEditDob,
                            onEditCompany = onEditCompany,
                            onEditIncome = onEditIncome,
                            onEditAddress = onEditAddress,
                            onSaveDetails = onSaveDetails,
                            onCancel = onCancelEdit
                        )
                        return@Column
                    }

                    Field("Name", lead.name)
                    Field("Company", lead.company ?: "—")
                    Field("Email", lead.email ?: "—")
                    Field("PAN", maskPan(lead.pan))
                    Field("DOB", maskDob(lead.dob))
                    Field("Annual Income Range", lead.annualIncomeRange ?: "—")
                    Field("ICI Cr Lmt", maskAmount(lead.iciCrLmt), emphasise = true)
                    Field("Address", maskAddress(lead.address))

                    TextButton(
                        onClick = onStartEdit,
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Edit details") }

                    if (lead.attempts > 0) {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text(
                            text = "Previous attempts: ${lead.attempts}" +
                                (lead.status?.let { " · last outcome ${it.label}" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!lead.lastRemarks.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Last remark: ${lead.lastRemarks}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---------- Call status ----------
            SectionLabel("Call Status")
            Spacer(Modifier.height(8.dp))
            CallStatus.entries.forEach { status ->
                StatusRow(
                    status = status,
                    selected = state.formStatus == status,
                    onClick = { onStatus(status) }
                )
                Spacer(Modifier.height(6.dp))
            }

            // ---------- Lead sub-dropdown ----------
            if (state.needsQuality) {
                Spacer(Modifier.height(12.dp))
                SectionLabel("Lead strength")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LeadQuality.entries.forEach { q ->
                        FilterChip(
                            selected = state.formQuality == q,
                            onClick = { onQuality(q) },
                            label = { Text(q.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
                state.formQuality?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = it.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------- Callback scheduling ----------
            if (state.needsCallback) {
                Spacer(Modifier.height(12.dp))
                SectionLabel("Call back at")
                Spacer(Modifier.height(8.dp))
                CallbackPicker(
                    currentMillis = state.formCallbackAt,
                    onPicked = onCallbackAt
                )
            }

            // ---------- Remarks ----------
            Spacer(Modifier.height(20.dp))
            SectionLabel("Remarks")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.formRemarks,
                onValueChange = onRemarks,
                placeholder = { Text("Anything worth recording about this call") },
                minLines = 3,
                maxLines = 6,
                supportingText = { Text("${state.formRemarks.length}/2000") },
                modifier = Modifier.fillMaxWidth()
            )

            // ---------- Errors ----------
            if (state.error != null) {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // ---------- Save ----------
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Save outcome")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (state.showSimPicker) {
        SimPickerDialog(
            sims = state.sims,
            onPick = onSimChosen,
            onDismiss = onDismissSimPicker
        )
    }
}

// ---------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------

@Composable
private fun CallButton(mobile: String, alreadyCalled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Call,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = formatMobile(mobile),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = if (alreadyCalled) "Tap to call again" else "Tap to call",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                )
            }
            if (alreadyCalled) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Called",
                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun SimPickerDialog(
    sims: List<SimOption>,
    onPick: (SimOption) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Call using") },
        text = {
            Column {
                sims.forEach { sim ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(sim) }
                            .padding(vertical = 14.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.SimCard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "SIM ${sim.slotIndex}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (!sim.carrier.isNullOrBlank()) {
                                Text(
                                    text = sim.carrier,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Correct the customer's details from what they say on the call.
 *
 * Only the fields the agent is allowed to change appear here. Mobile, PAN and
 * the credit limit are shown on the record but are not editable — the server
 * will not accept them either, so this is a matching restriction rather than
 * the only one.
 *
 * A field left blank is left unchanged rather than cleared, which is why the
 * hint says so: mid-call is the wrong moment to wipe data by accident.
 */
@Composable
private fun ColumnScope.EditDetailsForm(
    state: UiState,
    onEditName: (String) -> Unit,
    onEditEmail: (String) -> Unit,
    onEditDob: (String) -> Unit,
    onEditCompany: (String) -> Unit,
    onEditIncome: (String) -> Unit,
    onEditAddress: (String) -> Unit,
    onSaveDetails: () -> Unit,
    onCancel: () -> Unit
) {
    SectionLabel("Edit details")
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Anything you leave blank stays as it is.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = state.editName,
        onValueChange = onEditName,
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.editCompany,
        onValueChange = onEditCompany,
        label = { Text("Company") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.editEmail,
        onValueChange = onEditEmail,
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.editDob,
        onValueChange = onEditDob,
        label = { Text("Date of birth") },
        placeholder = { Text("DD-MM-YYYY") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.editIncome,
        onValueChange = onEditIncome,
        label = { Text("Annual income range") },
        placeholder = { Text("10L - 15L") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.editAddress,
        onValueChange = { if (it.length <= 2000) onEditAddress(it) },
        label = { Text("Address") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = "${state.editAddress.length}/2000",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.End)
    )

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onCancel, enabled = !state.saving) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSaveDetails, enabled = !state.saving) {
            if (state.saving) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Save details")
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, emphasise: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp)
        )
        Text(
            text = value,
            style = if (emphasise) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasise) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasise) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StatusRow(status: CallStatus, selected: Boolean, onClick: () -> Unit) {
    val accent = statusColor(status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) accent else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = status.label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallbackPicker(currentMillis: Long?, onPicked: (Long) -> Unit) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var stagedDate by remember { mutableLongStateOf(0L) }

    Column {
        // Quick options cover most callbacks without opening a picker.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickChip("In 1 hr") { onPicked(plusMinutes(60)) }
            QuickChip("In 3 hrs") { onPicked(plusMinutes(180)) }
            QuickChip("Tomorrow") { onPicked(tomorrowAt(10, 0)) }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(10.dp)
                )
                .clickable { showDate = true }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = formatEpoch(currentMillis),
                style = MaterialTheme.typography.bodyLarge,
                color = if (currentMillis == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }

    if (showDate) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = currentMillis ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    stagedDate = dateState.selectedDateMillis ?: System.currentTimeMillis()
                    showDate = false
                    showTime = true
                }) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTime) {
        val now = Calendar.getInstance()
        val timeState = rememberTimePickerState(
            initialHour = now.get(Calendar.HOUR_OF_DAY),
            initialMinute = 0,
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("Pick a time") },
            text = {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = timeState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTime = false
                    onPicked(combine(stagedDate, timeState.hour, timeState.minute))
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    FilterChip(selected = false, onClick = onClick, label = { Text(label) })
}

// ---------------------------------------------------------------------
// Time helpers
// ---------------------------------------------------------------------

private fun plusMinutes(minutes: Int): Long =
    System.currentTimeMillis() + minutes * 60_000L

private fun tomorrowAt(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, 1)
    set(Calendar.HOUR_OF_DAY, hour)
    set(Calendar.MINUTE, minute)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * The Compose date picker returns midnight UTC for the chosen day. Rebuild the
 * instant in the device's own timezone, otherwise a callback set for "3 PM"
 * lands 5.5 hours out for an IST user.
 */
private fun combine(dateMillisUtc: Long, hour: Int, minute: Int): Long {
    val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = dateMillisUtc
    }
    return Calendar.getInstance().apply {
        set(Calendar.YEAR, utc.get(Calendar.YEAR))
        set(Calendar.MONTH, utc.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
