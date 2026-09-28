from pathlib import Path

import numpy as np
import pandas as pd


# Все настройки анализа собраны здесь. Скрипт запускается без аргументов.
DATES = ["2026-03-19", "2026-03-20", "2026-03-21"]
GAP_THRESHOLD_SECONDS = 1.0
ADVERSE_HORIZONS_MS = [100, 500, 1_000]
TRADE_FLOW_WINDOW = "1s"

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR.parents[1]
DATA_DIR = PROJECT_DIR / "data"
REPORT_PATH = SCRIPT_DIR / "market_data_report.html"


def percentile(values, level):
    """Возвращает percentile непустого набора или NaN, если данных нет."""
    clean_values = pd.Series(values).dropna()
    if clean_values.empty:
        return np.nan
    return float(clean_values.quantile(level))


def correlation(left, right):
    """Считает корреляцию только по строкам, где присутствуют оба значения."""
    values = pd.DataFrame({"left": left, "right": right}).dropna()
    if len(values) < 2:
        return np.nan
    return float(values["left"].corr(values["right"]))


def read_day(date):
    """Читает только нужные для отчета колонки, чтобы не держать весь L2 в памяти."""
    book_columns = ["datetime", "bid_price_1", "ask_price_1"]
    for level in range(1, 4):
        book_columns.append(f"bid_qty_{level}")
        book_columns.append(f"ask_qty_{level}")

    book = pd.read_parquet(DATA_DIR / "orderbook" / f"{date}.parquet", columns=book_columns)
    trades = pd.read_parquet(DATA_DIR / "trades" / f"{date}.parquet")
    funding = pd.read_parquet(DATA_DIR / "fundings" / f"{date}.parquet")
    return book, trades, funding


def prepare_book(book):
    """Проверяет порядок исходных строк и добавляет простые признаки стакана."""
    original_time = book["datetime"]
    out_of_order = int((original_time.diff() < pd.Timedelta(0)).sum())
    duplicate_timestamps = int(original_time.duplicated().sum())

    # Остальной анализ требует хронологического порядка.
    book = book.sort_values("datetime").reset_index(drop=True)
    book["spread"] = book["ask_price_1"] - book["bid_price_1"]
    book["mid"] = (book["ask_price_1"] + book["bid_price_1"]) / 2.0
    book["depth_1"] = book["bid_qty_1"] + book["ask_qty_1"]

    bid_depth_3 = book[[f"bid_qty_{level}" for level in range(1, 4)]].sum(axis=1)
    ask_depth_3 = book[[f"ask_qty_{level}" for level in range(1, 4)]].sum(axis=1)
    book["depth_3"] = bid_depth_3 + ask_depth_3
    depth_1 = (book["bid_qty_1"] + book["ask_qty_1"]).replace(0.0, np.nan)
    depth_3 = (bid_depth_3 + ask_depth_3).replace(0.0, np.nan)
    book["imbalance_l1"] = (book["bid_qty_1"] - book["ask_qty_1"]) / depth_1
    book["imbalance_l3"] = (bid_depth_3 - ask_depth_3) / depth_3

    invalid_top = (
        book[["bid_price_1", "ask_price_1", "bid_qty_1", "ask_qty_1"]].isna().any(axis=1)
        | (book["bid_price_1"] <= 0.0)
        | (book["ask_price_1"] <= 0.0)
        | (book["bid_qty_1"] <= 0.0)
        | (book["ask_qty_1"] <= 0.0)
        | (book["bid_price_1"] >= book["ask_price_1"])
    )

    intervals_seconds = book["datetime"].diff().dt.total_seconds()
    quality = {
        "out_of_order": out_of_order,
        "duplicate_timestamps": duplicate_timestamps,
        "invalid_top": int(invalid_top.sum()),
        "intervals_seconds": intervals_seconds,
    }
    return book, quality


