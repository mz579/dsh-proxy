package com.dshproxy.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var store: ProviderStore
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var etBaseUrl: EditText
    private lateinit var etModel: EditText
    private lateinit var etApiKey: EditText
    private lateinit var spPreset: Spinner
    private lateinit var cbAutoStart: CheckBox

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private var appending = false
    private var userPicked = false

    /** 提交后台任务；Activity 已销毁/线程池已关闭时安全忽略。 */
    private fun submit(task: () -> Unit) {
        try {
            pool.execute(task)
        } catch (_: Exception) {
            // 线程池已 shutdown（Activity 已销毁）时忽略
        }
    }

    private val logSink: (String) -> Unit = { line ->
        ui.post {
            // Activity 销毁后仍可能收到回调，避免操作已失效的视图
            if (!isFinishing && !isDestroyed) {
                tvLog.append(if (appending) "\n$line" else line)
                appending = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = ProviderStore(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etModel = findViewById(R.id.etModel)
        etApiKey = findViewById(R.id.etApiKey)
        spPreset = findViewById(R.id.spPreset)
        cbAutoStart = findViewById(R.id.cbAutoStart)

        setupPresets()
        loadConfig()
        askNotificationPermission()

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveConfig(fromUi = true) }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startProxy() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopProxy() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { selfTest() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyLocalUrl() }
        findViewById<Button>(R.id.btnOpenDsh).setOnClickListener { openDsh() }
        findViewById<Button>(R.id.btnAutostartHelp).setOnClickListener { openAutostartSettings() }

        ProxyLog.observe(logSink)
        val history = ProxyLog.dump()
        tvLog.text = history
        appending = history.isNotEmpty()
        refreshStatus()

        // 打开 App 即自启：荣耀等 ROM 会拦截 BOOT_COMPLETED，
        // 靠这一层保证「只要点开 App，代理就自动就绪」。
        autoStartIfEnabled()
    }

    /** 若勾了开机自启且代理未运行，则自动拉起（「打开 App 即自启」兜底）。 */
    private fun autoStartIfEnabled() {
        if (!store.autoStart) return
        // userStopped 是进程内内存标记：用户本次手动点了「停止」才不再自动启动。
        // 重启手机后进程重建、标记自然清零，不会出现「上次停过就再也不自启」的残留问题。
        if (ProxyService.userStopped) {
            log("本次已手动停止代理，未自动启动（点「启动」可恢复）")
            return
        }
        if (ProxyService.running) return
        ProxyLog.add("检测到已开启自启，自动启动代理")
        ProxyService.start(this)
        ui.postDelayed({ refreshStatus(); probePort() }, 1000)
    }

    /** 跳转系统「应用启动管理」/电池优化，引导把本 App 加入自启白名单。 */
    private fun openAutostartSettings() {
        // 荣耀/华为：应用启动管理
        val candidates = listOf(
            android.content.ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            android.content.ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
        )
        for (cn in candidates) {
            val i = Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(i) }.isSuccess) {
                log("已打开「应用启动管理」，请把 DSH 代理设为手动管理并允许自启动")
                return
            }
        }
        // 兜底：应用详情页
        log("未找到启动管理页面，改打开应用详情页；请在「耗电/启动管理」里允许自启")
        openDshAppInfoFor(applicationContext.packageName)
    }

    private fun openDshAppInfoFor(pkg: String) {
        val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(i) }
            .onFailure { toast("打不开系统设置页") }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        ProxyLog.unobserve(logSink)
        pool.shutdownNow()
        super.onDestroy()
    }

    // ---------- 配置 ----------

    private fun setupPresets() {
        val names = Presets.ALL.map { it.name }
        spPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        // setSelection 在装监听器之前调用，所以初始化不会触发回调；
        // userPicked 是第二道保险，防止未来改动顺序时又冲掉配置。
        spPreset.setSelection(Presets.indexOfBaseUrl(store.baseUrl))
        spPreset.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (!userPicked) return
                val preset = Presets.ALL[pos]
                if (preset.baseUrl.isBlank()) return
                etBaseUrl.setText(preset.baseUrl)
                etModel.setText(preset.model)
                if (preset.note.isNotBlank()) log("预设：${preset.name} — ${preset.note}")
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        })
        spPreset.setOnTouchListener { _, _ ->
            userPicked = true
            false
        }
    }

    private fun loadConfig() {
        etBaseUrl.setText(store.baseUrl)
        etModel.setText(store.model)
        etApiKey.setText(store.apiKey)
        cbAutoStart.isChecked = store.autoStart
    }

    /**
     * 保存配置。
     *
     * fromUi=false 时（启动/自检/修复前的隐式保存）**不写 autoStart**：
     * 否则一旦复选框当前未勾选，就会被静默改坏，
     * 表现为「明明勾了开机自启，用几次就自己取消了」。
     */
    private fun saveConfig(fromUi: Boolean): Boolean {
        val base = etBaseUrl.text.toString().trim()
        if (base.isBlank()) {
            toast("Base URL 不能为空")
            return false
        }
        if (!base.startsWith("http")) {
            toast("Base URL 要以 http:// 或 https:// 开头")
            return false
        }
        if (etModel.text.toString().isBlank()) {
            toast("模型名不能为空")
            return false
        }
        store.baseUrl = base.trimEnd('/')
        store.model = etModel.text.toString().trim()
        store.apiKey = etApiKey.text.toString().trim()
        if (fromUi) {
            store.autoStart = cbAutoStart.isChecked
            log("配置已保存（开机自启=${if (cbAutoStart.isChecked) "开" else "关"}）")
            toast("已保存")
        }
        return true
    }

    // ---------- 启停 ----------

    private fun startProxy() {
        if (!saveConfig(fromUi = false)) return
        ProxyService.userStopped = false
        ProxyService.start(this)
        log("启动代理…")
        ui.postDelayed({ refreshStatus(); probePort() }, 800)
    }

    private fun stopProxy() {
        ProxyService.userStopped = true
        ProxyService.stop(this)
        log("已停止代理（本次不再自动启动，重启手机后按设置恢复）")
        ui.postDelayed({ refreshStatus() }, 500)
    }

    private fun refreshStatus() {
        val ok = ProxyService.running
        val port = ProxyConfig().port
        tvStatus.text = if (ok) {
            "状态：运行中 → 127.0.0.1:$port/v1"
        } else {
            "状态：未运行" + (ProxyService.lastError?.let { "（$it）" } ?: "")
        }
        tvStatus.setTextColor(if (ok) 0xFF1B7F3B.toInt() else 0xFFB3261E.toInt())
    }

    private fun probePort() {
        submit {
            val up = isPortOpen(ProxyConfig().port)
            ui.post {
                log(if (up) "✓ 本地端口已监听" else "✗ 本地端口未监听")
            }
        }
    }

    private fun isPortOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1500) }
        true
    } catch (_: Exception) {
        false
    }

    // ---------- 自检 ----------

    private fun selfTest() {
        if (!saveConfig(fromUi = false)) return
        val cfg = store.load()
        log("=== 开始自检 ===")
        submit {
            SelfTest.run(cfg) { line -> ui.post { log(line) } }
            // 关键：isPortOpen 会建立 TCP 连接，必须在后台线程做。
            // 之前放在 ui.post{} 里跑在主线程，抛 NetworkOnMainThreadException，
            // 又被 catch(Exception) 吞掉，导致永远误报「未监听」。
            val up = isPortOpen(cfg.port)
            ui.post {
                log(if (up) "✓ 本地代理端口正常" else "✗ 本地代理端口未监听，先点「启动」")
                log("=== 自检结束 ===")
                refreshStatus()
            }
        }
    }

    // ---------- 打开 DSH ----------

    private fun openDsh() {
        // 先试包管理器给的 launcher intent；拿不到就用显式组件名兜底。
        // （Android 11+ 若无 <queries> 声明，getLaunchIntentForPackage 会返回 null）
        val fromPm = packageManager.getLaunchIntentForPackage(DSH_PKG)
        val intent = fromPm ?: android.content.Intent().apply {
            setClassName(DSH_PKG, "$DSH_PKG/.MainActivity")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(intent) }
            .onSuccess { log("已打开 DSH") }
            .onFailure { log("打开 DSH 失败：${it.message}") }
    }

    private fun copyLocalUrl() {
        val url = "http://127.0.0.1:${ProxyConfig().port}/v1"
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("base_url", url))
        log("已复制 $url")
        toast("已复制")
    }

    // ---------- 杂项 ----------

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun log(msg: String) {
        ProxyLog.add(msg)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val DSH_PKG = "io.github.jackwener.dsh"
    }
}
