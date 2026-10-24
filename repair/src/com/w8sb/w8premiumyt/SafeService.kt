package com.w8sb.w8premiumyt

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.provider.Settings
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Visible, bounded service/boot/Accessibility behavior used by the original component entry points. */
object SafeService {
    private const val PREFS = "w8safe"
    private const val COMMANDS = "commands_enabled"
    private const val LIVE = "live_location_enabled"
    private const val LAST_UPDATE = "telegram_last_update"
    private const val NOTIFICATION_ID = 41023
    private const val CHANNEL = "device_management"
    private const val ACTION_PREFIX = "com.w8sb.w8premiumyt.action."
    private const val ACTION_COMMANDS = ACTION_PREFIX + "COMMANDS"
    private const val ACTION_LIVE = ACTION_PREFIX + "LIVE_LOCATION"
    private const val ACTION_STOP = ACTION_PREFIX + "STOP"
    private val running = AtomicBoolean(false)
    @Volatile private var pollThread: Thread? = null
    @Volatile private var liveThread: Thread? = null
    @Volatile private var accessibility = WeakReference<AccessibilityService>(null)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic fun commandsEnabled(context: Context): Boolean = prefs(context).getBoolean(COMMANDS, false)
    @JvmStatic fun liveEnabled(context: Context): Boolean = prefs(context).getBoolean(LIVE, false)

    @JvmStatic
    fun startCommands(context: Context): Boolean {
        if (!TelegramManager.isConfigured(context)) return false
        // Every new explicit session establishes a fresh baseline; commands sent while stopped stay discarded.
        prefs(context).edit().remove(LAST_UPDATE).putBoolean(COMMANDS, true).commit()
        return try {
            startService(context, ACTION_COMMANDS)
            true
        } catch (_: Exception) {
            prefs(context).edit().putBoolean(COMMANDS, false).commit()
            false
        }
    }

    @JvmStatic
    fun startLiveLocation(context: Context): Boolean {
        if (!TelegramManager.isConfigured(context)) return false
        prefs(context).edit().putBoolean(LIVE, true).commit()
        return try {
            startService(context, ACTION_LIVE)
            true
        } catch (_: Exception) {
            prefs(context).edit().putBoolean(LIVE, false).commit()
            false
        }
    }

    @JvmStatic
    fun stopCommands(context: Context) {
        prefs(context).edit().putBoolean(COMMANDS, false).commit()
        if (liveEnabled(context)) startService(context, ACTION_LIVE) else stopWork(context)
    }

    @JvmStatic
    fun stopLiveLocation(context: Context) {
        prefs(context).edit().putBoolean(LIVE, false).commit()
        if (commandsEnabled(context)) startService(context, ACTION_COMMANDS) else stopWork(context)
    }

    @JvmStatic
    fun stopWork(context: Context) {
        prefs(context).edit().putBoolean(COMMANDS, false).putBoolean(LIVE, false).commit()
        val intent = serviceIntent(context).setAction(ACTION_STOP)
        try {
            if (running.get()) context.startService(intent) else context.stopService(serviceIntent(context))
        } catch (_: Exception) {
            context.stopService(serviceIntent(context))
        }
    }

    private fun serviceIntent(context: Context): Intent = Intent().setClassName(context.packageName, "com.w8sb.w8premiumyt.UploadService")

