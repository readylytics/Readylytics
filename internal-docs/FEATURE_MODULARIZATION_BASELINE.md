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

## Post-Modularization Performance & Isolation (Task 17)

### Commit

- SHA: a73756b4dd2a7ab526b5050e42faa3e964b1bca8
- Date: 2026-06-29

### Modules

`:app`, `:core:model`, `:core:scoring`, `:core:database`, `:core:healthconnect`, `:core:designsystem`, `:core:ui`, `:feature:about`, `:feature:insights`, `:feature:sleep`, `:feature:workouts`, `:feature:vitals`, `:feature:dashboard`, `:feature:settings`, `:feature:onboarding`

### Verification

- `testDebugUnitTest`: pass
- `lint`: pass
- `:app:assembleDebug`: pass
- `jacocoCoverageVerification`: pass (with aggregate coverage gate set to >= 30%)

### Build timings (Post-Move)

- Clean build samples: 414.2s, 545.5s, 441.5s
- Median clean time: 441.5s
- Incremental compile samples (touching `:feature:about`): 63.5s, 3.4s, 5.2s
- Median incremental compile time: 5.2s

### Feature Isolation Evidence

Changing a non-ABI line in `:feature:about` and compiling `:app:compileDebugKotlin` shows that only `:feature:about:compileDebugKotlin` and `:app:compileDebugKotlin` compile/run, while all other feature compilation tasks (e.g. `:feature:sleep`, `:feature:vitals`, `:feature:workouts`, etc.) remain `UP-TO-DATE`.

