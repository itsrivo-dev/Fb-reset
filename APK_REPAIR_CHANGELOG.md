# APK Repair Change Log

## Baseline verification — 2026-10-23

- Verified the tracked original APK against SHA-256 `20d0701a59be76762a24d269fd4df42ff05638a63df642aeaee18b3793108111`.
- The earlier temporary `/tmp` backup did not survive the workspace restart. No patch had been applied when this was discovered.
- Recreated a separate backup at `repair/backups/W8PremiumYT All In One.original.apk` inside the persistent workspace and verified it byte-for-byte against the original and the expected hash.
- Working decodes, intermediate outputs, local configuration, and signing material are ignored. After a later environment restart discarded ignored artifacts, the backup was restored from the still-immutable original and the backup/final signed output were made persistent and non-ignored.
- The tracked original and the persistent backup still have the baseline hash. Neither has been modified.

## Implemented repair and signed build — 2026-10-23

### Artifact status

- Full Apktool 2.9.3 rebuild, user-approved signing, and signature verification **succeeded** for:
  - `repair/output/W8PremiumYT All In One.repaired.apk`
  - Final signed SHA-256: `4e6585d2626302771b6261c0512512d56d48454635b1f8c90c9dd73847f2236d`
- The exact statically verified unsigned input was `repair/output/W8PremiumYT All In One.repaired.unsigned.apk`, SHA-256 `70f83054027c22ddec644b055a8deadbbe1a44e242ec8ad70b0fbe215e22b138`.
- Final signature schemes: **v1=true, v2=true, v3=true, v4=false**. Apksig verification covered platform versions 23 through 34.
- User-approved repair-only certificate SHA-256: `d6b12efa31fa8cb03f8f5f8bec4a0fa62d45dd312233b3457d7d410c1b95cd4f`.
- No uninstall or installation has been performed. Signed/build verification is not a Samsung runtime test.
- Package `com.w8sb.w8premiumyt`, version, minimum SDK 21, target SDK 34, original resources, original launcher component, and original `:background_service` process are preserved.

### Source and binary integration

- Added auditable Kotlin repair sources under `repair/src/com/w8sb/w8premiumyt/`:
  - `SafeRepair.kt`: original-activity/WebView integration and local Device tools UI.
  - `SafeReports.kt`: owner-requested reports and export producers.
  - `SafeService.kt`: visible foreground service, boot/restart/Accessibility bridges, Telegram polling, and active location updates.
  - `TelegramManager.kt`: direct Bot API configuration/transport, numeric update parsing, bounded responses, retries, uploads, and redaction.
- Added `repair/patches/apply_patches.py` for repeatable changes to the separate Apktool decode.
- Compiled with Kotlin 2.4.21 using language/API 2.0 and JVM 1.8 bytecode, then converted with D8 3.3.28 using `--min-api 23`.
- Integrated the result as `classes6.dex`; no compile-only stub class is shipped.
- Replaced 12 original component method bodies and added five lifecycle bridge methods. Other original methods remain present; legacy code was not silently deleted, but its former automatic component entry points now use the repaired workflows.

### YouTube, WebView, UI, permissions, and lifecycle

- Preserved `https://www.youtube.com` and the original `activity_main` layout/resource files.
- Added a small programmatic **Device tools** entry button without replacing the original layout resource.
- YouTube no longer depends on sensitive permission approval. No startup permission/all-caps permission or fake-error flow is invoked by the repaired `MainActivity.onCreate`.
- WebView repairs: HTTPS-only navigation, cleartext disabled, mixed content blocked, file/content access disabled, universal/file URL access disabled, WebView debugging disabled, and a real error/retry dialog instead of simulated success.
- Added WebView `onPause`/`onResume` and existing destruction cleanup bridges.
- Added local permission/special-access status reporting and per-operation runtime permission requests.
- Added lifecycle result bridges for runtime permissions, document/gallery selection, and camera capture.

### Transparent local workflows

The Device tools UI implements real, owner-initiated workflows rather than mocks:

