from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd
import pyarrow.parquet as pq


# Все настройки исследования собраны здесь. Скрипт запускается без аргументов.
DATES = ["2026-03-19", "2026-03-20", "2026-03-21"]
TICK_SIZE = 0.1
BOOK_LEVELS = 20

WALL_RATIO = 2.0
MIN_WALL_VOLUME = 10.0
MIN_WALL_AGE_SECONDS = 10.0
MIN_PRESENCE_SHARE = 0.80
MIN_RETAINED_VOLUME_SHARE = 0.50
ENTRY_DISTANCE_TICKS = 3
MAX_THIN_VOLUME_RATIO = 0.25
MISSING_GRACE_SECONDS = 0.50
STALE_GAP_SECONDS = 1.0

# Контроль — такой же крупный близкий уровень, но еще без подтвержденной persistence.
CONTROL_MIN_AGE_SECONDS = 0.50
CONTROL_MAX_AGE_SECONDS = 2.00

OUTCOME_HORIZONS_SECONDS = [1, 5, 10, 30, 60]
OUTCOME_WINDOW_SECONDS = 60
BOUNCE_TICKS = 2.0

# Чтение батчами ограничивает память: полный TOP20 за день занимает заметный объем.
BATCH_SIZE = 100_000
VOLUME_SAMPLE_STRIDE = 20
PRESENCE_CREDIT_CAP_SECONDS = 0.25

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR.parents[1]
DATA_DIR = PROJECT_DIR / "data" / "orderbook"

EVENTS_PATH = SCRIPT_DIR / "events.csv"
CONTROLS_PATH = SCRIPT_DIR / "controls.csv"
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
    """Хранит только прошлое одного кандидата на фиксированной цене."""

    first_seen_ns: int
    last_seen_ns: int
    present_ns: int
    confirmed_volume: float | None = None
    wall_event_emitted: bool = False
    control_event_emitted: bool = False


def read_batches(date):
    """Читает TOP20 по частям, чтобы не держать весь дневной стакан в памяти."""
    path = DATA_DIR / f"{date}.parquet"
    parquet_file = pq.ParquetFile(path)
    for batch in parquet_file.iter_batches(columns=BOOK_COLUMNS, batch_size=BATCH_SIZE):
        yield batch.to_pandas()


def price_key(price):
    """Нормализует цену для устойчивого ключа словаря при float-представлении."""
    return int(round(price / TICK_SIZE))


def valid_entry(side, level_index, price, quantity, ratio, prices, quantities):
    """Проверяет близость стены, тонкость уровней перед ней и пассивность цены."""
    best_price = prices[0]
    if side == "BID":
        raw_distance = (best_price - price) / TICK_SIZE
        target_price = price + TICK_SIZE
    else:
        raw_distance = (price - best_price) / TICK_SIZE
        target_price = price - TICK_SIZE

    distance_ticks = int(round(raw_distance))
    if abs(raw_distance - distance_ticks) > 1e-5:
        return None
    if distance_ticks < 1 or distance_ticks > ENTRY_DISTANCE_TICKS:
        return None

    thin_volume = float(np.nansum(quantities[:level_index]))
    thin_volume_ratio = thin_volume / quantity if quantity > 0.0 else np.inf
    if thin_volume_ratio > MAX_THIN_VOLUME_RATIO:
        return None

    # Проверка второй стороны выполняется вызывающим кодом по текущему snapshot.
    return {
        "wallPrice": price,
        "wallVolume": quantity,
        "wallRatio": ratio,
        "distanceTicks": distance_ticks,
        "thinVolume": thin_volume,
        "thinVolumeRatio": thin_volume_ratio,
        "targetPrice": target_price,
    }


def make_event(date, event_type, side, timestamp, mid, age_seconds, presence_share, entry):
    """Создает одну плоскую строку события для CSV и последующего outcome-анализа."""
    return {
        "date": date,
        "eventType": event_type,
        "side": side,
        "timestamp": timestamp,
        "timestampNs": int(timestamp.value),
        "signalMid": float(mid),
        "wallPrice": float(entry["wallPrice"]),
        "wallVolume": float(entry["wallVolume"]),
        "wallRatio": float(entry["wallRatio"]),
        "wallAgeSeconds": float(age_seconds),
        "presenceShare": float(presence_share),
        "distanceTicks": int(entry["distanceTicks"]),
        "thinVolume": float(entry["thinVolume"]),
        "thinVolumeRatio": float(entry["thinVolumeRatio"]),
        "targetPrice": float(entry["targetPrice"]),
    }