def aggregate_trades(trades):
    """Объединяет записи так же, как event tape: timestamp, цена и сторона агрессора."""
    grouped = (
        trades.groupby(["datetime", "price", "is_maker_ask"], as_index=False)["size"]
        .sum()
        .sort_values("datetime")
        .reset_index(drop=True)
    )
    grouped["signed_size"] = np.where(
        grouped["is_maker_ask"] == 1,
        grouped["size"],
        -grouped["size"],
    )

    # Положительный flow означает преобладание aggressive buys за прошедшую секунду.
    grouped["trade_flow_1s"] = (
        grouped.set_index("datetime")["signed_size"]
        .rolling(TRADE_FLOW_WINDOW)
        .sum()
        .to_numpy()
    )
    return grouped


def match_trades_to_book(trades, book):
    """Добавляет последний известный до trade стакан без использования будущего."""
    book_state = book[
        [
            "datetime",
            "bid_price_1",
            "ask_price_1",
            "bid_qty_1",
            "ask_qty_1",
            "mid",
            "imbalance_l1",
            "imbalance_l3",
        ]
    ]
    matched = pd.merge_asof(
        trades.sort_values("datetime"),
        book_state.sort_values("datetime"),
        on="datetime",
        direction="backward",
        allow_exact_matches=False,
    )

    # В event tape trade при равном timestamp идет раньше snapshot, поэтому exact match запрещен.
    previous_book_time = pd.merge_asof(
        trades[["datetime"]].sort_values("datetime"),
        book[["datetime"]].rename(columns={"datetime": "book_datetime"}),
        left_on="datetime",
        right_on="book_datetime",
        direction="backward",
        allow_exact_matches=False,
    )["book_datetime"]
    matched["book_age_ms"] = (
        matched["datetime"] - previous_book_time
    ).dt.total_seconds() * 1_000.0

    buy_aggressor = matched["is_maker_ask"] == 1
    matched["correct_book_side"] = np.where(
        buy_aggressor,
        matched["price"] >= matched["ask_price_1"] - 1e-9,
        matched["price"] <= matched["bid_price_1"] + 1e-9,
    )
    matched["exact_touch"] = np.where(
        buy_aggressor,
        np.isclose(matched["price"], matched["ask_price_1"]),
        np.isclose(matched["price"], matched["bid_price_1"]),
    )
    matched["visible_touch_qty"] = np.where(
        buy_aggressor,
        matched["ask_qty_1"],
        matched["bid_qty_1"],
    )
    visible_queue = matched["visible_touch_qty"].replace(0.0, np.nan)
    matched["trade_to_queue"] = matched["size"] / visible_queue
    return matched


def add_future_mid(matched, book, horizon_ms):
    """Находит первый snapshot не раньше заданного горизонта после trade."""
    lookup = matched[["datetime"]].copy()
    lookup["lookup_time"] = lookup["datetime"] + pd.to_timedelta(horizon_ms, unit="ms")
    future_book = book[["datetime", "mid"]].rename(
        columns={"datetime": "future_book_time", "mid": f"future_mid_{horizon_ms}ms"}
    )
    future = pd.merge_asof(
        lookup.sort_values("lookup_time"),
        future_book.sort_values("future_book_time"),
        left_on="lookup_time",
        right_on="future_book_time",
        direction="forward",
    )
    return future[f"future_mid_{horizon_ms}ms"]


