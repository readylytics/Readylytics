---
layout: default
title: Backup & Data Management
permalink: /backup-and-data/
---

# Your data, your control

Readylytics stores everything locally on your device—no cloud account, no data leaving your phone. But that also means *you're* responsible for backing up your data. The app makes this easy with encrypted, password-protected backups, flexible retention settings, and full control over what syncs from Health Connect.

---

## Local encrypted backup

All your settings, preferences, and app configuration can be backed up to a local folder on your device, encrypted with AES-256 (bank-grade security).

**Find it:** Settings → Data & Backup → Local Backup

**What gets backed up:**
- All user settings (sleep goal, zones, thresholds, baselines)
- Readiness configuration (TRIMP model, RAS scaling, load sources)
- UI preferences (theme, colors, unit system)
- Backup scheduling and metadata

**Note:** The app backs up *settings only*, not your Health Connect data. Your raw health data stays synced to Health Connect itself, which you control separately.

### Create a backup

1. **Set a backup directory** — Choose a folder where the app can store backups (e.g., Documents/Readylytics Backups).
2. **Set a password** (optional but recommended) — Encrypts the backup file. Without a password, the file is stored unencrypted.
3. **Tap "Create Backup Now"** — The app creates a timestamped backup file.

### Automate backups

Don't want to remember to back up manually?

**Find it:** Settings → Data & Backup → Backup Schedule

- **Manual** (default) — You tap "Create Backup Now" when you want.
- **Daily** — A new backup is created every 24 hours.
- **Weekly** — A new backup every 7 days.

The app will create backups in the background. Each backup is timestamped, so you can keep multiple versions.

### Restore from backup

Lost your settings after reinstalling the app? Restore them in seconds.

1. **Go to:** Settings → Data & Backup → Available Backups
2. **Select the backup** you want to restore.
3. **Enter the backup password** (if you set one).
4. **Tap "Restore"** — Settings are restored; the app may restart to apply changes.

### Changing your backup password

If you want to change the backup password, the app will automatically re-encrypt all existing backups with the new password the next time it backs up. No manual steps needed.

---

## Data retention

The app can store unlimited historical health data, but if you're concerned about device storage, you can set an automatic data retention window.

**Find it:** Settings → Data & Backup → Data Retention

**Retention window** (180–1095 days, default 365):
- Dates older than this window are automatically deleted from the app's local database. State retention applies to every imported health-record table.
- Does **not** affect Health Connect—your original data stays in Health Connect unless you delete it there.
- Backups contain only records present in the local database at backup time (archived backups created before pruning may contain older data).

**Retention disabled:**
- Turn off "Enable Data Retention" to keep all historical data indefinitely.

**Why use retention:**
- Save device storage space.
- Protect older, less relevant data from accidental view.
- Meet privacy preferences (e.g., "only keep recent data").

**Why keep it off:**
- Historical baselines (HRV, RHR) improve with more data.
- Long-term trends become more meaningful with years of history.
- Readiness comparisons across seasons require historical context.

If you enable retention after years of data, the oldest entries will be pruned. This is irreversible, so consider a backup first.

---

## Health Connect sync

