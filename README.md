# FocusGuard — strict distraction blocker for Android

Limit social apps to the minutes you choose, make yourself pause before opening them,
lock everything during focus sessions, and make the rules hard to cheat.

## Features

| Feature | What it does |
|---|---|
| **Daily limit** | e.g. Instagram 30 min/day. Time is counted only while the app is on screen and the phone is unlocked. |
| **Mindful gate** | Opening a gated app shows a pause screen: wait N seconds, type *why* you're opening it, pick a session length. |
| **Session + cooldown** | When the session ends, the app locks for a cooldown (e.g. 30 min). |
| **Max opens per day** | Each started session counts as an open. |
| **Schedules** | Sleep, work or study windows (overnight windows supported, per-day selection). |
| **Focus session** | One tap: every app on your list is locked for 15–120 min. |
| **Strict mode** | Rules become append-only for 1 h – 7 days: no removing/loosening limits, no ending focus early, no disabling schedules. FocusGuard's own Settings / uninstall screens are bounced to Home. |
| **Website blocking** | Blocks domains (and subdomains) in Chrome, Edge, Brave, Firefox, Opera, Vivaldi, Samsung Internet, DuckDuckGo. |
| **Clock-cheat protection** | Changing the phone's date/time does not reset limits or end locks. |
| **Uninstall protection** | Optional device-admin registration: Android refuses to uninstall until it is removed. |
| **Insights** | Today's time per app, blocks, sessions, 7-day chart, and a journal of the reasons you typed. |

## Build & install (5 minutes)

1. Install **Android Studio** (current stable). Open the `FocusGuard` folder.
2. Let Gradle sync (needs internet the first time). It uses the included Gradle wrapper (8.9) and JDK 17+.
3. Plug in your phone (USB debugging on) and press **Run ▶**.
   Or from a terminal: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
4. Run the unit tests any time: `./gradlew test`.

## First-run setup (inside the app, Today tab)

1. **Accessibility service → Enable** → find *FocusGuard* → turn on.
   *Android 13+ and the switch is greyed out?* Tap **Open App info** in the app → ⋮ menu → **Allow restricted settings** → go back and retry.
2. **Display over other apps → Allow.**
3. **Unrestricted battery → Fix** (stops the system from sleeping the service). On Xiaomi/Oppo/Vivo/Samsung also enable *Autostart* / *Never sleeping apps* for FocusGuard.
4. (Optional) **Uninstall protection → Turn on.**
5. **Apps → Add**, pick your distracting apps. Defaults: 30 min/day, 10-minute gated sessions, 30-minute cooldown. Tap an app to tune it.

## How strict is "strict"?

Strict mode stops casual bypassing: no editing rules, no turning off focus, no reaching the Accessibility /
App-info / uninstall screens while FocusGuard is named on them, no resetting by changing the clock.

It is **not** unbreakable, by design of Android: Safe Mode, `adb`, a factory reset, or a second device
will always get around an app. While strict mode is on, Settings pages that list FocusGuard
(including the Accessibility page) are closed, which also hides other accessibility services' toggles
on that page; keep strict windows short at first (1–6 h) to learn how it feels.

## Project map

```
app/src/main/java/com/focusguard/app/
  Logic.kt         pure rules engine (unit-tested): limits, sessions, cooldowns, schedules, sites
  Clock.kt         tamper-resistant time source
  Repo.kt          persistent state + strict-mode guards (refuses loosening, not just hides buttons)
  Engine.kt        Repo -> Logic bridge
  GuardService.kt  accessibility service: foreground tracking, enforcement, site + settings guard
  BlockActivity.kt block screen and mindful gate
  MainActivity.kt, TodayScreen.kt, AppsScreen.kt, FocusScreen.kt, RulesScreen.kt   Compose UI
  Perms.kt, GuardAdminReceiver.kt, Components.kt, Theme.kt, Util.kt
app/src/test/.../LogicTest.kt   14 tests for the rules engine
```

## Privacy

Everything stays on the phone. The accessibility service reads only the foreground app's package name,
plus the address bar text of supported browsers (to match blocked domains), and the screen text of
Settings pages (to detect FocusGuard's own name in strict mode). There is no network permission.

## Known limits / ideas to extend

- Safety: Settings, System UI, your home launcher and the dialer can never be added to the block list
  (and are never blocked), so FocusGuard can't lock you out of your phone.
- Needs a real-device check on Android 14/15: the block screen is launched from the background and
  relies on the "Display over other apps" permission. If it ever fails to appear you are still sent Home.
- Pop-up windows from other apps (chat heads, assistants) can briefly pause usage counting.
- Address-bar view IDs differ between browser versions; a browser that changes its ID simply isn't covered
  until you update the map in `GuardService.kt` (`URL_BARS`).
- Blocking just *Shorts/Reels* inside YouTube/Instagram needs per-app UI detection and breaks with app updates, so it is not included.
- Natural next steps: Pomodoro auto-cycles, per-app "always allowed" contacts, weekly report notification, app-usage import via UsageStatsManager.
