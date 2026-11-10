package com.devcode940.ForexAnalyst.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ForexDao {
    // ========================================================================
    // PAGINATED QUERIES
    // ========================================================================

    /**
     * Get saved signals with pagination.
     * 
     * @param offset Starting index
     * @param limit Maximum number of items to return
     */
    @Query("SELECT * FROM saved_signals ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getSavedSignalsPaginated(offset: Int, limit: Int): List<SignalEntity>

    /**
     * Count all saved signals.
     */
    @Query("SELECT COUNT(*) FROM saved_signals")
    suspend fun countSavedSignals(): Int

    /**
     * Get custom alerts with pagination.
     * 
     * @param offset Starting index
     * @param limit Maximum number of items to return
     */
    @Query("SELECT * FROM custom_alerts ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getAlertsPaginated(offset: Int, limit: Int): List<AlertEntity>

    /**
     * Count all custom alerts.
     */
    @Query("SELECT COUNT(*) FROM custom_alerts")
    suspend fun countAlerts(): Int

    /**
     * Get historical candles with pagination.
     * 
     * @param symbol The currency pair symbol
     * @param timeframe The timeframe
     * @param offset Starting index
     * @param limit Maximum number of items to return
     */
    @Query("SELECT * FROM historical_candles WHERE symbol = :symbol AND timeframe = :timeframe ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    suspend fun getCandlesPaginated(
        symbol: String,
        timeframe: String,
        offset: Int,
        limit: Int
    ): List<CandleEntity>

    /**
     * Count historical candles for a specific symbol and timeframe.
     */
    @Query("SELECT COUNT(*) FROM historical_candles WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun countCandles(symbol: String, timeframe: String): Int

    // ========================================================================
    // EXISTING QUERIES
    // ========================================================================

    // Saved Signals
    @Query("SELECT * FROM saved_signals ORDER BY timestamp DESC")
    fun getAllSavedSignals(): Flow<List<SignalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignal(signal: SignalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignals(signals: List<SignalEntity>)

    @Query("DELETE FROM saved_signals WHERE id = :signalId")
    suspend fun deleteSignal(signalId: String)

    /** Recent saved signals — used by SignalWorker for notification de-duplication. */
    @Query("SELECT * FROM saved_signals WHERE timestamp >= :sinceTimestamp ORDER BY timestamp DESC")
    suspend fun getSignalsSince(sinceTimestamp: Long): List<SignalEntity>

    // Custom Alerts
    @Query("SELECT * FROM custom_alerts ORDER BY createdAt DESC")
    fun getAllAlerts(): Flow<List<AlertEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: AlertEntity)

    @Query("UPDATE custom_alerts SET isTriggered = :triggered, isActive = :active WHERE id = :alertId")
    suspend fun updateAlertStatus(alertId: String, triggered: Boolean, active: Boolean)

    @Query("DELETE FROM custom_alerts WHERE id = :alertId")
    suspend fun deleteAlert(alertId: String)

    // Watchlist
    @Query("SELECT * FROM watchlist_items")
    fun getWatchlist(): Flow<List<WatchlistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToWatchlist(item: WatchlistEntity)

    @Query("DELETE FROM watchlist_items WHERE symbol = :symbol")
    suspend fun removeFromWatchlist(symbol: String)

    // Historical Candles
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandles(candles: List<CandleEntity>)

    @Query("SELECT * FROM historical_candles WHERE symbol = :symbol AND timeframe = :timeframe ORDER BY timestamp ASC")
    suspend fun getCandles(symbol: String, timeframe: String): List<CandleEntity>
    
    @Query("DELETE FROM historical_candles WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun deleteCandles(symbol: String, timeframe: String)

    // User Profile & Authentication (Secure local persistence)
    @Query("SELECT * FROM user_profiles WHERE isLoggedIn = 1 LIMIT 1")
    fun getActiveUserProfile(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profiles WHERE email = :email LIMIT 1")
    suspend fun getUserByEmail(email: String): UserProfileEntity?

    @Query("SELECT * FROM user_profiles WHERE uid = :uid LIMIT 1")
    suspend fun getUserByUid(uid: String): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateUser(user: UserProfileEntity)

    @Query("UPDATE user_profiles SET isLoggedIn = 0")
    suspend fun logoutAllUsers()

    @Query("UPDATE user_profiles SET isLoggedIn = 1 WHERE uid = :uid")
    suspend fun setActiveUser(uid: String)

    // User Indicator Settings Persistence
    @Query("SELECT * FROM user_settings WHERE id = 'current_settings' LIMIT 1")
    fun getUserSettings(): Flow<UserSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveUserSettings(settings: UserSettingsEntity)
}
