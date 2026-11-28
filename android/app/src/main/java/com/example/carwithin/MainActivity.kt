package com.example.carwithin

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.remember
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.carwithin.ui.theme.CarWithinTheme
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var projection by mutableStateOf<ProjectionService?>(null)
    private var bound = false
    private var resumed by mutableStateOf(false)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            projection = (binder as ProjectionService.LocalBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName) {
            projection = null
        }
    }
    private var pendingEasyConnAfterPerm = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasWifiPermission()) {
            if (pendingEasyConnAfterPerm) {
                pendingEasyConnAfterPerm = false
                projection?.connectSavedEasyConn()
            } else {
                projection?.scan()
            }
        } else {
            pendingEasyConnAfterPerm = false
            projection?.record("请授予附近 Wi-Fi 设备或精确位置权限，然后重新连接")
        }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startQrScanner()
        else android.widget.Toast.makeText(this, "请授予相机权限以扫描仪表二维码", android.widget.Toast.LENGTH_SHORT).show()
    }
    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        handleQrText(text, autoConnect = true)
    }

    private fun launchQrScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startQrScanner()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("请对准摩托车仪表上的亿连投屏二维码")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
        qrScanner.launch(options)
    }

    private fun handleQrText(raw: String, autoConnect: Boolean) {
        val svc = projection ?: return
        svc.applyQrText(raw)
            .onSuccess { parsed ->
                val modeTip = if (parsed.recommendedMode() == EasyConnQrParser.LinkMode.WIFI_DIRECT) "WLAN 直连" else "车机热点"
                android.widget.Toast.makeText(
                    this,
                    "已填入 ${parsed.ssid()}（$modeTip）",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                if (autoConnect) {
                    connectEasyConnWithPermission(svc, parsed.recommendedMode())
                }
            }
            .onFailure {
                android.widget.Toast.makeText(this, "解析失败：${it.message}", android.widget.Toast.LENGTH_LONG).show()
                svc.record("二维码解析失败：${it.message}")
            }
    }

    private fun connectEasyConnWithPermission(svc: ProjectionService, mode: EasyConnQrParser.LinkMode) {
        if (mode == EasyConnQrParser.LinkMode.WIFI_AP || hasWifiPermission()) {
            svc.connectSavedEasyConn()
        } else {
            pendingEasyConnAfterPerm = true
            permissions.launch(
                if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bound = bindService(Intent(this, ProjectionService::class.java), connection, Context.BIND_AUTO_CREATE)
        setContent { CarWithinTheme { Interface() } }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    private enum class Page { HOME, SETTINGS, OUTPUT, ABOUT, HISTORY, DIAGNOSTICS, FULLSCREEN }

    @Composable
    private fun Interface() {
        var page by rememberSaveable { mutableStateOf(Page.HOME) }
        var outputParent by rememberSaveable { mutableStateOf(Page.HOME) }
        fun showOutput(parent: Page) {
            outputParent = parent
            page = Page.OUTPUT
        }
        fun depth(destination: Page) = when (destination) {
            Page.HOME -> 0
            Page.OUTPUT -> if (outputParent == Page.SETTINGS) 2 else 1
            Page.SETTINGS, Page.FULLSCREEN -> 1
            Page.ABOUT, Page.DIAGNOSTICS -> 2
            Page.HISTORY -> 3
        }
        var automatic by remember {
            mutableStateOf(getSharedPreferences("android_updates", MODE_PRIVATE).getBoolean("enabled", true))
        }
        var checking by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<UpdateResult?>(null) }
        val service = projection
        val state = service?.state?.collectAsState()?.value ?: ProjectionState()
        val idle = service != null && !state.carLifeRunning && !state.outputRunning && !state.connecting
        fun check(manual: Boolean) {
            if (checking) return
            checking = true
            lifecycleScope.launch {
                try {
                    val update = AndroidUpdates.inspect()
                    AndroidUpdates.markChecked(this@MainActivity, true)
                    val current = projection?.state?.value
                    val stillIdle = current != null && !current.carLifeRunning && !current.outputRunning && !current.connecting
                    if (manual || (resumed && stillIdle && automatic && update != null &&
                            update.build > BuildConfig.VERSION_CODE && update.minSdk <= Build.VERSION.SDK_INT)) {
                        val title = when {
                            update == null -> "尚无 Android 发布"
                            update.build <= BuildConfig.VERSION_CODE -> "已是最新版本"
                            update.minSdk > Build.VERSION.SDK_INT -> "发现更新 · 系统版本不兼容"
                            else -> "发现 Android 更新"
                        }
                        result = UpdateResult(title, update?.notes.orEmpty().ifBlank {
                            if (update == null) "公开发布后，可在这里查看版本与下载入口。" else update.release.name
                        }, update)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    AndroidUpdates.markChecked(this@MainActivity, false)
                    if (manual) result = UpdateResult("检查更新失败", failure.message ?: "请稍后重试")
                } finally {
                    checking = false
                }
            }
        }
        LaunchedEffect(resumed, idle, automatic, checking, page) {
            if (resumed && idle && automatic && !checking && page == Page.HOME && AndroidUpdates.isDue(this@MainActivity)) {
                delay(2_000)
                check(false)
            }
        }
        fun back() {
            page = when (page) {
                Page.ABOUT, Page.DIAGNOSTICS -> Page.SETTINGS
                Page.HISTORY -> Page.ABOUT
                Page.OUTPUT -> outputParent
                else -> Page.HOME
            }
        }
        BackHandler(page != Page.HOME) { back() }
        if (page == Page.FULLSCREEN && service != null) {
            Box(Modifier.fillMaxSize().background(UiColor(0xFF030819))) {
                AndroidView(
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                        .aspectRatio(state.width.toFloat() / state.height),
                    factory = { PreviewView(it, service) },
                )
                TextButton(onClick = { back() }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()) {
                    Text("‹ 完成", color = UiColor.White)
                }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding().heightIn(min = 58.dp).padding(horizontal = 16.dp)) {
                        val title = when (page) {
                            Page.HOME -> "CarWithin"
                            Page.SETTINGS -> "设置"
                            Page.OUTPUT -> "仪表投屏"
                            Page.ABOUT -> "关于"
                            Page.HISTORY -> "版本记录"
                            Page.DIAGNOSTICS -> "诊断与实验"
                            Page.FULLSCREEN -> "CarLife"
                        }
                        Text(
                            title,
                            modifier = Modifier.align(if (page == Page.HOME) Alignment.CenterStart else Alignment.Center),
                            style = if (page == Page.HOME) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (page != Page.HOME && page != Page.ABOUT) {
                            IconButton(onClick = { back() }, modifier = Modifier.align(Alignment.CenterStart)) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (page == Page.ABOUT) {
                            TextButton(onClick = { back() }, modifier = Modifier.align(Alignment.CenterEnd)) { Text("完成") }
                        } else if (page == Page.HOME) {
                            IconButton(onClick = { page = Page.SETTINGS }, modifier = Modifier.align(Alignment.CenterEnd)) {
                                Icon(Icons.Outlined.Settings, "设置", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                },
            ) { padding ->
                AnimatedContent(
                    targetState = page,
                    modifier = Modifier.fillMaxSize().padding(padding).clipToBounds(),
                    transitionSpec = {
                        val direction = if (depth(targetState) >= depth(initialState)) 1 else -1
                        (slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { direction * it / 5 } +
                            fadeIn(tween(200))) togetherWith
                            (slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -direction * it / 7 } +
                                fadeOut(tween(140)))
                    },
                    label = "CarWithin page transition",
                ) { visiblePage ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        when (visiblePage) {
                            Page.HOME -> {
                                if (service == null) Text("正在启动投屏服务…")
                                else Home(service, state, { showOutput(Page.HOME) }, { page = Page.FULLSCREEN }, visiblePage == page)
                            }
                            Page.OUTPUT -> if (service != null) Output(service, state)
                            Page.SETTINGS -> {
                                Group("连接与画面") {
                                    Setting(Icons.Outlined.Share, Blue, "当前输出", state.model.title) { showOutput(Page.SETTINGS) }
                                    Rule()
                                    var showScale by remember { mutableStateOf(false) }
                                    Box {
                                        Setting(ImageVector.vectorResource(R.drawable.ic_output_screen), Indigo, "画面填充模式",
                                            ProjectionService.scaleTitle(state.scale)) { showScale = true }
                                        DropdownMenu(showScale, { showScale = false }) {
                                            StreamProfile.Scale.values().forEach { mode ->
                                                DropdownMenuItem(text = { Text(ProjectionService.scaleTitle(mode)) }, onClick = {
                                                    service?.selectScale(mode)
                                                    showScale = false
                                                })
                                            }
                                        }
                                    }
                                    if (state.model.isEasyConn) {
                                        Rule()
                                        var showCanvas by remember { mutableStateOf(false) }
                                        Box {
                                            Setting(Icons.Outlined.Settings, Blue, "CarLife 画布尺寸", state.canvasPreset.title,
                                                enabled = !state.carLifeRunning) { showCanvas = true }
                                            DropdownMenu(showCanvas, { showCanvas = false }) {
                                                CarLifeCanvasPreset.entries.forEach { preset ->
                                                    DropdownMenuItem(text = { Text(preset.title) }, onClick = {
                                                        service?.selectCanvasPreset(preset)
                                                        showCanvas = false
                                                    })
                                                }
                                            }
                                        }
                                    }
                                    Rule()
                                    var expanded by remember { mutableStateOf(false) }
                                    Box {
                                        Setting(ImageVector.vectorResource(R.drawable.ic_frame_speed), Indigo, "画面帧率", "${state.fps} FPS",
                                            enabled = idle) { expanded = true }
                                        DropdownMenu(expanded, { expanded = false }) {
                                            listOf(20, 25, 30).forEach { fps ->
                                                DropdownMenuItem(text = { Text("$fps FPS") }, onClick = {
                                                    service?.selectFps(fps)
                                                    expanded = false
                                                })
                                            }
                                        }
                                    }
                                    Text("画面填充模式可在投屏过程中实时切换；CarLife 画布尺寸在重新启动 CarLife 后生效。",
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Group("更新") {
                                    Setting(Icons.Outlined.Refresh, Blue, "自动检查更新", trailing = {
                                        Switch(automatic, onCheckedChange = {
                                            automatic = it
                                            getSharedPreferences("android_updates", MODE_PRIVATE).edit()
                                                .putBoolean("enabled", it).apply()
                                        })
                                    })
                                }
                                Group("支持") {
                                    Setting(Icons.Outlined.FavoriteBorder, Red, "关注作者", "抖音", external = true) { open("https://v.douyin.com/x64M2NbYj0A/") }
                                    Rule()
                                    Setting(ImageVector.vectorResource(R.drawable.ic_support_coffee), Orange, "请我喝杯咖啡", "支付宝", external = true) {
                                        open("https://qr.alipay.com/fkx16608y1puemmltuocze0")
                                    }
                                    Rule()
                                    Setting(Icons.Outlined.Build, Indigo, "诊断与实验") { page = Page.DIAGNOSTICS }
                                    Rule()
                                    Setting(Icons.Outlined.Info, Blue, "关于", BuildConfig.VERSION_NAME) { page = Page.ABOUT }
                                }
                            }
                            Page.ABOUT -> {
                                Group {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        ) {
                                            Image(painterResource(R.drawable.carwithin_ios_icon), null,
                                                Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)))
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("CarWithin", style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.Bold)
                                                Text("Android ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
                                        Text("在安卓手机上运行本机 CarLife，并把画面投送到兼容仪表。",
                                            style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                Group("项目") {
                                    Setting(Icons.Outlined.Share, Indigo, "GitHub 项目", external = true) { open(AndroidUpdates.REPO) }
                                    Rule()
                                    Setting(Icons.Outlined.Info, Indigo, "第三方许可") {
                                        val text = listOf("CarNine-NOTICE.txt", "KCP-LICENSE.txt").joinToString("\n\n") { file ->
                                            assets.open("licenses/$file").bufferedReader().use { it.readText() }
                                        }
                                        result = UpdateResult("第三方许可", text, url = "https://github.com/xiaolu5hao/CarNine-Ninebot/blob/main/LICENSE")
                                    }
                                }
                                Group("更新") {
                                    Setting(Icons.Outlined.Refresh, Blue, "检查更新", if (checking) "正在检查" else null,
                                        enabled = !checking) { check(true) }
                                    Rule()
                                    Setting(Icons.Outlined.DateRange, Indigo, "版本记录") { page = Page.HISTORY }
                                }
                                Text("Copyright © 2026 xmz28", Modifier.padding(horizontal = 8.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Page.HISTORY -> History { release ->
                                result = UpdateResult(release.name, release.notes.ifBlank { release.tag }, url = release.url)
                            }
                            Page.DIAGNOSTICS -> {
                                Group("CarLife") {
                                    Setting(Icons.AutoMirrored.Outlined.ArrowForward, Blue, "仅连接 CarLife", enabled = !state.carLifeRunning && service != null) {
                                        service?.startCarLife()
                                    }
                                    Rule()
                                    Setting(Icons.Outlined.Share, Indigo, "复制全部连接日志", enabled = state.logs.isNotEmpty()) {
                                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("CarWithinLogs", state.logs.joinToString("\n")))
                                        android.widget.Toast.makeText(this@MainActivity, "已复制 ${state.logs.size} 条日志", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                Group("连接日志") {
                                    if (state.logs.isEmpty()) Text("暂无日志", Modifier.padding(16.dp))
                                    else state.logs.forEach { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                                        style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                            Page.FULLSCREEN -> Unit
                        }
                    }
                }
            }
        }
        result?.let { detail ->
            AlertDialog(
                onDismissRequest = { result = null },
                title = { Text(detail.title) },
                text = {
                    Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        detail.update?.let { Text("Android ${it.version}（${it.build}）", fontWeight = FontWeight.Bold) }
                        Text(detail.message)
                    }
                },
                confirmButton = {
                    val update = detail.update
                    when {
                        update != null && update.build > BuildConfig.VERSION_CODE && update.minSdk <= Build.VERSION.SDK_INT ->
                            TextButton(onClick = { result = null; open(update.download) }) { Text("下载 APK") }
                        update != null -> TextButton(onClick = { result = null; open(update.release.url) }) { Text("发布详情") }
                        detail.url != null -> TextButton(onClick = { result = null; open(detail.url) }) { Text("打开链接") }
                        else -> TextButton(onClick = { result = null }) { Text("完成") }
                    }
                },
                dismissButton = if (detail.update != null || detail.url != null) {
                    { TextButton(onClick = { result = null }) { Text("关闭") } }
                } else null,
            )
        }
    }

    private data class UpdateResult(
        val title: String,
        val message: String,
        val update: AndroidUpdates.Update? = null,
        val url: String? = null,
    )

    @Composable
    private fun History(show: (AndroidUpdates.Release) -> Unit) {
        var items by remember { mutableStateOf<List<AndroidUpdates.Release>?>(null) }
        var failed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            try {
                items = AndroidUpdates.history()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            }
        }
        Group {
            when {
                failed -> Text("加载失败，请稍后重试", Modifier.padding(16.dp))
                items == null -> Text("正在读取 Android 发布…", Modifier.padding(16.dp))
                items.orEmpty().isEmpty() -> Text("暂无 Android 发布记录", Modifier.padding(16.dp))
                else -> items.orEmpty().forEachIndexed { index, release ->
                    if (index > 0) Rule()
                    Setting(Icons.Outlined.DateRange, Blue, release.name) { show(release) }
                }
            }
            Rule()
            Setting(Icons.Outlined.Share, Indigo, "在 GitHub 查看", external = true) { open(AndroidUpdates.REPO + "/releases") }
        }
    }

    @Composable
    private fun Home(
        service: ProjectionService, state: ProjectionState, showOutput: () -> Unit, fullScreen: () -> Unit, showLiveSurface: Boolean,
    ) {
        Group {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("本机 CarLife", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("小米 CarWith / OPPO Car+", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.carLifeRunning) TextButton(onClick = fullScreen) { Text("全屏") }
                }
                Box(
                    Modifier.fillMaxWidth().aspectRatio(state.width.toFloat() / state.height)
                        .clip(RoundedCornerShape(14.dp)).background(UiColor(0xFF080F24)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.carLifeRunning && showLiveSurface) {
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { PreviewView(it, service) })
                    } else {
                        Image(painterResource(R.drawable.carwithin_ios_icon), null,
                            Modifier.size(62.dp).clip(RoundedCornerShape(14.dp)))
                    }
                }
                Text(state.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.carLifeRunning) {
                    Text("小米手机：先连蓝牙耳机/手表，点击下方启动后进入 CarWith → 百度 CarLife 无线连接 → 开始连接。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = {
                        if (state.carLifeRunning) service.stopCarLife()
                        else {
                            service.startCarLife(withInstrument = true, canReconnect = hasWifiPermission())
                            openCarLink(this@MainActivity, service)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(if (state.carLifeRunning) "停止 CarLife" else "启动本机 CarLife") }
            }
        }
        Group("输出") {
            Setting(Icons.Outlined.Share, Indigo, "当前输出", state.model.title) { showOutput() }
            if (state.model.isEasyConn) {
                Rule()
                val qrSummary = if (state.qrSsid.isBlank()) "点击扫码提取 Wi-Fi 与 ID"
                else buildString {
                    append(state.qrSsid)
                    if (state.qrModelId.isNotBlank()) append(" · ID ").append(state.qrModelId)
                }
                Setting(Icons.Outlined.Search, Blue, "扫码配对仪表", qrSummary) {
                    launchQrScan()
                    showOutput()
                }
            }
            if (state.carLifeRunning || state.connected || state.outputRunning) {
                Rule()
                val status = when {
                    state.outputRunning -> "仪表投屏中"
                    state.carLifeRunning -> state.output
                    else -> "仪表已连接"
                }
                Setting(ImageVector.vectorResource(R.drawable.ic_output_screen), Blue, status) { showOutput() }
            }
        }
    }

    @Composable
    private fun Output(service: ProjectionService, state: ProjectionState) {
        var showModels by remember { mutableStateOf(false) }
        var showPasteDialog by remember { mutableStateOf(false) }
        var pasteInput by remember { mutableStateOf("") }

        var ssidInput by remember { mutableStateOf(state.qrSsid) }
        var pwdInput by remember { mutableStateOf(state.qrPassword) }
        var macInput by remember { mutableStateOf(state.qrMac) }
        var modelIdInput by remember { mutableStateOf(state.qrModelId) }
        var linkModeInput by remember { mutableStateOf(state.qrLinkMode) }

        LaunchedEffect(state.qrSsid, state.qrPassword, state.qrMac, state.qrModelId, state.qrLinkMode) {
            ssidInput = state.qrSsid
            pwdInput = state.qrPassword
            macInput = state.qrMac
            modelIdInput = state.qrModelId
            linkModeInput = state.qrLinkMode
        }

        if (showPasteDialog) {
            AlertDialog(
                onDismissRequest = { showPasteDialog = false },
                title = { Text("粘贴仪表二维码内容") },
                text = {
                    OutlinedTextField(
                        value = pasteInput,
                        onValueChange = { pasteInput = it },
                        label = { Text("http://www.carbit.com.cn/...") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val text = pasteInput
                        showPasteDialog = false
                        handleQrText(text, autoConnect = false)
                    }) { Text("提取并填入") }
                },
                dismissButton = {
                    TextButton(onClick = { showPasteDialog = false }) { Text("取消") }
                },
            )
        }

        if (state.model.isEasyConn) {
            Group("扫码配对（自动提取密码与 ID）") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = ::launchQrScan,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("扫仪表二维码") }
                        OutlinedButton(
                            onClick = {
                                val cm = getSystemService(android.content.ClipboardManager::class.java)
                                val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty()
                                pasteInput = clip
                                showPasteDialog = true
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("粘贴二维码") }
                    }
                    OutlinedTextField(
                        value = ssidInput,
                        onValueChange = { ssidInput = it },
                        label = { Text("Wi-Fi / 直连名称 (SSID)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pwdInput,
                        onValueChange = { pwdInput = it },
                        label = { Text("Wi-Fi 密码 (pwd)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = modelIdInput,
                            onValueChange = { modelIdInput = it },
                            label = { Text("型号 ID (modelid)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = macInput,
                            onValueChange = { macInput = it },
                            label = { Text("仪表 MAC") },
                            singleLine = true,
                            modifier = Modifier.weight(1.3f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val isDirect = linkModeInput == EasyConnQrParser.LinkMode.WIFI_DIRECT
                        if (isDirect) {
                            Button(
                                onClick = { linkModeInput = EasyConnQrParser.LinkMode.WIFI_DIRECT },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            ) { Text("WLAN 直连") }
                            OutlinedButton(
                                onClick = { linkModeInput = EasyConnQrParser.LinkMode.WIFI_AP },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            ) { Text("车机热点") }
                        } else {
                            OutlinedButton(
                                onClick = { linkModeInput = EasyConnQrParser.LinkMode.WIFI_DIRECT },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            ) { Text("WLAN 直连") }
                            Button(
                                onClick = { linkModeInput = EasyConnQrParser.LinkMode.WIFI_AP },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            ) { Text("车机热点") }
                        }
                    }
                    Button(
                        onClick = {
                            service.saveQrConfig(ssidInput, pwdInput, macInput, modelIdInput, linkModeInput)
                            connectEasyConnWithPermission(service, linkModeInput)
                        },
                        enabled = !state.connected && !state.connecting && ssidInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(if (state.connected) "仪表已连接" else if (state.connecting) "正在连接…" else "保存并连接仪表") }
                    Text(
                        "扫码会自动提取名称、密码、MAC 与 ModelID。钱江赛600 等直连仪表选「WLAN 直连」；春风/贝纳利等热点仪表选「车机热点」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Group("输出设置") {
            Box {
                Setting(Icons.Outlined.Share, Indigo, "当前输出", state.model.title,
                    enabled = !state.connected && !state.connecting) { showModels = true }
                DropdownMenu(showModels, { showModels = false }) {
                    InstrumentModel.entries.forEach { model ->
                        DropdownMenuItem(text = { Text(model.title) }, onClick = {
                            service.selectModel(model)
                            showModels = false
                        })
                    }
                }
            }
            Rule()
            Setting(Icons.Outlined.CheckCircle, Blue, state.connection)
            if (!state.connected && !state.connecting) {
                Rule()
                Setting(Icons.Outlined.Search, Blue, "扫描 P2P 仪表", onClick = ::scan)
                if (state.model.isEasyConn) {
                    Rule()
                    Setting(Icons.Outlined.Refresh, Indigo, "接管当前已连 WLAN 直连") {
                        if (hasWifiPermission()) service.adoptActiveP2p()
                        else scan()
                    }
                    Rule()
                    Setting(Icons.Outlined.Refresh, Orange, "接管当前已连系统 Wi-Fi") {
                        service.adoptCurrentWifi()
                    }
                }
                state.peers.filter { state.model.matches(it.deviceName) }
                    .sortedWith(compareByDescending<android.net.wifi.p2p.WifiP2pDevice> {
                        val n = it.deviceName.orEmpty()
                        (state.qrSsid.isNotBlank() && n.contains(state.qrSsid, ignoreCase = true)) ||
                            n.startsWith("QJ", ignoreCase = true) || n.startsWith("DIRECT-")
                    }.thenBy { it.deviceName.orEmpty() }).forEach { device ->
                        Rule()
                        Setting(ImageVector.vectorResource(R.drawable.ic_output_screen), Indigo, device.deviceName.orEmpty(), device.deviceAddress.orEmpty()) {
                            service.connect(device)
                        }
                    }
            }
            if (state.connected || state.connecting) {
                Rule()
                Setting(Icons.Outlined.Close, Red, "断开仪表", onClick = service::disconnect)
            }
        }
        Group("投屏") {
            Text(state.output, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            Rule()
            var showScaleHere by remember { mutableStateOf(false) }
            Box {
                Setting(ImageVector.vectorResource(R.drawable.ic_output_screen), Indigo, "画面填充模式",
                    ProjectionService.scaleTitle(state.scale)) { showScaleHere = true }
                DropdownMenu(showScaleHere, { showScaleHere = false }) {
                    StreamProfile.Scale.values().forEach { mode ->
                        DropdownMenuItem(text = { Text(ProjectionService.scaleTitle(mode)) }, onClick = {
                            service.selectScale(mode)
                            showScaleHere = false
                        })
                    }
                }
            }
            Rule()
            if (state.outputRunning) {
                Setting(ImageVector.vectorResource(R.drawable.ic_stop_screen), Red, "停止仪表投屏") { service.pauseOutput() }
            } else {
                Setting(Icons.Outlined.PlayArrow, Blue, "投送 CarLife", enabled = state.connected && state.carLifeRunning) {
                    service.startOutput(false)
                }
                Rule()
                Setting(ImageVector.vectorResource(R.drawable.ic_test_grid), Orange, "动态测试图", enabled = state.connected) { service.startOutput(true) }
            }
        }
        Text("让仪表进入投屏模式，再扫描并选择对应设备。", Modifier.padding(horizontal = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            .onFailure { android.widget.Toast.makeText(this, "找不到可打开此链接的应用", android.widget.Toast.LENGTH_SHORT).show() }
    }


    private fun hasWifiPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun scan() {
        if (hasWifiPermission()) projection?.scan()
        else permissions.launch(
            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    private fun openCarLink(activity: Activity, service: ProjectionService) {
        // 1. Xiaomi CarWith open settings / connection entry
        val miAction = Intent("miui.intent.action.UCAR_CONNECT_SETTINGS")
        if (runCatching { activity.startActivity(miAction) }.isSuccess) {
            service.record("已打开小米 CarWith，请进入百度 CarLife 无线连接并点击开始连接")
            return
        }
        val miLauncher = packageManager.getLaunchIntentForPackage("com.miui.carlink")
        if (miLauncher != null && runCatching { activity.startActivity(miLauncher) }.isSuccess) {
            service.record("已打开小米 CarWith，请进入百度 CarLife 无线连接并点击开始连接")
            return
        }
        // 2. Fallback: OPPO Car+
        val carPlus = "com.oplus.ocar"
        val direct = Intent().setComponent(ComponentName(carPlus, "$carPlus.CarlifeActivity"))
        if (runCatching { activity.startActivity(direct) }.isSuccess) return
        val menu = packageManager.getLaunchIntentForPackage(carPlus)
        if (menu != null && runCatching { activity.startActivity(menu) }.isSuccess) {
            service.record("已打开 Car+，请进入 CarLife 连接界面")
        } else {
            service.record("未自动跳转车联页，请手动打开小米 CarWith → 百度 CarLife 无线连接")
        }
    }
}


private val Blue = UiColor(0xFF1575D4)
private val Indigo = UiColor(0xFF5759D4)
private val Orange = UiColor(0xFFAC5314)
private val Red = UiColor(0xFFBE3854)

@Composable
private fun Group(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun Rule() = HorizontalDivider(Modifier.padding(start = 66.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))

@Composable
private fun Setting(
    icon: ImageVector, accent: UiColor, label: String, detail: String? = null,
    enabled: Boolean = true, external: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
) {
    val action = if (onClick == null) Modifier else Modifier.clickable(enabled = enabled, onClick = onClick)
    Row(
        Modifier.fillMaxWidth().then(action).heightIn(min = 62.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tileColor = if (enabled) accent else accent.copy(alpha = .48f)
        Box(Modifier.size(36.dp).background(tileColor, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = UiColor.White, modifier = Modifier.size(22.dp))
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing != null) trailing()
        else if (onClick != null && enabled) Icon(
            if (external) ImageVector.vectorResource(R.drawable.ic_external_link)
            else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = if (external) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

private class PreviewView(context: Context, private val service: ProjectionService) :
    SurfaceView(context), SurfaceHolder.Callback {
    private var surface: android.view.Surface? = null
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f

    init {
        holder.addCallback(this)
        contentDescription = "CarLife 画面与触控区域"
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        service.attachPreview(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        cancelTouch()
        surface?.let(service::detachPreview)
        surface = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = send(event, MotionEvent.ACTION_DOWN)
                if (dragging) parent.requestDisallowInterceptTouchEvent(true)
                return dragging
            }
            MotionEvent.ACTION_MOVE -> if (dragging) send(event, MotionEvent.ACTION_MOVE)
            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                send(event, MotionEvent.ACTION_UP)
                dragging = false
                parent.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTouch()
                return true
            }
        }
        return dragging
    }

    override fun performClick(): Boolean = super.performClick()

    private fun send(event: MotionEvent, action: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        lastX = (event.x / width).coerceIn(0f, 1f)
        lastY = (event.y / height).coerceIn(0f, 1f)
        return service.touch(action, lastX, lastY)
    }

    private fun cancelTouch() {
        if (dragging) {
            service.touch(MotionEvent.ACTION_UP, lastX, lastY)
            parent?.requestDisallowInterceptTouchEvent(false)
            dragging = false
        }
    }
}
