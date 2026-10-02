package com.telecall.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

/**
 * Seconds the phone itself recorded for the most recent OUTGOING call to
 * [number] placed at or after [sinceMillis] — null if READ_CALL_LOG isn't
 * granted, no matching entry exists yet, or anything goes wrong.
 *
 * Deliberately narrow: the query is filtered to outgoing calls since this
 * lead's Call tap, only the first matching row is read, and only its
 * DURATION column ever leaves this function. No other call-log entry, and
 * no number, is stored or sent anywhere. See backend/38_call_duration.sql.
 */
fun lastOutgoingCallDurationSecs(context: Context, number: String, sinceMillis: Long): Int? {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
        PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    val wanted = number.filter { it.isDigit() }.takeLast(10)
    if (wanted.length < 10) return null
    return try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION),
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?",
            arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), sinceMillis.toString()),
            "${CallLog.Calls.DATE} DESC"
        )?.use { c ->
            val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
            val durIdx = c.getColumnIndex(CallLog.Calls.DURATION)
            while (c.moveToNext()) {
                val logged = c.getString(numIdx)?.filter { it.isDigit() }?.takeLast(10)
                if (logged == wanted) return c.getInt(durIdx)
            }
            null
        }
    } catch (e: Exception) {
        null
    }
}
