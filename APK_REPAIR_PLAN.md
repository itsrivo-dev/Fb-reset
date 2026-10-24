# W8PremiumYT APK Repair Plan

**Status:** Planning and static inspection only. No functional patch has been applied and no repaired APK is being claimed or delivered yet.

**Target:** Preserve the existing `com.w8sb.w8premiumyt` application, its YouTube interface, resources, and legitimate device-owner functionality while repairing privacy, security, permission, lifecycle, Telegram, and Android 6/API-23 defects.

## 1. Immutable baseline and evidence

- Original artifact: `W8PremiumYT All In One.apk`.
- Original SHA-256: `20d0701a59be76762a24d269fd4df42ff05638a63df642aeaee18b3793108111`.
- The tracked original remains the canonical backup. A separate byte-identical analysis backup was also verified under `/tmp/fb-reset-backups/`; it must not be treated as a durable release artifact.
- Package/version: `com.w8sb.w8premiumyt`, `1.0 (1)`.
- SDK declarations: minimum 21, target 34, compile 34. Installation on API 23 is therefore allowed in principle.
- Application code is concentrated in `classes3.dex`: five principal classes (`MainActivity`, `UploadService`, `BootReceiver`, `RestartService`, and `W8AccessibilityService`) plus coroutine/lambda bridges and `LiveLiterals` constant holders. The other DEX files are predominantly libraries.
- The only app-specific layout is the existing title-plus-WebView layout. App-specific XML also covers Accessibility and backup rules. Assets contain the WebView/configuration file and its README.
- No Gradle project, Android source, original signing keystore, or tests are present in the available repository/history.
- Apktool 2.9.3 completed a full no-op rebuild. All five DEX files retained their class/method sets, and all 99,091 method bodies containing code retained identical instructions. Re-decoding found equivalent manifest/resources; four re-encoded launcher images had identical pixels.
- A no-op artifact was test-signed with a temporary certificate and had v1/v2/v3 signature blocks. This was packaging evidence only, not a repaired or device-tested release.

**Verification limit:** No feature below is labelled “working” merely because code exists. No physical Samsung test, mobile-data test, installation test, or live Telegram test has yet been performed. “Confirmed problem” means a code/manifest defect established statically; runtime impact still needs device evidence where noted.

## 2. Repair rules and intent boundaries

1. Patch a decoded working copy, never the original APK.
2. Prefer the smallest Smali/manifest/resource change. Keep package name, app title, existing layout, WebView URL, resources, and unrelated library code unchanged.
3. Preserve a feature’s useful owner-authorized purpose where technically possible. Do not equate preserving a purpose with preserving covert automation.
4. The current asset README explicitly describes automatic collection, background camera/microphone operation, icon hiding, and fake errors. Sensitive code has no existing consent UI. For those features, the plan preserves owner-initiated capture, backup, transfer, or reporting, but **not** the original hidden workflow.
5. A temporary internal patch that disables an unsafe entry point is not a final feature-preserving deliverable. Final delivery requires either a working safe workflow or an explicit, approved explanation of why no safe implementation is feasible.
6. Sensitive exports and captures require local, informed user action. Telegram authentication alone will not turn remote SMS scraping, gallery upload, clipboard capture, or camera/microphone recording into a transparent workflow.
7. No arbitrary shell, password/key capture, hidden operation, security bypass, trust-all TLS, fake result, placeholder button, or simulated success.
8. Maintain a patch-by-patch changelog, before/after Smali diffs, permission/component diffs, hashes, and verification evidence. Never commit credentials, private exports, signing keys, or logs containing secrets.

## 3. Feature-by-feature repair plan

### F01 — Existing title UI and YouTube WebView

- **Existing behavior:** `MainActivity` inflates the existing `activity_main` layout, enables JavaScript/DOM storage/zoom, loads the asset-configured YouTube URL, and uses a small `WebViewClient`.
- **Problem identified:** Mixed content is explicitly allowed (`MIXED_CONTENT_ALWAYS_ALLOW`). File/content access is not restricted. Any WebView error replaces the page with `about:blank`, losing the YouTube UI without actionable diagnostics. WebView destruction is incomplete, and old Android WebView/YouTube compatibility is unverified.
- **Safe repair proposed:** Keep JavaScript and DOM storage required by YouTube, require HTTPS, disable mixed content and unnecessary file/content access, preserve the current layout, and show a visible retry/error state instead of silently blanking the page. Clean up WebView callbacks and lifecycle references.
- **Purpose/interface preservation:** The original YouTube page, title, layout, and navigation remain. No replacement interface is proposed.
- **Permissions/user actions:** No new Android permission. The user may need to update Android System WebView from a trusted source if YouTube no longer supports the installed version.
- **Android 6 expectation:** The WebView APIs used by the repair are available on API 23. YouTube’s remote web compatibility cannot be guaranteed by the APK; an installed YouTube-app handoff is only a documented fallback, not a silent replacement.
- **Verification method:** Diff the original layout/resources; inspect WebView settings; on the Samsung device test initial load, sign-in/navigation where applicable, rotation, back/retry, offline/mobile-data errors, and activity destruction. Record the WebView version with any failure.

### F02 — Configuration and Telegram credential handling

