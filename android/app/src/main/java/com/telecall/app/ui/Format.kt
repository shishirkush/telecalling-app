package com.telecall.app.ui

import androidx.compose.ui.graphics.Color
import com.telecall.app.BuildConfig
import com.telecall.app.data.CallStatus
import com.telecall.app.ui.theme.StatusCallLater
import com.telecall.app.ui.theme.StatusLead
import com.telecall.app.ui.theme.StatusNoAnswer
import com.telecall.app.ui.theme.StatusNotInterested
import com.telecall.app.ui.theme.StatusSwitchedOff
import com.telecall.app.ui.theme.StatusWrongNumber
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val indiaLocale = Locale.Builder().setLanguage("en").setRegion("IN").build()

/** ₹2,50,000 — Indian digit grouping, no decimals. */
fun formatRupees(amount: Double?): String {
    if (amount == null) return "—"
    val nf = NumberFormat.getCurrencyInstance(indiaLocale).apply {
        maximumFractionDigits = 0
    }
    return nf.format(amount)
}

/** "1988-04-30" -> "30 Apr 1988" */
fun formatDob(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
        if (parsed == null) iso else SimpleDateFormat("dd MMM yyyy", Locale.US).format(parsed)
    } catch (e: Exception) {
        iso
    }
}

/** Timestamps coming back from Postgres, rendered for a human. */
fun formatTimestamp(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss"
    )
    for (p in patterns) {
        try {
            val d = SimpleDateFormat(p, Locale.US).parse(iso)
            if (d != null) return SimpleDateFormat("dd MMM, h:mm a", Locale.US).format(d)
        } catch (e: Exception) {
            // try the next pattern
        }
    }
    return iso
}

/** Same parsing tolerance as [formatTimestamp], for the "is this due yet" check. */
fun isPast(iso: String?): Boolean {
    if (iso.isNullOrBlank()) return false
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss"
    )
    for (p in patterns) {
        try {
            val d = SimpleDateFormat(p, Locale.US).parse(iso)
            if (d != null) return d.before(Date())
        } catch (e: Exception) {
            // try the next pattern
        }
    }
    return false
}

fun formatEpoch(millis: Long?): String =
    if (millis == null) "Not set"
    else SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.US).format(Date(millis))

/** "9876543210" -> "98765 43210" for readability on the call button. */
fun formatMobile(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return when {
        digits.length == 10 -> "${digits.take(5)} ${digits.drop(5)}"
        digits.length == 12 && digits.startsWith("91") ->
            "+91 ${digits.drop(2).take(5)} ${digits.drop(7)}"
        else -> raw
    }
}

/**
 * Masking is controlled by the MASK_SENSITIVE build flag. It is currently
 * false — agents see full PAN, DOB, income and credit limit. Flipping the
 * flag in app/build.gradle.kts turns masking on everywhere at once.
 */
fun maskPan(pan: String?): String {
    if (pan.isNullOrBlank()) return "—"
    if (!BuildConfig.MASK_SENSITIVE) return pan.uppercase()
    // ABCPS1234K -> ABCXX1234X : enough for the customer to confirm, not
    // enough to reuse the number elsewhere.
    return if (pan.length == 10) {
        "${pan.take(3)}XX${pan.substring(5, 9)}X".uppercase()
    } else "XXXXXXXXXX"
}

fun maskDob(iso: String?): String =
    if (!BuildConfig.MASK_SENSITIVE) formatDob(iso)
    else if (iso.isNullOrBlank()) "—" else "••/••/${iso.take(4)}"

fun maskAmount(amount: Double?): String =
    if (!BuildConfig.MASK_SENSITIVE) formatRupees(amount) else "••••••"

fun maskAddress(address: String?): String {
    if (address.isNullOrBlank()) return "—"
    if (!BuildConfig.MASK_SENSITIVE) return address
    // Keep the PIN code so the agent can still confirm locality, and drop
    // the street detail that would identify the doorstep.
    val pin = Regex("\\b\\d{6}\\b").findAll(address).lastOrNull()?.value
    return if (pin != null) "•••••• $pin" else "••••••"
}

fun statusColor(status: CallStatus?): Color = when (status) {
    CallStatus.LEAD -> StatusLead
    CallStatus.CALL_LATER -> StatusCallLater
    CallStatus.NOT_INTERESTED -> StatusNotInterested
    CallStatus.WRONG_NUMBER -> StatusWrongNumber
    CallStatus.SWITCHED_OFF -> StatusSwitchedOff
    CallStatus.NO_ANSWER -> StatusNoAnswer
    null -> StatusSwitchedOff
}
