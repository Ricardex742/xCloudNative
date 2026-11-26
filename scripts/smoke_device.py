#!/usr/bin/env python3
"""Measure cold startup and resume of the signed APK on one Android device via ADB."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

PACKAGE = "com.xcloud.tclnative"
ACTIVITY = PACKAGE + "/.ui.MainActivity"


def value(pattern, output):
    match = re.search(pattern, output, re.MULTILINE)
    return int(match[1]) if match else None


def launch_metrics(output):
    if "Status: ok" not in output.splitlines():
        raise RuntimeError("O Activity Manager não confirmou a abertura do aplicativo.")
    return {label: value(rf"^{field}:\s*([0-9]+)$", output)
            for label, field in (("totalMs", "TotalTime"), ("waitMs", "WaitTime"))}


def total_pss(output):
    modern = value(r"\bTOTAL PSS:\s+([0-9]+)\b", output)
    return modern if modern is not None else value(r"^\s*TOTAL\s+([0-9]+)\s+[0-9]+", output)


def app_resumed(output):
    expanded = PACKAGE + "/" + PACKAGE + ".ui.MainActivity"
    for line in output.splitlines():
        if re.search(r"\b(?:topResumedActivity|mResumedActivity|ResumedActivity):", line):
            if ACTIVITY in line.split() or expanded in line.split():
                return True
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("serial")
    parser.add_argument("form_factor", choices=("phone", "tv"))
    parser.add_argument("--report", type=Path)
    parser.add_argument("--expected-abi", choices=("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
    parser.add_argument("--repeats", type=int, choices=range(1, 11), default=1, metavar="1–10")
    args = parser.parse_args()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        parser.error("Configure ANDROID_HOME ou ANDROID_SDK_ROOT.")

    adb = str(Path(sdk) / "platform-tools/adb")

    def command(*params, timeout=60, required=True):
        result = subprocess.run([adb, "-s", args.serial, *params], capture_output=True, text=True, timeout=timeout)
        if required and result.returncode:
            raise RuntimeError(f"ADB falhou ao executar {params[0]} ({result.returncode}).")
        return result.stdout

    def wait_for_visibility(expected):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            output = command("shell", "dumpsys", "activity", "activities", timeout=10)
            if app_resumed(output) == expected:
                return
            time.sleep(0.25)
        transition = "abertura" if expected else "saída para o launcher"
        raise RuntimeError(f"O Android não confirmou a {transition}.")

    def process_id():
        pid = command("shell", "pidof", PACKAGE, timeout=5, required=False).strip()
        if not pid.isdecimal():
            raise RuntimeError("O processo do aplicativo não permaneceu ativo.")
        return pid

    try:
        if command("get-state", timeout=10).strip() != "device":
            raise RuntimeError("O aparelho não está disponível no ADB.")
        verifier = Path(__file__).with_name("verify_apk_signing.py")
        verify = [sys.executable, str(verifier), str(args.apk), "--require-nondebuggable"]
        if args.expected_abi:
            verify.extend(("--expected-abi", args.expected_abi))
        subprocess.run(verify, check=True, timeout=60)
        metadata = json.loads(args.apk.with_suffix(args.apk.suffix + ".metadata.json").read_text(encoding="utf-8"))
        if "Success" not in command("install", "-r", str(args.apk), timeout=180).splitlines():
            raise RuntimeError("O Android não confirmou a instalação do APK.")
        suffix = "LEANBACK_LAUNCHER" if args.form_factor == "tv" else "LAUNCHER"
        intent = ("-a", "android.intent.action.MAIN", "-c", "android.intent.category." + suffix, "-p", PACKAGE)
        resolved = command("shell", "cmd", "package", "resolve-activity", "--brief", *intent)
        if ACTIVITY not in resolved.splitlines():
            raise RuntimeError("O launcher não resolveu a Activity esperada.")

        api = command("shell", "getprop", "ro.build.version.sdk").strip()
        abi = command("shell", "getprop", "ro.product.cpu.abi").strip()
        samples = []
        for _ in range(args.repeats):
            command("shell", "am", "force-stop", PACKAGE)
            cold = launch_metrics(command("shell", "am", "start", "-W", *intent))
            wait_for_visibility(True)
            pid = process_id()
            pss = total_pss(command("shell", "dumpsys", "meminfo", PACKAGE, required=False))
            command("shell", "input", "keyevent", "KEYCODE_HOME")
            wait_for_visibility(False)
            resumed = launch_metrics(command("shell", "am", "start", "-W", *intent))
            wait_for_visibility(True)
            if process_id() != pid:
                raise RuntimeError("O processo mudou entre a abertura e o retorno ao aplicativo.")
            samples.append({"coldStart": cold, "resume": resumed, "coldStartPssKiB": pss})
        report = {"schema": 1, "apkVersion": metadata["versionName"], "apkSha256": metadata["sha256"],
                  "launcher": args.form_factor, "api": int(api), "abi": abi,
                  "totalRamKiB": value(r"^MemTotal:\s+([0-9]+)\s+kB", command("shell", "cat", "/proc/meminfo", required=False)),
                  "samples": samples}
        text = json.dumps(report, indent=2) + "\n"
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(text, encoding="utf-8")
        print(text, end="")
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"Smoke sem confirmação: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
