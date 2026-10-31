package com.example.forex.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_signals")
data class SignalEntity(
    @PrimaryKey val id: String,
    val pairSymbol: String,
    val signalType: String,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val riskRewardRatio: Double,
    val confidenceScore: Int,
    val timeframeLabel: String,
    val summaryRationale: String,
    val timestamp: Long,
    val isBookmarked: Boolean = true,
    // Volatility-aware metadata (added with the ATR sizing upgrade; schema v4)
    val atrValue: Double = 0.0,
    val stopLossPips: Double = 0.0,
    val volatilityRegime: String = "NORMAL",
    val suggestedLotSize: Double = 0.0,
    val dataQualityScore: Int = 100,
    val dataSource: String = "SIMULATION"
)

@Entity(tableName = "custom_alerts")
data class AlertEntity(
    @PrimaryKey val id: String,
    val pairSymbol: String,
    val conditionType: String,
    val targetValue: Double,
    val isTriggered: Boolean,
    val isActive: Boolean,
    val note: String,
    val createdAt: Long
)

@Entity(tableName = "watchlist_items")
data class WatchlistEntity(
    @PrimaryKey val symbol: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "historical_candles",
    primaryKeys = ["symbol", "timeframe", "timestamp"]
)
data class CandleEntity(
    val symbol: String,
    val timeframe: String,
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey val uid: String,
    val email: String,
    val displayName: String,
    val photoUrl: String? = null,
    val passwordHash: String? = null,
    val accountTier: String = "Pro Trader",
    val joinedAt: Long = System.currentTimeMillis(),
    val isLoggedIn: Boolean = true
)

@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: String = "current_settings",
    val showSma20: Boolean = false,
    val showSma50: Boolean = false,
    val showEma20: Boolean = true,
    val showEma50: Boolean = true,
    val showEma200: Boolean = false,
    val showBollingerBands: Boolean = true,
    val showSupportResistance: Boolean = true,
    val showRsiSubchart: Boolean = true,
    val showMacdSubchart: Boolean = false,
    val showPatterns: Boolean = true,
    val smaPeriod1: Int = 20,
    val smaPeriod2: Int = 50,
    val emaPeriod1: Int = 20,
    val emaPeriod2: Int = 50,
    val emaPeriod3: Int = 200,
    val rsiPeriod: Int = 14,
    val rsiOverbought: Double = 70.0,
    val rsiOversold: Double = 30.0,
    val macdFastPeriod: Int = 12,
    val macdSlowPeriod: Int = 26,
    val macdSignalPeriod: Int = 9,
    val bollingerPeriod: Int = 20,
    val bollingerStdDev: Double = 2.0
)