- **Existing behavior:** Three classes independently read plaintext `bot_token` and `chat_id` from `assets/app_config.json`; feature flags are also read from that asset.
- **Problem identified:** The bot token is embedded and the public repository exposes it. An asset flag can enable sensitive behavior without a local consent decision. Configuration is duplicated and lacks validation; parse/read failures can fall through to empty/default configuration instead of stopping unsafe work cleanly. A destination chat ID is not authentication.
- **Safe repair proposed:** Revoke/rotate the exposed token. Remove real credentials from Git and assets. Centralize validated configuration. For direct mode, obtain a replacement token through a local, untracked setup flow and store it app-privately, while documenting that an APK/device is not a secure secret store. Prefer a backend relay when token confidentiality is required. Store a separate authorized numeric Telegram user ID.
- **Purpose/interface preservation:** Existing Telegram reporting and WebView configuration remain; only credential provisioning and consent source change. No secret is printed in logs.
- **Permissions/user actions:** The owner must rotate the bot token and complete local setup. Backend mode additionally requires a real owner-controlled relay and device credential.
- **Android 6 expectation:** Private `SharedPreferences` and HTTPS are supported. Neither direct nor backend mode can be certified until the actual Samsung TLS/mobile-data path is tested.
- **Verification method:** Secret-scan source, decoded APK, Git diff, and logs; verify missing/invalid configuration produces a visible setup error; confirm no asset contains a usable credential. Test authorized and unauthorized Telegram identities separately.

### F03 — Permission onboarding and special-access activation

- **Existing behavior:** Startup requests storage, contacts, SMS, call log, location, camera, and microphone together; denial can re-prompt once and then finish the app. Accessibility Settings opens automatically once. Battery-optimization exemption is requested at launch. Permission names can be sent to Telegram.
- **Problem identified:** This is bulk rather than feature-context consent. `requirePermissions` is read but not honored. `READ_PHONE_STATE`, `GET_ACCOUNTS`, and `READ_CALENDAR` are not handled by the requester despite related code. Usage Access and Bluetooth are missing. The Accessibility enabled check relies on a fragile substring. YouTube is unnecessarily conditioned on unrelated permissions.
- **Safe repair proposed:** Let YouTube launch without sensitive permissions. Request each dangerous permission only when its owner-initiated feature is used, with rationale and denial handling. Add separate disclosed flows for Accessibility, Usage Access, notification access, and battery optimization. Keep permission status local unless an approved status report explicitly includes it. Compare actual component names for Accessibility.
- **Purpose/interface preservation:** Existing features remain reachable through small additive dialogs/actions; the original YouTube workflow is no longer blocked by unrelated permissions.
- **Permissions/user actions:** Runtime permissions on API 23; special Settings activation for Accessibility and Usage Access; battery exemption only after the user enables continuous background work.
- **Android 6 expectation:** Runtime permissions and these Settings screens exist, but Samsung labels/behavior require physical verification.
- **Verification method:** Inspect every permission call site and manifest entry. Test grant, deny, “never ask again” where available, revocation while running, and process recreation for each feature independently.

### F04 — Launcher visibility, fake errors, recents, and lock-screen visibility

- **Existing behavior:** Dormant asset flags can hide the launcher icon after 30 seconds or show a fake incompatibility dialog and close the UI while starting the service. The activity is excluded from recents and declares lock-screen visibility.
- **Problem identified:** These behaviors conceal operation or mislead the user. They are not necessary for YouTube, backup, or device management.
- **Safe repair proposed:** Remove the deceptive enable paths; keep the launcher visible and use honest error messages. Return the activity to recents and do not show sensitive content over the lock screen. Do not merely leave the flags set to false while preserving one-click covert reconfiguration.
- **Purpose/interface preservation:** Normal launch and YouTube behavior are preserved. Concealment is an unavoidable behavior removal because it has no transparent device-management purpose.
- **Permissions/user actions:** None.
- **Android 6 expectation:** Component enable/disable and recents behavior are supported; unsupported newer manifest attributes must not be relied upon.
- **Verification method:** Inspect call graphs and manifest; test launcher presence, recents, honest offline errors, process restart, and lock-screen behavior.

### F05 — Device identity and battery status

- **Existing behavior:** `getBasicDeviceInfo` reports build fields, battery level/status, network details, and attempts to read a telephony device ID on versions below 29.
- **Problem identified:** IMEI-like stable identifiers are unnecessary for ordinary status and require sensitive phone permission on API 23. The code mixes benign status with identifiers. `BATTERY_STATS` is declared although the sticky battery Intent does not need it and privileged battery APIs are unavailable to an ordinary app.
- **Safe repair proposed:** Retain model/build/battery/charging status. Use an owner-assigned device label and, if needed, a random app-scoped installation ID instead of hardware identifiers. Remove unused privileged permission only after call-site verification.
- **Purpose/interface preservation:** Device identification for the owner and battery reporting remain; stable hardware identifier export changes to privacy-preserving identification.
- **Permissions/user actions:** No phone permission for the revised status. Optional device label configured locally.
- **Android 6 expectation:** Sticky `BATTERY_CHANGED` and build fields work on API 23.
- **Verification method:** Compare reported values with Samsung Settings and `dumpsys battery`; verify no IMEI/MAC is queried or transmitted; inspect `/status` and `/battery` responses.

### F06 — Network, Wi-Fi, public IP, and Bluetooth reporting

