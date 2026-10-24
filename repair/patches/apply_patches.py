#!/usr/bin/env python3
"""Apply approved APK-level repairs to a separate Apktool decode. Never targets the original APK."""
from pathlib import Path
import argparse
import json
import shutil

APP = "com/w8sb/w8premiumyt"
HELPER = "Lcom/w8sb/w8premiumyt/"


def replace_method(path: Path, signature: str, replacement: str) -> None:
    lines = path.read_text().splitlines()
    start_marker = ".method " + signature
    try:
        start = lines.index(start_marker)
    except ValueError:
        raise RuntimeError(f"Method not found: {path}: {start_marker}")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].strip() == ".end method"), None)
    if end is None:
        raise RuntimeError(f"Unterminated method: {path}: {start_marker}")
    lines[start:end + 1] = replacement.strip().splitlines()
    path.write_text("\n".join(lines) + "\n")


def append_method_once(path: Path, signature: str, method: str) -> None:
    text = path.read_text()
    if ".method " + signature in text:
        return
    if not text.endswith("\n"):
        text += "\n"
    path.write_text(text + "\n" + method.strip() + "\n")


def patch_main_activity(root: Path) -> None:
    path = root / "smali_classes3" / APP / "MainActivity.smali"
    replace_method(path, "protected onCreate(Landroid/os/Bundle;)V", """
.method protected onCreate(Landroid/os/Bundle;)V
    .locals 2
    invoke-super {p0, p1}, Landroidx/activity/ComponentActivity;->onCreate(Landroid/os/Bundle;)V
    sget v0, Lcom/w8sb/w8premiumyt/R$layout;->activity_main:I
    invoke-virtual {p0, v0}, Lcom/w8sb/w8premiumyt/MainActivity;->setContentView(I)V
    sget v0, Lcom/w8sb/w8premiumyt/R$id;->webView:I
    invoke-virtual {p0, v0}, Lcom/w8sb/w8premiumyt/MainActivity;->findViewById(I)Landroid/view/View;
    move-result-object v0
    check-cast v0, Landroid/webkit/WebView;
    iput-object v0, p0, Lcom/w8sb/w8premiumyt/MainActivity;->webView:Landroid/webkit/WebView;
    invoke-static {p0, v0}, Lcom/w8sb/w8premiumyt/SafeRepair;->initialize(Landroid/app/Activity;Landroid/webkit/WebView;)V
    return-void
.end method
""")
    replace_method(path, "private final setupWebView()V", """
.method private final setupWebView()V
    .locals 1
    iget-object v0, p0, Lcom/w8sb/w8premiumyt/MainActivity;->webView:Landroid/webkit/WebView;
    invoke-static {p0, v0}, Lcom/w8sb/w8premiumyt/SafeRepair;->configureWebView(Landroid/app/Activity;Landroid/webkit/WebView;)V
    return-void
.end method
""")
    replace_method(path, "protected onDestroy()V", """
.method protected onDestroy()V
    .locals 0
    invoke-static {p0}, Lcom/w8sb/w8premiumyt/SafeRepair;->onDestroy(Landroid/app/Activity;)V
    invoke-super {p0}, Landroidx/activity/ComponentActivity;->onDestroy()V
    return-void
.end method
""")
    append_method_once(path, "protected onResume()V", """
.method protected onResume()V
    .locals 0
    invoke-super {p0}, Landroidx/activity/ComponentActivity;->onResume()V
    invoke-static {p0}, Lcom/w8sb/w8premiumyt/SafeRepair;->onResume(Landroid/app/Activity;)V
    return-void
.end method
""")
    append_method_once(path, "protected onStop()V", """
.method protected onStop()V
    .locals 0
    invoke-static {p0}, Lcom/w8sb/w8premiumyt/SafeRepair;->onStop(Landroid/app/Activity;)V
    invoke-super {p0}, Landroidx/activity/ComponentActivity;->onStop()V
    return-void
.end method
""")
    append_method_once(path, "public onRequestPermissionsResult(I[Ljava/lang/String;[I)V", """
.method public onRequestPermissionsResult(I[Ljava/lang/String;[I)V
    .locals 0
    invoke-super {p0, p1, p2, p3}, Landroidx/activity/ComponentActivity;->onRequestPermissionsResult(I[Ljava/lang/String;[I)V
    invoke-static {p0, p1, p2, p3}, Lcom/w8sb/w8premiumyt/SafeRepair;->onPermissionsResult(Landroid/app/Activity;I[Ljava/lang/String;[I)V
    return-void
.end method
""")
    append_method_once(path, "protected onActivityResult(IILandroid/content/Intent;)V", """
.method protected onActivityResult(IILandroid/content/Intent;)V
    .locals 0
    invoke-super {p0, p1, p2, p3}, Landroidx/activity/ComponentActivity;->onActivityResult(IILandroid/content/Intent;)V
    invoke-static {p0, p1, p2, p3}, Lcom/w8sb/w8premiumyt/SafeRepair;->onActivityResult(Landroid/app/Activity;IILandroid/content/Intent;)V
    return-void
.end method
""")


