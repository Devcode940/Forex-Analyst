package com.example.forex.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ForexDao {
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
