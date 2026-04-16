package com.spshin.phone

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.spshin.phone.model.DeviceSettings
import com.spshin.phone.model.RestrictionPolicy
import com.spshin.phone.model.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "phone_protect_prefs")

class PreferencesRepository(
    private val context: Context
) {
    private object Keys {
        val role = stringPreferencesKey("role")
        val familyCode = stringPreferencesKey("family_code")
        val childName = stringPreferencesKey("child_name")
        val lastChildNightAlertAt = longPreferencesKey("last_child_night_alert_at")
        val lastParentNotifiedAlertAt = longPreferencesKey("last_parent_notified_alert_at")
        val restrictionStartHour = longPreferencesKey("restriction_start_hour")
        val restrictionStartMinute = longPreferencesKey("restriction_start_minute")
        val restrictionEndHour = longPreferencesKey("restriction_end_hour")
        val restrictionEndMinute = longPreferencesKey("restriction_end_minute")
    }

    val settings: Flow<DeviceSettings> = context.dataStore.data.map { prefs ->
        DeviceSettings(
            role = UserRole.fromValue(prefs[Keys.role]),
            familyCode = prefs[Keys.familyCode].orEmpty().ifBlank { AppConfig.familyCode },
            childName = prefs[Keys.childName].orEmpty(),
            lastChildNightAlertAt = prefs[Keys.lastChildNightAlertAt] ?: 0L,
            lastParentNotifiedAlertAt = prefs[Keys.lastParentNotifiedAlertAt] ?: 0L,
            restrictionPolicy = RestrictionPolicy(
                startHour = (prefs[Keys.restrictionStartHour] ?: AppConfig.defaultRestrictionPolicy.startHour.toLong()).toInt(),
                startMinute = (prefs[Keys.restrictionStartMinute] ?: AppConfig.defaultRestrictionPolicy.startMinute.toLong()).toInt(),
                endHour = (prefs[Keys.restrictionEndHour] ?: AppConfig.defaultRestrictionPolicy.endHour.toLong()).toInt(),
                endMinute = (prefs[Keys.restrictionEndMinute] ?: AppConfig.defaultRestrictionPolicy.endMinute.toLong()).toInt()
            )
        )
    }

    suspend fun getSettings(): DeviceSettings = settings.first()

    suspend fun saveSetup(role: UserRole, childName: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.role] = role.value
            prefs[Keys.familyCode] = AppConfig.familyCode
            prefs[Keys.childName] = childName.trim()
        }
    }

    suspend fun saveRestrictionPolicy(policy: RestrictionPolicy) {
        context.dataStore.edit { prefs ->
            prefs[Keys.restrictionStartHour] = policy.startHour.toLong()
            prefs[Keys.restrictionStartMinute] = policy.startMinute.toLong()
            prefs[Keys.restrictionEndHour] = policy.endHour.toLong()
            prefs[Keys.restrictionEndMinute] = policy.endMinute.toLong()
        }
    }

    suspend fun setLastChildNightAlertAt(value: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.lastChildNightAlertAt] = value
        }
    }

    suspend fun setLastParentNotifiedAlertAt(value: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.lastParentNotifiedAlertAt] = value
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
