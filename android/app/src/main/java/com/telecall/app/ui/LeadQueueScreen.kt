package com.telecall.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
    onSignOut: () -> Unit
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
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 88.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(visibleLeads, key = { it.id }) { lead ->
                                LeadCard(lead = lead, onClick = { onOpenLead(lead) })
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
                Text(
                    text = formatMobile(lead.mobile),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )

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
