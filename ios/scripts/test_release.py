import plistlib
from pathlib import Path
import tempfile
import unittest
import zipfile
import release


class ReleaseTests(unittest.TestCase):
    def env(self):
        return dict(IOS_RELEASE_SHA="a" * 40, IOS_BUILD_NUMBER="8", IOS_RELEASE_ACTION="upload-only")

    def test_exact_commit_and_explicit_action(self):
        self.assertEqual(release.selection(self.env(), "a" * 40, 7), "8")
        for changes in ({"IOS_RELEASE_SHA": "b" * 40}, {"IOS_RELEASE_SHA": "aaaa"},
                        {"IOS_RELEASE_ACTION": ""}, {"IOS_RELEASE_ACTION": "public"}):
            with self.assertRaises(ValueError):
                release.selection(self.env() | changes, "a" * 40, 7)

    def test_missing_and_invalid_build_numbers_fail(self):
        for number in ("", "0", "6", "08", "8.1", "10000", "8; echo bad", " 8"):
            with self.subTest(number=number), self.assertRaises(ValueError):
                release.selection(self.env() | {"IOS_BUILD_NUMBER": number}, "a" * 40, 7)
        with self.assertRaises(ValueError):
            release.selection({}, "a" * 40, 7)

    def test_repository_notes_match_version(self):
        version, minimum, notes = release.metadata()
        self.assertEqual(version, notes["version"])
        self.assertGreaterEqual(minimum, 7)

    def ipa(self, folder, overrides=None, missing=None):
        path = Path(folder) / "test.ipa"
        info = dict(CFBundleIdentifier=release.BUNDLE, CFBundleShortVersionString="0.7.0", CFBundleVersion="8")
        info.update(overrides or {})
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("Payload/GlucoTrail.app/Info.plist", plistlib.dumps(info, fmt=plistlib.FMT_BINARY))
            for name in ("embedded.mobileprovision", "_CodeSignature/CodeResources", "PrivacyInfo.xcprivacy"):
                if name != missing:
                    archive.writestr("Payload/GlucoTrail.app/" + name, b"fixture")
        return path

    def test_export_matches_exact_release(self):
        with tempfile.TemporaryDirectory() as folder:
            release.check_ipa(self.ipa(folder), "0.7.0", "8")

    def test_export_rejects_wrong_identity_version_and_build(self):
        with tempfile.TemporaryDirectory() as folder:
            for key in ("CFBundleIdentifier", "CFBundleShortVersionString", "CFBundleVersion"):
                with self.subTest(key=key), self.assertRaises(ValueError):
                    release.check_ipa(self.ipa(folder, {key: "wrong"}), "0.7.0", "8")

    def test_export_rejects_missing_signing_and_privacy_files(self):
        with tempfile.TemporaryDirectory() as folder:
            for name in ("embedded.mobileprovision", "_CodeSignature/CodeResources", "PrivacyInfo.xcprivacy"):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    release.check_ipa(self.ipa(folder, missing=name), "0.7.0", "8")

    def test_export_rejects_multiple_apps(self):
        with tempfile.TemporaryDirectory() as folder:
            path = self.ipa(folder)
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("Payload/Other.app/Info.plist", b"bad")
            with self.assertRaises(ValueError):
                release.check_ipa(path, "0.7.0", "8")


if __name__ == "__main__":
    unittest.main()
