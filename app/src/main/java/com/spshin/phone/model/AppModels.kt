package com.spshin.phone.model

import java.time.LocalDate
import java.time.LocalDateTime

enum class UserRole(val value: String) {
    NONE("none"),
    CHILD("child"),
    PARENT("parent");

    companion object {
        fun fromValue(value: String?): UserRole = entries.firstOrNull { it.value == value } ?: NONE
    }
}

data class DeviceSettings(
    val role: UserRole = UserRole.NONE,
    val familyCode: String = "SGF",
    val childName: String = "",
    val lastChildNightAlertAt: Long = 0L,
    val lastParentNotifiedAlertAt: Long = 0L,
    val restrictionPolicy: RestrictionPolicy = RestrictionPolicy()
)

data class RestrictionPolicy(
    val startHour: Int = 22,
    val startMinute: Int = 0,
    val endHour: Int = 6,
    val endMinute: Int = 0
)

data class DailyUsageSummary(
    val date: LocalDate,
    val firstUsed: LocalDateTime?,
    val lastUsed: LocalDateTime?,
    val totalMinutes: Long,
    val nightUsageDetected: Boolean
)

data class RemoteChildUsage(
    val childId: String,
    val childName: String,
    val summaries: List<DailyUsageSummary>
)

data class NightAlert(
    val id: String,
    val childId: String,
    val childName: String,
    val timestamp: Long,
    val message: String
)

data class SyncResult(
    val success: Boolean,
    val message: String
)
