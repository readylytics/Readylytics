# M3 Top App Bars for Primary Destinations — Plan

**Status:** PLAN — awaiting approval, decided independently of the main audit. No implementation code has been written.
**Date:** 2026-07-31
**Branch:** `claude/readylytics-m3-audit-plan-i6mx57`
**Parent document:** [`M3_COMPLIANCE_AUDIT.md`](./M3_COMPLIANCE_AUDIT.md) — this plan resolves finding **L-4**.
**Reference:** <https://m3.material.io/components/top-app-bar/guidelines>, <https://m3.material.io/foundations/layout/understanding-layout/parts-of-layout>

---

## 1. Why this is a separate plan

Every other finding in the M3 audit corrects something that is measurably wrong against a token or a spec: a hardcoded hex, an off-scale type size, a container role used as text, a background that bleeds past its parent's radius. Those are repairs — the app looks the same afterwards, only correct.

**L-4 is different.** Adding top app bars to Dashboard, Sleep, Vitals, Workouts, and Settings changes what the app *looks like*. It consumes roughly 64dp of vertical space on every primary screen, introduces a new persistent surface, and forces decisions about where existing header content (the `DateSwitcher`, the Settings search field) should live. That is a product decision wearing a compliance costume, so it gets its own approval.

The audit's other phases can ship in full without this one, and this one can ship without them.

---

## 2. Current state

`MainScaffold.kt:133` declares:

```kotlin
Scaffold(
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
) { innerPadding -> … }
```

No `topBar`. Only the six *detail* screens (`HeartRateDetailScreen`, `WeightDetailScreen`, `BodyFatDetailScreen`, `BloodPressureDetailScreen`, `StepDetailScreen`, `WorkoutDetailScreen`) declare one, each a plain `TopAppBar` with a back arrow and a title.

The five primary destinations each roll their own header, and **they do not agree with each other**:

| Destination | Container | Header content | Pinned or scrolls away? |
|---|---|---|---|
| Dashboard | `LazyColumn` | `DateSwitcher` as `item(key = "date_switcher")` | **Scrolls away** |
| Sleep | `Column` + `verticalScroll` | `DateSwitcher` inside the scrolling column | **Scrolls away** |
| Vitals | `Column` → fixed header + `weight(1f)` scroll region | `DateSwitcher` | **Pinned** |
| Workouts | `Column` → `ScreenHeaderSection` + `weight(1f)` scroll region | `DateSwitcher` | **Pinned** |
| Settings | `Column` + `weight(1f)` `verticalScroll` | `OutlinedTextField` search | **Scrolls away** |

So three screens scroll their header away and two pin it, for the same `DateSwitcher` component. **No screen displays its own name anywhere.** The user's only cue for "where am I" is the selected icon in the navigation bar. Settings in particular has neither a title nor a landmark for accessibility navigation.

`ScreenHeaderSection` (`core/ui/common/ScreenHeaderSection.kt`) is worth noting: it is a 17-line `Column` wrapper that adds nothing but a `fillMaxWidth()` and an `isLoading` passthrough. It exists as a seam for exactly this kind of header standardization, and is currently used by Vitals and Workouts only.

---

## 3. What M3 specifies

Per the M3 top app bar guidelines:

- A top app bar **displays navigational and contextual information at the top of a screen**, and is one of the standard layout regions alongside the body and the bottom navigation bar.
- Apps using bottom navigation should pair it with a top app bar on primary destinations; the navigation bar answers *where can I go*, the app bar answers *where am I*.
- Four variants: **small** (`TopAppBar`, 64dp), **center-aligned** (`CenterAlignedTopAppBar`), **medium** (`MediumTopAppBar`, 112dp, title drops to a second line), **large** (`LargeTopAppBar`, 152dp).
- The container color is `surface`, changing to `surfaceContainer` on scroll via a `scrollBehavior` — this is the mechanism that separates scrolled content from the bar. Without it, content slides under a flat, identically-colored bar with no boundary (audit finding L-5).
- Title typography is `titleLarge` for small/center-aligned; `headlineSmall`/`headlineMedium` for medium/large.

---

## 4. Proposed design

