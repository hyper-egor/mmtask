# Метрики backtest

## PnL

- `realizedPnlCumulative` — результат по закрытой части позиции;
- `unrealizedPnlEnd` — переоценка открытой позиции по последнему известному mid;
- `grossPnl = realizedPnl + unrealizedPnl`;
- `feesPaidCumulative` — maker fees;
- `fundingPnlCumulative` — в текущем исследовании всегда 0;
- `netPnl = grossPnl - fees + funding`;
- `dailyPnl` — изменение net equity за календарный день.

Позиция учитывается по average entry price. Независимый cash ledger проверяет PnL:

```text
netPnl = tradingCash + inventory * mid - fees + funding
```

Он используется только как invariant и не прибавляется к PnL повторно.

## Inventory и риск

- конечный inventory;
- максимальный long и short inventory;
- средний абсолютный inventory, взвешенный по времени;
- max drawdown по net equity.

```text
averageAbsInventory = sum(abs(inventory) * duration) / totalDuration
```

Max drawdown — максимальное падение equity от достигнутого ранее максимума.

## Исполнение

- активированные ордера;
- fill events;
- полностью и частично исполненные ордера;
- buy, sell и total fill volume;
- средний размер fill;
- cancel commands и replacements;
- среднее время жизни active order;
- gap resets и время со stale book.

Для reduce-only ордеров отдельно считаются активации, fills, full/partial fills,
volume, replacements и среднее время жизни. Один ордер с несколькими partial fills
считается одной активацией и несколькими fill events.

## Период расчета

Все три дня проигрываются одним непрерывным event tape. На границе дня inventory,
average entry, PnL и состояние стратегии не сбрасываются. Поэтому `dailyPnl` считается
как изменение equity относительно конца предыдущего дня, а дневные значения точно
складываются в общий PnL.

## Артефакты

Каждый запуск сохраняется в `results/<StrategyClass>/`:

- `summary.csv` — строки по дням и `TOTAL`, параметры запуска и все итоговые метрики;
- `hourly.csv` — 72 часовых среза без интерполяции будущих данных;
- `timeline.png` — net PnL и inventory.

Max drawdown и средний абсолютный inventory считаются по всем событиям, а не по
часовым точкам.