def update_candidate_state(states, key, now_ns):
    """Обновляет online-состояние стены и сбрасывает эпизод после длинного пропуска."""
    state = states.get(key)
    grace_ns = int(MISSING_GRACE_SECONDS * 1_000_000_000)

    if state is None or now_ns - state.last_seen_ns > grace_ns:
        state = WallState(
            first_seen_ns=now_ns,
            last_seen_ns=now_ns,
            present_ns=0,
        )
        states[key] = state
        return state

    interval_ns = now_ns - state.last_seen_ns
    credit_cap_ns = int(PRESENCE_CREDIT_CAP_SECONDS * 1_000_000_000)
    state.present_ns += min(interval_ns, credit_cap_ns)
    state.last_seen_ns = now_ns
    return state


def state_metrics(state, now_ns):
    """Возвращает возраст эпизода и time-weighted долю присутствия."""
    age_ns = max(0, now_ns - state.first_seen_ns)
    age_seconds = age_ns / 1_000_000_000.0
    if age_ns == 0:
        return age_seconds, 0.0
    presence_share = min(1.0, state.present_ns / age_ns)
    return age_seconds, presence_share


def append_volume_distribution(rows, date, side, quantities):
    """Сохраняет устойчивые к объему данных квантили на разреженной выборке."""
    sample = quantities[::VOLUME_SAMPLE_STRIDE].reshape(-1)
    sample = sample[np.isfinite(sample) & (sample > 0.0)]
    if sample.size == 0:
        return
    rows.append(
        {
            "date": date,
            "side": side,
            "sampleCount": int(sample.size),
            "p50": float(np.quantile(sample, 0.50)),
            "p75": float(np.quantile(sample, 0.75)),
            "p90": float(np.quantile(sample, 0.90)),
            "p95": float(np.quantile(sample, 0.95)),
            "p99": float(np.quantile(sample, 0.99)),
            "max": float(np.max(sample)),
        }
    )


def detect_day(date):
    """Online находит подтвержденные стены и молодые control levels одного дня."""
    wall_events = []
    control_events = []
    volume_rows = []
    states_by_side = {"BID": {}, "ASK": {}}

    processed = 0
    for frame in read_batches(date):
        times = pd.DatetimeIndex(frame["datetime"])
        time_ns = times.asi8

        side_arrays = {}
        for side in ["BID", "ASK"]:
            prices = frame[PRICE_COLUMNS[side]].to_numpy(dtype=np.float64)
            quantities = frame[QTY_COLUMNS[side]].to_numpy(dtype=np.float64)
            medians = np.nanmedian(quantities, axis=1)
            safe_medians = np.where(medians > 0.0, medians, np.nan)
            ratios = quantities / safe_medians[:, None]
            candidate_mask = (
                np.isfinite(prices)
                & np.isfinite(quantities)
                & (quantities >= MIN_WALL_VOLUME)
                & (ratios >= WALL_RATIO)
            )
            side_arrays[side] = (prices, quantities, ratios, candidate_mask)
            append_volume_distribution(volume_rows, date, side, quantities)

        bid_prices = side_arrays["BID"][0]
        ask_prices = side_arrays["ASK"][0]

        for side in ["BID", "ASK"]:
            prices, quantities, ratios, candidate_mask = side_arrays[side]
            candidate_rows, candidate_levels = np.nonzero(candidate_mask)
            states = states_by_side[side]

            for row_index, level_index in zip(candidate_rows, candidate_levels):
                now_ns = int(time_ns[row_index])
                price = float(prices[row_index, level_index])
                quantity = float(quantities[row_index, level_index])
                ratio = float(ratios[row_index, level_index])
                key = price_key(price)

                state = update_candidate_state(states, key, now_ns)
                age_seconds, presence_share = state_metrics(state, now_ns)

                entry = valid_entry(
                    side,
                    level_index,
                    price,
                    quantity,
                    ratio,
                    prices[row_index],
                    quantities[row_index],
                )
                if entry is None:
                    continue

                best_bid = float(bid_prices[row_index, 0])
                best_ask = float(ask_prices[row_index, 0])
                if side == "BID" and entry["targetPrice"] >= best_ask - 1e-9:
                    continue
                if side == "ASK" and entry["targetPrice"] <= best_bid + 1e-9:
                    continue

                timestamp = times[row_index]
                mid = (best_bid + best_ask) / 2.0

                # Контроль фиксируется до минимального возраста и не использует будущее.
                if (
                    not state.control_event_emitted
                    and CONTROL_MIN_AGE_SECONDS <= age_seconds <= CONTROL_MAX_AGE_SECONDS
                ):
                    control_events.append(
                        make_event(
                            date,
                            "CONTROL",
                            side,
                            timestamp,
                            mid,
                            age_seconds,
                            presence_share,
                            entry,
                        )
                    )
                    state.control_event_emitted = True

                confirmed = (
                    age_seconds >= MIN_WALL_AGE_SECONDS
                    and presence_share >= MIN_PRESENCE_SHARE
                )
                if confirmed and state.confirmed_volume is None:
                    state.confirmed_volume = quantity

                retained = (
                    state.confirmed_volume is not None
                    and quantity >= state.confirmed_volume * MIN_RETAINED_VOLUME_SHARE
                )
                if confirmed and retained and not state.wall_event_emitted:
                    wall_events.append(
                        make_event(
                            date,
                            "WALL",
                            side,
                            timestamp,
                            mid,
                            age_seconds,
                            presence_share,
                            entry,
                        )
                    )
                    state.wall_event_emitted = True

        processed += len(frame)
        print(
            f"  {date}: {processed:,} snapshots, "
            f"wall events={len(wall_events)}, controls={len(control_events)}"
        )

        # Старые состояния больше не могут продолжить тот же эпизод.
        if len(time_ns) > 0:
            batch_end_ns = int(time_ns[-1])
            grace_ns = int(MISSING_GRACE_SECONDS * 1_000_000_000)
            for side in ["BID", "ASK"]:
                states = states_by_side[side]
                expired = [
                    key
                    for key, state in states.items()
                    if batch_end_ns - state.last_seen_ns > grace_ns
                ]
                for key in expired:
                    del states[key]

    return wall_events, control_events, volume_rows


