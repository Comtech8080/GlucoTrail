# Phase 5 — health tracking and fitness

Version 0.6.0 (6). Open **History → Health journal** or **Settings → Health journal**.
The five existing tabs remain available. Sensor parsing, role switching, alerts,
Bluetooth recovery and Android code are unchanged.

## Journal

Add, inspect, edit and explicitly delete timestamped entries. Browse all dates or a
local calendar day, filtered by type. The supported entries are:

- Notes: up to 5,000 characters and four optional photos.
- Blood pressure: systolic/diastolic mmHg and optional pulse bpm. Whole numbers and
  systolic greater than diastolic are required. Input limits are data checks, not
  interpretations, diagnoses or recommendations.
- Weight: entered/displayed in kg or lb; stored canonically in kg using the exact
  conversion 1 lb = 0.45359237 kg. Changing display units does not rewrite stored data.
- Insulin: user-entered insulin name, recorded administered amount in units, event
  time, and an explicitly chosen countdown duration of 1–1,440 whole minutes. No dose
  or duration is preselected. The countdown is end-time minus current time, survives
  process restart through the saved entry, and is clamped at elapsed. A clock earlier
  than the entry is flagged. This is not insulin-on-board, activity estimation, dosing
  advice or a signal that another dose is safe. There is no countdown alarm while closed.
- Workouts: equipment, duration, optional sets/repetitions/load in kg and notes.
  The starter equipment catalogue includes cardio and strength machines. Add, rename
  or remove custom equipment. Workouts retain the name recorded when saved, including
  after equipment removal. This is manual logging; no fitness-machine BLE protocol,
  exercise recommendation, calorie calculation or HealthKit integration is implied.

New/edited entries must describe completed events, not future scheduled doses. Insulin
history is not deleted when its countdown ends. Editing an insulin time or duration
changes that record's countdown. No journal entry changes raw sensor glucose.

## Photos and persistence

The system Photos picker grants access to selected images without broad library access.
Selected photo data is downsampled to a maximum 1,600-pixel edge, orientation-adjusted,
redrawn and JPEG-encoded at 0.75 quality. Source metadata, including GPS, is not copied.
Animated/multi-image sources become one still image. Sources over 25 MB and resulting
JPEGs over 2 MB are rejected. Originals in Photos are not edited. iCloud-only photos
may require connectivity; errors keep the draft and existing journal intact.

Real journal data lives in Application Support/HealthJournal with complete iOS file
protection and backup exclusion. A versioned JSON index stores up to 5,000 entries and
100 custom machines; photos use generated UUID filenames. Photo bytes are immutable.
The app writes new photos before atomically replacing the index, rolls back new files
on a failed index write, and cleans up photos no longer referenced only after success.
Shared references keep a photo until its final reference is removed. An interrupted
process may leave unreferenced files; it does not publish dangling references or
automatically erase unknown files. Cleanup failures can leave an unused photo on disk.

Corrupt/unknown-version indexes and missing referenced photos fail closed with a visible
error. Existing files are not reset. Storage must be available/unlocked; reopening may
be needed after a protected-file failure. Saves adopt state only after the disk commit.
No automatic purge, server sync, analytics, or automatic photo upload is added.

Demo mode uses a separate memory-only journal, catalogue and photo map. Switching back
loads the real journal. Demo changes last only for the app session; they never enter
the real journal or glucose database, and cannot schedule glucose notifications.

## Export and backup planning

**Export & backup** prepares a versioned JSON snapshot for an inclusive chosen interval.
Journal entries and custom equipment are included; glucose readings/sensor IDs and
photos are independently optional and default off. The file includes a real/demo source
label, UTC timestamps, canonical metric keys (kg, units, mmHg, minutes), raw glucose and
phone receipt times. Photos, when selected, are base64 JPEG values keyed by UUID. When
photos are excluded, the references remain but `photosIncluded` is false and photo data
is empty. Glucose export covers all saved sensor identities in the interval; it does
not infer sensor measurement timestamps or alter raw readings.

Preparation stays in memory. The user explicitly selects **Save JSON file…** and the
destination in the iOS file exporter. Nothing is automatically sent. The file contains
private health information and is not encrypted; choosing a cloud file provider can
upload it. Preparation fails rather than silently truncating: maximum 10,000 readings
per sensor query, 20,000 overall, 20 MB of included photo bytes and 32 MB final JSON.
Use a shorter interval or omit photos if the limit is reached. Editing export options
or journal contents invalidates a prepared snapshot.

App data remains excluded from device backups. Keep periodic exports in a trusted,
protected location with a second copy, and inspect the date range and record counts.
This is a portable export, **not a restorable app backup**: import/restore is not
implemented, and alert settings, sensor roles and transition history are not included.
Deleting the app deletes its local data. Do not uninstall assuming this export can
restore the application. A future restore workflow needs schema migration, attachment
validation, conflict handling, and explicit review before any overwrite.

## Validation

Swift tests cover all entry types, canonical units, invalid values, countdown expiry
and restart, clock reversal, equipment snapshots, date/type filtering, photo/index
transactions and rollback, shared-photo deletion, corruption, missing attachments,
export ranges, raw glucose preservation and explicit photo inclusion. iOS app tests
check downsampling, rejection of bad/oversized input, and removal of injected GPS metadata.
Simulator UI tests exercise note creation/edit/delete/export, invalid insulin input,
countdown presentation and demo isolation, alongside all earlier UI tests.

Physical acceptance remains required: Photos/iCloud selection and cancellation,
large/accessibility text, kg/lb editing, clock/timezone changes, locked-device storage,
disk-full failures, Files/cloud-provider save cancellation, opening exported JSON and
photo extraction, and relaunch with real entries. No real sensor/notification or medical
validation is claimed by these journal tests. Signing/TestFlight remains Phase 6.

Apple API references: [PhotosPicker](https://developer.apple.com/documentation/photosui/photospicker),
[Image I/O thumbnail creation](https://developer.apple.com/documentation/imageio/cgimagesourcecreatethumbnailatindex(_:_:_:)).
