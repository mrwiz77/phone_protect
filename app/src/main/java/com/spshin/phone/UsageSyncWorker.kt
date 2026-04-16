package com.spshin.phone

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.spshin.phone.model.DeviceSettings
import com.spshin.phone.model.UserRole
import java.util.concurrent.TimeUnit

class UsageSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val preferencesRepository = PreferencesRepository(appContext)
    private val usageRepository = UsageRepository(appContext)
    private val remoteRepository = FamilyRemoteRepository(appContext)

    override suspend fun doWork(): Result {
        val settings = preferencesRepository.getSettings()
        if (settings.role == UserRole.NONE || settings.familyCode.isBlank()) {
            return Result.success()
        }

        return runCatching {
            when (settings.role) {
                UserRole.CHILD -> syncChild(settings)
                UserRole.PARENT -> syncParent(settings)
                UserRole.NONE -> Unit
            }
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    private suspend fun syncChild(settings: DeviceSettings) {
        if (!usageRepository.hasUsageAccess()) return

        val policy = remoteRepository.fetchRestrictionPolicy(settings.familyCode)
            ?: settings.restrictionPolicy
        preferencesRepository.saveRestrictionPolicy(policy)

        val summaries = usageRepository.getWeeklySummaries(policy)
        remoteRepository.pushWeeklySummaries(
            familyCode = settings.familyCode,
            childName = settings.childName,
            summaries = summaries
        )

        val latestRestrictedUsage = usageRepository.findLatestRestrictedUsage(policy)
        if (latestRestrictedUsage != null && latestRestrictedUsage > settings.lastChildNightAlertAt) {
            val result = remoteRepository.pushNightAlert(
                familyCode = settings.familyCode,
                childName = settings.childName,
                alertAt = latestRestrictedUsage,
                policy = policy
            )
            if (result.success) {
                preferencesRepository.setLastChildNightAlertAt(latestRestrictedUsage)
            }
        }
    }

    private suspend fun syncParent(settings: DeviceSettings) {
        remoteRepository.fetchRestrictionPolicy(settings.familyCode)?.let {
            preferencesRepository.saveRestrictionPolicy(it)
        }

        val alerts = remoteRepository.fetchAlerts(settings.familyCode)
        val latestAlert = alerts.maxByOrNull { it.timestamp } ?: return
        if (latestAlert.timestamp > settings.lastParentNotifiedAlertAt) {
            NotificationHelper.showParentAlert(applicationContext, latestAlert)
            preferencesRepository.setLastParentNotifiedAlertAt(latestAlert.timestamp)
        }
    }
}

object UsageSyncScheduler {
    private const val periodicWorkName = "phone_protect_usage_sync"
    private const val oneTimeWorkName = "phone_protect_force_sync"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            periodicWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UsageSyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            oneTimeWorkName,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