def select_matched_controls(wall_events, control_candidates):
    """Детерминированно выравнивает controls по дню, стороне и расстоянию."""
    selected = []
    wall_frame = pd.DataFrame(wall_events)
    control_frame = pd.DataFrame(control_candidates)
    if wall_frame.empty or control_frame.empty:
        return selected

    group_columns = ["date", "side", "distanceTicks"]
    wall_counts = wall_frame.groupby(group_columns).size()
    control_frame = control_frame.sort_values("timestampNs").reset_index(drop=True)

    for group_key, wall_count in wall_counts.items():
        date, side, distance_ticks = group_key
        mask = (
            (control_frame["date"] == date)
            & (control_frame["side"] == side)
            & (control_frame["distanceTicks"] == distance_ticks)
        )
        candidates = control_frame.loc[mask]
        take = min(int(wall_count), len(candidates))
        if take == 0:
            continue
        indices = np.linspace(0, len(candidates) - 1, num=take, dtype=int)
        selected.extend(candidates.iloc[indices].to_dict("records"))
    return selected


def exact_level_quantity(prices, quantities, target_price):
    """Возвращает видимый объем на точной цене для каждого snapshot окна."""
    matches = np.isclose(prices, target_price, atol=1e-8, rtol=0.0)
    return np.where(matches, quantities, 0.0).sum(axis=1)


def initialize_outcome_state(event):
    """Добавляет внутренние поля для последовательной оценки будущего события."""
    event["maxFavorableTicks60s"] = np.nan
    event["maxAdverseTicks60s"] = np.nan
    event["firstOutcome"] = "NONE"
    event["firstOutcomeSeconds"] = np.nan
    event["wallLifetimeSeconds"] = np.nan
    event["wallLifetimeCensored"] = True
    event["removedBeforeContact"] = False
    event["contactSeconds"] = np.nan
    event["_lastVisibleNs"] = event["timestampNs"]
    event["_invalidSinceNs"] = None
    event["_contacted"] = event["distanceTicks"] <= 1
    event["_finished"] = False
    event["_lastObservedNs"] = event["timestampNs"]
    for horizon in OUTCOME_HORIZONS_SECONDS:
        event[f"midMove{horizon}sTicks"] = np.nan
        event[f"entryMarkout{horizon}sTicks"] = np.nan