def analyze_day(date):
    """Считает дневные показатели и возвращает строки для таблиц HTML-отчета."""
    raw_book, raw_trades, funding = read_day(date)
    book, quality = prepare_book(raw_book)
    trades = aggregate_trades(raw_trades)
    matched = match_trades_to_book(trades, book)

    intervals = quality["intervals_seconds"]
    quality_row = {
        "Дата": date,
        "Snapshots": len(book),
        "Нарушения порядка": quality["out_of_order"],
        "Дубли timestamp": quality["duplicate_timestamps"],
        "Некорректный top": quality["invalid_top"],
        "Интервал p50, мс": percentile(intervals, 0.50) * 1_000.0,
        "Интервал p99, мс": percentile(intervals, 0.99) * 1_000.0,
        "Gaps > 1с": int((intervals > GAP_THRESHOLD_SECONDS).sum()),
        "Макс. gap, с": intervals.max(),
    }

    # Волатильность считается по соседним доступным секундным mid, без заполнения gaps.
    mid_1s = book.set_index("datetime")["mid"].resample("1s").last()
    returns_1s_bps = mid_1s.pct_change(fill_method=None) * 10_000.0
    dominant_spread = book["spread"].round(6).mode().iloc[0]
    buy_volume = raw_trades.loc[raw_trades["is_maker_ask"] == 1, "size"].sum()
    total_volume = raw_trades["size"].sum()
    market_row = {
        "Дата": date,
        "Mid p50": book["mid"].median(),
        "Основной spread": dominant_spread,
        "Доля основного spread, %": (book["spread"].round(6) == dominant_spread).mean() * 100.0,
        "Volatility 1с, bps": returns_1s_bps.std(),
        "Depth L1 p50, ETH": book["depth_1"].median(),
        "Depth L3 p50, ETH": book["depth_3"].median(),
        "Trade records": len(raw_trades),
        "Trade events": len(trades),
        "Объем trades, ETH": total_volume,
        "Buy-aggressor volume, %": buy_volume / total_volume * 100.0,
        "Размер trade p50, ETH": raw_trades["size"].median(),
        "Размер trade p95, ETH": percentile(raw_trades["size"], 0.95),
    }

    valid_match = matched["mid"].notna()
    executable = valid_match & matched["correct_book_side"]
    execution_row = {
        "Дата": date,
        "Trades со стаканом": int(valid_match.sum()),
        "Возраст стакана p50, мс": percentile(matched.loc[valid_match, "book_age_ms"], 0.50),
        "Возраст стакана p99, мс": percentile(matched.loc[valid_match, "book_age_ms"], 0.99),
        "На правильной стороне, %": matched.loc[valid_match, "correct_book_side"].mean() * 100.0,
        "Точно на touch, %": matched.loc[valid_match, "exact_touch"].mean() * 100.0,
        "Видимая очередь p50, ETH": matched.loc[executable, "visible_touch_qty"].median(),
        "Trade / очередь p50": matched.loc[executable, "trade_to_queue"].median(),
        "Trade >= очередь, %": (matched.loc[executable, "trade_to_queue"] >= 1.0).mean() * 100.0,
    }

    adverse_row = {"Дата": date}
    for horizon_ms in ADVERSE_HORIZONS_MS:
        future_mid = add_future_mid(matched, book, horizon_ms)
        direction = np.where(matched["is_maker_ask"] == 1, 1.0, -1.0)
        signed_move_bps = direction * (future_mid - matched["mid"]) / matched["mid"] * 10_000.0
        matched[f"future_return_{horizon_ms}ms"] = (
            (future_mid - matched["mid"]) / matched["mid"] * 10_000.0
        )
        adverse_row[f"Adverse move {horizon_ms}мс, bps"] = signed_move_bps[executable].mean()

    # Для первого простого сравнения сигналов используем один заранее выбранный горизонт 500 мс.
    future_return = matched["future_return_500ms"]
    adverse_row["Corr imbalance L1 / future return"] = correlation(
        matched["imbalance_l1"], future_return
    )
    adverse_row["Corr imbalance L3 / future return"] = correlation(
        matched["imbalance_l3"], future_return
    )
    adverse_row["Corr trade flow 1с / future return"] = correlation(
        matched["trade_flow_1s"], future_return
    )

    funding = funding.sort_values("datetime")
    funding_intervals = funding["datetime"].diff().dt.total_seconds()
    funding_row = {
        "Дата": date,
        "Наблюдений": len(funding),
        "Интервал p50, с": percentile(funding_intervals, 0.50),
        "Rate min": funding["funding_rate"].min(),
        "Rate mean": funding["funding_rate"].mean(),
        "Rate max": funding["funding_rate"].max(),
        "Положительный rate, %": (funding["funding_rate"] > 0.0).mean() * 100.0,
    }
    return quality_row, market_row, execution_row, adverse_row, funding_row


