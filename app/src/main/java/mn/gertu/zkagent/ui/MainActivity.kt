package mn.gertu.zkagent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mn.gertu.zkagent.data.AgentStatus
import mn.gertu.zkagent.data.Settings
import mn.gertu.zkagent.databinding.ActivityMainBinding
import mn.gertu.zkagent.net.BridgeClient
import mn.gertu.zkagent.service.AgentService
import mn.gertu.zkagent.zk.ZkClient
import java.time.ZoneId

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings

    private val askNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* татгалзсан ч service ажиллана, зөвхөн мэдэгдэл харагдахгүй */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        settings = Settings(this)
        loadIntoForm()
        wireButtons()
        observeStatus()
        requestNotificationPermission()
    }

    override fun onPause() {
        super.onPause()
        // Дэлгэцээс гарахад автоматаар хадгална — "Хадгалах дарахаа мартсан"
        // гэдэг алдааг бүрмөсөн арилгана
        saveFromForm()
    }

    // ────────────────────────────────────────────────────────────────────
    //  Форм
    // ────────────────────────────────────────────────────────────────────

    private fun loadIntoForm() = with(b) {
        etServer.setText(settings.serverUrl)
        etToken.setText(settings.bridgeToken)
        etName.setText(settings.deviceName)
        etSn.setText(settings.deviceSn)
        etHost.setText(settings.deviceHost)
        etPort.setText(settings.devicePort.toString())
        etCommKey.setText(if (settings.commKey == 0L) "" else settings.commKey.toString())
        etPoll.setText(settings.pollSeconds.toString())
        swSyncTime.isChecked = settings.syncTime
        swAutoStart.isChecked = settings.autoStart
    }

    private fun saveFromForm() = with(b) {
        settings.serverUrl = etServer.text.toString()
        settings.bridgeToken = etToken.text.toString()
        settings.deviceName = etName.text.toString()
        settings.deviceSn = etSn.text.toString()
        settings.deviceHost = etHost.text.toString()
        settings.devicePort = etPort.text.toString().toIntOrNull() ?: 4370
        settings.commKey = etCommKey.text.toString().toLongOrNull() ?: 0L
        settings.pollSeconds = etPoll.text.toString().toIntOrNull() ?: 60
        settings.syncTime = swSyncTime.isChecked
        settings.autoStart = swAutoStart.isChecked
    }

    // ────────────────────────────────────────────────────────────────────
    //  Товчнууд
    // ────────────────────────────────────────────────────────────────────

    private fun wireButtons() = with(b) {

        btnStartStop.setOnClickListener {
            saveFromForm()
            if (AgentStatus.running.value) {
                AgentService.stop(this@MainActivity)
            } else {
                val missing = settings.missingFields()
                if (missing.isNotEmpty()) {
                    toast("Дутуу: " + missing.joinToString(", "))
                    return@setOnClickListener
                }
                AgentService.start(this@MainActivity)
            }
        }

        btnTestDevice.setOnClickListener {
            saveFromForm()
            AgentStatus.log("── Терминал шалгаж байна…")
            runInBackground {
                ZkClient(
                    host = settings.deviceHost,
                    port = settings.devicePort,
                    password = settings.commKey,
                    zone = ZoneId.of("Asia/Ulaanbaatar"),
                ).use { zk ->
                    zk.connect()
                    val sizes = zk.readSizes()
                    val time = zk.getTime()
                    AgentStatus.log("✓ холбогдлоо · хэрэглэгч ${sizes.users} · лог ${sizes.records}")
                    AgentStatus.log("  терминалын цаг: ${time.toLocalDateTime()}")

                    val logs = zk.readAttLog()
                    AgentStatus.log("  ${logs.size} бичлэг уншлаа")
                    logs.takeLast(3).forEach { AgentStatus.log("  · ${it.describe()}") }
                }
            }
        }

        btnTestServer.setOnClickListener {
            saveFromForm()
            AgentStatus.log("── Backend шалгаж байна…")
            runInBackground {
                val out = BridgeClient(settings.serverUrl).testConnection(settings.bridgeToken)
                AgentStatus.log("✓ backend хариулав, токен зөв (${out.summary()})")
            }
        }

        btnBattery.setOnClickListener { openBatterySettings() }

        btnResetWatermark.setOnClickListener {
            settings.resetWatermark()
            AgentStatus.log("Тэмдэглэл цэвэрлэгдлээ — дараагийн мөчлөгт бүх лог дахин илгээгдэнэ")
            toast("Тэмдэглэл цэвэрлэгдлээ")
        }

        btnClearLog.setOnClickListener { AgentStatus.clear() }
    }

    /** Сүлжээний ажлыг үндсэн урсгалаас гаргана — эс тэгвэл апп царцна. */
    private fun runInBackground(block: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                AgentStatus.log("✗ ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Төлөв харуулах
    // ────────────────────────────────────────────────────────────────────

    private fun observeStatus() {
        lifecycleScope.launch {
            AgentStatus.running.collect { running ->
                b.btnStartStop.text = if (running) "Зогсоох" else "Эхлүүлэх"
                b.tvState.text = if (running) "Ажиллаж байна" else "Зогссон"
            }
        }
        lifecycleScope.launch {
            AgentStatus.headline.collect { b.tvHeadline.text = it }
        }
        lifecycleScope.launch {
            AgentStatus.lines.collect { lines ->
                b.tvLog.text = lines.asReversed().joinToString("\n")
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Зөвшөөрөл
    // ────────────────────────────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) askNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Батерейн оптимизацаас хасах.
     *
     * Үүнийг хийхгүй бол Android хэдэн цагийн дараа процессыг хязгаарлаж
     * эхэлнэ. Foreground service ч үүнээс бүрэн хамгаалагдахгүй —
     * ялангуяа Xiaomi, Huawei, Oppo дээр.
     */
    private fun openBatterySettings() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Аль хэдийн хасагдсан ✓")
            return
        }
        runCatching {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        }.onFailure {
            startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
