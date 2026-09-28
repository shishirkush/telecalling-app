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

/** A bold-title + emoji-bullet WhatsApp pitch for one card. Mirrors CARD_PITCHES in the web app. */
data class CardPitch(val title: String, val bullets: List<String>)

/**
 * The cards an agent can share over WhatsApp — a deliberately short, curated
 * list (not all of cardadda.in). Every bullet in [pitches] is hand-verified
 * against that card's own live page (never cross-copied from a sibling
 * card); see backend data-integrity notes. [load] only confirms a curated
 * card is still live on the site — unlike the old version of this object,
 * it never expands the list with cards the site adds later.
 */
object CardCatalog {
    const val SITE = "https://www.cardadda.in"

    /** Shown first in the picker regardless of sitemap order. */
    private const val PINNED_SLUG = "hdfc-pixel-play"

    private fun pinFirst(cards: List<CardLink>): List<CardLink> =
        cards.sortedByDescending { it.slug == PINNED_SLUG } // stable: keeps the rest in order

    val bundled: List<CardLink> = pinFirst(listOf(
        CardLink("HDFC Bank Pixel Play", "HDFC Bank", "hdfc-pixel-play"),
        CardLink("Flipkart Axis", "Axis Bank", "flipkart-axis"),
        CardLink("Axis Bank Neo Credit Card", "Axis Bank", "axis-neo"),
        CardLink("LIC Axis Bank Signature Credit Card", "Axis Bank", "lic-axis-signature"),
        CardLink("SimplyClick", "SBI Card", "simplyclick"),
        CardLink("Simply Save", "SBI Card", "simply-save"),
        CardLink("HDFC Bank IRCTC", "HDFC Bank", "hdfc-irctc"),
        CardLink("HDFC Bank Millennia", "HDFC Bank", "hdfc-millennia"),
        CardLink("Tata Neu Plus", "HDFC Bank", "tata-neu-plus"),
        CardLink("Legend", "IndusInd", "legend"),
        CardLink("FIRST Millennia", "IDFC First", "first-millennia"),
        CardLink("YES Bank POP-Club", "YES Bank", "yes-bank-pop-club")
    ))

    /** Verified against each card's own cardadda.in page, 9 Sep 2026. */
    val pitches: Map<String, CardPitch> = mapOf(
        "hdfc-pixel-play" to CardPitch(
            "HDFC Bank Pixel Play — ₹500 + GST/yr",
            listOf(
                "💳 5% cashback on any 2 spend packs of your choice (BookMyShow+Zomato, MakeMyTrip+Uber, Blinkit+Reliance Smart Bazaar, Electronics or Fashion)",
                "🛒 3% cashback on Amazon, Flipkart or PayZapp",
                "📱 1% cashback on UPI spends (RuPay variant)",
                "🎨 \"Build Your Own Card\" — pick your billing date, partners & card colour",
                "✅ FD-backed variant available, no income proof needed"
            )
        ),
        "flipkart-axis" to CardPitch(
            "Flipkart Axis — ₹0 joining (limited-period offer)",
            listOf(
                "🛍️ 7.5% cashback on Myntra, capped at ₹4,000/quarter",
                "📦 5% cashback on Flipkart & Cleartrip",
                "💰 4% on other preferred merchants, 1% on everything else",
                "⛽ 1% fuel surcharge waiver",
                "🎁 ₹350 welcome benefit + EMI on Flipkart purchases over ₹2,500"
            )
        ),
        "axis-neo" to CardPitch(
            "Axis Bank Neo — ₹0 joining (select channels, currently)",
            listOf(
                "🍔 Flat ₹120 off on Zomato (code AXISNEO, ₹499+ orders, twice a month)",
                "🛒 10% off on Blinkit, up to ₹250 (₹750+ orders, once a month)",
                "📱 5% off mobile recharge, broadband & DTH via Paytm",
                "✈️ 7% off Cleartrip flights, 18% off Cleartrip hotels, monthly",
                "🎁 100% cashback up to ₹300 on your first utility bill payment"
            )
        ),
        "lic-axis-signature" to CardPitch(
            "LIC Axis Bank Signature — ₹0 for life",
            listOf(
                "💯 Lifetime free — ₹0, forever",
                "🎯 1 Reward Point per ₹100 on all spends, 2X on LIC premiums & forex",
                "🎁 100% cashback up to ₹300 on your first utility bill payment",
                "🛫 8 complimentary domestic lounge visits a year",
                "🛡️ Lost-card liability cover up to your full credit limit"
            )
        ),
        "simplyclick" to CardPitch(
            "SBI SimplyClick — ₹499 + GST/yr",
            listOf(
                "🛒 10X Reward Points across 11 partners (Myntra, Swiggy, BookMyShow, Netmeds & more)",
                "💻 5X Reward Points on other online spends",
                "🎁 ₹500 Amazon voucher on paying the annual fee",
                "🎫 ₹2,000 Cleartrip/Yatra voucher at ₹1L & ₹2L annual online spend",
                "⛽ 1% fuel surcharge waiver"
            )
        ),
        "simply-save" to CardPitch(
            "SBI Simply Save — ₹499 + GST/yr",
            listOf(
                "🍽️ 10 Reward Points per ₹150 on dining, groceries, movies & department stores",
                "🎁 2,000 bonus points (~₹500) on ₹2,000 spend within the first 60 days",
                "⛽ 1% fuel surcharge waiver"
            )
        ),
        "hdfc-irctc" to CardPitch(
            "HDFC Bank IRCTC — Lifetime free right now",
            listOf(
                "✅ ₹0 annual fee (limited-period offer)",
                "🚆 5 Reward Points per ₹100 on IRCTC ticketing website & Rail Connect App bookings",
                "💰 Extra 5% cashback on train bookings via HDFC Bank SmartBuy",
                "🎟️ IRCTC executive lounge access",
                "🎁 ₹500 gift voucher on activation, plus ₹500 every quarter on ₹30,000+ spend",
                "⚡ 1% transaction-charge waiver on IRCTC bookings"
            )
        ),
        "hdfc-millennia" to CardPitch(
            "HDFC Bank Millennia — ₹1,000 + GST/yr",
            listOf(
                "🛍️ 5% cashback on Amazon, Flipkart, Myntra, Swiggy, Zomato, Uber, BookMyShow & more",
                "💳 1% cashback on all other spends",
                "🎁 ₹1,000 voucher OR 1 lounge visit per quarter on ₹1L+ quarterly spend (up to 4/year)",
                "🛒 Up to 10% extra cashback across 200+ brands via SmartBuy/PayZapp",
                "⛽ 1% fuel surcharge waiver"
            )
        ),
        "tata-neu-plus" to CardPitch(
            "Tata Neu Plus — ₹499 + GST/yr",
            listOf(
                "🛍️ 2% NeuCoins on Tata Neu & partner Tata Brand spends",
                "📱 Up to 1% back on UPI via your Tata Neu UPI ID",
                "🎁 499 NeuCoins welcome benefit on your first transaction",
                "🛫 A complimentary domestic lounge voucher every quarter (up to 4/year) on ₹50,000+ spend"
            )
        ),
        "legend" to CardPitch(
            "IndusInd Legend — ₹0 right now, no renewal fee",
            listOf(
                "💯 Lifetime free — ₹0 renewal, ever",
                "🎯 1 Reward Point per ₹100 on weekdays, 2X on weekends",
                "🎬 One free BookMyShow movie ticket every month, up to ₹200",
                "🌍 1.8% discounted forex mark-up on international spends",
                "🛡️ \"Total Protect\" cover against unauthorised/counterfeit fraud"
            )
        ),
        "first-millennia" to CardPitch(
            "IDFC FIRST Millennia — ₹0 for life",
            listOf(
                "💯 Lifetime free — ₹0, forever",
                "🍽️ 10X Reward Points on dining, travel and your birthday",
                "🌍 Zero forex mark-up on international spends",
                "🎁 ₹1,500 in welcome benefits",
                "🚂 4 complimentary railway lounge visits a quarter"
            )
        ),
        "yes-bank-pop-club" to CardPitch(
            "YES Bank POP-Club — ₹0 right now (Lifetime Free offer)",
            listOf(
                "💻 10 POPcoins per ₹100 spent online, 2 per ₹100 on everything else",
                "💰 POPcoins redeem at up to ₹1 each",
                "⛽ 1% fuel surcharge waiver",
                "🛍️ Partner rewards at Blinkit, Rapido, Zomato, cult.fit & more"
            )
        )
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val slugPattern = Regex("""/cards/([a-z0-9-]+)</loc>""")

    /** Confirms each curated card is still live; never adds cards beyond [bundled]. */
    suspend fun load(): List<CardLink> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$SITE/sitemap.xml").build()
            val body = http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                r.body?.string()
            } ?: return@withContext bundled
            val live = slugPattern.findAll(body).map { it.groupValues[1] }.toSet()
            if (live.isEmpty()) return@withContext bundled
            val stillLive = bundled.filter { it.slug in live }
            if (stillLive.isEmpty()) bundled else pinFirst(stillLive)
        } catch (e: Exception) {
            bundled
        }
    }
}
