# Material Design 3 Compliance Audit — Readylytics

**Status:** PLAN — awaiting approval. No implementation code has been written.
**Audit date:** 2026-07-31
**Branch:** `claude/readylytics-m3-audit-plan-i6mx57`
**Scope:** all Compose UI in `core/designsystem`, `core/ui`, `feature/*`, `app/src/main/kotlin/**/ui`, and every `res/values/strings.xml`.
**Companion plans:**
- [`M3_TOP_APP_BAR_PLAN.md`](./M3_TOP_APP_BAR_PLAN.md) — finding L-4 (top app bars on primary destinations) is planned there, because it changes app structure rather than correcting a token or a defect.
- [`M3_GAUGE_RESTYLE_PLAN.md`](./M3_GAUGE_RESTYLE_PLAN.md) — a requested visual redesign of the metric gauge (horseshoe arc matching the bar style). It rewrites the same lines as findings **T-2, L-9, L-10 and C-4**, which are folded into that plan rather than fixed twice. If both are approved, sequence the gauge restyle before this document's Phase 2 and Phase 6 to avoid re-editing `M3MetricGauge.kt` and `M3ScoreGaugeCard.kt`.

**Agreed scope:** all seven phases (0–6) are in scope, including the Phase 3b editorial rewrites. Wording is corrected wherever M3 content design calls for it, not only where sentence case is mechanically wrong.

**Reference standards**

- Core M3 design system & foundations — <https://m3.material.io/>
- M3 UX writing best practices — <https://m3.material.io/foundations/content-design/style-guide/ux-writing-best-practices>
- Project conventions in `.claude/CLAUDE.md` ("Component Specifications", "Strings & i18n")

---

## 1. Summary

**Overall compliance: ~72% (Good foundation, systematic gaps).**

Readylytics is *structurally* a well-built M3 app. The theme layer is genuinely modern: dynamic color on API 31+ with a Material Color Utilities fallback scheme, a full `surfaceContainer*` role ladder, harmonised extended colors (success/warning) with a `staticCompositionLocalOf` bridge, `NavigationSuiteScaffold` for adaptive navigation, `PullToRefreshBox`, `ModalBottomSheet`, `ListItem`, `SegmentedButton`-class components, and Vico charts that already satisfy the house rules (cubic interpolation `Interpolator.cubic(0.2f)`, vertical area gradients, tonal palette mapping). There is **not a single `RoundedCornerShape(...)` literal in the entire codebase** — every shape goes through `MaterialTheme.shapes`. That is better than most production Compose apps.

The failures are concentrated and systematic rather than scattered:

| Area | Grade | Headline problem |
|---|---|---|
| Shapes & radii | **A** | Zero custom radii; `MaterialTheme.shapes.large` used consistently |
| Elevation | **A** | No M2 shadow/`elevation =` misuse anywhere |
| Color roles | **C** | Hardcoded `surface`/`background` hex breaks the tonal ladder; container roles used as text color; 72 ad-hoc `.copy(alpha=)` calls |
| Typography | **B−** | `titleSmall` redefined off-spec; 5 sites bypass tokens with `fontSize`; 11 sites bolt `FontWeight` onto tokens; chart axis labels use a raw `TextStyle` with no font family |
| Components & layout | **C+** | Two competing `SnackbarHost`s; no top app bar on primary destinations; no `scrollBehavior` anywhere; hand-rolled `Modifier.background` + `clickable` containers instead of `Surface`/`Card` |
| **UX writing / content** | **D** | **102 distinct Title Case strings** where M3 mandates sentence case; hardcoded English in Compose; "Danger zone", "Please", ampersands in labels |
| i18n hygiene | **C** | 12 user-visible strings hardcoded in Kotlin, 3 hardcoded `contentDescription`s |

The single largest body of work is **content design** — Title Case is pervasive and touches almost every feature module. The single highest-*severity* defect is the **collapsed surface hierarchy in the fallback (non-dynamic) light theme**, where `background`/`surface` and `surfaceContainerLow` resolve to effectively the same tone, making low-emphasis cards invisible for every user who has dynamic color off.

---

## 2. Violations & Non-Compliant Elements

Findings are grouped by M3 foundation. Each carries a severity: **P1** (visible defect / accessibility failure), **P2** (guideline violation, no visible break), **P3** (consistency / maintainability).

### 2.1 Color & Elevation

**C-1 — `background`/`surface` hardcoded outside the tonal palette. [P1]**
`core/designsystem/ThemeColorUtils.kt:176,178,213,215,271,273,307,309`

```kotlin
background = Color(0xFF0A0A0A),   // dark
surface    = Color(0xFF0A0A0A),
background = Color(0xFFF5F5F5),   // light
surface    = Color(0xFFF5F5F5),
```

Every other role in the same `darkColorScheme`/`lightColorScheme` call is derived from the seed via `n(tone)`. These four are not. M3 specifies `surface = N-6` (dark) / `N-98` (light).

**Consequence, light theme:** `0xFFF5F5F5` ≈ neutral tone 96. Two lines below, `surfaceContainerLow = n(96)` — *the same tone*. Any component using `surfaceContainerLow` (`StatusLegend`, `M3CollapsibleSection`, `MetricStatus.DEFAULT_CARD`, `SkeletonCard`) is therefore **invisible against the page background** for every user running the fallback scheme (dynamic color off, or API < 31). The M3 elevation ladder is collapsed at its most-used rung.

**Consequence, dark theme:** `0xFF0A0A0A` ≈ tone 4, but `surfaceContainerLowest = n(4)`. Same collapse at the bottom of the ladder, plus the neutral chroma tint from the seed is discarded, so dark surfaces read as pure grey while the rest of the palette is tinted.

