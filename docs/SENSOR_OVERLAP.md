# Dual AiDEX X sensor overlap (experimental)

**Development status:** Implementation on `feature/dual-sensor-calibration-assist`, not released. See [Issue #3](https://github.com/Comtech8080/GlucoTrail/issues/3) and [Draft PR #4](https://github.com/Comtech8080/GlucoTrail/pull/4).

## Implemented on this branch

- A Dual Sensor Monitoring screen accessible from the app settings menu.
- Old, New and Compare graph modes with 2, 4, 6, 48 and 72-hour ranges. Separate colors and no fabricated readings between gaps.
- Start / activate replacement flow that preserves the current primary selection and no longer explicitly stops foreground monitoring during secondary activation.
- PendingIntent BLE scan filters for the two sensor addresses; both streams saved with their own serials.
- Primary-only glucose alarms, so replacement readings do not generate duplicate high/low notifications.
- Opt-in automatic primary handover when the old sensor has supplied no verified readings for **15 minutes** and the replacement has at least three recent valid, distinct-minute post-warmup samples (within 5 minutes).
- Manual primary switch only after a fresh CRC-verified post-warmup replacement reading.
- Preserves old/new history references and a separate history of display calibration adjustments.
- Fingerstick-meter comparison to show individual sensor errors. **No automatic calibration using the old CGM**. The explicit, user-confirmed fingerstick action adds a time-based correction to replacement display readings, retaining raw data.
- Unit tests for stream comparison validity, time-window filtering, warmup rejection and replacement readiness.

## Testing required before release

1. Confirm GitHub Actions Android unit tests, compilation, and lint succeed on the latest commit.
2. Check on real Android phone with **two AiDEX X all-in-one sensors** that activation of the second does not affect primary alarm continuity.
3. Test simultaneous BLE scan packets, GATT and reconnection behaviour in background, screen-off and battery-restricted modes.
4. Test automatic handover during actual old-sensor expiry and transient Bluetooth loss, manual handover, reboot and app update.
5. Validate history, calibration adjustment history and no gaps invented by the charts.
6. Verify alerts if **neither** sensor can provide a reliable glucose value.
7. Confirm sensor firmware permits the use pattern. Do not use outside manufacturer's validated lifespan.

## Important limitations

- Auto-handover is off by default; it is an experimental loss-of-readings fallback, **not** proof of sensor end-of-life. A Bluetooth disconnect can mimic expiration.
- The app cannot ensure readings are medically reliable beyond the sensor's printed 14-day expiration.
- Readings from two CGMs may differ because of lag or calibration drift. A failing old sensor is not a trustworthy reference for the new one.
- Calibration in this app is a display-level adjustment, not a calibration command to AiDEX sensor firmware. Follow manufacturer's approved calibration steps and use a fingerstick meter to confirm unexpected values.
- Until hardware tests are complete, do not use this build as the sole source of glucose readings or alarms.
