# Dual sensor overlap: implementation status

This feature is **experimental and incomplete**. Do not use it as your only
glucose monitoring method. See [Issue #3](https://github.com/Comtech8080/GlucoTrail/issues/3).

## Implemented on this branch

- Passive Android PendingIntent BLE scanning can filter both a primary and a
  configured replacement AiDEX X address.
- Background receiver persists replacement advertisements under the replacement
  sensor's **own serial** with existing CRC validation. Only the primary sensor
  produces glucose alarms.
- `AidexViewModel.configureReplacementSensor(AidexSensor)` stores the next
  sensor's serial/address without selecting it as primary.
- `refreshOverlapComparison()` loads separately stored readings and publishes
  the latest trustworthy paired comparison.
- `SensorOverlapComparison` rejects invalid, CRC-failed, stale, warm-up, and
  time-mismatched readings; an optional fingerstick comparison reports each
  sensor's error without automatically calibrating from the old sensor.
- Unit tests cover sample pairing, discrepancy detection, fingerstick
  comparison and readiness conditions.

## Still required before a usable Android release

1. Wire the new sensor registration into the Start New Sensor page.
2. Preserve primary monitoring during replacement activation (activation
   currently stops the foreground monitoring service).
3. Build the two individual graphs and the overlay comparison graph, plus
   calibration-assist prompts for a fingerstick reference.
4. Implement reliable primary handover with explicit expiry detection,
   connection-loss handling, manual override and alarm continuity.
5. Exercise dual GATT/advertising and background scanning with **two physical
   AiDEX X all-in-one sensors** and validate Android power restrictions.
6. Run Gradle tests, build an APK, perform on-device tests and release only
   after confirming no monitoring regression.

## Safety

The 14-day-labelled sensor's expiration is not bypassed. Extra life is not
guaranteed. Do not use readings from an expired or unreliable sensor to make
treatment decisions. A CGM-to-CGM difference is not a reference calibration:
confirm with a fingerstick and follow official sensor calibration guidance.
