#!/usr/bin/env python3
"""Reject APKs signed with any key other than the pinned xCLOUD update certificate."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

from prepare_signing import SigningError, expected_certificate


def signer_certificate(output, expected):
    matches = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", output)
    if len(matches) != 1 or matches[0].lower() != expected:
        raise SigningError("Assinatura do APK diferente da identidade fixa. Publicação bloqueada.")
    return matches[0].lower()


def package_metadata(output, require_nondebuggable=False):
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output)
    if not match or match[1] != "com.xcloud.tclnative":
        raise SigningError("Identificador do aplicativo alterado; o APK não atualizaria a instalação existente.")
    if int(match[2]) < 16:
        raise SigningError("versionCode anterior à correção de assinatura. Publicação bloqueada.")
    debuggable = any(line.strip() == "application-debuggable" for line in output.splitlines())
    if require_nondebuggable and debuggable:
        raise SigningError("APK com depuração habilitada. Publicação bloqueada.")
    return {"package": match[1], "versionCode": int(match[2]), "versionName": match[3], "debuggable": debuggable}


def runtime_metadata(output, expected_abi=None):
    lines = set(output.splitlines())
    activity = "com.xcloud.tclnative.ui.MainActivity"
    fields = ("sdkVersion:'24'", "targetSdkVersion:'34'")
    for field in fields:
        if field not in lines:
            raise SigningError(f"Versão de Android ausente ou alterada no APK: {field}")
    for prefix in ("launchable-activity", "leanback-launchable-activity"):
        if not any(line.startswith(f"{prefix}: name='{activity}'") for line in lines):
            raise SigningError(f"Launcher ausente no APK: {prefix}")
    for feature in ("android.hardware.wifi", "android.hardware.touchscreen", "android.software.leanback"):
        if not any(line.strip() == f"uses-feature-not-required: name='{feature}'" for line in lines):
            raise SigningError(f"O APK deve manter este recurso opcional: {feature}")
    abis = set(re.findall(r"'([^']+)'", next((line for line in lines if line.startswith("native-code:")), "")))
    expected_abis = {expected_abi} if expected_abi else {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
    if not expected_abis <= abis:
        raise SigningError("ABIs ausentes no APK: " + ", ".join(sorted(expected_abis - abis)))
    if expected_abi and abis != expected_abis:
        raise SigningError("O APK experimental deve incluir somente a ABI selecionada.")
    return {"minSdk": 24, "targetSdk": 34, "abis": sorted(abis), "launcher": activity, "leanbackLauncher": activity}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--require-nondebuggable", action="store_true")
    parser.add_argument("--expected-abi", choices=("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
    args = parser.parse_args()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise SystemExit("Configure ANDROID_HOME para verificar o APK.")
    build_tools = Path(sdk) / "build-tools/34.0.0"
    signature = subprocess.run([str(build_tools / "apksigner"), "verify", "--print-certs", str(args.apk)],
                               capture_output=True, text=True)
    if signature.returncode:
        raise SystemExit("A assinatura do APK não passou na verificação criptográfica.")
    try:
        fingerprint = signer_certificate(signature.stdout, expected_certificate())
        badging = subprocess.run([str(build_tools / "aapt"), "dump", "badging", str(args.apk)],
                                 capture_output=True, text=True, check=True)
        metadata = package_metadata(badging.stdout, require_nondebuggable=args.require_nondebuggable)
        metadata.update(runtime_metadata(badging.stdout, args.expected_abi))
    except (SigningError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error)) from None
    digest = hashlib.sha256()
    with args.apk.open("rb") as apk:
        for block in iter(lambda: apk.read(1024 * 1024), b""):
            digest.update(block)
    metadata.update(signingCertificateSHA256=fingerprint, sha256=digest.hexdigest())
    args.apk.with_suffix(args.apk.suffix + ".metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata, ensure_ascii=False))


if __name__ == "__main__":
    main()
