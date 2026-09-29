from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd
import pyarrow.parquet as pq


# Все настройки исследования собраны здесь. Скрипт запускается без аргументов.
DATES = ["2026-03-19", "2026-03-20", "2026-03-21"]
TICK_SIZE = 0.1
BOOK_LEVELS = 20

# 2
WALL_RATIO = 3.0

# 10
MIN_WALL_VOLUME = 3.0

# 10
MIN_WALL_AGE_SECONDS = 3.0
MIN_PRESENCE_SHARE = 0.80

MIN_RETAINED_VOLUME_SHARE = 0.50
MISSING_GRACE_SECONDS = 0.50

# 5
MIN_DISTANCE_TICKS = 1


# 15
MAX_DISTANCE_TICKS = 7

# за сколкьо секунд...
APPROACH_WINDOW_SECONDS = 3.0
APPROACH_LOOKBACK_TOLERANCE_SECONDS = 0.50
# ... должно наблюдаться движение цены в нужную сторону на заданное число тиков
MIN_APPROACH_TICKS = 4.0
MAX_THIN_VOLUME_RATIO = 0.50
MOVED_AWAY_TICKS = 3.0

CONTROL_MIN_AGE_SECONDS = 0.50
CONTROL_MAX_AGE_SECONDS = 2.00
OUTCOME_WINDOW_SECONDS = 60.0
MAX_DATA_GAP_SECONDS = 1.0
MAKER_FEE_BPS_SCENARIOS = [0.0, 0.5, 1.0]

BATCH_SIZE = 100_000
PRESENCE_CREDIT_CAP_SECONDS = 0.25
VOLUME_SAMPLE_STRIDE = 20
NO_WALL_SAMPLE_STRIDE = 20
NO_WALL_RANDOM_SEED = 42

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR.parents[1]
DATA_DIR = PROJECT_DIR / "data" / "orderbook"

EVENTS_PATH = SCRIPT_DIR / "events.csv"
CONTROLS_PATH = SCRIPT_DIR / "controls.csv"
NO_WALL_CONTROLS_PATH = SCRIPT_DIR / "no_wall_controls.csv"
SUMMARY_PATH = SCRIPT_DIR / "summary.csv"
FINDINGS_PATH = SCRIPT_DIR / "FINDINGS.md"
VOLUME_DISTRIBUTION_PATH = SCRIPT_DIR / "volume_distribution.csv"

PRICE_COLUMNS = {
    "BID": [f"bid_price_{level}" for level in range(1, BOOK_LEVELS + 1)],
    "ASK": [f"ask_price_{level}" for level in range(1, BOOK_LEVELS + 1)],
}
QTY_COLUMNS = {
    "BID": [f"bid_qty_{level}" for level in range(1, BOOK_LEVELS + 1)],
    "ASK": [f"ask_qty_{level}" for level in range(1, BOOK_LEVELS + 1)],
}
BOOK_COLUMNS = ["datetime"]
for side in ["BID", "ASK"]:
    BOOK_COLUMNS.extend(PRICE_COLUMNS[side])
    BOOK_COLUMNS.extend(QTY_COLUMNS[side])


@dataclass
class WallState:
    """Хранит только уже известную историю кандидата на фиксированной цене."""

    first_seen_ns: int
    last_seen_ns: int
    present_ns: int
    confirmed_volume: float | None = None
    wall_event_emitted: bool = False
    control_event_emitted: bool = False


def read_batches(date):
    """Читает дневной TOP20 батчами, чтобы ограничить потребление памяти."""
    parquet_file = pq.ParquetFile(DATA_DIR / f"{date}.parquet")
    for batch in parquet_file.iter_batches(columns=BOOK_COLUMNS, batch_size=BATCH_SIZE):
        yield batch.to_pandas()


def price_key(price):
    """Преобразует float-цену в целочисленный номер tick для ключа словаря."""
    return int(round(price / TICK_SIZE))


def update_wall_state(states, key, now_ns):
    """Обновляет online persistence и начинает новый эпизод после длинного пропуска."""
    grace_ns = int(MISSING_GRACE_SECONDS * 1_000_000_000)
    state = states.get(key)
    if state is None or now_ns - state.last_seen_ns > grace_ns:
        state = WallState(now_ns, now_ns, 0)
        states[key] = state
        return state

    interval_ns = now_ns - state.last_seen_ns
    credit_cap_ns = int(PRESENCE_CREDIT_CAP_SECONDS * 1_000_000_000)
    state.present_ns += min(interval_ns, credit_cap_ns)
    state.last_seen_ns = now_ns
    return state


def state_metrics(state, now_ns):
    """Считает реальный возраст и time-weighted долю присутствия кандидата."""
    age_ns = max(0, now_ns - state.first_seen_ns)
    age_seconds = age_ns / 1_000_000_000.0
    if age_ns == 0:
        return age_seconds, 0.0
    return age_seconds, min(1.0, state.present_ns / age_ns)


def distance_bucket(distance_ticks):
    """Сохраняет точное расстояние для честного matching walls и controls."""
    return str(distance_ticks)


def approach_bucket(approach_ticks):
    """Группирует силу приближения без подбора большого числа коэффициентов."""
    if approach_ticks < 4.0:
        return "2-3"
    if approach_ticks < 6.0:
        return "4-5"
    return "6+"