**C-2 — Container roles used as foreground text color. [P1 — accessibility]**
`feature/vitals/bloodpressure/BloodPressureSplitChart.kt:202`

```kotlin
Text(text = "Diastolic", color = MaterialTheme.colorScheme.tertiaryContainer)
```

`tertiaryContainer` is a *background* role (tone 90 light / 30 dark). Rendered as text on `surface` it is near-invisible and fails WCAG AA contrast in both themes. M3 pairs every container with its `onXContainer`; a container role never carries text.

**C-3 — `surfaceVariant` used as a card/box container. [P2]**
`DatabaseRecoveryScreen.kt:168`, `AboutComponents.kt:107` (`HighlightBox`), `AppInfoSection.kt:55` (`ScoreTable`), `EditModeIndicator.kt:33`, `InsightCard.kt:44,125`, `DropdownPreferenceItem.kt:51`

M3 reserves `surfaceVariant` for the *outline/divider family* (it pairs with `onSurfaceVariant` for secondary text). Container surfaces must use the `surfaceContainer{Lowest…Highest}` ladder. `.claude/CLAUDE.md` states this explicitly ("Map surfaces to explicit M3 container roles … rather than legacy tonal elevations").

**C-4 — Alpha-modified container colors. [P1 for two sites, P2 elsewhere]**
`DatabaseRecoveryScreen.kt:205` — `errorContainer.copy(alpha = 0.3f)`
`InsightCard.kt:44,125` — `surfaceVariant.copy(alpha = 0.3f)`

Applying alpha to a container invalidates the guaranteed contrast pairing with its `onXContainer`: `onErrorContainer` text is specified against full-strength `errorContainer`, not a 30% wash of it over an unknown backdrop. M3 achieves "softer" containers by *choosing a lower rung on the ladder*, not by fading one.

Total across the codebase: **72 `.copy(alpha = …)` call sites** (`core/ui`, `feature/*`, `app/*`). Legitimate cases exist (chart area gradients in `TrendCharts.kt:205`, zone bands in `ZoneBandUtils.kt`, disabled states at the M3-canonical `0.38f` in `DateSwitcher.kt:95,120`). The non-compliant residue is the ad-hoc emphasis values:

- `MetricCard.kt:96` — `contentColor.copy(alpha = 0.7f)` for secondary text → should be `onSurfaceVariant`
- `M3ScoreGaugeCard.kt:190` — `onSurfaceVariant.copy(alpha = 0.8f)`
- `SectionHeader.kt:23` — `color.copy(alpha = 0.5f)` for the disabled state → M3 disabled content is `0.38f`
- `SkeletonCard.kt:67,96,146` — `surfaceContainerLow.copy(alpha = 0.5f)`
- `TrendCharts.kt:402` — `onSurfaceVariant.copy(alpha = 0.6f)`
- `ChartDefaults.kt:39` — `onSurface.copy(alpha = 0.1f)` for guidelines → should be `outlineVariant`
- `DataPointTooltip.kt:162,168` — `inverseOnSurface.copy(alpha = 0.9f / 0.85f)`
- `M3MetricGauge.kt:30` — track color `onSurfaceVariant.copy(alpha = 0.38f)` → should be `surfaceContainerHighest`, the M3 progress-track role

**C-5 — Elevation. [No finding — compliant]**
No `elevation =`, `CardDefaults.cardElevation`, or `Modifier.shadow` anywhere in UI code. The app correctly expresses hierarchy through tonal containers, as M3 requires. This should be protected by a lint rule so it does not regress.

### 2.2 Shapes & Radii

**S-1 — Custom radii. [No finding — compliant]**
Zero `RoundedCornerShape(...)` literals in `main` source sets. All 86 shape references go through `MaterialTheme.shapes.*` or `CircleShape`.

**S-2 — `MaterialTheme` is constructed without a `shapes` argument. [P3]**
`core/designsystem/Theme.kt:236-240` passes `colorScheme` and `typography` only. This is *functionally* correct (the M3 defaults are the spec values), but it leaves the design system with no single place to express shape intent, and no `ShapeDefaults`-based token surface to point contributors at.

**S-3 — Shape inconsistency for the same semantic role. [P2]**
`DatabaseRecoveryScreen.kt:121,140` use `MaterialTheme.shapes.medium` for status cards while lines 170 and 207 use `shapes.large` for the action cards on the same screen. `.claude/CLAUDE.md` fixes the house standard at `shapes.large` (16dp) for "cards, tables, highlight boxes, and banners".

**S-4 — Unclipped child background bleeds past a rounded parent. [P1 — visible defect]**
`feature/settings/M3CollapsibleSection.kt:70-75`

```kotlin
Box(modifier = Modifier
    .fillMaxWidth()
    .background(MaterialTheme.colorScheme.surfaceContainer)   // no shape, no clip
    .padding(...))
```

The parent `Box` (line 36) paints `surfaceContainerLow` with `shape = MaterialTheme.shapes.large`, but never clips its children. The expanded content therefore paints square `surfaceContainer` corners *over* the parent's rounded bottom corners. Visible on every expanded settings section.

### 2.3 Typography & Copy

**T-1 — `titleSmall` redefined off-spec. [P2]**
`core/designsystem/Type.kt:56-63` overrides `titleSmall` to `12sp / 16sp line height`. The M3 type scale specifies **`titleSmall` = 14sp / 20sp / 0.1sp**. 12sp/16sp is the `labelMedium` metric. Any component reaching for `titleSmall` (e.g. `StatusLegend.kt:88`) silently renders a label-sized title. `bodyLarge` (lines 64-71) is also hand-rebuilt but happens to match spec exactly — redundant, and a trap for the next editor.

