# Phase 4 — notifications and safety validation

Version 0.5.0 (5) adds Settings → Alerts & safety. Android and the AiDEX packet
decoder are unchanged. No encrypted GATT authentication, activation, time-setting,
calibration or notification subscription has been added.

## Alerts

The master switch and every category default OFF. Permission is requested only by
the user. Permission alone does not enable alerts. Thresholds use canonical integer
mg/dL with an accompanying mmol/L conversion. The example low/high values (70/180)
are editable software defaults, not treatment recommendations; low must be below high.

- Low/high use inclusive thresholds and newly persisted PRIMARY advertisement data
  only: post-warm-up minute ≥60, quality >0, glucose 18–800, receipt age under 120 seconds.
  Replacement/demo/restored history, old minutes, duplicates, future receipts and
  invalid packets cannot trigger a glucose notification. Receipt time is not proof
  of sensor measurement time. Raw glucose is never modified.
- Each glucose category has an independent 5–60 minute cooldown (default 15), and
  repeating requires another new reading. A normal reading cancels obsolete pending
  glucose requests. A pending glucose request expires after 120 seconds of receipt
  age when the app can execute. Permission callbacks and notification completion
  callbacks recheck request identity/expiry to reject obsolete work.
- Stale reminders use the last eligible receipt or session start, configurable
  3–30 minutes (default 5). A new eligible minute replaces the old request. A warm-up
  packet does not establish usable glucose reception.
- Connection reminders are separate from data age, configurable 1–30 minutes
  (default 5). Advertisements can arrive without a connection. Repeated disconnect
  callbacks do not postpone the deadline. Reconnection cancels that request.
- Stale/disconnection reminders are one-shot per episode, not timer-driven repeated
  alarms. iOS receives at most three current requests. They may be delivered while
  the app is suspended; their wording describes when the check was scheduled, not
  a claim of real-time monitoring. The OS may delay presentation or a cancellation.
- Stop, demo mode, disabling settings or changing primary cancels obsolete requests.
  Backgrounding cancels glucose requests but retains scheduled check reminders.
  Foreground return starts new warning grace periods and requires new glucose data.
  Long execution gaps (>90 seconds) or backwards clock movement reset evidence.
  App relaunch clears pending requests; saved history is never evaluated as live.

The app requests ordinary banner/sound permission, not Critical Alerts or silent-mode
override. Focus, silent mode, notification settings and OS scheduling affect delivery.
No glucose value or sensor serial is placed in notification text. High/low text includes
the receipt time and explicitly says the alert may be delayed.

## History and errors

`alerts.json` stores settings and the last 200 request-status events together by atomic
file replacement in the existing completely protected, backup-excluded directory.
The history contains kind, request ID, request/delivery times and status, not glucose
values. An iOS acceptance callback is **not proof of delivery**. Delivery is marked
observed only via a foreground callback or a delivered-notifications query; even that
cannot prove the user saw/heard it. Dismissed notifications may never be observed.
Cancellation records mean a cancellation was requested, not guaranteed prevention.

Corruption and unsupported schemas fail without deleting the file. A request must be
recorded before it is submitted; a storage failure prevents that new submission and
is visible in the screen. Already scheduled OS reminders may still arrive. Settings
changes are adopted only after a successful save. The preview uses memory-only
policy simulations and cannot request permission, schedule real notifications or
write alert history.

## Background recovery boundary

An explicit confirmation enables experimental connection recovery. The app declares
`bluetooth-central` and uses one stable CoreBluetooth restoration identifier. Recovery
requires saved user intent, usable local storage and a selected UUID. Unknown/restored
unselected peripherals are cancelled. Stop revokes persisted recovery intent. Demo
launches do not instantiate the Bluetooth manager. State restoration never restores
glucose freshness or automatic-handover consent.

Background scanning/data processing remains disabled. Existing connections may remain
and saved primary/replacement connections may be requested/recovered while iOS grants
execution. Pending connections are managed by CoreBluetooth; bounded retry delays can
only execute when the process is runnable. No background timer or relaunch is promised.
Completely protected storage may be unavailable while locked; restoration then fails
closed and requires reopening/unlocking and restarting reception. Automatic sensor
handover remains foreground-only and is disarmed on inactivity.

Apple coalesces background advertisement discoveries, so repeated foreground packets
are not a validated continuous background stream. Force-quitting generally prevents
Bluetooth relaunch; the user must reopen and check reception. Any pre-scheduled check
reminder after force-quit is not evidence that the app is monitoring glucose.

Sources checked for implementation:

- [Apple: Core Bluetooth background processing and restoration](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html)
- [Apple TN3115: Bluetooth restoration relaunch rules](https://developer.apple.com/documentation/technotes/tn3115-bluetooth-state-restoration-app-relaunch-rules)
- [Apple: scheduling local notifications](https://developer.apple.com/documentation/usernotifications/scheduling-a-notification-locally-from-your-app)
- [Apple: requesting notification permission](https://developer.apple.com/documentation/usernotifications/asking-permission-to-use-notifications)

## Automated validation

Swift package tests cover defaults/validation, inclusive thresholds, independent
cooldowns, duplicates, unselected sensors, warm-up/invalid/delayed/future readings,
missing readings, deadline replacement, reconnects, stop/resume, clock changes,
relaunch isolation, corruption, bounded history and recovery selection gates.
Simulator UI tests exercise low/high/stale/disconnected/delayed/stopped previews,
verify alerts stay disabled and real history stays empty, alongside existing dashboard
and dual-sensor handover tests. Existing GitHub/Codemagic scripts run all these tests
plus unsigned simulator/iPhone builds. A policy simulation does not prove OS delivery.

## Required physical acceptance (not yet performed)

Use a signed build on supported iPhones with already activated sensors and an independent
established monitoring system. Record iPhone/iOS/sensor versions and timestamps privately.
Do not put glucose data or device identifiers in public CI logs/issues.

| Scenario | Required observations |
| --- | --- |
| First launch, deny/grant/revoke permissions | No surprise prompt; clear permission/sound/banner state; no delivery falsely marked confirmed |
| Configured low/high thresholds with controlled input | Only new valid primary data triggers; unchanged raw values; cooldown and normal-recovery cancellation |
| Missing data and duplicates | Warning based on original eligible receipt; duplicates do not defer it; delayed messages identify scheduling time |
| Stop, demo, settings off, handover | Old pending requests cancel; no old-primary or demo threshold alert; fresh data required after restart |
| Primary and replacement disconnect independently | Separate retry states; correct selected primary warning; no unintended handover |
| Lock/background, suspend, return | Glucose becomes historical; reminders may appear; no continuous background-glucose claim; foreground requires new data |
| Bluetooth off/on, permission revoke, airplane mode | Disconnected/stale states remain truthful; no false-current value or inferred expiry |
| System termination and restored peripherals | Restore only consented selected UUIDs; cancel unknowns; complete-file-protection failure remains visible |
| Force-quit, wait, reopen | Do not expect relaunch or new glucose alerts; distinguish pre-scheduled reminders; verify new reception explicitly |
| Focus/silent mode, notification summary, delayed delivery | Document actual presentation and cancellation delays; accepted is not equated with heard |
| Clock/timezone changes, storage failure, corrupt archive | Reset unsafe freshness; preserve files; visible error; no unrecorded new notification submission |

These device tests and medical suitability validation are outstanding. Signing and
TestFlight remain Phase 6 work; an unsigned artifact cannot be installed directly.
