# Heart-rate resync flow design

## Problem

`HeartRateDetailViewModel` debounces Room emissions for 500 ms. A historical
resync can invalidate the query more frequently than that for minutes, so the
flow may never emit and the detail screen can remain loading or stale.

## Design

Replace the debounce with a 500 ms sample window. Sampling retains the latest
query result and emits at a bounded cadence even while invalidations continue.
This preserves the performance goal of avoiding a render for every ingest batch
without requiring a quiet period.

Keep the change inside the ViewModel flow. The repository remains Room-backed,
and no ingestion, schema, scoring, or data-flow behavior changes.

## Verification

Update the burst regression test to assert that rapid invalidations are sampled
to the latest value. Add a sustained-invalidation test that emits more often
than 500 ms and proves the UI leaves loading and continues receiving fresh
heart-rate state before the source becomes quiet.

Run the focused ViewModel tests, then the repository-mandated formatting, unit
test, and release lint tasks.