**T-2 — Token bypass via `fontSize`. [P2]**
5 sites overwrite a token's size, which breaks the type scale and defeats user font-scale expectations:

- `M3ScoreGaugeCard.kt:213` — `bodySmall.copy(fontSize = 11.sp)`
- `M3MetricGauge.kt:144` — `labelMedium.copy(fontSize = 11.sp)`
- `DashboardMetricRenderers.kt:178` — `labelMedium.copy(fontSize = 11.sp)`
- `DashboardMetricRenderers.kt:230` — `bodySmall.copy(fontSize = 11.sp)`
- `HrTimelineChart.kt:181` — `TextStyle(color = …, fontSize = 10.sp)`

M3 provides `labelSmall` (11sp) for exactly this need. Four of the five are re-deriving `labelSmall` by hand.

**T-3 — Chart text bypasses the type system entirely. [P2]**
`core/ui/components/ChartDefaults.kt:27,33`

```kotlin
rememberTextComponent(style = TextStyle(color = MaterialTheme.colorScheme.onSurface))
```

A bare `TextStyle` carries no `fontFamily`, so every Vico axis and marker label renders in the platform default font while the rest of the app uses `GoogleSansFlex`. Same defect at `HrTimelineChart.kt:181`.

**T-4 — `FontWeight` bolted onto type tokens. [P2]**
11 sites: `HeartRateSettings.kt:230,337`; `AboutComponents.kt:147,150`; `AppInfoSection.kt:67,73,79`; `DatabaseRecoveryScreen.kt:99,147,180,217`.

M3 type tokens already encode weight (`titleMedium` is Medium 500; `headlineMedium` is Regular 400 *by design* — headlines are not bold in M3). `headlineMedium + FontWeight.Bold` (`DatabaseRecoveryScreen.kt:99`) produces a weight that exists nowhere in the scale. Emphasis in M3 comes from *choosing a larger token or a stronger color role*, not from thickening an existing one. (The `SpanStyle(FontWeight.Bold)` inside `parseMarkdown` is the one legitimate case — it is inline rich-text markup, not a component style.)

**T-5 — Title Case throughout the UI. [P1 — the largest single violation set]**
**102 distinct Title Case strings** across all `strings.xml` files. M3 UX writing is explicit: *use sentence case for all UI text* — titles, labels, buttons, menu items, headers. Title Case is reserved for proper nouns and product names.

Representative sample (full list to be enumerated during implementation):

| File | Key | Current | Should be |
|---|---|---|---|
| `core/ui` | `heart_rate_title` | Heart Rate | Heart rate |
| `core/ui` | `label_normal_limit` | Normal Limit | Normal limit |
| `core/ui` | `label_zone_breakdown` | Zone Breakdown | Zone breakdown |
| `core/ui` | `label_daily_steps` | Daily Steps | Daily steps |
| `core/ui` | `card_title_sleep_efficiency` | Sleep Efficiency | Sleep efficiency |
| `core/ui` | `settings_retention_enabled_label` | Retention Enabled | Retention enabled |
| `core/ui` | `settings_retention_period_label` | Retention Period | Retention period |
| `core/ui` | `sync_progress_download_logs` | Download Logs | Download logs |
| `core/ui` | `card_bp_status_warning` | Hypertension Stage 1: … | Hypertension stage 1: … |
| `app` | `accessibility_security_alert` | Security Alert | Security alert |
| `feature/dashboard` | `manage_cards` | Manage Cards | Manage cards |
| `feature/sleep` | `sleep_score_gauge_title` | Sleep Score | Sleep score |
| `feature/sleep` | `sleep_time_gauge_title` | Sleep Time | Sleep time |
| `feature/sleep` | `card_title_nap_duration` | Nap Duration | Nap duration |
| `feature/sleep` | `card_title_nap_count` | Naps Today | Naps today |
| `feature/vitals` | `label_body_fat_percentage` | Body Fat Percentage | Body fat percentage |
| `feature/vitals` | `label_blood_pressure_trend` | Blood Pressure Trend | Blood pressure trend |
| `feature/vitals` | `weight_status_healthy_weight` | Healthy Weight | Healthy weight |
| `feature/vitals` | `body_fat_status_above_range` | Above Range | Above range |
| `feature/workouts` | `workout_intensity_very_hard` | Very Hard | Very hard |
| `feature/workouts` | `workout_stats_ras_title` | Readylytics Activity Score | *(keep — product term)* |
| `feature/settings` | `advanced_training_load_label` | Training Load Model | Training load model |
| `feature/settings` | `circadian_threshold_window_label` | Threshold Window | Threshold window |
| `feature/settings` | `baseline_window` / `evaluation_period` | Baseline Window / Evaluation Period | Baseline window / Evaluation period |
| `feature/onboarding` | `onboarding_open_hc_settings` | Open Health Connect Settings | Open Health Connect settings |
| `feature/onboarding` | `onboarding_grant_permissions_retry` | Grant Permissions | Grant permissions |
| `feature/onboarding` | `onboarding_sync_error_retry` | Retry Sync | Retry sync |
| `feature/onboarding` | `onboarding_sync_error_report` | Report Issue | Report issue |
| `feature/insights` | `insight_detail_observed_signal` | Observed Signal | Observed signal |
| `feature/insights` | `insight_detail_what_this_might_mean` | What This Might Mean | What this might mean |
| `feature/insights` | `insight_detail_how_this_affects_your_score` | How This Affects Your Score | How this affects your score |
| `feature/insights` | `insight_step_shortfall_title` | Low Daily Activity | Low daily activity |
| `feature/insights` | `insight_sick_indicator_title` | Potential Illness Detected | Possible illness detected |
| `feature/insights` | `insight_late_nadir_elevated_rhr_title` | Delayed Recovery with Elevated Resting Heart Rate | Delayed recovery with elevated resting heart rate |

