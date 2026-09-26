package com.telecall.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.telecall.app.UiState
import com.telecall.app.data.Campaign

/**
 * One campaign at a time (profiles.current_campaign_id,
 * backend/32_campaigns.sql) — reached either because the agent has never
 * picked one yet (no back arrow: there is nowhere to go back to) or via
 * the Queue screen's own "Switch campaign" action (back arrow shown,
 * [showBack] true).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampaignScreen(
    state: UiState,
    showBack: Boolean,
    onBack: () -> Unit,
    onSelect: (Campaign) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pick a campaign") },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
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
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (!showBack) {
                Text(
                    text = "Pick a campaign to see your queue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }

            if (state.campaignError != null) {
                Text(
                    text = state.campaignError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.switchingCampaign -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.campaigns.isEmpty() -> {
                        Text(
                            text = "No active campaigns yet. Ask your supervisor.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.campaigns, key = { it.id }) { campaign ->
                                CampaignCard(
                                    campaign = campaign,
                                    isCurrent = campaign.id == state.profile?.currentCampaignId,
                                    onClick = { onSelect(campaign) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CampaignCard(campaign: Campaign, isCurrent: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = campaign.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (isCurrent) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Current campaign",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
