# AiDEX X protocol evidence and iOS boundaries

Source baseline: Android at commit `398b7a23e0a03c7d4f7986a594aaedf4bd4d74b0`.
This is evidence from the repository, not a manufacturer specification or hardware certification.

| iOS behavior | Android evidence | Port decision |
| --- | --- | --- |
| Service 181F; F001/F002/F003 | `app/src/main/java/com/aidexx/reader/protocol/AidexProtocol.kt` | Discover only; no assumed standard CGM characteristic |
| Company 0059, 20-byte payload; LE minute 0..1, signed trend 4, packed glucose 5..6, quality 7, prior values 8..9 and 11..12 | `AidexAdvertisementParser.kt` in the same directory | iOS strips company bytes, requires exact payload size; extensions rejected until evidenced |
| CRC32 polynomial 04C11DB7, seed = sum of four LE32 body words modulo 7FA777; CRC at 16..19 | `AidexAdvertisementParser.kt`, `AidexCrc.kt` | Same arithmetic and fixed Android reference vector |
| Current validity bit 8000 and glucose mask 03FF | `AidexAdvertisementParser.kt` | Reject invalid packets. iOS additionally rejects zero quality and values outside 18..800 as conservative application policy |
| Advertisement packed bits 10..14 | No meanings established by advertisement parser | Reject nonzero reserved bits as unsupported; do not label them warm-up based on a different packet layout |
| AiDEX X name and 10-character serial | `AidexProtocol.serialFromName` | Strict ASCII alphanumeric 10-character suffix. No arbitrary suffix truncation or LinX/Lumi/Smart compatibility claims |
| Clear F003: 17 bytes; trend 3, minute 4..5, packed value 6..7; CRC16 at 15..16 | `AidexF003Parser.kt` | Decrypted-only parser; warm-up bit 0400 rejected, validity/range checked |
| F003 is AES-CFB encrypted using per-connection KEY1 | `AidexCrypto.kt`, `AidexLiveGattClient.kt` | No on-wire decoding enabled; no plaintext fallback |
| KEY0 exchange via F001; KEY1 via F002, CRC8; encrypted setup sequence | `AidexLiveGattClient.kt`, `AidexCrypto.kt`, `AidexProtocol.kt` | Not ported as executable commands pending iOS handshake validation |

`app/src/test/java/com/aidexx/reader/protocol/AidexProtocolTest.kt` supplies advertisement
`3F0000000A7E81647B81647C816400005FD30083` (minute 63, glucose 382, trend 10).
`AidexF003ParserTest.kt` supplies the clear packet fields used by the Swift fixture
(minute 1234, glucose 225, trend -12). Its fixed CRC 070B was independently calculated
using Python's CRC-HQX with initial FFFF; CRC16 also has the standard 123456789 check.
Synthetic fixtures are not recordings from an iPhone or proof of live sensor compatibility.

## Layers

- `Advertisement` and `CurrentPacket`: pure packet parsing with no Bluetooth side effects.
- `ReadingTimeline`: per-sensor monotonic minute acceptance and receipt-age policy. No
  wrap/reset inference: a lower minute is rejected until an explicit session clear.
- `SensorIdentityStore`: versioned local UUID/serial record. Malformed records fail closed.
- `ConnectionSession`: deterministic, clock-injected policy, with simulated event tests.
- `SensorScanner`: main-queue CoreBluetooth adapter, bounded discovery cache, cancellation
  barrier, service discovery and published UI state. Connected never means authenticated.
- SwiftUI: explicit selection, saved serial, permission/power/connection diagnostics,
  historical labeling, and clearly separated demo mode.

No background capability is declared. Suspension cancels connections and invalidates the
current-reading indicator; a duplicate packet cannot revalidate it on resume. Manufacturer
serials are not authenticated and CoreBluetooth UUIDs are local identities, not MAC addresses.
The persisted pair is not a pairing key. Key material and glucose records are not stored.

## Remaining evidence before authenticated GATT

Validate iOS delivery of F001 notifications and write completion ordering, KEY0 response
shape, KEY1 CRC8 validation, AES-CFB segment behavior, encrypted response lengths and
CRC16 checks, and whether each Android setup command changes sensor state. Test supported
firmware, interruption at each handshake step, fresh keys per connection and replay/old
session rejection. Do not enable activation/time/configuration commands solely because
Android defines byte constants. Hardware tests are required separately from compilation.