- **Existing behavior:** The service enumerates local IPv4 addresses, current Wi-Fi SSID/MAC, configured Wi-Fi networks, bonded Bluetooth devices, and can query `api.ipify.org` for public IP.
- **Problem identified:** These are bundled into automatic reports. Public-IP lookup discloses a request to a third party. Android 6 normally returns a placeholder Wi-Fi MAC to ordinary apps. SSID visibility can depend on location/services. Bluetooth permission is absent. Some configured-network behavior changes on newer Android.
- **Safe repair proposed:** Provide separate local `/network` status for active transport, connectivity, local addresses, and available Wi-Fi state. Make public-IP lookup an explicit option with endpoint disclosure. Report unavailable fields honestly. Add Bluetooth permission only for an owner-initiated bonded-device report; do not attempt MAC/SSID security bypasses.
- **Purpose/interface preservation:** Network/device status remains. Saved-network and bonded-device lists become explicit exports rather than automatic collection.
- **Permissions/user actions:** `ACCESS_NETWORK_STATE`/`ACCESS_WIFI_STATE`; Bluetooth permission for bonded devices on API 23; location only where SSID access requires it; explicit consent before public-IP lookup.
- **Android 6 expectation:** Basic reporting is supported. Real Wi-Fi MAC retrieval is not a supported requirement; SSID results vary by Samsung settings.
- **Verification method:** Test Wi-Fi and mobile data, airplane mode, network transitions, location-off SSID behavior, Bluetooth off/on, and third-party lookup consent. Compare with `dumpsys connectivity`/`dumpsys wifi` without logging sensitive identifiers.

### F07 — Installed applications and usage statistics

- **Existing behavior:** Batch export includes installed app names/versions/timestamps and the previous day’s usage durations.
- **Problem identified:** Collection is automatic. Usage Access is not declared or activated, so empty/error results are likely. Newer Android also restricts package visibility, independently of the API-23 target.
- **Safe repair proposed:** Retain an owner-initiated installed-app inventory. For usage reporting, add the proper declaration and a disclosed Usage Access Settings flow, and clearly report when access is absent. Keep exports local until the user confirms a destination.
- **Purpose/interface preservation:** Device inventory/backup purpose remains; silent scheduled reporting does not.
- **Permissions/user actions:** Usage Access for usage statistics; no broad package-query permission is needed for the API-23 target. Newer-Android package visibility must be handled separately if support is requested.
- **Android 6 expectation:** `UsageStatsManager` and Usage Access are available from API 21.
- **Verification method:** Test with Usage Access off/on and compare sample durations with Samsung usage screens. Verify no collection occurs merely at startup.

### F08 — Location reporting and live location

- **Existing behavior:** The activity sends last known fused location after permission success and every 60 seconds through an unmanaged main Handler. The service has an asset-enabled last-known-location loop, configured here for 30 seconds. A “location history” report is actually last known locations from providers.
- **Problem identified:** Collection starts automatically, Handler callbacks survive activity destruction, stale fixes are not identified, duplicates are not suppressed, service restarts can repeat reports, and the “history” label is inaccurate.
- **Safe repair proposed:** Preserve one-shot owner-authorized location reporting and an explicit start/stop live-sharing session. Cancel callbacks in lifecycle methods, show fix timestamp/accuracy, distinguish stale fixes, deduplicate by provider/time, and keep a visible notification during live sharing. Rename the report to “Last known locations.”
- **Purpose/interface preservation:** Location reporting and owner-selected continuous sharing remain; invisible always-on transmission changes to a disclosed session.
- **Permissions/user actions:** Fine/coarse location requested in context; location services enabled; explicit start and stop controls.
- **Android 6 expectation:** Fused location depends on compatible Google Play services; `LocationManager` is the supported fallback. There is no separate Android 10-style background-location permission on API 23.
- **Verification method:** Test permission denied/granted, GPS off, stale/no fix, duplicate fixes, activity destruction, service restart, and visible stop behavior over mobile data.

### F09 — SMS reading and backup

- **Existing behavior:** Automatic reports query SMS address/body/date; one path attempts to limit to 50 rows using `date DESC LIMIT 50` as a provider sort order.
- **Problem identified:** Message content is collected without a user-facing backup flow. The provider-specific `LIMIT` sort string is fragile. Permission is requested at startup rather than at export.
- **Safe repair proposed:** Retain SMS only as an explicit owner-initiated viewing/backup/export workflow. Show scope and destination before reading, request permission at that point, use a normal descending sort with a bounded row counter, and keep the export app-private until confirmed. Do not add remote SMS capture commands.
- **Purpose/interface preservation:** Owner backup/transfer is preserved; automatic message collection is not. This safe purpose is proposed by the repair—the existing code does not itself prove a consent-based backup intent.
- **Permissions/user actions:** `READ_SMS`, local confirmation, and destination confirmation for every export session.
- **Android 6 expectation:** SMS provider and runtime permission are supported; OEM provider behavior must be tested.
- **Verification method:** Use a small owner-controlled test message set; verify ordering, limits, denied permission, empty inbox, local file cleanup, and that no startup/background query occurs.

### F10 — Contacts viewing and backup

- **Existing behavior:** Contacts and phone numbers are automatically exported. One method requests `data1` twice and labels the duplicate value “Email”; another exports all phone rows.
- **Problem identified:** The email field is incorrect, queries are unbounded, and collection is automatic.
- **Safe repair proposed:** Preserve contact backup with a corrected projection and separate email lookup only when selected. Prefer a system contact picker for selected-contact transfer; require a separate disclosed full-backup flow if full export is retained. Never transmit automatically at startup.
- **Purpose/interface preservation:** Selected-contact sharing and owner-authorized full backup remain possible; the incorrect email reporting and silent workflow change.
- **Permissions/user actions:** A picker can minimize broad permission use; full backup requires `READ_CONTACTS` and explicit confirmation.
- **Android 6 expectation:** Contacts provider and picker workflows are supported.
- **Verification method:** Test multiple numbers/emails, missing fields, duplicate rows, denied permission, selected-contact URI access, full-export confirmation, and output correctness.