### 4.1 Variant: small `TopAppBar` with `enterAlwaysScrollBehavior`

**Recommended.** Rationale:

- The app is data-dense. Every primary screen is a scrolling stack of cards and charts; `MediumTopAppBar` (112dp) or `LargeTopAppBar` (152dp) would cost a third of the visible fold on a compact phone to display a one-word title.
- `enterAlwaysScrollBehavior` collapses the bar on scroll-down and returns it on any scroll-up, so the 64dp cost is paid only while the user is at rest or reaching for navigation — which is exactly when they need the title.
- Center-aligned is the M3 pattern for single-purpose or entry screens; a five-tab app with actions reads better left-aligned.

Settings is the one candidate for `LargeTopAppBar`, since it is a long list where a collapsing large title is idiomatic. **Recommendation: use small everywhere for v1** and revisit Settings separately — mixing variants across sibling tabs is a bigger consistency cost than the aesthetic gain.

### 4.2 Placement: hoist one bar into `MainScaffold`

Two options:

**(A) Per-screen `Scaffold` with its own `topBar`** — mirrors the detail screens. Rejected: it nests a second `Scaffold` inside `MainScaffold`'s, duplicates inset handling five times, and makes it impossible to share one `scrollBehavior` with the outer layout.

**(B) One `topBar` in `MainScaffold`, driven by the current destination.** **Recommended.** `MainScaffold` already derives `currentDestination` from the back stack (line 62) and already computes `showBottomBar` from it (lines 63-73). The title resolves the same way:

```
currentDestination → TabDestination.all.find { hasRoute(it::class) } → labelRes
```

`TabDestination` (`app/ui/navigation/TabDestination.kt`) already carries `labelRes` for all five tabs — `tab_dashboard`, `tab_sleep`, `tab_vitals`, `tab_workouts`, `tab_settings`. **No new strings are required**; the same resource labels the nav item and the app bar, which is the correct M3 pairing.

The bar is shown when `showBottomBar` is true (i.e. on primary destinations) and hidden on detail screens, which supply their own. This reuses the existing predicate rather than adding a second one.

### 4.3 What happens to the `DateSwitcher`

Three options considered:

**(A) Leave it in the body, below the app bar.** Lowest risk. The app bar carries the screen name; the `DateSwitcher` continues to carry the date. Costs ~64dp. **Recommended for v1.**

**(B) Move date navigation into the app bar** as a title-slot control or actions. Tempting — it reclaims the 64dp — but the `DateSwitcher` is a three-part control (prev / date pill / next) with a date-picker dialog, and M3 app bar action slots are sized for 24dp icon buttons. It would need a custom title composable, and it would diverge from the detail screens' plain-title bars.

**(C) App bar on Settings only**, where there is no `DateSwitcher`. Rejected — inconsistent siblings are worse than a uniform cost.

Under (A), **the pin-vs-scroll inconsistency documented in §2 should be resolved in the same change**: standardize all five on "header scrolls with content, app bar handles pinning". That means Vitals and Workouts stop pinning their `DateSwitcher` and move it into their scroll region, matching Dashboard and Sleep, with `ScreenHeaderSection` either absorbed into the scroll body or retired. §4.3.1 establishes why this direction is safe.

### 4.3.1 Is the Vitals/Workouts pinning deliberate? — investigated

**Finding: the pinning is incidental, not designed.** Confidence: high. Standardizing on scroll-away is therefore the correct direction, and the cheaper one.

Git history cannot corroborate anything here — `git log` on `VitalsScreen.kt`, `WorkoutsScreen.kt`, and `ScreenHeaderSection.kt` returns exactly one commit each, `fb349a7`, which is the repository's **root commit: 1080 files, 273,433 insertions**. All five primary screens "arrived" in the same bulk import, so relative authorship order is unrecoverable. The evidence below is from the code.

**1. `ScreenHeaderSection` has no layout opinion.** Its entire body (`core/ui/common/ScreenHeaderSection.kt`, 17 lines) is a `Column` with `fillMaxWidth()` that invokes `headerContent(isLoading)`. It does not pin, does not measure, does not interact with scroll. Its only real parameter is `isLoading`, forwarded to the content lambda as `isDisabled`. **The component is a loading-gate seam, not a header-pinning mechanism.** Had pinning been the intent, this is where it would live.

