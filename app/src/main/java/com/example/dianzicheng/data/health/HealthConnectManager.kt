package com.example.dianzicheng.data.health

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.example.dianzicheng.domain.BodyMeasurement
import java.time.Instant
import java.time.ZoneOffset

/**
 * Health Connect 读写封装。
 *
 * 负责把体重与体脂写入系统 Health Connect，并支持按测量时间删除对应记录。
 * 小米运动健康等三方 App 是否展示这里写入的数据，取决于对方 App 自身的实现。
 */
class HealthConnectManager(private val context: Context) {

    private val healthConnectClient: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    /** 权限集合：体重与体脂的读写 */
    val permissions = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class)
    )

    /** 最近一次写入失败的原因，供界面展示 */
    @Volatile
    var lastWriteError: String? = null
        private set

    fun isAvailable(): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    /** 系统 Health Connect 可用状态的中文说明 */
    fun sdkStatusText(): String = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> "可用"
        HealthConnectClient.SDK_UNAVAILABLE -> "不可用（系统未提供 Health Connect 框架）"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "需更新 Health Connect 应用"
        else -> "未知"
    }

    /** 是否已获得全部读写权限 */
    suspend fun hasAllPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        return try {
            client.permissionController.getGrantedPermissions().containsAll(permissions)
        } catch (e: Exception) {
            Log.w(TAG, "读取已授权权限失败", e)
            false
        }
    }

    /** 一次性把某条测量记录构造成 HC 记录（体重必写，体脂有值才写） */
    private fun buildRecords(measurement: BodyMeasurement): List<androidx.health.connect.client.records.Record> {
        val instant = Instant.ofEpochMilli(measurement.measuredAtEpochMs)
        val zoneOffset = ZoneOffset.systemDefault().rules.getOffset(instant)
        val metadata = Metadata(
            recordingMethod = Metadata.RECORDING_METHOD_ACTIVELY_RECORDED
        )

        val records = mutableListOf<androidx.health.connect.client.records.Record>(
            WeightRecord(
                weight = Mass.kilograms(measurement.weightKg),
                time = instant,
                zoneOffset = zoneOffset,
                metadata = metadata
            )
        )

        if (measurement.bodyFatPct > 0.0) {
            records.add(
                BodyFatRecord(
                    percentage = Percentage(measurement.bodyFatPct),
                    time = instant,
                    zoneOffset = zoneOffset,
                    metadata = metadata
                )
            )
        }
        return records
    }

    suspend fun writeMeasurement(measurement: BodyMeasurement): Boolean {
        val client = healthConnectClient ?: run {
            lastWriteError = "Health Connect 不可用：${sdkStatusText()}"
            Log.w(TAG, lastWriteError!!)
            return false
        }
        if (!hasAllPermissions()) {
            lastWriteError = "缺少 Health Connect 读写权限，请在授权页全部勾选"
            Log.w(TAG, lastWriteError!!)
            return false
        }

        return try {
            client.insertRecords(buildRecords(measurement))
            lastWriteError = null
            Log.d(TAG, "已写入 Health Connect：${measurement.weightKg}kg @ ${measurement.measuredAtEpochMs}")
            true
        } catch (e: Exception) {
            lastWriteError = e.message?.take(160) ?: e.javaClass.simpleName
            Log.e(TAG, "写入 Health Connect 失败", e)
            false
        }
    }

    /**
     * 从 Health Connect 删除与本地记录同一时刻写入的体重/体脂数据。
     * 历史页删除记录时调用，保持两端数据一致。
     */
    suspend fun deleteMeasurement(measurement: BodyMeasurement): Boolean {
        val client = healthConnectClient ?: run {
            Log.w(TAG, "Health Connect Client is unavailable")
            return false
        }

        return try {
            val instant = Instant.ofEpochMilli(measurement.measuredAtEpochMs)
            val zoneOffset = ZoneOffset.systemDefault().rules.getOffset(instant)
            val window = TimeRangeFilter.between(instant.minusMillis(1000), instant.plusMillis(1000))

            try {
                val weightResponse = client.readRecords(
                    ReadRecordsRequest(recordType = WeightRecord::class, timeRangeFilter = window)
                )
                val weightIds = weightResponse.records
                    .filter { it.time == instant && it.zoneOffset == zoneOffset }
                    .map { it.metadata.id }
                    .toList()
                if (weightIds.isNotEmpty()) {
                    client.deleteRecords(
                        recordType = WeightRecord::class,
                        recordIdsList = weightIds,
                        clientRecordIdsList = emptyList()
                    )
                }

                val fatResponse = client.readRecords(
                    ReadRecordsRequest(recordType = BodyFatRecord::class, timeRangeFilter = window)
                )
                val fatIds = fatResponse.records
                    .filter { it.time == instant && it.zoneOffset == zoneOffset }
                    .map { it.metadata.id }
                    .toList()
                if (fatIds.isNotEmpty()) {
                    client.deleteRecords(
                        recordType = BodyFatRecord::class,
                        recordIdsList = fatIds,
                        clientRecordIdsList = emptyList()
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "Timestamp-based cleanup skipped: ${e.message}")
            }

            Log.d(TAG, "Deleted weight and fat records from Health Connect")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete measurement from Health Connect", e)
            false
        }
    }

    /**
     * 批量将历史数据同步到 Health Connect
     * @return Pair(成功条数, 失败条数)
     */
    suspend fun batchWriteMeasurements(measurements: List<BodyMeasurement>): Pair<Int, Int> {
        val client = healthConnectClient ?: return Pair(0, measurements.size)
        if (!hasAllPermissions()) {
            lastWriteError = "缺少 Health Connect 读写权限，请在授权页全部勾选"
            return Pair(0, measurements.size)
        }

        var success = 0
        var failed = 0
        var firstError: String? = null

        // 每批最多 20 条，避免单次请求过大
        measurements.chunked(20).forEach { batch ->
            try {
                val records = mutableListOf<androidx.health.connect.client.records.Record>()
                batch.forEach { measurement -> records.addAll(buildRecords(measurement)) }
                client.insertRecords(records)
                success += batch.size
                Log.d(TAG, "Batch written ${batch.size} measurements")
            } catch (e: Exception) {
                Log.e(TAG, "Batch write failed", e)
                failed += batch.size
                if (firstError == null) firstError = e.message?.take(160) ?: e.javaClass.simpleName
            }
        }

        lastWriteError = firstError
        return Pair(success, failed)
    }

    /**
     * 检测小米健康是否已安装
     */
    fun isMiHealthInstalled(): Boolean {
        val miHealthPackages = listOf(
            "com.mi.health",
            "com.xiaomi.hm.health",
            "com.xiaomi.wearable"
        )
        return miHealthPackages.any { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * 打开小米健康应用
     */
    fun openMiHealth() {
        val miHealthPackages = listOf("com.mi.health", "com.xiaomi.hm.health", "com.xiaomi.wearable")
        for (pkg in miHealthPackages) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open $pkg", e)
            }
        }
        // 如果找不到小米健康，打开应用市场搜索页
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.mi.health"))
            marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(marketIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open market", e)
        }
    }

    /**
     * 打开 Health Connect 设置页（让用户管理已授权 App）
     */
    fun openHealthConnectSettings() {
        try {
            val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open ACTION_HEALTH_CONNECT_SETTINGS, trying fallback", e)
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                try {
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))
                    webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(webIntent)
                } catch (e3: Exception) {
                    Log.e(TAG, "Cannot open Health Connect settings or play store", e3)
                }
            }
        }
    }

    private companion object {
        const val TAG = "HealthConnectManager"
    }
}