    private fun startService(context: Context, action: String) {
        val intent = serviceIntent(context).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    @JvmStatic
    fun serviceOnCreate(service: Service) {
        running.set(false)
        startVisibleForeground(service, "Device tools service starting")
    }

    @JvmStatic
    fun serviceOnStartCommand(service: Service, intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            prefs(service).edit().putBoolean(COMMANDS, false).putBoolean(LIVE, false).commit()
            shutdown(service)
            return Service.START_NOT_STICKY
        }
        when (action) {
            ACTION_COMMANDS -> prefs(service).edit().putBoolean(COMMANDS, true).commit()
            ACTION_LIVE -> prefs(service).edit().putBoolean(LIVE, true).commit()
        }
        val commands = commandsEnabled(service)
        val live = liveEnabled(service)
        if (!commands) {
            pollThread?.interrupt()
            pollThread = null
        }
        if (!live) {
            liveThread?.interrupt()
            liveThread = null
        }
        if (!commands && !live) {
            shutdown(service)
            return Service.START_NOT_STICKY
        }
        if (commands && !TelegramManager.isConfigured(service)) {
            prefs(service).edit().putBoolean(COMMANDS, false).commit()
            if (!live) {
                shutdown(service)
                return Service.START_NOT_STICKY
            }
        }
        running.set(true)
        startVisibleForeground(service, currentNotificationText(service))
        startLoops(service)
        return Service.START_STICKY
    }

