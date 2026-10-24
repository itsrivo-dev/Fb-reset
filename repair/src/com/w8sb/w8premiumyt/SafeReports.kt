package com.w8sb.w8premiumyt

import android.Manifest
import android.accounts.AccountManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ExportData(val title: String, val text: String, val extension: String = "txt", val mimeType: String = "text/plain")

/** Owner-initiated report producers. None of these methods is called automatically at app startup. */
object SafeReports {
    private fun date(value: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(value))

    private fun hasPermission(context: Context, permission: String): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) {
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } else {
            context.packageManager.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun installationId(context: Context): String {
        val p = context.getSharedPreferences("w8safe", Context.MODE_PRIVATE)
        val existing = p.getString("installation_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        p.edit().putString("installation_id", created).apply()
        return created
    }

    @JvmStatic
    fun batteryStatus(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "Battery status is unavailable."
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100.0f / scale) else -1.0f
        val status = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }
        val plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Battery / unknown"
        }
        return buildString {
            append("Battery: ")
            if (percent >= 0) append(String.format(Locale.US, "%.1f%%", percent)) else append("Unknown")
            append("\nStatus: ").append(status)
            append("\nPower source: ").append(plugged)
        }
    }

    @JvmStatic
    fun networkStatus(context: Context): String {
        val out = StringBuilder()
        try {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = connectivity.activeNetworkInfo
            if (active == null) {
                out.append("Active network: none\n")
            } else {
                out.append("Active network: ").append(active.typeName)
                    .append("\nState: ").append(active.state)
                    .append("\nConnected: ").append(active.isConnected)
                    .append("\nRoaming: ").append(active.isRoaming).append('\n')
            }
        } catch (e: Exception) {
            out.append("Connectivity error: ").append(e.javaClass.simpleName).append('\n')
        }
        try {
            val addresses = ArrayList<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (network in java.util.Collections.list(interfaces)) {
                if (network.isLoopback || !network.isUp) continue
                for (address in java.util.Collections.list(network.inetAddresses)) {
                    if (!address.isLoopbackAddress && address is Inet4Address) addresses.add(address.hostAddress ?: continue)
                }
            }
            out.append("Local IPv4: ").append(if (addresses.isEmpty()) "none" else addresses.distinct().joinToString(", ")).append('\n')
        } catch (e: Exception) {
            out.append("Local IP error: ").append(e.javaClass.simpleName).append('\n')
        }
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            out.append("Wi-Fi enabled: ").append(wifi.isWifiEnabled).append('\n')
            val hasLocation = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (hasLocation) {
                val ssid = wifi.connectionInfo?.ssid
                out.append("Wi-Fi SSID: ").append(if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") "unavailable" else ssid).append('\n')
            } else {
                out.append("Wi-Fi SSID: hidden until location permission is approved\n")
            }
        } catch (e: Exception) {
            out.append("Wi-Fi error: ").append(e.javaClass.simpleName).append('\n')
        }
        return out.toString().trim()
    }

    @JvmStatic
    fun deviceStatus(context: Context): String = buildString {
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append("\nAndroid: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')')
        append("\nBuild: ").append(Build.DISPLAY)
        append("\nApp installation ID: ").append(installationId(context))
        append("\n\n").append(batteryStatus(context))
        append("\n\n").append(networkStatus(context))
    }

    @JvmStatic
    fun bestLocation(context: Context): Location? {
        if (!hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            throw SecurityException("Location permission has not been granted.")
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (provider in manager.getProviders(true)) {
            try {
                val location = manager.getLastKnownLocation(provider) ?: continue
                if (best == null || location.time > best!!.time) best = location
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
        return best
    }

    @JvmStatic
    fun locationReport(context: Context): String {
        val location = bestLocation(context) ?: throw IllegalStateException("No last known location is available. Enable location services and try again.")
        return formatLocation(location)
    }

    @JvmStatic
    fun formatLocation(location: Location): String = buildString {
        append("Location fix time: ").append(date(location.time))
        append("\nProvider: ").append(location.provider)
        append("\nLatitude: ").append(location.latitude)
        append("\nLongitude: ").append(location.longitude)
        if (location.hasAccuracy()) append("\nAccuracy: ").append(location.accuracy).append(" m")
        append("\nAge when reported: ").append((System.currentTimeMillis() - location.time).coerceAtLeast(0) / 1000).append(" seconds")
        append("\nMap: https://maps.google.com/?q=").append(location.latitude).append(',').append(location.longitude)
    }

    @JvmStatic
    fun sms(context: Context): ExportData {
        val out = StringBuilder("Owner-approved SMS export — latest 50 records\n\n")
        var count = 0
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("address", "body", "date", "type"), null, null, "date DESC"
        ) ?: throw IllegalStateException("SMS provider is unavailable")
        cursor.use {
            while (it.moveToNext() && count < 50) {
                out.append("Address: ").append(it.getString(0) ?: "Unknown")
                    .append("\nDate: ").append(date(it.getLong(2)))
                    .append("\nType: ").append(it.getInt(3))
                    .append("\nMessage: ").append(it.getString(1) ?: "")
                    .append("\n------------------------\n")
                count++
            }
        }
        out.append("\nRecords: ").append(count)
        return ExportData("SMS backup (latest $count)", out.toString())
    }

    @JvmStatic
    fun contacts(context: Context): ExportData {
        val out = StringBuilder("Owner-approved contacts export\n\n")
        var count = 0
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf("display_name", "data1", "data2"), null, null, "display_name ASC"
        ) ?: throw IllegalStateException("Contacts provider is unavailable")
        cursor.use {
            while (it.moveToNext()) {
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, it.getInt(2), "Other")
                out.append("Name: ").append(it.getString(0) ?: "Unknown")
                    .append("\nNumber: ").append(it.getString(1) ?: "")
                    .append("\nType: ").append(label)
                    .append("\n------------------------\n")
                count++
            }
        }
        out.append("\nPhone records: ").append(count)
        return ExportData("Contacts backup ($count phone records)", out.toString())
    }

    @JvmStatic
    fun callHistory(context: Context): ExportData {
        val out = StringBuilder("Owner-approved call history — latest 100 records\n\n")
        var count = 0
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf("number", "type", "date", "duration", "name"), null, null, "date DESC"
        ) ?: throw IllegalStateException("Call-history provider is unavailable")
        cursor.use {
            while (it.moveToNext() && count < 100) {
                val type = when (it.getInt(1)) {
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    CallLog.Calls.REJECTED_TYPE -> "Rejected"
                    else -> "Other (${it.getInt(1)})"
                }
                out.append("Name: ").append(it.getString(4) ?: "Unknown")
                    .append("\nNumber: ").append(it.getString(0) ?: "Unknown")
                    .append("\nType: ").append(type)
                    .append("\nDate: ").append(date(it.getLong(2)))
                    .append("\nDuration: ").append(it.getLong(3)).append(" seconds")
                    .append("\n------------------------\n")
                count++
            }
        }
        out.append("\nRecords: ").append(count)
        return ExportData("Call history (latest $count)", out.toString())
    }

    @JvmStatic
    fun calendar(context: Context): ExportData {
        val out = StringBuilder("Owner-approved calendar export\n\n")
        var count = 0
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf("title", "dtstart", "dtend", "eventLocation", "calendar_displayName"),
            null, null, "dtstart ASC"
        ) ?: throw IllegalStateException("Calendar provider is unavailable")
        cursor.use {
            while (it.moveToNext()) {
                val end = if (it.isNull(2)) "Unspecified" else date(it.getLong(2))
                out.append("Title: ").append(it.getString(0) ?: "Untitled")
                    .append("\nCalendar: ").append(it.getString(4) ?: "Unknown")
                    .append("\nStart: ").append(date(it.getLong(1)))
                    .append("\nEnd: ").append(end)
                    .append("\nLocation: ").append(it.getString(3) ?: "")
                    .append("\n------------------------\n")
                count++
            }
        }
        out.append("\nEvents: ").append(count)
        return ExportData("Calendar export ($count events)", out.toString())
    }

    @JvmStatic
    fun installedApps(context: Context): ExportData {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA).sortedBy { pm.getApplicationLabel(it).toString().lowercase(Locale.US) }
        val out = StringBuilder("Owner-approved installed application inventory\nAndroid 11+ may omit packages not visible to this app.\n\n")
        for (app in apps) {
            val label = pm.getApplicationLabel(app).toString()
            val info = try { pm.getPackageInfo(app.packageName, 0) } catch (_: Exception) { null }
            out.append(label).append(" (").append(app.packageName).append(')')
                .append("\nVersion: ").append(info?.versionName ?: "Unknown")
                .append("\nFirst installed: ").append(info?.firstInstallTime?.let { date(it) } ?: "Unknown")
                .append("\nLast updated: ").append(info?.lastUpdateTime?.let { date(it) } ?: "Unknown")
                .append("\n------------------------\n")
        }
        out.append("\nApplications: ").append(apps.size)
        return ExportData("Installed applications (${apps.size})", out.toString())
    }

    @JvmStatic
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow("android:get_usage_stats", Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    @JvmStatic
    fun usageStats(context: Context): ExportData {
        if (!hasUsageAccess(context)) throw SecurityException("Usage Access has not been enabled.")
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 24L * 60L * 60L * 1000L, end)
            .sortedByDescending { it.totalTimeInForeground }
        val out = StringBuilder("Owner-approved usage report — previous 24 hours\n\n")
        for (item in stats) {
            out.append("Package: ").append(item.packageName)
                .append("\nForeground: ").append(item.totalTimeInForeground / 1000).append(" seconds")
                .append("\nLast used: ").append(date(item.lastTimeUsed))
                .append("\n------------------------\n")
        }
        out.append("\nPackages: ").append(stats.size)
        return ExportData("Usage statistics (${stats.size} packages)", out.toString())
    }

    @JvmStatic
    fun accounts(context: Context): ExportData {
        val manager = context.getSystemService(Context.ACCOUNT_SERVICE) as AccountManager
        val accounts = manager.accounts
        val out = StringBuilder("Owner-approved account inventory\n\n")
        for (account in accounts) out.append("Account: ").append(account.name).append("\nType: ").append(account.type).append("\n------------------------\n")
        out.append("\nAccounts: ").append(accounts.size)
        return ExportData("Account inventory (${accounts.size})", out.toString())
    }

    @JvmStatic
    fun wifiBluetooth(context: Context): ExportData {
        val out = StringBuilder("Owner-approved Wi-Fi and Bluetooth report\n\n")
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val networks = wifi.configuredNetworks.orEmpty()
            out.append("Saved Wi-Fi networks: ").append(networks.size).append('\n')
            for (network in networks) out.append("SSID: ").append(network.SSID ?: "Unknown")
                .append("\nBSSID: ").append(network.BSSID ?: "Unavailable").append("\n------------------------\n")
        } catch (e: Exception) {
            out.append("Wi-Fi unavailable: ").append(e.javaClass.simpleName).append('\n')
        }
        out.append('\n')
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                out.append("Bluetooth: not available")
            } else {
                val devices = adapter.bondedDevices.orEmpty()
                out.append("Bluetooth enabled: ").append(adapter.isEnabled)
                    .append("\nBonded devices: ").append(devices.size).append('\n')
                for (device in devices) out.append("Name: ").append(device.name ?: "Unknown")
                    .append("\nAddress: ").append(device.address ?: "Unavailable").append("\n------------------------\n")
            }
        } catch (e: Exception) {
            out.append("Bluetooth unavailable: ").append(e.javaClass.simpleName)
        }
        return ExportData("Wi-Fi and Bluetooth report", out.toString())
    }

    @JvmStatic
    fun publicIp(): String {
        val connection = URL("https://api.ipify.org?format=text").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Public IP service returned HTTP $code")
            val value = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(65)
                while (true) {
                    val remaining = 65 - output.size()
                    if (remaining <= 0) throw IllegalStateException("Public IP service response exceeded 64 bytes")
                    val count = input.read(buffer, 0, remaining)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), Charsets.UTF_8).trim()
            }
            if (value.isBlank() || value.length > 64) throw IllegalStateException("Public IP service returned an invalid response")
            return "Public IP (reported by api.ipify.org): $value"
        } finally {
            connection.disconnect()
        }
    }

}
