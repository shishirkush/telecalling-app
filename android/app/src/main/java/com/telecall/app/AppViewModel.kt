package com.telecall.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.telecall.app.call.SimManager
import com.telecall.app.call.SimOption
import com.telecall.app.data.CallStatus
import com.telecall.app.data.Lead
import com.telecall.app.data.LeadQuality
import com.telecall.app.data.LeadRepository
import com.telecall.app.data.Outcome
import com.telecall.app.data.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { LOGIN, QUEUE, DETAIL }

data class UiState(
    val screen: Screen = Screen.LOGIN,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val info: String? = null,

    val profile: Profile? = null,
    val queue: List<Lead> = emptyList(),
    val selected: Lead? = null,

    // --- SIM selection ---
    val sims: List<SimOption> = emptyList(),
    val showSimPicker: Boolean = false,
    val pendingNumber: String? = null,
    val calledOnSimSlot: Int? = null,
    val hasCalledThisLead: Boolean = false,

    // --- disposition form ---
    val formStatus: CallStatus? = null,
    val formQuality: LeadQuality? = null,
    val formRemarks: String = "",
    val formCallbackAt: Long? = null,     // epoch millis

    // --- editing the customer's own details, learned on the call ---
    val editing: Boolean = false,
    val editName: String = "",
    val editEmail: String = "",
    val editDob: String = "",             // as typed: DD-MM-YYYY
    val editCompany: String = "",
    val editIncome: String = "",
    val editAddress: String = ""
) {
    /** The Lead sub-dropdown is only required when the status is LEAD. */
    val needsQuality: Boolean get() = formStatus == CallStatus.LEAD
    val needsCallback: Boolean get() = formStatus == CallStatus.CALL_LATER

    val canSave: Boolean
        get() = formStatus != null &&
            (!needsQuality || formQuality != null) &&
            (!needsCallback || formCallbackAt != null) &&
            !saving
}

