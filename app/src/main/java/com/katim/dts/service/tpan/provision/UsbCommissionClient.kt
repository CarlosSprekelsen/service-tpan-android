package com.katim.dts.service.tpan.provision

import android.net.Network
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Calls the Hub USB commissioning endpoint over the active USB network.
 */
class UsbCommissionClient {
    companion object {
        private const val TAG = "UsbCommissionClient"
        private const val COMMISSION_URL = "http://192.168.101.35:8002/api/v1/commission/usb"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
    }

    @Throws(IOException::class)
    fun commission(network: Network, localBtMac: String): UsbCommissionRecord {
        val url = URL(COMMISSION_URL)
        val connection = network.openConnection(url) as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            val body = JSONObject().apply {
                put("btMac", localBtMac)
            }.toString()

            connection.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val responseBody = stream.bufferedReader().use { it.readText() }
            if (responseCode !in 200..299) {
                throw IOException("Commissioning failed: HTTP $responseCode $responseBody")
            }

            val envelope = JSONObject(responseBody)
            if (envelope.optString("result") != "ok") {
                throw IOException("Commissioning returned error envelope: $responseBody")
            }

            val data = envelope.getJSONObject("data")
            val record = UsbCommissionRecord.fromJson(data.toString())
            val errors = UsbCommissionRecord.validate(record)
            if (errors.isNotEmpty()) {
                throw IOException("Invalid commission response: ${errors.joinToString("; ")}")
            }

            Log.i(TAG, "Commissioned role=${record.localEud.role} hub=${record.hub.btMac}")
            return record
        } finally {
            connection.disconnect()
        }
    }
}