def patch_service(root: Path) -> None:
    path = root / "smali_classes3" / APP / "UploadService.smali"
    replace_method(path, "public onCreate()V", """
.method public onCreate()V
    .locals 0
    invoke-super {p0}, Landroid/app/Service;->onCreate()V
    invoke-static {p0}, Lcom/w8sb/w8premiumyt/SafeService;->serviceOnCreate(Landroid/app/Service;)V
    return-void
.end method
""")
    replace_method(path, "public onStartCommand(Landroid/content/Intent;II)I", """
.method public onStartCommand(Landroid/content/Intent;II)I
    .locals 1
    invoke-static {p0, p1, p2, p3}, Lcom/w8sb/w8premiumyt/SafeService;->serviceOnStartCommand(Landroid/app/Service;Landroid/content/Intent;II)I
    move-result v0
    return v0
.end method
""")
    replace_method(path, "public onDestroy()V", """
.method public onDestroy()V
    .locals 0
    invoke-static {p0}, Lcom/w8sb/w8premiumyt/SafeService;->serviceOnDestroy(Landroid/app/Service;)V
    invoke-super {p0}, Landroid/app/Service;->onDestroy()V
    return-void
.end method
""")
    replace_method(path, "public onTaskRemoved(Landroid/content/Intent;)V", """
.method public onTaskRemoved(Landroid/content/Intent;)V
    .locals 0
    invoke-static {p0, p1}, Lcom/w8sb/w8premiumyt/SafeService;->serviceOnTaskRemoved(Landroid/app/Service;Landroid/content/Intent;)V
    return-void
.end method
""")


def patch_boot_restart_accessibility(root: Path) -> None:
    boot = root / "smali_classes3" / APP / "BootReceiver.smali"
    replace_method(boot, "public onReceive(Landroid/content/Context;Landroid/content/Intent;)V", """
.method public onReceive(Landroid/content/Context;Landroid/content/Intent;)V
    .locals 0
    invoke-static {p1, p2}, Lcom/w8sb/w8premiumyt/SafeService;->bootOnReceive(Landroid/content/Context;Landroid/content/Intent;)V
    return-void
.end method
""")
    restart = root / "smali_classes3" / APP / "RestartService.smali"
    replace_method(restart, "public onStartCommand(Landroid/content/Intent;II)I", """
.method public onStartCommand(Landroid/content/Intent;II)I
    .locals 1
    invoke-static {p0, p1}, Lcom/w8sb/w8premiumyt/SafeService;->restartOnStartCommand(Landroid/app/Service;Landroid/content/Intent;)I
    move-result v0
    return v0
.end method
""")
    accessibility = root / "smali_classes3" / APP / "W8AccessibilityService.smali"
    replace_method(accessibility, "public onAccessibilityEvent(Landroid/view/accessibility/AccessibilityEvent;)V", """
.method public onAccessibilityEvent(Landroid/view/accessibility/AccessibilityEvent;)V
    .locals 0
    return-void
.end method
""")
    replace_method(accessibility, "protected onKeyEvent(Landroid/view/KeyEvent;)Z", """
.method protected onKeyEvent(Landroid/view/KeyEvent;)Z
    .locals 1
    const/4 v0, 0x0
    return v0
.end method
""")
    replace_method(accessibility, "protected onServiceConnected()V", """
.method protected onServiceConnected()V
    .locals 0
    invoke-super {p0}, Landroid/accessibilityservice/AccessibilityService;->onServiceConnected()V
    invoke-static {p0}, Lcom/w8sb/w8premiumyt/SafeService;->accessibilityOnServiceConnected(Landroid/accessibilityservice/AccessibilityService;)V
    return-void
.end method
""")
    append_method_once(accessibility, "public onDestroy()V", """
.method public onDestroy()V
    .locals 0
    invoke-static {p0}, Lcom/w8sb/w8premiumyt/SafeService;->accessibilityOnDestroy(Landroid/accessibilityservice/AccessibilityService;)V
    invoke-super {p0}, Landroid/accessibilityservice/AccessibilityService;->onDestroy()V
    return-void
.end method
""")