def find_past_touch(
    now_ns,
    side,
    combined_times,
    combined_best_bid,
    combined_best_ask,
):
    """Находит последний известный touch не позже начала approach-окна."""
    target_ns = now_ns - int(APPROACH_WINDOW_SECONDS * 1_000_000_000)
    index = int(np.searchsorted(combined_times, target_ns, side="right")) - 1
    if index < 0:
        return None

    age_from_target = target_ns - int(combined_times[index])
    tolerance_ns = int(APPROACH_LOOKBACK_TOLERANCE_SECONDS * 1_000_000_000)
    if age_from_target > tolerance_ns:
        return None
    if side == "BID":
        return float(combined_best_bid[index])
    return float(combined_best_ask[index])


def evaluate_signal(
    side,
    level_index,
    wall_price,
    wall_volume,
    wall_ratio,
    prices,
    quantities,
    best_bid,
    best_ask,
    past_touch,
):
    """Проверяет расстояние, приближение, тонкость и потенциальную дистанцию."""
    if past_touch is None:
        return None

    if side == "BID":
        raw_distance = (best_bid - wall_price) / TICK_SIZE
        past_distance = (past_touch - wall_price) / TICK_SIZE
        entry_price = best_ask
        target_price = wall_price + TICK_SIZE
        gross_edge = entry_price - target_price
    else:
        raw_distance = (wall_price - best_ask) / TICK_SIZE
        past_distance = (wall_price - past_touch) / TICK_SIZE
        entry_price = best_bid
        target_price = wall_price - TICK_SIZE
        gross_edge = target_price - entry_price

    distance_ticks = int(round(raw_distance))
    if abs(raw_distance - distance_ticks) > 1e-5:
        return None
    if distance_ticks < MIN_DISTANCE_TICKS or distance_ticks > MAX_DISTANCE_TICKS:
        return None

    approach_ticks = past_distance - raw_distance
    if approach_ticks < MIN_APPROACH_TICKS - 1e-9:
        return None

    thin_volume = float(np.nansum(quantities[:level_index]))
    thin_volume_ratio = thin_volume / wall_volume if wall_volume > 0.0 else np.inf
    if thin_volume_ratio > MAX_THIN_VOLUME_RATIO:
        return None

    gross_edge_ticks = gross_edge / TICK_SIZE
    if gross_edge_ticks <= 0.0:
        return None

    signal = {
        "wallPrice": wall_price,
        "wallVolume": wall_volume,
        "wallRatio": wall_ratio,
        "distanceTicks": distance_ticks,
        "distanceBucket": distance_bucket(distance_ticks),
        "approachTicks": float(approach_ticks),
        "approachBucket": approach_bucket(approach_ticks),
        "thinVolume": thin_volume,
        "thinVolumeRatio": thin_volume_ratio,
        "entryPrice": entry_price,
        "targetPrice": target_price,
        "grossEdgeTicks": gross_edge_ticks,
        "signalBestBid": best_bid,
        "signalBestAsk": best_ask,
    }

    # Комиссионные сценарии — только верхняя граница при двух условных maker fills.
    for fee_bps in MAKER_FEE_BPS_SCENARIOS:
        fee_usd = (entry_price + target_price) * fee_bps / 10_000.0
        fee_ticks = fee_usd / TICK_SIZE
        suffix = fee_suffix(fee_bps)
        signal[f"roundTripFee{suffix}Ticks"] = fee_ticks
        signal[f"netEdgeIfHit{suffix}Ticks"] = gross_edge_ticks - fee_ticks
    return signal


def fee_suffix(fee_bps):
    """Создает стабильный фрагмент имени CSV-колонки для fee-сценария."""
    return str(fee_bps).replace(".", "_") + "bps"


def make_event(date, event_type, side, timestamp, age_seconds, presence_share, signal):
    """Создает плоскую строку события для CSV и outcome-прохода."""
    event = {
        "date": date,
        "eventType": event_type,
        "side": side,
        "timestamp": timestamp,
        "timestampNs": int(timestamp.value),
        "wallAgeSeconds": age_seconds,
        "presenceShare": presence_share,
    }
    event.update(signal)
    return event


