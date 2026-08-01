# Gauge Restyle — Horseshoe Arc Matching the Bar Style

**Status:** PLAN — awaiting approval, decided independently of the M3 audit. No implementation code has been written.
**Date:** 2026-08-01
**Branch:** `claude/readylytics-m3-audit-plan-i6mx57`
**Companion plans:** [`M3_COMPLIANCE_AUDIT.md`](./M3_COMPLIANCE_AUDIT.md) (findings T-2, L-9, L-10, C-4 overlap this work), [`M3_TOP_APP_BAR_PLAN.md`](./M3_TOP_APP_BAR_PLAN.md)
**Reference:** <https://m3.material.io/components/progress-indicators/guidelines>

---

## 1. Context

The dashboard and detail-screen gauges draw a plain 180° semicircle (`core/ui/.../components/M3MetricGauge.kt`), while the same metrics rendered in **Bar** mode use an M3 `LinearProgressIndicator` with rounded caps (`DashboardBarRenderer`). Two visualizations of the same value, in the same card, switched by the same menu — and they do not read as one system.

A reference design was supplied: a "horseshoe" gauge — a wide arc open at the bottom, thick rounded track carrying faint tick dots, a thick rounded active segment, and a small marker dot at its leading edge. The requirement is to adopt that gauge *style*, explicitly **"so it matches our bar style"**.

Content placement is unchanged in structure, only corrected:

- **Centre of the arc** (where the reference puts its icon) → the **value + unit**.
- **Below the arc** (where the reference puts its label text) → the **trend/delta pill**.

Outcome: one gauge style, derived from the same thickness, colour and cap tokens as the bar, applied everywhere.

---

## 2. Decisions

| Decision | Choice | Why |
|---|---|---|
| Arc sweep | **~240°**, open at the bottom | Keeps `cardHeight` at 156dp and keeps `displaySmall` for the value. A true 270° arc is nearly square; at the ~76dp the card can spare, its inner diameter is ~52dp, which cannot hold a 36sp value plus a unit. |
| Tick dots | **Included**, spaced *by fraction of sweep* | Scale-independent, so the same code serves `maxScore = 100f` (scores) and `maxScore = 1f` (RHR/HRV fill). Value-anchored ticks would need per-metric threshold plumbing. |
| Scope | **App-wide** | `M3MetricGauge` is the single change point; all 13 `M3ScoreGaugeCard` call sites plus `DashboardGaugeRenderer` inherit it. Consistency is the stated goal. |

---

## 3. Geometry

Compose arc angles: 0° = 3 o'clock, increasing clockwise.

- `startAngle = 150f`, `sweepAngle = 240f` — starts lower-left, sweeps up over the top, ends lower-right, leaving a 120° gap centred on the bottom.
- Bounding box: width `2r`, height `1.5r` — the topmost point is the circle top, the lowest drawn points sit at `sin 30° = 0.5r`. **Aspect ratio 4:3.**

**The Canvas must become adaptive.** It is currently hardcoded to `120.dp × 60.dp` (`M3MetricGauge.kt:65-66`). A 240° arc is proportionally taller than a semicircle, and `DashboardVisualizationRegressionTest` asserts the gauge stays within the card when the title wraps to two lines. Derive the radius from the *measured* size so the gauge shrinks under title and font-scale pressure instead of overflowing:

```
r = min(availableWidth / 2, availableHeight / 1.5) - strokeWidth / 2
```

This is a strict improvement on the current fixed Canvas, which cannot respond to either pressure.

---

## 4. Style — the concrete link to the bar

`DashboardBarRenderer` already defines the target: `LinearProgressIndicator`, `StrokeCap.Round`, `DASHBOARD_TRACK_HEIGHT = 10.dp`, `color = status.gaugeColor()`, `trackColor = metricVisualizationTrackColor()`.

1. **Promote the thickness to a shared token.** `DASHBOARD_TRACK_HEIGHT` is a `private val` in `DashboardMetricRenderers.kt:97`. Move it to `Dimens.kt` as `metricTrackThickness = 10.dp` and have *both* the bar and the gauge read it. This is the mechanical guarantee that the two stay matched — without it, "matches our bar style" is a comment that rots.
2. **Track:** `metricVisualizationTrackColor()` (already shared by both renderers), `StrokeCap.Round`, thickness = the shared token.
3. **Active segment:** `status.gaugeColor()` (unchanged), `StrokeCap.Round`, drawn `+2dp` thicker to reproduce the reference's bulge. Expose the delta as a single named constant so it can be flattened to uniform thickness in one edit if the bulge does not survive on-device review.
4. **Gap** between the active segment's leading end and where the track resumes — present in the reference and consistent with M3 Expressive progress indicators.
5. **Marker dot** inset at the leading edge of the active segment, ~6dp. Its colour must be **the container the gauge is drawn on**, so it reads as punched out of the segment. Add a `markerColor: Color` parameter: `M3ScoreGaugeCard` passes `surfaceContainerHighest`; `DashboardGaugeRenderer` passes the card's status container colour. A fixed `surface` would be wrong — dashboard cards are status-tinted (`primaryContainer`, `errorContainer`, `warningContainer`).
6. **Tick dots:** 4 dots at 0.2 / 0.4 / 0.6 / 0.8 of the sweep, drawn on the track in `onSurfaceVariant` at low alpha, suppressed where the active segment covers them.

---

