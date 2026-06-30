# Performance Testing

Macrobenchmark cold/warm/hot startup tests live in the `:benchmark` Gradle module and run via
`./gradlew :benchmark:connectedBenchmarkAndroidTest`. They require a physical or managed device
and are intentionally excluded from the standard CI gate.

Battery drain measurement is a manual procedure — a 5-minute charge-counter sample extrapolated
to 24 hours is not a reliable signal. Use the procedure below instead.

---

## Battery Drain — Manual Procedure

### Requirements

- Physical device (not emulator; emulator has no real power rail)
- Battery Historian CLI or Android Studio Battery Profiler
- At least 24 hours of uninterrupted measurement window
- Device fully charged to 100% before starting

### Device pre-conditions

Before starting a measurement run, apply all of these:

1. **Screen** — set display timeout to 30 s; keep screen off for the full measurement window.
2. **Sync** — disable all automatic sync (Settings → Accounts → Auto-sync off).
3. **Wi-Fi** — keep connected but ensure no large background downloads are active.
4. **Charging** — disconnect charger for the entire measurement window.
5. **WorkManager jobs** — complete or defer any pending background work before the run starts.
6. **Other apps** — minimize background activity; use a dedicated test device if possible.

### Capture with Battery Historian

```bash
# Reset battery stats before the test window
adb shell dumpsys batterystats --reset

# After 24 hours, pull the stats
adb bugreport bugreport.zip

# Open Battery Historian (requires Docker or local install)
# https://github.com/google/battery-historian
historian bugreport.zip
```

### Capture with Android Studio Battery Profiler

1. Open Android Studio → Profiler → Battery.
2. Let the app run in idle for at least 24 hours.
3. Export the profiler session for archiving.

### Pass criteria

Idle drain **< 2% per 24 hours** measured from the charge-counter delta over the full
24-hour window (not extrapolated from a short sample).

### When to run

Run this procedure:
- Before a production release.
- After any change to WorkManager job schedules, background sync intervals, or wake-lock usage.
- After adding new foreground services.