def make_no_wall_signal(
    side,
    distance_ticks,
    prices,
    quantities,
    best_bid,
    best_ask,
    past_touch,
):
    """Создает искусственную цель и отбрасывает моменты с аномальным объемом рядом."""
    if past_touch is None:
        return None

    if side == "BID":
        approach_ticks = (past_touch - best_bid) / TICK_SIZE
        wall_price = best_bid - distance_ticks * TICK_SIZE
        target_price = wall_price + TICK_SIZE
        entry_price = best_ask
        gross_edge = entry_price - target_price
    else:
        approach_ticks = (best_ask - past_touch) / TICK_SIZE
        wall_price = best_ask + distance_ticks * TICK_SIZE
        target_price = wall_price - TICK_SIZE
        entry_price = best_bid
        gross_edge = target_price - entry_price

    if approach_ticks < MIN_APPROACH_TICKS - 1e-9:
        return None

    finite_qty = quantities[np.isfinite(quantities) & (quantities > 0.0)]
    if finite_qty.size == 0:
        return None
    median_qty = float(np.median(finite_qty))
    anomalous = (quantities >= MIN_WALL_VOLUME) & (
        quantities >= WALL_RATIO * median_qty
    )
    near_anchor = np.abs(prices - wall_price) <= TICK_SIZE + 1e-8
    if np.any(anomalous & near_anchor):
        return None

    gross_edge_ticks = gross_edge / TICK_SIZE
    if gross_edge_ticks <= 0.0:
        return None

    signal = {
        "wallPrice": wall_price,
        "wallVolume": 0.0,
        "wallRatio": 0.0,
        "distanceTicks": distance_ticks,
        "distanceBucket": distance_bucket(distance_ticks),
        "approachTicks": float(approach_ticks),
        "approachBucket": approach_bucket(approach_ticks),
        "thinVolume": np.nan,
        "thinVolumeRatio": np.nan,
        "entryPrice": entry_price,
        "targetPrice": target_price,
        "grossEdgeTicks": gross_edge_ticks,
        "signalBestBid": best_bid,
        "signalBestAsk": best_ask,
    }
    for fee_bps in MAKER_FEE_BPS_SCENARIOS:
        fee_usd = (entry_price + target_price) * fee_bps / 10_000.0
        fee_ticks = fee_usd / TICK_SIZE
        suffix = fee_suffix(fee_bps)
        signal[f"roundTripFee{suffix}Ticks"] = fee_ticks
        signal[f"netEdgeIfHit{suffix}Ticks"] = gross_edge_ticks - fee_ticks
    return signal


def append_volume_sample(samples, date, side, quantities):
    """Добавляет разреженную выборку объемов для диагностических квантилей."""
    sample = quantities[::VOLUME_SAMPLE_STRIDE].reshape(-1)
    sample = sample[np.isfinite(sample) & (sample > 0.0)]
    if sample.size:
        samples[(date, side)].append(sample)


def detect_day(date):
    """Online находит walls, молодые уровни и простые NO_WALL candidates."""
    wall_events = []
    control_candidates = []
    no_wall_candidates = []
    states_by_side = {"BID": {}, "ASK": {}}
    volume_samples = {(date, "BID"): [], (date, "ASK"): []}
    random = np.random.default_rng(NO_WALL_RANDOM_SEED + int(date[-2:]))

    history_times = np.array([], dtype=np.int64)
    history_best_bid = np.array([], dtype=np.float64)
    history_best_ask = np.array([], dtype=np.float64)
    processed = 0

    for frame in read_batches(date):
        times = pd.DatetimeIndex(frame["datetime"])
        time_ns = times.asi8
        bid_prices = frame[PRICE_COLUMNS["BID"]].to_numpy(dtype=np.float64)
        ask_prices = frame[PRICE_COLUMNS["ASK"]].to_numpy(dtype=np.float64)
        bid_qty = frame[QTY_COLUMNS["BID"]].to_numpy(dtype=np.float64)
        ask_qty = frame[QTY_COLUMNS["ASK"]].to_numpy(dtype=np.float64)
        best_bid = bid_prices[:, 0]
        best_ask = ask_prices[:, 0]

        combined_times = np.concatenate([history_times, time_ns])
        combined_best_bid = np.concatenate([history_best_bid, best_bid])
        combined_best_ask = np.concatenate([history_best_ask, best_ask])

        side_data = {
            "BID": (bid_prices, bid_qty),
            "ASK": (ask_prices, ask_qty),
        }
        for side in ["BID", "ASK"]:
            prices, quantities = side_data[side]
            medians = np.nanmedian(quantities, axis=1)
            safe_medians = np.where(medians > 0.0, medians, np.nan)
            ratios = quantities / safe_medians[:, None]
            candidate_mask = (
                np.isfinite(prices)
                & np.isfinite(quantities)
                & (quantities >= MIN_WALL_VOLUME)
                & (ratios >= WALL_RATIO)
            )
            append_volume_sample(volume_samples, date, side, quantities)

            candidate_rows, candidate_levels = np.nonzero(candidate_mask)
            states = states_by_side[side]
            for row_index, level_index in zip(candidate_rows, candidate_levels):
                now_ns = int(time_ns[row_index])
                wall_price = float(prices[row_index, level_index])
                wall_volume = float(quantities[row_index, level_index])
                wall_ratio = float(ratios[row_index, level_index])
                state = update_wall_state(states, price_key(wall_price), now_ns)
                age_seconds, presence_share = state_metrics(state, now_ns)

                past_touch = find_past_touch(
                    now_ns,
                    side,
                    combined_times,
                    combined_best_bid,
                    combined_best_ask,
                )
                signal = evaluate_signal(
                    side,
                    level_index,
                    wall_price,
                    wall_volume,
                    wall_ratio,
                    prices[row_index],
                    quantities[row_index],
                    float(best_bid[row_index]),
                    float(best_ask[row_index]),
                    past_touch,
                )
                if signal is None:
                    continue

                timestamp = times[row_index]
                if (
                    not state.control_event_emitted
                    and CONTROL_MIN_AGE_SECONDS <= age_seconds <= CONTROL_MAX_AGE_SECONDS
                ):
                    control_candidates.append(
                        make_event(
                            date,
                            "YOUNG_LEVEL",
                            side,
                            timestamp,
                            age_seconds,
                            presence_share,
                            signal,
                        )
                    )
                    state.control_event_emitted = True

                confirmed = (
                    age_seconds >= MIN_WALL_AGE_SECONDS
                    and presence_share >= MIN_PRESENCE_SHARE
                )
                if confirmed and state.confirmed_volume is None:
                    state.confirmed_volume = wall_volume
                retained = (
                    state.confirmed_volume is not None
                    and wall_volume
                    >= state.confirmed_volume * MIN_RETAINED_VOLUME_SHARE
                )
                if confirmed and retained and not state.wall_event_emitted:
                    wall_events.append(
                        make_event(
                            date,
                            "WALL",
                            side,
                            timestamp,
                            age_seconds,
                            presence_share,
                            signal,
                        )
                    )
                    state.wall_event_emitted = True

            # Разреженно берем обычные моменты и случайное расстояние до цели.
            # Никакого matching по волатильности здесь намеренно нет.
            sample_rows = np.arange(len(frame))
            sample_rows = sample_rows[(processed + sample_rows) % NO_WALL_SAMPLE_STRIDE == 0]
            for row_index in sample_rows:
                now_ns = int(time_ns[row_index])
                past_touch = find_past_touch(
                    now_ns,
                    side,
                    combined_times,
                    combined_best_bid,
                    combined_best_ask,
                )
                distance_ticks = int(
                    random.integers(MIN_DISTANCE_TICKS, MAX_DISTANCE_TICKS + 1)
                )
                signal = make_no_wall_signal(
                    side,
                    distance_ticks,
                    prices[row_index],
                    quantities[row_index],
                    float(best_bid[row_index]),
                    float(best_ask[row_index]),
                    past_touch,
                )
                if signal is not None:
                    no_wall_candidates.append(
                        make_event(
                            date,
                            "NO_WALL",
                            side,
                            times[row_index],
                            0.0,
                            0.0,
                            signal,
                        )
                    )

        processed += len(frame)
        print(
            f"  {date}: {processed:,} snapshots, "
            f"walls={len(wall_events)}, young={len(control_candidates)}, "
            f"no-wall={len(no_wall_candidates)}"
        )

        # Храним только небольшой хвост, нужный следующему батчу для approach-окна.
        if len(time_ns):
            keep_seconds = APPROACH_WINDOW_SECONDS + APPROACH_LOOKBACK_TOLERANCE_SECONDS
            keep_after_ns = int(time_ns[-1] - keep_seconds * 1_000_000_000)
            keep_index = int(np.searchsorted(combined_times, keep_after_ns, side="left"))
            history_times = combined_times[keep_index:]
            history_best_bid = combined_best_bid[keep_index:]
            history_best_ask = combined_best_ask[keep_index:]

            grace_ns = int(MISSING_GRACE_SECONDS * 1_000_000_000)
            batch_end_ns = int(time_ns[-1])
            for side in ["BID", "ASK"]:
                states = states_by_side[side]
                expired = [
                    key
                    for key, state in states.items()
                    if batch_end_ns - state.last_seen_ns > grace_ns
                ]
                for key in expired:
                    del states[key]

    return wall_events, control_candidates, no_wall_candidates, volume_samples


