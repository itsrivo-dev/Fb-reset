package com.w8sb.w8premiumyt

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Chronometer
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

/** Local, explicit owner workflows attached to the original MainActivity and WebView layout. */
object SafeRepair {
    private const val PREFS = "w8safe"
    private const val REQ_PERMISSIONS = 5201
    private const val REQ_IMAGE = 5202
    private const val REQ_DOCUMENT = 5203
    private const val REQ_BROWSER = 5204
    private const val REQ_CAMERA = 5205
    private const val MAX_FILE_BYTES = 45L * 1024L * 1024L
    private val controllers = WeakHashMap<Activity, Controller>()
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    fun initialize(activity: Activity, webView: WebView) {
        val controller = Controller(activity, webView)
        controllers[activity] = controller
        controller.initialize()
    }

    @JvmStatic
    fun configureWebView(activity: Activity, webView: WebView) {
        controllers[activity]?.configureWebView() ?: run {
            val controller = Controller(activity, webView)
            controllers[activity] = controller
            controller.configureWebView()
        }
    }

    @JvmStatic
    fun onDestroy(activity: Activity) {
        controllers.remove(activity)?.destroy()
    }

    @JvmStatic
    fun onStop(activity: Activity) {
        controllers[activity]?.onStop()
    }

    @JvmStatic
    fun onResume(activity: Activity) {
        controllers[activity]?.onResume()
    }

    @JvmStatic
    fun onPermissionsResult(activity: Activity, requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        controllers[activity]?.onPermissionsResult(requestCode, grantResults)
    }

