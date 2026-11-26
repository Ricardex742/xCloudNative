import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from check_lint import check
from prepare_signing import SigningError
from smoke_device import ACTIVITY, app_resumed, launch_metrics, main as smoke, total_pss
from verify_apk_signing import runtime_metadata


class LintBaselineTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name)
        self.report = self.root / "report.xml"
        self.baseline = self.root / "baseline.json"

    def lint(self, entries, version="lint 8.5.2", *, write=False):
        issues = "".join(f'<issue id="UnusedResources" severity="{severity}">'
                         f'<location file="{self.root}/{path}"/></issue>' for severity, path in entries)
        self.report.write_text(f'<issues format="6" by="{version}">{issues}</issues>', encoding="utf-8")
        with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
            return check(self.report, self.baseline, self.root, write_baseline=write)

    def test_known_warnings_allowed_across_workspaces_and_new_warnings_fail(self):
        warning = ("Warning", "app/src/a.kt")
        self.assertTrue(self.lint([warning], write=True))
        self.assertTrue(self.lint([warning]))
        self.assertTrue(self.lint([]))
        self.assertFalse(self.lint([warning, warning]))
        self.assertFalse(self.lint([("Warning", "app/src/b.kt")]))
        with self.assertRaisesRegex(ValueError, "Baseline incompatível"):
            self.lint([warning], version="lint 9.0.0")

    def test_errors_cannot_be_baselined(self):
        self.assertFalse(self.lint([("Fatal", "app/src/a.kt")], write=True))
        self.assertFalse(self.baseline.exists())

    def test_maven_version_suggestions_do_not_hide_other_dependency_issues(self):
        self.assertTrue(self.lint([], write=True))
        suggestion = "A newer version of androidx.appcompat:appcompat than 1.6.1 is available: 1.7.1"
        for severity, message, expected in (
            ("Warning", suggestion, True),
            ("Warning", "Dependency requires a newer compile SDK", False),
            ("Error", suggestion, False),
        ):
            with self.subTest(severity=severity, message=message):
                self.report.write_text(
                    '<issues format="6" by="lint 8.5.2">'
                    f'<issue id="GradleDependency" severity="{severity}" message="{message}">'
                    '<location file="app/build.gradle.kts"/></issue></issues>',
                    encoding="utf-8",
                )
                with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(expected, check(self.report, self.baseline, self.root))