def select_matched_controls(wall_events, control_candidates):
    """Выравнивает YOUNG_LEVEL по дню, стороне, расстоянию и приближению."""
    wall_frame = pd.DataFrame(wall_events)
    control_frame = pd.DataFrame(control_candidates)
    if wall_frame.empty or control_frame.empty:
        return []

    group_columns = [
        "date",
        "side",
        "distanceBucket",
        "approachBucket",
    ]
    wall_counts = wall_frame.groupby(group_columns).size()
    control_frame = control_frame.sort_values("timestampNs").reset_index(drop=True)
    selected = []
    for group_key, wall_count in wall_counts.items():
        mask = np.ones(len(control_frame), dtype=bool)
        for column, value in zip(group_columns, group_key):
            mask &= control_frame[column].to_numpy() == value
        candidates = control_frame.loc[mask]
        take = min(int(wall_count), len(candidates))
        if take == 0:
            continue
        indices = np.linspace(0, len(candidates) - 1, num=take, dtype=int)
        selected.extend(candidates.iloc[indices].to_dict("records"))
    return selected


def select_no_wall_controls(wall_events, no_wall_candidates):
    """Случайно и воспроизводимо выбирает NO_WALL под состав wall events."""
    wall_frame = pd.DataFrame(wall_events)
    control_frame = pd.DataFrame(no_wall_candidates)
    if wall_frame.empty or control_frame.empty:
        return []

    group_columns = ["date", "side", "distanceBucket", "approachBucket"]
    wall_counts = wall_frame.groupby(group_columns).size()
    selected = []
    for group_number, (group_key, wall_count) in enumerate(wall_counts.items()):
        mask = np.ones(len(control_frame), dtype=bool)
        for column, value in zip(group_columns, group_key):
            mask &= control_frame[column].to_numpy() == value
        candidates = control_frame.loc[mask]
        take = min(int(wall_count), len(candidates))
        if take == 0:
            continue
        sampled = candidates.sample(
            n=take,
            replace=False,
            random_state=NO_WALL_RANDOM_SEED + group_number,
        )
        selected.extend(sampled.to_dict("records"))
    return selected


