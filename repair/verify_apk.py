#!/usr/bin/env python3
"""Static verification for the repaired APK. Does not claim installation or runtime testing."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from zipfile import ZipFile

from loguru import logger
logger.remove()
from androguard.core.apk import APK
from androguard.core.dex import DEX
from PIL import Image

ORIGINAL_SHA256 = "20d0701a59be76762a24d269fd4df42ff05638a63df642aeaee18b3793108111"
APP = "com/w8sb/w8premiumyt"
EXPECTED_CHANGED_METHODS = {
    (f"L{APP}/MainActivity;", "onCreate", "(Landroid/os/Bundle;)V"),
    (f"L{APP}/MainActivity;", "setupWebView", "()V"),
    (f"L{APP}/MainActivity;", "onDestroy", "()V"),
    (f"L{APP}/UploadService;", "onCreate", "()V"),
    (f"L{APP}/UploadService;", "onStartCommand", "(Landroid/content/Intent;II)I"),
    (f"L{APP}/UploadService;", "onDestroy", "()V"),
    (f"L{APP}/UploadService;", "onTaskRemoved", "(Landroid/content/Intent;)V"),
    (f"L{APP}/BootReceiver;", "onReceive", "(Landroid/content/Context;Landroid/content/Intent;)V"),
    (f"L{APP}/RestartService;", "onStartCommand", "(Landroid/content/Intent;II)I"),
    (f"L{APP}/W8AccessibilityService;", "onAccessibilityEvent", "(Landroid/view/accessibility/AccessibilityEvent;)V"),
    (f"L{APP}/W8AccessibilityService;", "onKeyEvent", "(Landroid/view/KeyEvent;)Z"),
    (f"L{APP}/W8AccessibilityService;", "onServiceConnected", "()V"),
}
EXPECTED_NEW_METHODS = {
    (f"L{APP}/MainActivity;", "onResume", "()V"),
    (f"L{APP}/MainActivity;", "onStop", "()V"),
    (f"L{APP}/MainActivity;", "onRequestPermissionsResult", "(I[Ljava/lang/String;[I)V"),
    (f"L{APP}/MainActivity;", "onActivityResult", "(IILandroid/content/Intent;)V"),
    (f"L{APP}/W8AccessibilityService;", "onDestroy", "()V"),
}
REMOVED_PERMISSIONS = {
    "android.permission.BATTERY_STATS",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.READ_PHONE_STATE",
    "android.permission.WAKE_LOCK",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
}
ADDED_PERMISSIONS = {
    "android.permission.BLUETOOTH",
    "android.permission.READ_CALENDAR",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.FOREGROUND_SERVICE_LOCATION",
}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def method_key(cls, method):
    return (cls.get_name(), method.get_name(), method.get_descriptor().replace(" ", ""))


def body_hash(method) -> str:
    if method.get_code() is None:
        return "no-code"
    h = hashlib.sha256()
    for instruction in method.get_instructions():
        h.update(instruction.get_name().encode("utf-8"))
        h.update(b"\0")
        h.update(instruction.get_output().encode("utf-8"))
        h.update(b"\n")
    return h.hexdigest()


def load_dexes(path: Path):
    classes = {}
    methods = {}
    bodies = {}
    fields = set()
    supers = {}
    duplicates = []
    dex_info = []
    with ZipFile(path) as z:
        names = sorted((n for n in z.namelist() if re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex", n)), key=lambda n: (len(n), n))
        for name in names:
            raw = z.read(name)
            if not raw.startswith(b"dex\n035\0"):
                raise AssertionError(f"Unexpected DEX header in {name}: {raw[:8]!r}")
            dex = DEX(raw)
            class_list = dex.get_classes()
            dex_info.append({"name": name, "bytes": len(raw), "classes": len(class_list)})
            for cls in class_list:
                cname = cls.get_name()
                if cname in classes:
                    duplicates.append(cname)
                classes[cname] = cls
                supers[cname] = cls.get_superclassname()
                for field in cls.get_fields():
                    fields.add((cname, field.get_name(), field.get_descriptor()))
                for method in cls.get_methods():
                    key = method_key(cls, method)
                    methods[key] = method
                    bodies[key] = body_hash(method)
    if duplicates:
        raise AssertionError(f"Duplicate DEX classes: {sorted(set(duplicates))[:20]}")
    return {"classes": classes, "methods": methods, "bodies": bodies, "fields": fields, "supers": supers, "dex": dex_info}


def verify_links(original, repaired):
    defined = set(repaired["methods"])
    checked = 0
    unresolved = []
    interesting = set(EXPECTED_CHANGED_METHODS) | EXPECTED_NEW_METHODS
    interesting |= {key for key in repaired["methods"] if key[0].startswith(f"L{APP}/") and key not in original["methods"]}
    invoke_re = re.compile(r"(L[^;]+;)->(.+)$")

    def resolvable(target):
        if target in defined:
            return True
        owner, name, descriptor = target
        seen = set()
        while owner in repaired["classes"] and owner not in seen:
            seen.add(owner)
            owner = repaired["supers"].get(owner)
            if owner is None:
                return False
            if (owner, name, descriptor) in defined:
                return True
            # Android framework/java methods are external to the APK. Compilation was
            # against Android 34 and API-23-sensitive calls are reviewed separately.
            if owner.startswith(("Landroid/", "Ljava/", "Ljavax/", "Lorg/json/")):
                return True
        return False

    for key in sorted(interesting):
        method = repaired["methods"].get(key)
        if method is None or method.get_code() is None:
            continue
        for instruction in method.get_instructions():
            if not instruction.get_name().startswith("invoke-"):
                continue
            match = invoke_re.search(instruction.get_output())
            if not match:
                unresolved.append({"from": key, "unparsed_invoke": instruction.get_output()})
                continue
            owner, tail = match.groups()
            if not owner.startswith((f"L{APP}/", "Lkotlin/", "Lkotlinx/", "Landroidx/")):
                continue
            name, descriptor = tail.split("(", 1)
            target = (owner, name, "(" + descriptor.replace(" ", ""))
            checked += 1
            if not resolvable(target):
                unresolved.append({"from": key, "target": target})
    if unresolved:
        raise AssertionError(f"Unresolved application/library references: {unresolved[:20]}")
    return checked


def file_map(root: Path):
    return {str(p.relative_to(root)): p for p in root.rglob("*") if p.is_file()}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("original", type=Path)
    parser.add_argument("repaired", type=Path)
    parser.add_argument("--baseline-decode", type=Path, required=True)
    parser.add_argument("--repaired-decode", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    original_hash = sha256(args.original)
    assert original_hash == ORIGINAL_SHA256, "Original APK identity changed"
    repaired_hash = sha256(args.repaired)
    with ZipFile(args.original) as zo, ZipFile(args.repaired) as zr:
        assert zr.testzip() is None, "ZIP CRC failure"
        original_config = json.loads(zo.read("assets/app_config.json"))
        repaired_config = json.loads(zr.read("assets/app_config.json"))
        original_token = original_config.get("bot_token", "")
        assert original_token and repaired_config.get("bot_token") == "" and repaired_config.get("chat_id") == ""
        for name in zr.namelist():
            if name.endswith("/"):
                continue
            assert original_token.encode() not in zr.read(name), f"Original bot token remains in {name}"
        signature_entries = [n for n in zr.namelist() if n.upper().startswith("META-INF/") and n.upper().endswith((".RSA", ".DSA", ".EC", ".SF"))]
        original_metadata = []
        for name in zo.namelist():
            upper = name.upper()
            if not upper.startswith("META-INF/") or upper == "META-INF/MANIFEST.MF" or upper.endswith((".SF", ".RSA", ".DSA", ".EC")):
                continue
            assert name in zr.namelist(), f"Original metadata entry missing: {name}"
            assert zo.read(name) == zr.read(name), f"Original metadata changed: {name}"
            original_metadata.append(name)
        dex_names = [n for n in zr.namelist() if re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex", n)]
        assert len(dex_names) == 6, dex_names

    original = load_dexes(args.original)
    repaired = load_dexes(args.repaired)
    removed_classes = set(original["classes"]) - set(repaired["classes"])
    removed_methods = set(original["methods"]) - set(repaired["methods"])
    removed_fields = set(original["fields"]) - set(repaired["fields"])
    assert not removed_classes, f"Removed classes: {sorted(removed_classes)[:10]}"
    assert not removed_methods, f"Removed methods: {sorted(removed_methods)[:10]}"
    assert not removed_fields, f"Removed fields: {sorted(removed_fields)[:10]}"
    changed_methods = {key for key in original["bodies"] if original["bodies"][key] != repaired["bodies"].get(key)}
    assert changed_methods == EXPECTED_CHANGED_METHODS, f"Unexpected method-body changes: {sorted(changed_methods ^ EXPECTED_CHANGED_METHODS)[:30]}"
    new_methods = set(repaired["methods"]) - set(original["methods"])
    unexpected_new_existing = {key for key in new_methods if key[0] in original["classes"]} - EXPECTED_NEW_METHODS
    assert not unexpected_new_existing, f"Unexpected methods on original classes: {sorted(unexpected_new_existing)[:20]}"
    new_classes = set(repaired["classes"]) - set(original["classes"])
    assert all(name.startswith(f"L{APP}/") for name in new_classes), sorted(new_classes)[:20]
    links_checked = verify_links(original, repaired)
    file_provider_target = (
        "Landroidx/core/content/FileProvider;",
        "getUriForFile",
        "(Landroid/content/Context;Ljava/lang/String;Ljava/io/File;)Landroid/net/Uri;",
    )
    assert file_provider_target in repaired["methods"], "Reflective FileProvider target is missing"

    apk = APK(str(args.repaired))
    original_apk = APK(str(args.original))
    assert apk.get_package() == original_apk.get_package() == "com.w8sb.w8premiumyt"
    assert apk.get_min_sdk_version() == original_apk.get_min_sdk_version() == "21"
    assert apk.get_target_sdk_version() == original_apk.get_target_sdk_version() == "34"
    assert apk.get_androidversion_code() == original_apk.get_androidversion_code()
    permissions = set(apk.get_permissions())
    assert not (permissions & REMOVED_PERMISSIONS), permissions & REMOVED_PERMISSIONS
    assert ADDED_PERMISSIONS <= permissions, ADDED_PERMISSIONS - permissions
    assert "androidx.core.content.FileProvider" in apk.get_providers()
    rebuilt_manifest_text = (args.repaired_decode / "AndroidManifest.xml").read_text()
    assert 'android:authorities="com.w8sb.w8premiumyt.fileprovider"' in rebuilt_manifest_text
    assert not apk.is_signed(), "This verification target is expected to remain unsigned"

    baseline = file_map(args.baseline_decode)
    rebuilt = file_map(args.repaired_decode)
    allowed_resource_changes = {"res/xml/file_paths.xml", "res/xml/accessibility_service_config.xml", "res/values/public.xml"}
    for section in ("res", "assets"):
        a = {k: v for k, v in baseline.items() if k.startswith(section + "/")}
        b = {k: v for k, v in rebuilt.items() if k.startswith(section + "/")}
        added = set(b) - set(a)
        removed = set(a) - set(b)
        changed = {k for k in set(a) & set(b) if a[k].read_bytes() != b[k].read_bytes()}
        if section == "res":
            assert added == {"res/xml/file_paths.xml"}, added
            assert not removed, removed
            assert changed == {"res/xml/accessibility_service_config.xml", "res/values/public.xml"}, changed
        else:
            assert not added and not removed
            assert changed == {"assets/app_config.json", "assets/app_config.README"}, changed
    for name, path in file_map(args.repaired_decode).items():
        if name.startswith("res/layout"):
            original_path = args.baseline_decode / name
            assert original_path.read_bytes() == path.read_bytes(), f"Layout changed: {name}"

    # Launcher pixels must survive even if Apktool recompresses PNG containers.
    pixel_checked = 0
    original_decode = args.baseline_decode.parent / "verify-original"
    if original_decode.is_dir():
        for path in original_decode.glob("res/drawable-*/ic_launcher_foreground.png"):
            other = args.repaired_decode / path.relative_to(original_decode)
            with Image.open(path) as a, Image.open(other) as b:
                assert a.size == b.size and a.convert("RGBA").tobytes() == b.convert("RGBA").tobytes(), f"Launcher pixels changed: {path.name}"
                pixel_checked += 1

    report = {
        "verification_type": "static only; not installed or run on an emulator/device",
        "original_sha256": original_hash,
        "repaired_unsigned_sha256": repaired_hash,
        "zip_crc": "passed",
        "signature": "unsigned; Android 6 installation is not possible until approved v1 signing",
        "package": apk.get_package(),
        "min_sdk": apk.get_min_sdk_version(),
        "target_sdk": apk.get_target_sdk_version(),
        "dex_files": repaired["dex"],
        "original_classes": len(original["classes"]),
        "repaired_classes": len(repaired["classes"]),
        "new_helper_classes": len(new_classes),
        "original_methods": len(original["methods"]),
        "repaired_methods": len(repaired["methods"]),
        "changed_original_method_bodies": len(changed_methods),
        "approved_new_lifecycle_methods": len(EXPECTED_NEW_METHODS),
        "unchanged_original_method_bodies": len(original["bodies"]) - len(changed_methods),
        "resolved_application_kotlin_androidx_references_checked": links_checked,
        "reflective_fileprovider_target_verified": True,
        "original_bot_token_absent_from_every_rebuilt_zip_entry": True,
        "layouts_byte_identical_to_rebuilt_baseline": True,
        "launcher_pngs_pixel_identical": pixel_checked,
        "original_process_retained": ":background_service" in (args.repaired_decode / "AndroidManifest.xml").read_text(),
        "removed_permissions": sorted(REMOVED_PERMISSIONS),
        "added_permissions": sorted(ADDED_PERMISSIONS),
        "signature_entries": signature_entries,
        "original_non_signature_meta_inf_entries_preserved": len(original_metadata),
    }
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"VERIFICATION FAILED: {exc}", file=sys.stderr)
        raise
