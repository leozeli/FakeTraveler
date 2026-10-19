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