def exact_level_quantity(prices, quantities, wall_price):
    """Возвращает видимый объем точной wall price для каждого snapshot."""
    matches = np.isclose(prices, wall_price, atol=1e-8, rtol=0.0)
    return np.where(matches, quantities, 0.0).sum(axis=1)


def initialize_outcome(event):
    """Создает состояние fixed-target наблюдения после сигнала."""
    event["outcome"] = "NONE"
    event["outcomeSeconds"] = np.nan
    event["targetHitWithinWindow"] = False
    event["timeToTargetSeconds"] = np.nan
    event["wallRemoved"] = False
    event["timeToWallRemovalSeconds"] = np.nan
    event["wallRemovedBeforeHit"] = False
    event["targetHitAfterWallRemoved"] = False
    event["secondsFromRemovalToHit"] = np.nan
    event["movedAwayBeforeHit"] = False
    event["timeToMovedAwaySeconds"] = np.nan
    event["maxMovedAwayTicks"] = 0.0
    event["_invalidSinceNs"] = None
    event["_wallRemovalNs"] = None
    event["_lastProcessedNs"] = event["timestampNs"]
    event["_finished"] = False


def complete_event(event, outcome, now_ns):
    """Фиксирует только первый исход события и его задержку от сигнала."""
    event["outcome"] = outcome
    event["outcomeSeconds"] = (
        now_ns - event["timestampNs"]
    ) / 1_000_000_000.0
    event["_finished"] = True


def update_outcome_slice(event, times_ns, bid_prices, ask_prices, bid_qty, ask_qty):
    """Следит за фиксированной целью; wall removal и away сохраняет как признаки."""
    event_ns = event["timestampNs"]
    end_ns = event_ns + int(OUTCOME_WINDOW_SECONDS * 1_000_000_000)
    start_index = int(np.searchsorted(times_ns, event_ns, side="right"))
    end_index = int(np.searchsorted(times_ns, end_ns, side="right"))
    if start_index >= end_index:
        return

    current_times = times_ns[start_index:end_index]
    if event["side"] == "BID":
        current_prices = bid_prices[start_index:end_index]
        current_qty = bid_qty[start_index:end_index]
        touch = current_prices[:, 0]
    else:
        current_prices = ask_prices[start_index:end_index]
        current_qty = ask_qty[start_index:end_index]
        touch = current_prices[:, 0]

    wall_qty = exact_level_quantity(current_prices, current_qty, event["wallPrice"])
    retained_threshold = event["wallVolume"] * MIN_RETAINED_VOLUME_SHARE
    gap_ns = int(MAX_DATA_GAP_SECONDS * 1_000_000_000)
    grace_ns = int(MISSING_GRACE_SECONDS * 1_000_000_000)

    for index in range(len(current_times)):
        now_ns = int(current_times[index])
        if now_ns - event["_lastProcessedNs"] > gap_ns:
            complete_event(event, "DATA_GAP", now_ns)
            return
        event["_lastProcessedNs"] = now_ns

        # У NO_WALL нет реального уровня, поэтому removal для него не определен.
        if event["eventType"] != "NO_WALL":
            visible = wall_qty[index] >= retained_threshold - 1e-12
            if not visible:
                if event["_invalidSinceNs"] is None:
                    event["_invalidSinceNs"] = now_ns
                if (
                    not event["wallRemoved"]
                    and now_ns - event["_invalidSinceNs"] >= grace_ns
                ):
                    removal_ns = event["_invalidSinceNs"]
                    event["wallRemoved"] = True
                    event["_wallRemovalNs"] = removal_ns
                    event["timeToWallRemovalSeconds"] = (
                        removal_ns - event_ns
                    ) / 1_000_000_000.0
                    event["wallRemovedBeforeHit"] = True
            else:
                event["_invalidSinceNs"] = None

        if event["side"] == "BID":
            moved_away = (
                touch[index]
                >= event["signalBestBid"] + MOVED_AWAY_TICKS * TICK_SIZE - 1e-9
            )
            moved_away_ticks = max(
                0.0,
                (touch[index] - event["signalBestBid"]) / TICK_SIZE,
            )
            hit_target = touch[index] <= event["targetPrice"] + 1e-9
        else:
            moved_away = (
                touch[index]
                <= event["signalBestAsk"] - MOVED_AWAY_TICKS * TICK_SIZE + 1e-9
            )
            moved_away_ticks = max(
                0.0,
                (event["signalBestAsk"] - touch[index]) / TICK_SIZE,
            )
            hit_target = touch[index] >= event["targetPrice"] - 1e-9

        event["maxMovedAwayTicks"] = max(
            event["maxMovedAwayTicks"], float(moved_away_ticks)
        )
        if moved_away and not event["movedAwayBeforeHit"]:
            event["movedAwayBeforeHit"] = True
            event["timeToMovedAwaySeconds"] = (
                now_ns - event_ns
            ) / 1_000_000_000.0

        if hit_target:
            event["targetHitWithinWindow"] = True
            event["timeToTargetSeconds"] = (
                now_ns - event_ns
            ) / 1_000_000_000.0
            event["targetHitAfterWallRemoved"] = event["wallRemoved"]
            if event["wallRemoved"]:
                event["secondsFromRemovalToHit"] = (
                    now_ns - event["_wallRemovalNs"]
                ) / 1_000_000_000.0
            complete_event(event, "HIT_TARGET", now_ns)
            return


