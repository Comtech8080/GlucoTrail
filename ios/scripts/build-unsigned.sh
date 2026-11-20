#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
xcodebuild -project GlucoTrail.xcodeproj -scheme GlucoTrail \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/simulator -resultBundlePath build/Simulator.xcresult \
  CODE_SIGNING_ALLOWED=NO build
ditto -c -k --sequesterRsrc --keepParent \
  build/simulator/Build/Products/Debug-iphonesimulator/GlucoTrail.app GlucoTrail-Simulator.zip
xcodebuild -project GlucoTrail.xcodeproj -scheme GlucoTrail \
  -configuration Debug -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath build/device -resultBundlePath build/iPhone.xcresult \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
ditto -c -k --sequesterRsrc --keepParent \
  build/device/Build/Products/Debug-iphoneos/GlucoTrail.app GlucoTrail-iPhone-Unsigned.zip
# Exercise the release/archive path without credentials. This is not an installable IPA.
xcodebuild archive -project GlucoTrail.xcodeproj -scheme GlucoTrail \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  -archivePath build/GlucoTrail-Unsigned.xcarchive \
  -derivedDataPath build/release -resultBundlePath build/Release.xcresult \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO
