package com.pricetranslator.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

class ExchangeRateRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("exchange_rates", Context.MODE_PRIVATE)

    data class Rate(val base: String, val quote: String, val value: Double, val date: String)

    fun cached(base: String, quote: String): Rate? {
        val key = "$base-$quote"
        val value = prefs.getString("$key-value", null)?.toDoubleOrNull() ?: return null
        val date = prefs.getString("$key-date", null) ?: return null
        return Rate(base, quote, value, date)
    }

    fun fetch(base: String, quote: String): Rate {
        require(base != quote) { "Currencies must be different" }
        val connection = URL("https://api.frankfurter.dev/v2/rate/$base/$quote")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")

        try {
            if (connection.responseCode !in 200..299) {
                error("Exchange-rate service returned ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val value = json.getDouble("rate")
            val date = json.optString("date", LocalDate.now().toString())
            val rate = Rate(base, quote, value, date)
            val key = "$base-$quote"
            prefs.edit()
                .putString("$key-value", value.toString())
                .putString("$key-date", date)
                .apply()
            return rate
        } finally {
            connection.disconnect()
        }
    }
}