def add_outcomes(date, events):
    """Вторым streaming-проходом определяет первый исход каждого события дня."""
    day_events = [event for event in events if event["date"] == date]
    if not day_events:
        return
    for event in day_events:
        initialize_outcome(event)

    last_day_ns = None
    for frame in read_batches(date):
        times_ns = pd.DatetimeIndex(frame["datetime"]).asi8
        if len(times_ns) == 0:
            continue
        last_day_ns = int(times_ns[-1])
        batch_end_ns = last_day_ns
        bid_prices = frame[PRICE_COLUMNS["BID"]].to_numpy(dtype=np.float64)
        ask_prices = frame[PRICE_COLUMNS["ASK"]].to_numpy(dtype=np.float64)
        bid_qty = frame[QTY_COLUMNS["BID"]].to_numpy(dtype=np.float64)
        ask_qty = frame[QTY_COLUMNS["ASK"]].to_numpy(dtype=np.float64)

        for event in day_events:
            if event["_finished"] or batch_end_ns <= event["timestampNs"]:
                continue
            # Если следующий батч начинается после длинного неизвестного участка,
            # не превращаем отсутствие snapshots в timeout или успешный hit.
            if (
                int(times_ns[0]) > event["_lastProcessedNs"]
                and int(times_ns[0]) - event["_lastProcessedNs"]
                > int(MAX_DATA_GAP_SECONDS * 1_000_000_000)
            ):
                complete_event(event, "DATA_GAP", int(times_ns[0]))
                continue
            update_outcome_slice(
                event, times_ns, bid_prices, ask_prices, bid_qty, ask_qty
            )
            end_ns = event["timestampNs"] + int(
                OUTCOME_WINDOW_SECONDS * 1_000_000_000
            )
            if not event["_finished"] and batch_end_ns >= end_ns:
                complete_event(event, "TIMEOUT", end_ns)

    # Незавершенное событие у конца дня нельзя выдавать за timeout полного окна.
    for event in day_events:
        if not event["_finished"]:
            complete_event(event, "DATA_GAP", last_day_ns or event["timestampNs"])
        for internal in [
            "_invalidSinceNs",
            "_wallRemovalNs",
            "_lastProcessedNs",
            "_finished",
        ]:
            del event[internal]


def aggregate_volume_distribution(all_samples):
    """Считает квантили по общей разреженной выборке каждого дня и стороны."""
    rows = []
    for (date, side), parts in all_samples.items():
        if not parts:
            continue
        sample = np.concatenate(parts)
        rows.append(
            {
                "date": date,
                "side": side,
                "sampleCount": len(sample),
                "p50": float(np.quantile(sample, 0.50)),
                "p90": float(np.quantile(sample, 0.90)),
                "p95": float(np.quantile(sample, 0.95)),
                "p99": float(np.quantile(sample, 0.99)),
                "max": float(np.max(sample)),
            }
        )
    return pd.DataFrame(rows)


def summarize(events):
    """Считает fixed-target результат и диагностику пути по дням и сторонам."""
    frame = pd.DataFrame(events)
    if frame.empty:
        return pd.DataFrame()

    rows = []
    group_specs = list(frame.groupby(["eventType", "date", "side"]))
    for key, group in frame.groupby(["eventType", "side"]):
        group_specs.append(((key[0], "TOTAL", key[1]), group))

    for (event_type, date, side), group in group_specs:
        outcomes = group["outcome"]
        hit = outcomes == "HIT_TARGET"
        row = {
            "eventType": event_type,
            "date": date,
            "side": side,
            "events": len(group),
            "targetHitShare": hit.mean(),
            "wallRemovedShare": group["wallRemoved"].mean(),
            "wallRemovedBeforeHitShare": group["wallRemovedBeforeHit"].mean(),
            "hitAfterWallRemovedShare": group["targetHitAfterWallRemoved"].mean(),
            "movedAwayBeforeHitShare": group["movedAwayBeforeHit"].mean(),
            "timeoutShare": (outcomes == "TIMEOUT").mean(),
            "dataGapShare": (outcomes == "DATA_GAP").mean(),
            "medianOutcomeSeconds": group["outcomeSeconds"].median(),
            "medianTimeToTargetSeconds": group.loc[
                hit, "timeToTargetSeconds"
            ].median(),
            "medianTimeToWallRemovalSeconds": group.loc[
                group["wallRemoved"], "timeToWallRemovalSeconds"
            ].median(),
            "meanMaxMovedAwayTicks": group["maxMovedAwayTicks"].mean(),
            "meanDistanceTicks": group["distanceTicks"].mean(),
            "meanApproachTicks": group["approachTicks"].mean(),
            "meanGrossEdgeTicks": group["grossEdgeTicks"].mean(),
        }
        for fee_bps in MAKER_FEE_BPS_SCENARIOS:
            suffix = fee_suffix(fee_bps)
            net_column = f"netEdgeIfHit{suffix}Ticks"
            positive = group[net_column] > 0.0
            row[f"positiveNetEdge{suffix}Share"] = positive.mean()
            row[f"hitAndPositive{suffix}Share"] = (hit & positive).mean()
            row[f"meanNetEdgeIfHit{suffix}Ticks"] = group[net_column].mean()
        rows.append(row)
    return pd.DataFrame(rows)