### F11 — Call-history backup

- **Existing behavior:** Call number/type/date/duration/name is exported at startup; one query is unbounded and another uses the fragile `LIMIT` sort string. Batch export duplicates call-data collection.
- **Problem identified:** Automatic sensitive collection, duplicate work/transmission, unbounded output, and fragile query limiting.
- **Safe repair proposed:** Retain a single explicit call-history backup path with a documented row/date scope, standard sort, bounded iteration, and local preview/confirmation before Telegram transfer.
- **Purpose/interface preservation:** Owner-authorized backup remains; automatic and duplicate reporting is removed.
- **Permissions/user actions:** `READ_CALL_LOG` requested only for this workflow; explicit export confirmation.
- **Android 6 expectation:** Call-log provider and runtime permission are supported; Samsung may add call types that need an “unknown/other” fallback.
- **Verification method:** Test incoming/outgoing/missed/rejected/unknown types, limit handling, permission denial, empty history, and confirmation cancellation.

### F12 — Gallery, media, and documents

- **Existing behavior:** The service enumerates external media metadata and loops over new images using `_data` file paths. It starts with an in-memory timestamp of zero and advances that timestamp without checking Telegram success.
- **Problem identified:** First service start can select the entire gallery; restart resets the watermark and can duplicate uploads; failed uploads can be treated as complete; broad collection occurs every few seconds; raw file paths are fragile on newer Android. Generated text/ZIP documents are also uploaded without a user-facing export flow.
- **Safe repair proposed:** Preserve owner-initiated photo/document transfer using Storage Access Framework pickers and `content://` streams. For a disclosed full-backup workflow, use an explicit scope and durable, success-based checkpoint. Do not run an automatic gallery loop. Reuse the real Telegram document/photo transport only after confirmation.
- **Purpose/interface preservation:** Photo backup and document transfer remain; covert bulk upload and duplicate-prone polling do not.
- **Permissions/user actions:** Selected SAF documents need no broad storage permission; API-23 full media backup needs storage permission and separate confirmation.
- **Android 6 expectation:** SAF is available from API 19. Telegram file-size limits and Samsung document providers must be handled honestly.
- **Verification method:** Test selected images/documents, large/unsupported files, revoked URI access, mobile-data interruption, retry without duplicates, and checkpoint behavior across process death.

### F13 — Calendar export

- **Existing behavior:** Batch export attempts to read all calendar events but `READ_CALENDAR` is absent from the manifest and permission requester.
- **Problem identified:** The existing path cannot obtain permission through the current flow and collects all events automatically.
- **Safe repair proposed:** If calendar backup is retained, add a separate date-bounded, owner-confirmed export with the manifest/runtime permission. Otherwise document that this hidden, currently nonfunctional routine is not an existing usable feature before removing it.
- **Purpose/interface preservation:** A legitimate calendar-backup purpose can be preserved, but it requires a new visible workflow rather than the current automatic path.
- **Permissions/user actions:** `READ_CALENDAR`, date-range selection, and export confirmation.
- **Android 6 expectation:** `CalendarContract` and runtime permission are supported.
- **Verification method:** Test permission off/on, empty calendar, date bounds, timezone formatting, multiple calendars, and no startup access.

### F14 — Account inventory

- **Existing behavior:** Account names/types are included in automatic device reports and photo captions may include the first account name.
- **Problem identified:** Account identifiers are unrelated to a normal photo and are collected without feature-specific consent. `GET_ACCOUNTS` is not requested by the current permission flow.
- **Safe repair proposed:** Remove account identifiers from ordinary status/photo metadata. If an account inventory is genuinely needed, retain it only as a separate owner-initiated report with explicit scope and permission; otherwise prefer account counts/types without names where the platform permits.
- **Purpose/interface preservation:** Device reporting remains without unnecessary identifiers. A separate inventory can be preserved if its legitimate purpose is confirmed.
- **Permissions/user actions:** `GET_ACCOUNTS` only for the optional API-23 inventory, with explicit export confirmation.
- **Android 6 expectation:** AccountManager behavior differs from newer Android; permission and actual Samsung providers require testing.
- **Verification method:** Inspect all captions/reports for account data; test denied/granted permission and ensure normal photo/status workflows never query accounts.

### F15 — Browser history

- **Existing behavior:** Batch export queries legacy browser and Chrome bookmark/history content providers and often returns an “not accessible” message.
- **Problem identified:** There is no reliable general public API for another browser’s history. The current code cannot promise this feature on Samsung/Android 6 or newer Chrome.
- **Safe repair proposed:** Do not bypass provider protections. Preserve the owner’s export purpose through a user-selected browser-export/bookmarks file or supported share workflow where the installed browser offers one. Clearly label unsupported direct history access.
- **Purpose/interface preservation:** Owner-initiated import/transfer of an exported file is the closest supported alternative; automatic history collection cannot be retained reliably.
- **Permissions/user actions:** File selection through SAF; no attempt to obtain another app’s private data.
- **Android 6 expectation:** SAF works; browser-specific export availability depends on the installed Samsung/Chrome version.
- **Verification method:** Test with the actual browser’s exported file and verify unsupported direct access produces an honest local explanation rather than fabricated data.

### F16 — Clipboard functions