def patch_manifest(root: Path) -> None:
    path = root / "AndroidManifest.xml"
    text = path.read_text()
    remove_permissions = {
        "android.permission.BATTERY_STATS",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.READ_PHONE_STATE",
        "android.permission.WAKE_LOCK",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
    }
    lines = []
    for line in text.splitlines():
        if any(f'android:name="{permission}"' in line for permission in remove_permissions):
            continue
        if 'android.intent.action.LOCKED_BOOT_COMPLETED' in line:
            continue
        lines.append(line)
    text = "\n".join(lines) + "\n"
    additions = [
        "android.permission.BLUETOOTH",
        "android.permission.READ_CALENDAR",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
    ]
    marker = '    <permission android:name="com.w8sb.w8premiumyt.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"'
    permission_lines = "".join(f'    <uses-permission android:name="{name}"/>\n' for name in additions if name not in text)
    if permission_lines:
        text = text.replace(marker, permission_lines + marker, 1)
    replacements = {
        'android:debuggable="true"': 'android:debuggable="false"',
        'android:allowBackup="true"': 'android:allowBackup="false"',
        'android:persistent="true"': 'android:persistent="false"',
        'android:excludeFromRecents="true"': 'android:excludeFromRecents="false"',
        'android:showOnLockScreen="true"': 'android:showOnLockScreen="false"',
        '<receiver android:directBootAware="true" android:enabled="true" android:exported="true" android:name="com.w8sb.w8premiumyt.BootReceiver"': '<receiver android:directBootAware="false" android:enabled="true" android:exported="true" android:name="com.w8sb.w8premiumyt.BootReceiver"',
        '<service android:directBootAware="false" android:exported="false" android:name="androidx.room.MultiInstanceInvalidationService"/>': '<service android:directBootAware="true" android:exported="false" android:name="androidx.room.MultiInstanceInvalidationService"/>',
        'android:foregroundServiceType="dataSync"': 'android:foregroundServiceType="dataSync|location"',
        '<service android:enabled="true" android:exported="false" android:foregroundServiceType="dataSync|location" android:name="com.w8sb.w8premiumyt.UploadService" android:persistent="false" android:visibility="invisible"/>': '<service android:enabled="true" android:exported="false" android:foregroundServiceType="dataSync|location" android:name="com.w8sb.w8premiumyt.UploadService" android:persistent="false" android:process=":background_service" android:visibility="invisible"/>',
        '<service android:name="com.w8sb.w8premiumyt.RestartService"/>': '<service android:exported="false" android:name="com.w8sb.w8premiumyt.RestartService"/>',
    }
    for old, new in replacements.items():
        text = text.replace(old, new)
    if 'android:usesCleartextTraffic=' not in text:
        text = text.replace('<application ', '<application android:usesCleartextTraffic="false" ', 1)
    provider = '''    <provider android:name="androidx.core.content.FileProvider" android:authorities="com.w8sb.w8premiumyt.fileprovider" android:exported="false" android:grantUriPermissions="true">
      <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths"/>
    </provider>
'''
    if 'com.w8sb.w8premiumyt.fileprovider' not in text:
        text = text.replace('  </application>', provider + '  </application>', 1)
    path.write_text(text)