Readylytics pulls sleep, heart rate, HRV, and workout data from Health Connect (Android's native health data hub). You control *when* and *how often* this sync happens.

**Find it:** Settings → Data & Backup → Health Connect Sync

### Sync on app open

Controls whether the app syncs with Health Connect automatically when you launch it.

- **Always** — Every time you open the app, it pulls the latest data. (Best for staying current; uses more battery.)
- **Never** — No automatic sync; you manually trigger syncs only. (Best for limiting Health Connect requests.)
- **By time** (default) — Syncs only once per hour, even if you open the app multiple times. (Balanced approach.)

### Background sync

For continuous health monitoring, enable background syncing.

**Find it:** Settings → Data & Backup → Background Sync

- **Enabled** — The app periodically syncs in the background, even when closed.
- **Sync interval** — How often (15 minutes, 1 hour, 4 hours, 12 hours, or daily).
- **Note:** Background sync uses more battery. Daily or 12-hour intervals are usually sufficient.

### Manually resync Health Connect data

Missed data? Reconnected a device? Force a full resync.

**Find it:** Settings → Data & Backup → Resync Health Connect Button

Tap to trigger a fresh sync from Health Connect. This pulls the latest data across all connected devices.

---

## Device filtering

If you own multiple health trackers or watches (e.g., Apple Watch, Oura Ring, Garmin), they might all sync to Health Connect. You can filter which device feeds each data type.

**Find it:** Settings → Data Sources → Device Selection

**Data categories:**
- **Activity** — Exercise sessions, step counts
- **Body Measurements** — Weight, body fat percentage
- **Sleep** — Sleep sessions, stage data
- **Vitals** — Heart rate, HRV, blood pressure, oxygen saturation

For each category, choose:
- All devices (default)
- A specific device

**Example use case:** Your smartwatch's HRV is noisy, but your fitness tracker's is clean. Filter sleep vitals to use the fitness tracker only.

---

## Full historical resync

Every Health Connect has a data history. By default, Readylytics pulls recent data. But if you change how data is filtered, enable a data type you previously disabled, or suspect data loss, you can trigger a full historical resync.

**Find it:** Settings → Data & Backup → Resync All Historical Data

**What it does:**
- Pulls all available health data from Health Connect, back to your retention window limit (or 10 years if retention is off).
- Re-processes all workouts, sleep sessions, and vital signs.
- Recalculates all scores (Sleep, Readiness, Load) from the beginning.

**Why use it:**
- You enabled a data type (e.g., Heart Rate Variability) that you previously had disabled.
- You changed device filtering and want old data from the newly-selected device.
- You suspect synced data was corrupted or incomplete.
- You want to ensure consistency after a major app update.

**When it runs:**
- Runs in the foreground (you see a progress banner).
- Can take a few minutes to an hour, depending on data volume.
- Survives backgrounding — the app will complete even if you switch away.
- Survives interruptions (e.g., network dropout) — retries with exponential backoff.

**Impact:**
- All previously calculated scores are recomputed. Results should be nearly identical (may differ by <1 point due to rounding).
- Baselines (HRV, RHR) may shift slightly as they're recalculated from the full history.

---

## Health Connect permissions

Readylytics asks for permission to read specific data types. You grant or revoke permissions in Android's Health Connect app, not in Readylytics.

**Requested permissions:**
- **Sleep** — Sleep sessions, stage data (deep/REM/light)
- **Heart Rate** — During sleep and workouts
- **Heart Rate Variability (RMSSD)** — Overnight HRV for recovery metrics
- **Exercise** — Logged workouts

**Permission revocation:**
If you revoke access:
1. Open **Health Connect** (Android system app)
2. Tap **Apps** → **Readylytics** → **Manage permissions**
3. Toggle off any data type

Readylytics will detect missing permissions and show a recovery flow directing you back to Health Connect.

---

## What about my raw data?

**Health Connect is your single source of truth.** Readylytics only *reads* from it; it never writes. Your original data is always safe in Health Connect, even if something goes wrong in Readylytics.

**To see or export your raw data:**
1. Open the **Health Connect** app.
2. Browse each data category (Sleep, Heart Rate, Workouts, etc.).
3. Tap any entry to see raw values.
4. Use Health Connect's export features to download CSV or other formats.

Readylytics adds interpretive layers (scores, baselines, trends), but the raw numbers come straight from your devices via Health Connect.

---

## Privacy at a glance

- **No account required** — No sign-up, no email verification.
- **No cloud processing** — All calculations happen on your device.
- **No data sharing** — Your health data never leaves your phone (except to Health Connect, which is controlled by you).
- **Open source** — Every calculation and security practice is auditable on GitHub.
- **You own the backups** — Encrypted locally, you control the encryption key.

For full details, see our [Privacy Policy](/privacy/).

---

<a href="{{ '/' | relative_url }}" class="back-link">← Back to home</a>