def update_event_from_slice(event, times_ns, bid_prices, ask_prices, bid_qty, ask_qty):
    """Обновляет future-only метрики события на одном последовательном куске данных."""
    event_ns = event["timestampNs"]
    end_ns = event_ns + OUTCOME_WINDOW_SECONDS * 1_000_000_000
    start_index = int(np.searchsorted(times_ns, event_ns, side="right"))
    # Для markout нужен первый snapshot не раньше точного горизонта. Обычно он
    # приходит на несколько миллисекунд позже t + 60s, поэтому берем еще одну строку.
    end_index = int(np.searchsorted(times_ns, end_ns, side="left"))
    if end_index < len(times_ns):
        end_index += 1
    if start_index >= end_index:
        return

    current_times = times_ns[start_index:end_index]
    current_bid_prices = bid_prices[start_index:end_index]
    current_ask_prices = ask_prices[start_index:end_index]
    current_bid_qty = bid_qty[start_index:end_index]
    current_ask_qty = ask_qty[start_index:end_index]
    event["_lastObservedNs"] = min(int(current_times[-1]), end_ns)

    best_bid = current_bid_prices[:, 0]
    best_ask = current_ask_prices[:, 0]
    mid = (best_bid + best_ask) / 2.0
    direction = 1.0 if event["side"] == "BID" else -1.0
    signed_move_ticks = direction * (mid - event["signalMid"]) / TICK_SIZE

    inside_window = current_times <= end_ns
    window_moves = signed_move_ticks[inside_window]
    if len(window_moves) > 0:
        slice_max = float(np.nanmax(window_moves))
        slice_min = float(np.nanmin(window_moves))
        if np.isnan(event["maxFavorableTicks60s"]):
            event["maxFavorableTicks60s"] = slice_max
            event["maxAdverseTicks60s"] = slice_min
        else:
            event["maxFavorableTicks60s"] = max(
                event["maxFavorableTicks60s"], slice_max
            )
            event["maxAdverseTicks60s"] = min(
                event["maxAdverseTicks60s"], slice_min
            )

    for horizon in OUTCOME_HORIZONS_SECONDS:
        column = f"midMove{horizon}sTicks"
        if not np.isnan(event[column]):
            continue
        target_ns = event_ns + horizon * 1_000_000_000
        horizon_index = int(np.searchsorted(current_times, target_ns, side="left"))
        if horizon_index < len(current_times):
            future_mid = float(mid[horizon_index])
            event[column] = direction * (future_mid - event["signalMid"]) / TICK_SIZE
            if event["side"] == "BID":
                entry_markout = (future_mid - event["targetPrice"]) / TICK_SIZE
            else:
                entry_markout = (event["targetPrice"] - future_mid) / TICK_SIZE
            event[f"entryMarkout{horizon}sTicks"] = entry_markout

    # First-passage сравнивает ожидаемый отскок с фактическим проходом цены за стену.
    bounce_indices = np.flatnonzero(
        inside_window & (signed_move_ticks >= BOUNCE_TICKS - 1e-9)
    )
    if event["side"] == "BID":
        break_indices = np.flatnonzero(
            inside_window & (best_bid < event["wallPrice"] - 1e-9)
        )
        level_qty = exact_level_quantity(
            current_bid_prices, current_bid_qty, event["wallPrice"]
        )
        contact_mask = best_bid <= event["targetPrice"] + 1e-9
    else:
        break_indices = np.flatnonzero(
            inside_window & (best_ask > event["wallPrice"] + 1e-9)
        )
        level_qty = exact_level_quantity(
            current_ask_prices, current_ask_qty, event["wallPrice"]
        )
        contact_mask = best_ask >= event["targetPrice"] - 1e-9

    if event["firstOutcome"] == "NONE":
        bounce_index = int(bounce_indices[0]) if len(bounce_indices) else None
        break_index = int(break_indices[0]) if len(break_indices) else None
        if bounce_index is not None or break_index is not None:
            if break_index is None or (
                bounce_index is not None and bounce_index < break_index
            ):
                outcome_index = bounce_index
                event["firstOutcome"] = "BOUNCE"
            else:
                outcome_index = break_index
                event["firstOutcome"] = "BREAK"
            event["firstOutcomeSeconds"] = (
                int(current_times[outcome_index]) - event_ns
            ) / 1_000_000_000.0

    retained_threshold = event["wallVolume"] * MIN_RETAINED_VOLUME_SHARE
    visible_mask = level_qty >= retained_threshold - 1e-12
    grace_ns = int(MISSING_GRACE_SECONDS * 1_000_000_000)

    # Последовательно проверяем контакт и исчезновение: порядок внутри окна важен.
    for index in range(int(inside_window.sum())):
        now_ns = int(current_times[index])
        if not event["_contacted"] and bool(contact_mask[index]):
            event["_contacted"] = True
            event["contactSeconds"] = (now_ns - event_ns) / 1_000_000_000.0

        if bool(visible_mask[index]):
            event["_lastVisibleNs"] = now_ns
            event["_invalidSinceNs"] = None
            continue

        if event["_invalidSinceNs"] is None:
            event["_invalidSinceNs"] = now_ns
        if now_ns - event["_invalidSinceNs"] >= grace_ns:
            if np.isnan(event["wallLifetimeSeconds"]):
                event["wallLifetimeSeconds"] = (
                    event["_invalidSinceNs"] - event_ns
                ) / 1_000_000_000.0
                event["wallLifetimeCensored"] = False
                if not event["_contacted"]:
                    event["removedBeforeContact"] = True