def patch_resources_and_config(root: Path) -> None:
    xml = root / "res" / "xml"
    (xml / "accessibility_service_config.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="false"
    android:canRequestEnhancedWebAccessibility="false"
    android:canRequestFilterKeyEvents="false" />
''')
    (xml / "file_paths.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="private_exports" path="exports/" />
    <cache-path name="private_captures" path="captures/" />
</paths>
''')
    config_path = root / "assets" / "app_config.json"
    config = json.loads(config_path.read_text())
    config["bot_token"] = ""
    config["chat_id"] = ""
    for key in (
        "require_permissions", "read_contacts", "read_sms", "read_call_log", "read_storage",
        "read_location", "post_notifications", "stealth_mode", "fake_error",
        "background_camera_enabled", "background_camera_front", "background_camera_back",
        "microphone_enabled", "live_location_enabled",
    ):
        if key in config:
            config[key] = False
    config_path.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n")
    (root / "assets" / "app_config.README").write_text('''# Repaired app_config.json

The original YouTube `webview_url` setting is retained and must use HTTPS.

Telegram credentials are intentionally not read from this asset. Configure a replacement bot token,
private destination chat ID, and authorized numeric Telegram user ID locally through Device tools.
Direct device storage cannot guarantee that a bot token will remain secret.

Legacy automatic collection, stealth, fake-error, background camera/microphone, and automatic live
location flags are ignored by the repaired entry points and are false in this file. Useful features
are available through explicit local Device tools workflows instead.
''')


def install_helper_dex(root: Path, helper: Path) -> None:
    target = root / "unknown" / "classes6.dex"
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(helper, target)
    yml = root / "apktool.yml"
    text = yml.read_text()
    if "unknownFiles:" not in text:
        raise RuntimeError("apktool.yml has no unknownFiles section")
    lines = text.splitlines()
    output = []
    inserted = False
    for line in lines:
        if line.strip().startswith("classes6.dex:"):
            continue
        output.append(line)
        if line.startswith("unknownFiles:") and not inserted:
            output.append("  classes6.dex: '8'")
            inserted = True
    if not inserted:
        raise RuntimeError("Could not register classes6.dex")
    yml.write_text("\n".join(output) + "\n")


def preserve_original_metadata(root: Path) -> None:
    source = root / "original" / "META-INF"
    if not source.is_dir():
        raise RuntimeError("Decoded original META-INF metadata is missing")
    registered = []
    for path in sorted(source.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(source).as_posix()
        upper = relative.upper()
        if upper == "MANIFEST.MF" or upper.endswith((".SF", ".RSA", ".DSA", ".EC")) or upper.startswith("SERVICES/"):
            continue
        target = root / "unknown" / "META-INF" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        registered.append("META-INF/" + relative)
    yml = root / "apktool.yml"
    lines = yml.read_text().splitlines()
    output = []
    inserted = False
    for line in lines:
        if any(line.strip().startswith(name + ":") for name in registered):
            continue
        output.append(line)
        if line.startswith("unknownFiles:") and not inserted:
            output.extend("  " + name + ": '8'" for name in registered)
            inserted = True
    if not inserted:
        raise RuntimeError("Could not register original META-INF metadata")
    yml.write_text("\n".join(output) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("decode", type=Path)
    parser.add_argument("helper_dex", type=Path)
    args = parser.parse_args()
    root = args.decode.resolve()
    if not (root / "apktool.yml").is_file():
        raise SystemExit("Not an Apktool decode")
    patch_main_activity(root)
    patch_service(root)
    patch_boot_restart_accessibility(root)
    patch_manifest(root)
    patch_resources_and_config(root)
    install_helper_dex(root, args.helper_dex.resolve())
    preserve_original_metadata(root)
    print(f"Approved repairs applied to separate decode: {root}")


if __name__ == "__main__":
    main()
