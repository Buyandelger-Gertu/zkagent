package mn.gertu.zkagent.zk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Эталон тестүүд.
 *
 * Бүх хүлээгдэж буй утга нь БОДИТ xFace600 төхөөрөмжөөс (ZAM180_TFT,
 * Ver 6.60 Aug 19 2021, SN FAC1242800004) утсан дээр барьсан байтууд.
 * Go хувилбар эдгээрийг даваад бодит төхөөрөмж дээр ажилласан.
 *
 * Эдгээр унавал протоколын порт буруу — Android-ийн асуудал биш.
 */
class ZkProtocolTest {

    private val ub: ZoneId = ZoneId.of("Asia/Ulaanbaatar")

    private fun hex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Пакет
    // ────────────────────────────────────────────────────────────────────

    /**
     * CONNECT пакет. Эхний илгээдэг зүйл — буруу бол төхөөрөмж чимээгүй
     * хаяж, "i/o timeout" гэж харагдана.
     */
    @Test
    fun connectPacketMatchesDevice() {
        val pkt = ZkProtocol.buildPacket(
            ZkProtocol.CMD_CONNECT, sessionId = 0, replyId = ZkProtocol.INITIAL_REPLY_ID,
        )
        assertEquals("e80317fc00000000", hex(pkt))
    }

    @Test
    fun connectPacketTcpWrapped() {
        val pkt = ZkProtocol.buildPacket(
            ZkProtocol.CMD_CONNECT, sessionId = 0, replyId = ZkProtocol.INITIAL_REPLY_ID,
        )
        assertEquals("5050827d08000000e80317fc00000000", hex(ZkProtocol.tcpWrap(pkt)))
    }

    // ────────────────────────────────────────────────────────────────────
    //  Comm Key
    // ────────────────────────────────────────────────────────────────────

    /**
     * Төхөөрөмж хүлээн авсан бодит түлхүүр.
     * Гурав дахь байт нь ticks (0x32 = 50) — XOR биш, орлуулсан.
     */
    @Test
    fun commKeyMatchesDevice() {
        assertEquals("297f3274", hex(ZkProtocol.makeCommKey(123456L, 36205, 50)))
    }

    /** Төхөөрөмж ACK_OK буцаасан бүтэн AUTH пакет. */
    @Test
    fun authPacketMatchesDevice() {
        val key = ZkProtocol.makeCommKey(123456L, 36205, 50)
        val pkt = ZkProtocol.buildPacket(ZkProtocol.CMD_AUTH, 36205, 0, key)
        assertEquals("4e04e77a6d8d0100297f3274", hex(pkt))
    }

    /** Гурав дахь байтыг XOR болговол ийм болно — энэ БУРУУ хувилбар. */
    @Test
    fun commKeyThirdByteIsTicksNotXor() {
        val key = ZkProtocol.makeCommKey(123456L, 36205, 50)
        assertEquals("ticks-ийн утга байх ёстой", 0x32, key[2].toInt() and 0xFF)
    }

    @Test
    fun commKeyVariesWithSession() {
        // Go хувилбартай тулгасан утгууд
        assertEquals("297f3275", hex(ZkProtocol.makeCommKey(123456L, 36031, 50)))
    }

    // ────────────────────────────────────────────────────────────────────
    //  Checksum
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun checksumOfZeroBuffer() {
        // sum=0 → ~0 = -1 → +0xFFFF = 65534
        assertEquals(65534, ZkProtocol.checksum(ByteArray(8)))
    }

    // ────────────────────────────────────────────────────────────────────
    //  Цаг
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun timeRoundTrip() {
        val orig = ZonedDateTime.of(2026, 9, 23, 14, 54, 50, 0, ub)
        val got = ZkProtocol.decodeTime(ZkProtocol.encodeTime(orig), ub)
        assertEquals(orig, got)
    }

    /** Бодит бичлэгээс: 0x3333c88c → buyna-гийн 2026-09-23 11:54:52 */
    @Test
    fun decodeRealTimestamp() {
        val got = ZkProtocol.decodeTime(0x3333c88cL, ub)
        assertEquals("2026-09-23T11:54:52", got.toLocalDateTime().toString())
    }

