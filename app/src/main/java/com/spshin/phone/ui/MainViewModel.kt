package com.spshin.phone.ui

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spshin.phone.AppConfig
import com.spshin.phone.FamilyRemoteRepository
import com.spshin.phone.NotificationHelper
import com.spshin.phone.PreferencesRepository
import com.spshin.phone.UsageRepository
import com.spshin.phone.UsageSyncScheduler
import com.spshin.phone.model.DailyUsageSummary
import com.spshin.phone.model.DeviceSettings
import com.spshin.phone.model.NightAlert
import com.spshin.phone.model.RemoteChildUsage
import com.spshin.phone.model.RestrictionPolicy
import com.spshin.phone.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferencesRepository = PreferencesRepository(application)
    private val usageRepository = UsageRepository(application)
    private val remoteRepository = FamilyRemoteRepository(application)

    val settings: StateFlow<DeviceSettings> = preferencesRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DeviceSettings()
    )

    private val _localUsage = MutableStateFlow<List<DailyUsageSummary>>(emptyList())
    val localUsage: StateFlow<List<DailyUsageSummary>> = _localUsage.asStateFlow()

    private val _remoteUsage = MutableStateFlow<List<RemoteChildUsage>>(emptyList())
    val remoteUsage: StateFlow<List<RemoteChildUsage>> = _remoteUsage.asStateFlow()

    private val _alerts = MutableStateFlow<List<NightAlert>>(emptyList())
    val alerts: StateFlow<List<NightAlert>> = _alerts.asStateFlow()

    private val _hasUsageAccess = MutableStateFlow(false)
    val hasUsageAccess: StateFlow<Boolean> = _hasUsageAccess.asStateFlow()

    private val _remoteReady = MutableStateFlow(false)
    val remoteReady: StateFlow<Boolean> = _remoteReady.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val parentUnlockedState = mutableStateOf(false)
    val isParentUnlocked: State<Boolean> = parentUnlockedState

    init {
        viewModelScope.launch {
            settings.collect { current ->
                if (current.role != UserRole.PARENT) {
                    parentUnlockedState.value = true
                }
            }
        }
    }

    fun saveSetup(role: UserRole, childName: String) {
        viewModelScope.launch {
            preferencesRepository.saveSetup(role, childName)
            parentUnlockedState.value = role != UserRole.PARENT
            UsageSyncScheduler.schedule(getApplication())
            UsageSyncScheduler.runNow(getApplication())
            refresh(forceRemoteSync = role == UserRole.CHILD)
        }
    }

    fun unlockParent(password: String) {
        if (password == AppConfig.parentPassword) {
            parentUnlockedState.value = true
            _statusMessage.value = "부모 모드가 열렸습니다."
            refresh()
        } else {
            _statusMessage.value = "비밀번호가 일치하지 않습니다."
        }
    }

    fun updateRestrictionPolicy(policy: RestrictionPolicy) {
        viewModelScope.launch {
            preferencesRepository.saveRestrictionPolicy(policy)
            val currentSettings = preferencesRepository.getSettings()

            if (_remoteReady.value && currentSettings.role == UserRole.PARENT) {
                val result = remoteRepository.saveRestrictionPolicy(currentSettings.familyCode, policy)
                _statusMessage.value = result.message
            } else {
                _statusMessage.value = "제한 시간이 저장되었습니다."
            }

            refresh(forceRemoteSync = currentSettings.role == UserRole.CHILD)
        }
    }

    fun refresh(forceRemoteSync: Boolean = false) {
        viewModelScope.launch {
            _isRefreshing.value = true
            var currentSettings = preferencesRepository.getSettings()
            _hasUsageAccess.value = usageRepository.hasUsageAccess()
            _remoteReady.value = remoteRepository.isConfigured()

            if (_remoteReady.value && currentSettings.role != UserRole.NONE) {
                remoteRepository.fetchRestrictionPolicy(currentSettings.familyCode)?.let { remotePolicy ->
                    if (remotePolicy != currentSettings.restrictionPolicy) {
                        preferencesRepository.saveRestrictionPolicy(remotePolicy)
                        currentSettings = preferencesRepository.getSettings()
                    }
                }
            }

            _localUsage.value = if (_hasUsageAccess.value) {
                usageRepository.getWeeklySummaries(currentSettings.restrictionPolicy)
            } else {
                emptyList()
            }

            when (currentSettings.role) {
                UserRole.CHILD -> {
                    if (forceRemoteSync) {
                        syncChildData(currentSettings)
                        UsageSyncScheduler.runNow(getApplication())
                    }
                }

                UserRole.PARENT -> refreshParentData(currentSettings)
                UserRole.NONE -> {
                    _remoteUsage.value = emptyList()
                    _alerts.value = emptyList()
                }
            }

            _isRefreshing.value = false
        }
    }

    fun resetSetup() {
        viewModelScope.launch {
            preferencesRepository.clearAll()
            parentUnlockedState.value = false
            _localUsage.value = emptyList()
            _remoteUsage.value = emptyList()
            _alerts.value = emptyList()
            _statusMessage.value = "앱 역할 설정이 초기화되었습니다."
        }
    }

    fun consumeStatus() {
        _statusMessage.value = null
    }

    private suspend fun syncChildData(settings: DeviceSettings) {
        if (!_hasUsageAccess.value) {
            _statusMessage.value = "사용량 접근 권한을 허용하면 자녀 기록을 동기화할 수 있습니다."
            return
        }

        if (settings.familyCode.isBlank()) {
            _statusMessage.value = "가족 코드가 비어 있어 동기화할 수 없습니다."
            return
        }

        val policy = remoteRepository.fetchRestrictionPolicy(settings.familyCode)
            ?: settings.restrictionPolicy
        preferencesRepository.saveRestrictionPolicy(policy)

        val summaries = usageRepository.getWeeklySummaries(policy)
        _localUsage.value = summaries

        val weeklyResult = remoteRepository.pushWeeklySummaries(
            familyCode = settings.familyCode,
            childName = settings.childName,
            summaries = summaries
        )
        _statusMessage.value = weeklyResult.message

        val latestRestrictedUsage = usageRepository.findLatestRestrictedUsage(policy)
        if (latestRestrictedUsage != null && latestRestrictedUsage > settings.lastChildNightAlertAt) {
            val alertResult = remoteRepository.pushNightAlert(
                familyCode = settings.familyCode,
                childName = settings.childName,
                alertAt = latestRestrictedUsage,
                policy = policy
            )
            if (alertResult.success) {
                preferencesRepository.setLastChildNightAlertAt(latestRestrictedUsage)
            }
            _statusMessage.value = alertResult.message
        }
    }

    private suspend fun refreshParentData(settings: DeviceSettings) {
        if (settings.familyCode.isBlank()) {
            _statusMessage.value = "가족 코드 설정 후 부모 대시보드를 사용할 수 있습니다."
            return
        }

        remoteRepository.fetchRestrictionPolicy(settings.familyCode)?.let {
            preferencesRepository.saveRestrictionPolicy(it)
        }

        _alerts.value = remoteRepository.fetchAlerts(settings.familyCode)
        _remoteUsage.value = remoteRepository.fetchWeeklyUsage(settings.familyCode)

        val latestAlert = _alerts.value.maxByOrNull { it.timestamp }
        if (latestAlert != null && latestAlert.timestamp > settings.lastParentNotifiedAlertAt) {
            NotificationHelper.showParentAlert(getApplication(), latestAlert)
            preferencesRepository.setLastParentNotifiedAlertAt(latestAlert.timestamp)
        }
    }
}
