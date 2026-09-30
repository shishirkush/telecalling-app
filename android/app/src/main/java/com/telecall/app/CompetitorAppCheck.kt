package com.telecall.app

import android.content.Context
import android.content.pm.PackageManager

/**
 * Checks for a small, fixed, named list of apps agents could be using to
 * divert leads to a competing referral program — never a general inventory
 * of everything installed. AndroidManifest.xml declares each package
 * individually under <queries> (Android 11+ "named package" visibility),
 * which needs no runtime permission and no QUERY_ALL_PACKAGES — that
 * broader permission would reveal every app on the phone and is
 * deliberately not requested. See backend/35_competitor_app_check.sql for
 * where this is reported, and docs/privacy-policy/index.html for the
 * disclosure this check is run under.
 */
private val COMPETITOR_APPS = mapOf(
    "com.gromo.partner" to "GroMo",
    "in.magnetapp" to "Zet",
    "com.mymoneymantra.customer.app" to "MyMoneyMantra"
)

/** Labels of the competitor apps found installed, e.g. ["GroMo", "Zet"] — empty if none. */
fun detectCompetitorApps(context: Context): List<String> {
    val pm = context.packageManager
    return COMPETITOR_APPS.filter { (packageName, _) ->
        try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }.values.toList()
}
