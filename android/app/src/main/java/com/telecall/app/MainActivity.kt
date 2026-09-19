package com.telecall.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.telecall.app.ui.LeadDetailScreen
import com.telecall.app.ui.LeadQueueScreen
import com.telecall.app.ui.LoginScreen
import com.telecall.app.ui.SearchScreen
import com.telecall.app.ui.UnsupportedDeviceScreen
import com.telecall.app.ui.theme.TelecallTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        AppViewModel.Factory(
            application,
            (application as TelecallApplication).repository
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            TelecallTheme {
                val state by viewModel.state.collectAsState()

                Box(Modifier.fillMaxSize()) {
                    when (state.screen) {
                        Screen.UNSUPPORTED_DEVICE -> UnsupportedDeviceScreen()

                        Screen.LOGIN -> LoginScreen(
                            state = state,
                            onSignIn = viewModel::signIn
                        )

                        Screen.QUEUE -> LeadQueueScreen(
                            state = state,
                            onOpenLead = viewModel::openLead,
                            onClaimNext = viewModel::claimNext,
                            onRefresh = viewModel::refreshQueue,
                            onSignOut = viewModel::signOut,
                            onSearch = viewModel::openSearch,
                            onEditContactNumber = viewModel::openContactNumberDialog,
                            onDismissContactNumberDialog = viewModel::dismissContactNumberDialog,
                            onContactNumberInputChange = viewModel::setContactNumberInput,
                            onSaveContactNumber = viewModel::saveContactNumber
                        )

                        Screen.SEARCH -> SearchScreen(
                            state = state,
                            onBack = viewModel::backFromSearch,
                            onQueryChange = viewModel::setSearchQuery,
                            onSearch = viewModel::performSearch,
                            onSelectResult = viewModel::selectSearchResult,
                            onDismissResult = viewModel::dismissSearchResult
                        )

                        Screen.DETAIL -> {
                            val lead = state.selected
                            if (lead == null) {
                                // Defensive: never mutate state during composition.
                                LaunchedEffect(Unit) { viewModel.backToQueue() }
                            } else {
                                LeadDetailScreen(
                                    state = state,
                                    lead = lead,
                                    onBack = viewModel::backToQueue,
                                    onCall = viewModel::onMobileTapped,
                                    onSimChosen = viewModel::onSimChosen,
                                    onDismissSimPicker = viewModel::dismissSimPicker,
                                    onStatus = viewModel::setStatus,
                                    onQuality = viewModel::setQuality,
                                    onRemarks = viewModel::setRemarks,
                                    onCallbackAt = viewModel::setCallbackAt,
                                    onSave = { viewModel.saveDisposition { viewModel.backToQueue() } },
                                    onStartEdit = viewModel::startEditingDetails,
                                    onCancelEdit = viewModel::cancelEditingDetails,
                                    onEditName = viewModel::onEditName,
                                    onEditEmail = viewModel::onEditEmail,
                                    onEditDob = viewModel::onEditDob,
                                    onEditCompany = viewModel::onEditCompany,
                                    onEditIncome = viewModel::onEditIncome,
                                    onEditAddress = viewModel::onEditAddress,
                                    onSaveDetails = viewModel::saveDetails
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The agent may have granted the Phone permission from Settings while
        // the app was backgrounded; re-read the SIM list on the way back in.
        viewModel.refreshSims()
    }

    // MainActivity is singleTask (see AndroidManifest.xml) specifically so a
    // tapped callback-reminder notification routes here instead of stacking
    // a second instance.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val leadId = intent?.getLongExtra(EXTRA_OPEN_LEAD_ID, -1L) ?: -1L
        if (leadId >= 0) viewModel.openLeadById(leadId)
    }

    companion object {
        const val EXTRA_OPEN_LEAD_ID = "open_lead_id"
    }
}
