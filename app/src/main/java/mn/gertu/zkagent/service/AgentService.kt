package mn.gertu.zkagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mn.gertu.zkagent.R
import mn.gertu.zkagent.data.AgentStatus
import mn.gertu.zkagent.data.Settings
import mn.gertu.zkagent.net.BridgeClient
import mn.gertu.zkagent.ui.MainActivity
import mn.gertu.zkagent.zk.AttRecord
import mn.gertu.zkagent.zk.ZkClient
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Терминалаас цохилт татаж backend руу илгээх үргэлжилсэн үйлчилгээ.
 *
 * Foreground service болгосны учир: Android энгийн background процессыг
 * хэдхэн минутын дараа устгадаг. Foreground service нь байнгын
 * мэдэгдэлтэй — тэр нь "энэ ажил хэрэглэгчид харагдаж байна" гэсэн гэрээ
 * бөгөөд систем түүнийг амархан таслахгүй.
 *
 * `specialUse` төрлийг сонгосон шалтгаан: `dataSync` нь Android 15 дээр
 * 24 цагт 6 хүртэл цаг гэсэн хязгаартай — 24/7 ажиллах агентад тохирохгүй.
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "ZkAgent"
        private const val CHANNEL_ID = "agent"
        private const val NOTIFICATION_ID = 1

        // Хэрэглэгчийн жагсаалт татах давтамж. Хүн ховор нэмэгддэг тул
        // цохилтын мөчлөг бүрд татах нь илүүц ачаалал.
        private const val USER_SYNC_INTERVAL_MS = 10 * 60 * 1000L

        const val ACTION_START = "mn.gertu.zkagent.START"
        const val ACTION_STOP = "mn.gertu.zkagent.STOP"

        fun start(context: Context) {
            val i = Intent(context, AgentService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AgentService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private lateinit var settings: Settings
    private val zone: ZoneId = ZoneId.of("Asia/Ulaanbaatar")

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /**
     * Хэрэглэгчийн жагсаалтыг сүүлд хэзээ илгээсэн.
     *
     * Цохилт бүрийн хамт илгээх шаардлагагүй — хүн ховор нэмэгддэг.
     * Гэхдээ огт илгээхгүй бол шинэ ажилтныг админ харахгүй.
     */
    private var lastUserSync = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
        }

        if (job?.isActive == true) return START_STICKY

        startForeground(NOTIFICATION_ID, buildNotification("Эхэлж байна…"))
        acquireLocks()
        AgentStatus.setRunning(true)
        AgentStatus.report("Агент эхэллээ")

        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        job = s.launch { loop() }

        // Систем устгавал дахин асаана
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun stopEverything() {
        job?.cancel()
        scope?.cancel()
        job = null
        scope = null
        releaseLocks()
        AgentStatus.setRunning(false)
        AgentStatus.log("Агент зогсов")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ────────────────────────────────────────────────────────────────────
    //  Үндсэн мөчлөг
    // ────────────────────────────────────────────────────────────────────

    private suspend fun loop() {
        val s = scope ?: return
        while (s.isActive) {
            val started = System.currentTimeMillis()

            try {
                cycle()
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "мөчлөг: $msg", e)
                AgentStatus.report("Алдаа: $msg")
                notify(AgentStatus.headline.value)
            }

            val elapsed = System.currentTimeMillis() - started
            val wait = settings.pollSeconds * 1000L - elapsed
            if (wait > 0) delay(wait)
        }
    }

    private fun cycle() {
        if (!settings.isComplete()) {
            AgentStatus.report("Тохиргоо дутуу: " + settings.missingFields().joinToString(", "))
            notify(AgentStatus.headline.value)
            return
        }

        val name = settings.deviceName.ifBlank { settings.deviceHost }

        ZkClient(
            host = settings.deviceHost,
            port = settings.devicePort,
            password = settings.commKey,
            zone = zone,
            log = { Log.d(TAG, it) },
        ).use { zk ->
            zk.connect()

            checkClock(zk, name)

            syncUsers(zk, name)

            val logs = zk.readAttLog()
            val fresh = filterNew(logs)

            if (fresh.isEmpty()) {
                val msg = "$name · шинэ цохилт алга (терминал дээр ${logs.size})"
                AgentStatus.headline(msg)
                notify(msg)
                return@use
            }

            sendAll(name, fresh)
        }
    }

    /**
     * Терминал дээрх хэрэглэгчийн жагсаалтыг backend руу илгээнэ.
     *
     * ⚠️ БҮТЭН УНШИЛТ БОЛСОН ҮЕД Л ИЛГЭЭНЭ. Backend нь ирээгүй PIN-ийг
     * «терминалаас устсан» гэж үзэх тул хагас жагсаалт илгээвэл байгаа
     * хүмүүсийг алга болсон гэж бүртгэнэ. Алдаа гарвал чимээгүй алгасна —
     * ирц татах гол ажлыг үүнээс болж зогсоохгүй.
     */
    private fun syncUsers(zk: ZkClient, name: String) {
        val now = System.currentTimeMillis()
        if (now - lastUserSync < USER_SYNC_INTERVAL_MS) return

        try {
            val users = zk.readUsers()
            if (users.isEmpty()) {
                AgentStatus.log("$name · терминал дээр хэрэглэгч алга")
                lastUserSync = now
                return
            }

            val summary = BridgeClient(settings.serverUrl).sendUsers(settings.bridgeToken, users)
            AgentStatus.log("$name · $summary")
            lastUserSync = now
        } catch (e: Exception) {
            Log.w(TAG, "хэрэглэгч илгээх: ${e.message}", e)
            AgentStatus.log("$name · хэрэглэгчийн жагсаалт илгээгдсэнгүй: ${e.message}")
            // lastUserSync-ийг урагшлуулахгүй — дараагийн мөчлөгт дахин оролдоно
        }
    }

    /**
     * Терминалын цаг буруу бол цохилтын огноо буруу бичигдэнэ.
     * Тайлан бүхэлдээ эвдэрч, буцааж засахад хэцүү.
     */
    private fun checkClock(zk: ZkClient, name: String) {
        val deviceTime = runCatching { zk.getTime() }.getOrNull() ?: return
        val drift = ChronoUnit.SECONDS.between(deviceTime, ZonedDateTime.now(zone))

        if (drift in -120..120) return

        if (settings.syncTime) {
            runCatching { zk.setTime(ZonedDateTime.now(zone)) }
                .onSuccess { AgentStatus.log("$name · цаг тааруулав (зөрүү ${drift}с)") }
                .onFailure { AgentStatus.log("$name · цаг тааруулж чадсангүй: ${it.message}") }
        } else {
            AgentStatus.log("$name · ⚠ цагийн зөрүү ${drift}с")
        }
    }

    /** Сүүлд илгээснээс хойшхийг л авна. Давхардлыг backend бас таслана. */
    private fun filterNew(logs: List<AttRecord>): List<AttRecord> {
        val since = settings.watermark
        return logs
            .filter { since == null || it.time.isAfter(since) }
            .sortedBy { it.time }
    }

    private fun sendAll(name: String, records: List<AttRecord>) {
        val bridge = BridgeClient(settings.serverUrl)
        var sent = 0

        records.chunked(BridgeClient.MAX_BATCH).forEach { batch ->
            val out = bridge.sendPunches(settings.bridgeToken, batch)
            sent += batch.size

            AgentStatus.log("$name · ${batch.size} цохилт: ${out.summary()}")
            out.invalidDetails.take(3).forEach { AgentStatus.log("   ✗ $it") }

            // Backend тодорхой хариу өгсөн тул энэ багцыг дууссанд тооцно.
            // Алдаа гарвал энэ мөр хүрэхгүй — тэмдэглэл урагшлахгүй,
            // дараагийн мөчлөгт дахин оролдоно.
            settings.advanceWatermark(batch.last().time)
        }

        val msg = "$name · $sent цохилт илгээв"
        AgentStatus.headline(msg)
        notify(msg)
    }

    // ────────────────────────────────────────────────────────────────────
    //  Түгжээ — унтсан үед ажиллах
    // ────────────────────────────────────────────────────────────────────

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZkAgent::poll").apply {
            setReferenceCounted(false)
            acquire()
        }

        // Дэлгэц унтрахад Android WiFi-г унтраадаг. Үүнгүйгээр шөнө
        // холболт тасарч, өглөө нь "яагаад лог алга" болно.
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ZkAgent::wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    // ────────────────────────────────────────────────────────────────────
    //  Мэдэгдэл
    // ────────────────────────────────────────────────────────────────────

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Агентын төлөв",
            // LOW — дуу чимээгүй, гэхдээ байнга харагдана
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Терминалаас цохилт татаж байгаа эсэх"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ZkAgent")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_agent)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "Зогсоох", stop,
                ).build()
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notify(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        runCatching { mgr.notify(NOTIFICATION_ID, buildNotification(text)) }
    }
}