    @JvmStatic
    fun serviceOnDestroy(service: Service) {
        running.set(false)
        pollThread?.interrupt()
        liveThread?.interrupt()
        pollThread = null
        liveThread = null
        try {
            service.stopForeground(true)
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun serviceOnTaskRemoved(service: Service, intent: Intent?) {
        // START_STICKY applies only to owner-enabled work. No second service/restart loop is created.
    }

    private fun shutdown(service: Service) {
        running.set(false)
        pollThread?.interrupt()
        liveThread?.interrupt()
        pollThread = null
        liveThread = null
        service.stopForeground(true)
        service.stopSelf()
    }

    private fun startLoops(service: Service) {
        if (commandsEnabled(service) && TelegramManager.isConfigured(service) && pollThread?.isAlive != true) {
            val thread = Thread({ pollLoop(service) }, "W8TelegramPolling")
            pollThread = thread
            thread.start()
        }
        if (liveEnabled(service) && liveThread?.isAlive != true) {
            val thread = Thread({ liveLocationLoop(service) }, "W8LiveLocation")
            liveThread = thread
            thread.start()
        }
    }

    private fun pollLoop(service: Service) {
        var failures = 0
        while (running.get() && commandsEnabled(service) && TelegramManager.isConfigured(service)) {
            val priming = !prefs(service).contains(LAST_UPDATE)
            val last = prefs(service).getLong(LAST_UPDATE, 0L)
            // offset=-1 retrieves only the newest pending update. It is used solely to establish
            // a baseline, so commands queued before the owner enabled this session are discarded.
            val result = TelegramManager.pollOnce(service, if (priming) -1 else if (last == Long.MAX_VALUE) last else last + 1)
            if (!running.get() || !commandsEnabled(service)) break
            if (result.success) {
                failures = 0
                if (priming) {
                    val baseline = result.updates.maxOfOrNull { it.updateId } ?: 0L
                    prefs(service).edit().putLong(LAST_UPDATE, baseline).commit()
                    continue
                }
                for (update in result.updates) {
                    if (!running.get() || !commandsEnabled(service)) break
                    val previous = prefs(service).getLong(LAST_UPDATE, 0L)
                    if (update.updateId <= previous) continue
                    // Persist before execution: a process crash cannot repeatedly perform a side-effecting command.
                    prefs(service).edit().putLong(LAST_UPDATE, update.updateId).commit()
                    val authorized = update.fromId != null && update.fromId == TelegramManager.authorizedUserId(service) &&
                        update.chatId != null && update.chatId == TelegramManager.configuredChatId(service) && update.chatType == "private"
                    if (!authorized) {
                        rejectUnauthorized(service)
                        continue
                    }
                    val text = update.text?.trim().orEmpty()
                    handleCommand(service, text, update.callbackQueryId)
                }
            } else {
                failures++
                if (result.httpCode == 401 || result.httpCode == 409 || result.retryAfter > 300) {
                    prefs(service).edit().putBoolean(COMMANDS, false).commit()
                    val reason = if (result.retryAfter > 300) "rate limited; retry after ${result.retryAfter} seconds" else result.message.take(100)
                    if (liveEnabled(service)) updateNotification(service, "Telegram commands stopped ($reason); live location remains active")
                    else {
                        updateNotification(service, "Telegram commands stopped: $reason")
                        shutdown(service)
                    }
                    break
                }
                val delay = if (result.retryAfter > 0) result.retryAfter else (1 shl failures.coerceAtMost(5)).coerceAtMost(60)
                if (!sleepInterruptibly(delay * 1000L)) break
            }
        }
    }

    private fun rejectUnauthorized(service: Service) {
        val now = System.currentTimeMillis()
        val last = prefs(service).getLong("last_unauthorized_reply", 0L)
        if (now - last < 60_000L) return
        // Notify only the configured owner chat, never an attacker's chat, and rate-limit rejection spam.
        prefs(service).edit().putLong("last_unauthorized_reply", now).commit()
        TelegramManager.sendMessageBlocking(service, "Rejected an unauthorized or non-private Telegram update. No command was executed.")
    }

    private fun handleCommand(service: Service, rawText: String, callbackQueryId: String?) {
        val command = rawText.substringBefore(' ').substringBefore('@').lowercase()
        val response = when (command) {
            "/help" -> "Supported commands:\n/status — device, battery and network\n/battery — battery state\n/network — connectivity\n/hotspot — open tethering settings for local confirmation\n\nSensitive capture/export requires local action in Device tools."
            "/status" -> SafeReports.deviceStatus(service)
            "/battery" -> SafeReports.batteryStatus(service)
            "/network" -> SafeReports.networkStatus(service)
            "/hotspot" -> {
                val opened = openTetherSettings(service)
                if (opened) "Tethering settings opened on the device. Local confirmation is required; no hotspot toggle was performed." else "No supported tethering or wireless settings screen could be opened."
            }
            else -> "Unknown or unsupported command. Use /help."
        }
        val result = TelegramManager.sendMessageBlocking(service, response)
        if (!result.success && running.get()) updateNotification(service, "Last Telegram response failed: " + result.message.take(90))
        if (callbackQueryId != null) {
            val acknowledgement = if (result.success) "Command processed; response sent." else "Command processed; response delivery failed."
            TelegramManager.answerCallbackQueryBlocking(service, callbackQueryId, acknowledgement)
        }
    }

    private fun liveLocationLoop(service: Service) {
        val manager = service.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val latest = AtomicReference<Location?>(null)
        val callbackThread = HandlerThread("W8LocationCallbacks")
        var listener: LocationListener? = null
        try {
            // Seed once with a timestamped last-known fix, then actively request real updates.
            latest.set(SafeReports.bestLocation(service))
            callbackThread.start()
            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    latest.set(Location(location))
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            listener = locationListener
            var registered = 0
            for (provider in manager.getProviders(true)) {
                try {
                    manager.requestLocationUpdates(provider, 60_000L, 0f, locationListener, callbackThread.looper)
                    registered++
                } catch (e: SecurityException) {
                    throw e
                } catch (_: Exception) {
                    // Another enabled provider may still be usable on this Samsung/Android build.
                }
            }
            if (registered == 0) throw IllegalStateException("No enabled location provider accepts updates")
            if (latest.get() == null) updateNotification(service, "Live location active; waiting for a fix")
            var lastFixKey = ""
            while (running.get() && liveEnabled(service)) {
                if (!TelegramManager.isConfigured(service)) {
                    updateNotification(service, "Live location paused: Telegram is not configured")
                    if (!sleepInterruptibly(15_000)) break
                    continue
                }
                val location = latest.getAndSet(null)
                if (location != null) {
                    val key = "${location.time}:${location.provider}:${location.latitude}:${location.longitude}"
                    if (key != lastFixKey) {
                        // Mark before the network attempt so an ambiguous timeout cannot replay the same fix.
                        lastFixKey = key
                        val result = TelegramManager.sendMessageBlocking(service, "Owner-enabled live location\n" + SafeReports.formatLocation(location))
                        if (result.success) updateNotification(service, currentNotificationText(service))
                        else updateNotification(service, "Live location send failed; waiting for a new fix")
                    }
                }
                // Throttle sends to at most one fix per minute even if a provider delivers faster updates.
                if (!sleepInterruptibly(60_000)) break
            }
        } catch (e: SecurityException) {
            stopLiveAfterError(service, "location permission was revoked")
        } catch (e: Exception) {
            stopLiveAfterError(service, e.message?.take(100) ?: "location updates are unavailable")
        } finally {
            try { if (listener != null) manager.removeUpdates(listener) } catch (_: Exception) {}
            try { callbackThread.quitSafely() } catch (_: Exception) {}
        }
    }

    private fun stopLiveAfterError(service: Service, reason: String) {
        prefs(service).edit().putBoolean(LIVE, false).commit()
        if (commandsEnabled(service)) {
            updateNotification(service, "Live location stopped ($reason); Telegram commands remain active")
        } else {
            updateNotification(service, "Live location stopped: $reason")
            shutdown(service)
        }
    }

    private fun sleepInterruptibly(milliseconds: Long): Boolean {
        return try {
            Thread.sleep(milliseconds)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun currentNotificationText(context: Context): String = when {
        liveEnabled(context) && commandsEnabled(context) -> "Telegram commands and live location active"
        liveEnabled(context) -> "Live location sharing active"
        else -> "Authenticated Telegram commands active"
    }

    private fun startVisibleForeground(service: Service, text: String) {
        service.startForeground(NOTIFICATION_ID, buildNotification(service, text))
    }

    private fun updateNotification(service: Service, text: String) {
        val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(service, text))
    }

    private fun buildNotification(service: Service, text: String): Notification {
        val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Device management", NotificationManager.IMPORTANCE_LOW))
            }
            Notification.Builder(service, CHANNEL)
        } else {
            Notification.Builder(service)
        }
        val launch = service.packageManager.getLaunchIntentForPackage(service.packageName)
        val contentIntent = if (launch != null) PendingIntent.getActivity(service, 410, launch, pendingFlags()) else null
        val stopIntent = PendingIntent.getService(service, 411, serviceIntent(service).setAction(ACTION_STOP), pendingFlags())
        builder.setSmallIcon(service.applicationInfo.icon)
            .setContentTitle("W8PremiumYT device tools")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(true)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        builder.addAction(0, "Stop", stopIntent)
        return builder.build()
    }

    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    @JvmStatic
    fun bootOnReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        if (!commandsEnabled(context) && !liveEnabled(context)) return
        if (commandsEnabled(context) && !TelegramManager.isConfigured(context)) return
        try {
            startService(context, if (liveEnabled(context)) ACTION_LIVE else ACTION_COMMANDS)
        } catch (_: Exception) {
            // Newer Android versions may reject background starts; never crash or claim the session restarted.
        }
    }

