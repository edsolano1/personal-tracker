# Personal Tracker

A personal Android app (Samsung, Android 12+) that reads the plan and money
events Claude Duet writes to the phone's calendar and shows them in three
home-screen widgets and three screens. Offline, no login, no backend. It's a
sideloaded debug build and is never published.

## What it reads
Events come from the Google calendar named **Personal Tracker**. If that
calendar doesn't exist, any calendar's `[Plan]`/`[Money]` events are used.

**Plan items** – timed event, title `[Plan] Back + Chest`, description lines:
```
type: workout        # meal | workout | walk | task | event
slot: Evening        # meals: Breakfast | Lunch | Dinner | Snack
mins: 60
cal: 0
protein: 0
swap: Huel | 400 | 40   # optional, meals only: name | cal | protein
eaten: yes           # optional, meals only: yes | no
```
A `type: workout` item makes it a lift day. Unknown keys are ignored.

`eaten` is the only thing that says a meal happened. `eaten: yes` counts it
towards the day's calories and protein, `eaten: no` marks it skipped, and
with no line at all the meal stays planned however long ago it was. The app
writes the same line back when Eddie answers on the phone, so the calendar
is the one record both sides read.

**Money** – all-day event `[Money] Spending left: $123.45`, description lines:
```
pending | Chipotle | 17.40 | tip
pending | Target | 23.10
posted | Starbucks | 6.25
```
The title number is final (carryover, pending charges and tips already
included); the app only displays it. The newest `[Money]` event for today
wins; with none today, yesterday's is shown with an "as of" date.

Targets: 165 g protein; 1,900 cal on rest days, 2,200 on lift days.
Nothing is ever assumed from the clock: a meal counts only once it is
answered, on the phone or by an `eaten:` line. Done checks on non-food items
are stored on the phone only. **Today → Raw events** shows every event the
app read and how it parsed it.

## Changing a meal
**Food → Change this meal** swaps in a different food, takes a portion as an
amount and a unit (servings, grams, oz, cups) and moves the calories and
protein with it. Grams and ounces convert; cups and grams can't be compared
without knowing the density, so it asks for the numbers instead of guessing.
Typing a calorie or protein figure yourself stops the recalculating.
"Save this food and portion" keeps it on the list for next time, and
"Back to plan" throws the edit away. Edits live on the phone only — the
`[Plan]` event itself is left exactly as Claude wrote it.

## Adding a meal, and My foods
**Food → Add a meal** logs something that was never on the plan. It is written
to the calendar as a `[Plan]` event that already says `eaten: yes` (plus a
`portion:` line when one was entered), so it counts at once and Claude sees it.
Tapping a row under **Fits what's left** or **My foods** opens the same dialog
already filled in. **New food** saves a food to the list without logging it.

## Refresh
Google syncs the calendar to the phone only about once a day by itself. The app
asks for a sync when it comes to the front (at most every ten minutes), after
adding a meal, and from **Today → Refresh**, which also shows when the calendar
was last read.

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