- Device, battery, network, location, Wi-Fi/Bluetooth, installed-app, account, Usage Access, and explicit public-IP reports.
- Latest-50 SMS, contacts, latest-100 call-history, and calendar exports after disclosure and permission approval.
- System-picker gallery, arbitrary document, and user-exported browser-file selection. Private browser databases are not accessed.
- One-time, foreground, user-initiated clipboard preview; no clipboard listener is registered.
- Visible system-camera capture and visible microphone recording with timer, Stop/Cancel controls, local playback, and cleanup on leaving/destroying the activity.
- Private temporary exports/captures with preview, Android Share, explicit Telegram delivery, deletion controls, size limits, and partial-file cleanup.
- Accessibility is limited to explicit global Back/Home/Recents/notifications/quick-settings actions. Window content, passwords, text, and key events are not collected; key events return unhandled.
- Hotspot/tethering opens Android settings for local confirmation. No hidden hotspot API or unsupported toggle is claimed.
- Battery settings disclose Samsung/Android background restrictions and open the relevant user-controlled settings.

### Telegram and background operation

- Original embedded bot token/chat configuration is blanked in the repaired asset. A replacement token, destination chat ID, and authorized numeric user ID are configured locally; blank token input retains the saved token.
- Supported incoming commands are `/help`, `/status`, `/battery`, `/network`, and `/hotspot`.
- Both `message.from.id` and `callback_query.from.id` must be JSON integers matching the authorized numeric user ID. The update must also be in the configured private chat. Other senders/chats are not executed and produce a rate-limited rejection notice to the configured owner chat.
- Empty/unknown commands receive an unsupported-command response. Callback queries receive an acknowledgement after the response attempt.
- Each new command session primes its offset with the newest pending update and discards stale queued commands. Update IDs are persisted before execution to prevent duplicate command execution after a crash.
- Polling uses timeouts, bounded batches, exponential backoff, Telegram `retry_after`, and fatal 401/409 handling. Long rate limits stop polling rather than retrying early.
- Telegram delivery requires HTTP success plus Bot API JSON `ok: true`; empty HTTP 200 responses are failures.
- Responses are read with a 4 MiB bound. Outgoing text is chunked within Bot API limits and oversized text is rejected accurately. Uploads have a 45 MiB local limit.
- Ambiguous send timeouts/5xx responses are not automatically replayed, avoiding duplicate owner-approved data. Only explicit, reasonably bounded rate-limit responses are retried.
- Active owner-enabled live location uses a visible foreground notification, real enabled-provider updates on a callback thread, at-most-once handling per fix, and at most one send per minute. The initial report may use a timestamped last-known fix. Stop/error paths remove location updates and interrupt workers.
- Boot/restart only resumes previously owner-enabled work. No hidden restart alarm/loop is used. Newer Android background-start denials are caught rather than presented as successful restarts.
- Original `:background_service` architecture is retained. No service-process migration was made.

### Manifest/resource changes

- Added a non-exported, grant-based AndroidX `FileProvider` and cache-only `res/xml/file_paths.xml` for approved camera/share workflows.
- Added `BLUETOOTH`, `READ_CALENDAR`, `PACKAGE_USAGE_STATS`, and `FOREGROUND_SERVICE_LOCATION` declarations needed by the implemented workflows.
- Removed unused broad/privileged declarations: `READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_PHONE_STATE`, `BATTERY_STATS`, `SYSTEM_ALERT_WINDOW`, and `WAKE_LOCK`. Gallery/document access now uses the system picker.
- Disabled debugging, backup, cleartext traffic, application/service persistent flags, launcher hiding, lock-screen display, and recents exclusion.
- BootReceiver direct-boot handling and `LOCKED_BOOT_COMPLETED` were removed because credential/preferences state requires unlocked storage. The unrelated AndroidX Room direct-boot flag remains unchanged.
- Accessibility configuration no longer requests window content, enhanced web accessibility, or key-event filtering.

### Deliberately not claimed as repaired

