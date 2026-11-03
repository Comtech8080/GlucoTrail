import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from package_release import package


class PackagingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / "release"
        self.output.mkdir()
        self.dist = self.root / "dist"
        self.metadata = {"applicationId": "com.aidexx.reader", "elements": [
            {"versionCode": 99, "versionName": "0.1.60.20", "filters": [], "outputFile": "app-release.apk"}]}
        (self.output / "app-release.apk").write_bytes(b"test payload")

    def run_package(self, tag="v0.1.60.20"):
        (self.output / "output-metadata.json").write_text(json.dumps(self.metadata))
        package(tag, self.output, self.dist)

    def test_metadata_and_checksum_match_apk(self):
        self.run_package()
        manifest = json.loads((self.dist / "update.json").read_text())
        self.assertEqual(99, manifest["versionCode"])
        name = manifest["apk"]
        self.assertEqual(hashlib.sha256(b"test payload").hexdigest() + "  " + name + "\n",
                         (self.dist / (name + ".sha256")).read_text())

    def test_tag_must_match(self):
        with self.assertRaises(ValueError):
            self.run_package("v0.1.60.21")

    def test_identity_must_match(self):
        self.metadata["applicationId"] = "other.app"
        with self.assertRaises(ValueError):
            self.run_package()

    def test_unsigned_is_refused(self):
        self.metadata["elements"][0]["outputFile"] = "app-release-unsigned.apk"
        with self.assertRaises(ValueError):
            self.run_package()

    def test_split_apk_is_refused(self):
        self.metadata["elements"][0]["filters"] = [{"filterType": "ABI", "value": "arm64-v8a"}]
        with self.assertRaises(ValueError):
            self.run_package()


if __name__ == "__main__":
    unittest.main()
