package com.example.dianzicheng.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 测量历史页面的 ViewModel。
 *
 * 职责：
 * 1. 提供测量历史记录列表（按时间倒序），历史页 UI 直接订阅
 * 2. 提供家庭成员列表，用于历史记录中的成员筛选功能
 * 3. 提供删除单条测量记录的操作入口（本地与 Health Connect 同步删除）
 */
class HistoryViewModel(
    private val repository: ScaleRepository,
    private val preferenceManager: PreferenceManager? = null,
    private val healthConnectManager: HealthConnectManager? = null
) : ViewModel() {

    /**
     * 所有测量历史记录列表（Flow，按时间倒序，数据库变更时自动推送）。
     * 使用 [SharingStarted.Eagerly] 确保页面切换回来时数据已就绪，无白屏等待。
     */
    val history: StateFlow<List<BodyMeasurement>> = repository.getHistory()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 所有家庭成员列表（Flow），用于历史记录页的成员过滤筛选控件。
     * 使用 [SharingStarted.Eagerly] 保证切换到历史页时筛选器立即可用。
     */
    val members: StateFlow<List<com.example.dianzicheng.domain.FamilyMember>> = repository.getMembers()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 改绑操作的结果提示（Toast 文案），UI 消费后调用 [clearRebindMessage] 清空，
     * 避免旋转屏幕或返回页面时重复弹出。
     */
    private val _rebindMessage = MutableStateFlow<String?>(null)
    val rebindMessage: StateFlow<String?> = _rebindMessage.asStateFlow()

    /** 正在改绑的记录 ID，用于在列表中显示进度并防止重复点击 */
    private val _rebindingMeasurementId = MutableStateFlow<String?>(null)
    val rebindingMeasurementId: StateFlow<String?> = _rebindingMeasurementId.asStateFlow()

    /**
     * 把一条历史记录改绑到另一位成员，并按目标成员的档案重算全部体成分指标。
     *
     * 为什么必须重算：体脂率、骨骼肌、基础代谢、身体年龄等指标全部依赖成员的
     * 性别 / 身高 / 年龄，改绑后若沿用原成员的参数，数值就会张冠李戴。
     * 重算逻辑复用 [ScaleRepository.bindMeasurementToMember]，与称重时的手动
     * 重选成员走同一条代码路径，保证口径一致。
     *
     * @param measurement 要改绑的测量记录
     * @param member 目标成员
     */
    fun changeMeasurementMember(measurement: BodyMeasurement, member: FamilyMember) {
        if (measurement.memberId == member.id) return
        if (_rebindingMeasurementId.value != null) return
        viewModelScope.launch {
            _rebindingMeasurementId.value = measurement.id
            val ok = try {
                repository.bindMeasurementToMember(measurement, member)
                true
            } catch (e: Exception) {
                AppLogger.e("HistoryViewModel", "改绑成员失败: ${e.message}")
                false
            }
            _rebindingMeasurementId.value = null
            _rebindMessage.value = if (ok) {
                "已改绑到「${member.name}」，体成分已按其档案重新计算"
            } else {
                "改绑失败，请稍后重试"
            }
        }
    }

    /** 清除改绑提示（UI 弹出 Toast 后调用） */
    fun clearRebindMessage() { _rebindMessage.value = null }

    /**
     * 删除单条测量记录。
     * 先删除本地数据库记录（[history] Flow 自动推送更新，UI 无需手动刷新），
     * 若已开启 Health Connect 同步，再删除远端对应记录，保持两端数据一致。
     *
     * @param measurement 要删除的测量记录
     */
    fun deleteMeasurement(measurement: BodyMeasurement) {
        viewModelScope.launch {
            repository.deleteMeasurement(measurement)

            val prefs = preferenceManager ?: return@launch
            val hc = healthConnectManager ?: return@launch
            try {
                val enabled = prefs.healthConnectEnabled.first()
                if (enabled && hc.hasAllPermissions()) {
                    hc.deleteMeasurement(measurement)
                }
            } catch (e: Exception) {
                android.util.Log.e("HistoryViewModel", "Health Connect delete sync failed", e)
            }
        }
    }
}
