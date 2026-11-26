import copy
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import compare_release_matrix as matrix
import release_validation as release
from prepare_signing import expected_certificate

REVISION = "a" * 40
RUN = "524"
FIXTURE = release.read_json(release.MATRIX)
FIXTURE_SHA = release.sha256(release.MATRIX)


class ReleaseGateTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name)
        self.apk = self.path / "app-release.apk"
        self.apk.write_bytes(b"fixture for metadata checks")
        digest = release.sha256(self.apk)
        self.metadata = {
            "sha256": digest, "package": release.PACKAGE,
            "versionName": "0.41.36-performance-trials", "versionCode": 149,
            "signingCertificateSHA256": expected_certificate(), "debuggable": False,
            "minSdk": 24, "targetSdk": 34, "abis": release.ABIS,
            "launcher": release.PACKAGE + ".ui.MainActivity",
            "leanbackLauncher": release.PACKAGE + ".ui.MainActivity",
        }
        release.write_json(self.apk.with_suffix(".apk.metadata.json"), self.metadata)
        self.apk.with_suffix(".apk.sha256").write_text(f"{digest}  {self.apk.name}\n", encoding="utf-8")
        self.build = {
            "schema": 1, "revision": REVISION, "runId": RUN, "apk": self.metadata,
            "matrixSha256": FIXTURE_SHA, "runtimeInventorySha256": release.sha256(ROOT / "gradle/runtime-artifacts.sha256"),
            "lint": {"errors": 0, "warnings": 1666, "baselineSha256": release.sha256(ROOT / "app/lint-warning-baseline.json")},
            "graphics": dict.fromkeys(release.GRAPHICS, "f" * 64),
            "jvm": {"tests": 975, "errors": 0, "failures": 0, "skipped": 0},
        }

    def emulator(self, api, apk_sha=None):
        path = self.path / f"smoke-{api}.json"
        sample = {"coldStart": {"totalMs": 255, "waitMs": 265}, "resume": {"totalMs": None, "waitMs": 45},
                  "coldStartPssKiB": 64_000}
        release.write_json(path, {"schema": 1, "api": api, "abi": "x86_64", "launcher": "phone",
                                  "apkSha256": apk_sha or self.metadata["sha256"],
                                  "apkVersion": self.metadata["versionName"], "samples": [sample] * 3})
        return path

    def test_release_is_bound_to_verified_apk_smoke_revision_version_and_physical_hash(self):
        reports = [self.emulator(29), self.emulator(34)]
        receipt = release.smoke_receipt(self.build, reports)
        self.assertEqual([29, 34], [row["api"] for row in receipt["emulator"]])
        self.assertEqual(self.metadata, release.check_publication(receipt, self.apk, REVISION, RUN,
                                                                 "v0.41.36", self.metadata["sha256"]))
        with self.assertRaisesRegex(ValueError, "validação física"):
            release.check_publication(receipt, self.apk, REVISION, RUN, "v0.41.36", "0" * 64)
        with self.assertRaisesRegex(ValueError, "outra revisão"):
            release.check_publication(receipt, self.apk, "b" * 40, RUN, "v0.41.36", self.metadata["sha256"])
        with self.assertRaisesRegex(ValueError, "revisão ou execução"):
            release.check_publication(receipt, self.apk, REVISION, "999", "v0.41.36", self.metadata["sha256"])
        with self.assertRaisesRegex(ValueError, "Tag"):
            release.check_publication(receipt, self.apk, REVISION, RUN, "v0.41.35", self.metadata["sha256"])
        self.apk.write_bytes(b"changed binary")
        with self.assertRaisesRegex(ValueError, "Identidade"):
            release.check_publication(receipt, self.apk, REVISION, RUN, "v0.41.36", self.metadata["sha256"])

    def test_freeze_checks_clean_revision_and_required_graphics_logs(self):
        root = self.path / "source"
        root.mkdir()
        for path in ("scripts/release_matrix.json", "gradle/runtime-artifacts.sha256", "app/lint-warning-baseline.json"):
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / path).read_bytes())
        (root / ".gitignore").write_text("/app/build/\n", encoding="utf-8")

        def git(*args):
            return subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True, text=True).stdout.strip()

        git("init", "--quiet")
        git("add", ".")
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.test", "-c", "commit.gpgsign=false",
            "commit", "--quiet", "-m", "Freeze synthetic test revision")
        revision = git("rev-parse", "HEAD")
        builds = root / "app/build"
        lint = builds / "reports/lint-audit-debug.xml"
        lint.parent.mkdir(parents=True)
        lint.write_text('<issues format="6" by="lint 8.5.2"/>', encoding="utf-8")
        test = builds / "test-results/testDebugUnitTest/TEST-Fake.xml"
        test.parent.mkdir(parents=True)
        test.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"/>', encoding="utf-8")
        gfx = self.path / "graphics"
        gfx.mkdir()
        for suite in release.GRAPHICS:
            (gfx / f"{suite}.log").write_text("Synthetic graphic suite passed.\n", encoding="utf-8")
        data = release.build_evidence(self.apk, revision, RUN, gfx, root)
        release.check_build(data, revision, RUN, root)
        (gfx / "motion_fence.log").write_text("", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Resultado gráfico vazio"):
            release.build_evidence(self.apk, revision, RUN, gfx, root)
        (root / "local-edit").touch()
        with self.assertRaisesRegex(ValueError, "checkout deve estar limpo"):
            release.build_evidence(self.apk, revision, RUN, gfx, root)

    def test_missing_or_mixed_smoke_and_skipped_tests_are_rejected(self):
        report = self.emulator(29)
        with self.assertRaisesRegex(ValueError, "Falta smoke"):
            release.smoke_receipt(self.build, [report])
        with self.assertRaisesRegex(ValueError, "duplicado"):
            release.smoke_receipt(self.build, [report, report])
        with self.assertRaisesRegex(ValueError, "outro APK"):
            release.smoke_receipt(self.build, [report, self.emulator(34, "0" * 64)])
        with self.assertRaisesRegex(ValueError, "zero falhas"):
            release.check_build(self.build | {"jvm": self.build["jvm"] | {"skipped": 1}}, REVISION, RUN)
        with self.assertRaisesRegex(ValueError, "Matriz"):
            release.check_build(self.build | {"matrixSha256": "0" * 64}, REVISION, RUN)


class PhysicalMatrixTest(unittest.TestCase):
    def setUp(self):
        self.plan = matrix.draft_plan(FIXTURE, FIXTURE_SHA, "1" * 64, {
            "matrixSha256": FIXTURE_SHA, "revision": REVISION, "apk": {"sha256": "2" * 64}, "runId": RUN
        })
        for name, definition in FIXTURE["targets"].items():
            target = self.plan["targets"][name]
            target["device"].update(model="fixture-device", firmware="test", api=definition.get("apiMin", 29),
                                    abi=definition.get("abi", "arm64-v8a"), ramMiB=2048, pageSize=definition.get("pageSize", 4096))
            target["conditions"] = dict.fromkeys(FIXTURE["conditions"], "fixed-condition")
            target["p95Margin"].update(dict.fromkeys(FIXTURE["mandatoryMetrics"], 5))
        matrix.check_plan(self.plan, FIXTURE, FIXTURE_SHA)
        self.plan_sha = hashlib.sha256(str(self.plan).encode()).hexdigest()
        self.results = matrix.start_results(self.plan, self.plan_sha, FIXTURE)

    def complete_rounds(self):
        for name, paired in self.results["targets"].items():
            for row in paired.values():
                row.update(decoder="test-codec", pairedRunSeconds=[900] * 3, longSessionSeconds=3600,
                           lifecycleCycles=FIXTURE["targets"][name]["cycles"])
                row["observedStream"].update(codecProfile="H264 baseline", width=1280, height=720, displayHz=60.0)
                row["checks"] = dict.fromkeys(row["checks"], "pass")
                row["incidents"] = dict.fromkeys(row["incidents"], 0)
                for key, minimum in FIXTURE["mandatoryMetrics"].items():
                    row["samples"][key] = [12] * minimum

    def evaluate(self):
        return matrix.evaluate(self.plan, self.plan_sha, self.results, FIXTURE)

    def test_pending_and_regression_block_the_physical_release_digest(self):
        self.assertEqual("pending", self.evaluate()["status"])
        self.complete_rounds()
        report = self.evaluate()
        self.assertEqual("passed", report["status"])
        self.assertIn("inputToPhotonMs", report["targets"]["phone_entry"]["unmeasured"])
        entry = self.results["targets"]["phone_entry"]
        entry["baseline"]["checks"]["dungeonsTouch"] = "fail"
        entry["candidate"]["samples"]["decodeMs"] = [12, 12, 30]
        result = self.evaluate()["targets"]["phone_entry"]
        self.assertEqual(["dungeonsTouch"], result["correctedChecks"])
        self.assertEqual(["p95/decodeMs"], result["failures"])
        self.assertEqual(28.2, result["metrics"]["decodeMs"]["candidate"]["p95"])
        entry["candidate"]["samples"]["decodeMs"] = [12, 12, 12]
        entry["candidate"]["longSessionSeconds"] = 3599
        self.assertEqual("pending", self.evaluate()["status"])

    def test_less_video_work_is_not_a_comparable_performance_pass(self):
        self.complete_rounds()
        candidate = self.results["targets"]["phone_entry"]["candidate"]
        candidate["observedStream"].update(width=854, height=480)
        for key, minimum in FIXTURE["mandatoryMetrics"].items():
            candidate["samples"][key] = [0] * minimum
        report = self.evaluate()
        entry = report["targets"]["phone_entry"]
        self.assertEqual("pending", report["status"])
        self.assertIn("observedStream/notComparable", entry["pending"])
        self.assertEqual({}, entry["metrics"])

    def test_swapping_apk_or_changing_frozen_conditions_is_rejected(self):
        results = copy.deepcopy(self.results)
        results["targets"]["android_tv"]["baseline"]["apkSha256"] = self.plan["candidate"]["apkSha256"]
        with self.assertRaisesRegex(ValueError, "APK ou condições"):
            matrix.evaluate(self.plan, self.plan_sha, results, FIXTURE)
        with self.assertRaisesRegex(ValueError, "outro plano"):
            matrix.evaluate(self.plan, "3" * 64, self.results, FIXTURE)
        self.plan["targets"]["phone_premium"]["device"]["pageSize"] = 4096
        with self.assertRaisesRegex(ValueError, "Tamanho de página"):
            matrix.check_plan(self.plan, FIXTURE, FIXTURE_SHA)


if __name__ == "__main__":
    unittest.main()