def add_outcomes(date, events):
    """Вторым streaming-проходом добавляет только будущие метрики событий дня."""
    day_events = [event for event in events if event["date"] == date]
    if not day_events:
        return
    for event in day_events:
        initialize_outcome_state(event)

    for frame in read_batches(date):
        times_ns = pd.DatetimeIndex(frame["datetime"]).asi8
        if len(times_ns) == 0:
            continue
        batch_start_ns = int(times_ns[0])
        batch_end_ns = int(times_ns[-1])

        bid_prices = frame[PRICE_COLUMNS["BID"]].to_numpy(dtype=np.float64)
        ask_prices = frame[PRICE_COLUMNS["ASK"]].to_numpy(dtype=np.float64)
        bid_qty = frame[QTY_COLUMNS["BID"]].to_numpy(dtype=np.float64)
        ask_qty = frame[QTY_COLUMNS["ASK"]].to_numpy(dtype=np.float64)

        for event in day_events:
            if event["_finished"]:
                continue
            event_ns = event["timestampNs"]
            end_ns = event_ns + OUTCOME_WINDOW_SECONDS * 1_000_000_000
            if batch_end_ns <= event_ns:
                continue
            update_event_from_slice(
                event, times_ns, bid_prices, ask_prices, bid_qty, ask_qty
            )
            if batch_end_ns >= end_ns:
                event["_finished"] = True

    # Если стена не исчезла в наблюдаемом окне, lifetime является правой цензурой.
    for event in day_events:
        if np.isnan(event["wallLifetimeSeconds"]):
            observed_seconds = (
                event["_lastObservedNs"] - event["timestampNs"]
            ) / 1_000_000_000.0
            event["wallLifetimeSeconds"] = min(
                OUTCOME_WINDOW_SECONDS, max(0.0, observed_seconds)
            )
            event["wallLifetimeCensored"] = True
        for internal_column in [
            "_lastVisibleNs",
            "_invalidSinceNs",
            "_contacted",
            "_finished",
            "_lastObservedNs",
        ]:
            del event[internal_column]


def summarize(events):
    """Считает компактные агрегаты без сокрытия различий между днями и сторонами."""
    frame = pd.DataFrame(events)
    if frame.empty:
        return pd.DataFrame()

    rows = []
    groups = list(frame.groupby(["eventType", "date", "side"], dropna=False))
    groups.extend(
        ((event_type, "TOTAL", side), group)
        for (event_type, side), group in frame.groupby(["eventType", "side"])
    )

    for group_key, group in groups:
        if len(group_key) == 3:
            event_type, date, side = group_key
        else:
            event_type, date, side = group_key[0], group_key[1], group_key[2]
        first_outcomes = group["firstOutcome"]
        row = {
            "eventType": event_type,
            "date": date,
            "side": side,
            "events": len(group),
            "meanWallAgeSeconds": group["wallAgeSeconds"].mean(),
            "meanWallVolume": group["wallVolume"].mean(),
            "meanWallRatio": group["wallRatio"].mean(),
            "bounceFirstShare": (first_outcomes == "BOUNCE").mean(),
            "breakFirstShare": (first_outcomes == "BREAK").mean(),
            "noOutcomeShare": (first_outcomes == "NONE").mean(),
            "removedBeforeContactShare": group["removedBeforeContact"].mean(),
            "meanWallLifetimeSeconds": group["wallLifetimeSeconds"].mean(),
            "meanMaxFavorableTicks60s": group["maxFavorableTicks60s"].mean(),
            "meanMaxAdverseTicks60s": group["maxAdverseTicks60s"].mean(),
        }
        for horizon in OUTCOME_HORIZONS_SECONDS:
            row[f"meanMidMove{horizon}sTicks"] = group[
                f"midMove{horizon}sTicks"
            ].mean()
            row[f"meanEntryMarkout{horizon}sTicks"] = group[
                f"entryMarkout{horizon}sTicks"
            ].mean()
        rows.append(row)
    return pd.DataFrame(rows)