**2. The pinning is a side effect of call-site placement.** Vitals and Workouts both structure as `Column { ScreenHeaderSection { … }; Column(Modifier.weight(1f).verticalScroll(…)) { … } }`. The header is pinned purely because the call sits *outside* the `weight(1f)` scroll region. Move the same call inside and the pinning vanishes with no change to `ScreenHeaderSection` itself.

**3. The correlation is exact, and explained by something else.** The two screens that pin (Vitals, Workouts) are precisely the two that adopted `ScreenHeaderSection`, and they adopted it for **gating**, not layout — see point 4. The three that scroll away (Dashboard, Sleep, Settings) never adopted it. Pinning rode along with the gating adoption.

**4. The only comment on this code explains gating, and says nothing about pinning.** `VitalsScreen.kt:76-77`:

> `// isRefreshing (not isLoading) gates the date-switcher: date navigation stays disabled for`
> `// the full sync duration, not just on true first-load (F1).`

The `(F1)` tag is a numbered review-finding marker; the same convention appears at `SecureFileLogSink.kt:152` as `(F2)`. So a reviewer found that gating on `isLoading` was wrong, and the fix was to gate on `isRefreshing`. **That fix is about when the date control is disabled — not about where it sits.**

**5. No test asserts pinned behavior.** `DateSwitcherTest` (`core/ui/androidTest`) exercises the component in isolation — today/yesterday/selected labels, enablement. Nothing anywhere asserts that the header survives a scroll.

### 4.3.2 The inconsistency that actually matters — a partially applied fix

Chasing the pinning question surfaced a real defect that is **independent of the app bar decision** and should be fixed regardless of whether this plan is approved.

The F1 fix — gate date navigation on `isRefreshing` so it stays disabled for the whole sync — **was applied to only two of the four screens that have a `DateSwitcher`**:

| Screen | `DateSwitcher` gating | State available? |
|---|---|---|
| Vitals | `enabled = !isDisabled`, driven by `isRefreshing` ✅ F1-correct | yes |
| Workouts | `enabled = !isDisabled`, driven by `isRefreshing` ✅ F1-correct | yes |
| Sleep | **no `enabled` argument at all** — defaults to `true` | `isRefreshing` present in `SleepUiState` (`SleepViewModel.kt:55, 287`) |
| Dashboard | **no `enabled` argument at all** — defaults to `true` | `isRefreshing` present in `DashboardUiState` (`DashboardViewModel.kt:123, 374`) |

Both un-gated screens **already carry `isRefreshing` in their UI state and never use it for this**. Sleep is the clearest case: it gates its other controls at `SleepScreen.kt:217,231` with `enabled = !uiState.isLoading` — the *pre-F1* signal — and leaves the date switcher ungated entirely. `SleepViewModel.kt:270-277` even carries a long comment distinguishing `isLoading` ("true first-load, no data yet") from `isRefreshing` ("tracks every sync"), so the distinction was understood; it just was not wired to the date control.

**User-visible consequence:** during a sync, paging the date is blocked on Vitals and Workouts but allowed on Dashboard and Sleep — the exact behavior F1 identified as wrong, still live on half the surface.

**Recommendation:** propagate the F1 gating to `DashboardScreen` and `SleepScreen` (pass `enabled = !uiState.isRefreshing`). This is a ~4-line change, needs no app bar, and should ship independently — it is listed as step 0 in §5 so it can go first or separately.

### 4.4 Interaction with `PullToRefreshBox` — the main integration risk

`MainScaffold.kt:91-95` wraps the **entire** `NavigationSuiteScaffold` in a `PullToRefreshBox`:

```kotlin
PullToRefreshBox(isRefreshing = …, onRefresh = …, enabled = !isSyncProgressScreen) {
    NavigationSuiteScaffold(…) { Scaffold(…) { … } }
}
```

The refresh indicator therefore animates from the very top of the window. Once a top app bar occupies that space, the indicator will render **over** the app bar rather than below it. Both fixes are viable:

