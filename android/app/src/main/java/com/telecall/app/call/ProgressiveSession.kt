package com.telecall.app.call

import android.app.Application
import com.telecall.app.CallStateTracker
import com.telecall.app.data.CallStatus
import com.telecall.app.data.Lead
import com.telecall.app.data.LeadQuality
import com.telecall.app.data.LeadRepository
import com.telecall.app.data.Outcome
import com.telecall.app.lastOutgoingCallDurationSecs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Progressive calling (backend/60_progressive_mode.sql): the app places calls
 * back to back, one lead after another, and the agent saves each call's outcome
 * later from a "to save" list instead of between calls.
 *
 * The rules, in one place:
 *  - Up to [ROUND_SIZE] (10) calls are dialled without stopping. After the 10th,
 *    dialling stops until every one of them has been saved ("save required").
 *    The server enforces the same ceiling on unsaved calls, so a modified app
 *    can't go past it either.
 *  - The next call starts [COUNTDOWN_SECS] seconds after the previous one ends,
 *    unless the agent pauses. That gap is the only moment to say "not now".
 *  - It needs this screen in the foreground to dial: Android doesn't let an app
 *    start a call from the background. If a call ends while the app isn't on
 *    screen, dialling waits until it is.
 *  - Saving goes through the same save_disposition() as normal mode, so every
 *    existing rule (30 seconds after the call, must be assigned to the agent)
 *    applies unchanged.
 *
 * Owned by AppViewModel, so it survives the screen being left; leaving the
 * screen pauses it (see [pause]).
 */
