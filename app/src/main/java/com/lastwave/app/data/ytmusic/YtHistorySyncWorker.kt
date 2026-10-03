package com.lastwave.app.data.ytmusic

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class YtHistorySyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val sync = EntryPointAccessors.fromApplication(applicationContext, HistorySyncEntryPoint::class.java).historySync()
        if (sync.flushPendingHistory()) Result.success() else Result.retry()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<YtHistorySyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            // Append prevents a listen queued just as a worker finishes from being lost.
            WorkManager.getInstance(context).enqueueUniqueWork("lastwave-offline-history",
                ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface HistorySyncEntryPoint {
    fun historySync(): YtMusicHistorySyncManager
}