1. **Move `PullToRefreshBox` inside** the `Scaffold` content lambda so it sits below the bar and inherits `innerPadding`. Cleanest, but changes the gesture's hit region.
2. **Keep it outside and offset the indicator** via `PullToRefreshBox`'s `indicator` parameter with a top offset equal to the app bar height.

**Recommendation: option 1.** It is the arrangement M3 assumes (refresh belongs to the content region, not the chrome) and it avoids hardcoding a bar height. It must be verified against `MainScaffoldTest` and `DashboardRecompositionTest`, both of which exercise this scaffold.

### 4.5 Insets and double-padding

`MainScaffold` currently passes `innerPadding` into `MainNavHost` (lines 146-152), and the screens *additionally* apply `MaterialTheme.spacing.pageTop`. With no `topBar`, `innerPadding.calculateTopPadding()` is just the status bar inset. Once a `topBar` exists it becomes status bar + 64dp, and screens that add `pageTop` on top will read as over-padded.

Every primary screen's top padding must be re-checked: `SleepScreen.kt:99` (`padding(top = spacing.pageTop, …)`), `DashboardScreen.kt:180` (`contentPadding = PaddingValues(top = spacing.pageTop, …)`), `WorkoutsScreen.kt:81`, and the Vitals equivalent. The likely resolution is to drop `pageTop` on screens now sitting under a bar, since M3 already specifies the bar-to-content gap.

---

## 5. Implementation steps

### Step 0 — Propagate the F1 date-switcher gating (independent; ship first or separately)

Pass `enabled = !uiState.isRefreshing` to the `DateSwitcher` in `DashboardScreen.kt:191` and `SleepScreen.kt:107`, matching Vitals and Workouts. Both `uiState` objects already expose the field. See §4.3.2 — this is a live behavioral defect, not an app bar prerequisite, and carries none of this plan's risk.

### Step 1 — App bar infrastructure in `MainScaffold`

1. Resolve the current tab: `TabDestination.all.find { tab -> currentDestination?.hierarchy?.any { it.hasRoute(tab::class) } == true }`. Reuse the `hierarchy` walk already present at lines 105-108 rather than writing a second one.
2. Create `val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()` and attach `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)` to the `Scaffold`.
3. Add the `topBar` slot, rendered only when `showBottomBar` is true:
   ```
   TopAppBar(
       title = { Text(stringResource(tab.labelRes)) },
       scrollBehavior = scrollBehavior,
   )
   ```
4. No new string resources — `TabDestination.labelRes` supplies all five titles.

### Step 2 — Move `PullToRefreshBox` inside the `Scaffold` content

Relocate it below `topBar` so it occupies the content region, and confirm the gesture still triggers from a scrolled-to-top list on all five tabs.

### Step 3 — Reconcile padding

Remove now-duplicated `pageTop` from `SleepScreen`, `DashboardScreen`, `WorkoutsScreen`, and `VitalsScreen`. Verify the first card's top gap matches M3's bar-to-content spacing in each.

### Step 4 — Standardize header pinning

Move the `DateSwitcher` in `VitalsScreen` and `WorkoutsScreen` from the fixed header into the scroll region so all five destinations behave identically. §4.3.1 establishes this is safe: the pinning is an artifact of call-site placement, not a design decision.

**Preserve the gating while moving the call.** `ScreenHeaderSection`'s only real function is the `isLoading → isDisabled` seam, and that must survive relocation — the simplest form is to drop the wrapper and pass `enabled = !uiState.isRefreshing` directly, which is what step 0 does on the other two screens anyway. After that, all four `DateSwitcher` call sites are identical, and `ScreenHeaderSection` has no remaining users and can be deleted (`codegraph sync` after removal, per `.claude/CLAUDE.md`).

### Step 5 — Accessibility

1. Confirm the app bar title is exposed as a heading landmark, so TalkBack users get "where am I" on every primary destination — currently unavailable anywhere.
2. Confirm the collapsing bar does not trap focus when collapsed; `enterAlwaysScrollBehavior` restores on scroll-up, but verify with TalkBack's swipe navigation.
3. Verify at 200% font scale that a collapsed bar still returns and the title does not clip.

### Step 6 — Detail screens (shared with the parent plan's Phase 5)