- **Existing behavior:** A service listener transmits every clipboard change; batch export also reads the current clip. The listener is not unregistered.
- **Problem identified:** Silent clipboard monitoring can capture passwords and personal data and creates a lifecycle leak.
- **Safe repair proposed:** Remove background listener registration. Preserve a foreground, user-initiated “read/share clipboard” action that displays the content locally and requires confirmation before transmission. Never include clipboard content in general backups.
- **Purpose/interface preservation:** Explicit clipboard transfer remains; monitoring does not.
- **Permissions/user actions:** No special API-23 permission, but explicit foreground action and send confirmation are required.
- **Android 6 expectation:** Clipboard read is supported. Later Android versions impose additional background restrictions; the repair must not attempt to bypass them.
- **Verification method:** Change clipboard while app is closed and confirm no network/query; test explicit share, cancellation, empty clip, non-text clip, and activity destruction.

### F17 — Camera capture

- **Existing behavior:** Camera2 captures 640×480 JPEG frames through `ImageReader` in a repeating background loop, currently configured for 30-second intervals, then sends photos with extensive metadata.
- **Problem identified:** There is no visible capture session or system-style indicator on Android 6. Camera/thread/callback cleanup and error paths need repair, the separate back-camera flag is read but not used by the capture loop, capture success is not tied to Telegram success, and unnecessary account/location/device metadata is attached. Actual Samsung Camera2 behavior is unverified.
- **Safe repair proposed:** Preserve owner-initiated photo capture in a visible capture workflow with an obvious active indicator and stop/close control. Capture only after local action, show the result, and require confirmation before sending. Remove unrelated metadata and release camera/ImageReader/threads in every exit path. Keep the original Camera2 implementation only where it works; investigate a visible Camera1 fallback for affected Samsung hardware rather than silently failing.
- **Purpose/interface preservation:** Taking and sending owner-approved photos remains. Repeating invisible capture cannot be preserved safely.
- **Permissions/user actions:** `CAMERA` requested in context; local capture action and send confirmation. Location/account metadata is not required.
- **Android 6 expectation:** Camera2 exists from API 21 but OEM support varies. Android 6 has no built-in modern camera indicator, so the app’s visible indicator is essential.
- **Verification method:** Test front/rear selection, denied permission, busy camera, orientation, capture timeout, activity/service destruction, file integrity, cancellation, and confirmed send/retry on the Samsung phone.

### F18 — Microphone recording

- **Existing behavior:** `MediaRecorder` records five-second M4A clips in a repeating background loop, currently configured every 30 seconds, sends them, and deletes local files without a reliable success result.
- **Problem identified:** Recording is not transparently controlled, there is no Android 6 system microphone indicator, and failure can lose the only local recording while still allowing repeated transmission attempts.
- **Safe repair proposed:** Preserve explicit owner-initiated audio recording with visible start/stop, elapsed time, a persistent active notification if the service is involved, local playback/confirmation, and success-based cleanup. No autonomous recording loop or remote microphone command.
- **Purpose/interface preservation:** Recording and approved Telegram transfer remain; hidden scheduled recording does not.
- **Permissions/user actions:** `RECORD_AUDIO` requested in context; local record/stop and send actions.
- **Android 6 expectation:** MediaRecorder is supported, but Samsung audio source/format behavior requires device testing.
- **Verification method:** Test deny/grant, recorder busy, start/stop, short recording failure, process death, playback, file retention on network failure, and deletion only after confirmed handling.

### F19 — Accessibility service

- **Existing behavior:** The service reads active-window text recursively, transmits window/content changes and typed text, requests key-event filtering, and sends physical key descriptions. No assistive/global device-management action is implemented.
- **Problem identified:** This is screen-content and key capture, not an accessible control workflow. It can expose passwords and private content and generates duplicate/unbounded network events.
- **Safe repair proposed:** Stop all text/key extraction and transmission. Retain the service only for clearly disclosed, owner-enabled actions supported by Accessibility, such as explicit global navigation or user-selected node actions. Provide a local purpose/activation screen and a visible enabled state. If those safe actions are not implemented, the service must remain disabled rather than preserving a nonfunctional toggle.
- **Purpose/interface preservation:** Supported assistive/device-management actions are the closest safe alternative. The existing capture behavior cannot be retained; no current capture UI/workflow is lost because none existed.
- **Permissions/user actions:** Explicit activation in Accessibility Settings after disclosure; no automatic Settings launch or permission reporting.
- **Android 6 expectation:** Global/node actions are supported. `dispatchGesture()` is API 24 and arbitrary touchscreen injection must not be promised on API 23.
- **Verification method:** Inspect event masks/flags and call graph; verify text/password/key events cause no reads or network calls; test every supported action and service disable/revoke behavior on Samsung.

### F20 — UploadService foreground lifecycle and wake lock

- **Existing behavior:** Service startup acquires a ten-minute wake lock, launches automatic work, and returns `START_STICKY`. On pre-26 it calls `startForeground` then immediately `stopForeground(true)`; on 26+ it creates a blank “Hidden Service” channel/notification and cancels its notification. `onDestroy` and `onTaskRemoved` restart it.
- **Problem identified:** Operation is deliberately hidden, restart loops are possible, idle work can consume power, and `isUploading` conflates transfer state with loop lifetime. This does not provide reliable Samsung background execution.
- **Safe repair proposed:** Separate user session state, active transfer state, and polling state. Use a visible foreground notification only during owner-enabled continuous work, stop foreground when idle, cancel jobs/listeners deterministically, and hold a wake lock only around bounded active work in `try/finally`. Do not restart after explicit user stop or revocation.
- **Purpose/interface preservation:** Legitimate transfers, live sessions, and polling remain; hidden persistence changes to Android-supported visible operation.
- **Permissions/user actions:** Foreground-service permission where applicable; notification permission only on API 33+; explicit background-session opt-in and stop.
- **Android 6 expectation:** `startForeground` works without channels; Doze still limits background networking. `android:persistent="true"` does not make this ordinary app persistent.
- **Verification method:** Inspect all start/stop paths; test swiping from recents, explicit stop, process kill, reboot, Doze, permission revocation, wake-lock release, and Samsung battery settings. Use logcat/dumpsys as evidence.

