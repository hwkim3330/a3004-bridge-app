# A3004 Bridge — native tablet client

An Android client for the [A3004NS-M sensor bridge](https://github.com/hwkim3330/openwrt/tree/iptime-a3004ns-m/package/keti):
an ipTIME A3004NS-M running OpenWrt with a USB camera, its microphone, an Ouster
lidar, and optionally a USB-CAN adapter and a FlySky receiver.

The router already serves a web dashboard that does all of this in a browser.
This exists because three things are genuinely better natively, not because
native is nicer:

| | browser | app |
|---|---|---|
| control channel | one HTTP request per command; ran into Chrome's parallel-connection cap at 20 Hz | **UDP at 50 Hz** — there is no connection to exhaust |
| lidar ring | polled JSON | the **binary UDP datagram** straight off `ouster-edge` |
| audio | Web Audio, ~80 ms of jitter buffer | `AudioTrack` with a ~40 ms buffer |

It also keeps the screen on and disarms on `onPause`, which a tab cannot promise.

## What it shows

- **Camera** — MJPEG from `ustreamer` on :8080, decoded per frame. The parts are
  found by scanning for JPEG SOI/EOI rather than trusting the multipart boundary,
  which some streamers get subtly wrong.
- **Lidar range ring** — the 360-sector minimum-range ring on UDP :7602, drawn
  as a polar plot. Near is red, far is blue.
- **Microphone** — raw S16 PCM from `mic-stream` on :8082.
- **RC** — 14 i-BUS channels from `rc-ibus`, with the link state.
- **CAN** — interface, frame count, and whether injection is enabled.
- **Teleop** — a touch joystick and an ARM button.

## Safety

Driving something over a wireless link has exactly one failure mode worth
designing for: commands stop arriving while the last one is still being obeyed.
CAN drive commands are level-triggered, so "stop sending" is not a stop.

- nothing is sent as armed until ARM is pressed
- releasing the stick re-centres it — a stick that holds its deflection after
  your finger leaves is a stuck throttle
- frames go out at 50 Hz **armed or not**, so a receiver can tell neutral from
  gone
- leaving the app disarms immediately, rather than waiting for a deadman
- the router's `teleop` runs its own deadman, and the receiver on the machine
  holding the control loop is expected to run a third

Read `doc/TELEOP.md` and `doc/CAN.md` in the firmware repo before connecting
this to anything that moves. The deadmen bound how long a runaway lasts, not
whether one can happen.

## Build

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and an Android SDK with platform 35. Enter the router's address in
the field at the top; it is remembered.

## Verified

Run on a Galaxy Tab S7 FE against the real daemons, with the tablet reachable
over USB tethering:

- camera decoding live at 1280x720
- the ring arriving over UDP and rendering, including the zone alarm flag
- 14 RC channels tracking, CAN frame counts advancing
- `udp_commands` on the daemon climbing at the app's send rate with zero
  malformed packets
- ARM → joystick → daemon `axes=[-0.30, 0.39]` → reference receiver
  `LIVE [-0.26 +0.36]`
- releasing the stick going to neutral while staying armed, because the app keeps
  the stream alive
- backgrounding the app disarming without the deadman having to fire

Not verified: any of it against a real router, since the firmware has not been
flashed to hardware yet.
