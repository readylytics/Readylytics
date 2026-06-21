---
layout: default
title: Privacy Policy
permalink: /privacy/
---

# Privacy Policy

<p class="policy-meta">Last updated: 2026-06-20</p>

Readylytics is an offline-first wellness app for Android. This policy explains
what data Readylytics processes and how that data is handled.

## Who operates Readylytics

Readylytics operates the app and this website. For privacy questions, contact
`readylytics@gmail.com`.

## Data Readylytics reads

Readylytics reads health and fitness data from Android Health Connect after you
grant permission. Depending on enabled permissions and available data sources,
this can include:

- Sleep sessions and sleep stages
- Heart rate and resting heart rate
- Heart rate variability
- Exercise sessions
- Steps
- Optional body metrics, such as weight, body fat, blood pressure, or oxygen
  saturation, if supported and permitted

Readylytics reads this data to calculate wellness trends, sleep insights,
readiness, and training-load context.

## Local storage

Readylytics stores app data locally on your device using its private app
storage. Health data, preferences, and computed summaries are not uploaded by
Readylytics to a cloud service.

Readylytics may create encrypted local backup files when you use the backup
feature. These backup files are controlled by you and remain local to your
device or the storage location you choose.

The production app does not request the Android `INTERNET` permission. It does
not include analytics, advertising, telemetry uploads, or any Readylytics-run
network service for your health data.

## Data sharing

Readylytics does not sell health data, does not use advertising trackers, and
does not upload Health Connect data to third-party sign-in, cloud backup, or
Readylytics-hosted services.

Diagnostic logging stays on-device and is only enabled in debug builds.
Production error handling uses sanitized messages rather than exposing raw
exception text from health data, storage, or cryptographic operations.

## Your controls

You can revoke Health Connect permissions in Android Health Connect settings.
You can delete local app data through Android system settings. You can also
delete local backup files from their storage location.

Readylytics limits locally stored history using a retention setting. By
default, retention is enabled at 365 days, and you can adjust it in Settings
to any value between 180 days and 3 years (1095 days). State retention
applies to every imported health-record table. If you turn retention
limiting off, Readylytics keeps history up to a 10-year (3650-day) ceiling
rather than indefinitely. Backups contain only records present in the local
database at backup time.

To protect your privacy and prevent decryption or data corruption issues on new devices (since cryptographic keys are hardware-bound and do not transfer), all local app data—including databases, preferences, encryption keys, and local backup files—is explicitly excluded from standard Android Auto Backup (cloud backup) and device-to-device transfers.

## Age requirement

Readylytics is intended for users aged 18 and older and is not directed at
children. Readylytics does not knowingly collect data from children.

## Medical disclaimer

Readylytics is a wellness app, not a medical device. It does not diagnose,
treat, prevent, or cure any disease or medical condition. Wearable metrics can
be incomplete or inaccurate, and any health concern should be discussed with a
qualified clinician.

## Changes

This policy may be updated as Readylytics changes. Updates will be published on
this page.