class AppViewModel(
    app: Application,
    private val repo: LeadRepository
) : AndroidViewModel(app) {

    private val simManager = SimManager(app.applicationContext)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (repo.isSignedIn) {
            _state.update { it.copy(screen = Screen.QUEUE) }
            loadProfileAndQueue()
        }
    }

    // -----------------------------------------------------------------
    // Auth
    // -----------------------------------------------------------------

    fun signIn(loginId: String, password: String) {
        if (loginId.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = "Enter your login ID and password.") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.signIn(loginId, password)) {
                is Outcome.Err -> _state.update { it.copy(loading = false, error = r.message) }
                is Outcome.Ok -> {
                    _state.update { it.copy(loading = false, screen = Screen.QUEUE) }
                    loadProfileAndQueue()
                }
            }
        }
    }

    fun signOut() {
        repo.signOut()
        _state.value = UiState(screen = Screen.LOGIN)
    }

    // -----------------------------------------------------------------
    // Queue
    // -----------------------------------------------------------------

    private fun loadProfileAndQueue() {
        viewModelScope.launch {
            when (val p = repo.myProfile()) {
                is Outcome.Ok -> _state.update { it.copy(profile = p.value) }
                is Outcome.Err -> _state.update { it.copy(error = p.message) }
            }
            refreshQueue()
        }
    }

    fun refreshQueue() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.myQueue()) {
                is Outcome.Ok -> _state.update { it.copy(loading = false, queue = r.value) }
                is Outcome.Err -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    /** Pull one more lead out of the shared pool. */
    fun claimNext() {
        _state.update { it.copy(loading = true, error = null, info = null) }
        viewModelScope.launch {
            when (val r = repo.claimNextLead()) {
                is Outcome.Err -> _state.update { it.copy(loading = false, error = r.message) }
                is Outcome.Ok -> {
                    val lead = r.value
                    if (lead == null) {
                        _state.update {
                            it.copy(loading = false, info = "No more leads in queue. Contact manager.")
                        }
                    } else {
                        _state.update { it.copy(loading = false) }
                        refreshQueue()
                        openLead(lead)
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Lead detail
    // -----------------------------------------------------------------

    fun openLead(lead: Lead) {
        _state.update {
            it.copy(
                screen = Screen.DETAIL,
                selected = lead,
                error = null,
                info = null,
                formStatus = null,
                formQuality = null,
                formRemarks = "",
                formCallbackAt = null,
                calledOnSimSlot = null,
                hasCalledThisLead = false
            )
        }
        // Audit trail — the agent is about to see unmasked PAN / DOB / income.
        viewModelScope.launch { repo.logLeadView(lead.id) }
    }

    fun backToQueue() {
        _state.update {
            it.copy(screen = Screen.QUEUE, selected = null, showSimPicker = false, error = null)
        }
        refreshQueue()
    }

    // -----------------------------------------------------------------
    // Calling
    // -----------------------------------------------------------------

    /**
     * Tapping the mobile number. Single SIM -> dial straight away.
     * Dual SIM -> show the chooser first.
     */
    fun onMobileTapped(number: String) {
        val sims = simManager.availableSims()
        if (sims.size > 1) {
            _state.update { it.copy(sims = sims, showSimPicker = true, pendingNumber = number) }
        } else {
            dial(number, sims.firstOrNull())
        }
    }

    fun onSimChosen(sim: SimOption) {
        val number = _state.value.pendingNumber
        _state.update { it.copy(showSimPicker = false, pendingNumber = null) }
        if (number != null) dial(number, sim)
    }

    fun dismissSimPicker() {
        _state.update { it.copy(showSimPicker = false, pendingNumber = null) }
    }

    private fun dial(number: String, sim: SimOption?) {
        when (val result = simManager.placeCall(number, sim)) {
            is SimManager.CallResult.Dialled ->
                _state.update {
                    it.copy(calledOnSimSlot = result.simSlot, hasCalledThisLead = true, error = null)
                }
            SimManager.CallResult.OpenedDialer ->
                _state.update {
                    it.copy(
                        hasCalledThisLead = true,
                        info = "Opened the dialer. Grant the Phone permission to dial in one tap."
                    )
                }
            SimManager.CallResult.InvalidNumber ->
                _state.update { it.copy(error = "This record has no valid phone number.") }
            is SimManager.CallResult.Failed ->
                _state.update { it.copy(error = result.message) }
        }
    }

    /** Call again after permissions were granted, or to retry. */
    fun refreshSims() {
        _state.update { it.copy(sims = simManager.availableSims()) }
    }

    // -----------------------------------------------------------------
    // Disposition form
    // -----------------------------------------------------------------

    fun setStatus(s: CallStatus) = _state.update {
        it.copy(
            formStatus = s,
            // Clear dependent fields when the status changes so a stale
            // "Hot" cannot be submitted alongside "Wrong No.".
            formQuality = if (s == CallStatus.LEAD) it.formQuality else null,
            formCallbackAt = if (s == CallStatus.CALL_LATER) it.formCallbackAt else null,
            error = null
        )
    }

    fun setQuality(q: LeadQuality) = _state.update { it.copy(formQuality = q) }
    fun setRemarks(t: String) = _state.update { it.copy(formRemarks = t.take(2000)) }
    fun setCallbackAt(epochMillis: Long) = _state.update { it.copy(formCallbackAt = epochMillis) }

    fun saveDisposition(onSaved: () -> Unit) {
        val s = _state.value
        val lead = s.selected ?: return
        val status = s.formStatus ?: return

        if (s.needsQuality && s.formQuality == null) {
            _state.update { it.copy(error = "Choose how strong this lead is.") }
            return
        }
        if (s.needsCallback && s.formCallbackAt == null) {
            _state.update { it.copy(error = "Pick a date and time for the callback.") }
            return
        }
        if (s.needsCallback && s.formCallbackAt!! <= System.currentTimeMillis()) {
            _state.update { it.copy(error = "The callback time must be in the future.") }
            return
        }

        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val r = repo.saveDisposition(
                leadId = lead.id,
                status = status,
                quality = s.formQuality,
                remarks = s.formRemarks,
                callbackAtIso = s.formCallbackAt?.let { iso(it) },
                simSlot = s.calledOnSimSlot
            )
            when (r) {
                is Outcome.Err -> _state.update { it.copy(saving = false, error = r.message) }
                is Outcome.Ok -> {
                    _state.update { it.copy(saving = false, info = "Saved.") }
                    onSaved()
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Editing the customer's own details
    // -----------------------------------------------------------------

    /**
     * Open the editor pre-filled with what is on record. The placeholder the
     * importer writes for a nameless lead is blanked, so the agent types into
     * an empty field instead of deleting "Name not provided" first.
     */
    fun startEditingDetails() {
        val lead = _state.value.selected ?: return
        _state.update {
            it.copy(
                editing = true,
                error = null,
                editName = lead.name.takeIf { n -> n != UNKNOWN_NAME }.orEmpty(),
                editEmail = lead.email.orEmpty(),
                editDob = lead.dob?.let { iso -> ddmmyyyy(iso) }.orEmpty(),
                editCompany = lead.company.orEmpty(),
                editIncome = lead.annualIncomeRange.orEmpty(),
                editAddress = lead.address.orEmpty()
            )
        }
    }

    fun cancelEditingDetails() = _state.update { it.copy(editing = false) }

    fun onEditName(v: String)    = _state.update { it.copy(editName = v) }
    fun onEditEmail(v: String)   = _state.update { it.copy(editEmail = v) }
    fun onEditDob(v: String)     = _state.update { it.copy(editDob = v) }
    fun onEditCompany(v: String) = _state.update { it.copy(editCompany = v) }
    fun onEditIncome(v: String)  = _state.update { it.copy(editIncome = v) }
    fun onEditAddress(v: String) = _state.update { it.copy(editAddress = v) }

    fun saveDetails() {
        val s = _state.value
        val lead = s.selected ?: return

        // Typed as DD-MM-YYYY to match the CSV import; the server wants ISO.
        val dobIso = s.editDob.trim().takeIf { it.isNotEmpty() }?.let { typed ->
            isoFromDdMmYyyy(typed) ?: run {
                _state.update { it.copy(error = "Date of birth must be DD-MM-YYYY, for example 25-12-1990.") }
                return
            }
        }
        if (s.editAddress.length > 2000) {
            _state.update { it.copy(error = "The address is longer than 2000 characters.") }
            return
        }

        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.saveLeadDetails(
                leadId = lead.id,
                name = s.editName,
                email = s.editEmail,
                dobIso = dobIso,
                company = s.editCompany,
                annualIncomeRange = s.editIncome,
                address = s.editAddress
            )) {
                is Outcome.Err -> _state.update { it.copy(saving = false, error = r.message) }
                is Outcome.Ok -> _state.update { st ->
                    // Replace the record in the queue too, so going back does
                    // not show the old details.
                    st.copy(
                        saving = false,
                        editing = false,
                        info = "Details saved.",
                        selected = r.value,
                        queue = st.queue.map { q -> if (q.id == r.value.id) r.value else q }
                    )
                }
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, info = null) }

    private fun iso(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(epochMillis))

    /** "1990-12-25" -> "25-12-1990" for display in the editor. */
    private fun ddmmyyyy(iso: String): String {
        val p = iso.split("-")
        return if (p.size == 3) "${p[2]}-${p[1]}-${p[0]}" else iso
    }

    /** "25-12-1990" -> "1990-12-25", or null if it is not a real date. */
    private fun isoFromDdMmYyyy(typed: String): String? {
        val m = Regex("""^(\d{1,2})[-/](\d{1,2})[-/](\d{4})$""").find(typed) ?: return null
        val (d, mo, y) = m.destructured
        val day = d.toInt(); val month = mo.toInt(); val year = y.toInt()
        if (month !in 1..12 || day !in 1..31 || year !in 1900..2100) return null
        // Reject 31 February and friends rather than letting Postgres do it.
        val cal = java.util.Calendar.getInstance().apply {
            isLenient = false
            clear()
            set(year, month - 1, day)
        }
        return try {
            cal.time
            "%04d-%02d-%02d".format(year, month, day)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** Must match UNKNOWN_NAME in dashboard/index.html. */
        const val UNKNOWN_NAME = "Name not provided"
    }

    // -----------------------------------------------------------------

    class Factory(
        private val app: Application,
        private val repo: LeadRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(app, repo) as T
    }
}
