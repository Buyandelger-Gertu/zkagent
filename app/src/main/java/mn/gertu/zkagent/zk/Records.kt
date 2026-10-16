package mn.gertu.zkagent.zk

import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Нэвтрэлтийн нэг бичлэг.
 *
 * ⚠️ Нэршил төөрөгдүүлэхүйц: ZK-ийн `status` нь ТАНИЛТЫН АРГА (15 = царай),
 * `punch` нь ИРСЭН/ЯВСАН. Backend-ийн `verify_mode` / `status` -тай
 * ЭСРЭГЭЭРЭЭ таарна. Хөрвүүлэлтийг BridgeClient хийнэ.
 */
data class AttRecord(
    val uid: Int,
    val userId: String,
    val status: Int,       // танилтын арга
    val punch: Int,        // ирсэн/явсан
    val time: ZonedDateTime,
) {
    fun describe(): String =
        "$userId  ${time.toLocalDateTime()}  ${ZkProtocol.punchName(punch)} / " +
            ZkProtocol.verifyModeName(status)
}

/** Төхөөрөмж дээр бүртгэлтэй хэрэглэгч. */
data class ZkUser(
    val uid: Int,
    val userId: String,
    val name: String,
    val privilege: Int,
    val card: Long,
    val groupId: Int,
)

/** Багтаамжийн мэдээлэл. */
data class ZkSizes(
    val users: Int,
    val fingers: Int,
    val records: Int,
    val cards: Int,
    val fingersCap: Int,
    val usersCap: Int,
    val recordsCap: Int,
)

/**
 * Түүхий блокоос бичлэгүүдийг задлах.
 *
 * Эхний 4 байт нь нийт хэмжээ — алгасна.
 *
 * Бичлэгийн хэмжээ загвар болгон дээр ӨӨР: xFace600 дээр 49 байт, бусад
 * дээр 40. Таамаглахын оронд төхөөрөмжөөс бичлэгийн тоог асууж
 * `их бие ÷ тоо` гэж яг тооцно ([recordCount] өгөх).
 *
 * 32 байтаас дээш бүх хувилбарт толгойн бүтэц нэг:
 *
 *     uid(2) user_id(24) status(1) timestamp(4) punch(1) [нөөц…]
 */
object AttLogParser {

    private val CANDIDATE_SIZES = intArrayOf(49, 40, 52, 48, 44, 36, 32, 28, 16)

    fun parse(data: ByteArray, zone: ZoneId, recordCount: Int = 0): List<AttRecord> {
        if (data.size < 4) return emptyList()
        val bodyLen = data.size - 4
        if (bodyLen <= 0) return emptyList()

        var recSize = 0
        if (recordCount > 0 && bodyLen % recordCount == 0) {
            recSize = bodyLen / recordCount
        }
        if (recSize == 0) {
            recSize = CANDIDATE_SIZES.firstOrNull { bodyLen % it == 0 } ?: 0
        }

        return when {
            recSize >= 32 -> parseGeneric(data, 4, bodyLen, recSize, zone)
            recSize == 16 -> parse16(data, 4, bodyLen, zone)
            else -> throw ZkException(
                "бичлэгийн хэмжээ тодорхойлогдсонгүй: их бие $bodyLen байт, " +
                    "төхөөрөмжийн бичлэгийн тоо $recordCount"
            )
        }
    }

    private fun parseGeneric(
        data: ByteArray, start: Int, bodyLen: Int, recSize: Int, zone: ZoneId,
    ): List<AttRecord> {
        val out = ArrayList<AttRecord>(bodyLen / recSize)
        var i = start
        val end = start + bodyLen
        while (i + recSize <= end) {
            val userId = ZkProtocol.cstr(data, i + 2, 24)
            if (userId.isNotEmpty()) {
                out.add(
                    AttRecord(
                        uid = ZkProtocol.le16(data, i),
                        userId = userId,
                        status = data[i + 26].toInt() and 0xFF,
                        time = ZkProtocol.decodeTime(ZkProtocol.le32(data, i + 27), zone),
                        punch = data[i + 31].toInt() and 0xFF,
                    )
                )
            }
            i += recSize
        }
        return out
    }