**Proper nouns that must be preserved:** Health Connect, Google Fit, Garmin Connect, Readylytics, Readylytics Activity Score (RAS), GitHub, Play Store, Android Keystore, SpO2, HRV, RHR, ACWR, TRIMP, BMI.

**T-6 — Ampersands in UI labels. [P2]**
`onboarding_grant_access` — "Grant Access & Continue"; `recovery_reset_button` — "Reset database & start fresh"; `workout_stats_acwr_title` — "Training Load & Strain Ratio (ACWR)"; `log_share_chooser_title` — "Download/Share logs".
M3 UX writing: spell out "and"; reserve "&" for space-constrained tabular contexts. Slash constructions ("Download/Share") force the reader to parse two options in one label.

**T-7 — "Please" and non-user-focused phrasing. [P2]**
`app/strings.xml:75` — "…Please attach that file to this issue." M3 UX writing removes "please" from instructions; it adds length without adding politeness in a UI context.
`recovery_danger_title` — "Danger zone" is jargon borrowed from developer tooling and describes the *UI region*, not what the user is doing. M3 favours user-focused, action-oriented labels: "Delete all data".
`recovery_reset_button` — "Reset database & start fresh" mixes a technical noun ("database") with marketing tone ("start fresh"). Prefer "Delete all data".

**T-8 — Exclamation mark in a system message. [P3]**
`core/ui/strings.xml:124` — `insight_rest_day_perfect_sleep` = "Your night's rest was perfect!" M3 UX writing discourages exclamation marks in system-generated messages; they read as artificial enthusiasm. Also "perfect" is an absolute claim for a probabilistic wearable estimate — inconsistent with the app's own measurement caveat in `AppInfoSection`.

**T-9 — Snackbar copy. [P3]**
`sync_completed` = "Sync and recalculation completed". M3 snackbars should be short and lead with the outcome. "Sync complete" or "Health data updated" is the M3 register.

### 2.4 Components & Layout

**L-1 — Two competing `SnackbarHost`s. [P1]**
`MainScaffold.kt:75,134` owns a `SnackbarHostState` in the `Scaffold`'s `snackbarHost` slot (correct M3). `DashboardScreen.kt:77,319` creates a *second*, independent `SnackbarHostState` and places its `SnackbarHost` manually in a `Box` with `Alignment.BottomCenter`.

Consequences: (a) a sync-completion snackbar and a dashboard-error snackbar can render simultaneously and overlap, because neither host knows about the other's queue; (b) the dashboard host is outside the `Scaffold`, so it does not participate in FAB/bottom-bar avoidance; (c) it is manually padded, including a magic constant.

**L-2 — Magic-number snackbar inset. [P2]**
`DashboardScreen.kt:329` — `bottom = if (uiState.isManagingCards) 88.dp else …`, with the comment "no grid token, clears the edit-mode FAB height exactly". This is exactly the layout coupling `Scaffold` exists to eliminate — placing the FAB in the `floatingActionButton` slot makes the framework compute the offset. 88dp is also off the M3 4dp grid for this purpose (FAB 56 + 16 + 16 = 88 works only for the current FAB variant; it silently breaks if the FAB changes).

**L-3 — Snackbar styled with the error palette. [P2]**
`DashboardScreen.kt:332-336` overrides `containerColor = errorContainer`, `contentColor = onErrorContainer`. The M3 snackbar spec fixes the container to `inverseSurface` with `inverseOnSurface` content and `inversePrimary` actions — deliberately, so snackbars read as a consistent system surface regardless of message sentiment. M3 does not define an "error snackbar" variant; error emphasis belongs in the message text or in an inline error surface.

**L-4 — No top app bar on any primary destination. [P2] → tracked separately**
Only the six *detail* screens declare a `topBar`. Dashboard, Sleep, Workouts, Vitals, and Settings have none — `MainScaffold`'s `Scaffold` (line 133) passes only `snackbarHost`. M3 navigation guidance expects primary destinations to carry a top app bar for title, context, and overflow actions. Today the Dashboard's only title-equivalent is the `DateSwitcher`, and Settings has no screen title at all.

This is a genuine M3 finding, but remediating it changes the app's visual structure rather than correcting a token or a defect. **It is therefore planned separately in [`M3_TOP_APP_BAR_PLAN.md`](./M3_TOP_APP_BAR_PLAN.md)** and is explicitly out of scope for this document.

**L-5 — No `scrollBehavior` anywhere. [P2]**
Zero occurrences of `scrollBehavior` or `nestedScroll` in the codebase. Every `TopAppBar` (6 detail screens) is static. M3 top app bars are specified to change container color on scroll (`TopAppBarDefaults.enterAlwaysScrollBehavior` / `pinnedScrollBehavior`) so content is visually separated as it passes beneath. Without it, scrolled content slides under a flat, same-colored bar with no boundary.

Scope split: retrofitting `scrollBehavior` to the **six existing detail-screen app bars** is in scope here (Phase 5). Scroll behavior for any *new* primary-destination app bar belongs to the separate top-app-bar plan.

**L-6 — Hand-rolled interactive containers instead of `Surface`/`Card`. [P1 for the settings section, P2 for the legend]**
`M3CollapsibleSection.kt:36-48` and `StatusLegend.kt:71-76` build clickable containers from `Modifier.background(...)` + `Modifier.clickable {}`.

Missing versus `Surface(onClick = …)` / `Card(onClick = …)`: shape-bounded ripple (the ripple currently paints as a rectangle over rounded corners), the M3 state layer (hover/focus/pressed tonal overlay), `Role.Button` semantics for TalkBack, and the enforced 48dp minimum touch target. `.claude/CLAUDE.md` requires native M3 components "instead of custom-built row/toggle layouts".