### F21 — BootReceiver, RestartService, and restart alarm

- **Existing behavior:** BootReceiver starts UploadService for boot/quick-boot. The alarm sends `RESTART_SERVICE` to BootReceiver, but BootReceiver ignores that action. RestartService starts UploadService, stops itself, and returns sticky. UploadService also restarts RestartService on destruction/task removal.
- **Problem identified:** The restart alarm is logically unmatched; sticky cycles can duplicate work; restarts occur without checking owner consent or pending legitimate work. `LOCKED_BOOT_COMPLETED`/direct-boot declarations are not useful to this API-23 target and need proper handling on newer versions.
- **Safe repair proposed:** Replace the cycle with one explicit scheduling policy. Start after boot only if the owner previously enabled a resumable feature or approved pending work exists. Make one-shot restart non-sticky and idempotent, or remove it only after a replacement passes tests. Do not restart sensitive automatic collection.
- **Purpose/interface preservation:** Legitimate startup/resume behavior remains; forced persistence and duplicate collection do not.
- **Permissions/user actions:** Boot permission remains only if an opted-in background feature needs it; user-visible setting to disable startup work.
- **Android 6 expectation:** Normal boot broadcast works. Exact/alarm and Doze behavior require Samsung testing; no API-24 direct-boot behavior should be assumed.
- **Verification method:** Test enabled/disabled boot state, direct `RESTART_SERVICE` delivery in a controlled build, duplicate intents, null-intent restarts, process death, and reboot without repeating completed exports.

### F22 — Outbound Telegram transport and reporting

- **Existing behavior:** OkHttp has 30-second connect/read/write timeouts; several `HttpURLConnection` paths have no explicit timeouts. Responses are often logged but not returned as success/failure. ZIP/clipboard paths ignore response status; photo timestamps and local cleanup can advance regardless of success. There is no shared backoff/rate-limit/outbox policy.
- **Problem identified:** Callers cannot reliably distinguish delivered, rejected, rate-limited, or offline operations. Retries/duplicates and misleading completion are possible. Tokens appear in request URLs and must never be logged.
- **Safe repair proposed:** Introduce one authenticated transport result type for approved messages/files, validate HTTP and Telegram JSON `ok` responses, close bodies, bound retries with exponential backoff/jitter, honor Telegram `retry_after`, and persist pending user-approved work without duplicating completed operations. Treat permanent 4xx errors as failures requiring correction, not infinite retries.
- **Purpose/interface preservation:** Real Telegram messaging and approved reports remain. No mock responses or fabricated delivery state.
- **Permissions/user actions:** Internet/mobile data and valid owner configuration; explicit consent for each sensitive export.
- **Android 6 expectation:** OkHttp/TLS should support API 23, but actual Samsung certificate/TLS/mobile-data behavior is a required runtime test.
- **Verification method:** Static response-path inspection plus real test-bot transfers for text/photo/document/audio. Test offline recovery and cancellation. Parser tests may use sanitized fixtures, but only real Telegram responses count as end-to-end delivery evidence.

### F23 — Incoming authenticated Telegram command processor

- **Existing behavior:** Absent. No `getUpdates`, command parser, sender authorization, offset persistence, or update deduplication exists.
- **Problem identified:** There is no incoming management function to repair; it must be added without pretending it already existed.
- **Safe repair proposed:** Add a small allowlisted processor for `/help`, `/status`, `/battery`, and `/network` first. Authenticate numeric `message.from.id` and `callback_query.from.id`, reject unauthorized/invalid commands, persist offsets, make update handling idempotent, and add polling timeouts/backoff. Add `/notifications` and `/hotspot` only after their local prerequisites exist. Do not add shell or remote sensitive capture/export commands. Any Device Administrator lock command remains a separate optional change requiring explicit local admin activation.
- **Purpose/interface preservation:** This is additive remote management; the original UI remains unchanged apart from necessary local setup/consent.
- **Permissions/user actions:** Authorized numeric user ID and bot/relay setup. Continuous polling requires an owner-enabled background session; sensitive features additionally require local activation.
- **Android 6 expectation:** Telegram polling itself is compatible; Doze/Samsung process limits mean real-time polling cannot be promised while the app is killed or network is suspended.
- **Verification method:** Test authorized user, wrong user, wrong chat, malformed commands, duplicate updates, offset restart, conflicting webhook/getUpdates errors, offline recovery, and mobile data. Never use only `chat_id` as authorization.

### F24 — Notifications