    @JvmStatic
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        controllers[activity]?.onActivityResult(requestCode, resultCode, data)
    }

    @JvmStatic
    fun openTetherSettings(context: Context): Boolean = SafeService.openTetherSettings(context)

    class Controller(val activity: Activity, val webView: WebView) {
        private var pendingPermissionAction: (() -> Unit)? = null
        private var recorder: MediaRecorder? = null
        private var activeRecording: File? = null
        private var pendingAudio: File? = null
        private var recordingDialog: AlertDialog? = null
        private var player: MediaPlayer? = null
        private var cameraFile: File? = null
        private var initialized = false

        fun initialize() {
            if (initialized) return
            initialized = true
            configureWebView()
            addDeviceToolsButton()
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("safe_disclosure_seen", false)) {
                AlertDialog.Builder(activity)
                    .setTitle("W8PremiumYT device tools")
                    .setMessage("YouTube remains available without sensitive permissions. Device exports, capture, and Telegram commands are off until you choose them in Device tools. Camera, microphone, clipboard, location, and personal-data operations require an explicit local action.")
                    .setPositiveButton("Continue") { _, _ ->
                        prefs.edit().putBoolean("safe_disclosure_seen", true).apply()
                    }
                    .setCancelable(false)
                    .show()
            }
        }

        fun configureWebView() {
            WebView.setWebContentsDebuggingEnabled(false)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                setSupportZoom(true)
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = false
                setAllowFileAccessFromFileURLs(false)
                setAllowUniversalAccessFromFileURLs(false)
                setGeolocationEnabled(false)
            }
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val target = url ?: return false
                    if (URLUtil.isHttpsUrl(target)) return false
                    if (target == "about:blank") return false
                    toast("Blocked a non-HTTPS WebView navigation.")
                    return true
                }

                @Deprecated("Required for API 23")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    if (activity.isFinishing || activity.isDestroyed) return
                    val current = view?.url
                    if (failingUrl != null && current != null && failingUrl != current) return
                    activity.runOnUiThread {
                        AlertDialog.Builder(activity)
                            .setTitle("YouTube could not be loaded")
                            .setMessage("The page was not replaced with a fake result. Check mobile data/Wi-Fi and WebView support, then retry.\n\n${description ?: "WebView error $errorCode"}")
                            .setPositiveButton("Retry") { _, _ -> loadConfiguredUrl() }
                            .setNegativeButton("Close", null)
                            .show()
                    }
                }
            }
            loadConfiguredUrl()
        }

        private fun loadConfiguredUrl() {
            var url = "https://www.youtube.com"
            try {
                activity.assets.open("app_config.json").bufferedReader().use {
                    val configured = JSONObject(it.readText()).optString("webview_url", url)
                    if (URLUtil.isHttpsUrl(configured)) url = configured
                }
            } catch (_: Exception) {
            }
            if (!URLUtil.isHttpsUrl(url)) {
                toast("Only HTTPS WebView addresses are supported.")
                return
            }
            webView.loadUrl(url)
        }

        private fun addDeviceToolsButton() {
            val parent = webView.parent as? ViewGroup ?: return
            val button = Button(activity).apply {
                text = "Device tools"
                contentDescription = "Open owner-authorized device tools"
                setOnClickListener { showTools() }
            }
            val index = parent.indexOfChild(webView).coerceAtLeast(0)
            parent.addView(button, index, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        private fun showTools() {
            val commands = SafeService.commandsEnabled(activity)
            val live = SafeService.liveEnabled(activity)
            val items = arrayOf(
                "Device / battery / network status",
                "Permission and access status",
                if (commands) "Stop Telegram commands" else "Start Telegram commands",
                "Telegram settings",
                "Location report",
                if (live) "Stop live location" else "Start visible live location",
                "SMS backup (latest 50)",
                "Contacts backup",
                "Call history (latest 100)",
                "Choose gallery image",
                "Choose document",
                "Clipboard preview / share",
                "Visible camera capture",
                "Visible microphone recording",
                "Calendar export",
                "Installed applications export",
                "Usage statistics (24 hours)",
                "Account inventory",
                "Wi-Fi / Bluetooth report",
                "Public IP (external query)",
                "Choose browser export file",
                "Accessibility actions / settings",
                "Hotspot / tethering settings",
                "Battery / background settings",
                "Delete temporary exports and captures"
            )
            AlertDialog.Builder(activity).setTitle("Device tools").setItems(items) { _, which ->
                when (which) {
                    0 -> showTextActions("Device status", SafeReports.deviceStatus(activity))
                    1 -> showPermissionStatus()
                    2 -> if (commands) stopCommands() else startCommands()
                    3 -> showTelegramSettings()
                    4 -> locationWorkflow()
                    5 -> if (live) stopLiveLocation() else liveLocationWorkflow()
                    6 -> exportWorkflow("SMS backup", "Read the latest 50 SMS records and prepare a private export. Nothing is sent until you confirm a destination.", arrayOf(Manifest.permission.READ_SMS)) { SafeReports.sms(activity) }
                    7 -> exportWorkflow("Contacts backup", "Read contact names, phone numbers, and number types for an owner-approved export.", arrayOf(Manifest.permission.READ_CONTACTS)) { SafeReports.contacts(activity) }
                    8 -> exportWorkflow("Call history", "Read the latest 100 call-history records for an owner-approved export.", arrayOf(Manifest.permission.READ_CALL_LOG)) { SafeReports.callHistory(activity) }
                    9 -> chooseFile(REQ_IMAGE, "image/*")
                    10 -> chooseFile(REQ_DOCUMENT, "*/*")
                    11 -> clipboardWorkflow()
                    12 -> cameraWorkflow()
                    13 -> microphoneWorkflow()
                    14 -> exportWorkflow("Calendar export", "Read calendar titles, calendars, dates, and event locations for an owner-approved export.", arrayOf(Manifest.permission.READ_CALENDAR)) { SafeReports.calendar(activity) }
                    15 -> exportWorkflow("Installed applications", "Create an inventory of installed application names, package names, versions, and install timestamps.", emptyArray()) { SafeReports.installedApps(activity) }
                    16 -> usageWorkflow()
                    17 -> exportWorkflow("Account inventory", "Read account names and types for an owner-approved inventory. These identifiers will not be added to ordinary status or photos.", arrayOf(Manifest.permission.GET_ACCOUNTS)) { SafeReports.accounts(activity) }
                    18 -> exportWorkflow("Wi-Fi and Bluetooth", "Read saved Wi-Fi SSID/BSSID entries and bonded Bluetooth names/addresses. No passwords are available or included.", arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) { SafeReports.wifiBluetooth(activity) }
                    19 -> publicIpWorkflow()
                    20 -> chooseFile(REQ_BROWSER, "*/*")
                    21 -> accessibilityWorkflow()
                    22 -> {
                        if (!SafeService.openTetherSettings(activity)) toast("No supported tethering or wireless settings screen was found.")
                    }
                    23 -> batterySettings()
                    24 -> confirmDeleteTemporaryFiles()
                }
            }.show()
        }

        private fun showPermissionStatus() {
            val permissions = listOf(
                "SMS" to Manifest.permission.READ_SMS,
                "Contacts" to Manifest.permission.READ_CONTACTS,
                "Call history" to Manifest.permission.READ_CALL_LOG,
                "Location" to Manifest.permission.ACCESS_FINE_LOCATION,
                "Camera" to Manifest.permission.CAMERA,
                "Microphone" to Manifest.permission.RECORD_AUDIO,
                "Calendar" to Manifest.permission.READ_CALENDAR,
                "Accounts" to Manifest.permission.GET_ACCOUNTS
            )
            val text = buildString {
                append("Local permission status — nothing is transmitted\n\n")
                for ((label, permission) in permissions) {
                    val granted = hasPermission(permission)
                    append(label).append(": ").append(if (granted) "granted" else "not granted").append('\n')
                }
                append("Usage Access: ").append(if (SafeReports.hasUsageAccess(activity)) "enabled" else "not enabled").append('\n')
                append("Accessibility actions: ").append(if (SafeService.isAccessibilityReady()) "enabled" else "not enabled").append('\n')
                val power = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                append("Battery-optimization exemption: ").append(
                    if (Build.VERSION.SDK_INT >= 23) power.isIgnoringBatteryOptimizations(activity.packageName).toString() else "not applicable before API 23"
                )
            }
            AlertDialog.Builder(activity).setTitle("Permission and access status").setMessage(text).setPositiveButton("Close", null).show()
        }

        private fun locationWorkflow() {
            confirm("Location report", "Read the newest available last-known location and show its timestamp and accuracy. It will not be shared until you choose Telegram or Share.") {
                ensurePermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    try {
                        showTextActions("Location report", SafeReports.locationReport(activity))
                    } catch (e: Exception) {
                        showError("Location unavailable", e)
                    }
                }
            }
        }

        private fun liveLocationWorkflow() {
            confirm("Visible live location", "Start a visible foreground session that sends a new location fix to your configured Telegram chat no more than once per minute. Duplicate fixes are skipped. Stop it from Device tools or the persistent notification.") {
                ensurePermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    ensureForegroundNotificationPermission {
                        if (!TelegramManager.isConfigured(activity)) {
                            toast("Configure Telegram first.")
                            showTelegramSettings()
                        } else if (SafeService.startLiveLocation(activity)) {
                            toast("Live location started with a visible notification.")
                        } else {
                            toast("Live location could not be started.")
                        }
                    }
                }
            }
        }

        private fun exportWorkflow(title: String, rationale: String, permissions: Array<String>, producer: () -> ExportData) {
            confirm(title, rationale) {
                ensurePermissions(permissions) {
                    toast("Preparing $title…")
                    Thread {
                        try {
                            val data = producer()
                            val file = writeExport(data)
                            main.post {
                                if (!activity.isFinishing && !activity.isDestroyed) showFileActions(data.title, file, preview(data.text))
                            }
                        } catch (e: Exception) {
                            main.post { if (!activity.isFinishing && !activity.isDestroyed) showError("$title failed", e) }
                        }
                    }.start()
                }
            }
        }

        private fun usageWorkflow() {
            confirm("Usage statistics", "Read the previous 24 hours of application foreground usage. Android requires separate Usage Access activation.") {
                if (!SafeReports.hasUsageAccess(activity)) {
                    AlertDialog.Builder(activity)
                        .setTitle("Enable Usage Access")
                        .setMessage("Android Settings will open. Enable Usage Access for W8PremiumYT only if you want this report, then return and select it again.")
                        .setPositiveButton("Open Settings") { _, _ ->
                            try { activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (e: Exception) { showError("Settings unavailable", e) }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    exportWorkflow("Usage statistics", "Create the now-authorized 24-hour usage report.", emptyArray()) { SafeReports.usageStats(activity) }
                }
            }
        }

        private fun publicIpWorkflow() {
            confirm("External public-IP query", "This contacts https://api.ipify.org over the network. That service will see your current public IP. No query is made until you continue.") {
                Thread {
                    try {
                        val text = SafeReports.publicIp()
                        main.post { if (!activity.isFinishing && !activity.isDestroyed) showTextActions("Public IP", text) }
                    } catch (e: Exception) {
                        main.post { if (!activity.isFinishing && !activity.isDestroyed) showError("Public IP query failed", e) }
                    }
                }.start()
            }
        }

        private fun clipboardWorkflow() {
            confirm("Read current clipboard", "Read the current clipboard once while the app is visible, then show it before any sharing. No clipboard listener or background monitoring will be enabled.") {
                val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = manager.primaryClip
                val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(activity).toString() else ""
                if (text.isBlank()) toast("Clipboard is empty or does not contain text.") else showTextActions("Clipboard preview", text)
            }
        }

        private fun chooseFile(requestCode: Int, mimeType: String) {
            val purpose = when (requestCode) {
                REQ_IMAGE -> "Choose one image in the system picker. It will be copied to private temporary storage only after selection."
                REQ_BROWSER -> "Choose a bookmarks/history file exported by your browser. The app will not attempt to read another browser's private database."
                else -> "Choose one document in the system picker. It will be copied to private temporary storage only after selection."
            }
            confirm("User-selected file", purpose) {
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = mimeType
                    }
                    activity.startActivityForResult(intent, requestCode)
                } catch (e: Exception) {
                    showError("Document picker unavailable", e)
                }
            }
        }

        private fun cameraWorkflow() {
            confirm("Visible camera capture", "After permission approval, the visible system camera will open. The full-resolution photo stays private until you choose Telegram, Share, or Delete. No background capture is enabled.") {
                ensurePermissions(arrayOf(Manifest.permission.CAMERA)) { launchCamera() }
            }
        }

        private fun launchCamera() {
            try {
                val directory = File(activity.cacheDir, "captures").apply { mkdirs() }
                val file = File(directory, "photo_${System.currentTimeMillis()}.jpg")
                val uri = privateFileUri(file)
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                intent.clipData = ClipData.newRawUri("capture", uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (intent.resolveActivity(activity.packageManager) == null) {
                    toast("No camera application is available.")
                    return
                }
                cameraFile = file
                activity.startActivityForResult(intent, REQ_CAMERA)
            } catch (e: Exception) {
                cameraFile?.delete()
                cameraFile = null
                showError("Camera could not be opened", e)
            }
        }

        private fun microphoneWorkflow() {
            confirm("Visible microphone recording", "Record only after you press Start, with a visible timer and Stop control. Leaving the app stops recording. You can play, share, send, or delete the result. No background loop is enabled.") {
                ensurePermissions(arrayOf(Manifest.permission.RECORD_AUDIO)) { showRecordingConfirmation() }
            }
        }

        private fun showRecordingConfirmation() {
            AlertDialog.Builder(activity)
                .setTitle("Start microphone recording?")
                .setMessage("Recording will begin immediately after Start and remain visible. Android 6 does not provide a system microphone indicator, so this dialog is the active indicator.")
                .setPositiveButton("Start") { _, _ -> startRecording() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun startRecording() {
            try {
                val directory = File(activity.cacheDir, "captures").apply { mkdirs() }
                val file = File(directory, "audio_${System.currentTimeMillis()}.m4a")
                val mediaRecorder = MediaRecorder()
                recorder = mediaRecorder
                activeRecording = file
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mediaRecorder.setAudioSamplingRate(44_100)
                mediaRecorder.setAudioEncodingBitRate(96_000)
                mediaRecorder.setOutputFile(file.absolutePath)
                mediaRecorder.prepare()
                mediaRecorder.start()
                recorder = mediaRecorder
                activeRecording = file
                val layout = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    val padding = (18 * resources.displayMetrics.density).toInt()
                    setPadding(padding, padding, padding, padding)
                }
                val timer = Chronometer(activity)
                timer.base = SystemClock.elapsedRealtime()
                timer.start()
                layout.addView(timer)
                layout.addView(TextView(activity).apply { text = "Microphone recording is active. Leaving the app stops it." })
                val dialog = AlertDialog.Builder(activity)
                    .setTitle("Recording…")
                    .setView(layout)
                    .setPositiveButton("Stop") { _, _ -> stopRecording(delete = false, showPreview = true) }
                    .setNegativeButton("Cancel & delete") { _, _ -> stopRecording(delete = true, showPreview = false) }
                    .setOnCancelListener { stopRecording(delete = true, showPreview = false) }
                    .create()
                recordingDialog = dialog
                dialog.show()
            } catch (e: Exception) {
                try { recorder?.release() } catch (_: Exception) {}
                recorder = null
                activeRecording?.delete()
                activeRecording = null
                showError("Microphone recording failed", e)
            }
        }

        private fun stopRecording(delete: Boolean, showPreview: Boolean) {
            val current = recorder
            val file = activeRecording
            recorder = null
            activeRecording = null
            try { recordingDialog?.dismiss() } catch (_: Exception) {}
            recordingDialog = null
            var valid = file != null && file.length() > 0
            if (current != null) {
                try { current.stop() } catch (e: Exception) { valid = false }
                try { current.release() } catch (_: Exception) {}
            }
            if (delete || !valid) {
                file?.delete()
                pendingAudio = null
                if (!valid && !delete) toast("Recording was too short or could not be finalized.")
            } else if (file != null) {
                pendingAudio = file
                if (showPreview) showAudioActions(file)
            }
        }

        private fun showAudioActions(file: File) {
            if (!file.isFile || file.length() == 0L) {
                toast("The recording is unavailable.")
                return
            }
            val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(TextView(activity).apply { text = "Recording ready\n${file.name}\n${file.length()} bytes\n\nNothing is sent until you choose an action." })
            layout.addView(Button(activity).apply {
                text = "Play / stop playback"
                setOnClickListener { togglePlayback(file) }
            })
            AlertDialog.Builder(activity)
                .setTitle("Microphone recording")
                .setView(layout)
                .setPositiveButton("Telegram") { _, _ -> sendFile(file) }
                .setNeutralButton("Share") { _, _ -> shareFile(file) }
                .setNegativeButton("Delete") { _, _ -> file.delete(); pendingAudio = null }
                .setOnCancelListener { file.delete(); pendingAudio = null }
                .show()
        }

        private fun togglePlayback(file: File) {
            try {
                if (player?.isPlaying == true) {
                    player?.stop()
                    player?.release()
                    player = null
                    return
                }
                player?.release()
                val mediaPlayer = MediaPlayer()
                mediaPlayer.setDataSource(file.absolutePath)
                mediaPlayer.prepare()
                mediaPlayer.setOnCompletionListener {
                    it.release()
                    if (player === it) player = null
                }
                mediaPlayer.start()
                player = mediaPlayer
            } catch (e: Exception) {
                showError("Playback failed", e)
            }
        }

        private fun accessibilityWorkflow() {
            if (!SafeService.isAccessibilityReady()) {
                AlertDialog.Builder(activity)
                    .setTitle("Accessibility device actions")
                    .setMessage("The repaired service supports only explicit global navigation actions. It does not read screen text, passwords, or key events. Android Settings will require you to enable it yourself.")
                    .setPositiveButton("Open Accessibility Settings") { _, _ ->
                        try { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { showError("Settings unavailable", e) }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
            val labels = arrayOf("Back", "Home", "Recents", "Notifications shade", "Quick settings")
            val actions = intArrayOf(
                AccessibilityService.GLOBAL_ACTION_BACK,
                AccessibilityService.GLOBAL_ACTION_HOME,
                AccessibilityService.GLOBAL_ACTION_RECENTS,
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
                AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            )
            AlertDialog.Builder(activity).setTitle("Accessibility actions").setItems(labels) { _, which ->
                val success = SafeService.performAccessibilityAction(actions[which])
                toast(if (success) "Action requested." else "Android did not complete the action.")
            }.show()
        }

        private fun startCommands() {
            if (!TelegramManager.isConfigured(activity)) {
                toast("Configure Telegram first.")
                showTelegramSettings()
                return
            }
            confirm("Start authenticated Telegram commands", "Start a visible foreground polling session for /help, /status, /battery, /network, and /hotspot. Only your numeric Telegram user ID in the configured private chat is accepted. Old pending updates are discarded before commands begin.") {
                ensureForegroundNotificationPermission {
                    if (SafeService.startCommands(activity)) toast("Telegram commands started with a visible notification.") else toast("Commands could not be started.")
                }
            }
        }

        private fun ensureForegroundNotificationPermission(action: () -> Unit) {
            if (Build.VERSION.SDK_INT >= 33) ensurePermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), action) else action()
        }

        private fun stopCommands() {
            SafeService.stopCommands(activity)
            toast("Telegram commands stopped; live location preference was not changed.")
        }

        private fun stopLiveLocation() {
            SafeService.stopLiveLocation(activity)
            toast("Live location stopped; Telegram command preference was not changed.")
        }

        private fun stopBackgroundWork() {
            SafeService.stopWork(activity)
            toast("Background commands and live location stopped.")
        }

        private fun showTelegramSettings() {
            val padding = (18 * activity.resources.displayMetrics.density).toInt()
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
            }
            val explanation = TextView(activity).apply {
                text = "Direct mode stores configuration in app-private preferences. A token on a reverse-engineerable device cannot be guaranteed secret. Use a replacement token, not the exposed original. Chat ID is a destination only; authorization uses the numeric user ID."
            }
            layout.addView(explanation)
            val token = EditText(activity).apply {
                hint = "Replacement bot token (blank keeps saved token)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val chat = EditText(activity).apply {
                hint = "Private destination chat ID"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                val saved = TelegramManager.configuredChatId(activity)
                if (saved != 0L) setText(saved.toString())
            }
            val user = EditText(activity).apply {
                hint = "Authorized numeric Telegram user ID"
                inputType = InputType.TYPE_CLASS_NUMBER
                val saved = TelegramManager.authorizedUserId(activity)
                if (saved > 0L) setText(saved.toString())
            }
            layout.addView(token)
            layout.addView(chat)
            layout.addView(user)
            AlertDialog.Builder(activity)
                .setTitle("Telegram settings")
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    val error = TelegramManager.saveConfiguration(activity, token.text.toString(), chat.text.toString(), user.text.toString())
                    if (error.isEmpty()) toast("Telegram configuration saved locally.") else toast(error)
                }
                .setNeutralButton("Clear") { _, _ ->
                    SafeService.stopWork(activity)
                    TelegramManager.clearConfiguration(activity)
                    toast("Telegram configuration cleared.")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun batterySettings() {
            val power = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            val exempt = Build.VERSION.SDK_INT >= 23 && power.isIgnoringBatteryOptimizations(activity.packageName)
            AlertDialog.Builder(activity)
                .setTitle("Background reliability")
                .setMessage("Samsung/Android may still suspend networking. A foreground notification is required for active sessions; hidden persistence is not used.\n\nBattery-optimization exemption: $exempt")
                .setPositiveButton("Request exemption") { _, _ ->
                    if (Build.VERSION.SDK_INT < 23) {
                        toast("Battery-optimization controls require Android 6 / API 23 or newer.")
                    } else {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.packageName))
                            activity.startActivity(intent)
                        } catch (e: Exception) {
                            try { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { showError("Battery settings unavailable", e2) }
                        }
                    }
                }
                .setNeutralButton("Open list") { _, _ ->
                    try { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { showError("Battery settings unavailable", e) }
                }
                .setNegativeButton("Close", null)
                .show()
        }

        private fun showTextActions(title: String, text: String) {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(preview(text))
                .setPositiveButton("Telegram") { _, _ -> sendText(text) }
                .setNeutralButton("Share") { _, _ -> shareText(title, text) }
                .setNegativeButton("Close", null)
                .show()
        }

        private fun sendText(text: String) {
            if (!TelegramManager.isConfigured(activity)) {
                toast("Telegram is not configured.")
                showTelegramSettings()
                return
            }
            toast("Sending to Telegram…")
            TelegramManager.sendMessageAsync(activity, text, object : TelegramManager.Callback {
                override fun onComplete(success: Boolean, message: String) {
                    toast(if (success) "Telegram delivery confirmed." else "Telegram failed: $message")
                }
            })
        }

        private fun sendFile(file: File) {
            if (!TelegramManager.isConfigured(activity)) {
                toast("Telegram is not configured; private file retained.")
                showTelegramSettings()
                return
            }
            toast("Sending file to Telegram…")
            TelegramManager.sendFileAsync(activity, file, mimeType(file), "Owner-approved export from W8PremiumYT", object : TelegramManager.Callback {
                override fun onComplete(success: Boolean, message: String) {
                    if (success) {
                        file.delete()
                        if (pendingAudio === file) pendingAudio = null
                        toast("Telegram delivery confirmed; temporary file deleted.")
                    } else {
                        toast("Telegram failed: $message. Private file retained.")
                    }
                }
            })
        }

        private fun shareText(title: String, text: String) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            try { activity.startActivity(Intent.createChooser(intent, title)) } catch (e: Exception) { showError("Sharing unavailable", e) }
        }

        private fun privateFileUri(file: File): Uri {
            // The original APK already contains AndroidX FileProvider. Reflection avoids shipping
            // a compile-only Kotlin companion stub and binds directly to its public static API.
            val provider = Class.forName("androidx.core.content.FileProvider")
            val method = provider.getMethod(
                "getUriForFile",
                Context::class.java,
                String::class.java,
                File::class.java
            )
            return method.invoke(null, activity, activity.packageName + ".fileprovider", file) as Uri
        }

        private fun shareFile(file: File) {
            try {
                val uri = privateFileUri(file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType(file)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(intent, "Share owner-approved export"))
            } catch (e: Exception) {
                showError("Sharing unavailable", e)
            }
        }

        private fun showFileActions(title: String, file: File, previewText: String) {
            val message = "$previewText\n\nTemporary private file: ${file.name}\nSize: ${file.length()} bytes\n\nTelegram deletes this temporary file only after confirmed delivery. Share uses Android's chooser. Cancel deletes it."
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Telegram") { _, _ -> sendFile(file) }
                .setNeutralButton("Share") { _, _ -> shareFile(file) }
                .setNegativeButton("Delete") { _, _ -> file.delete() }
                .setOnCancelListener { file.delete() }
                .show()
        }

        private fun showImageActions(file: File) {
            val scroll = ScrollView(activity)
            val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val bitmap = decodeSampledBitmap(file, 1024)
            if (bitmap != null) layout.addView(ImageView(activity).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            layout.addView(TextView(activity).apply { text = "${file.name}\n${file.length()} bytes\n\nChoose Telegram, Share, or Delete." })
            scroll.addView(layout)
            AlertDialog.Builder(activity)
                .setTitle("Camera photo")
                .setView(scroll)
                .setPositiveButton("Telegram") { _, _ -> sendFile(file) }
                .setNeutralButton("Share") { _, _ -> shareFile(file) }
                .setNegativeButton("Delete") { _, _ -> file.delete() }
                .setOnCancelListener { file.delete() }
                .show()
        }

        private fun onFileChosen(requestCode: Int, uri: Uri) {
            Thread {
                try {
                    val file = copyUri(uri)
                    main.post {
                        if (activity.isFinishing || activity.isDestroyed) {
                            file.delete()
                            return@post
                        }
                        when (requestCode) {
                            REQ_IMAGE -> showFileActions("Selected gallery image", file, "Image selected by the device owner.")
                            REQ_BROWSER -> showFileActions("User-selected browser export", file, "Browser-exported file selected by the device owner. No private browser database was accessed.")
                            else -> showFileActions("Selected document", file, "Document selected by the device owner.")
                        }
                    }
                } catch (e: Exception) {
                    main.post { if (!activity.isFinishing && !activity.isDestroyed) showError("Could not read selected file", e) }
                }
            }.start()
        }

        private fun copyUri(uri: Uri): File {
            var name = "document_${System.currentTimeMillis()}.bin"
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val value = cursor.getString(0)
                    if (!value.isNullOrBlank()) name = value
                }
            }
            name = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(100)
            val directory = File(activity.cacheDir, "exports").apply { mkdirs() }
            val file = File(directory, "${System.currentTimeMillis()}_$name")
            try {
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_FILE_BYTES) throw IOException("Selected file exceeds the 45 MiB repair limit.")
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: throw IOException("The selected document could not be opened.")
                if (file.length() == 0L) throw IOException("The selected file is empty.")
                return file
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }

        private fun writeExport(data: ExportData): File {
            val directory = File(activity.cacheDir, "exports").apply { mkdirs() }
            val base = data.title.replace(Regex("[^A-Za-z0-9._-]"), "_").take(50)
            val file = File(directory, "${System.currentTimeMillis()}_$base.${data.extension}")
            file.writeText(data.text, Charsets.UTF_8)
            return file
        }

        private fun preview(text: String): String {
            if (text.length <= 3500) return text
            return text.take(3500) + "\n\n[Preview truncated; the complete private export is retained for your chosen action.]"
        }

        private fun confirmDeleteTemporaryFiles() {
            confirm("Delete temporary files", "Delete private temporary exports, photos, and recordings created by Device tools. This does not uninstall the app or delete its original data.") {
                var count = 0
                listOf(File(activity.cacheDir, "exports"), File(activity.cacheDir, "captures")).forEach { directory ->
                    directory.listFiles()?.forEach { file -> if (file.isFile && file.delete()) count++ }
                }
                pendingAudio = null
                toast("Deleted $count temporary files.")
            }
        }

        private fun hasPermission(permission: String): Boolean {
            return if (Build.VERSION.SDK_INT >= 23) {
                activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            } else {
                activity.packageManager.checkPermission(permission, activity.packageName) == PackageManager.PERMISSION_GRANTED
            }
        }

        private fun ensurePermissions(permissions: Array<String>, action: () -> Unit) {
            if (Build.VERSION.SDK_INT < 23) {
                action()
                return
            }
            val missing = permissions.filter { !hasPermission(it) }
            if (missing.isEmpty()) {
                action()
            } else {
                pendingPermissionAction = action
                activity.requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            }
        }

        fun onPermissionsResult(requestCode: Int, grantResults: IntArray) {
            if (requestCode != REQ_PERMISSIONS) return
            val action = pendingPermissionAction
            pendingPermissionAction = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                action?.invoke()
            } else {
                toast("Permission denied. No protected data was accessed.")
            }
        }

        fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (resultCode != Activity.RESULT_OK) {
                if (requestCode == REQ_CAMERA) {
                    cameraFile?.delete()
                    cameraFile = null
                }
                return
            }
            when (requestCode) {
                REQ_IMAGE, REQ_DOCUMENT, REQ_BROWSER -> {
                    val uri = data?.data
                    if (uri == null) toast("No file was selected.") else onFileChosen(requestCode, uri)
                }
                REQ_CAMERA -> {
                    val file = cameraFile
                    cameraFile = null
                    if (file == null || !file.isFile || file.length() == 0L) {
                        file?.delete()
                        toast("The camera did not produce a photo.")
                    } else {
                        showImageActions(file)
                    }
                }
            }
        }

        fun onStop() {
            if (recorder != null) stopRecording(delete = false, showPreview = false)
            webView.onPause()
        }

        fun onResume() {
            webView.onResume()
            val file = pendingAudio
            if (file != null && file.isFile && recorder == null && !activity.isFinishing) {
                pendingAudio = null
                showAudioActions(file)
            }
        }

        fun destroy() {
            try { if (recorder != null) stopRecording(delete = true, showPreview = false) } catch (_: Exception) {}
            try { recordingDialog?.dismiss() } catch (_: Exception) {}
            try { player?.release() } catch (_: Exception) {}
            player = null
            pendingAudio?.delete()
            pendingAudio = null
            pendingPermissionAction = null
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }

        private fun confirm(title: String, message: String, action: () -> Unit) {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Continue") { _, _ -> action() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun showError(title: String, error: Exception) {
            val message = (error.message ?: error.javaClass.simpleName).replace(Regex("bot[0-9]+:[A-Za-z0-9_-]+"), "bot[redacted]")
            AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("Close", null).show()
        }

        private fun toast(message: String) {
            if (Looper.myLooper() == Looper.getMainLooper()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            else main.post { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
        }

        private fun mimeType(file: File): String {
            val extension = file.extension.lowercase(Locale.US)
            return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }

        private fun decodeSampledBitmap(file: File, maxDimension: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDimension || bounds.outHeight / (sample * 2) >= maxDimension) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeFile(file.absolutePath, options)
        }
    }
}
