package com.spshin.phone

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.spshin.phone.model.DailyUsageSummary
import com.spshin.phone.model.NightAlert
import com.spshin.phone.model.RemoteChildUsage
import com.spshin.phone.model.RestrictionPolicy
import com.spshin.phone.model.SyncResult
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class FamilyRemoteRepository(
    private val context: Context
) {
    suspend fun isConfigured(): Boolean = firebaseOptions() != null

    suspend fun pushWeeklySummaries(
        familyCode: String,
        childName: String,
        summaries: List<DailyUsageSummary>
    ): SyncResult {
        val firestore = firestoreOrNull() ?: return SyncResult(
            success = false,
            message = "Firebase 설정이 없어 부모 폰 연동이 비활성화되어 있습니다."
        )

        return runCatching {
            val safeFamilyCode = safeKey(familyCode)
            val childId = safeKey(childName.ifBlank { "child" })
            val batch = firestore.batch()
            val childDoc = firestore.collection("families")
                .document(safeFamilyCode)
                .collection("children")
                .document(childId)

            batch.set(
                childDoc,
                mapOf(
                    "childId" to childId,
                    "childName" to childName,
                    "updatedAt" to System.currentTimeMillis()
                )
            )

            summaries.forEach { summary ->
                val summaryDoc = childDoc.collection("weekly_usage").document(summary.date.toString())
                batch.set(
                    summaryDoc,
                    mapOf(
                        "date" to summary.date.toString(),
                        "firstUsedAt" to summary.firstUsed?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
                        "lastUsedAt" to summary.lastUsed?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
                        "totalMinutes" to summary.totalMinutes,
                        "nightUsageDetected" to summary.nightUsageDetected,
                        "childId" to childId,
                        "childName" to childName
                    )
                )
            }

            batch.commit().await()
            SyncResult(true, "자녀 기기 기록이 부모 보기용으로 동기화되었습니다.")
        }.getOrElse { error ->
            SyncResult(false, error.message ?: "주간 사용 기록을 동기화하지 못했습니다.")
        }
    }

    suspend fun pushNightAlert(
        familyCode: String,
        childName: String,
        alertAt: Long,
        policy: RestrictionPolicy
    ): SyncResult {
        val firestore = firestoreOrNull() ?: return SyncResult(
            success = false,
            message = "Firebase 설정이 없어 부모 폰 경고 전송이 비활성화되어 있습니다."
        )

        return runCatching {
            val safeFamilyCode = safeKey(familyCode)
            val childId = safeKey(childName.ifBlank { "child" })
            val docId = "$childId-$alertAt"
            val message = "${childName.ifBlank { "자녀" }} 기기에서 제한 시간 ${policy.label()} 사용이 감지되었습니다."
            firestore.collection("families")
                .document(safeFamilyCode)
                .collection("alerts")
                .document(docId)
                .set(
                    mapOf(
                        "id" to docId,
                        "childId" to childId,
                        "childName" to childName,
                        "timestamp" to alertAt,
                        "message" to message
                    )
                )
                .await()
            SyncResult(true, "부모 기기로 제한 시간 사용 경고를 전송했습니다.")
        }.getOrElse { error ->
            SyncResult(false, error.message ?: "부모 기기 경고를 전송하지 못했습니다.")
        }
    }

    suspend fun saveRestrictionPolicy(
        familyCode: String,
        policy: RestrictionPolicy
    ): SyncResult {
        val firestore = firestoreOrNull() ?: return SyncResult(
            success = false,
            message = "Firebase 설정이 없어 제한 시간 저장이 비활성화되어 있습니다."
        )

        return runCatching {
            firestore.collection("families")
                .document(safeKey(familyCode))
                .collection("config")
                .document("policy")
                .set(
                    mapOf(
                        "startHour" to policy.startHour,
                        "startMinute" to policy.startMinute,
                        "endHour" to policy.endHour,
                        "endMinute" to policy.endMinute,
                        "updatedAt" to System.currentTimeMillis()
                    )
                )
                .await()
            SyncResult(true, "제한 시간이 저장되었습니다.")
        }.getOrElse { error ->
            SyncResult(false, error.message ?: "제한 시간을 저장하지 못했습니다.")
        }
    }

    suspend fun fetchRestrictionPolicy(familyCode: String): RestrictionPolicy? {
        val firestore = firestoreOrNull() ?: return null

        return runCatching {
            val snapshot = firestore.collection("families")
                .document(safeKey(familyCode))
                .collection("config")
                .document("policy")
                .get()
                .await()

            if (!snapshot.exists()) {
                null
            } else {
                RestrictionPolicy(
                    startHour = snapshot.getLong("startHour")?.toInt()
                        ?: AppConfig.defaultRestrictionPolicy.startHour,
                    startMinute = snapshot.getLong("startMinute")?.toInt()
                        ?: AppConfig.defaultRestrictionPolicy.startMinute,
                    endHour = snapshot.getLong("endHour")?.toInt()
                        ?: AppConfig.defaultRestrictionPolicy.endHour,
                    endMinute = snapshot.getLong("endMinute")?.toInt()
                        ?: AppConfig.defaultRestrictionPolicy.endMinute
                )
            }
        }.getOrNull()
    }

    suspend fun fetchAlerts(familyCode: String): List<NightAlert> {
        val firestore = firestoreOrNull() ?: return emptyList()
        val safeFamilyCode = safeKey(familyCode)

        return runCatching {
            firestore.collection("families")
                .document(safeFamilyCode)
                .collection("alerts")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(20)
                .get()
                .await()
                .documents
                .mapNotNull { document ->
                    val timestamp = document.getLong("timestamp") ?: return@mapNotNull null
                    NightAlert(
                        id = document.getString("id").orEmpty(),
                        childId = document.getString("childId").orEmpty(),
                        childName = document.getString("childName").orEmpty(),
                        timestamp = timestamp,
                        message = document.getString("message").orEmpty()
                    )
                }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchWeeklyUsage(familyCode: String): List<RemoteChildUsage> {
        val firestore = firestoreOrNull() ?: return emptyList()
        val safeFamilyCode = safeKey(familyCode)

        return runCatching {
            val childDocuments = firestore.collection("families")
                .document(safeFamilyCode)
                .collection("children")
                .get()
                .await()
                .documents

            childDocuments.map { childDocument ->
                val childId = childDocument.id
                val childName = childDocument.getString("childName").orEmpty().ifBlank { childId }
                val summaries = firestore.collection("families")
                    .document(safeFamilyCode)
                    .collection("children")
                    .document(childId)
                    .collection("weekly_usage")
                    .get()
                    .await()
                    .documents
                    .mapNotNull { summary ->
                        val date = summary.getString("date")?.let(LocalDate::parse) ?: return@mapNotNull null
                        DailyUsageSummary(
                            date = date,
                            firstUsed = summary.getLong("firstUsedAt")?.let(::millisToLocalDateTime),
                            lastUsed = summary.getLong("lastUsedAt")?.let(::millisToLocalDateTime),
                            totalMinutes = summary.getLong("totalMinutes") ?: 0L,
                            nightUsageDetected = summary.getBoolean("nightUsageDetected") ?: false
                        )
                    }

                RemoteChildUsage(
                    childId = childId,
                    childName = childName,
                    summaries = summaries.sortedByDescending { it.date }.take(7)
                )
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun firestoreOrNull(): FirebaseFirestore? {
        val app = ensureFirebaseApp() ?: return null
        ensureAnonymousAuth(app)
        return FirebaseFirestore.getInstance(app)
    }

    private suspend fun ensureAnonymousAuth(app: FirebaseApp) {
        val auth = FirebaseAuth.getInstance(app)
        if (auth.currentUser == null) {
            auth.signInAnonymously().await()
        }
    }

    private fun ensureFirebaseApp(): FirebaseApp? {
        FirebaseApp.getApps(context).firstOrNull()?.let { return it }
        val options = firebaseOptions() ?: return null
        return FirebaseApp.initializeApp(context, options, "phone_protect")
    }

    private fun firebaseOptions(): FirebaseOptions? {
        if (
            BuildConfig.FIREBASE_API_KEY.isBlank() ||
            BuildConfig.FIREBASE_APP_ID.isBlank() ||
            BuildConfig.FIREBASE_PROJECT_ID.isBlank()
        ) {
            return null
        }

        return FirebaseOptions.Builder()
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .apply {
                if (BuildConfig.FIREBASE_GCM_SENDER_ID.isNotBlank()) {
                    setGcmSenderId(BuildConfig.FIREBASE_GCM_SENDER_ID)
                }
            }
            .build()
    }

    private fun millisToLocalDateTime(value: Long): LocalDateTime =
        Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDateTime()

    private fun safeKey(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "_").ifBlank { "family" }
}

private fun RestrictionPolicy.label(): String =
    "%02d:%02d-%02d:%02d".format(startHour, startMinute, endHour, endMinute)