- Notification-content access is not implemented.
- Automatic hotspot enable/disable is not implemented; only the settings workflow is available.
- Private browser-history extraction, Wi-Fi passwords, remote arbitrary shell execution, covert Accessibility capture, background camera/microphone surveillance, and protection bypasses are not implemented.
- Direct on-device bot tokens remain extractable from a reverse-engineerable device; a user-controlled backend relay would be needed for stronger token protection.

## Static verification actually performed — 2026-10-23

Machine-readable result: `repair/work/static-verification.json`.

- Kotlin compilation: **passed**, with deprecation warnings only.
- D8 conversion: **passed**; all six rebuilt DEX files have DEX 035 headers and parse successfully.
- Apktool full build and independent second decode: **passed**.
- ZIP CRC/integrity: **passed**.
- Original identity and backup: **passed**; both still equal the baseline SHA-256.
- Duplicate-class check: **passed**; no duplicate DEX class definitions.
- Original preservation: **passed**; 15,506 original classes, 103,435 original methods, and all original fields remain present.
- Instruction comparison: **passed**; 103,423 original method bodies are instruction-equivalent and exactly the 12 approved component entry-point bodies changed.
- Reference validation: **passed**; 1,027 application/Kotlin/AndroidX references from new/changed methods resolve, including inherited methods. The real AndroidX `FileProvider.getUriForFile` reflection target was separately verified.
- Rebuilt size: 15,600 classes and 103,987 methods across six DEX files, including 94 new helper classes and five approved lifecycle bridges.
- Resource comparison against a same-toolchain no-op rebuild: only the intended FileProvider resource/ID, Accessibility configuration, two sanitized asset files, manifest, five component smali files, new helper DEX, and explicitly preserved original metadata changed.
- Layouts are unchanged; six launcher foreground PNGs are pixel-identical despite possible PNG container recompression.
- All 75 original non-signature `META-INF` entries are present and byte-identical. Original signature files were not carried over.
- The original bot token is absent from every decompressed entry in the rebuilt APK.
- No signature files are present in the unsigned artifact.
- Final unsigned-to-signed comparison: **passed**. Only `META-INF/MANIFEST.MF`, `META-INF/W8REPAIR.SF`, and `META-INF/W8REPAIR.RSA` were added; every unsigned entry, including the manifest, resources, and all six DEX files, is byte-identical in the signed APK.
- Final signed ZIP CRC, Apksig, and Androguard signature checks: **passed**. v1/v2/v3 are present and verified; no v4 sidecar is required.

## Not tested / remaining limitations

- **No emulator test has been performed.**
- **No installation or runtime test on the Samsung Android 6 device has been performed.**
- WebView rendering, Samsung camera output, microphone capture, provider updates, Telegram network delivery/polling, permission dialogs, boot behavior, and Accessibility actions still require real-device validation.
- Newer Android restrictions (background starts, package visibility, notification permission, foreground-service rules, and newer Bluetooth permissions) are not evidence of Android 6 success. The requested target remains Samsung Android 6/API 23.
- Installed-app reports can be limited by package visibility on Android 11+.

## User-approved signing completed and installation impact

The original private signing key was unavailable. On 2026-10-23 the user explicitly approved a new repair-only signing identity after being told about update incompatibility and possible app-data/permission loss. The new certificate differs from the original and **cannot be installed as an in-place update** over the original. Manual removal of the original would be required first and can delete its app data and runtime permission grants. Back up anything important first; no uninstall or installation was performed by this repair process.

Android 6-compatible v1 signing is present and verified, with v2/v3 also verified for newer Android versions. The private key remains only in ignored `repair/local/` and was not printed, committed, or included in the APK. Preserve that key securely if future updates with the same repair identity are needed.

Apksig reports 75 warnings that original non-signature `META-INF` metadata/service entries are not covered by the v1 JAR signature. The original APK has the same 75 warnings; this inherited Android 6/v1 coverage limitation is not a verification failure. The repaired APK otherwise passes v1 verification, and v2/v3 verification passes on versions supporting those schemes.

The exposed original bot token remains present in the immutable original/repository history and should be revoked/rotated regardless of its removal from the repaired build.
