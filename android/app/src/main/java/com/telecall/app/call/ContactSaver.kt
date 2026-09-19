package com.telecall.app.call

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.telecall.app.AppViewModel

/**
 * Saves a lead into the phone's own Contacts as "<first name> - TC" (or
 * "<mobile> - TC" when the CRM has no name yet) right before WhatsApp opens
 * to their number — otherwise WhatsApp's chat list only shows a bare
 * number, indistinguishable from any other unsaved chat. "TC" = Telecalling,
 * so the agent's own contacts stay visually separate from leads.
 */
object ContactSaver {
    private const val TAG = "ContactSaver"
    const val SUFFIX = " - TC"

    fun hasPermissions(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * No-ops quietly without both permissions granted — WhatsApp still opens
     * to the right number regardless, same tolerance as the rest of this
     * app's permission handling (see the manifest's own note on this).
     *
     * Idempotent by design rather than by remembering what it's already
     * done: it looks the number up first (CONTENT_FILTER_URI, which
     * normalizes formatting the way phone dialers do) and skips the insert
     * if any contact already has it — whether that's a previous run of
     * this same feature or something the agent saved themselves. It never
     * renames or overwrites an existing contact.
     */
    fun saveLeadContact(context: Context, leadName: String, mobile: String) {
        if (!hasPermissions(context)) {
            Log.w(TAG, "saveLeadContact: contacts permission not granted, skipping")
            return
        }
        val cleanedMobile = mobile.filter { it.isDigit() || it == '+' }
        if (cleanedMobile.isBlank()) return

        try {
            if (findExistingContact(context, cleanedMobile)) return

            val firstName = leadName.trim()
                .takeIf { it.isNotBlank() && it != AppViewModel.UNKNOWN_NAME }
                ?.substringBefore(' ')
                ?: cleanedMobile
            val displayName = "$firstName$SUFFIX"

            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, cleanedMobile)
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    )
                    .build()
            )
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.i(TAG, "Saved contact \"$displayName\" for $cleanedMobile")
        } catch (e: Exception) {
            Log.e(TAG, "saveLeadContact failed for $cleanedMobile", e)
        }
    }

    private fun findExistingContact(context: Context, mobile: String): Boolean {
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(mobile)
        )
        context.contentResolver.query(
            uri, arrayOf(ContactsContract.CommonDataKinds.Phone._ID), null, null, null
        )?.use { cursor -> return cursor.moveToFirst() }
        return false
    }
}
