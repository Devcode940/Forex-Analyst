package com.devcode940.ForexAnalyst.data.model

/**
 * Value class for Signal IDs to prevent primitive obsession.
 * Ensures type safety when working with signal identifiers.
 */
@JvmInline
value class SignalId(val value: String) {
    companion object {
        fun random(): SignalId = SignalId(java.util.UUID.randomUUID().toString())
        fun fromString(value: String): SignalId = SignalId(value)
    }
    
    override fun toString(): String = value
}

/**
 * Value class for Alert IDs to prevent primitive obsession.
 * Ensures type safety when working with alert identifiers.
 */
@JvmInline
value class AlertId(val value: String) {
    companion object {
        fun random(): AlertId = AlertId(java.util.UUID.randomUUID().toString())
        fun fromString(value: String): AlertId = AlertId(value)
    }
    
    override fun toString(): String = value
}

/**
 * Value class for User IDs to prevent primitive obsession.
 * Ensures type safety when working with user identifiers.
 */
@JvmInline
value class UserId(val value: String) {
    companion object {
        fun random(): UserId = UserId(java.util.UUID.randomUUID().toString())
        fun fromString(value: String): UserId = UserId(value)
    }
    
    override fun toString(): String = value
}

/**
 * Value class for Watchlist Item IDs to prevent primitive obsession.
 * Ensures type safety when working with watchlist identifiers.
 */
@JvmInline
value class WatchlistItemId(val value: String) {
    companion object {
        fun fromString(value: String): WatchlistItemId = WatchlistItemId(value)
    }
    
    override fun toString(): String = value
}

/**
 * Value class for Candle IDs to prevent primitive obsession.
 * Ensures type safety when working with candle identifiers.
 */
@JvmInline
value class CandleId(val value: String) {
    companion object {
        fun fromParts(symbol: String, timeframe: String, timestamp: Long): CandleId {
            return CandleId("$symbol:$timeframe:$timestamp")
        }
    }
    
    override fun toString(): String = value
}

/**
 * Value class for Economic Event IDs to prevent primitive obsession.
 */
@JvmInline
value class EconomicEventId(val value: String) {
    companion object {
        fun random(): EconomicEventId = EconomicEventId(java.util.UUID.randomUUID().toString())
        fun fromString(value: String): EconomicEventId = EconomicEventId(value)
    }
    
    override fun toString(): String = value
}

/**
 * Value class for AI Chat Message IDs to prevent primitive obsession.
 */
@JvmInline
value class AiChatMessageId(val value: String) {
    companion object {
        fun random(): AiChatMessageId = AiChatMessageId(java.util.UUID.randomUUID().toString())
        fun fromString(value: String): AiChatMessageId = AiChatMessageId(value)
    }
    
    override fun toString(): String = value
}

/**
 * Value class for AI Zone IDs to prevent primitive obsession.
 */
@JvmInline
value class AiZoneId(val value: String) {
    companion object {
        fun random(): AiZoneId = AiZoneId(java.util.UUID.randomUUID().toString())
        fun fromString(value: String): AiZoneId = AiZoneId(value)
    }
    
    override fun toString(): String = value
}

/**
 * Value class for AI Trendline IDs to prevent primitive obsession.
 */
@JvmInline
value class AiTrendlineId(val value: String) {
    companion object {
        fun random(): AiTrendlineId = AiTrendlineId(java.util.UUID.randomUUID().toString())
        fun fromString(value: String): AiTrendlineId = AiTrendlineId(value)
    }
    
    override fun toString(): String = value
}

/**
 * Value class for AI Target Projection IDs to prevent primitive obsession.
 */
@JvmInline
value class AiTargetProjectionId(val value: String) {
    companion object {
        fun random(): AiTargetProjectionId = AiTargetProjectionId(java.util.UUID.randomUUID().toString())
        fun fromString(value: String): AiTargetProjectionId = AiTargetProjectionId(value)
    }
    
    override fun toString(): String = value
}

// ========================================================================
// Extension Functions for String Conversion
// ========================================================================

/**
 * Convert String to SignalId.
 */
fun String.toSignalId(): SignalId = SignalId(this)

/**
 * Convert String to AlertId.
 */
fun String.toAlertId(): AlertId = AlertId(this)

/**
 * Convert String to UserId.
 */
fun String.toUserId(): UserId = UserId(this)

/**
 * Convert String to WatchlistItemId.
 */
fun String.toWatchlistItemId(): WatchlistItemId = WatchlistItemId(this)

/**
 * Convert String to CandleId.
 */
fun String.toCandleId(): CandleId = CandleId(this)

/**
 * Convert String to EconomicEventId.
 */
fun String.toEconomicEventId(): EconomicEventId = EconomicEventId(this)

/**
 * Convert String to AiChatMessageId.
 */
fun String.toAiChatMessageId(): AiChatMessageId = AiChatMessageId(this)

/**
 * Convert String to AiZoneId.
 */
fun String.toAiZoneId(): AiZoneId = AiZoneId(this)

/**
 * Convert String to AiTrendlineId.
 */
fun String.toAiTrendlineId(): AiTrendlineId = AiTrendlineId(this)

/**
 * Convert String to AiTargetProjectionId.
 */
fun String.toAiTargetProjectionId(): AiTargetProjectionId = AiTargetProjectionId(this)

// ========================================================================
// Extension Properties for Value Classes
// ========================================================================

/**
 * Get the raw String value of a SignalId.
 */
val SignalId.raw: String get() = this.value

/**
 * Get the raw String value of an AlertId.
 */
val AlertId.raw: String get() = this.value

/**
 * Get the raw String value of a UserId.
 */
val UserId.raw: String get() = this.value

/**
 * Get the raw String value of a WatchlistItemId.
 */
val WatchlistItemId.raw: String get() = this.value

/**
 * Get the raw String value of a CandleId.
 */
val CandleId.raw: String get() = this.value

/**
 * Get the raw String value of an EconomicEventId.
 */
val EconomicEventId.raw: String get() = this.value

/**
 * Get the raw String value of an AiChatMessageId.
 */
val AiChatMessageId.raw: String get() = this.value

/**
 * Get the raw String value of an AiZoneId.
 */
val AiZoneId.raw: String get() = this.value

/**
 * Get the raw String value of an AiTrendlineId.
 */
val AiTrendlineId.raw: String get() = this.value

/**
 * Get the raw String value of an AiTargetProjectionId.
 */
val AiTargetProjectionId.raw: String get() = this.value
