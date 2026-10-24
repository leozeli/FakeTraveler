# FakeTraveler FGS background-inject patch (2.3.2-fgs / versionCode 233)

## Root cause
- `MockedLocationService` was bind-only (`BIND_AUTO_CREATE`), no `startForeground`.
- Injection used `java.util.Timer` / `TimerTask` — OEM (Honor) freezes Timer when another app is foreground.
- Manifest had no `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION`.
- `onUnbind` cancelled tasks and removed test providers.

## Fix
1. `AndroidManifest.xml`: FGS location type + POST_NOTIFICATIONS + WAKE_LOCK.
2. `MockedLocationService.java`: foreground notification (Stop action), `HandlerThread` inject loop, partial wake lock, keep running after unbind.
3. `MainActivity.java`: `startForegroundService` + bind; stop via `binder.stopMock()`; request runtime location/notification perms.

## Build
```
cd FakeTraveler && ./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
Built on host with JDK 17 (toolchain set to 17). Installed on Redroid as `2.3.2-fgs`.

## Verify (Redroid)
- `isForeground=true foregroundId=42`, notification channel `mock_location`.
- With Meituan resumed, gps `et=` advanced ~10s per sample (default mockFrequency).

## 2.3.2-fgs2 (versionCode 234) keepalive harden
- Persist `mockRunning` + sticky resume when `onStartCommand(null)` after kill
- `BootReceiver` for BOOT_COMPLETED / MY_PACKAGE_REPLACED
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + request on Apply
- Re-acquire PARTIAL_WAKE_LOCK each inject tick

## 2.3.3 (versionCode 237) interval 0 + loop that stays up

The 2.3.2 foreground service still stopped injecting, and interval 0 never reached the loop.

- `MainActivity.loadSharedPrefs` rewrote `mockFrequency <= 0` to 1 (added in `e4399e6` to avoid dividing by zero). `saveSettings()` then stored that 1, so the field could show 0 while the service waited 1000 ms. `BootReceiver`, sticky resume, and `MockedLocationService` (`Math.max(200, mockMilli)`) did the same. 0 now means continuous: `MockInterval.toDelayMillis(0)` is 100 ms, the settings label says so, and `MoreActivity` pushes the new delay into a live loop without a process restart.
- `pushOnce()` had no try/catch. `setTestProviderLocation` throws once the system drops the test provider (common when another app is in front). That exception skipped `postDelayed`, so the notification stayed up and injection never ran again. Each provider now reinstalls itself, a fused failure cannot cancel GPS, and the runnable always schedules the next tick.
- The partial wake lock was acquired for 1 h only when `!isHeld()`, so it was not refreshed. It is renewed before it expires.
- After the process died, opening the activity only flipped the button to Stop. `onResume` now starts the foreground service when `mockRunning` is set. A watchdog exact alarm restarts a stalled loop and a killed process.