def format_table(rows, decimals=4):
    """Создает компактную HTML-таблицу с единым форматированием чисел."""
    frame = pd.DataFrame(rows)
    return frame.to_html(
        index=False,
        border=0,
        classes="metrics",
        na_rep="—",
        float_format=lambda value: f"{value:,.{decimals}f}",
    )


def build_report(tables):
    """Собирает один автономный HTML без JavaScript и внешних ресурсов."""
    quality, market, execution, adverse, funding = tables
    return f"""<!doctype html>
<html lang="ru">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Проверка market data</title>
    <style>
        body {{ font-family: Arial, sans-serif; margin: 32px; color: #202124; }}
        h1, h2 {{ color: #17365d; }}
        p, li {{ line-height: 1.45; max-width: 1050px; }}
        .table-wrap {{ overflow-x: auto; margin-bottom: 28px; }}
        table.metrics {{ border-collapse: collapse; white-space: nowrap; font-size: 13px; }}
        table.metrics th, table.metrics td {{ border: 1px solid #d7dce2; padding: 7px 9px; text-align: right; }}
        table.metrics th:first-child, table.metrics td:first-child {{ text-align: left; }}
        table.metrics th {{ background: #eef3f8; }}
        .note {{ background: #f7f8fa; border-left: 4px solid #6b84a3; padding: 10px 14px; }}
        code {{ background: #f1f3f4; padding: 2px 4px; }}
    </style>
</head>
<body>
    <h1>Проверка данных ETH perpetual</h1>
    <p>Три дня: {", ".join(DATES)}. Отчет создан скриптом <code>analyze_market_data.py</code>.</p>

    <h2>1. Качество snapshots и gaps</h2>
    <div class="table-wrap">{format_table(quality)}</div>

    <h2>2. Режим рынка и поток сделок</h2>
    <div class="table-wrap">{format_table(market)}</div>

    <h2>3. Trades, стакан и грубая оценка очереди</h2>
    <div class="table-wrap">{format_table(execution)}</div>
    <p class="note">Trade сопоставляется только с последним snapshot, известным строго до него.
    Отношение trade к видимой очереди — диагностика масштаба, а не готовая вероятность fill:
    cancellations и реальная L3-очередь неизвестны.</p>

    <h2>4. Adverse selection и простые сигналы</h2>
    <div class="table-wrap">{format_table(adverse)}</div>
    <p class="note">Положительный adverse move означает, что после aggressive trade цена в среднем
    продолжила движение против maker. Корреляции считаются с будущим mid через 500 мс и используются
    только для исследования, не как доступная стратегии информация.</p>

    <h2>5. Funding</h2>
    <div class="table-wrap">{format_table(funding, decimals=8)}</div>

    <h2>Ограничения</h2>
    <ul>
        <li>Проверяется top of book и первые три уровня, а не полная корректность всех 20 уровней.</li>
        <li>L2 snapshots не позволяют восстановить точную очередь и причины исчезновения объема.</li>
        <li>Корреляция сигнала с будущим движением не доказывает его торговую полезность.</li>
        <li>Три дня нельзя считать достаточной выборкой для вывода о production-прибыльности.</li>
    </ul>
</body>
</html>
"""


def main():
    """Последовательно анализирует три дня и сохраняет воспроизводимый HTML-отчет."""
    quality_rows = []
    market_rows = []
    execution_rows = []
    adverse_rows = []
    funding_rows = []

    for date in DATES:
        print(f"Анализируем {date}...")
        rows = analyze_day(date)
        quality_rows.append(rows[0])
        market_rows.append(rows[1])
        execution_rows.append(rows[2])
        adverse_rows.append(rows[3])
        funding_rows.append(rows[4])

    report = build_report(
        (quality_rows, market_rows, execution_rows, adverse_rows, funding_rows)
    )
    REPORT_PATH.write_text(report, encoding="utf-8")
    print(f"Готово: {REPORT_PATH}")


if __name__ == "__main__":
    main()
