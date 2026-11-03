# AiDEX X protocol notes used by v0.1

These notes document only what the app currently implements.

## BLE identity

Primary service:

```text
0000181f-0000-1000-8000-00805f9b34fb
```

Private characteristics:

```text
F001  0000f001-0000-1000-8000-00805f9b34fb  notify + write
F002  0000f002-0000-1000-8000-00805f9b34fb  notify + read + write-no-response
F003  0000f003-0000-1000-8000-00805f9b34fb  notify
F005  0000f005-0000-1000-8000-00805f9b34fb  read
```

CCCD:

```text
00002902-0000-1000-8000-00805f9b34fb
```

The OTA service is intentionally excluded from the application.

## Serial derivation

Map each serial character:

```text
0..9 -> 0..9
A..Z -> 10..35
a..z -> 10..35
```

IV:

```text
mapped[i] = (mapped[i] * 17 + 0x13) & 0xFF
IV = MD5(mapped)
```

F001 request:

```text
mapped[i] = (mapped[i] * 13 + 61) & 0xFF
request = MD5(mapped)
```

## Encryption

```text
AES-128-CFB
NoPadding
16-byte serial-derived IV
```

F001 returns a 16-byte KEY0.

A 17-byte F002 read is decrypted with KEY0 and IV:

```text
bytes 0..15 = KEY1
byte 16     = CRC8/MAXIM(KEY1)
```

A new F002 session block is read after reconnect, producing a new KEY1.

Normal F002 protocol packets are plaintext payload + little-endian CRC16, then AES-CFB encrypted using KEY1 and the same IV.

## CRCs

CRC8/MAXIM:

```text
initial = 0x00
reflected polynomial = 0x8C
```

CRC16-CCITT-FALSE:

```text
initial = 0xFFFF
polynomial = 0x1021
CRC bytes are little-endian in the packet
```

## Passive glucose advertisement

Manufacturer ID:

```text
0x0059
```

The parser expects 20 bytes after the Android manufacturer-ID field is removed:

```text
0..1   sensor minute, little-endian
2      status
3      calibration/temperature field
4      signed trend, units of 0.1 mg/dL/min
5..6   current packed glucose/status
7      current quality
8..9   previous reading 1 packed
10     previous 1 quality
11..12 previous reading 2 packed
13     previous 2 quality
14..15 reserved/other
16..19 CRC32, little-endian
```

Packed current/previous values use the low 10 bits for mg/dL and bit 15 as the validity flag.

Advertisement CRC seed:

```text
seed = (LE32[0..3] + LE32[4..7] + LE32[8..11] + LE32[12..15]) % 0x7FA777
```

CRC32:

```text
non-reflected polynomial = 0x04C11DB7
initial = seed
process bytes 0..15
```

Known active test payload:

```text
3F0000000A7E81647B81647C816400005FD30083
```

Expected decode:

```text
minute       63
current      382 mg/dL
trend raw    10
quality      100
previous 1   379 mg/dL
previous 2   380 mg/dL
CRC          valid
```

## Captured new-sensor startup command order

First GATT connection:

```text
F003 notify
F002 notify
F005 read
F001 notify -> secure bonding if required
F001 serial request -> KEY0
F002 read -> KEY1
0x10 + CRC16
0x21 + CRC16
sensor reports zero/uninitialized start time
DISCONNECT
```

Second GATT connection, same KEY0 but a fresh KEY1:

```text
F003 notify
F002 notify
F005 read
F002 read -> fresh KEY1
0x10 + CRC16
0x31 0x01 + CRC16
0x20 + local date/time + standard timezone quarter-hours + DST quarter-hours + CRC16
0x11 + CRC16
0x21 + CRC16
0x35 0x01 + CRC16
0x34 0x01 + CRC16
final response family 0x134
DISCONNECT
```

The application stops rather than inventing a command if an unexpected startup response family is received.

## v0.1.58.4 live secure-state probe

When the persistent live GATT client receives verified encrypted F002 response `0x111` after the active-sensor finalize sequence, it no longer treats that response as terminal. While F003 remains subscribed, it continues with the same read-only state queries already proven by encrypted history recovery:

```text
0x111 response
0x21 ASK_START_TIME
0x121 response
0x22 ASK_LAST_ID
0x122 response
WAITING_FOR_CURRENT F003
```

The live probe does not request any `0x23` history pages and does not write clock/activation data. It is used only to verify that the selected active sensor is still answering encrypted F002 state requests before waiting for F003 current-glucose notifications.


## v0.1.58.5 F003 delivery probe

After the verified read-only sensor-state response `0x122`, the persistent live client now explicitly re-arms the F003 notification path:

```text
0x122 verified
setCharacteristicNotification(F003, true)
write F003 CCCD = 0x0001
wait for descriptor-write success
```

Diagnostics record the local-notification result, CCCD status/write count, F003 callback count, raw callback byte length/time/source, and validation result. This allows a test to distinguish these cases:

```text
CCCD succeeds + callback count 0      -> sensor/Android delivered no F003 callback
callback count > 0 + CRC/decrypt bad  -> F003 transport exists, packet/session interpretation needs work
callback count > 0 + CRC valid        -> normal current-glucose stream
```

The observed protocol documentation still describes F003 as notify-only. At runtime, the app inspects the Android characteristic properties. Only if `PROPERTY_READ` is actually advertised will it issue a read-only F003 characteristic read after re-arming. No write is ever sent to F003, and no sensor activation/clock/history state is changed by this probe.