class ProgressiveSession(
    private val app: Application,
    private val scope: CoroutineScope,
    private val repo: LeadRepository,
    private val simManager: SimManager
) {

    enum class Phase {
        /** Nothing running yet. */
        IDLE,
        /** Fetching the next lead from the pool. */
        CLAIMING,
        /** Next lead chosen; counting down to dial it. */
        COUNTDOWN,
        /** A call is in progress (dialling, ringing or talking). */
        ON_CALL,
        /** 10 calls done; save them all before more are dialled. */
        SAVE_REQUIRED,
        PAUSED,
        /** A call ended while the app wasn't on screen; resumes when it is. */
        WAITING_FOREGROUND,
        NO_LEADS,
        /** Something went wrong; [Ui.message] says what. Start again to retry. */
        STOPPED
    }

    /** A call that has ended and is waiting for its outcome. */
    data class Pending(
        val lead: Lead,
        /** When the call attempt was logged (epoch ms) — the save floor counts from here. */
        val attemptedAt: Long,
        val talkSecs: Int? = null,
        val dialSecs: Int? = null
    ) {
        val dwellRemainingSecs: Int
            get() = ((attemptedAt + DWELL_MS - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
    }

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val current: Lead? = null,
        val dialsThisRound: Int = 0,
        val pending: List<Pending> = emptyList(),
        val countdown: Int = 0,
        /** Set while a call is on and the agent has asked to stop after it. */
        val pausing: Boolean = false,
        val message: String? = null,
        val sims: List<SimOption> = emptyList(),
        val chosenSim: SimOption? = null,

        // the save dialog
        val editing: Pending? = null,
        val formStatus: CallStatus? = null,
        val formQuality: LeadQuality? = null,
        val formRemarks: String = "",
        val formCallbackAt: Long? = null,
        val saving: Boolean = false,
        val formError: String? = null,

        /** Bumped every second while open so wait-to-save countdowns redraw. */
        val clock: Long = 0L
    ) {
        val roundSize: Int get() = ROUND_SIZE
        val needsQuality: Boolean get() = formStatus == CallStatus.LEAD
        val needsCallback: Boolean get() = formStatus == CallStatus.CALL_LATER
        val canSaveForm: Boolean
            get() {
                val e = editing ?: return false
                return formStatus != null &&
                    (!needsQuality || formQuality != null) &&
                    (!needsCallback || formCallbackAt != null) &&
                    !saving &&
                    e.dwellRemainingSecs <= 0
            }
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var paused = false
    private var foreground = true
    private var nextLead: Lead? = null
    private var dialStartedAt = 0L
    private var attemptConfirmedAt = 0L
    private var runJob: Job? = null
    private var watchdog: Job? = null
    private var keepAlive: Job? = null
    private var ticker: Job? = null
    private var opened = false

    /**
     * Wired up by AppViewModel (which owns the single CallStateTracker.onCallEnded
     * hook): the phone's call state says the watched call has ended.
     */
    fun onTrackerCallEnded(leadId: Long) = onCallEnded(leadId, failed = false)

    // ------------------------------------------------------------------
    // Screen lifecycle
    // ------------------------------------------------------------------

    /**
     * For an agent in progressive mode who is NOT on the progressive screen (app
     * start, or just switched mode): rebuild the "to save" list and keep their
     * claimed leads from timing out.
     */
    fun restore() {
        if (keepAlive == null) {
            // While calls wait to be saved the server must not treat the agent's
            // leads as abandoned (30-minute stale lock), so touch them regularly.
            keepAlive = scope.launch {
                while (true) {
                    delay(KEEP_ALIVE_MS)
                    if (_ui.value.pending.isNotEmpty()) repo.refreshMyLocks()
                }
            }
        }
        scope.launch { loadPending() }
    }

    /** The progressive screen was opened. */
    fun open() {
        if (!opened) {
            opened = true
            ticker = scope.launch {
                while (true) {
                    delay(1000)
                    _ui.update { it.copy(clock = System.currentTimeMillis()) }
                }
            }
        }
        refreshSims()
        restore()
    }

    /** Leaving the screen: stop dialling after the current call and stop redrawing. */
    fun close() {
        pause()
        opened = false
        ticker?.cancel()
        ticker = null
    }

    /** Sign-out or a mode change: forget everything. */
    fun reset() {
        runJob?.cancel()
        watchdog?.cancel()
        keepAlive?.cancel()
        ticker?.cancel()
        keepAlive = null
        ticker = null
        opened = false
        paused = false
        nextLead = null
        _ui.value = Ui()
    }

    fun onForeground(isForeground: Boolean) {
        foreground = isForeground
        if (isForeground && _ui.value.phase == Phase.WAITING_FOREGROUND && !paused) beginNext()
    }

    // ------------------------------------------------------------------
    // SIMs
    // ------------------------------------------------------------------

    /**
     * Picks the SIM calls go out on WITHOUT asking: a chooser dialog would stop the
     * dialling until the agent tapped it. The agent can switch on the screen at any
     * time (it applies from the next call), and that choice is remembered.
     */
    fun refreshSims() {
        val sims = simManager.availableSims()
        // The agent's saved pick wins (it may have just been changed on the queue screen).
        _ui.update { u ->
            u.copy(sims = sims, chosenSim = simManager.defaultOrPhoneSim(sims))
        }
    }

    fun chooseSim(sim: SimOption) {
        simManager.saveDefault(sim)
        _ui.update { it.copy(chosenSim = sim) }
    }

    // ------------------------------------------------------------------
    // Pending list
    // ------------------------------------------------------------------

    /** Rebuilds the "to save" list from the server (after a restart, or on opening the screen). */
    suspend fun loadPending() {
        val calls = (repo.myUnsavedCalls() as? Outcome.Ok)?.value ?: return
        val queue = (repo.myQueue() as? Outcome.Ok)?.value.orEmpty()
        val fromServer = calls.mapNotNull { c ->
            val lead = queue.firstOrNull { it.id == c.leadId }
                ?: (repo.getLead(c.leadId) as? Outcome.Ok)?.value
                ?: return@mapNotNull null
            val local = _ui.value.pending.firstOrNull { it.lead.id == c.leadId }
            Pending(
                lead = lead,
                attemptedAt = c.lastAttemptAt?.let { parseIso(it) } ?: System.currentTimeMillis(),
                // The phone's reading may not have reached the server yet.
                talkSecs = c.talkSecs ?: local?.talkSecs,
                dialSecs = c.dialSecs ?: local?.dialSecs
            )
        }
        _ui.update { u ->
            // A call that ended seconds ago may not be on the server list yet.
            val recentLocal = u.pending.filter { p ->
                fromServer.none { it.lead.id == p.lead.id } &&
                    System.currentTimeMillis() - p.attemptedAt < 120_000L
            }
            val merged = (fromServer + recentLocal).sortedBy { it.attemptedAt }
            u.copy(
                pending = merged,
                dialsThisRound = if (u.phase == Phase.IDLE) merged.size else u.dialsThisRound
            )
        }
    }

    // ------------------------------------------------------------------
    // The dialling loop
    // ------------------------------------------------------------------

    fun start() {
        if (_ui.value.phase == Phase.ON_CALL || runJob?.isActive == true) return
        // Calls are placed and their end noticed through these two permissions;
        // without them progressive dialling can't work safely.
        if (!simManager.hasCallPermission() || !simManager.hasPhoneStatePermission()) {
            setPhase(Phase.STOPPED, "Allow the Phone permissions so calls can be placed and tracked automatically, then press Start.")
            return
        }
        paused = false
        _ui.update { it.copy(message = null, pausing = false) }
        beginNext()
    }

    /** Stops dialling after the current call (immediately, if between calls). */
    fun pause() {
        paused = true
        when (_ui.value.phase) {
            Phase.CLAIMING, Phase.COUNTDOWN -> {
                runJob?.cancel()
                setPhase(Phase.PAUSED)
            }
            Phase.ON_CALL -> _ui.update { it.copy(pausing = true) }
            else -> Unit
        }
    }

    /** Drops the lead that is about to be dialled and moves on to the next one. */
    fun skip() {
        if (_ui.value.phase != Phase.COUNTDOWN) return
        runJob?.cancel()
        nextLead = null
        beginNext()
    }

    private fun setPhase(phase: Phase, message: String? = null) =
        _ui.update { it.copy(phase = phase, message = message) }

    private fun beginNext() {
        runJob?.cancel()
        runJob = scope.launch {
            if (paused) { setPhase(Phase.PAUSED); return@launch }
            if (!foreground) { setPhase(Phase.WAITING_FOREGROUND); return@launch }

            var u = _ui.value
            if (u.dialsThisRound >= ROUND_SIZE) {
                if (u.pending.isNotEmpty()) { setPhase(Phase.SAVE_REQUIRED); return@launch }
                _ui.update { it.copy(dialsThisRound = 0) }
                u = _ui.value
            }
            if (u.pending.size >= ROUND_SIZE) { setPhase(Phase.SAVE_REQUIRED); return@launch }

            val target: Lead = nextLead ?: run {
                _ui.update { it.copy(phase = Phase.CLAIMING, message = null) }
                val claimed = claim() ?: return@launch
                nextLead = claimed
                // Same audit trail as opening a lead in normal mode.
                scope.launch { runCatching { repo.logLeadView(claimed.id) } }
                claimed
            }

            _ui.update { it.copy(current = target, phase = Phase.COUNTDOWN, pausing = false) }
            for (i in COUNTDOWN_SECS downTo 1) {
                _ui.update { it.copy(countdown = i) }
                delay(1000)
                if (paused) { setPhase(Phase.PAUSED); return@launch }   // the lead stays held
                if (!foreground) { setPhase(Phase.WAITING_FOREGROUND); return@launch }
            }
            nextLead = null
            dial(target)
        }
    }

    /** The next lead from the pool, or null after setting the phase that explains why not. */
    private suspend fun claim(): Lead? {
        repeat(2) {
            when (val r = repo.claimNextLead()) {
                is Outcome.Ok -> {
                    val lead = r.value
                    if (lead == null) {
                        setPhase(Phase.NO_LEADS, "No more leads in queue. Contact manager.")
                        return null
                    }
                    return lead
                }
                is Outcome.Err -> {
                    val m = r.message
                    when {
                        m.contains("Wait a moment") -> delay(3000)            // the 3-second claim gap; try again
                        m.contains("Save the outcomes") -> { setPhase(Phase.SAVE_REQUIRED); return null }
                        else -> { setPhase(Phase.STOPPED, m); return null }
                    }
                }
            }
        }
        setPhase(Phase.STOPPED, "Could not get the next lead. Check your connection, then press Start.")
        return null
    }

    private suspend fun dial(lead: Lead) {
        dialStartedAt = System.currentTimeMillis()
        attemptConfirmedAt = dialStartedAt
        _ui.update { it.copy(phase = Phase.ON_CALL, current = lead, countdown = 0, message = null) }

        // Armed BEFORE dialling so the OFFHOOK at dial start isn't missed — this is
        // also how the end of the call is noticed.
        CallStateTracker.arm(app, lead.id)
        when (val res = simManager.placeCall(lead.mobile, _ui.value.chosenSim)) {
            is SimManager.CallResult.Dialled -> Unit
            SimManager.CallResult.OpenedDialer -> {
                setPhase(Phase.STOPPED, "Allow the Phone permission so calls can be placed automatically, then press Start.")
                return
            }
            SimManager.CallResult.InvalidNumber -> {
                _ui.update { it.copy(message = "Skipped a lead with no valid number.") }
                beginNext()
                return
            }
            is SimManager.CallResult.Failed -> {
                setPhase(Phase.STOPPED, res.message)
                return
            }
        }

        // The server-side record that a call was placed — save_disposition() needs it.
        when (val r = repo.logCallAttempt(lead.id)) {
            is Outcome.Ok -> attemptConfirmedAt = System.currentTimeMillis()
            is Outcome.Err -> _ui.update { it.copy(message = r.message) }
        }

        // If the phone never went off-hook the call didn't start (no signal, airplane
        // mode, ...): treat it as an ended call so the lead gets an outcome.
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(NO_OFFHOOK_MS)
            if (_ui.value.phase == Phase.ON_CALL && !CallStateTracker.sawOffhook()) {
                onCallEnded(lead.id, failed = true)
            }
        }
    }

    private fun onCallEnded(leadId: Long, failed: Boolean) {
        val u = _ui.value
        val lead = u.current ?: return
        if (lead.id != leadId || u.phase != Phase.ON_CALL) return
        watchdog?.cancel()

        val dial = CallStateTracker.dialSecs(lead.id)
        val started = dialStartedAt
        _ui.update {
            it.copy(
                pending = it.pending + Pending(lead, attemptConfirmedAt, talkSecs = if (failed) 0 else null, dialSecs = dial),
                dialsThisRound = it.dialsThisRound + 1
            )
        }

        // The call log row appears a moment after hang-up; read the talk time then
        // and report it, exactly as normal mode does after a save.
        scope.launch(Dispatchers.IO) {
            var secs: Int? = if (failed) 0 else null
            var tries = 0
            while (secs == null && tries < 5) {
                delay(if (tries == 0) 1500L else 2000L)
                secs = lastOutgoingCallDurationSecs(app, lead.mobile, started - 5_000L)
                tries++
            }
            withContext(Dispatchers.Main) { setTalk(lead.id, secs) }
            if (secs != null || dial != null) runCatching { repo.reportCallTiming(lead.id, secs, dial) }
        }

        beginNext()
    }

    private fun setTalk(leadId: Long, secs: Int?) = _ui.update { u ->
        u.copy(
            pending = u.pending.map { if (it.lead.id == leadId && it.talkSecs == null) it.copy(talkSecs = secs) else it },
            editing = u.editing?.let { if (it.lead.id == leadId && it.talkSecs == null) it.copy(talkSecs = secs) else it }
        )
    }

    // ------------------------------------------------------------------
    // Saving outcomes
    // ------------------------------------------------------------------

    fun openSave(item: Pending) = _ui.update {
        it.copy(
            editing = item, formStatus = null, formQuality = null,
            formRemarks = "", formCallbackAt = null, formError = null, saving = false
        )
    }

    fun closeSave() = _ui.update { it.copy(editing = null, saving = false, formError = null) }

    fun setStatus(s: CallStatus) = _ui.update {
        it.copy(
            formStatus = s,
            formQuality = if (s == CallStatus.LEAD) it.formQuality else null,
            formCallbackAt = if (s == CallStatus.CALL_LATER) it.formCallbackAt else null,
            formError = null
        )
    }

    fun setQuality(q: LeadQuality) = _ui.update { it.copy(formQuality = q) }
    fun setRemarks(t: String) = _ui.update { it.copy(formRemarks = t.take(2000)) }
    fun setCallbackAt(epochMillis: Long) = _ui.update { it.copy(formCallbackAt = epochMillis) }

    fun save() {
        val u = _ui.value
        val item = u.editing ?: return
        val status = u.formStatus ?: return
        if (u.needsQuality && u.formQuality == null) {
            _ui.update { it.copy(formError = "Choose how strong this lead is.") }
            return
        }
        if (u.needsCallback && u.formCallbackAt == null) {
            _ui.update { it.copy(formError = "Pick a date and time for the callback.") }
            return
        }
        if (u.needsCallback && u.formCallbackAt!! <= System.currentTimeMillis()) {
            _ui.update { it.copy(formError = "The callback time must be in the future.") }
            return
        }

        _ui.update { it.copy(saving = true, formError = null) }
        scope.launch {
            val r = repo.saveDisposition(
                leadId = item.lead.id,
                status = status,
                quality = u.formQuality,
                remarks = u.formRemarks,
                callbackAtIso = u.formCallbackAt?.let { iso(it) },
                simSlot = u.chosenSim?.slotIndex
            )
            when (r) {
                is Outcome.Err -> {
                    if (r.message.contains("is not assigned to you")) {
                        // Waited too long: the lead was released (backend/55). Nothing left to save.
                        removePending(item.lead.id)
                        _ui.update {
                            it.copy(
                                editing = null, saving = false,
                                message = "${item.lead.name} was released because it waited too long to be saved."
                            )
                        }
                        afterPendingChanged()
                    } else {
                        _ui.update { it.copy(saving = false, formError = r.message) }
                    }
                }
                is Outcome.Ok -> {
                    // Same reminder handling as normal mode.
                    CallbackScheduler.cancel(app, item.lead.id)
                    if (status == CallStatus.CALL_LATER && u.formCallbackAt != null) {
                        CallbackScheduler.schedule(app, item.lead.id, item.lead.name, item.lead.mobile, u.formCallbackAt)
                    }
                    removePending(item.lead.id)
                    _ui.update { it.copy(editing = null, saving = false, formError = null) }
                    afterPendingChanged()
                }
            }
        }
    }

    private fun removePending(leadId: Long) =
        _ui.update { u -> u.copy(pending = u.pending.filterNot { it.lead.id == leadId }) }

    /** After a save: once everything is saved, the next round of 10 starts by itself. */
    private fun afterPendingChanged() {
        val u = _ui.value
        if (u.phase == Phase.SAVE_REQUIRED && u.pending.isEmpty()) {
            _ui.update { it.copy(dialsThisRound = 0) }
            if (paused) setPhase(Phase.PAUSED) else beginNext()
        }
    }

    // ------------------------------------------------------------------

    private fun iso(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(epochMillis))

    private fun parseIso(iso: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (p in patterns) {
            try {
                SimpleDateFormat(p, Locale.US).parse(iso)?.let { return it.time }
            } catch (e: Exception) {
                // try the next pattern
            }
        }
        return null
    }

    companion object {
        /** Calls dialled back to back before every outcome must be saved. Matches max_unsaved in backend/60. */
        const val ROUND_SIZE = 10
        const val COUNTDOWN_SECS = 3
        /** The server's save floor is 30s after the attempt (backend/23); a little extra for clock drift. */
        const val DWELL_MS = 33_000L
        private const val NO_OFFHOOK_MS = 25_000L
        private const val KEEP_ALIVE_MS = 5 * 60_000L
    }
}
