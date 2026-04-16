package com.spshin.phone

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.spshin.phone.model.DailyUsageSummary
import com.spshin.phone.model.RestrictionPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToLong

class UsageRepository(
    context: Context
) {
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val zoneId = ZoneId.systemDefault()

    fun hasUsageAccess(): Boolean {
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 60_000L,
            now
        )
        return stats.isNotEmpty()
    }

    fun getWeeklySummaries(
        policy: RestrictionPolicy,
        now: LocalDateTime = LocalDateTime.now(zoneId)
    ): List<DailyUsageSummary> {
        val today = now.toLocalDate()
        return (0L..6L).map { offset ->
            buildDailySummary(today.minusDays(offset), now, policy)
        }.sortedBy { it.date }
    }

    fun findLatestRestrictedUsage(
        policy: RestrictionPolicy,
        now: LocalDateTime = LocalDateTime.now(zoneId)
    ): Long? {
        if (!isRestrictedTime(now.toLocalTime(), policy)) {
            return null
        }

        val timeRange = currentRestrictedWindow(policy, now)
        val start = timeRange.first.atZone(zoneId).toInstant().toEpochMilli()
        val end = minOf(
            timeRange.second.atZone(zoneId).toInstant().toEpochMilli(),
            now.atZone(zoneId).toInstant().toEpochMilli()
        )

        val events = usageStatsManager.queryEvents(start, end)
        var lastActiveAt: Long? = null

        iterateEvents(events) { event ->
            if (event.isForegroundEvent()) {
                lastActiveAt = event.timeStamp
            }
        }
        return lastActiveAt
    }

    private fun buildDailySummary(
        date: LocalDate,
        now: LocalDateTime,
        policy: RestrictionPolicy
    ): DailyUsageSummary {
        val dayStart = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEnd = if (date == now.toLocalDate()) {
            now.atZone(zoneId).toInstant().toEpochMilli()
        } else {
            date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        }

        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            dayStart,
            dayEnd
        )
        val totalMinutes = (usageStats.sumOf { it.totalTimeInForeground } / 60_000.0).roundToLong()

        var firstUsed: Long? = null
        var lastUsed: Long? = null
        var nightUsageDetected = false

        val events = usageStatsManager.queryEvents(dayStart, dayEnd)
        iterateEvents(events) { event ->
            if (event.isForegroundEvent()) {
                if (firstUsed == null) {
                    firstUsed = event.timeStamp
                }
                lastUsed = event.timeStamp
                val eventTime = toLocalDateTime(event.timeStamp).toLocalTime()
                if (isRestrictedTime(eventTime, policy)) {
                    nightUsageDetected = true
                }
            } else if (event.isBackgroundEvent()) {
                lastUsed = event.timeStamp
            }
        }

        return DailyUsageSummary(
            date = date,
            firstUsed = firstUsed?.let(::toLocalDateTime),
            lastUsed = lastUsed?.let(::toLocalDateTime),
            totalMinutes = totalMinutes,
            nightUsageDetected = nightUsageDetected
        )
    }

    private fun iterateEvents(events: UsageEvents, block: (UsageEvents.Event) -> Unit) {
        while (events.hasNextEvent()) {
            val event = UsageEvents.Event()
            events.getNextEvent(event)
            block(event)
        }
    }

    private fun UsageEvents.Event.isForegroundEvent(): Boolean =
        eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
            eventType == UsageEvents.Event.MOVE_TO_FOREGROUND

    private fun UsageEvents.Event.isBackgroundEvent(): Boolean =
        eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
            eventType == UsageEvents.Event.ACTIVITY_STOPPED ||
            eventType == UsageEvents.Event.MOVE_TO_BACKGROUND

    private fun isRestrictedTime(time: LocalTime, policy: RestrictionPolicy): Boolean {
        val start = LocalTime.of(policy.startHour, policy.startMinute)
        val end = LocalTime.of(policy.endHour, policy.endMinute)
        return when {
            start == end -> true
            start < end -> time >= start && time < end
            else -> time >= start || time < end
        }
    }

    private fun currentRestrictedWindow(
        policy: RestrictionPolicy,
        now: LocalDateTime
    ): Pair<LocalDateTime, LocalDateTime> {
        val start = LocalTime.of(policy.startHour, policy.startMinute)
        val end = LocalTime.of(policy.endHour, policy.endMinute)
        val today = now.toLocalDate()
        val currentTime = now.toLocalTime()

        return when {
            start == end -> today.atStartOfDay() to today.plusDays(1).atStartOfDay()
            start < end -> today.atTime(start) to today.atTime(end)
            currentTime >= start -> today.atTime(start) to today.plusDays(1).atTime(end)
            else -> today.minusDays(1).atTime(start) to today.atTime(end)
        }
    }

    private fun toLocalDateTime(value: Long): LocalDateTime =
        Instant.ofEpochMilli(value).atZone(zoneId).toLocalDateTime()
}
