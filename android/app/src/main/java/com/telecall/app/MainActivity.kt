package com.telecall.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.telecall.app.ui.BankAppDetailScreen
import com.telecall.app.ui.CampaignScreen
import com.telecall.app.ui.LeadDetailScreen
import com.telecall.app.ui.LeadQueueScreen
import com.telecall.app.ui.LoginScreen
import com.telecall.app.ui.MicrophoneRequiredScreen
import com.telecall.app.ui.SearchScreen
import com.telecall.app.ui.TrainingScreen
import com.telecall.app.ui.UnsupportedDeviceScreen
import com.telecall.app.ui.theme.TelecallTheme

class MainActivity : ComponentActivity() {

    // The app cannot be used without the microphone (it records the agent's side of calls).
    // Android has no install-time prompt, so it is asked at first launch, and until it is
    // granted a blocking screen replaces the whole app. Re-checked on every return to the
    // foreground, so switching it off later in Settings brings the screen back.
    private val microphoneGranted = mutableStateOf(true)

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshMicrophoneGate() }

    private val viewModel: AppViewModel by viewModels {
        AppViewModel.Factory(
            application,
            (application as TelecallApplication).repository
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        refreshMicrophoneGate()
        askForMicrophoneOnce()

        setContent {
            TelecallTheme {
                val state by viewModel.state.collectAsState()

                Box(Modifier.fillMaxSize()) {
                    if (!microphoneGranted.value) {
                        MicrophoneRequiredScreen(
                            onAllow = { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) },
                            onOpenSettings = { openAppSettings() }
                        )
                    } else when (state.screen) {
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
                            onOpenTraining = viewModel::openTraining,
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

                        Screen.TRAINING -> TrainingScreen(
                            state = state,
                            onBack = viewModel::backFromTraining,
                            onRefresh = viewModel::loadTraining,
                            onGetFile = viewModel::trainingFile
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
        refreshMicrophoneGate()
        // The agent may have granted the Phone permission from Settings while
        // the app was backgrounded; re-read the SIM list on the way back in.
        viewModel.refreshSims()
        viewModel.reportCompetitorApps()
        viewModel.syncCallRecordings()
    }

    // MainActivity is singleTask (see AndroidManifest.xml) specifically so a
    // tapped callback-reminder notification routes here instead of stacking
    // a second instance.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** True when the microphone is needed on this device and has not been allowed. */
    private fun refreshMicrophoneGate() {
        val needed = DeviceSupport.isSupportedDevice(this) &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphoneGranted.value = !needed || granted
    }

    private fun openAppSettings() {
        startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
        )
    }

    /** Asks for the microphone the first time the app is opened (the gate handles any refusal). */
    private fun askForMicrophoneOnce() {
        if (!DeviceSupport.isSupportedDevice(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("mic_asked", false)) return
        prefs.edit().putBoolean("mic_asked", true).apply()
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun handleIntent(intent: Intent?) {
        val leadId = intent?.getLongExtra(EXTRA_OPEN_LEAD_ID, -1L) ?: -1L
        if (leadId >= 0) viewModel.openLeadById(leadId)
    }

    companion object {
        const val EXTRA_OPEN_LEAD_ID = "open_lead_id"
    }
}
