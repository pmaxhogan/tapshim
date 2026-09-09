# Bose shortcut "Spotify" mode: how it reaches the phone

Reverse-engineered on 2026-09-09 from the Bose app 10.2.4 (`com.bose.bosemusic`)
and Spotify 9.1.80 (`com.spotify.music`) Android APKs. Nothing from either app
is included in this repository; these are observations.

## Bose side

- The shortcut modes are written into headphone firmware over Bose's BMAP
  protocol as a `ConfigurableButtonRequest`. The Spotify option is mode 16
  (`ActionButtonMode.SPOTIFY_GO_MODE`).
- The Bose app never launches Spotify. Its only references to
  `com.spotify.music` are an is-installed check (which gates the radio button
  and shows an "unavailable" sheet) and an open-Play-Store helper.
- Uninstalling Spotify does not reset the mode stored in the headphones.

## Spotify side (the actual trigger path)

- The headphones expose an RFCOMM (SPP) server with SDP UUID
  `9B26D8C0-A8ED-440B-95B0-C4714A518BCC`.
- On an A2DP or LE Audio `CONNECTION_STATE_CHANGED` broadcast, Spotify starts a
  `connectedDevice` foreground service with the device address, calls
  `createInsecureRfcommSocketToServiceRecord(uuid).connect()`, and blocks on
  `InputStream.read(buf, 0, 257)` in a loop.
- Each shortcut press is one packet from the headphones:

  ```
  0x01, <len>, then <len> bytes = clientId NUL deviceName NUL manufacturer NUL
  ```

  Three NUL-terminated ASCII strings. Spotify reads the length byte as signed
  and rejects values over 127; TapShim accepts the full unsigned range. A
  separate magic byte sequence exists for legacy Microsoft Surface Buds.
- The "press again to discover music" behaviour lives in the Spotify app, not
  in the headphones.

## Consequence

Any app holding `BLUETOOTH_CONNECT` can play the client role instead of
Spotify. Only one client should own the socket, so Spotify must be uninstalled.
That is exactly what TapShim does; see `TapSocketService` and the `protocol`
package.
