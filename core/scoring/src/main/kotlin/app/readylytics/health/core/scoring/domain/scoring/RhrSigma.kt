package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.scoring.ScoringConstants

/**
 * Single place the RHR sigma floor is applied. Floor is applied once, where a sigma is computed
 * from a population (live or backfill). Persisted/frozen sigmas are read back as stored, never
 * re-floored, so a frozen day reproduces the sigma it was scored against.
 */
internal fun Float.flooredRhrSigma(): Float = coerceAtLeast(ScoringConstants.Restoration.RHR_MIN_SIGMA_BPM)
