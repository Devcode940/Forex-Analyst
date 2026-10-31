# Forex & Crypto Technical Analyst App

A modern, high-performance Android application built with **Kotlin** and **Jetpack Compose** for real-time foreign exchange (Forex) and cryptocurrency market technical analysis, interactive candlestick charting, indicator overlays, trade signal discovery, custom alerts, risk management, persistent watchlist, and AI market breakdown.

---

## 🌟 Key Features

### 📈 Interactive Candlestick Chart Terminal
- **Interactive Canvas Engine**: Pinch-to-zoom and pan smoothly across historical price action.
- **Technical Indicator Overlays**:
  - **Exponential Moving Averages**: EMA 20, EMA 50, and EMA 200.
  - **Bollinger Bands**: (20-period, 2.0 standard deviation).
  - **Auto Support & Resistance**: Automated structural level detection.
  - **RSI Subchart**: 14-period Relative Strength Index with 70/30 overbought/oversold boundaries.
- **Drawing Tools**:
  - **Crosshair with Snapping**: Snap to High, Low, Open, Close prices with exact price and timestamp tooltips.
  - **Horizontal Support & Resistance Lines**: Tap to place custom price lines on the chart.
  - **Trendlines**: Drag and drop trendlines between swing highs/lows.
  - **Fibonacci Retracements**: Automatic 0%, 23.6%, 38.2%, 50%, 61.8%, 78.6%, 100% levels.
- **Chart Snapshot & Sharing**: Capture high-resolution chart images directly to device storage.

### ⭐ Persistent Watchlist (Room Database)
- **Room Data Persistence**: Persists user's favorite currency and crypto pairs locally using Room DAO and Kotlin Flows.
- **Quick Pair Switching**: Switch between pairs with a single tap and immediately view live candlestick data on the chart terminal.
- **Filter & Search**: Categorize pairs by Major, Minor, or Metals & Crypto, or search by symbol/name.
- **Default Setup**: Automatically populates default major pairs (`EUR/USD`, `GBP/USD`, `XAU/USD`, `BTC/USD`) on first launch.

### 🤖 OpenRouter AI Technical Analyst
- **Live OpenRouter Integration**: Connects to `https://openrouter.ai/api/v1/chat/completions` using LLMs like `google/gemini-2.5-flash` to generate real-time market structure breakdowns.
- **Automated Fallback**: Defaults to a local deterministic technical analysis engine when API keys are unconfigured.
- **Analysis Structure**: Evaluates Directional Bias, RSI Momentum, Trend Confluence, Pattern Recognition, and Strategy Execution Plans (Entry, SL, TP1, TP2, R:R).

### ⚡ Live Trade Signals
- Real-time trade setup recommendations across all active pairs.
- Persistent signal bookmarking in Room DB.
- **"Inspect Targets on Live Chart"**: One-tap overlay of SL and TP target lines directly on the candlestick chart canvas.

### 🔔 Custom Price & Indicator Alerts
- Set alerts for price thresholds (e.g. "Price Rises Above 1.0900") and RSI overbought/oversold levels.
- Reactive toggle switches and deletion.

### 🧮 Position Risk & Lot Size Calculator
- Calculate exact lot size based on Account Balance, Risk Percentage, Stop Loss Pips, and Contract Size.
- Live conversion for Forex Majors, Minors, Gold (`XAU/USD`), and Crypto (`BTC/USD`).

### 🎨 Material Design 3 & Dynamic Light/Dark Theme
- Instant toggle between High-Contrast Dark Canvas and Vibrant Light Theme (`Brightness7` / `Brightness4`).
- Material 3 color system with accessible touch targets and test tags for UI testability.

---

## ⚡ Live Production vs. Sample / Simulation Breakdown

| Feature | Live Production Mode | Sample / Simulation Mode (Default) |
|---|---|---|
| **AI Technical Analysis** | Calls **OpenRouter API** (`mistralai/mistral-large`) via `OPENROUTER_API_KEY`, grounded on a deterministic indicator fact sheet + the trader's question | Dynamic rule-engine answers recomputed from live candles (intent-aware, ATR/volatility aware) — never canned text |
| **Price Feeds & Candles** | **Alpha Vantage** (`ALPHAVANTAGE_API_KEY`: FX_DAILY, DIGITAL_CURRENCY_DAILY, CURRENCY_EXCHANGE_RATE realtime quotes; intraday on premium) → **FCS API** (`fcsapi.com`) fallback for intraday bars & ticks | Realistic 5-second random-walk tick generator stream & historical candle generator |
| **Data Validation** | Every REST tick/candle payload passes `MarketDataValidator` (OHLC invariants, spike/gap/statistics filters, quality score) before entering cache, DB or signals | Same validation layer applied to simulated ticks |
| **Watchlist & Favorites** | **100% Real Persistence** stored in SQLite via Room DB | **100% Real Persistence** stored in SQLite via Room DB |
| **Trade Signals & Bookmarks** | Real-time calculation with **ATR-based stops/targets + volatility-regime position sizing** + persistent Room DB storage | Real-time calculation + persistent Room DB storage |
| **Background Signal Alerts (`SignalWorker`)** | Periodic WorkManager scan: refreshes validated candles (Alpha Vantage → FCS → Room history), runs the technical engine, persists & notifies only actionable de-duplicated signals | Same pipeline, but analyzed data may originate from the local generator (no random/mock notifications) |
| **Price & Indicator Alerts** | Local reactive trigger monitoring + Room DB storage | Local reactive trigger monitoring + Room DB storage |
| **Position Size Calculator** | 100% Real mathematical risk & lot size conversion | 100% Real mathematical risk & lot size conversion |

---

## 🔑 Environment Secret Configuration

Configure your API keys in the AI Studio **Secrets** panel or in `.env`:
```env
# FCS_API_KEY for live forex price data
FCS_API_KEY=your_fcs_api_key_here

# OPENROUTER_API_KEY for live AI LLM market analysis
OPENROUTER_API_KEY=your_openrouter_api_key_here
```

---

## 🛠️ Architecture & Tech Stack

- **Language**: 100% Kotlin
- **UI**: Jetpack Compose with Material Design 3 (M3)
- **Architecture**: MVVM (Model-View-ViewModel)
- **Local Database**: Room Database with **KSP** (Kotlin Symbol Processing) and **Kotlin StateFlow/Flow**
- **Networking**: Retrofit 2 with FCS API integration & fallback real-time price tick simulation stream
- **Graphics**: Jetpack Compose `Canvas` rendering engine

---

## 🗄️ Database Schema (Room)

```
watchlist_items   (symbol PRIMARY KEY, addedAt)
saved_signals     (id PRIMARY KEY, pairSymbol, signalType, entryPrice, stopLoss, takeProfit1, takeProfit2, riskRewardRatio, confidenceScore, timeframeLabel, summaryRationale, timestamp, isBookmarked)
custom_alerts     (id PRIMARY KEY, pairSymbol, conditionType, targetValue, isTriggered, isActive, note, createdAt)
historical_candles(symbol, timeframe, timestamp PRIMARY KEY, open, high, low, close, volume)
```

---

## 🚀 Building & Running

1. **Build Debug APK**:
   ```bash
   gradle assembleDebug
   ```
2. **Run Unit Tests**:
   ```bash
   gradle testDebugUnitTest
   ```
3. **Compile Verification**:
   ```bash
   gradle compileDebugKotlin
   ```

---

## 📄 License

Licensed under the Apache License, Version 2.0.
