# Padding Alignment Design

Date: 2026-07-03
Topic: workout details and vitals page spacing alignment

## Goal

Align page-level padding rhythm so:

- workout details match spacing used on dashboard and other main surfaces
- vitals page uses consistent vertical spacing between stacked trend charts
- shared spacing values come from designsystem theme API instead of scattered hardcoded `dp`

## Current Context

Relevant current behavior:

- `feature/workouts/.../WorkoutDetailScreen.kt` uses outer `padding(16.dp)` and `verticalArrangement = Arrangement.spacedBy(24.dp)`
- `feature/vitals/.../overview/VitalsScreen.kt` mixes repeated hardcoded `16.dp`, `8.dp`, `16.dp`, and `24.dp`
- `feature/dashboard/.../DashboardScreen.kt` establishes typical page rhythm with `16.dp` horizontal padding and `16.dp` vertical section spacing
- `feature/sleep/.../SleepScreen.kt` already consumes `MaterialTheme.spacing`
- `core/designsystem/.../Spacing.kt` already defines theme-backed spacing tokens:
  - `extraSmall = 4.dp`
  - `small = 8.dp`
  - `medium = 16.dp`
  - `large = 24.dp`
  - `extraLarge = 32.dp`
  - `doubleExtraLarge = 48.dp`

## Design Decision

Use existing theme-backed `MaterialTheme.spacing` as single spacing source of truth.

Do not add separate constants file.
Do not add second spacing system in app module.
Do not introduce another composition local.

Instead:

- migrate touched screens from raw `dp` values to `MaterialTheme.spacing`
- keep numeric tier tokens already owned by `core:designsystem`
- optionally add a very small number of semantic helper properties in `core:designsystem` only if repeated intent becomes unclear during implementation

Recommended default mapping for this task:

- `8.dp` -> `MaterialTheme.spacing.small`
- `16.dp` -> `MaterialTheme.spacing.medium`
- `24.dp` -> `MaterialTheme.spacing.large`

## Options Considered

### 1. Reuse existing `MaterialTheme.spacing` directly

Pros:

- lowest churn
- already used by sleep and about surfaces
- no duplicate API
- fixes immediate inconsistency and supports future reuse on sleep/vitals/workouts/dashboard

Cons:

- token names are tier-based, not intent-based

Decision: chosen baseline.

### 2. Add standalone global padding constants

Pros:

- simple mechanical migration

Cons:

- duplicates existing theme system
- weak ownership
- splits design tokens between constants and theme

Decision: reject.

### 3. Create fully semantic spacing API immediately

Examples:

- `screenHorizontal`
- `sectionGap`
- `sectionGapLarge`

Pros:

- clearest design language

Cons:

- more API design work
- risk of wrapping existing token set without enough real reuse yet

Decision: defer unless direct implementation shows tier tokens are not expressive enough.

## Proposed Changes

### `core:designsystem`

Keep `Spacing.kt` as source of truth.

Possible narrow enhancement:

- if repeated page-level usage feels noisy, add read-only semantic aliases that map to existing tier tokens rather than new values
- example: `pageHorizontal = medium`, `pageSectionGap = medium`, `pageSectionGapLarge = large`

This is optional, not default. Implementation should prefer smallest API change that keeps call sites readable.

### `feature:workouts`

Update `WorkoutDetailScreen` to consume theme spacing:

- replace outer `padding(16.dp)` with theme spacing token
- replace `Arrangement.spacedBy(24.dp)` with token that matches main page rhythm

Expected outcome:

- workout detail items align more closely with dashboard spacing cadence
- no change to chart/card internal padding

Default target:

- outer page inset -> `MaterialTheme.spacing.medium`
- inter-section gap -> `MaterialTheme.spacing.medium`

Reason:

Dashboard main rhythm is centered on `16.dp`; current `24.dp` gap in workout detail looks oversized versus neighboring surfaces.

### `feature:vitals`

Update `VitalsScreen` to consume theme spacing:

- replace repeated `horizontal = 16.dp` with theme spacing token
- replace repeated `top/bottom = 16.dp` where they represent normal page rhythm
- replace `Spacer(8.dp)` and `Spacer(16.dp)` around chart stack with theme spacing tokens
- make RHR -> SpO2 gap equal to HRV -> RHR gap

Expected outcome:

- chart stack uses one consistent vertical rhythm
- oxygen chart no longer appears detached from section above it

Default target:

- horizontal inset -> `MaterialTheme.spacing.medium`
- standard chart gap -> `MaterialTheme.spacing.small`
- bottom legend separation can remain larger if current visual balance still needs it

### `feature:sleep`

No required behavior change in this task.

Sleep screen serves as pattern reference because it already uses `MaterialTheme.spacing`. During implementation, verify whether touched vitals/workouts call sites should follow same style for consistency.

### `feature:dashboard`

No broad migration in this task.

Dashboard remains visual reference. Optional minimal cleanup allowed only if needed to keep touched files consistent or to prove shared token usage pattern. Avoid wide token sweep beyond affected surfaces.

## Scope Boundaries

In scope:

- page-level padding on workout detail
- page-level horizontal padding on vitals
- vertical spacing between vitals stacked trend cards
- use of shared theme spacing tokens in touched files

Out of scope:

- redesigning cards
- changing chart internals
- changing dashboard card grid spacing
- full repo-wide replacement of every raw `dp`
- screenshot/golden test framework creation

## Error Handling / Risk

No domain logic risk.
Primary risk is visual regression:

- spacing may become too tight on workout detail if all `24.dp` gaps collapse without checking section hierarchy
- vitals legend spacing could feel cramped if reduced unintentionally

Mitigation:

- limit changes to page-level rhythm only
- verify on workout details, vitals, dashboard, and sleep surfaces
- keep larger gap only where it signals section break, not accidental inconsistency

## Verification

Required verification after implementation:

1. compile affected modules or app target
2. run unit tests required by repo workflow
3. run lint at end per repo instructions
4. manual UI verification on:
   - dashboard
   - workout details
   - vitals
   - sleep

Manual checks:

- workout detail section gaps visually match dashboard rhythm
- vitals HRV -> RHR gap equals RHR -> SpO2 gap
- no clipped content after padding changes
- date switcher and bottom legend still feel balanced

## Implementation Notes

Implementation should prefer:

- `MaterialTheme.spacing.*` imports over raw `dp`
- smallest viable edits in touched files
- no new design token values unless existing set cannot express needed rhythm

If semantic aliases are added, place them in `core/designsystem` and map them to current tier tokens. Do not introduce magic numbers in alias definitions beyond existing `Spacing` values.
