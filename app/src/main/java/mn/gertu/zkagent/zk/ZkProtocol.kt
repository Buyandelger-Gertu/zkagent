package mn.gertu.zkagent.zk

import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * ZKTeco standalone протокол (порт 4370).
 *
 * ZKTeco үүнийг албан ёсоор баримтжуулаагүй. Доорх бүтэц нь бодит
 * xFace600 (ZAM180_TFT, Ver 6.60 Aug 19 2021) төхөөрөмжтэй солилцсон
 * байтуудаар баталгаажсан. Эталон утгуудыг ZkProtocolTest барина —
 * энд юм засвал тэр тестүүд шууд унана.
 *
 * Пакетын бүтэц (TCP):
 *
 *     ┌──────── TCP боодол (8 байт) ────────┐
 *     │ 0x5050 │ 0x7d82 │ size (uint32)      │
 *     └─────────────────────────────────────┘
 *     ┌──────── ZK толгой (8 байт) ─────────┐
 *     │ command │ checksum │ session │ reply │  бүгд uint16 LE
 *     └─────────────────────────────────────┘
 *     │ payload …                            │
 */
object ZkProtocol {

    // ── TCP боодлын тэмдэг ──────────────────────────────────────────────
    const val TCP_MAGIC_1 = 20560 // 0x5050
    const val TCP_MAGIC_2 = 32130 // 0x7d82

    // ── Командууд ───────────────────────────────────────────────────────
    const val CMD_CONNECT = 1000
    const val CMD_EXIT = 1001
    const val CMD_ENABLE_DEVICE = 1002
    const val CMD_DISABLE_DEVICE = 1003
    const val CMD_RESTART = 1004

    const val CMD_ACK_OK = 2000
    const val CMD_ACK_ERROR = 2001
    const val CMD_ACK_DATA = 2002
    const val CMD_ACK_UNAUTH = 2005

    const val CMD_PREPARE_DATA = 1500
    const val CMD_DATA = 1501
    const val CMD_FREE_DATA = 1502
    const val CMD_DATA_WRRQ = 1503   // буфер бэлтгэх хүсэлт
    const val CMD_READ_BUFFER = 1504 // буферээс хэсэгчлэн унших

    const val CMD_ATTLOG_RRQ = 13    // нэвтрэлтийн лог унших
    const val CMD_CLEAR_ATTLOG = 15
    const val CMD_USERTEMP_RRQ = 9   // хэрэглэгч унших
    const val CMD_USER_WRQ = 8
    const val CMD_DELETE_USER = 18
    const val CMD_REFRESH_DATA = 1013
    const val CMD_UNLOCK = 31        // хаалга онгойлгох
    const val CMD_GET_TIME = 201
    const val CMD_SET_TIME = 202
    const val CMD_GET_FREE_SIZES = 50
    const val CMD_AUTH = 1102

    // ── Танилтын арга (бичлэгийн status талбар) ─────────────────────────
    const val VERIFY_FINGERPRINT = 1
    const val VERIFY_USER_ID = 2
    const val VERIFY_PASSWORD = 3
    const val VERIFY_CARD = 4
    const val VERIFY_FACE = 15
    const val VERIFY_PALM = 16

    // ── Цохилтын төлөв (бичлэгийн punch талбар) ─────────────────────────
    const val PUNCH_CHECK_IN = 0
    const val PUNCH_CHECK_OUT = 1
    const val PUNCH_BREAK_OUT = 2
    const val PUNCH_BREAK_IN = 3
    const val PUNCH_OT_IN = 4
    const val PUNCH_OT_OUT = 5

    /**
     * reply_id-ийн эхлэх утга (USHRT_MAX - 1).
     *
     * Үүнээс болж эхний пакет reply_id=0 -оор явна. Санамсаргүй тоо биш —
     * pyzk-ийн зан үйлийг давтаж байгаа бөгөөд бодит төхөөрөмж үүнийг
     * хүлээж авдаг.
     */
    const val INITIAL_REPLY_ID = 0xFFFF - 1