def markdown_table(frame, columns):
    """Формирует небольшую Markdown-таблицу без зависимости tabulate."""
    if frame.empty:
        return "Данных нет."
    lines = [
        "| " + " | ".join(columns) + " |",
        "|" + "|".join(["---"] * len(columns)) + "|",
    ]
    for _, row in frame.iterrows():
        values = []
        for column in columns:
            value = row[column]
            if isinstance(value, (float, np.floating)):
                values.append(f"{value:.4f}")
            else:
                values.append(str(value))
        lines.append("| " + " | ".join(values) + " |")
    return "\n".join(lines)


def build_findings(
    wall_events,
    young_controls,
    no_wall_controls,
    summary,
    volume_distribution,
):
    """Создает короткий отчет без утверждений о fills и реальном PnL."""
    total_rows = summary[summary["date"] == "TOTAL"].copy()

    total_walls = len(wall_events)
    total_young = len(young_controls)
    total_no_wall = len(no_wall_controls)
    if total_walls:
        wall_hit = sum(e["targetHitWithinWindow"] for e in wall_events) / total_walls
        wall_removed = sum(e["wallRemoved"] for e in wall_events) / total_walls
        hit_after_removal = sum(
            e["targetHitAfterWallRemoved"] for e in wall_events
        ) / total_walls
        wall_away = sum(e["movedAwayBeforeHit"] for e in wall_events) / total_walls
        wall_gap = sum(e["outcome"] == "DATA_GAP" for e in wall_events) / total_walls
    else:
        wall_hit = wall_removed = hit_after_removal = wall_away = wall_gap = np.nan
    young_hit = (
        sum(e["targetHitWithinWindow"] for e in young_controls) / total_young
        if total_young
        else np.nan
    )
    no_wall_hit = (
        sum(e["targetHitWithinWindow"] for e in no_wall_controls) / total_no_wall
        if total_no_wall
        else np.nan
    )
    if total_walls and total_no_wall:
        hit_difference = wall_hit - no_wall_hit
        hit_standard_error = np.sqrt(
            wall_hit * (1.0 - wall_hit) / total_walls
            + no_wall_hit * (1.0 - no_wall_hit) / total_no_wall
        )
        hit_half_width = 1.96 * hit_standard_error
    else:
        hit_difference = np.nan
        hit_half_width = np.nan

    if total_walls < 30:
        conclusion = (
            "Событий меньше 30: стартовая конфигурация слишком редкая для решения о "
            "Java backtest. Допустима одна ограниченная проверка чувствительности."
        )
    elif np.isfinite(no_wall_hit) and wall_hit > no_wall_hit + 0.05:
        conclusion = (
            "Walls достигают целевой зоны заметно чаще NO_WALL. Это поддерживает "
            "гипотезу дополнительного магнитного эффекта, но не доказывает "
            "исполнимость maker-цикла."
        )
    else:
        conclusion = (
            "Walls не показывают убедительного преимущества над NO_WALL. "
            "Переход к Java fill backtest по этой конфигурации пока не обоснован."
        )

    total_columns = [
        "eventType",
        "side",
        "events",
        "targetHitShare",
        "wallRemovedShare",
        "hitAfterWallRemovedShare",
        "movedAwayBeforeHitShare",
        "dataGapShare",
        "medianTimeToTargetSeconds",
        "meanMaxMovedAwayTicks",
        "meanGrossEdgeTicks",
    ]
    daily_columns = [
        "eventType",
        "date",
        "side",
        "events",
        "targetHitShare",
        "wallRemovedShare",
        "hitAfterWallRemovedShare",
        "movedAwayBeforeHitShare",
        "dataGapShare",
    ]
    volume_columns = ["date", "side", "p50", "p90", "p95", "p99", "max"]

    return f"""# PRICE_MAGNET: первый event study

## Параметры

- `wallRatio = {WALL_RATIO}`
- `minimumWallAgeSeconds = {MIN_WALL_AGE_SECONDS}`
- `distanceTicks = {MIN_DISTANCE_TICKS}..{MAX_DISTANCE_TICKS}`
- `approach = {MIN_APPROACH_TICKS}+ ticks за {APPROACH_WINDOW_SECONDS}s`
- `maximumThinVolumeRatio = {MAX_THIN_VOLUME_RATIO}`
- `movedAwayTicks = {MOVED_AWAY_TICKS}`
- `outcomeWindowSeconds = {OUTCOME_WINDOW_SECONDS}`

## Выборка

- Wall events: **{total_walls}**.
- YOUNG_LEVEL controls: **{total_young}**, hit rate **{young_hit:.1%}**.
- NO_WALL controls: **{total_no_wall}**, hit rate **{no_wall_hit:.1%}**.
- Wall hit rate: **{wall_hit:.1%}**.
- WALL минус NO_WALL: **{hit_difference:+.1%}**, приблизительный 95% интервал
  **[{hit_difference - hit_half_width:+.1%}; {hit_difference + hit_half_width:+.1%}]**.
- WALL минус YOUNG_LEVEL: **{wall_hit - young_hit:+.1%}**.
- Wall removed during observation: **{wall_removed:.1%}**.
- Target hit after wall removal: **{hit_after_removal:.1%}**.
- Price moved away before hit: **{wall_away:.1%}**.
- Data gap before outcome: **{wall_gap:.1%}**.

{conclusion}

`WALL` против `NO_WALL` — основная метрика эффекта наличия стены. `WALL` против
`YOUNG_LEVEL` отдельно показывает эффект устойчивости уровня. Wall removal является
диагностикой, а не терминальным исходом. Приблизительный интервал hit-rate
оптимистичен, потому что события могут быть зависимы внутри одной рыночной фазы.

## Итог по сторонам

{markdown_table(total_rows, total_columns)}

`targetHitShare` отвечает только на вопрос о достижении зафиксированной цены. Это не
PnL: реальные entry и exit fills не проверялись.

## Дневной разрез

{markdown_table(summary[summary["date"] != "TOTAL"], daily_columns)}

## Распределение объемов

{markdown_table(volume_distribution, volume_columns)}

## Ограничения

- Сигнал использует только прошлое, но outcomes используют будущие snapshots для
  исследовательской оценки.
- Цена могла дойти до стены именно без fill нашего входного maker-ордера.
- Контроли уменьшают влияние дня, стороны, расстояния и momentum, но не сопоставлены
  по волатильности и не устраняют все различия рыночного режима.
- Три дня остаются небольшой in-sample выборкой.
"""


