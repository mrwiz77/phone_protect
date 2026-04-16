package com.spshin.phone

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spshin.phone.model.DailyUsageSummary
import com.spshin.phone.model.DeviceSettings
import com.spshin.phone.model.NightAlert
import com.spshin.phone.model.RemoteChildUsage
import com.spshin.phone.model.RestrictionPolicy
import com.spshin.phone.model.UserRole
import com.spshin.phone.ui.MainViewModel
import com.spshin.phone.ui.theme.Phone_protectTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Phone_protectTheme {
                PhoneProtectApp()
            }
        }
    }
}

@Composable
private fun PhoneProtectApp(viewModel: MainViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val localUsage by viewModel.localUsage.collectAsStateWithLifecycle()
    val remoteUsage by viewModel.remoteUsage.collectAsStateWithLifecycle()
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    val hasUsageAccess by viewModel.hasUsageAccess.collectAsStateWithLifecycle()
    val remoteReady by viewModel.remoteReady.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val isParentUnlocked by viewModel.isParentUnlocked

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.refresh()
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeStatus()
        }
    }

    val gradient = Brush.verticalGradient(
        listOf(Color(0xFFF5F1E8), Color(0xFFE2E8F0), Color(0xFFF8FAFC))
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .padding(innerPadding),
            color = Color.Transparent
        ) {
            when {
                settings.role == UserRole.NONE -> SetupScreen(
                    onSave = viewModel::saveSetup
                )

                settings.role == UserRole.PARENT && !isParentUnlocked -> ParentUnlockScreen(
                    onUnlock = viewModel::unlockParent,
                    onReset = viewModel::resetSetup
                )

                else -> DashboardScreen(
                    settings = settings,
                    localUsage = localUsage,
                    remoteUsage = remoteUsage,
                    alerts = alerts,
                    hasUsageAccess = hasUsageAccess,
                    remoteReady = remoteReady,
                    isRefreshing = isRefreshing,
                    onOpenUsageAccess = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                    onRefresh = { viewModel.refresh(forceRemoteSync = true) },
                    onReset = viewModel::resetSetup,
                    onUpdatePolicy = viewModel::updateRestrictionPolicy
                )
            }
        }
    }
}

