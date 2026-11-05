package com.example.dianzicheng.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.health.connect.client.PermissionController
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.domain.AgeUtils
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.ScaleModel
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.ui.theme.电子秤Theme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileContent(
    members: List<FamilyMember>,
    onAddMember: (String, Sex, Double, Int) -> Unit,
    onUpdateMember: (FamilyMember, String, Sex, Double, Int) -> Unit = { _, _, _, _, _ -> },
    onDeleteMember: (FamilyMember) -> Unit,
    onResetPairing: () -> Unit,
    onNavigateToPairing: () -> Unit = onResetPairing,
    pairedMac: String? = null,
    pairedDeviceName: String? = null,
    selectedScaleModel: ScaleModel = ScaleModel.AUTO,
    onSelectScaleModel: (ScaleModel) -> Unit = {},
    healthConnectEnabled: Boolean,
    onToggleHealthConnect: (Boolean) -> Unit,
    onRequestHealthConnectPermissions: () -> Unit,
    onBatchSyncToHealthConnect: () -> Unit,
    onOpenMiHealth: () -> Unit,
    onOpenHealthConnectSettings: () -> Unit,
    syncProgress: String?,
    isMiHealthInstalled: Boolean,
    webdavUrl: String,
    webdavUsername: String,
    webdavPassword: String,
    lastBackupTime: Long,
    onSaveWebdavConfig: (String, String, String) -> Unit,
    onTestWebdavConnection: (String, String, String) -> Unit,
    onBackupData: () -> Unit,
    onRestoreData: () -> Unit,
    isOperating: Boolean,
    onRecalculateHistory: () -> Unit = {},
    recalcInProgress: Boolean = false,
    logEntries: List<AppLogger.LogEntry> = emptyList(),
    onClearLogs: () -> Unit = {},
    onShareLogs: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var memberToEdit by remember { mutableStateOf<FamilyMember?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showWebdavDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var showUnpairConfirmDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showRecalcConfirmDialog by remember { mutableStateOf(false) }
    var memberToDelete by remember { mutableStateOf<FamilyMember?>(null) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // 直接读安装包 versionName：用户可通过标题栏版本号确认装的是不是最新 APK
    val versionContext = LocalContext.current
    val appVersion = remember(versionContext) {
        runCatching {
            versionContext.packageManager
                .getPackageInfo(versionContext.packageName, 0).versionName
        }.getOrNull() ?: "1.4.2"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "我的",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "v$appVersion",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加成员")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Member Section
            item {
                Text(
                    text = "成员管理",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (members.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(
                            "暂无成员，点击右上角图标添加",
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(members) { member ->
                    MemberCard(
                        member = member,
                        onEdit = {
                            memberToEdit = member
                            showEditDialog = true
                        },
                        onDelete = { memberToDelete = member }
                    )
                }
            }

            // Data Maintenance Section：算法口径变更后，把历史记录刷成新公式
            // 位置刻意放在成员管理之后，避免夹在 Health Connect / WebDAV 之间被划过去
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "数据维护",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    SettingsItem(
                        title = "重新计算历史体成分",
                        subtitle = "按当前公式刷新历史记录（体重、阻抗、称重时间保持不变）",
                        icon = Icons.Default.Refresh,
                        onClick = { showRecalcConfirmDialog = true },
                        isLoading = recalcInProgress
                    )
                }
            }

            // System Health Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "系统健康同步 (Health Connect)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Favorite,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "同步至系统健康", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                text = "将体重与体脂数据同步至系统 Health Connect",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = healthConnectEnabled,
                            onCheckedChange = onToggleHealthConnect
                        )
                    }

                    if (healthConnectEnabled) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            title = "申请健康中心读写权限",
                            icon = Icons.Default.Security,
                            onClick = onRequestHealthConnectPermissions
                        )
                    }
                }
            }

            // Xiaomi Health Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "导入小米健康 (Mi Health)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    // 说明栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(androidx.compose.ui.graphics.Color(0xFFFF6900).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = androidx.compose.ui.graphics.Color(0xFFFF6900)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "同步历史数据到小米健康",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "本应用将体重与体脂写入系统 Health Connect，小米健康等支持 Health Connect 的应用可读取。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (syncProgress != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = syncProgress,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "立即同步全部历史数据",
                        subtitle = "体重与体脂批量写入 Health Connect",
                        icon = Icons.Default.CloudSync,
                        onClick = onBatchSyncToHealthConnect,
                        isLoading = syncProgress != null
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = if (isMiHealthInstalled) "打开小米健康" else "安装小米健康",
                        subtitle = "在小米健康 > 设置 > 数据来源 中启用 Health Connect",
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        onClick = onOpenMiHealth
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "管理 Health Connect 权限",
                        icon = Icons.Default.ManageAccounts,
                        onClick = onOpenHealthConnectSettings
                    )
                }
            }

            // WebDAV Backup Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "WebDAV 云端备份",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    SettingsItem(
                        title = if (webdavUrl.isBlank()) "配置 WebDAV 服务器" else "WebDAV 已配置 (${if (webdavUrl.length > 24) webdavUrl.take(24) + "..." else webdavUrl})",
                        icon = Icons.Default.Cloud,
                        onClick = { showWebdavDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "立即备份到 WebDAV",
                        subtitle = if (lastBackupTime > 0) "上次备份：${dateFormat.format(Date(lastBackupTime))}" else "从未备份",
                        icon = Icons.Default.CloudUpload,
                        onClick = onBackupData,
                        isLoading = isOperating
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "从 WebDAV 恢复数据",
                        icon = Icons.Default.CloudDownload,
                        onClick = onRestoreData,
                        isLoading = isOperating
                    )
                }
            }

            // Settings Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "应用设置",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    SettingsItem(
                        title = if (pairedMac.isNullOrEmpty()) "绑定体脂秤" else "已记住体脂秤",
                        subtitle = if (pairedMac.isNullOrEmpty()) "暂未绑定，点击手动搜索并连接" else "${pairedDeviceName ?: "体脂秤"} ($pairedMac) · 点击解除绑定",
                        icon = Icons.Default.Bluetooth,
                        onClick = {
                            if (pairedMac.isNullOrEmpty()) {
                                onNavigateToPairing()
                            } else {
                                showUnpairConfirmDialog = true
                            }
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "体脂秤型号与品牌",
                        subtitle = "${selectedScaleModel.displayName} · 点击切换型号偏好",
                        icon = Icons.Default.Tune,
                        onClick = { showModelDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "查看运行日志",
                        subtitle = if (logEntries.isEmpty()) "暂无日志" else "共 ${logEntries.size} 条记录",
                        icon = Icons.AutoMirrored.Filled.Article,
                        onClick = { showLogDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "关于软件",
                        subtitle = "版本 $appVersion",
                        icon = Icons.Default.Info,
                        onClick = { }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    if (showRecalcConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRecalcConfirmDialog = false },
            title = { Text("重新计算历史体成分？", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "将用当前版体成分公式，结合每位成员的性别、身高、年龄，重新计算全部历史记录中的" +
                        "体脂率、水分率、蛋白质率、骨量、肌肉量、BMI、基础代谢、骨骼肌量、皮下脂肪率、" +
                        "内脏脂肪等级和身体年龄。\n\n" +
                        "体重、阻抗、称重时间不会被修改；未绑定成员或没有阻抗数据的记录会原样保留。\n\n" +
                        "升级到 1.5.0 后，历史记录的新增指标最初都是 0，执行一次本操作即可全部回填。\n\n" +
                        "建议先执行一次 WebDAV 备份，以便需要时恢复。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRecalcConfirmDialog = false
                        onRecalculateHistory()
                    }
                ) {
                    Text("开始重算", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecalcConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showLogDialog) {
        LogViewerDialog(
            entries = logEntries,
            onDismiss = { showLogDialog = false },
            onClear = onClearLogs,
            onShare = onShareLogs
        )
    }

    if (showAddDialog) {
        MemberEditDialog(
            member = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, sex, height, age ->
                onAddMember(name, sex, height, age)
                showAddDialog = false
            }
        )
    }

    if (showEditDialog && memberToEdit != null) {
        MemberEditDialog(
            member = memberToEdit,
            onDismiss = {
                showEditDialog = false
                memberToEdit = null
            },
            onConfirm = { name, sex, height, age ->
                val target = memberToEdit
                if (target != null) onUpdateMember(target, name, sex, height, age)
                showEditDialog = false
                memberToEdit = null
            }
        )
    }

    if (memberToDelete != null) {
        val target = memberToDelete!!
        AlertDialog(
            onDismissRequest = { memberToDelete = null },
            title = { Text("确认删除成员", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要删除成员「${target.name}」吗？\n该成员的全部历史测量记录会随之一并删除，且无法撤销。如果只是资料填错，请改用「编辑」而不是删除。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteMember(target)
                        memberToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showUnpairConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirmDialog = false },
            title = { Text("解除设备绑定？", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要解除与体脂秤「${pairedDeviceName ?: pairedMac}」的绑定吗？\n\n解除后 App 将暂停自动连接，直到您重新手动搜索并绑定设备。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnpairConfirmDialog = false
                        onResetPairing()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("解除绑定", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showWebdavDialog) {
        WebDavConfigDialog(
            initialUrl = webdavUrl,
            initialUsername = webdavUsername,
            initialPassword = webdavPassword,
            onDismiss = { showWebdavDialog = false },
            onSave = { url, user, pass ->
                onSaveWebdavConfig(url, user, pass)
                showWebdavDialog = false
            },
            onTest = { url, user, pass ->
                onTestWebdavConnection(url, user, pass)
            },
            isOperating = isOperating
        )
    }

    if (showModelDialog) {
        SelectScaleModelDialog(
            currentModel = selectedScaleModel,
            onModelSelected = onSelectScaleModel,
            onDismiss = { showModelDialog = false }
        )
    }
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    onClick: () -> Unit,
    isLoading: Boolean = false
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = androidx.compose.ui.graphics.Color.Transparent,
        enabled = !isLoading
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerDialog(
    entries: List<AppLogger.LogEntry>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    // 每次有新日志时自动滚动到最底部
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("运行日志", fontWeight = FontWeight.Bold)
                            Text(
                                "${entries.size} 条记录",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val text = AppLogger.exportText()
                            clipboardManager.setText(AnnotatedString(text))
                            Toast.makeText(context, "已复制全部日志", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制")
                        }
                        IconButton(onClick = {
                            val text = AppLogger.exportText()
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                                putExtra(Intent.EXTRA_SUBJECT, "AppLog")
                            }
                            context.startActivity(Intent.createChooser(intent, "分享日志"))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "分享")
                        }
                        IconButton(onClick = onClear) {
                            Icon(Icons.Default.Delete, contentDescription = "清空")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { innerPadding ->
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.Article,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "暂无日志",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(MaterialTheme.colorScheme.surface),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(entries, key = { "${it.timestamp}_${it.message.hashCode()}" }) { entry ->
                        LogEntryRow(entry = entry, timeFmt = timeFmt)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(
    entry: AppLogger.LogEntry,
    timeFmt: SimpleDateFormat
) {
    val (levelColor, levelBg) = when (entry.level) {
        AppLogger.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant to Color.Transparent
        AppLogger.Level.INFO  -> Color(0xFF2196F3) to Color(0xFF2196F3).copy(alpha = 0.07f)
        AppLogger.Level.WARN  -> Color(0xFFFF9800) to Color(0xFFFF9800).copy(alpha = 0.07f)
        AppLogger.Level.ERROR -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(levelBg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeFmt.format(Date(entry.timestamp)),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 1.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = entry.level.name.take(1),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = levelColor,
            modifier = Modifier.padding(top = 1.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "[${entry.tag}] ${entry.message}",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = if (entry.level == AppLogger.Level.DEBUG)
                MaterialTheme.colorScheme.onSurfaceVariant
            else
                MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun WebDavConfigDialog(
    initialUrl: String,
    initialUsername: String,
    initialPassword: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onTest: (String, String, String) -> Unit,
    isOperating: Boolean
) {
    var url by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置 WebDAV 云同步") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "支持坚果云、Nextcloud、OwnCloud、NAS 等任意标准 WebDAV 服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("服务器 URL (如 https://dav.jianguoyun.com/dav/)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("账号 / 用户名") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码 / 应用授权码") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(url, username, password) },
                shape = MaterialTheme.shapes.medium
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onTest(url, username, password) },
                    enabled = !isOperating,
                    shape = MaterialTheme.shapes.medium
                ) {
                    if (isOperating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("测试连接")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

@Composable
fun MemberCard(member: FamilyMember, onEdit: () -> Unit = {}, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = member.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = buildString {
                        append(if (member.sex == Sex.MALE) "男" else "女")
                        append(" · ${AgeUtils.displayAge(member.birthDateEpochMs)}岁")
                        append(" · ${member.heightCm.toInt()}cm")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onEdit,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Edit, contentDescription = "编辑成员", modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onDelete,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * 成员资料编辑弹窗，新增与修改共用。
 *
 * @param member 传入已有成员表示「编辑」，传 null 表示「新增」
 * @param onDismiss 关闭弹窗
 * @param onConfirm 确认回调：(姓名, 性别, 身高cm, 年龄岁)
 *
 * 年龄是体成分计算的关键入参（体脂率、基础代谢、内脏脂肪、身体年龄都依赖它），
 * 此前界面上无处可改，所有成员都被硬编码成 25 岁，属于真实缺陷。此处按周岁收集，
 * 落库时由 [AgeUtils.birthDateFromAge] 换算成出生日期，保证界面与算法口径一致。
 */
@Composable
fun MemberEditDialog(
    member: FamilyMember? = null,
    onDismiss: () -> Unit,
    onConfirm: (String, Sex, Double, Int) -> Unit
) {
    val isEdit = member != null
    var name by remember(member) { mutableStateOf(member?.name ?: "") }
    var sex by remember(member) { mutableStateOf(member?.sex ?: Sex.MALE) }
    var height by remember(member) {
        mutableStateOf(member?.heightCm?.toInt()?.toString() ?: "170")
    }
    var age by remember(member) {
        mutableStateOf(member?.let { AgeUtils.displayAge(it.birthDateEpochMs).toString() } ?: "30")
    }

    val heightValue = height.toDoubleOrNull()
    val ageValue = age.toIntOrNull()
    val heightValid = heightValue != null && heightValue in 50.0..250.0
    val ageValid = ageValue != null && ageValue in 1..120

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑成员资料" else "添加新成员") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("姓名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )

                Text("性别", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FilterChip(
                        selected = sex == Sex.MALE,
                        onClick = { sex = Sex.MALE },
                        label = { Text("男生") },
                        leadingIcon = if (sex == Sex.MALE) { { Icon(Icons.Default.Check, null) } } else null
                    )
                    FilterChip(
                        selected = sex == Sex.FEMALE,
                        onClick = { sex = Sex.FEMALE },
                        label = { Text("女生") },
                        leadingIcon = if (sex == Sex.FEMALE) { { Icon(Icons.Default.Check, null) } } else null
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = height,
                        onValueChange = { input -> height = input.filter { it.isDigit() || it == '.' } },
                        label = { Text("身高 (cm)") },
                        singleLine = true,
                        isError = height.isNotBlank() && !heightValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    )
                    OutlinedTextField(
                        value = age,
                        onValueChange = { input -> age = input.filter { it.isDigit() }.take(3) },
                        label = { Text("年龄 (岁)") },
                        singleLine = true,
                        isError = age.isNotBlank() && !ageValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    )
                }

                Text(
                    text = if (isEdit) {
                        "性别、身高、年龄都会参与体成分计算。保存后会自动按新资料重算该成员的历史记录，体重与阻抗不变。"
                    } else {
                        "年龄按周岁填写，会影响体脂率、基础代谢、身体年龄的计算结果。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = if (name.isBlank()) "新成员" else name.trim()
                    onConfirm(
                        finalName,
                        sex,
                        heightValue ?: 170.0,
                        ageValue ?: AgeUtils.DEFAULT_AGE
                    )
                    onDismiss()
                },
                enabled = heightValid && ageValid,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(if (isEdit) "保存" else "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    modifier: Modifier = Modifier,
    onNavigateToPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val members by viewModel.members.collectAsState()
    val healthConnectEnabled by viewModel.healthConnectEnabled.collectAsState()
    val webdavUrl by viewModel.webdavUrl.collectAsState()
    val webdavUsername by viewModel.webdavUsername.collectAsState()
    val webdavPassword by viewModel.webdavPassword.collectAsState()
    val lastBackupTime by viewModel.lastBackupTime.collectAsState()
    val isOperating by viewModel.isOperating.collectAsState()
    val recalcInProgress by viewModel.recalcInProgress.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    val logEntries by viewModel.logEntries.collectAsState()
    val pairedMac by viewModel.pairedMac.collectAsState()
    val pairedDeviceName by viewModel.pairedDeviceName.collectAsState()
    val selectedScaleModel by viewModel.selectedScaleModel.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        // Health Connect 权限页把「允许读取」「允许写入」分成两栏，用户很容易只勾一边。
        // 分别判断并给出可执行的提示，避免出现「授权了但同步没反应」。
        val writeWeight = androidx.health.connect.client.permission.HealthPermission.getWritePermission(
            androidx.health.connect.client.records.WeightRecord::class
        )
        val writeFat = androidx.health.connect.client.permission.HealthPermission.getWritePermission(
            androidx.health.connect.client.records.BodyFatRecord::class
        )
        val writeGranted = granted.contains(writeWeight) && granted.contains(writeFat)
        val readGranted = granted.size > 2

        val msg = when {
            writeGranted && readGranted -> "已获得体重/体脂读写权限"
            writeGranted -> "已获得写入权限（未开读取，自检无法回读条数）"
            granted.isEmpty() -> "未获得任何健康权限"
            else -> "只给了读取权限，无法写入——请重试并勾选「允许写入」中的体重、体脂"
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.clearStatusMessage()
        }
    }

    ProfileContent(
        members = members,
        onAddMember = { name, sex, height, age -> viewModel.addMember(name, sex, height, age) },
        onUpdateMember = { member, name, sex, height, age ->
            viewModel.updateMember(member, name, sex, height, age)
        },
        onDeleteMember = { viewModel.deleteMember(it) },
        onResetPairing = { viewModel.resetPairing() },
        onNavigateToPairing = onNavigateToPairing,
        pairedMac = pairedMac,
        pairedDeviceName = pairedDeviceName,
        selectedScaleModel = selectedScaleModel,
        onSelectScaleModel = { viewModel.selectScaleModel(it) },
        healthConnectEnabled = healthConnectEnabled,
        onToggleHealthConnect = { enabled ->
            viewModel.setHealthConnectEnabled(enabled)
            if (enabled && viewModel.isHealthConnectAvailable()) {
                permissionLauncher.launch(
                    setOf(
                        androidx.health.connect.client.permission.HealthPermission.getWritePermission(androidx.health.connect.client.records.WeightRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getWritePermission(androidx.health.connect.client.records.BodyFatRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.WeightRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.BodyFatRecord::class)
                    )
                )
            }
        },
        onRequestHealthConnectPermissions = {
            if (viewModel.isHealthConnectAvailable()) {
                permissionLauncher.launch(
                    setOf(
                        androidx.health.connect.client.permission.HealthPermission.getWritePermission(androidx.health.connect.client.records.WeightRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getWritePermission(androidx.health.connect.client.records.BodyFatRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.WeightRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.BodyFatRecord::class)
                    )
                )
            } else {
                Toast.makeText(context, "当前设备不支持或未安装 Health Connect", Toast.LENGTH_SHORT).show()
            }
        },
        onBatchSyncToHealthConnect = { viewModel.batchSyncToHealthConnect() },
        onOpenMiHealth = { viewModel.openMiHealth() },
        onOpenHealthConnectSettings = { viewModel.openHealthConnectSettings() },
        syncProgress = syncProgress,
        isMiHealthInstalled = viewModel.isMiHealthInstalled(),
        webdavUrl = webdavUrl,
        webdavUsername = webdavUsername,
        webdavPassword = webdavPassword,
        lastBackupTime = lastBackupTime,
        onSaveWebdavConfig = { url, user, pass -> viewModel.saveWebdavConfig(url, user, pass) },
        onTestWebdavConnection = { url, user, pass -> viewModel.testWebdavConnection(url, user, pass) },
        onBackupData = { viewModel.backupData() },
        onRestoreData = { viewModel.restoreData() },
        onRecalculateHistory = { viewModel.recalculateHistory() },
        recalcInProgress = recalcInProgress,
        isOperating = isOperating,
        logEntries = logEntries,
        onClearLogs = { viewModel.clearLogs() },
        onShareLogs = {
            val text = viewModel.exportLogsText()
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "AppLog")
            }
            context.startActivity(android.content.Intent.createChooser(intent, "分享日志"))
        },
        modifier = modifier
    )
}

