#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
SIMULATOR_ID=$(xcrun simctl list devices available -j | python3 -c 'import json,sys; d=json.load(sys.stdin); print(next(x["udid"] for devices in d["devices"].values() for x in devices if "iPhone" in x["name"] and x["isAvailable"]))')
xcodebuild test -project GlucoTrail.xcodeproj -scheme GlucoTrail \
  -destination "platform=iOS Simulator,id=$SIMULATOR_ID" \
  -derivedDataPath build/ui -resultBundlePath build/UITests.xcresult \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
