package com.xcloud.tclnative.ui

import okhttp3.HttpUrl
import java.util.Locale

/** Public Xbox checkout route used by its web client, with sign-in handled by the browser. */
internal fun streamingHoursPurchaseUrl(locale: Locale, plan: String?): String {
    val market = locale.takeIf { it.country.matches(Regex("[A-Z]{2}")) }?.toLanguageTag() ?: "en-US"
    val partner = when (plan) {
        "ULTIMATE" -> "edgewater_xgpusub"
        "PREMIUM", "STANDARD" -> "edgewater_xgppsub"
        "ESSENTIAL", "CORE" -> "edgewater_xgpesub"
        else -> "edgewater_xgpnosub"
    }
    return HttpUrl.Builder().scheme("https").host("www.xbox.com")
        .addPathSegment(market).addPathSegments("external/streaming-hours")
        .addQueryParameter("partnerid", partner).addQueryParameter("campaignId", "CloudGaming.Web")
        .addQueryParameter("clientType", "XEWeb").addQueryParameter("v", "v2")
        .addQueryParameter("t", "dark").build().toString()
}