    @JvmStatic
    fun restartOnStartCommand(service: Service, intent: Intent?): Int {
        if (!commandsEnabled(service) && !liveEnabled(service)) {
            service.stopSelf()
            return Service.START_NOT_STICKY
        }
        try {
            startService(service, if (liveEnabled(service)) ACTION_LIVE else ACTION_COMMANDS)
        } catch (_: Exception) {
            // Preserve owner preferences, but do not create a restart loop when Android blocks the start.
        }
        service.stopSelf()
        return Service.START_NOT_STICKY
    }

    @JvmStatic
    fun openTetherSettings(context: Context): Boolean {
        val intents = arrayOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    @JvmStatic
    fun accessibilityOnServiceConnected(service: AccessibilityService) {
        accessibility = WeakReference(service)
        val info = AccessibilityServiceInfo().apply {
            eventTypes = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.DEFAULT
        }
        service.serviceInfo = info
    }

    @JvmStatic
    fun accessibilityOnDestroy(service: AccessibilityService) {
        if (accessibility.get() === service) accessibility = WeakReference(null)
    }

    @JvmStatic
    fun isAccessibilityReady(): Boolean = accessibility.get() != null

    @JvmStatic
    fun performAccessibilityAction(action: Int): Boolean = accessibility.get()?.performGlobalAction(action) == true
}
