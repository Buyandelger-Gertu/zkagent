package mn.gertu.zkagent.net

import mn.gertu.zkagent.zk.AttRecord
import mn.gertu.zkagent.zk.ZkUser
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.format.DateTimeFormatter

/**
 * time-registration backend-ийн гүүрийн эндпойнт рүү цохилт илгээнэ.
 *
 *     POST {server}/api/device-bridge/punches
 *     X-Device-Token: {bridge_token}
 *
 * Backend тал аль хэдийн хэрэгжсэн — энд зөвхөн түүний гэрээг дагана.
 * Давхардлыг backend өөрөө таслах тул дахин илгээхээс эмээх хэрэггүй.
 */
class BridgeClient(
    private val serverUrl: String,
    private val timeoutMs: Int = 30_000,
) {

    data class Outcome(
        val accepted: Int,
        val duplicate: Int,
        val invalid: Int,
        val invalidDetails: List<String>,
    ) {
        val total get() = accepted + duplicate + invalid
        fun summary(): String = "$accepted шинэ · $duplicate давхардсан · $invalid буруу"
    }

    class BridgeException(message: String) : IOException(message)

    /** Backend 500-аас олныг нэг удаад хүлээж авахгүй. */
    companion object {
        const val MAX_BATCH = 500
        private val RFC3339: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }

    fun sendPunches(token: String, punches: List<AttRecord>): Outcome {
        require(punches.size <= MAX_BATCH) { "нэг удаад $MAX_BATCH-аас олон цохилт илгээж болохгүй" }

        val body = JSONObject().apply {
            put("punches", JSONArray().apply {
                punches.forEach { r ->
                    put(JSONObject().apply {
                        put("pin", r.userId)
                        put("occurred_at", r.time.format(RFC3339))
                        // ⚠️ Нэршил ЭСРЭГЭЭРЭЭ таарна:
                        //   ZK-ийн punch  (ирсэн/явсан)   → backend-ийн status
                        //   ZK-ийн status (танилтын арга) → backend-ийн verify_mode
                        put("status", r.punch)
                        put("verify_mode", r.status)
                    })
                }
            })
        }

        val (code, text) = post("/api/device-bridge/punches", token, body.toString())

        when (code) {
            in 200..299 -> Unit
            else -> throw BridgeException("Backend $code: ${text.take(300)}")
        }

        run {
            // Backend бүх хариуг {message, data} хэлбэрээр боодог
            val data = JSONObject(text).optJSONObject("data")
                ?: throw BridgeException("хариунд data талбар алга: ${text.take(200)}")

            val details = mutableListOf<String>()
            data.optJSONArray("results")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    if (r.optString("status") == "invalid") {
                        details += "PIN=${r.optString("pin")} ${r.optString("occurred_at")} — " +
                            r.optString("error")
                    }
                }
            }

            return Outcome(
                accepted = data.optInt("accepted"),
                duplicate = data.optInt("duplicate"),
                invalid = data.optInt("invalid"),
                invalidDetails = details,
            )
        }
    }

    /**
     * Нэг POST хүсэлт. Хоёр эндпойнт ижил толгой, ижил алдааны
     * тайлбартай тул нэг газар байлгав.
     */
    private fun post(path: String, token: String, json: String): Pair<Int, String> {
        val conn = (URL(serverUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Device-Token", token)
            setRequestProperty("User-Agent", "ZkAgent-Android")
        }

        try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

            // Эдгээр хоёрыг тусад нь барина — тохиргооны алдаа гэдэг нь
            // тодорхой байх ёстой, «Backend 401» гэж бичвэл ойлгомжгүй.
            /*
             * ⚠️ 401 нь ХОЁР УТГАТАЙ.
             *
             * Токен буруу байж болно — эсвэл зам нь тухайн серверийн
             * хувилбарт БАЙХГҮЙ. Backend нь api бүлгийн олдоогүй замыг
             * админы JWT шалгуур руу оруулдаг тул хариу нь мөн 401 болно.
             * Зөвхөн «токен буруу» гэж бичвэл хүн токеноо шалгаад цаг
             * алдана — үнэндээ серверээ шинэчлэх хэрэгтэй байхад.
             */
            when (code) {
                401 -> throw BridgeException(
                    "401 — токен буруу, ЭСВЭЛ $path зам серверийн энэ " +
                        "хувилбарт байхгүй. Цохилт хэвийн явж байвал токен зөв " +
                        "бөгөөд backend-ээ шинэчлэх хэрэгтэй.")
                403 -> throw BridgeException(
                    "Төхөөрөмж идэвхгүй (403). biometric_devices.is_active -ыг шалгана уу")
                404 -> throw BridgeException(
                    "404 — $path зам олдсонгүй. Backend хуучин хувилбартай байна.")
            }

            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Терминал дээрх хэрэглэгчийн жагсаалтыг backend руу илгээнэ.
     *
     * ⚠️ БҮРЭН ЖАГСААЛТ илгээх ёстой. Backend нь ирээгүй PIN-ийг
     * «терминалаас устсан» гэж үзнэ — хэсэгчилсэн жагсаалт илгээвэл
     * байгаа хүмүүсийг алга болсон гэж бүртгэнэ. Тиймээс уншилт бүтэн
     * амжилттай болсон үед л дуудна.
     *
     * Backend нь нэр өөрчлөгдсөнийг барьж, PIN дахин ашиглагдсан бол
     * холбоосыг таслана — ирц буруу хүнд бичигдэхээс сэргийлнэ.
     */
    fun sendUsers(token: String, users: List<ZkUser>): String {
        require(users.isNotEmpty()) { "хоосон жагсаалт илгээхгүй" }

        val body = JSONObject().apply {
            put("users", JSONArray().apply {
                users.forEach { u ->
                    put(JSONObject().apply {
                        put("pin", u.userId)
                        put("name", u.name)
                        put("privilege", u.privilege)
                        put("card", u.card)
                    })
                }
            })
        }

        val (code, text) = post("/api/device-bridge/users", token, body.toString())
        if (code !in 200..299) {
            throw BridgeException("Backend $code: ${text.take(300)}")
        }

        val data = JSONObject(text).optJSONObject("data") ?: return "хүлээн авлаа"
        val parts = mutableListOf<String>()
        data.optInt("added").takeIf { it > 0 }?.let { parts += "$it шинэ" }
        data.optInt("renamed").takeIf { it > 0 }?.let { parts += "$it нэр солигдсон" }
        data.optInt("removed").takeIf { it > 0 }?.let { parts += "$it устсан" }

        data.optJSONArray("unmapped_pins")?.let { arr ->
            if (arr.length() > 0) {
                val pins = (0 until arr.length()).joinToString(",") { arr.optString(it) }
                parts += "⚠ холбоос тасарсан: $pins"
            }
        }

        return if (parts.isEmpty()) "${users.size} хэрэглэгч, өөрчлөлтгүй"
               else "${users.size} хэрэглэгч · " + parts.joinToString(" · ")
    }

    /** Холболт шалгах — хоосон багц илгээнэ. Токен зөв эсэхийг мэдэх хамгийн хялбар арга. */
    fun testConnection(token: String): Outcome = sendPunches(token, emptyList())
}
