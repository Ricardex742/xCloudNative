#!/usr/bin/env python3
"""Bind the release validation results to the signed APK, revision and build run."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from xml.etree import ElementTree

import check_lint
from prepare_signing import expected_certificate

ROOT = Path(__file__).resolve().parent.parent
MATRIX = ROOT / "scripts/release_matrix.json"
GRAPHICS = ("image_filter", "fsr1", "image_treatment", "reconstruction", "image_lab", "motion_fence")
SMOKE_APIS = (29, 34)
ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
PACKAGE = "com.xcloud.tclnative"
JVM_FIELDS = ("tests", "failures", "errors", "skipped")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def is_hash(value, size=64):
    return isinstance(value, str) and re.fullmatch(rf"[0-9a-f]{{{size}}}", value) is not None


def valid_count(number, minimum=0):
    return type(number) is int and number >= minimum


def jvm_totals(directory):
    suites = [ElementTree.parse(path).getroot() for path in directory.glob("TEST-*.xml")]
    require(all(suite.tag == "testsuite" for suite in suites), "Formato inválido nos resultados JVM.")
    columns = {key: [int(suite.attrib[key]) for suite in suites] for key in JVM_FIELDS}
    require(all(value >= 0 for values in columns.values() for value in values), "Contagem JVM inválida.")
    totals = {key: sum(values) for key, values in columns.items()}
    check_jvm(totals)
    return totals


def check_jvm(totals):
    require(valid_count(totals["tests"], 1) and
            all(type(totals[key]) is int and totals[key] == 0 for key in JVM_FIELDS[1:]),
            "A suíte JVM deve ter testes executados e zero falhas, erros ou ignorados.")


def checked_apk(apk):
    metadata = read_json(apk.with_suffix(apk.suffix + ".metadata.json"))
    checked = {
        "sha256": sha256(apk),
        "package": PACKAGE,
        "versionName": metadata["versionName"],
        "versionCode": metadata["versionCode"],
        "signingCertificateSHA256": expected_certificate(),
        "debuggable": False,
        "minSdk": 24,
        "targetSdk": 34,
        "abis": ABIS,
        "launcher": PACKAGE + ".ui.MainActivity",
        "leanbackLauncher": PACKAGE + ".ui.MainActivity",
    }
    require(all(metadata.get(key) == value for key, value in checked.items()) and
            metadata["debuggable"] is False and valid_count(metadata["versionCode"], 16) and
            isinstance(metadata["versionName"], str) and
            re.fullmatch(r"\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?", metadata["versionName"]),
            "Identidade incompatível com o APK universal de release.")
    sha_file = apk.with_suffix(apk.suffix + ".sha256")
    parts = sha_file.read_text(encoding="utf-8").split()
    require(len(parts) == 2 and parts[0] == checked["sha256"] and
            Path(parts[1]).name == apk.name, "O arquivo SHA-256 não confere com o APK.")
    return checked


def build_evidence(apk, revision, run_id, graphics, root=ROOT):
    require(is_hash(revision, 40) and run_id.isdecimal(), "Origem do build inválida.")

    def git(*args):
        return subprocess.run(["git", *args], cwd=root, check=True, text=True, capture_output=True).stdout.strip()

    require(git("rev-parse", "HEAD") == revision and not git("status", "--porcelain", "--untracked-files=normal"),
            "O checkout deve estar limpo e na revisão do build informado.")
    app_build = root / "app/build"
    report = app_build / "reports/lint-audit-debug.xml"
    baseline = root / "app/lint-warning-baseline.json"
    require(check_lint.check(report, baseline, root), "O lint está fora da linha de base.")
    version, warnings, errors = check_lint.read_report(report, root)
    logs = {}
    for suite in GRAPHICS:
        log = graphics / f"{suite}.log"
        require(log.stat().st_size > 0, f"Resultado gráfico vazio em {suite}.")
        logs[suite] = sha256(log)
    return {
        "schema": 1, "revision": revision, "runId": run_id, "apk": checked_apk(apk),
        "matrixSha256": sha256(root / "scripts/release_matrix.json"),
        "jvm": jvm_totals(app_build / "test-results/testDebugUnitTest"),
        "lint": {"version": version, "errors": sum(errors.values()), "warnings": sum(warnings.values()),
                 "baselineSha256": sha256(baseline)},
        "runtimeInventorySha256": sha256(root / "gradle/runtime-artifacts.sha256"),
        "graphics": logs,
    }


def check_build(data, revision, run_id, root=ROOT):
    require(data["schema"] == 1 and data["revision"] == revision and is_hash(revision, 40) and
            data["runId"] == run_id and run_id.isdecimal(), "A evidência pertence a outra revisão ou execução.")
    require(data["matrixSha256"] == sha256(root / "scripts/release_matrix.json") and
            data["runtimeInventorySha256"] == sha256(root / "gradle/runtime-artifacts.sha256") and
            data["lint"]["baselineSha256"] == sha256(root / "app/lint-warning-baseline.json"),
            "Matriz, dependências ou linha de base divergentes da revisão congelada.")
    check_jvm(data["jvm"])
    require(type(data["lint"]["errors"]) is int and data["lint"]["errors"] == 0 and
            valid_count(data["lint"]["warnings"]), "Evidência de lint inválida.")
    require(set(data["graphics"]) == set(GRAPHICS) and all(is_hash(digest) for digest in data["graphics"].values()),
            "A evidência gráfica está incompleta.")


def smoke_receipt(build, reports):
    results = {}
    for path in reports:
        report = read_json(path)
        api = report["api"]
        samples = report["samples"]
        require(report["schema"] == 1 and api in SMOKE_APIS and api not in results and
                report["abi"] == "x86_64" and report["launcher"] == "phone",
                "API, ABI ou launcher do smoke de emulador inválido ou duplicado.")
        require(report["apkSha256"] == build["apk"]["sha256"] and
                report["apkVersion"] == build["apk"]["versionName"],
                "O smoke usou outro APK.")
        require(isinstance(samples, list) and len(samples) == 3, "O smoke exige três rodadas.")
        for sample in samples:
            values = [sample["coldStartPssKiB"]]
            for kind in ("coldStart", "resume"):
                values.extend(sample[kind][name] for name in ("totalMs", "waitMs"))
            require(all(value is None or valid_count(value) for value in values), "Amostra de smoke inválida.")
        results[api] = {"api": api, "abi": report["abi"], "rounds": len(samples), "reportSha256": sha256(path)}
    require(set(results) == set(SMOKE_APIS), "Falta smoke de uma das APIs previstas.")
    return {"schema": 1, "build": build, "emulator": [results[api] for api in SMOKE_APIS]}


def check_publication(receipt, apk, revision, run_id, tag, validated_sha):
    require(receipt["schema"] == 1, "Versão inválida da evidência de release.")
    build = receipt["build"]
    check_build(build, revision, run_id)
    actual = checked_apk(apk)
    require(build["apk"] == actual and is_hash(validated_sha) and validated_sha == actual["sha256"],
            "O APK para publicação diverge do build ou da validação física informada.")
    require(tag == "v" + actual["versionName"].split("-")[0], "Tag de release diferente da versão do APK.")
    received_apis = []
    for report in receipt["emulator"]:
        require(report["rounds"] == 3 and report["abi"] == "x86_64" and is_hash(report["reportSha256"]),
                "Recibo do smoke inválido.")
        received_apis.append(report["api"])
    require(received_apis == list(SMOKE_APIS), "A evidência dos emuladores está incompleta.")
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    jvm = commands.add_parser("jvm")
    jvm.add_argument("directory", type=Path)
    collect = commands.add_parser("build")
    collect.add_argument("--apk", type=Path, required=True)
    collect.add_argument("--graphics", type=Path, required=True)
    collect.add_argument("--output", type=Path, required=True)
    smoke = commands.add_parser("emulator")
    smoke.add_argument("--build", type=Path, required=True)
    smoke.add_argument("--report", type=Path, action="append", required=True)
    smoke.add_argument("--output", type=Path, required=True)
    publish = commands.add_parser("publish")
    publish.add_argument("--receipt", type=Path, required=True)
    publish.add_argument("--apk", type=Path, required=True)
    publish.add_argument("--tag", required=True)
    publish.add_argument("--validated-sha256", required=True)
    for subparser in (collect, smoke, publish):
        subparser.add_argument("--revision", required=True)
        subparser.add_argument("--run-id", required=True)

    args = parser.parse_args()
    try:
        if args.command == "jvm":
            message = f"Testes JVM: {jvm_totals(args.directory)}"
            if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
                with open(summary, "a", encoding="utf-8") as output:
                    output.write(message + "\n")
            print(message)
        elif args.command == "build":
            write_json(args.output, build_evidence(args.apk, args.revision, args.run_id, args.graphics))
        elif args.command == "emulator":
            build = read_json(args.build)
            check_build(build, args.revision, args.run_id)
            write_json(args.output, smoke_receipt(build, args.report))
        else:
            checked = check_publication(read_json(args.receipt), args.apk, args.revision, args.run_id,
                                        args.tag, args.validated_sha256)
            print(f"Release validada • {checked['versionName']} • SHA-256 {checked['sha256']}")
    except (OSError, ValueError, TypeError, KeyError, AttributeError, ElementTree.ParseError, subprocess.SubprocessError) as error:
        print(f"Validação de release: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