    /** Хуучин загвар: user_id(4) time(4) status(1) punch(1) res(2) workcode(4) */
    private fun parse16(
        data: ByteArray, start: Int, bodyLen: Int, zone: ZoneId,
    ): List<AttRecord> {
        val out = ArrayList<AttRecord>(bodyLen / 16)
        var i = start
        val end = start + bodyLen
        while (i + 16 <= end) {
            val id = ZkProtocol.le32(data, i)
            if (id != 0L) {
                out.add(
                    AttRecord(
                        uid = 0,
                        userId = id.toString(),
                        status = data[i + 8].toInt() and 0xFF,
                        punch = data[i + 9].toInt() and 0xFF,
                        time = ZkProtocol.decodeTime(ZkProtocol.le32(data, i + 4), zone),
                    )
                )
            }
            i += 16
        }
        return out
    }
}

/**
 * Хэрэглэгчийн бичлэг задлах.
 *
 * 72 байт: uid(2) pri(1) passwd(8) name(24) card(4) pad(1) group(7) pad(1) user_id(24)
 * 28 байт: uid(2) pri(1) passwd(5) name(8) card(4) pad(1) group(1) tz(2) user_id(4)
 */
object UserParser {

    fun parse(data: ByteArray): List<ZkUser> {
        if (data.size < 4) return emptyList()
        val bodyLen = data.size - 4
        if (bodyLen <= 0) return emptyList()

        return when {
            bodyLen % 72 == 0 -> parse72(data, 4, bodyLen)
            bodyLen % 28 == 0 -> parse28(data, 4, bodyLen)
            else -> throw ZkException("хэрэглэгчийн бичлэгийн хэмжээ танигдсангүй: $bodyLen байт")
        }
    }

    private fun parse72(data: ByteArray, start: Int, bodyLen: Int): List<ZkUser> {
        val out = ArrayList<ZkUser>(bodyLen / 72)
        var i = start
        val end = start + bodyLen
        while (i + 72 <= end) {
            val uid = ZkProtocol.le16(data, i)
            var userId = ZkProtocol.cstr(data, i + 48, 24)
            if (userId.isEmpty()) userId = uid.toString()

            var name = ZkProtocol.cstr(data, i + 11, 24)
            // Төхөөрөмж дээр нэр бүртгээгүй бол ID-г нь харуулна
            if (name.isEmpty()) name = userId

            out.add(
                ZkUser(
                    uid = uid,
                    userId = userId,
                    name = name,
                    privilege = data[i + 2].toInt() and 0xFF,
                    card = ZkProtocol.le32(data, i + 35),
                    groupId = ZkProtocol.cstr(data, i + 40, 7).toIntOrNull() ?: 0,
                )
            )
            i += 72
        }
        return out
    }

    private fun parse28(data: ByteArray, start: Int, bodyLen: Int): List<ZkUser> {
        val out = ArrayList<ZkUser>(bodyLen / 28)
        var i = start
        val end = start + bodyLen
        while (i + 28 <= end) {
            val userId = ZkProtocol.le32(data, i + 24).toString()
            var name = ZkProtocol.cstr(data, i + 8, 8)
            if (name.isEmpty()) name = userId

            out.add(
                ZkUser(
                    uid = ZkProtocol.le16(data, i),
                    userId = userId,
                    name = name,
                    privilege = data[i + 2].toInt() and 0xFF,
                    card = ZkProtocol.le32(data, i + 16),
                    groupId = data[i + 21].toInt() and 0xFF,
                )
            )
            i += 28
        }
        return out
    }
}

class ZkException(message: String, cause: Throwable? = null) : Exception(message, cause)
