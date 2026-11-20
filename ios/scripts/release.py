"""Fail-closed beta metadata checks; no credentials, network calls or uploads."""
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = "com.glucotrail.ios"


def metadata(root=ROOT):
    project = (root / "ios/project.yml").read_text(encoding="utf-8")
    version = re.search(r"MARKETING_VERSION: '([0-9]+\.[0-9]+\.[0-9]+)'", project)
    build = re.search(r"CURRENT_PROJECT_VERSION: ([0-9]+)", project)
    if not version or not build:
        raise ValueError("Missing canonical version/build in ios/project.yml")
    history = json.loads((root / "ios/App/ReleaseHistory.json").read_text(encoding="utf-8"))
    if not history or history[0]["version"] != version[1]:
        raise ValueError("Latest release notes must match the project version")
    if len({r["version"] for r in history}) != len(history):
        raise ValueError("Duplicate release versions")
    if any(not r["title"].strip() or not r["changes"] or
           any(not c.strip() for c in r["changes"]) for r in history):
        raise ValueError("Release notes must not be empty")
    return version[1], int(build[1]), history[0]


def selection(env, head, minimum):
    approved = env.get("IOS_RELEASE_SHA", "")
    number = env.get("IOS_BUILD_NUMBER", "")
    if not re.fullmatch(r"[0-9a-f]{40}", approved) or approved != head:
        raise ValueError("IOS_RELEASE_SHA must exactly match the reviewed checkout SHA")
    if not re.fullmatch(r"[1-9][0-9]{0,3}", number) or int(number) < minimum:
        raise ValueError(f"IOS_BUILD_NUMBER must be an integer from {minimum} to 9999")
    if env.get("IOS_RELEASE_ACTION") != "upload-only":
        raise ValueError("Set IOS_RELEASE_ACTION=upload-only for this manually selected release")
    return number


def check_ipa(path, version, number):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        plists = [n for n in names if re.fullmatch(r"Payload/[^/]+\.app/Info.plist", n)]
        if len(plists) != 1:
            raise ValueError("Expected exactly one application in the IPA")
        prefix = plists[0].removesuffix("Info.plist")
        info = plistlib.loads(archive.read(plists[0]))
        expected = {"CFBundleIdentifier": BUNDLE, "CFBundleShortVersionString": version,
                    "CFBundleVersion": number}
        if any(info.get(k) != v for k, v in expected.items()):
            raise ValueError("IPA bundle/version/build does not match the selected release")
        for required in ("embedded.mobileprovision", "_CodeSignature/CodeResources", "PrivacyInfo.xcprivacy"):
            if prefix + required not in names:
                raise ValueError(f"IPA missing {required}")
        # Apple performs cryptographic signature/provisioning validation on upload.


def main(action):
    version, minimum, notes = metadata()
    if action == "check":
        print(f"Release metadata valid: {version} ({minimum})")
        return
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    number = selection(os.environ, head, minimum)
    output = ROOT / "ios/build"
    record_path = output / "release-record.json"
    if action == "prepare":
        dirty = subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=ROOT, text=True)
        if dirty.strip():
            raise ValueError("Release checkout must be clean before generation and tests")
        output.mkdir(parents=True, exist_ok=True)
        record = {"commit": head, "version": version, "build": number,
                  "bundleIdentifier": BUNDLE, "distribution": "upload-only"}
        record_path.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
        (output / "what-to-test.txt").write_text(
            f"GlucoTrail {version} ({number})\nCommit: {head}\n\n" +
            "\n".join(notes["changes"]) +
            "\n\nTest demo navigation, journal editing/export and feedback preview. "
            "Physical sensor and notification acceptance is still required. "
            "Keep using your established monitoring system.\n", encoding="utf-8")
    elif action == "verify":
        record = json.loads(record_path.read_text(encoding="utf-8"))
        if (record["commit"], record["version"], record["build"]) != (head, version, number):
            raise ValueError("Release selection changed after preparation")
        artifacts = list((output / "ios/ipa").glob("*.ipa"))
        if len(artifacts) != 1:
            raise ValueError("Expected exactly one exported IPA")
        check_ipa(artifacts[0], version, number)
        record["ipaSHA256"] = hashlib.sha256(artifacts[0].read_bytes()).hexdigest()
        record["artifact"] = artifacts[0].name
        record_path.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    else:
        raise ValueError("Expected check, prepare or verify")
    print(f"Release {action} succeeded: {version} ({number}), {head}")


if __name__ == "__main__":
    try:
        main(sys.argv[1] if len(sys.argv) == 2 else "")
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        sys.exit(f"Release stopped: {error}")