**L-7 — Non-standard "banner" component. [P2]**
`MainScaffold.kt:184-188` (`RecalcProgressBanner`) uses a bare `Surface` with no `shape`, so it renders full-bleed with square corners. M3 removed the M2 "banner" component; the sanctioned patterns for transient progress are a snackbar, an inline progress surface, or a status card. `.claude/CLAUDE.md` additionally requires `MaterialTheme.shapes.large` for banners and `surfaceContainerHigh` for progress banners (the color is right; the shape is missing).
`CalibrationBanner.kt` gets this right (`shape = MaterialTheme.shapes.large`, `secondaryContainer`) and should be the pattern both follow.

**L-8 — Decorative icon without a size token. [P3]**
`DatabaseRecoveryScreen.kt:89-94` renders `Icons.Default.Warning` with no `Modifier.size(...)`, defaulting to 24dp where the surrounding layout (a full-screen hero) calls for a larger display icon. Every other icon in the app uses `MaterialTheme.dimens.icon*`.

**L-9 — Raw `.dp` literals outside the token system. [P3]**
**190 raw `.dp` occurrences** in `main` source sets. Concentrated in charts, where per-chart geometry is defensible, but a meaningful share are component-level spacing and sizing that should be `MaterialTheme.spacing` / `MaterialTheme.dimens`:

`HrTimelineChart.kt` (17), `SleepStagesChart.kt` (13), `AcwrChartOverlay.kt` (8), `SleepTrendChart.kt` (8), `BloodPressureTrendChart.kt` (7), `SleepTrendOverlay.kt` (7), `VicoChartTooltipOverlay.kt` (7), `TrendCharts.kt` (7). Component-level offenders needing tokens: `M3ScoreGaugeCard.kt:181,202` (`10.dp`, `20.dp`), `M3MetricGauge.kt:149` (`(-8).dp` offset), `HeartRateSettings.kt` (`60.dp`, `72.dp` field widths), `DashboardScreen.kt:329` (`88.dp`).

**L-10 — Fully-qualified inline composable references. [P3]**
`M3MetricGauge.kt:130-155` calls `androidx.compose.foundation.layout.Column`, `androidx.compose.material3.Text`, `androidx.compose.ui.text.style.TextAlign.Center` inline rather than importing. Stylistic, but it obscures which design-system layer a call belongs to.

### 2.5 Strings & i18n (project rule + M3 content design)

**I-1 — User-visible strings hardcoded in Kotlin. [P1 — blocks localization]**
`.claude/CLAUDE.md`: *"All user-facing strings … must be defined in `app/src/main/res/values/strings.xml`. … Never hardcode strings in code."*

| Location | Literal |
|---|---|
| `core/ui/components/StatusLegend.kt:87` | `"Status Guide"` |
| `core/ui/components/StatusLegend.kt:50-53` | `"Optimal"`, `"Neutral"`, `"Warning"`, `"Poor"` — duplicating the existing `metric_status_*` keys |
| `core/ui/components/TrendCharts.kt:382` | `"$label: $formattedValue $unit"` |
| `feature/vitals/…/BloodPressureSplitChart.kt:186,200` | `"Systolic"`, `"Diastolic"` |
| `feature/settings/ThresholdSettings.kt:135` | `"${currentStepGoal.roundToInt()} steps"` — needs a plurals resource |
| `feature/workouts/WorkoutMetricsDisplay.kt:242` | `"%.0f min".format(minutes)` |
| `feature/workouts/WorkoutListSection.kt:141` | `"$displayType $dateStr"` |
| `feature/dashboard/StepsCard.kt:79,85` | `"${stepCount ?: 0}"`, `"/ $stepGoal"` |
| `feature/dashboard/HeartRateCard.kt:89` | `"${summary.minBpm}–${summary.maxBpm}"` |
| `feature/about/AboutComponents.kt:83` | `"•"` |
| `feature/insights/InsightDetailSheet.kt:124` | `"• $value"` |
| `feature/dashboard/EditModeIndicator.kt:69` | `if (isEditing) "Editing" else "Edit"` |
| `feature/about/AppInfoSection.kt:20-45` | Four full English paragraphs plus the entire `ScoreTable` — headers `"Score"`, `"What it answers"`, `"Range"`, all three row triples, and the literal markdown headings `"# About your scores"`, `"## A note on measurement"`, `"## The three scores at a glance"` |

**I-2 — Hardcoded `contentDescription`. [P1 — accessibility + i18n]**
`StatusLegend.kt:94` and `M3CollapsibleSection.kt:59` — `if (expanded) "Collapse" else "Expand"`.
`EditModeIndicator.kt:64` — `if (isEditing) "Currently in editing mode" else "Enter editing mode"`.
Screen-reader users in any non-English locale get untranslated English. The third also violates M3 accessibility guidance by *describing state in the label* rather than exposing it via `semantics { stateDescription = … }`.

**I-3 — Markdown control characters leaking into presentation strings. [P3]**
`AppInfoSection.kt:19,29,37` pass `"# About your scores"` / `"## …"` to `SectionHeader`/`SubHeader`, and `ScoreTableRow("**Sleep Score**", …)` passes `**` markers. The `#` levels are inert (`SectionHeader` ignores them; only `**`/`*` are parsed by `parseMarkdown`), so they are dead syntax that will end up in translator-facing strings.

---

## 3. Proposed Refactoring Strategy

Six phases, ordered so that foundation fixes land before the code that consumes them. Each phase is independently shippable and independently verifiable.

### Phase 0 — Guardrails (do first, ~0.5 day)

