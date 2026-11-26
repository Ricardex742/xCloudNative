#!/usr/bin/env python3
"""Fail on broad Android lint errors or warning counts above the reviewed baseline."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "app/lint-warning-baseline.json"


def read_report(report, root):
    xml = ElementTree.parse(report).getroot()
    version = xml.get("by", "")
    if xml.tag != "issues" or xml.get("format") != "6" or not re.fullmatch(r"lint [\w.-]+", version):
        raise ValueError("Formato ou versão do relatório lint inválido.")
    warnings = Counter()
    errors = Counter()
    for issue in xml.findall("issue"):
        rule = issue.attrib["id"]
        severity = issue.attrib["severity"]
        location = issue.find("location")
        path = location.get("file") if location is not None else None
        if path:
            path = Path(path)
            if path.is_absolute():
                try:
                    path = path.relative_to(root)
                except ValueError:
                    path = Path("<external>") / path.name
            location_name = path.as_posix()
        else:
            location_name = "<global>"
        if severity == "Warning":
            warnings[f"{rule} {location_name}"] += 1
        elif severity in {"Error", "Fatal"}:
            errors[f"{rule} {location_name}"] += 1
        elif severity not in {"Information", "Ignore"}:
            raise ValueError(f"Gravidade lint desconhecida: {severity}")
    return version, warnings, errors


def check(report, baseline, root=ROOT, *, write_baseline=False):
    version, warnings, errors = read_report(report, root)
    print(f"{version} • {sum(errors.values())} erros • {sum(warnings.values())} avisos")
    if errors:
        for location, count in sorted(errors.items()):
            print(f"Erro ({count}): {location}", file=sys.stderr)
        return False
    if write_baseline:
        data = {"schema": 1, "lint": version, "warnings": dict(warnings)}
        baseline.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return True

    data = json.loads(baseline.read_text(encoding="utf-8"))
    if data["schema"] != 1 or data["lint"] != version:
        raise ValueError("Baseline incompatível com a versão do lint.")
    allowed = data["warnings"]
    if not isinstance(allowed, dict) or any(type(count) is not int or count < 1 for count in allowed.values()):
        raise ValueError("Contagens inválidas no baseline do lint.")
    regressions = warnings - Counter(allowed)
    for location, extra in sorted(regressions.items()):
        print(f"Novo aviso (+{extra}): {location}", file=sys.stderr)
    return not regressions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--write-baseline", action="store_true")
    args = parser.parse_args()
    try:
        return 0 if check(args.report, args.baseline, write_baseline=args.write_baseline) else 1
    except (OSError, ValueError, KeyError, TypeError, ElementTree.ParseError) as error:
        print(f"Falha ao verificar lint: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