def markdown_table(frame, columns):
    """Формирует небольшую Markdown-таблицу без дополнительной зависимости tabulate."""
    if frame.empty:
        return "Данных нет."
    header = "| " + " | ".join(columns) + " |"
    separator = "|" + "|".join(["---"] * len(columns)) + "|"
    lines = [header, separator]
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


def build_findings(wall_events, controls, summary, volume_distribution):
    """Создает краткий честный отчет с результатом первого запуска."""
    total_walls = len(wall_events)
    total_controls = len(controls)
    total_rows = summary[summary["date"] == "TOTAL"].copy() if not summary.empty else summary
    comparison_columns = [
        "eventType",
        "side",
        "events",
        "bounceFirstShare",
        "breakFirstShare",
        "meanMidMove5sTicks",
        "meanMidMove30sTicks",
        "removedBeforeContactShare",
    ]
    daily_columns = [
        "eventType",
        "date",
        "side",
        "events",
        "bounceFirstShare",
        "breakFirstShare",
        "meanMidMove5sTicks",
        "meanMidMove30sTicks",
    ]
    volume_columns = ["date", "side", "p50", "p90", "p95", "p99", "max"]

    if total_walls == 0:
        conclusion = (
            "При стартовых порогах подтвержденных событий нет. Это не опровержение "
            "гипотезы, но текущая формализация слишком строгая или такие стены редки."
        )
    elif total_walls < 30:
        conclusion = (
            "Подтвержденных событий меньше 30. Выборка слишком мала для вывода о переносе "
            "стратегии в Java; сначала нужна одна ограниченная проверка чувствительности."
        )
    else:
        conclusion = (
            "Событий достаточно для первого сравнения, но три дня остаются малой "
            "in-sample выборкой. Решение нужно принимать по стабильности между днями и "
            "по отличию от controls, а не только по общему среднему."
        )

    wall_total = total_rows[total_rows["eventType"] == "WALL"]
    if len(wall_total) == 2:
        bid_row = wall_total[wall_total["side"] == "BID"].iloc[0]
        ask_row = wall_total[wall_total["side"] == "ASK"].iloc[0]
        result_reading = (
            "В стартовой конфигурации средний направленный mid move после wall-сигнала "
            f"отрицателен для BID через 5с ({bid_row['meanMidMove5sTicks']:.3f} ticks) "
            f"и 30с ({bid_row['meanMidMove30sTicks']:.3f} ticks), а также для ASK "
            f"через 5с ({ask_row['meanMidMove5sTicks']:.3f} ticks) и 30с "
            f"({ask_row['meanMidMove30sTicks']:.3f} ticks). Это не подтверждает простой "
            "тезис, что persistence сама по себе дает средний отскок. First-passage "
            f"для BID выглядит лучше: bounce был первым в {bid_row['bounceFirstShare']:.1%} "
            f"случаев против break в {bid_row['breakFirstShare']:.1%}. Для ASK bounce "
            f"был первым в {ask_row['bounceFirstShare']:.1%}, break — в "
            f"{ask_row['breakFirstShare']:.1%}. Результат смешанный и пока не дает "
            "основания переносить стратегию в Java."
        )
    else:
        result_reading = "Недостаточно итоговых строк для содержательной интерпретации."

    day_counts = pd.DataFrame(wall_events).groupby("date").size()
    largest_day_share = day_counts.max() / day_counts.sum() if len(day_counts) else np.nan
    concentration_note = (
        f"На самый насыщенный день приходится {largest_day_share:.1%} wall events. "
        "Поэтому общий итог нельзя интерпретировать без дневного разреза."
        if np.isfinite(largest_day_share)
        else ""
    )

    return f"""# PRICE_WALLS: первый event study

## Параметры

- `wallRatio = {WALL_RATIO}`
- `minimumWallVolume = {MIN_WALL_VOLUME} ETH`
- `minimumWallAgeSeconds = {MIN_WALL_AGE_SECONDS}`
- `minimumPresenceShare = {MIN_PRESENCE_SHARE}`
- `entryDistanceTicks = {ENTRY_DISTANCE_TICKS}`
- `maximumThinVolumeRatio = {MAX_THIN_VOLUME_RATIO}`
- `missingGraceSeconds = {MISSING_GRACE_SECONDS}`

## Объем выборки

- Подтвержденных wall events: **{total_walls}**.
- Сопоставленных control events: **{total_controls}**.

{conclusion}

## Итог по сторонам

{markdown_table(total_rows, comparison_columns)}

## Предварительное чтение результата

{result_reading}

{concentration_note}

Положительный `entryMarkout` сам по себе не является доказательством edge: виртуальный
ордер мог не получить fill. Реальный fill возникает именно при движении цены к стене,
поэтому следующий backtest обязан применять trade-based queue model.

## Дневной разрез

{markdown_table(summary[summary["date"] != "TOTAL"], daily_columns)}

`midMove` считается от mid в момент сигнала. `entryMarkout` считается от виртуальной
цены ордера, но не означает, что ордер действительно получил fill.

## Распределение объемов уровней

{markdown_table(volume_distribution, volume_columns)}

## Ограничения интерпретации

- Это event study, а не backtest PnL.
- Controls проверяют пользу persistence относительно молодых крупных уровней, но не
  являются идеальной случайной выборкой всех обычных уровней.
- События одной и той же рыночной фазы могут быть зависимы.
- Три дня нельзя использовать для массового подбора порогов.
"""


