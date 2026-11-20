"""Package Gradle's actual output metadata, never inferred tag ordering, for the updater."""
import hashlib
import json
import re
import argparse
import shutil
from pathlib import Path


def package(tag, output=Path("app/build/outputs/apk/release"), destination=Path("dist"),
            application_id="com.aidexx.reader"):
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", tag):
        raise ValueError("Invalid tag")
    metadata = json.loads((output / "output-metadata.json").read_text())
    elements = metadata["elements"]
    if len(elements) != 1 or elements[0].get("filters"):
        raise ValueError("Release must contain one universal APK")
    element = elements[0]
    if metadata["applicationId"] != application_id:
        raise ValueError(f"Application ID is {metadata['applicationId']}, expected {application_id}")
    if tag != "v" + element["versionName"] or element["versionCode"] <= 0:
        raise ValueError("Tag must match the APK versionName and versionCode must be positive")
    source = output / element["outputFile"]
    if source.name.endswith("-unsigned.apk"):
        raise ValueError("Refusing unsigned release")
    destination.mkdir(exist_ok=True)
    name = f"GlucoTrail-{tag}.apk"
    apk = destination / name
    shutil.copyfile(source, apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (destination / (name + ".sha256")).write_text(f"{digest}  {name}\n", encoding="utf-8")
    (destination / "update.json").write_text(json.dumps({
        "applicationId": metadata["applicationId"],
        "versionCode": element["versionCode"],
        "versionName": element["versionName"],
        "apk": name,
    }, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Package a signed APK for the GlucoTrail updater.")
    parser.add_argument("tag")
    parser.add_argument("--output", default="app/build/outputs/apk/release")
    parser.add_argument("--destination", default="dist")
    parser.add_argument("--application-id", default="com.aidexx.reader")
    args = parser.parse_args()
    package(args.tag, Path(args.output), Path(args.destination), args.application_id)
