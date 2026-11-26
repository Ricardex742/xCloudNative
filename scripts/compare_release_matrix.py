#!/usr/bin/env python3
"""Freeze paired physical test conditions and assess the release matrix in a private directory."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import sys

from release_validation import ABIS, MATRIX, is_hash, read_json, require, sha256, valid_count, write_json

DEVICE_FIELDS = ("model", "firmware", "abi", "api", "ramMiB")
STREAM_FIELDS = ("codecProfile", "width", "height", "displayHz")


def conditions_hash(target):
    canonical = json.dumps({"device": target["device"], "conditions": target["conditions"]},
                           sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def all_metrics(fixture):
    return fixture["mandatoryMetrics"] | fixture["optionalMetrics"]


def draft_plan(fixture, matrix_sha, baseline_sha, build=None):
    require(is_hash(baseline_sha), "SHA-256 da base inválido.")
    candidate = {"revision": None, "apkSha256": None, "runId": None}
    if build:
        require(build["matrixSha256"] == matrix_sha, "A candidata usa outra matriz.")
        candidate = {"revision": build["revision"], "apkSha256": build["apk"]["sha256"], "runId": build["runId"]}
        require(is_hash(candidate["revision"], 40) and is_hash(candidate["apkSha256"]) and
                candidate["runId"].isdecimal() and baseline_sha != candidate["apkSha256"],
                "Identidade da candidata inválida ou igual à base.")
    return {
        "schema": 1, "matrixSha256": matrix_sha, "baselineSha256": baseline_sha, "candidate": candidate,
        "targets": {
            name: {"device": dict.fromkeys(DEVICE_FIELDS + ("pageSize",)),
                   "conditions": dict.fromkeys(fixture["conditions"]), "p95Margin": dict.fromkeys(all_metrics(fixture))}
            for name in fixture["targets"]
        },
    }


def number(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def check_plan(plan, fixture, matrix_sha):
    require(plan["schema"] == 1 and plan["matrixSha256"] == matrix_sha and is_hash(plan["baselineSha256"]),
            "Plano incompatível com a matriz ou a base.")
    candidate = plan["candidate"]
    require(is_hash(candidate["revision"], 40) and is_hash(candidate["apkSha256"]) and
            isinstance(candidate["runId"], str) and candidate["runId"].isdecimal() and
            candidate["apkSha256"] != plan["baselineSha256"], "A candidata assinada continua pendente.")
    require(set(plan["targets"]) == set(fixture["targets"]), "O plano precisa cobrir todas as categorias físicas.")
    for name, definition in fixture["targets"].items():
        target = plan["targets"][name]
        device = target["device"]
        conditions = target["conditions"]
        require(set(conditions) == set(fixture["conditions"]) and
                all(isinstance(value, str) and value.strip() for value in conditions.values()),
                f"Condições incompletas em {name}.")
        require(all(isinstance(device[key], str) and device[key].strip() for key in DEVICE_FIELDS[:3]) and
                device["abi"] in ABIS and valid_count(device["api"], 24) and valid_count(device["ramMiB"], 1),
                f"Dispositivo incompleto em {name}.")
        require(definition.get("apiMin", 24) <= device["api"] <= definition.get("apiMax", device["api"]) and
                device["ramMiB"] <= definition.get("ramMaxMiB", device["ramMiB"]) and
                device["abi"] == definition.get("abi", device["abi"]),
                f"API, RAM ou ABI fora do perfil de {name}.")
        if "pageSize" in definition:
            require(device["pageSize"] == definition["pageSize"], f"Tamanho de página pendente em {name}.")
        margins = target["p95Margin"]
        require(set(margins) == set(all_metrics(fixture)) and
                all(number(margins[key]) for key in fixture["mandatoryMetrics"]) and
                all(value is None or number(value) for value in margins.values()),
                f"Margens de P95 incompletas em {name}.")


def start_results(plan, plan_sha, fixture):
    def observation(name, apk):
        definition = fixture["targets"][name]
        return {
            "apkSha256": apk, "conditionsSha256": conditions_hash(plan["targets"][name]),
            "decoder": None, "observedStream": dict.fromkeys(STREAM_FIELDS),
            "pairedRunSeconds": [None] * fixture["pairedRuns"],
            "longSessionSeconds": None, "lifecycleCycles": None,
            "checks": dict.fromkeys(fixture["checks"] + definition["checks"]),
            "incidents": dict.fromkeys(fixture["incidents"]), "samples": {key: [] for key in all_metrics(fixture)},
        }

    return {
        "schema": 1, "planSha256": plan_sha,
        "targets": {
            name: {"baseline": observation(name, plan["baselineSha256"]),
                   "candidate": observation(name, plan["candidate"]["apkSha256"])}
            for name in fixture["targets"]
        },
    }


def percentiles(samples):
    ordered = sorted(samples)

    def percentile(p):
        rank = (len(ordered) - 1) * p
        i = int(rank)
        return ordered[i] + (ordered[min(i + 1, len(ordered) - 1)] - ordered[i]) * (rank - i)

    return {"n": len(samples), "min": ordered[0], "max": ordered[-1],
            **{f"p{p}": percentile(p / 100) for p in (50, 95, 99)}}


def observed_stream(row):
    value = row.get("observedStream")
    if not isinstance(value, dict):
        return None
    codec = value.get("codecProfile")
    width, height, hz = (value.get(key) for key in STREAM_FIELDS[1:])
    if (not isinstance(codec, str) or not codec.strip() or not valid_count(width, 1) or
            not valid_count(height, 1) or not number(hz) or hz == 0):
        return None
    return codec.strip().lower(), width, height, round(hz, 2)


def evaluate(plan, plan_sha, observed, fixture):
    require(observed["schema"] == 1 and observed["planSha256"] == plan_sha,
            "Os resultados pertencem a outro plano ou as condições foram alteradas.")
    require(set(observed["targets"]) <= set(fixture["targets"]), "Resultados com categoria desconhecida.")
    output = {}
    for name, definition in fixture["targets"].items():
        paired = observed["targets"].get(name, {})
        failed, pending, corrections = [], [], []
        comparison = {}
        cond_sha = conditions_hash(plan["targets"][name])
        for role, expected in (("baseline", plan["baselineSha256"]), ("candidate", plan["candidate"]["apkSha256"])):
            row = paired.get(role)
            if row is None:
                pending.append(role)
                continue
            require(row["apkSha256"] == expected and row["conditionsSha256"] == cond_sha,
                    f"APK ou condições divergentes em {name}/{role}.")
            duration = row["pairedRunSeconds"]
            if len(duration) != fixture["pairedRuns"] or not all(valid_count(n, fixture["pairedRunSeconds"]) for n in duration):
                pending.append(f"{role}/pairedRuns")
            if not valid_count(row["longSessionSeconds"], fixture["longSessionSeconds"]):
                pending.append(f"{role}/longSession")
            if not valid_count(row["lifecycleCycles"], definition["cycles"]):
                pending.append(f"{role}/lifecycle")
            if not isinstance(row["decoder"], str) or not row["decoder"].strip():
                pending.append(f"{role}/decoder")

        if "baseline" in paired and "candidate" in paired:
            base, candidate = paired["baseline"], paired["candidate"]
            old_stream, new_stream = observed_stream(base), observed_stream(candidate)
            comparable_stream = old_stream is not None and old_stream == new_stream
            if not comparable_stream:
                pending.append("observedStream/notComparable")
            for key in fixture["checks"] + definition["checks"]:
                old, new = base["checks"].get(key), candidate["checks"].get(key)
                if new == "fail":
                    failed.append(f"check/{key}/" + ("alsoInBaseline" if old == "fail" else "candidateFailed"))
                elif (old not in ("pass", "fail")) or new != "pass":
                    pending.append(f"check/{key}")
                elif old == "fail":
                    corrections.append(key)
            for key in fixture["incidents"]:
                old, new = base["incidents"].get(key), candidate["incidents"].get(key)
                if valid_count(new, 1):
                    failed.append(f"incident/{key}/" + ("alsoInBaseline" if valid_count(old, new) else "candidateFailed"))
                elif not valid_count(old) or not valid_count(new):
                    pending.append(f"incident/{key}")
            for key, minimum in all_metrics(fixture).items():
                margin = plan["targets"][name]["p95Margin"][key]
                old, new = base["samples"].get(key, []), candidate["samples"].get(key, [])
                if margin is None:
                    require(not old and not new, f"Métrica {name}/{key} coletada sem margem congelada.")
                    continue
                require(isinstance(old, list) and isinstance(new, list) and all(number(n) for n in old + new),
                        f"Amostras inválidas em {name}/{key}.")
                if not comparable_stream or len(old) != len(new) or len(old) < minimum:
                    pending.append(f"samples/{key}")
                    continue
                old_stats, new_stats = percentiles(old), percentiles(new)
                limit = old_stats["p95"] + margin
                passed = new_stats["p95"] <= limit
                comparison[key] = {"baseline": old_stats, "candidate": new_stats, "p95Margin": margin, "pass": passed}
                if not passed:
                    failed.append(f"p95/{key}")
        state = "failed" if failed else "pending" if pending else "passed"
        output[name] = {"status": state, "pending": pending, "failures": failed, "correctedChecks": corrections,
                        "metrics": comparison, "unmeasured": [key for key in all_metrics(fixture) if key not in comparison]}

    passed = all(result["status"] == "passed" for result in output.values())
    return {"schema": 1, "planSha256": plan_sha, "baselineSha256": plan["baselineSha256"], "candidate": plan["candidate"],
            "status": "passed" if passed else "failed" if any(r["status"] == "failed" for r in output.values()) else "pending",
            "targets": output}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    draft = commands.add_parser("draft")
    draft.add_argument("--baseline-sha256", required=True)
    draft.add_argument("--receipt", type=Path)
    prepare = commands.add_parser("prepare")
    compare = commands.add_parser("compare")
    compare.add_argument("--results", type=Path, required=True)
    for command in (prepare, compare):
        command.add_argument("--plan", type=Path, required=True)
    for command in (draft, prepare, compare):
        command.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    fixture, matrix_sha = read_json(MATRIX), sha256(MATRIX)
    try:
        if args.command == "draft":
            build = read_json(args.receipt)["build"] if args.receipt else None
            write_json(args.output, draft_plan(fixture, matrix_sha, args.baseline_sha256, build))
            return 0
        plan = read_json(args.plan)
        check_plan(plan, fixture, matrix_sha)
        fingerprint = sha256(args.plan)
        if args.command == "prepare":
            write_json(args.output, start_results(plan, fingerprint, fixture))
            return 0
        report = evaluate(plan, fingerprint, read_json(args.results), fixture)
        write_json(args.output, report)
        if report["status"] == "passed":
            print(report["candidate"]["apkSha256"])
            return 0
        print(f"Matriz física • {report['status']}", file=sys.stderr)
        return 2
    except (OSError, ValueError, KeyError, TypeError, OverflowError, AttributeError) as error:
        print(f"Matriz física: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