1. Add a `core/designsystem` unit test asserting the fallback schemes keep a monotonic tonal ladder: `surface ≠ surfaceContainerLow`, and `surfaceContainerLowest < Low < Container < High < Highest` by luminance, in both light and dark. This test **fails today** — it is the regression proof for C-1.
2. Add a `Typography` spec test asserting each token's `fontSize`/`lineHeight`/`letterSpacing` equals the M3 scale value. Fails today on `titleSmall` (T-1).
3. Add ktlint/Detekt custom rules (or a CI `grep` gate) for: `Color(0x` outside `core/designsystem`; `RoundedCornerShape(` anywhere in `main`; `text = "` / `contentDescription = "` string literals in Compose; `elevation =` in UI modules. This locks in the two areas already at grade A.

### Phase 1 — Color foundation (P1)

1. **C-1** — Replace the four hardcoded hex values in `ThemeColorUtils.kt` with tonal derivations: dark `background`/`surface` → `n(6)`; light → `n(98)`. Verify the Phase-0 ladder test passes and screenshot the fallback theme in both modes.
2. **C-2** — `BloodPressureSplitChart.kt:202` → `tertiary` for the label text; keep `tertiaryContainer` for the swatch only. Verify contrast ≥ 4.5:1 in both themes.
3. **C-3** — Migrate all seven `surfaceVariant` container sites to the ladder: `HighlightBox` and `ScoreTable` → `surfaceContainerLow`; `DatabaseRecoveryScreen` recommended-action card → `surfaceContainerHigh`; `EditModeIndicator` → `surfaceContainerHigh`; `InsightCard` → `surfaceContainerLow`; `DropdownPreferenceItem` disabled container → `surfaceContainerHighest`.
4. **C-4** — Remove alpha from container colors. `errorContainer.copy(0.3f)` → `errorContainer` (or `surfaceContainerLow` with `error`-colored content if the intent was "less shouty"). `surfaceVariant.copy(0.3f)` → `surfaceContainerLow`. Convert the emphasis alphas to roles: `MetricCard` secondary → `onSurfaceVariant`; `SectionHeader` disabled → the M3 `0.38f`; `ChartDefaults.guidelineComponent` → `outlineVariant`; `M3MetricGauge` track → `surfaceContainerHighest`.

*Verify:* ladder test green, `./gradlew testDebugUnitTest`, visual diff of Dashboard + Settings + About in light/dark × dynamic/fallback.

### Phase 2 — Typography foundation (P2)

1. **T-1** — Delete the `titleSmall` and `bodyLarge` hand-built `TextStyle`s in `Type.kt`; use `defaultTypography.titleSmall.copy(fontFamily = GoogleSansFlex)` like every sibling. Audit `titleSmall` call sites (notably `StatusLegend.kt:88`) for the size change from 12sp → 14sp and re-token any that were relying on the smaller metric (`labelMedium` is the correct target there).
2. **T-2** — Replace all five `fontSize = 11.sp` / `10.sp` overrides with `MaterialTheme.typography.labelSmall`.
3. **T-3** — Add `ChartDefaults.chartTextStyle()` returning `MaterialTheme.typography.labelSmall.copy(color = …)`; route `labelTextComponent`, `axisLabelTextComponent`, and `HrTimelineChart.kt:181` through it so charts inherit `GoogleSansFlex`.
4. **T-4** — Remove all 11 `fontWeight = FontWeight.*` overrides. Where emphasis is genuinely needed, move up a token (`titleMedium` → `titleLarge`) or change color role (`onSurfaceVariant` → `onSurface`, or `primary`). Keep the `SpanStyle` weights inside `parseMarkdown` — that is inline rich text, not component styling.
5. **S-2** — Pass an explicit `shapes = Shapes(...)` built from `ShapeDefaults` into the `MaterialTheme` call so the design system owns all three axes (color, type, shape) in one place.

*Verify:* typography spec test green; `./gradlew testDebugUnitTest`; check every screen at 200% font scale for clipping introduced by the `titleSmall` size change.

### Phase 3 — Content & UX writing (P1, largest volume)

Split into two independently-reviewable commits so the mechanical change and the editorial change do not obscure each other.

**3a — Sentence case sweep (mechanical).** Convert all 102 Title Case values across the 10 `strings.xml` files, preserving the proper-noun allowlist (Health Connect, Google Fit, Garmin Connect, Readylytics, Readylytics Activity Score, GitHub, Play Store, Android Keystore, SpO2, HRV, RHR, ACWR, TRIMP, BMI). Keys are unchanged, so no Kotlin edits are required.

**3b — Editorial rewrite (judgement). In scope.** Every item below is a change M3 content design calls for, not a discretionary tone preference. Each row cites the specific guideline it satisfies.