class AndroidContractTest(unittest.TestCase):
    def test_apk_contract_checks_both_launchers_and_packaged_abis(self):
        activity = "com.xcloud.tclnative.ui.MainActivity"
        valid = "\n".join(("sdkVersion:'24'", "targetSdkVersion:'34'",
                          f"launchable-activity: name='{activity}'",
                          f"leanback-launchable-activity: name='{activity}'",
                          "uses-feature-not-required: name='android.hardware.touchscreen'",
                          "uses-feature-not-required: name='android.hardware.wifi'",
                          "uses-feature-not-required: name='android.software.leanback'",
                          "native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'"))
        self.assertEqual(24, runtime_metadata(valid)["minSdk"])
        arm64 = valid.replace("'armeabi-v7a' 'x86' 'x86_64'", "")
        self.assertEqual(["arm64-v8a"], runtime_metadata(arm64, "arm64-v8a")["abis"])
        with self.assertRaises(SigningError):
            runtime_metadata(arm64)
        with self.assertRaises(SigningError):
            runtime_metadata(valid, "arm64-v8a")
        with self.assertRaises(SigningError):
            runtime_metadata(arm64, "x86_64")
        for token in ("'arm64-v8a'", "leanback-launchable-activity", "sdkVersion:'24'", "android.hardware.touchscreen"):
            with self.subTest(token=token), self.assertRaises(SigningError):
                runtime_metadata(valid.replace(token, "regression"))

    def test_measures_only_fields_reported_by_android(self):
        self.assertEqual({"totalMs": 44, "waitMs": 51}, launch_metrics("Status: ok\nTotalTime: 44\nWaitTime: 51\n"))
        self.assertEqual({"totalMs": None, "waitMs": 2}, launch_metrics("Status: ok\nWaitTime: 2\n"))
        with self.assertRaisesRegex(RuntimeError, r"(?s)retomada da repetição 2.*Status: timeout"):
            launch_metrics("Status: timeout", "a retomada da repetição 2")
        self.assertEqual(150_000, total_pss("TOTAL PSS: 150000  TOTAL RSS: 330000"))
        self.assertEqual(90_000, total_pss("     TOTAL      90000     30000     50000"))
        self.assertIsNone(total_pss("** No process found"))
        self.assertTrue(app_resumed(f"  topResumedActivity: ActivityRecord{{abc u0 {ACTIVITY} t17}}"))
        self.assertTrue(app_resumed(f"  topResumedActivity=ActivityRecord{{abc u0 {ACTIVITY} t17}}"))
        self.assertTrue(app_resumed(f"  mResumedActivity: ActivityRecord{{abc u0 {ACTIVITY} t17}}"))
        self.assertFalse(app_resumed(f"  topPausingActivity=ActivityRecord{{abc u0 {ACTIVITY} t17}}"))
        self.assertFalse(app_resumed(f"  Hist #0: ActivityRecord{{abc u0 {ACTIVITY} t17}}"))

    def test_smoke_records_a_resume_and_rejects_a_process_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / "app-release.apk"
            apk.with_suffix(".apk.metadata.json").write_text(json.dumps({"versionName": "demo", "sha256": "0" * 64}))
            report = Path(folder) / "result.json"
            visible = False
            launches = 0
            restart = False

            def adb_or_verifier(argv, **_):
                nonlocal visible, launches
                if argv[0] == sys.executable:
                    return subprocess.CompletedProcess(argv, 0)
                match tuple(argv[3:6]):
                    case ("get-state",):
                        stdout = "device\n"
                    case ("install", "-r", _):
                        stdout = "Success\n"
                    case ("shell", "cmd", "package"):
                        stdout = ACTIVITY + "\n"
                    case ("shell", "getprop", "ro.build.version.sdk"):
                        stdout = "29\n"
                    case ("shell", "getprop", "ro.product.cpu.abi"):
                        stdout = "x86_64\n"
                    case ("shell", "am", "force-stop") | ("shell", "input", "keyevent"):
                        visible = False
                        stdout = ""
                    case ("shell", "am", "start"):
                        self.assertEqual(["-W", "-a", "android.intent.action.MAIN", "-c",
                                          "android.intent.category.LAUNCHER", "-n", ACTIVITY], argv[6:])
                        visible = True
                        launches += 1
                        stdout = "Status: ok\n"
                    case ("shell", "dumpsys", "activity"):
                        stdout = f"mResumedActivity: ActivityRecord{{abc u0 {ACTIVITY} t17}}\n" if visible else ""
                    case ("shell", "pidof", _):
                        stdout = str(1000 + (launches if restart else 0))
                    case ("shell", "dumpsys", "meminfo"):
                        stdout = "TOTAL PSS: 100000\n"
                    case ("shell", "cat", "/proc/meminfo"):
                        stdout = "MemTotal: 2000000 kB\n"
                    case other:
                        raise AssertionError(other)
                return subprocess.CompletedProcess(argv, 0, stdout=stdout, stderr="")

            args = ["smoke_device.py", str(apk), "phone-serial", "phone", "--report", str(report)]
            stderr = io.StringIO()
            with patch.dict("os.environ", {"ANDROID_HOME": folder}), patch("subprocess.run", adb_or_verifier), \
                    patch("sys.argv", args), contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, smoke())
                data = report.read_text(encoding="utf-8")
                self.assertNotIn("phone-serial", data)
                self.assertEqual(29, json.loads(data)["api"])
                self.assertEqual(100000, json.loads(data)["samples"][0]["coldStartPssKiB"])
                report.unlink()
                restart = True
                self.assertEqual(1, smoke())
            self.assertIn("processo mudou", stderr.getvalue())
            self.assertFalse(report.exists())


if __name__ == "__main__":
    unittest.main()