## 5. Centre and footer content

- **Centre:** value (`displaySmall`) + unit, centred on the arc's circle centre. This replaces the current offset hack — `Modifier.offset(y = extraSmallMedium)` on the column and `Modifier.offset(y = (-8).dp)` on the unit label (`M3MetricGauge.kt:130, 149`) — with real centring against known geometry.
- **Footer:** the trend pill already exists in both consumers (`M3ScoreGaugeCard.kt:198-223` and `DashboardMetricRenderers.kt:81-91`). **No structural change** — only confirm it still centres under the wider arc.

---

## 6. Overlapping audit findings — fold in, don't churn twice

This rewrite touches the exact lines flagged in `M3_COMPLIANCE_AUDIT.md`. Fixing them here avoids editing the same code in two separate passes:

| Finding | Site | Fix |
|---|---|---|
| T-2 | `M3MetricGauge.kt:144`, `M3ScoreGaugeCard.kt:213` | `labelMedium.copy(fontSize = 11.sp)` → `labelSmall` |
| L-9 | `M3MetricGauge.kt:149`; `M3ScoreGaugeCard.kt:181, 202` | `(-8).dp` offset removed by real centring; `10.dp` / `20.dp` → tokens |
| L-10 | `M3MetricGauge.kt:129-150` | Fully-qualified `androidx.compose.*` call sites → imports |
| C-4 | `M3ScoreGaugeCard.kt:190` | `onSurfaceVariant.copy(alpha = 0.8f)` → `onSurfaceVariant` |

**Deliberately deferred:** `metricVisualizationTrackColor()` (`M3MetricGauge.kt:30` — `onSurfaceVariant.copy(alpha = 0.38f)`, audit finding C-4). It is shared with the bar, and dashboard cards use status-tinted containers, so the correct replacement is a container-aware role rather than the fixed `surfaceContainerHighest` the audit proposes. Leave it to audit Phase 1 so the colour refactor and this visual work do not collide.

---

## 7. Files

| File | Change |
|---|---|
| `core/ui/.../components/M3MetricGauge.kt` | Main rewrite: 240° arc, adaptive sizing, tick dots, marker dot, real centring, `markerColor` parameter |
| `core/designsystem/.../Dimens.kt` | Add `metricTrackThickness`, gauge marker and tick tokens |
| `feature/dashboard/.../DashboardMetricRenderers.kt` | Read the shared thickness token instead of the private `DASHBOARD_TRACK_HEIGHT`; pass `markerColor` |
| `core/ui/.../components/M3ScoreGaugeCard.kt` | Pass `markerColor`; replace raw dp with tokens |
| `feature/dashboard/.../DashboardMetricCardPreviews.kt` | No API change — visual verification only |

`M3ScoreGaugeCard`'s public signature is unchanged, so the 13 consumers in Sleep, Vitals and Workouts need no edits.

---

## 8. Verification

**Tests — the safety net; all must stay green:**

- `M3MetricGaugeTest.metricGauge_acceptsNullMarker_andClampsOutsideRange_withSingleTrackContract` — asserts the gauge Canvas adds **no semantic children**. Tick dots and the marker dot must be drawn inside the same `Canvas`, never added as composables.
- `M3MetricGaugeTest.m3ScoreGaugeCard_regression_semanticsAndRendering` — value/title text and `contentDescription` unchanged.
- `DashboardVisualizationRegressionTest` — **the critical one.** `assertVisualizationIsInsideCard(DASHBOARD_GAUGE_TAG)`, `gaugeMode_allowsTwoLineTitleAndKeepsInfoActionVisible`, `gaugeMode_keepHrvValueAndUnitReadableWithLongTitle`, `gaugeUnitAndDeltaPillFollowTheStatusContentColor_forNonNeutralStatus`. These directly exercise the taller-arc overflow risk that motivates the adaptive Canvas in §3.

**Commands** (per `.claude/CLAUDE.md`):

```
./gradlew ktlintFormat && ./gradlew testDebugUnitTest
./gradlew lintRelease          # after the change is complete
```

**Manual:**

- Dashboard in GAUGE mode; toggle GAUGE → BAR → VALUE and confirm gauge and bar now read as one system.
- All 13 `M3ScoreGaugeCard` sites: Sleep (2), Vitals overview (2), Weight (2), Blood pressure (2), Body fat, Steps, Workouts (2).
- Font scale 200% — confirm the arc shrinks and nothing clips.
- Light/dark × dynamic/fallback colour.
- Status-tinted cards (WARNING/POOR) — confirm the marker dot still reads as punched out against the tinted container.
- Edge cases: `markerFraction = null` (calibrating) and `1.0` (full sweep).

No new files, so no `codegraph index` is required; run `codegraph sync` only if `M3MetricGauge.kt` ends up split.

---

## 9. Risk

| Item | Risk | Note |
|---|---|---|
| Adaptive Canvas sizing | **Medium** | The reason the regression tests exist. A 240° arc is taller than the semicircle it replaces; getting the derivation wrong overflows the card on two-line titles. |
| Marker dot colour | Low–Medium | Needs the container colour threaded from two different call sites with different container roles. |
| Active-segment bulge | Low | Reference-matching but not part of the bar style; isolated behind one constant so it can be flattened after on-device review. |
| Tick dots | Low | Purely decorative and fraction-based; no data plumbing. |

**Total: ~1 day.**