The parent audit's Phase 5 retrofits `pinnedScrollBehavior` to the six detail screens. If both plans are approved, do that work **here** instead, so all app bar changes land in one reviewable commit; if only the parent plan is approved, it stays there. Either way it happens exactly once — this is a scheduling note, not duplicated scope.

---

## 6. Verification

- `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`
- `./gradlew lintRelease` after the change
- Existing instrumented tests must stay green — `MainScaffoldTest`, `DashboardScreenTest`, `DashboardRecompositionTest` all construct this scaffold and will catch structural regressions
- Manual: pull-to-refresh on all five tabs; scroll-down/scroll-up bar collapse and return; rotate on each tab; 200% font scale; TalkBack landmark navigation; navigate primary → detail → back and confirm exactly one app bar is present at every step

---

## 7. Effort & risk

| Step | Effort | Risk |
|---|---|---|
| 0 — F1 gating propagation | 0.1 d | Low — 2 call sites, state already present; independent of everything else |
| 1 — App bar infrastructure | 0.5 d | Low |
| 2 — `PullToRefreshBox` relocation | 0.5 d | **Medium–High** — touches the gesture path for every screen; instrumented tests are the safety net |
| 3 — Padding reconciliation | 0.5 d | Low–Medium — visual-only, but touches all five screens |
| 4 — Header pinning standardization | 0.4 d | Low–Medium — downgraded from Medium; §4.3.1 establishes the pinning is incidental |
| 5 — Accessibility verification | 0.25 d | Low |
| 6 — Detail-screen `scrollBehavior` | 0.25 d | Low |

**Total: ~2.5 days.**

**Highest risk:** step 2. `PullToRefreshBox` currently wraps everything including the navigation scaffold; moving it inside changes which region owns the gesture. Land it as its own commit so it can be reverted independently of the app bar itself.

Step 4's risk was **downgraded after investigation**. The concern was that Vitals/Workouts pinning might be deliberate behavior users rely on, which would have forced standardizing the other way — pinning all five, materially more work. §4.3.1 rules that out: `ScreenHeaderSection` contains no pinning logic, the only comment on the code addresses gating rather than layout, no test asserts pinned behavior, and git history is uninformative because every screen landed in the same root commit. The one thing that must survive step 4 is the loading gate, which step 0 generalizes anyway.

---

## 8. Recommendation

**Do it, with the design in §4.** The strongest argument is not the M3 checkbox — it is that no screen in the app currently states its own name, and the `DateSwitcher` already behaves inconsistently across the five tabs. A hoisted app bar fixes the naming gap, gives accessibility a landmark it does not have today, and provides a single place to resolve the pinning inconsistency instead of five.

The honest cost: ~64dp on every primary screen (recovered on scroll-down by `enterAlwaysScrollBehavior`), and a `PullToRefreshBox` relocation that carries real regression risk on a gesture users hit constantly.

If only part of this is wanted, **steps 1 and 3 alone deliver most of the value** — titles and correct spacing — and can ship without touching pull-to-refresh, provided the refresh indicator overlapping the bar is accepted as a known cosmetic issue until step 2 follows.

---

## 9. Open questions

1. **Small vs. large app bar for Settings.** Small everywhere is recommended for sibling consistency; Settings is the one screen where `LargeTopAppBar` would be idiomatic.
2. ~~**Is the Vitals/Workouts `DateSwitcher` pinning deliberate?**~~ **Answered — see §4.3.1.** It is incidental: an artifact of where the `ScreenHeaderSection` call sits relative to the scroll region, adopted for loading-gate reasons that have nothing to do with layout. Step 4 standardizes on scroll-away. The investigation also surfaced §4.3.2, a partially applied fix now scheduled as step 0.
3. **Should Settings' search field become an M3 `SearchBar`?** It is currently an `OutlinedTextField` inside the scroll region. Related, and it interacts with app bar placement, but it is a separate component migration and is **not** in this plan's scope.
4. **Any app bar actions?** The design above adds title-only bars. Overflow candidates exist (Dashboard's "Customize" is currently a `FilledTonalButton` at the bottom of the list; Settings could surface "Resync"), but moving them is a separate interaction change and is excluded here.