| Key | Current | Proposed | M3 rule |
|---|---|---|---|
| `onboarding_grant_access` | Grant Access & Continue | Grant access and continue | Spell out "and"; sentence case |
| `recovery_reset_button` | Reset database & start fresh | Delete all data | Spell out "and"; name the action, not the mechanism; avoid marketing tone in a destructive control |
| `recovery_danger_title` | Danger zone | Delete all data | User-focused, action-oriented; not developer jargon naming a UI region |
| `recovery_danger_body` | If you don't have a backup, you can reset the database. This will permanently delete all existing local health dashboards and records. | Deleting removes all your health data from this device permanently. This can't be undone. | Lead with the consequence; second person; avoid "database" as user-facing vocabulary |
| `workout_stats_acwr_title` | Training Load & Strain Ratio (ACWR) | Training load and strain ratio (ACWR) | Spell out "and"; sentence case |
| `log_share_chooser_title` | Download/Share logs | Share logs | Avoid slash constructions that stack two options into one label |
| `github_issue_report_saved_to_file` | …has been saved to %1$s. Please attach that file to this issue. | …was saved to %1$s. Attach that file to this issue. | Drop "please"; prefer active past over present perfect |
| `insight_rest_day_perfect_sleep` | Your night's rest was perfect! | Your sleep was fully restorative last night | No exclamation marks in system messages; avoid absolute claims for a probabilistic estimate |
| `sync_completed` | Sync and recalculation completed | Health data updated | Snackbars are short and lead with the outcome, not the internal process |
| `error_sync_failed` | Sync failed | Couldn't sync health data | State what failed from the user's perspective; "couldn't" over bare failure nouns |
| `recovery_title` | Database access problem | Can't open your health data | User-facing vocabulary; describe the effect, not the subsystem |
| `recovery_success` | Database restored. Restart the app. | Your data was restored. Restart Readylytics to continue. | Second person; give the reason for the required action |
| `crash_report_dialog_send_github` | GitHub Issue (public) | GitHub issue (public) | Sentence case (GitHub is a proper noun; "issue" is not) |
| `github_issue_bug_title` / `github_issue_feature_title` | Bug Report / Feature Request | Bug report / Feature request | Sentence case |
| `accessibility_security_alert` | Security Alert | Security alert | Sentence case |
| `database_migration_failed` | Your existing data is unchanged. Retry the update or export diagnostics before continuing. | Your data is unchanged. Try the update again, or export diagnostics first. | Concise; plain verbs over nominalizations |
| `sync_progress_download_logs` | Download Logs | Save logs | Sentence case; "save" matches what actually happens on-device |
| `insight_sick_indicator_title` | Potential Illness Detected | Possible illness detected | Sentence case; "possible" is honest about a signal the app cannot diagnose, consistent with the measurement caveat in `AppInfoSection` |

Additional sweeps in this commit:
- Read through the `insight_detail_*` section headers as a set for register consistency once 3a has lowercased them ("What This Might Mean" → "What this might mean") — the mechanical pass fixes the case; this pass confirms the whole sequence still reads as one voice.
- Confirm no remaining exclamation marks or "please" in any `strings.xml` (currently one of each).
- Confirm every string ≤ 60 chars used as a button/label has no terminal period, and every multi-sentence body string does — M3 punctuates full sentences, not labels.

**3c — Documentation sync (mandatory per `.claude/CLAUDE.md`).** Score-explanation copy is load-bearing. Any wording change to score explanations, tooltips, or onboarding text in 3b requires a same-PR update to `ABOUT.md`, `docs/about.md`, and the relevant `internal-docs/DATA_FLOW.md` sections, and must keep `domain/scoring/**DocumentationDriftTest*` green. `.github/ISSUE_TEMPLATE/*.md` must stay mirrored with `report_email_bug_template` / `report_email_feature_template`.

*Verify:* `./gradlew testDebugUnitTest` (drift tests); `./gradlew lintRelease` (missing-translation / unused-resource checks); manual read-through of Settings, Onboarding, and Insight detail.

### Phase 4 — Externalize strings & accessibility (P1)

1. **I-1** — Extract all 12+ hardcoded literals to the owning module's `strings.xml`. Notes:
   - `StatusLegend` status labels should *reuse* the existing `metric_status_*` keys in `core/ui` rather than adding duplicates.
   - `ThresholdSettings` "N steps" and `WorkoutMetricsDisplay` "N min" need `<plurals>`, not `<string>`.
   - `StepsCard` `"/ $stepGoal"` and `HeartRateCard` `"$min–$max"` need format strings so RTL locales and locale-specific separators work.
   - `AppInfoSection` prose and the entire `ScoreTable` move to `feature/about/strings.xml`; strip the leading `#`/`##` markdown from the heading literals (I-3).
2. **I-2** — Add `action_expand` / `action_collapse` to `core/ui/strings.xml` and use them in `StatusLegend` and `M3CollapsibleSection`. For `EditModeIndicator`, move the state out of the label: a stable `contentDescription` plus `semantics { stateDescription = … }`.
3. Sweep for interactive elements below the 48dp M3 minimum touch target once Phase 5 converts the hand-rolled containers.

*Verify:* `./gradlew lintRelease` with `HardcodedText` promoted to error; TalkBack pass over Dashboard, Settings, and About.

### Phase 5 — Components & layout (P1/P2)

1. **L-1 / L-2 / L-3 — Unify snackbars.** Delete the `SnackbarHostState` and `SnackbarHost` from `DashboardScreen`; hoist a single host into `MainScaffold`'s `Scaffold` slot and pass either the state or an `onShowMessage: (String) -> Unit` down to `DashboardRoute`. Move `EditModeFab` into the `Scaffold`'s `floatingActionButton` slot so the framework computes FAB avoidance — this deletes the `88.dp` magic constant. Drop the `errorContainer` override and use the default M3 `inverseSurface` snackbar.
2. **L-5 — Scroll behavior on existing app bars.** Retrofit `TopAppBarDefaults.pinnedScrollBehavior()` to the six detail screens (`HeartRateDetailScreen`, `WeightDetailScreen`, `BodyFatDetailScreen`, `BloodPressureDetailScreen`, `StepDetailScreen`, `WorkoutDetailScreen`), wiring each through `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)` on the `Scaffold` and passing `scrollBehavior` to the `TopAppBar`. Keep `contentWindowInsets = WindowInsets(0)` on the nested detail `Scaffold`s — that is correct today, since the outer `Scaffold` already consumes system bars, and removing it would double-inset. *(L-4, adding app bars to primary destinations, is planned in `M3_TOP_APP_BAR_PLAN.md`.)*
3. **L-6 — Native M3 containers.** `M3CollapsibleSection` → `Surface(onClick = …, shape = MaterialTheme.shapes.large, color = surfaceContainerLow)` with the expanded region as a nested `Surface(color = surfaceContainer)` inside `Modifier.clip(MaterialTheme.shapes.large)` — this also fixes **S-4**. Animate the chevron with `animateFloatAsState` per M3 motion. `StatusLegend` header → `Surface(onClick = …)` or the `ListItem` + `Modifier.clickable` pattern already used correctly in `SettingsScreen`.
4. **L-7 — `RecalcProgressBanner`.** Give it `shape = MaterialTheme.shapes.large` and page-horizontal padding so it floats as a card rather than a full-bleed rectangle, matching `CalibrationBanner`.
5. **S-3** — Standardize `DatabaseRecoveryScreen` on `MaterialTheme.shapes.large` for all four cards.
6. **L-8** — Size the recovery hero icon via `MaterialTheme.dimens` (add an `iconHero` token if none fits).
7. **L-10** — Replace fully-qualified inline composable calls in `M3MetricGauge.kt` with imports.

