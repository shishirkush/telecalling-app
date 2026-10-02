package com.telecall.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.telecall.app.ui.BankAppDetailScreen
import com.telecall.app.ui.CampaignScreen
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
                            onSaveContactNumber = viewModel::saveContactNumber,
                            onSwitchCampaign = viewModel::openCampaignPicker,
                            onOpenBankAppsTab = viewModel::loadBankAppsIfNeeded,
                            onStartNewBankApp = viewModel::startNewBankApp,
                            onOpenBankAppDetail = viewModel::openBankAppDetail,
                            onCancelBankAppForm = viewModel::cancelBankAppForm,
                            onBankAppBank = viewModel::onBankAppFieldBank,
                            onBankAppCustomerName = viewModel::onBankAppFieldCustomerName,
                            onBankAppPhone = viewModel::onBankAppFieldPhone,
                            onBankAppApplicationId = viewModel::onBankAppFieldApplicationId,
                            onBankAppCardName = viewModel::onBankAppFieldCardName,
                            onBankAppVkycStatus = viewModel::onBankAppFieldVkycStatus,
                            onSaveBankApp = viewModel::saveBankApp
                        )

                        Screen.CAMPAIGN -> CampaignScreen(
                            state = state,
                            showBack = state.profile?.currentCampaignId != null,
                            onBack = viewModel::backFromCampaignPicker,
                            onSelect = { campaign -> viewModel.selectCampaign(campaign.id) }
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
                                // Covers the system back gesture/button too, not just
                                // the in-app arrow — both route through the same
                                // guarded backToQueue(), which refuses to leave (and
                                // surfaces state.error instead) while this lead has
                                // been called but has no saved outcome yet.
                                BackHandler { viewModel.backToQueue() }
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
                                    onSave = { viewModel.saveDisposition { viewModel.backToQueue(afterSave = true) } },
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

                        Screen.BANK_APP_DETAIL -> {
                            val row = state.selectedBankApp
                            if (row == null) {
                                // Defensive: never mutate state during composition.
                                LaunchedEffect(Unit) { viewModel.backFromBankAppDetail() }
                            } else {
                                BackHandler { viewModel.backFromBankAppDetail() }
                                BankAppDetailScreen(
                                    state = state,
                                    row = row,
                                    onBack = viewModel::backFromBankAppDetail,
                                    onStartEdit = { viewModel.startEditBankApp(row) },
                                    onCancelEdit = viewModel::cancelBankAppForm,
                                    onBankAppCustomerName = viewModel::onBankAppFieldCustomerName,
                                    onBankAppPhone = viewModel::onBankAppFieldPhone,
                                    onBankAppApplicationId = viewModel::onBankAppFieldApplicationId,
                                    onBankAppCardName = viewModel::onBankAppFieldCardName,
                                    onSaveBankApp = viewModel::saveBankApp
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
        viewModel.reportCompetitorApps()
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