- **Existing behavior:** The only app-specific notification behavior is the hidden service notification. There is no `NotificationListenerService` and no notification forwarding.
- **Problem identified:** `/notifications` cannot be implemented by the existing components. Notification content may contain sensitive information and requires a separate special permission.
- **Safe repair proposed:** First make the app’s own service notification honest and visible. Then optionally add a declared Notification Listener with a local explanation, user-selected app allowlist, and content redaction/default-off policy. Forward only during an owner-enabled session and show its active state.
- **Purpose/interface preservation:** Legitimate notification reporting is additive; the original UI can gain a small local activation control without redesign.
- **Permissions/user actions:** Explicit Notification Access in Settings; API-33 notification permission is separate and only concerns this app’s posted notifications.
- **Android 6 expectation:** `NotificationListenerService` is available from API 18; OEM Settings behavior needs testing.
- **Verification method:** Test listener off/on, selected versus blocked apps, redaction, service restart, duplicate notifications, and revocation. Do not capture notification content before access is enabled.

### F25 — Hotspot state and tethering workflow

- **Existing behavior:** Absent. Existing Wi-Fi reporting is not hotspot management.
- **Problem identified:** An ordinary API-23 app has no supported, reliable public hotspot-toggle API. Hidden API/reflection behavior varies by Samsung build and must not be presented as dependable.
- **Safe repair proposed:** Implement `/hotspot` as honest connectivity/Wi-Fi status plus a supported workflow that opens tethering or wireless settings for local confirmation. Resolve the Intent first and fall back to general wireless settings. Do not claim a toggle occurred.
- **Purpose/interface preservation:** Adds the closest supported device-management workflow without changing existing Wi-Fi reporting semantics.
- **Permissions/user actions:** Local confirmation in Samsung Settings; no privileged-permission bypass.
- **Android 6 expectation:** Settings navigation is supported; actual hotspot state/toggle cannot be guaranteed through public APIs.
- **Verification method:** Test Settings resolution/fallback on the exact Samsung build and verify responses clearly distinguish “settings opened” from “hotspot enabled.”

### F26 — Manifest, backup, and application security posture

- **Existing behavior:** The app is debuggable, allows unrestricted backup, declares persistent operation, excludes itself from recents, and requests several unused or privileged permissions. `fullBackupContent` and cloud extraction rules have no exclusions.
- **Problem identified:** Debug/backup settings can expose app data and future credentials/exports. `persistent` is ineffective for an ordinary app. Unused permissions increase risk and make permission reporting inaccurate.
- **Safe repair proposed:** Disable debugging in the release patch, prevent backup of credentials/private exports (prefer no automatic backup unless a safe exclusion set is proven), remove ineffective persistence, explicitly set non-exported components where appropriate, and reconcile every permission with a retained call site. Keep required library components/resources intact.
- **Purpose/interface preservation:** Legitimate UI and features remain; security-only manifest changes are documented individually rather than hidden.
- **Permissions/user actions:** Reinstall may be required for some changes to be evaluated cleanly; no uninstall will be performed automatically.
- **Android 6 expectation:** Full-backup rules apply; newer data-extraction rules do not. Special permission behavior must be checked against API 23, not inferred from target 34.
- **Verification method:** Compare decoded manifests and backup rules, enumerate components/permissions, run secret and exported-component audits, and inspect `dumpsys package` after installation.

### Static code-coverage notes

- All 53 app-specific classes are accounted for: the five principal classes above, their lambda/coroutine implementations, and five `LiveLiterals` constant holders. Lambda/coroutine classes are not separate user features.
- AndroidX WorkManager/Room/Startup/ProfileInstaller and Google API components are library infrastructure, not evidence of additional working app features. Preserve them unless an approved patch specifically changes their use.
- `sendKeyToTelegram` in `MainActivity` has no discovered call site; it is dead sensitive code rather than a working feature and should be removed after a final cross-DEX reference check.
- `backgroundCameraBack` is read but unused, `requirePermissions` is ineffective, contact/call collection is duplicated, and the restart action is unmatched. These are implementation defects, not reasons to add more automatic collection.
- No existing incoming-command, notification-listener, hotspot-management, Device Administrator, arbitrary-shell, or API-23 gesture-injection implementation was found.

## 4. Permission correction matrix

| Permission/setting | Current finding | Planned disposition |
|---|---|---|
| `INTERNET`, network/Wi-Fi state | Needed for real status/Telegram | Retain and verify mobile-data behavior. |
| Storage / media images | Used for broad gallery/media access | Prefer SAF selection; retain broad permission only for an explicit API-23 full-backup workflow. |
| Contacts, SMS, call log | Requested together at startup | Move to feature-specific owner-initiated workflows. |
| Fine/coarse location | Automatic activity/service reporting | Request in context; visible one-shot/live-session controls. |
| Camera, microphone | Asset-enabled background loops | Request only for visible user-initiated capture. |
| `READ_PHONE_STATE` | Used for stable device ID but not requested by onboarding | Remove if privacy-preserving device ID replaces hardware ID; do not request merely to preserve telemetry. |
| `GET_ACCOUNTS` | Account data collected but not requested by onboarding | Optional separate inventory only; never in ordinary status/photo captions. |
| `READ_CALENDAR` | Code checks it but manifest/requester omit it | Add only with an approved calendar-export workflow. |
| Bluetooth | Bonded-device code lacks manifest permission | Add for explicit API-23 bonded-device report. |
| Usage Access | Usage query lacks declaration/activation | Add special-access declaration and disclosed Settings flow if retained. |
| `BATTERY_STATS` | Declared but sticky battery reporting does not need it | Remove after final call-site audit; do not promise privileged battery data. |
| `SYSTEM_ALERT_WINDOW` | No app overlay call site found | Remove after final call-site audit. |
| Boot/quick-boot | Starts automatic collection | Retain only for opted-in resumable legitimate work. |
| Wake lock | Acquired at service creation | Retain only for bounded active work with guaranteed release. |
| Battery-optimization exemption | Requested at launch without rationale | Request only after explicit continuous-background opt-in. |
| Foreground service / notifications | Used to hide service | Replace with visible active-work notification; API-33 permission guarded separately. |
| Accessibility / Notification Access | Accessibility auto-opens Settings; notification listener absent | Separate explicit Settings activation with purpose disclosure. |

