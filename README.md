# HydroLock — Android Water Intake Enforcer

A hydration app that doesn't just track, it enforces. Camera-verified drink sessions,
phone lockdown until you hit your targets, and a punishing streak loss experience.

---

## Setup

### Requirements
- Android Studio Hedgehog or later
- Android SDK 34
- Minimum Android 8.0 (API 26) device for testing
- Physical Android device recommended (camera features don't work on emulator)

### Opening the project
1. Open Android Studio
2. File → Open → select the `HydroLock` folder
3. Wait for Gradle sync to complete
4. Run on a physical Android device

### Font setup (required before build)
The app uses the Inter font family. Add these to `app/src/main/res/font/`:
- `inter_regular.ttf`
- `inter_medium.ttf`
- `inter_semibold.ttf`
- `inter_bold.ttf`

Download from: https://fonts.google.com/specimen/Inter

Alternatively, update all `android:fontFamily` attributes in layouts to use `sans-serif`.

### MPAndroidChart repository (required)
Add to your project-level `build.gradle`:
```
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }  // ← Add this
    }
}
```

---

## Architecture

```
com.hydrolock/
├── data/
│   └── database/
│       ├── AppDatabase.kt          Room database
│       ├── dao/Daos.kt             All DAOs
│       └── entities/Entities.kt    Room entities
├── di/
│   └── DatabaseModule.kt           Hilt DI
├── domain/
│   └── usecases/
│       ├── CalculateScheduleUseCase.kt   Schedule math
│       ├── ActiveWindowDetector.kt        Wake/sleep detection
│       └── StreakManager.kt               Streak logic
├── ml/
│   └── LiquidLevelEstimator.kt     Camera ML verification
├── services/
│   ├── HydroAccessibilityService.kt  App refocus enforcement
│   ├── OverlayLockService.kt         Foreground overlay
│   ├── BootReceiver.kt               Boot persistence
│   └── PhoneActivityMonitorService.kt (stub)
├── ui/
│   ├── onboarding/                   5-screen onboarding flow
│   ├── home/                         Main dashboard
│   ├── drink/                        Camera verification session
│   └── streak/                       Streak loss screen
└── workers/
    └── Workers.kt                    WorkManager jobs
```

---

## Key Features

### Lock Mechanic
When a drink window opens:
1. Notification fires (10 min grace period)
2. If ignored → `OverlayLockService` draws a full-screen overlay
3. `HydroAccessibilityService` watches for app switches and refocuses
4. Phone stays locked until drink is verified

### Camera Verification (LiquidLevelEstimator)
- **Before scan**: ML Kit detects container, estimates fill level via pixel saturation analysis
- **User drinks on camera**
- **After scan**: Re-estimates fill level, calculates delta
- **Confidence scoring**: Low light or unusual containers trigger manual confirm fallback
- **Trust debt**: Too many manual confirms = double-verify requirement

### Schedule Engine
- Active window detected from UsageStats (past 7 days of screen-on events)
- Daily target divided evenly across the active window
- Missed windows roll into next window (stacking pressure)
- End-of-day: remaining split into ≤250ml sessions with 5min gaps

### Streak Loss Screen
- Large streak number shown first
- Shake animation → number shatters after 1s
- Cold copy reveals: "7 days. Gone."
- Shows nearest milestone that slipped away
- New streak = 0 displayed
- Acknowledge button locked for 5 seconds (can't skip it)

---

## Permissions Required

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw overlay over other apps |
| `PACKAGE_USAGE_STATS` | Detect wake/sleep patterns |
| `BIND_ACCESSIBILITY_SERVICE` | Refocus to drink screen |
| `CAMERA` | Drink verification |
| `POST_NOTIFICATIONS` | Drink reminders |
| `FOREGROUND_SERVICE` | Keep services alive |
| `RECEIVE_BOOT_COMPLETED` | Restart schedule after reboot |

---

## Notes

### iOS
The lock mechanic described here is Android-specific. iOS does not allow
system overlay windows or accessibility-based app refocus by third-party apps.
An iOS version would use Screen Time API suggestions and aggressive notification
strategies as the closest equivalent.

### ML Model
The current `LiquidLevelEstimator` uses heuristic pixel analysis (saturation delta scanning)
combined with ML Kit object detection. For production, this should be replaced or supplemented
with a custom TFLite model trained specifically on liquid containers. The heuristic approach
works reasonably well in good lighting with clear containers.

### Battery
WorkManager's `PeriodicWorkRequest` and `OneTimeWorkRequest` are battery-efficient,
but the `OverlayLockService` foreground service does consume more battery while active.
The `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission is requested so the app isn't
killed by aggressive battery savers.
