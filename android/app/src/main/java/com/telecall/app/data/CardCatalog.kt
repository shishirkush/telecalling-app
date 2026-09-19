package com.telecall.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One cardadda.in card page the agent can send to a customer. */
data class CardLink(val name: String, val issuer: String, val slug: String) {
    val url: String get() = "${CardCatalog.SITE}/cards/$slug"
}

/**
 * The cards an agent can share over WhatsApp. Names and slugs are copied from
 * cardadda.in's own card data; the live sitemap is the source of truth for
 * which pages exist, so [load] drops any bundled card the site no longer
 * lists and appends any new page it does. If the sitemap can't be reached the
 * bundled list is used as-is.
 */
object CardCatalog {
    const val SITE = "https://www.cardadda.in"

    val bundled: List<CardLink> = listOf(
        CardLink("Flipkart Axis", "Axis Bank", "flipkart-axis"),
        CardLink("Airtel Axis Bank Credit Card", "Axis Bank", "airtel-axis"),
        CardLink("IndianOil Axis Bank Credit Card", "Axis Bank", "indianoil-axis"),
        CardLink("LIC Axis Bank Signature Credit Card", "Axis Bank", "lic-axis-signature"),
        CardLink("SimplyClick", "SBI Card", "simplyclick"),
        CardLink("Simply Save", "SBI Card", "simply-save"),
        CardLink("SBI Card PRIME", "SBI Card", "sbi-card-prime"),
        CardLink("SBI Card ELITE", "SBI Card", "sbi-card-elite"),
        CardLink("HDFC Bank Pixel Go", "HDFC Bank", "hdfc-pixel-go"),
        CardLink("HDFC Bank Pixel Play", "HDFC Bank", "hdfc-pixel-play"),
        CardLink("Marriott Bonvoy", "HDFC Bank", "marriott-bonvoy"),
        CardLink("Tata Neu Plus", "HDFC Bank", "tata-neu-plus"),
        CardLink("Tata Neu Infinity", "HDFC Bank", "tata-neu-infinity"),
        CardLink("HDFC Bank IRCTC", "HDFC Bank", "hdfc-irctc"),
        CardLink("Legend", "IndusInd", "legend"),
        CardLink("AU Altura+", "AU Small Finance Bank", "au-altura-plus"),
        CardLink("AU Vetta", "AU Small Finance Bank", "au-vetta"),
        CardLink("FIRST Millennia", "IDFC First", "first-millennia"),
        CardLink("Select", "IDFC First", "select"),
        CardLink("First Wealth", "IDFC First", "first-wealth"),
        CardLink("BOBCARD Uni GoldX", "Bank of Baroda", "bobcard-uni-goldx"),
        CardLink("YES Bank POP-Club", "YES Bank", "yes-bank-pop-club"),
        CardLink("YES Bank Wellness", "YES Bank", "yes-bank-wellness"),
        CardLink("YES Bank ACE", "YES Bank", "yes-bank-ace"),
        CardLink("Scapia Federal Bank Credit Card", "Federal Bank", "scapia-federal-bank"),
        CardLink("Jupiter Edge+", "CSB Bank", "jupiter-edge-plus"),
        CardLink("RBL Shoprite", "RBL Bank", "rbl-shoprite")
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val slugPattern = Regex("""/cards/([a-z0-9-]+)</loc>""")

    suspend fun load(): List<CardLink> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$SITE/sitemap.xml").build()
            val body = http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                r.body?.string()
            } ?: return@withContext bundled
            val live = slugPattern.findAll(body).map { it.groupValues[1] }.toList()
            if (live.isEmpty()) return@withContext bundled
            val known = bundled.associateBy { it.slug }
            live.map { slug -> known[slug] ?: CardLink(prettify(slug), "", slug) }
        } catch (e: Exception) {
            bundled
        }
    }

    private fun prettify(slug: String): String =
        slug.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
