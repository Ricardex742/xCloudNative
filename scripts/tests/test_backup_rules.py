from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


MAIN = Path(__file__).resolve().parents[2] / "app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"
PRIVATE_PREFERENCES = {
    "xcloud_native_auth.xml",
    "session_diagnostic.xml",
    "startup_diagnostics.xml",
}


class BackupPolicyTest(unittest.TestCase):
    def test_auth_and_diagnostics_are_excluded_from_all_backup_paths(self):
        application = ET.parse(MAIN / "AndroidManifest.xml").getroot().find("application")

        def rules(attribute):
            resource = application.attrib[ANDROID + attribute]
            self.assertTrue(resource.startswith("@xml/"))
            return ET.parse(MAIN / "res/xml" / (resource.removeprefix("@xml/") + ".xml")).getroot()

        legacy = rules("fullBackupContent")
        modern = rules("dataExtractionRules")
        self.assertEqual("full-backup-content", legacy.tag)
        self.assertEqual("data-extraction-rules", modern.tag)
        for policy in (legacy, modern.find("cloud-backup"), modern.find("device-transfer")):
            self.assertIsNotNone(policy)
            exclusions = {rule.attrib["path"] for rule in policy.findall("exclude")
                          if rule.attrib["domain"] == "sharedpref"}
            self.assertTrue(PRIVATE_PREFERENCES <= exclusions)