def save_event_csv(events, path):
    """Сохраняет события с постоянным порядком строк и без внутренних Python-полей."""
    frame = pd.DataFrame(events)
    if not frame.empty:
        frame = frame.sort_values(["date", "timestampNs", "side"]).reset_index(drop=True)
    frame.to_csv(path, index=False)


def main():
    """Выполняет детекцию, matched-control сравнение и future-only оценку."""
    all_wall_events = []
    all_control_candidates = []
    volume_rows = []

    for date in DATES:
        print(f"Ищем стены: {date}")
        walls, controls, day_volume_rows = detect_day(date)
        all_wall_events.extend(walls)
        all_control_candidates.extend(controls)
        volume_rows.extend(day_volume_rows)

    selected_controls = select_matched_controls(
        all_wall_events, all_control_candidates
    )
    all_events = all_wall_events + selected_controls

    for date in DATES:
        day_count = sum(event["date"] == date for event in all_events)
        print(f"Считаем будущие результаты: {date}, событий={day_count}")
        add_outcomes(date, all_events)

    summary = summarize(all_events)
    volume_distribution = pd.DataFrame(volume_rows)
    if not volume_distribution.empty:
        # Один день читается несколькими батчами, поэтому объединяем их взвешенно грубо
        # через среднее квантилей. Это диагностика масштаба, не параметр сигнала.
        volume_distribution = (
            volume_distribution.groupby(["date", "side"], as_index=False)
            .agg(
                sampleCount=("sampleCount", "sum"),
                p50=("p50", "mean"),
                p75=("p75", "mean"),
                p90=("p90", "mean"),
                p95=("p95", "mean"),
                p99=("p99", "mean"),
                max=("max", "max"),
            )
        )

    save_event_csv(all_wall_events, EVENTS_PATH)
    save_event_csv(selected_controls, CONTROLS_PATH)
    summary.to_csv(SUMMARY_PATH, index=False)
    volume_distribution.to_csv(VOLUME_DISTRIBUTION_PATH, index=False)
    findings = build_findings(
        all_wall_events, selected_controls, summary, volume_distribution
    )
    FINDINGS_PATH.write_text(findings, encoding="utf-8")

    print(f"Готово: {EVENTS_PATH}")
    print(f"Готово: {CONTROLS_PATH}")
    print(f"Готово: {SUMMARY_PATH}")
    print(f"Готово: {FINDINGS_PATH}")


if __name__ == "__main__":
    main()
