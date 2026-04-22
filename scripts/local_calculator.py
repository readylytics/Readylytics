import sqlite3
import pandas as pd
import numpy as np
from datetime import datetime, timedelta
from dataclasses import dataclass
from typing import Optional, Union

# ---------------------- DATA MODELS ----------------------


@dataclass(frozen=True)
class HrvStatistics:
    baseline: float
    trend: float
    residual: float


@dataclass(frozen=True)
class Baselines:
    hrv_baseline: float
    hrv_sigma: float
    rhr_baseline: float


@dataclass(frozen=True)
class SleepMetrics:
    hrv: float
    rhr: float
    tst: float
    eff: float
    p_deep: float
    p_rem: float


@dataclass(frozen=True)
class DailyScores:
    date: str
    readiness: float
    strain: float
    sleep: float
    hrv: float
    rhr: float


# ---------------------- MAIN CLASS ----------------------


class HealthConnectScorer:

    def __init__(
        self,
        db_path: str,
        sleep_goal_hrs: float = 8.0,
        user_fitness_level: float = 35.0,
    ) -> None:
        self.db_path: str = db_path
        self.sleep_goal: float = sleep_goal_hrs * 3600.0
        self.max_hr: float = 190.0
        self.user_fitness_level: float = user_fitness_level
        self.sleep_windows: Optional[list[tuple[int, int]]] = None

    def _get_connection(self) -> sqlite3.Connection:
        return sqlite3.connect(self.db_path)

    # ---------------------- PURE FORMULA FUNCTIONS ----------------------

    @staticmethod
    def calc_duration_score(
        tst_seconds: float, goal_seconds: float, efficiency: float
    ) -> float:
        """Calculates the S'_dur based on Total Sleep Time and Goal."""
        return float(min(100.0, (tst_seconds / goal_seconds) * 100) * efficiency)

    @staticmethod
    def calc_architecture_score(p_deep: float, p_rem: float) -> float:
        """Calculates the S'_arch based on Deep and REM sleep percentages."""
        return float(
            0.5 * (min(100.0, (p_deep / 0.20) * 100) + min(100.0, (p_rem / 0.20) * 100))
        )

    @staticmethod
    def calc_restoration_score(
        z_hrv: float, rhr_baseline: float, rhr_night: float
    ) -> float:
        """Calculates the S'_rest based on HRV Z-Score and RHR ratio."""
        rhr_ratio = rhr_baseline / rhr_night if rhr_night > 0 else 1.0
        score = 0.5 * (50 + 25 * z_hrv) + 0.5 * (min(1.0, rhr_ratio) * 100)
        return float(np.clip(score, 0, 100))

    @staticmethod
    def calc_sleep_score(s_dur: float, s_arch: float, s_rest: float) -> float:
        """Calculates the final composite Sleep Score (SS)."""
        return float((0.50 * s_dur) + (0.25 * s_arch) + (0.25 * s_rest))

    @staticmethod
    def calc_load_score(strain_ratio: float) -> float:
        """Calculates the S_load based on the ATL/CTL Strain Ratio."""
        if strain_ratio < 0.8:
            return 40.0
        elif strain_ratio <= 1.2:
            return 100.0
        elif strain_ratio <= 1.5:
            return float(100.0 - (strain_ratio - 1.2) * 200.0)
        else:
            return 40.0

    @staticmethod
    def calc_readiness_score(s_rest: float, s_sleep: float, s_load: float) -> float:
        """Calculates the final Readiness Score (RS)."""
        return float((0.4 * s_rest) + (0.3 * s_sleep) + (0.3 * s_load))

    @staticmethod
    def calc_rhr_baseline(rhr_vals: list[float]) -> float:
        """Calculates the baseline Resting Heart Rate (median of collected values)."""
        if not rhr_vals:
            return 60.0
        return float(np.median(rhr_vals))

    @staticmethod
    def calc_hrv_statistics(daily_hrv: list[float]) -> Optional[HrvStatistics]:
        """Calculates the 30-day baseline, 7-day trend, and residual for HRV."""
        if len(daily_hrv) < 7:
            return None

        series = pd.Series(daily_hrv)
        baseline = float(series.rolling(30, min_periods=1).median().iloc[-1])
        trend = float(series.rolling(7, min_periods=1).mean().iloc[-1])
        residual = float(series.iloc[-1] - trend)

        return HrvStatistics(baseline=baseline, trend=trend, residual=residual)

    @staticmethod
    def calc_hrv_sigma(trend: float) -> float:
        """Calculates the standard deviation/sigma for HRV based on a 15% CV rule."""
        return max(trend * 0.15, 1e-6)

    # ---------------------- SLEEP WINDOWS ----------------------
    def get_sleep_windows(
        self, conn: sqlite3.Connection, start_ts: int, end_ts: int
    ) -> list[tuple[int, int]]:
        df = pd.read_sql_query(
            f"""
            SELECT stage_start_time as start_time,
                stage_end_time as end_time
            FROM sleep_stages_table
            WHERE stage_start_time >= {start_ts}
            AND stage_end_time <= {end_ts}
        """,
            conn,
        )
        if not df.empty:
            return list(zip(df["start_time"], df["end_time"]))
        return []

    def _set_sleep_windows(self, target_dt: datetime) -> None:
        start_dt = target_dt - timedelta(days=30)
        start_ts = int(start_dt.timestamp() * 1000)
        end_ts = int(target_dt.timestamp() * 1000)

        with self._get_connection() as conn:
            self.sleep_windows = self.get_sleep_windows(conn, start_ts, end_ts)

    # ---------------------- BASELINES ----------------------
    def compute_baselines(self) -> Optional[Baselines]:
        if self.sleep_windows is None:
            return None

        daily_hrv: list[float] = []
        rhr_vals: list[float] = []

        with self._get_connection() as conn:
            for s, e in self.sleep_windows:
                # Fetch HRV
                hrv_df = pd.read_sql_query(
                    f"SELECT heart_rate_variability_millis as hrv FROM heart_rate_variability_rmssd_record_table WHERE time BETWEEN {s} AND {e}",
                    conn,
                )
                if not hrv_df.empty:
                    daily_hrv.append(float(hrv_df["hrv"].mean()))

                # Fetch RHR
                hr_df = pd.read_sql_query(
                    f"SELECT beats_per_minute as bpm FROM heart_rate_record_series_table WHERE epoch_millis BETWEEN {s} AND {e}",
                    conn,
                )
                if not hr_df.empty:
                    rhr_vals.extend(hr_df["bpm"].tolist())

        # Apply Pure Functions
        stats = self.calc_hrv_statistics(daily_hrv)

        if stats is None:
            return None  # Still in the 7-day calibration phase

        sigma = self.calc_hrv_sigma(stats.trend)
        rhr_baseline = self.calc_rhr_baseline(rhr_vals)

        return Baselines(
            hrv_baseline=stats.baseline, hrv_sigma=sigma, rhr_baseline=rhr_baseline
        )

    # ---------------------- TRIMP ----------------------
    def calculate_trimp(self, df: pd.DataFrame) -> float:
        if df.empty:
            return 0.0

        df = df.sort_values("epoch_millis")
        df["delta"] = df["epoch_millis"].diff() / 1000
        df["delta"] = df["delta"].clip(lower=1, upper=300)
        df["delta"] = df["delta"].fillna(1)

        def zone(hr: float) -> int:
            pct = hr / self.max_hr
            if pct >= 0.9:
                return 5
            if pct >= 0.8:
                return 4
            if pct >= 0.7:
                return 3
            if pct >= 0.6:
                return 2
            return 1

        df["zone"] = df["hr"].apply(zone)
        return float((df["zone"] * df["delta"]).sum())

    def rolling_trimp(self, target_dt: datetime, days: int) -> float:
        start_dt = target_dt - timedelta(days=days)
        with self._get_connection() as conn:
            df = pd.read_sql_query(
                f"""
                SELECT beats_per_minute as hr, epoch_millis
                FROM heart_rate_record_series_table
                WHERE epoch_millis BETWEEN {int(start_dt.timestamp()*1000)} AND {int(target_dt.timestamp()*1000)}
            """,
                conn,
            )
        return self.calculate_trimp(df) / days

    # ---------------------- CTL ----------------------
    def compute_ctl(self, target_dt: datetime, tau: int = 42) -> float:
        start_dt = target_dt - timedelta(days=60)

        with self._get_connection() as conn:
            df = pd.read_sql_query(
                f"""
                SELECT beats_per_minute as hr, epoch_millis
                FROM heart_rate_record_series_table
                WHERE epoch_millis BETWEEN {int(start_dt.timestamp()*1000)} AND {int(target_dt.timestamp()*1000)}
            """,
                conn,
            )

        if df.empty:
            return self.user_fitness_level

        df["date"] = pd.to_datetime(df["epoch_millis"], unit="ms").dt.date
        daily = df.groupby("date").apply(lambda x: self.calculate_trimp(x))
        days_of_data = len(daily)

        if days_of_data < 7:
            return self.user_fitness_level
        elif days_of_data < 21:
            return float(daily.mean())
        else:
            alpha = 2.0 / (tau + 1.0)
            ctl = float(daily.iloc[:7].mean())
            for val in daily.iloc[7:]:
                ctl = alpha * val + (1 - alpha) * ctl
            return float(ctl)

    # ---------------------- SLEEP METRICS ----------------------
    def get_sleep_metrics(self, target_dt: datetime) -> Optional[SleepMetrics]:
        end_ts = int(target_dt.timestamp() * 1000)
        start_ts = end_ts - 86400000

        with self._get_connection() as conn:
            stages_df = pd.read_sql_query(
                f"""
                SELECT stage_type as stage,
                    stage_start_time as start_time,
                    stage_end_time as end_time,
                    (stage_end_time - stage_start_time)/1000.0 as duration_sec
                FROM sleep_stages_table
                WHERE stage_start_time >= {start_ts}
                AND stage_end_time <= {end_ts + 3600000}
            """,
                conn,
            )

            if stages_df.empty:
                return None

            sleep_start = int(stages_df["start_time"].min())
            sleep_end = int(stages_df["end_time"].max())

            hr_df = pd.read_sql_query(
                f"SELECT beats_per_minute as bpm FROM heart_rate_record_series_table WHERE epoch_millis BETWEEN {sleep_start} AND {sleep_end}",
                conn,
            )

            hrv_df = pd.read_sql_query(
                f"SELECT heart_rate_variability_millis as hrv FROM heart_rate_variability_rmssd_record_table WHERE time BETWEEN {sleep_start} AND {sleep_end}",
                conn,
            )

        if hr_df.empty or hrv_df.empty:
            return None

        tst = float(
            stages_df[stages_df["stage"].isin([2, 4, 5, 6])]["duration_sec"].sum()
        )
        total = float(stages_df["duration_sec"].sum())

        return SleepMetrics(
            hrv=float(hrv_df["hrv"].mean()),
            rhr=float(hr_df["bpm"].mean()),
            tst=tst,
            eff=tst / total if total > 0 else 0.0,
            p_deep=(
                float(stages_df[stages_df["stage"] == 5]["duration_sec"].sum() / tst)
                if tst
                else 0.0
            ),
            p_rem=(
                float(stages_df[stages_df["stage"] == 6]["duration_sec"].sum() / tst)
                if tst
                else 0.0
            ),
        )

    # ---------------------- MAIN ----------------------
    def calculate_scores(self, target_date: str) -> Union[DailyScores, str]:
        target_dt = datetime.strptime(target_date, "%Y-%m-%d")

        self._set_sleep_windows(target_dt)
        assert self.sleep_windows is not None, "Sleep windows not set"

        baselines = self.compute_baselines()
        if baselines is None:
            return "Calibration phase"

        sleep = self.get_sleep_metrics(target_dt)
        if not sleep:
            return "No sleep data"

        # 1. Fetch Workload Data
        atl = self.rolling_trimp(target_dt, 7)
        ctl = self.compute_ctl(target_dt)
        strain = atl / ctl if ctl else 1.0

        # 2. Calculate Sub-Scores via Pure Functions
        z_score = (sleep.hrv - baselines.hrv_baseline) / baselines.hrv_sigma

        s_rest = self.calc_restoration_score(z_score, baselines.rhr_baseline, sleep.rhr)
        s_arch = self.calc_architecture_score(sleep.p_deep, sleep.p_rem)
        s_dur = self.calc_duration_score(sleep.tst, self.sleep_goal, sleep.eff)
        s_load = self.calc_load_score(strain)

        # 3. Calculate Final Composites
        sleep_score = self.calc_sleep_score(s_dur, s_arch, s_rest)
        readiness_raw = self.calc_readiness_score(s_rest, sleep_score, s_load)

        return DailyScores(
            date=target_date,
            readiness=round(readiness_raw, 0),
            strain=round(strain, 2),
            sleep=round(sleep_score, 0),
            hrv=round(sleep.hrv, 0),
            rhr=round(sleep.rhr, 0),
        )


if __name__ == "__main__":
    scorer = HealthConnectScorer("health_connect_export.db")
    result = scorer.calculate_scores("2026-04-21")
    print(result)
