# ClearHUD

A clean heads-up display app for Android. It does two things:

1. **Drives a WYHUD / Wiiyii Bluetooth HUD** with Google Maps turn-by-turn directions, trip ETA, street names, and call/message alerts.
2. **Turns the phone itself into a HUD.** Set it flat on the dash and it shows a mirrored, black-background display that reflects off the windshield: turn arrow with approach bar, speed, posted speed limit, arrival time, and OBD engine data.

## Data sources
- **Directions:** the Google Maps navigation notification. Grant notification access in setup.
- **Speed:** phone GPS, or the car's own speed when a Veepeak OBD adapter is connected.
- **Speed limit:** OpenStreetMap via the Overpass API. Coverage varies.
- **Engine:** any ELM327-compatible OBD2 adapter, Classic or BLE (RPM, coolant, voltage).

## Install and updates
Download the latest APK from [Releases](../../releases/latest). After that, the app updates itself from new releases.

## WYHUD Bluetooth protocol notes
Worked out for interoperability so this app can talk to the hardware.

- **Service and write channel:** service `0000fee9-0000-1000-8000-00805f9b34fb`, write characteristic `d44bc439-abfd-45a2-b575-925416129600`. Use write-without-response. No handshake is needed.
- **Packet framing:** each packet is the ASCII string `^` + base64(frame) + `$`.
- **Frame layout:** `31 30 | sum_hi sum_lo | 07 | cmd | payload`, where the sum is the 16-bit total of every byte except the two sum bytes.
- **Chunking:** frames longer than the link allows go out in MTU-sized pieces about 50 ms apart. The app requests an MTU of 80.

| cmd | meaning | payload |
|---|---|---|
| 1 | next turn | icon u16, meters low u16, `01`, meters high u16, following icon u16 |
| 3 | speed camera | `00 00 00` to clear, or `01` + meters u16 |
| 4 | lanes | 12 bytes, one per lane: low bits = kind, `0x80` = recommended, `0x0F` = empty |
| 6 | text line | length byte + GB2312 text |
| 9 | trip | hours, minutes, remaining distance in units of 100 m (u16) |

**Turn icons:** left 2, right 3, slight left 4, slight right 5, sharp left 6, sharp right 7, U-turn 8 / 19, straight 9, roundabout 11, roundabout exit 12, other 13, arrive 15.