*Verify:* `./gradlew testDebugUnitTest` + existing `MainScaffoldTest` / `DashboardScreenTest` androidTests; manual check that only one snackbar can be on screen at a time; expand/collapse a settings section and confirm the corner bleed is gone.

### Phase 6 — Token hygiene (P3)

1. **L-9** — Triage the 190 raw `.dp` literals. Convert component-level spacing/sizing to `MaterialTheme.spacing` / `MaterialTheme.dimens`, adding tokens where a real gap exists (`fieldWidthCompact`, `iconHero`). Leave genuine chart geometry (stroke widths, point radii, tick offsets) as locals, but hoist them to named `private val`s at file top so they are greppable and reviewable.
2. Extend the Phase-0 lint gate to flag new raw `.dp` in non-chart UI files.

### File-lifecycle & process obligations

Per `.claude/CLAUDE.md`:

- Pre-commit for every phase: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`; `./gradlew lintRelease` once all phases are complete.
- Run `codegraph index` after any phase that adds files (Phase 0 tests, Phase 6 token additions); `codegraph sync` after Phase 5's structural moves.
- Phase 3 carries the documentation-sync requirement (`ABOUT.md`, `docs/about.md`, `internal-docs/DATA_FLOW.md`, in-app `about_*`/`tooltip_*` strings). No scoring formulas, thresholds, or coefficients are touched by any phase in this plan — copy only.
- Keep files ≤ 400 lines (hard limit 800). `SettingsScreen.kt` is 665 and `SleepStagesChart.kt` is 756; Phase 5 and 6 touch both, so split them opportunistically rather than growing them further.

### Sequencing rationale

Phase 1 before Phase 5 — several component fixes pick new container roles, and choosing them against a *collapsed* ladder would bake in the wrong choice. Phase 2 before Phase 3 — the `titleSmall` size change alters line lengths, and re-flowing text after a copy rewrite would mean reviewing the same screens twice. Phase 4 after Phase 3 — extracting strings that are about to be rewritten doubles the review surface.

---

## 4. Estimated effort & risk

| Phase | Effort | Risk | Notes |
|---|---|---|---|
| 0 — Guardrails | 0.5 d | Low | New tests only; two fail by design |
| 1 — Color | 1 d | **Medium** | C-1 changes every surface in the fallback theme; needs light/dark × dynamic/fallback screenshot review |
| 2 — Typography | 0.5 d | Low–Medium | `titleSmall` 12→14sp may reflow tight layouts |
| 3 — Content | 2 d | Low–Medium | 3a is high-volume but mechanical; 3b is an 18-row editorial rewrite carrying the doc-sync obligation |
| 4 — Strings/a11y | 1 d | Low | Mostly extraction; plurals need care |
| 5 — Components | 1.5 d | **Medium–High** | Snackbar unification and FAB relocation touch `MainScaffold` + `DashboardScreen`; androidTests exist and must stay green |
| 6 — Token hygiene | 1 d | Low | Cosmetic, but in scope — closes the last token gap |

**Total: ~7.5 days**, all seven phases. Phases 0–6 together bring the audited surface to full M3 compliance; the remaining L-4 structural work is scoped in the companion plan.

**Highest-risk change:** C-1. It shifts `surface`/`background` for every non-dynamic-color user. Recommend landing it alone, behind a screenshot-diff review, before anything else in Phase 1.

**Second-highest:** Phase 3b. It changes user-facing product voice on destructive controls (`recovery_danger_*`) and on health-signal copy (`insight_sick_indicator_title`). Both deserve a read-through by someone with product context before merge, and both trigger the documentation-sync rule in `.claude/CLAUDE.md`.

---

## 5. Approval gate

This document is a plan. **No implementation code will be written until it is explicitly approved.**

**Scope is settled:** all seven phases (0–6) proceed, and wording is corrected wherever M3 content design calls for it — both the mechanical sentence-case pass (3a) and the editorial rewrites (3b). The one structural finding, L-4, is planned separately in [`M3_TOP_APP_BAR_PLAN.md`](./M3_TOP_APP_BAR_PLAN.md) and approved or declined on its own.

Open points that do **not** block approval but should be settled before Phase 3b merges:

1. The proposed replacement strings in the 3b table are recommendations, not fixed text. Any row can be re-worded as long as it still satisfies the cited M3 rule.
2. `recovery_danger_title` and `recovery_reset_button` both resolve to "Delete all data" under the proposal. That is intentional — the section heading and its button state the same action — but if the section needs a distinct heading, "Delete all data" (heading) with "Delete" (button) is the M3-conformant alternative.
3. `insight_sick_indicator_title` sits closest to medical-claim territory. "Possible illness detected" is the more defensible phrasing, but the final wording should be confirmed against `ABOUT.md`'s measurement caveat during the 3c doc sync.
