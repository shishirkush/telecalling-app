package com.telecall.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * The hiring message an agent can send to a lead with one tap from the lead
 * screen. It only OPENS the phone's own messaging app with this text and the
 * lead's number filled in — the agent presses Send there — so no SMS
 * permission is needed and nothing is sent in the background. Mirrors
 * JOB_MESSAGE_TEXT in the web app; change both together.
 */
const val JOB_MESSAGE_TEXT =
    "Work from Home opportunity with commission upto Rs 40,000/- .\n\n" +
        "Send whatsapp to 9953552591 to know more...Hurry!!"

fun openJobMessage(context: Context, mobile: String) {
    val number = mobile.filter { it.isDigit() }.takeLast(10)
    if (number.length < 10) {
        Toast.makeText(context, "This record has no valid phone number.", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
        putExtra("sms_body", JOB_MESSAGE_TEXT)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No messaging app found on this phone.", Toast.LENGTH_SHORT).show()
    }
}
