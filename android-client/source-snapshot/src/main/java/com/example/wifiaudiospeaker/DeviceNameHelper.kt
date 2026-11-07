package com.example.wifiaudiospeaker

import android.content.Context
import android.os.Build
import android.provider.Settings

object DeviceNameHelper {

    // 🎯 Master database mapping raw board/model numbers to Commercial Market Names
    private val KNOWN_MARKET_NAMES = mapOf(
        // Lenovo Vibe K5 Lineup
        "A6020a40" to "Lenovo Vibe K5",
        "A6020A40" to "Lenovo Vibe K5",
        "A6020a41" to "Lenovo Vibe K5",
        "A6020a46" to "Lenovo Vibe K5 Plus",
        "A6020" to "Lenovo Vibe K5",
        "K50a40" to "Lenovo K3 Note",
        "K50-T5" to "Lenovo K3 Note",
        "K52e78" to "Lenovo Vibe K5 Note",
        "K53a48" to "Lenovo K6 Note",
        "P2a42" to "Lenovo P2",

        // Samsung Galaxy Lineup
        "SM-M107F" to "Galaxy M10s",
        "SM-M107G" to "Galaxy M10s",
        "SM-M105F" to "Galaxy M10",
        "SM-M205F" to "Galaxy M20",
        "SM-M307F" to "Galaxy M30s",
        "SM-M315F" to "Galaxy M31",
        "SM-A105F" to "Galaxy A10",
        "SM-A505F" to "Galaxy A50",

        // Realme Lineup
        "RMX3850" to "Realme GT 6T",
        "RMX3851" to "Realme GT 6",
        "RMX3360" to "Realme GT Master Edition",
        "RMX3363" to "Realme GT Master Edition"
    )

    fun getDeviceName(context: Context): String {
        val rawModel = Build.MODEL?.trim().orEmpty()
        val rawManufacturer = Build.MANUFACTURER?.trim()?.replaceFirstChar { it.uppercase() } ?: "Android"
        val rawDevice = Build.DEVICE?.trim().orEmpty()
        val rawProduct = Build.PRODUCT?.trim().orEmpty()

        // 1. Direct hardware database lookup
        KNOWN_MARKET_NAMES[rawModel]?.let { return it }
        KNOWN_MARKET_NAMES[rawDevice]?.let { return it }
        KNOWN_MARKET_NAMES[rawProduct]?.let { return it }

        // Wildcard check for Lenovo Vibe K5 family
        if (rawModel.contains("A6020", ignoreCase = true) || rawDevice.contains("A6020", ignoreCase = true)) {
            return if (rawModel.contains("a46", ignoreCase = true)) "Lenovo Vibe K5 Plus" else "Lenovo Vibe K5"
        }

        // 2. Query OEM-specific system properties for marketed device name
        val sysProps = listOf(
            "ro.product.marketname",
            "ro.config.marketing_name",
            "ro.lenovo.series",
            "ro.product.vendor.marketname",
            "ro.product.display"
        )
        for (prop in sysProps) {
            val propVal = getSystemProperty(prop)
            if (!propVal.isNullOrBlank() && !isRawModelNumber(propVal, rawModel, rawDevice, rawProduct)) {
                return if (propVal.contains(rawManufacturer, ignoreCase = true)) propVal else "$rawManufacturer $propVal"
            }
        }

        // 3. Check Settings (Ignore if value is just the raw model number)
        var customName: String? = null
        try {
            customName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) {}

        if (customName.isNullOrBlank() || isRawModelNumber(customName, rawModel, rawDevice, rawProduct)) {
            try {
                customName = Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            } catch (_: Exception) {}
        }

        if (!customName.isNullOrBlank() && !isRawModelNumber(customName, rawModel, rawDevice, rawProduct)) {
            return customName
        }

        // 4. Default Fallback
        return if (rawModel.lowercase().startsWith(rawManufacturer.lowercase())) {
            rawModel
        } else {
            "$rawManufacturer $rawModel"
        }
    }

    private fun isRawModelNumber(name: String, model: String, device: String, product: String): Boolean {
        val clean = name.trim().lowercase()
        return clean.isEmpty() ||
                clean == model.lowercase() ||
                clean == device.lowercase() ||
                clean == product.lowercase() ||
                clean.matches(Regex("^[a-z0-9_-]{4,10}$", RegexOption.IGNORE_CASE))
    }

    private fun getSystemProperty(key: String): String? {
        try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java)
            val str = get.invoke(null, key) as? String
            if (!str.isNullOrBlank()) return str.trim()
        } catch (_: Throwable) {}
        return null
    }
}