    // ────────────────────────────────────────────────────────────────────
    //  49 байтын бичлэг
    // ────────────────────────────────────────────────────────────────────

    /**
     * xFace600-аас татсан бодит бичлэгүүд.
     *
     * Бүтэц: uid(2) user_id(24) status(1) timestamp(4) punch(1) нөөц(17)
     *
     * Эдгээр нь ZKBio Time.Net-ийн дэлгэц дээр харагдсантай таарсан:
     * Tengis 15:11 Check Out, 15:12 Overtime In.
     */
    private val sampleAttHex =
        // uid=1  user_id="1"  царай  2026-09-23 11:54:52  ирсэн
        "0100310000000000000000000000000000000000000000000000" +
            "0f8cc8333300" + "0000000000000000ff3235350000000000" +
            // uid=15 user_id="2"  царай  14:54:50  ирсэн
            "0f00320000000000000000000000000000000000000000000000" +
            "0fbaf2333300" + "0000000000000000ff3235350000000000" +
            // uid=30 user_id="8"  царай  15:11:05  ЯВСАН
            "1e00380000000000000000000000000000000000000000000000" +
            "0f89f6333301" + "0000000000000000ff3235350000000000" +
            // uid=35 user_id="8"  царай  15:12:54  ИЦ ЭХЭЛСЭН
            "2300380000000000000000000000000000000000000000000000" +
            "0ff6f6333304" + "0000000000000000ff3235350000000000"

    @Test
    fun parse49ByteRecords() {
        val body = unhex(sampleAttHex)
        assertEquals("жишээ өгөгдөл 49-д хуваагдах ёстой", 0, body.size % 49)

        // Түүхий блок 4 байтын толгойтой ирдэг
        val data = ByteArray(4) + body
        val recs = AttLogParser.parse(data, ub, recordCount = body.size / 49)

        assertEquals(4, recs.size)

        recs[0].let {
            assertEquals(1, it.uid)
            assertEquals("1", it.userId)
            assertEquals(ZkProtocol.VERIFY_FACE, it.status)
            assertEquals(ZkProtocol.PUNCH_CHECK_IN, it.punch)
            assertEquals("2026-09-23T11:54:52", it.time.toLocalDateTime().toString())
        }
        recs[1].let {
            assertEquals(15, it.uid)
            assertEquals("2", it.userId)
            assertEquals("2026-09-23T14:54:50", it.time.toLocalDateTime().toString())
        }
        recs[2].let {
            assertEquals("8", it.userId)
            assertEquals(ZkProtocol.PUNCH_CHECK_OUT, it.punch)
            assertEquals("2026-09-23T15:11:05", it.time.toLocalDateTime().toString())
        }
        recs[3].let {
            assertEquals("8", it.userId)
            assertEquals(ZkProtocol.PUNCH_OT_IN, it.punch)
            assertEquals("2026-09-23T15:12:54", it.time.toLocalDateTime().toString())
        }
    }

    /** Бичлэгийн тоо мэдэгдэхгүй үед ч хэмжээг таамаглах ёстой. */
    @Test
    fun parseAutoDetectsRecordSize() {
        val data = ByteArray(4) + unhex(sampleAttHex)
        val recs = AttLogParser.parse(data, ub, recordCount = 0)
        assertEquals(4, recs.size)
        assertEquals("1", recs[0].userId)
    }

    @Test
    fun parseEmptyBlob() {
        assertTrue(AttLogParser.parse(ByteArray(4), ub).isEmpty())
        assertTrue(AttLogParser.parse(ByteArray(0), ub).isEmpty())
    }

    // ────────────────────────────────────────────────────────────────────
    //  Туслахууд
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun cstrStopsAtNull() {
        val b = unhex("3100000000")
        assertEquals("1", ZkProtocol.cstr(b, 0, 5))
    }

    @Test
    fun le16AndLe32() {
        val b = unhex("8cc83333")
        assertEquals(0xc88c, ZkProtocol.le16(b, 0))
        assertEquals(0x3333c88cL, ZkProtocol.le32(b, 0))
    }
}
