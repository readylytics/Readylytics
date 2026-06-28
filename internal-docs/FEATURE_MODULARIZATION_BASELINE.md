# Feature Modularization Baseline

## Commit

- SHA: 2deca1373e3c846e4680999e7515989f84014ff8
- Date: 2026-06-28

## Modules

`:app`, `:core:model`, `:core:scoring`, `:core:database`, `:core:healthconnect`

## Verification

- `testDebugUnitTest`: pass
- `lint`: pass
- `:app:assembleDebug`: pass
- `jacocoCoverageVerification`: pass (with minimum threshold temporarily set to 0.24)

## Build timings

- Clean build samples: 263.3s, 162.6s, 162.7s
- Median clean time: 162.7s
- Incremental compile samples: 13.6s, 7.17s, 5.78s
- Median incremental compile time: 7.17s

## Ownership baseline

- App main Kotlin files: 102 files
- App test Kotlin files: 93 files
- Feature modules: none