## 5. Telegram architecture decision

### Direct polling

- **Advantages:** No separate server; smallest deployment; can work directly over mobile data.
- **Costs:** A reusable bot token on a reverse-engineerable device/APK cannot be treated as truly secret. Token theft can disrupt polling and abuse the bot even though numeric sender-ID checks still prevent an attacker from simply forging an authorized Telegram user through Bot API.
- **Required controls:** Local credential setup, authorized numeric user ID, persisted update offsets, idempotency, real response validation, no token logging, and immediate revocation procedure.

### Backend relay

- **Advantages:** Bot token stays off the APK; centralized sender verification, rate limiting, update handling, and revocation are easier.
- **Costs:** Requires a real owner-controlled service, secure device authentication, deployment, monitoring, and availability. It must not become an unauthenticated proxy.

**Plan preference:** Backend relay when credential protection is required; direct polling is acceptable only with the documented extraction risk. This decision is not required to begin UI/privacy/lifecycle patches, but it is required before final Telegram end-to-end testing.

## 6. Implementation order and change control

### Phase 0 — Baseline and plan (current stage)

- Preserve/hash the original and backup.
- Inventory classes, components, resources, configuration, permissions, and call paths.
- Record this feature plan and runtime-evidence limitations.

### Phase 1 — Internal safety containment and smallest UI/lifecycle repairs

- Prevent automatic sensitive collection from starting before safe workflows exist.
- Keep YouTube independent of sensitive permissions; repair WebView and honest errors.
- Remove deceptive launcher/fake-error enable paths.
- Replace hidden foreground behavior and uncontrolled restart loops with explicit session state.
- This phase is an internal safety checkpoint, **not** a final APK that merely disables features.

### Phase 2 — Core legitimate reporting and Telegram foundation

- Repair local device/battery/network status.
- Implement the shared real Telegram transport and `/help`, `/status`, `/battery`, `/network`.
- Add owner-controlled background-session semantics and boot/resume behavior.
- Decide direct versus relay before live credential testing.

### Phase 3 — Safe replacements for existing sensitive features

- Implement and verify one local workflow at a time: location, SMS, contacts, call history, gallery/documents, calendar/accounts if approved, clipboard, camera, and microphone.
- Reuse existing collection/transport code only after correcting its query, lifecycle, consent, and result defects.
- Do not expose these as autonomous remote scraping/capture commands.

### Phase 4 — Accessibility, notification access, and hotspot alternatives

- Replace Accessibility capture with real disclosed actions.
- Add opt-in notification forwarding only if approved.
- Add honest hotspot status/settings workflow without hidden toggle claims.

### Phase 5 — Release candidate and verification

- Reconcile manifest/permissions and remove only code/resources proven unused after safe replacements work.
- Produce a complete changelog and intended Smali/resource diffs.
- Rebuild from a clean decoded working copy; verify ZIP, alignment where tooling is available, all five DEX files, manifest, resources, assets, and secrets.
- Sign with v1 plus appropriate newer schemes and run real signature verification rather than checking only for signature-file presence.
- Install/test on the exact Samsung Android 6 device and over mobile data before marking runtime criteria as passed.

## 7. Release verification matrix

| Area | Required evidence before claiming success |
|---|---|
| Original preservation | Original hash unchanged; patches applied only to working copies. |
| Build | Clean Apktool build log with no suppressed errors; output is a new file. |
| ZIP/alignment | Archive integrity passes; alignment checked with real tooling when available. |
| DEX | All expected DEX files parse; instruction differences are limited to approved methods/classes. |
| Manifest/resources | Package/SDK preserved; only approved components/permissions/resources differ; YouTube layout retained. |
| Signatures | Genuine private-key signing; verified v1 signature for API 23 and appropriate v2/v3 blocks; certificate fingerprint recorded. |
| Secrets | No bot token, signing key/password, private export, or sensitive log committed or embedded unintentionally. |
| Static security | No automatic Accessibility text/key capture, clipboard listener, hidden camera/mic loop, or unapproved bulk export call path remains. |
| Samsung runtime | Installation, launch, YouTube, permissions, background behavior, and each retained feature tested separately with logs/results. |
| Telegram | Real authorized/unauthorized command tests and real file/message outcomes over mobile data; no simulated delivery claims. |
| Installation | Same-signature update or explicitly approved different-signature reinstall path; no automatic uninstall or data destruction. |

## 8. Open inputs required before final packaging/testing

1. Exact Samsung model/build and current failure symptoms; ADB availability or assisted physical testing.
2. Whether the original signing keystore is available. It is not in the repository. If it is unavailable, a new signature will prevent an in-place update and may require an explicitly approved reinstall with data/permission loss.
3. Direct Telegram versus backend relay and the authorized numeric Telegram user ID. Replacement credentials must be supplied only through an untracked secure setup path, never committed or pasted into logs.
4. Approval of the necessary workflow changes: sensitive capture/export becomes local and explicit; Accessibility key/screen capture and deceptive stealth/fake-error behavior are not retained.

Until these artifacts and tests exist, the correct status is **repair plan complete; implementation and Samsung runtime verification pending**, not “repaired” or “tested.”
