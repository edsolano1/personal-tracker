# Personal Tracker

A personal Android app (Samsung, Android 12+) that reads the plan and money
events Claude Duet writes to the phone's calendar and shows them in three
home-screen widgets and three screens. Offline, no login, no backend. It's a
sideloaded debug build and is never published.

## What it reads

| Event | Example | Used for |
|---|---|---|
| Plan item | `[Plan] Huel`, description `cal: 450` / `protein: 30` | Timeline, food totals, notifications |
| Workout | a plan item whose description has `tag: workout` (or `#workout`) | Makes the day a lift day (2,200 cal) |
| Money | all-day `[Money] Spending left: $123` | Money widget and screen |
| Charges | description lines like `- Chipotle $20.00 (restaurant, pending)` | Pending restaurant charges get a 20% tip estimate |

Targets: 165 g protein; 1,900 cal on rest days, 2,200 on lift days.
A plan item counts as eaten once its start time has passed.

## Widgets
- **Today** (3×2): next 4 plan items with times, calories and protein vs targets
- **Money** (2×1): spending left today
- **Today + Money** (4×2): both

Tapping any widget opens the app. Widgets refresh every 30 minutes and
within seconds of any calendar change.

## Getting the APK
Every push to `main` runs **Actions → Build debug APK**. Open the latest
run and download **personal-tracker-debug-apk** under Artifacts (a zip with
`app-debug.apk` inside). Builds are always signed with the committed
`debug.keystore`, so a new APK installs over the old one.

## Installing with wireless debugging
You need Android platform-tools (`adb`) on the desktop:
https://developer.android.com/tools/releases/platform-tools

1. Put the phone and desktop on the same Wi-Fi.
2. On the phone: **Settings → Developer options → Wireless debugging → Pair device with pairing code.**
   Note the IP:port and the 6-digit code shown.
3. On the desktop: `adb pair <ip>:<pairing-port>` and enter the code.
4. Back on the Wireless debugging screen, note the IP:port under
   "IP address & Port" (a different port from pairing), then:
   `adb connect <ip>:<port>`
5. `adb install -r app-debug.apk`
6. Open Personal Tracker, allow calendar and notification access, then
   long-press the home screen → **Widgets → Personal Tracker**.

Pairing is only needed once; next time start from step 4.