def validate_signals(wall_events, young_controls, no_wall_controls):
    """Проверяет главные инварианты, чтобы ошибка детектора не стала выводом."""
    for event in wall_events:
        assert event["wallAgeSeconds"] >= MIN_WALL_AGE_SECONDS
        assert event["presenceShare"] >= MIN_PRESENCE_SHARE
        assert event["wallRatio"] >= WALL_RATIO
        assert MIN_DISTANCE_TICKS <= event["distanceTicks"] <= MAX_DISTANCE_TICKS
        assert event["approachTicks"] >= MIN_APPROACH_TICKS - 1e-9
        assert event["thinVolumeRatio"] <= MAX_THIN_VOLUME_RATIO + 1e-12
    for event in young_controls:
        assert (
            CONTROL_MIN_AGE_SECONDS
            <= event["wallAgeSeconds"]
            <= CONTROL_MAX_AGE_SECONDS
        )
        assert event["eventType"] == "YOUNG_LEVEL"
    for event in no_wall_controls:
        assert event["eventType"] == "NO_WALL"
        assert MIN_DISTANCE_TICKS <= event["distanceTicks"] <= MAX_DISTANCE_TICKS
        assert event["approachTicks"] >= MIN_APPROACH_TICKS - 1e-9


def save_events(events, path):
    """Сохраняет плоские события в стабильном хронологическом порядке."""
    frame = pd.DataFrame(events)
    if not frame.empty:
        frame = frame.sort_values(["date", "timestampNs", "side"]).reset_index(drop=True)
    frame.to_csv(path, index=False)


def main():
    """Выполняет online-детекцию, matching controls и future-only event study."""
    all_walls = []
    all_control_candidates = []
    all_no_wall_candidates = []
    all_samples = {}

    for date in DATES:
        print(f"Ищем magnet events: {date}")
        walls, controls, no_wall, samples = detect_day(date)
        all_walls.extend(walls)
        all_control_candidates.extend(controls)
        all_no_wall_candidates.extend(no_wall)
        for key, parts in samples.items():
            all_samples.setdefault(key, []).extend(parts)

    selected_controls = select_matched_controls(all_walls, all_control_candidates)
    selected_no_wall = select_no_wall_controls(all_walls, all_no_wall_candidates)
    validate_signals(all_walls, selected_controls, selected_no_wall)
    all_events = all_walls + selected_controls + selected_no_wall

    for date in DATES:
        day_count = sum(event["date"] == date for event in all_events)
        print(f"Считаем outcomes: {date}, событий={day_count}")
        add_outcomes(date, all_events)

    summary = summarize(all_events)
    volume_distribution = aggregate_volume_distribution(all_samples)
    save_events(all_walls, EVENTS_PATH)
    save_events(selected_controls, CONTROLS_PATH)
    save_events(selected_no_wall, NO_WALL_CONTROLS_PATH)
    summary.to_csv(SUMMARY_PATH, index=False)
    volume_distribution.to_csv(VOLUME_DISTRIBUTION_PATH, index=False)
    FINDINGS_PATH.write_text(
        build_findings(
            all_walls,
            selected_controls,
            selected_no_wall,
            summary,
            volume_distribution,
        ),
        encoding="utf-8",
    )

    print(f"Готово: {EVENTS_PATH}")
    print(f"Готово: {CONTROLS_PATH}")
    print(f"Готово: {NO_WALL_CONTROLS_PATH}")
    print(f"Готово: {SUMMARY_PATH}")
    print(f"Готово: {FINDINGS_PATH}")


if __name__ == "__main__":
    main()
