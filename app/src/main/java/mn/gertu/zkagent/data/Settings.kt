package mn.gertu.zkagent.data

import android.content.Context
import android.content.SharedPreferences
import java.time.ZonedDateTime

/**
 * Тохиргоо ба төлөв. SharedPreferences дээр хадгална — хэмжээ багатай,
 * апп унтраад асахад хэвээр үлдэнэ.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zkagent", Context.MODE_PRIVATE)

    // ── Backend ─────────────────────────────────────────────────────────

    /** Жишээ: https://hr.gertu.mn  эсвэл  http://192.168.1.180:3000 */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, "")!!
        set(v) = prefs.edit().putString(KEY_SERVER, v.trim().trimEnd('/')).apply()

    /** biometric_devices.bridge_token */
    var bridgeToken: String
        get() = prefs.getString(KEY_TOKEN, "")!!
        set(v) = prefs.edit().putString(KEY_TOKEN, v.trim()).apply()

    // ── Терминал ────────────────────────────────────────────────────────

    var deviceName: String
        get() = prefs.getString(KEY_NAME, "Үндсэн орц")!!
        set(v) = prefs.edit().putString(KEY_NAME, v.trim()).apply()

    var deviceSn: String
        get() = prefs.getString(KEY_SN, "")!!
        set(v) = prefs.edit().putString(KEY_SN, v.trim()).apply()

    var deviceHost: String
        get() = prefs.getString(KEY_HOST, "192.168.1.104")!!
        set(v) = prefs.edit().putString(KEY_HOST, v.trim()).apply()

    var devicePort: Int
        get() = prefs.getInt(KEY_PORT, 4370)
        set(v) = prefs.edit().putInt(KEY_PORT, v).apply()

    /** Терминал дээрх Comm Key. Тавиагүй бол 0. */
    var commKey: Long
        get() = prefs.getLong(KEY_COMM_KEY, 0)
        set(v) = prefs.edit().putLong(KEY_COMM_KEY, v).apply()

    // ── Ажиллагаа ───────────────────────────────────────────────────────

    var pollSeconds: Int
        get() = prefs.getInt(KEY_POLL, 60).coerceAtLeast(10)
        set(v) = prefs.edit().putInt(KEY_POLL, v.coerceAtLeast(10)).apply()

    /** Терминалын цагийг утасных руу тааруулах эсэх. */
    var syncTime: Boolean
        get() = prefs.getBoolean(KEY_SYNC_TIME, true)
        set(v) = prefs.edit().putBoolean(KEY_SYNC_TIME, v).apply()

    /** Хэрэглэгч restart хийхэд агент автоматаар асах эсэх. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTOSTART, v).apply()

    // ── Төлөв ───────────────────────────────────────────────────────────

    /**
     * Сүүлд амжилттай илгээсэн цохилтын цаг (ISO).
     *
     * Терминал өөрөө бүх түүхээ хадгалдаг тул агентад дараалал хэрэггүй —
     * зөвхөн "хаана хүрсэн"-ээ санана. Интернэт тасарвал юу ч алдагдахгүй.
     */
    var watermark: ZonedDateTime?
        get() = prefs.getString(KEY_WATERMARK, null)
            ?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }
        set(v) = prefs.edit().putString(KEY_WATERMARK, v?.toString()).apply()

    fun advanceWatermark(t: ZonedDateTime) {
        val cur = watermark
        if (cur == null || t.isAfter(cur)) watermark = t
    }

    fun resetWatermark() = prefs.edit().remove(KEY_WATERMARK).apply()

    /** Тохиргоо ажиллуулахад хангалттай бүрдсэн эсэх. */
    fun isComplete(): Boolean =
        serverUrl.isNotBlank() && bridgeToken.isNotBlank() && deviceHost.isNotBlank()

    fun missingFields(): List<String> = buildList {
        if (serverUrl.isBlank()) add("Backend хаяг")
        if (bridgeToken.isBlank()) add("Гүүрийн токен")
        if (deviceHost.isBlank()) add("Терминалын IP")
    }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_TOKEN = "bridge_token"
        const val KEY_NAME = "device_name"
        const val KEY_SN = "device_sn"
        const val KEY_HOST = "device_host"
        const val KEY_PORT = "device_port"
        const val KEY_COMM_KEY = "comm_key"
        const val KEY_POLL = "poll_seconds"
        const val KEY_SYNC_TIME = "sync_time"
        const val KEY_AUTOSTART = "auto_start"
        const val KEY_WATERMARK = "watermark"
    }
}