    // ────────────────────────────────────────────────────────────────────
    //  Байт унших/бичих туслахууд (бүгд little-endian)
    // ────────────────────────────────────────────────────────────────────

    fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun le32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    fun putLe16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    fun putLe32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    // ────────────────────────────────────────────────────────────────────
    //  Checksum
    // ────────────────────────────────────────────────────────────────────

    /**
     * 16 бит үгсийг нэмж, 0xFFFF-ээр багасгаж, эцэст нь нэгийн нөхөлт.
     *
     * Энгийн нийлбэр БИШ — тээлтийг эргүүлэн нэмэх, сөрөг утгыг 0xFFFF
     * нэмж засах нь заавал. Нэг алхам алгасвал төхөөрөмж пакетыг
     * чимээгүй хаяна.
     */
    fun checksum(buf: ByteArray, len: Int = buf.size): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < len) {
            sum += le16(buf, i).toLong()
            if (sum > 0xFFFF) sum -= 0xFFFF
            i += 2
        }
        if (i < len) sum += (buf[i].toInt() and 0xFF).toLong()
        while (sum > 0xFFFF) sum -= 0xFFFF

        var c = sum.inv() // -sum-1
        while (c < 0) c += 0xFFFF
        return (c and 0xFFFF).toInt()
    }

    /**
     * ZK толгой + payload.
     *
     * ⚠️ Нэг зөрүүтэй зан үйл: checksum-ыг `replyId` дээр бодоод, пакетад
     * `replyId + 1`-ийг бичнэ. Өөрөөр хэлбэл илгээсэн пакетын checksum нь
     * өөрийнх нь агуулгатай ТААРАХГҮЙ. Логик бус боловч бодит төхөөрөмж
     * яг үүнийг хүлээж авдаг тул зөрүүлж болохгүй.
     */
    fun buildPacket(
        cmd: Int,
        sessionId: Int,
        replyId: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val buf = ByteArray(8 + payload.size)
        putLe16(buf, 0, cmd)
        putLe16(buf, 2, 0) // checksum-ыг 0-ээр бодно
        putLe16(buf, 4, sessionId)
        putLe16(buf, 6, replyId)
        payload.copyInto(buf, 8)

        val cs = checksum(buf)

        var next = replyId + 1
        if (next >= 0xFFFF) next -= 0xFFFF

        putLe16(buf, 2, cs)
        putLe16(buf, 6, next)
        return buf
    }

    /** TCP горимд пакетыг 8 байтын толгойгоор боож өгнө. */
    fun tcpWrap(pkt: ByteArray): ByteArray {
        val out = ByteArray(8 + pkt.size)
        putLe16(out, 0, TCP_MAGIC_1)
        putLe16(out, 2, TCP_MAGIC_2)
        putLe32(out, 4, pkt.size.toLong())
        pkt.copyInto(out, 8)
        return out
    }

    // ────────────────────────────────────────────────────────────────────
    //  Comm Key
    // ────────────────────────────────────────────────────────────────────

    /**
     * Төхөөрөмж CMD_ACK_UNAUTH буцаавал энэ түлхүүрийг CMD_AUTH-аар илгээнэ.
     *
     * ⚠️ Сүүлийн алхамд ГУРАВ ДАХЬ байт нь `ticks`-ийн утга ӨӨРӨӨ болно —
     * XOR биш, солино. Энэ нэг байтын зөрүүнээс болж auth бүхэлдээ унадаг.
     * Бодит төхөөрөмжөөс барьсан байтаар баталгаажсан:
     * session 36205, password 123456, ticks 50 → 297f3274
     */
    fun makeCommKey(password: Long, sessionId: Int, ticks: Int = 50): ByteArray {
        // Түлхүүрийн битүүдийг эргүүлнэ (bit 0 → bit 31)
        var k = 0L
        for (i in 0 until 32) {
            k = if ((password and (1L shl i)) != 0L) (k shl 1) or 1L else (k shl 1)
            k = k and 0xFFFFFFFFL
        }
        k = (k + sessionId) and 0xFFFFFFFFL

        val b = ByteArray(4)
        putLe32(b, 0, k)

        b[0] = (b[0].toInt() xor 'Z'.code).toByte()
        b[1] = (b[1].toInt() xor 'K'.code).toByte()
        b[2] = (b[2].toInt() xor 'S'.code).toByte()
        b[3] = (b[3].toInt() xor 'O'.code).toByte()

        // 16 битийн хагасуудыг солино
        val h0 = le16(b, 0)
        val h1 = le16(b, 2)
        putLe16(b, 0, h1)
        putLe16(b, 2, h0)

        val t = ticks and 0xFF
        b[0] = (b[0].toInt() xor t).toByte()
        b[1] = (b[1].toInt() xor t).toByte()
        b[2] = t.toByte() // ← XOR биш, ОРЛУУЛНА
        b[3] = (b[3].toInt() xor t).toByte()

        return b
    }

    // ────────────────────────────────────────────────────────────────────
    //  Цаг
    // ────────────────────────────────────────────────────────────────────

    /**
     * ZK-ийн шахсан огноог задална.
     *
     * Unix timestamp БИШ. Секунд/минут/цаг/өдөр/сар/жилийг нэг uint32-д
     * шахсан бөгөөд сар бүрийг 31 өдөр гэж үздэг.
     */
    fun decodeTime(raw: Long, zone: ZoneId): ZonedDateTime {
        var t = raw
        val sec = (t % 60).toInt(); t /= 60
        val min = (t % 60).toInt(); t /= 60
        val hour = (t % 24).toInt(); t /= 24
        val day = (t % 31).toInt() + 1; t /= 31
        val month = (t % 12).toInt() + 1; t /= 12
        val year = (t + 2000).toInt()
        return ZonedDateTime.of(year, month, day, hour, min, sec, 0, zone)
    }

    /** decodeTime-ийн эсрэг. */
    fun encodeTime(dt: ZonedDateTime): Long {
        val y = (dt.year % 100).toLong()
        return ((y * 12 * 31) + (dt.monthValue - 1) * 31 + dt.dayOfMonth - 1) * 24 * 60 * 60 +
            (dt.hour * 60 + dt.minute) * 60 + dt.second
    }

    // ────────────────────────────────────────────────────────────────────
    //  Мөр
    // ────────────────────────────────────────────────────────────────────

    /** null-паддинг хийсэн талбараас мөр гаргана. */
    fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val limit = minOf(off + len, b.size)
        while (end < limit && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
            .filter { it.code >= 32 }
            .trim()
    }

    // ────────────────────────────────────────────────────────────────────
    //  Нэршил
    // ────────────────────────────────────────────────────────────────────

    fun verifyModeName(v: Int): String = when (v) {
        VERIFY_FINGERPRINT -> "хурууны хээ"
        VERIFY_USER_ID -> "ID"
        VERIFY_PASSWORD -> "нууц үг"
        VERIFY_CARD -> "карт"
        VERIFY_FACE -> "царай"
        VERIFY_PALM -> "алга"
        else -> "тодорхойгүй($v)"
    }

    fun punchName(p: Int): String = when (p) {
        PUNCH_CHECK_IN -> "ирсэн"
        PUNCH_CHECK_OUT -> "явсан"
        PUNCH_BREAK_OUT -> "завсарлага гарсан"
        PUNCH_BREAK_IN -> "завсарлагаас ирсэн"
        PUNCH_OT_IN -> "ИЦ эхэлсэн"
        PUNCH_OT_OUT -> "ИЦ дууссан"
        else -> "тодорхойгүй($p)"
    }
}