@Composable
private fun SetupScreen(
    onSave: (UserRole, String) -> Unit
) {
    var role by rememberSaveable { mutableStateOf(UserRole.CHILD) }
    var childName by rememberSaveable { mutableStateOf("") }

    AppPage {
        HeaderBlock(
            title = "폰 지킴이",
            subtitle = "가족 코드는 내부적으로 SGF로 고정되어 자동 연결됩니다."
        )
        HighlightCard(
            title = "앱 역할 선택",
            body = "같은 앱을 자녀폰과 부모폰에 설치한 뒤 역할만 다르게 설정합니다."
        ) {
            RoleOption("자녀 기기", role == UserRole.CHILD) { role = UserRole.CHILD }
            RoleOption("부모 기기", role == UserRole.PARENT) { role = UserRole.PARENT }
        }
        HighlightCard(
            title = "기본 연결 정보",
            body = "가족 코드는 자동으로 SGF가 사용됩니다. 자녀 기기만 이름을 입력하면 됩니다."
        ) {
            if (role == UserRole.CHILD) {
                OutlinedTextField(
                    value = childName,
                    onValueChange = { childName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("자녀 이름") },
                    singleLine = true
                )
            } else {
                Text(
                    text = "부모 기기는 추가 입력 없이 바로 설정할 수 있습니다.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(
                onClick = {
                    onSave(
                        role,
                        if (role == UserRole.CHILD) childName.ifBlank { "우리 아이" } else ""
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Text("설정 저장")
            }
        }
    }
}

@Composable
private fun ParentUnlockScreen(
    onUnlock: (String) -> Unit,
    onReset: () -> Unit
) {
    var password by rememberSaveable { mutableStateOf("") }

    AppPage(verticalArrangement = Arrangement.Center) {
        HeaderBlock(
            title = "부모 전용 잠금",
            subtitle = "부모 앱은 비밀번호 입력 후에만 열리도록 설정되어 있습니다."
        )
        HighlightCard(
            title = "보호자 인증",
            body = "등록된 비밀번호를 입력해 주세요."
        ) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("비밀번호") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            Button(
                onClick = { onUnlock(password) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Text("대시보드 열기")
            }
            TextButton(
                onClick = onReset,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("역할 다시 설정")
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    settings: DeviceSettings,
    localUsage: List<DailyUsageSummary>,
    remoteUsage: List<RemoteChildUsage>,
    alerts: List<NightAlert>,
    hasUsageAccess: Boolean,
    remoteReady: Boolean,
    isRefreshing: Boolean,
    onOpenUsageAccess: () -> Unit,
    onRefresh: () -> Unit,
    onReset: () -> Unit,
    onUpdatePolicy: (RestrictionPolicy) -> Unit
) {
    AppPage {
        HeaderBlock(
            title = if (settings.role == UserRole.CHILD) {
                "${settings.childName.ifBlank { "자녀" }} 기기"
            } else {
                "부모 대시보드"
            },
            subtitle = "가족 코드 SGF  |  제한 시간 ${settings.restrictionPolicy.label()}"
        )

        StatusStrip(
            hasUsageAccess = hasUsageAccess,
            remoteReady = remoteReady,
            isRefreshing = isRefreshing
        )

        HighlightCard(
            title = "백그라운드 확인",
            body = "역할 설정이 끝난 뒤에는 자녀가 앱을 열지 않아도 15분 주기로 사용 기록과 제한 시간 경고를 동기화합니다."
        ) {
            Text(
                text = "휴대폰 재부팅 후에도 자동으로 동기화를 다시 시작하도록 설정되어 있습니다.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (!hasUsageAccess) {
            HighlightCard(
                title = "사용량 접근 권한 필요",
                body = "주간 사용 시간과 제한 시간 감지는 Android 사용량 접근 권한이 있어야 동작합니다."
            ) {
                Button(
                    onClick = onOpenUsageAccess,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("사용량 접근 권한 열기")
                }
            }
        }

        ControlRow(
            onRefresh = onRefresh,
            onReset = onReset,
            refreshLabel = if (settings.role == UserRole.CHILD) "지금 동기화" else "새로고침"
        )

        if (settings.role == UserRole.PARENT) {
            HighlightCard(
                title = "자녀 사용 금지 시간",
                body = "부모 기기에서 제한 시간을 바꾸면 자녀 기기의 백그라운드 감지 기준도 함께 변경됩니다."
            ) {
                RestrictionPolicyEditor(
                    policy = settings.restrictionPolicy,
                    editable = true,
                    onSave = onUpdatePolicy
                )
            }
        } else {
            HighlightCard(
                title = "현재 제한 시간",
                body = "이 시간대에 자녀 기기 화면 사용이 감지되면 부모 기기로 경고가 전달됩니다."
            ) {
                Text(
                    text = settings.restrictionPolicy.label(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (settings.role == UserRole.CHILD) {
            HighlightCard(
                title = "주간 사용 기록",
                body = "날짜별 처음 사용 시각, 마지막 사용 시각, 총 사용 시간을 표시합니다."
            ) {
                UsageSummaryList(localUsage, settings.restrictionPolicy)
            }
        } else {
            HighlightCard(
                title = "자녀 제한 시간 경고",
                body = "제한 시간 안에 자녀 사용이 감지되면 여기에 기록되고 부모 기기에도 알림이 뜹니다."
            ) {
                AlertList(alerts)
            }
            HighlightCard(
                title = "자녀 주간 사용 기록",
                body = "자녀 기기가 백그라운드로 올린 최근 7일 사용 기록입니다."
            ) {
                RemoteUsageList(remoteUsage, settings.restrictionPolicy)
            }
        }
    }
}

@Composable
private fun RestrictionPolicyEditor(
    policy: RestrictionPolicy,
    editable: Boolean,
    onSave: (RestrictionPolicy) -> Unit
) {
    var startHour by rememberSaveable(policy.startHour, policy.startMinute, policy.endHour, policy.endMinute) {
        mutableStateOf(policy.startHour.toString().padStart(2, '0'))
    }
    var startMinute by rememberSaveable(policy.startHour, policy.startMinute, policy.endHour, policy.endMinute) {
        mutableStateOf(policy.startMinute.toString().padStart(2, '0'))
    }
    var endHour by rememberSaveable(policy.startHour, policy.startMinute, policy.endHour, policy.endMinute) {
        mutableStateOf(policy.endHour.toString().padStart(2, '0'))
    }
    var endMinute by rememberSaveable(policy.startHour, policy.startMinute, policy.endHour, policy.endMinute) {
        mutableStateOf(policy.endMinute.toString().padStart(2, '0'))
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        TimeField(
            value = startHour,
            label = "시작 시",
            enabled = editable,
            onValueChange = { startHour = it.filter(Char::isDigit).take(2) },
            modifier = Modifier.weight(1f)
        )
        TimeField(
            value = startMinute,
            label = "시작 분",
            enabled = editable,
            onValueChange = { startMinute = it.filter(Char::isDigit).take(2) },
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        TimeField(
            value = endHour,
            label = "종료 시",
            enabled = editable,
            onValueChange = { endHour = it.filter(Char::isDigit).take(2) },
            modifier = Modifier.weight(1f)
        )
        TimeField(
            value = endMinute,
            label = "종료 분",
            enabled = editable,
            onValueChange = { endMinute = it.filter(Char::isDigit).take(2) },
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )
    }

    Text(
        text = "예시: 22:00 ~ 06:00 처럼 자정을 넘기는 시간도 가능합니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp)
    )

    if (editable) {
        Button(
            onClick = {
                onSave(
                    RestrictionPolicy(
                        startHour = startHour.toIntOrNull()?.coerceIn(0, 23) ?: policy.startHour,
                        startMinute = startMinute.toIntOrNull()?.coerceIn(0, 59) ?: policy.startMinute,
                        endHour = endHour.toIntOrNull()?.coerceIn(0, 23) ?: policy.endHour,
                        endMinute = endMinute.toIntOrNull()?.coerceIn(0, 59) ?: policy.endMinute
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            Text("제한 시간 저장")
        }
    }
}

@Composable
private fun TimeField(
    value: String,
    label: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}

@Composable
private fun AppPage(
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = verticalArrangement
    ) {
        content()
    }
}

@Composable
private fun HeaderBlock(title: String, subtitle: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 18.dp)
    )
}

@Composable
private fun HighlightCard(
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.88f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
            content()
        }
    }
}

@Composable
private fun RoleOption(title: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(title)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusStrip(
    hasUsageAccess: Boolean,
    remoteReady: Boolean,
    isRefreshing: Boolean
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        StatusPill("사용량 권한 ${if (hasUsageAccess) "연결됨" else "미허용"}")
        StatusPill("Firebase ${if (remoteReady) "연결됨" else "미설정"}")
        if (isRefreshing) {
            StatusPill("동기화 진행 중")
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Box(
        modifier = Modifier
            .padding(end = 10.dp, bottom = 10.dp)
            .background(Color(0xFFF1F5F9), RoundedCornerShape(100.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text)
    }
}

@Composable
private fun ControlRow(
    onRefresh: () -> Unit,
    onReset: () -> Unit,
    refreshLabel: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Button(onClick = onRefresh, modifier = Modifier.weight(1f)) {
            Text(refreshLabel)
        }
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Text("역할 재설정")
        }
    }
}

@Composable
private fun UsageSummaryList(
    summaries: List<DailyUsageSummary>,
    policy: RestrictionPolicy
) {
    if (summaries.isEmpty()) {
        EmptyState("권한 허용 후 새로고침하면 최근 7일 사용 기록이 표시됩니다.")
        return
    }
    summaries.sortedByDescending { it.date }.forEach { summary ->
        SummaryRow(
            title = summary.date.format(DateTimeFormatter.ofPattern("M월 d일")),
            start = summary.firstUsed?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "-",
            end = summary.lastUsed?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "-",
            total = "${summary.totalMinutes}분",
            highlighted = summary.nightUsageDetected,
            restrictionLabel = policy.label()
        )
    }
}

@Composable
private fun RemoteUsageList(
    remoteUsage: List<RemoteChildUsage>,
    policy: RestrictionPolicy
) {
    if (remoteUsage.isEmpty()) {
        EmptyState("자녀 기기에서 역할 설정이 끝나고 백그라운드 동기화가 돌면 여기에 표시됩니다.")
        return
    }
    remoteUsage.forEach { childUsage ->
        Text(
            text = childUsage.childName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        childUsage.summaries.sortedByDescending { it.date }.forEach { summary ->
            SummaryRow(
                title = summary.date.format(DateTimeFormatter.ofPattern("M월 d일")),
                start = summary.firstUsed?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "-",
                end = summary.lastUsed?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "-",
                total = "${summary.totalMinutes}분",
                highlighted = summary.nightUsageDetected,
                restrictionLabel = policy.label()
            )
        }
    }
}

@Composable
private fun AlertList(alerts: List<NightAlert>) {
    if (alerts.isEmpty()) {
        EmptyState("아직 제한 시간 사용 경고가 없습니다.")
        return
    }
    alerts.sortedByDescending { it.timestamp }.forEach { alert ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1F2))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("${alert.childName} 제한 시간 사용 감지", fontWeight = FontWeight.SemiBold)
                Text(
                    text = Instant.ofEpochMilli(alert.timestamp)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("M월 d일 HH:mm")),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(text = alert.message, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun SummaryRow(
    title: String,
    start: String,
    end: String,
    total: String,
    highlighted: Boolean,
    restrictionLabel: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) Color(0xFFFFF7ED) else Color(0xFFF8FAFC)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text("사용 시작 $start  |  사용 종료 $end", modifier = Modifier.padding(top = 6.dp))
            Text(
                text = "총 사용 시간 $total${if (highlighted) "  |  제한 시간($restrictionLabel) 사용 감지" else ""}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF8FAFC), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun RestrictionPolicy.label(): String =
    "%02d:%02d-%02d:%02d".format(startHour, startMinute, endHour, endMinute)
