package com.example.forex.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager registration for the real [SignalWorker].
 *
 * - Periodic scan (15 min minimum per WorkManager constraints) with a NETWORK_CONNECTED
 *   constraint and LINEAR backoff so transient upstream outages (Alpha Vantage quota
 *   exhaustion, DNS failures) retry without spamming notifications.
 * - `ExistingPeriodicWorkPolicy.KEEP` preserves the user's original cadence across launches.
 * - A OneTimeWork kickstart gives a first real scan shortly after app start instead of
 *   waiting up to 15 minutes for the periodic window.
 */
fun scheduleSignalWorker(
    context: Context,
    minConfidence: Int = SignalWorker.DEFAULT_MIN_CONFIDENCE,
    cooldownHours: Long = SignalWorker.DEFAULT_COOLDOWN_HOURS,
    timeframe: String? = null,
    symbols: List<String>? = null
) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val input = buildSignalWorkInput(minConfidence, cooldownHours, timeframe, symbols)

    val workRequest = PeriodicWorkRequestBuilder<SignalWorker>(15, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .setInputData(input)
        .setBackoffCriteria(
            androidx.work.BackoffPolicy.LINEAR,
            2, TimeUnit.MINUTES
        )
        .setInitialDelay(30, TimeUnit.SECONDS)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UNIQUE_PERIODIC,
        ExistingPeriodicWorkPolicy.KEEP,
        workRequest
    )

    // Immediate first pass for a fresh install / app start (idempotent via REPLACE on cold start only).
    val kickoff = OneTimeWorkRequestBuilder<SignalWorker>()
        .setConstraints(constraints)
        .setInputData(input)
        .setInitialDelay(10, TimeUnit.SECONDS)
        .addTag(TAG_SIGNAL_SCAN)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        UNIQUE_KICKOFF,
        ExistingWorkPolicy.REPLACE,
        kickoff
    )
}

/** Trigger an immediate out-of-band scan (used by pull-to-refresh / debug entry points). */
fun triggerSignalScanNow(
    context: Context,
    minConfidence: Int = 70,
    cooldownHours: Long = 0L,
    symbols: List<String>? = null
) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val request = OneTimeWorkRequestBuilder<SignalWorker>()
        .setConstraints(constraints)
        .setInputData(buildSignalWorkInput(minConfidence, cooldownHours, null, symbols))
        .addTag(TAG_SIGNAL_SCAN)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        "TradeSignalManualScan",
        ExistingWorkPolicy.REPLACE,
        request
    )
}

fun cancelSignalWorker(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
    WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_KICKOFF)
}

private fun buildSignalWorkInput(
    minConfidence: Int,
    cooldownHours: Long,
    timeframe: String?,
    symbols: List<String>?
): Data = Data.Builder()
    .putInt(SignalWorker.KEY_MIN_CONFIDENCE, minConfidence)
    .putLong(SignalWorker.KEY_COOLDOWN_HOURS, cooldownHours)
    .apply {
        if (!timeframe.isNullOrBlank()) putString(SignalWorker.KEY_TIMEFRAME, timeframe)
        if (!symbols.isNullOrEmpty()) putString(SignalWorker.KEY_SYMBOLS, symbols.joinToString(","))
    }
    .build()

private const val UNIQUE_PERIODIC = "TradeSignalPolling"
private const val UNIQUE_KICKOFF = "TradeSignalKickoff"
private const val TAG_SIGNAL_SCAN = "trade_signal_scan"
