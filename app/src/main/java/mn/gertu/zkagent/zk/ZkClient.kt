package mn.gertu.zkagent.zk

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Нэг терминалтай хийх TCP session.
 *
 * Ашиглах:
 * ```
 * ZkClient("192.168.1.104", 4370, 123456, zone).use { c ->
 *     c.connect()
 *     val logs = c.readAttLog()
 * }
 * ```
 *
 * ⚠️ Терминал НЭГ session л зөвшөөрнө. ZKBio Time.Net ажиллаж байхад
 * холбогдохгүй.
 */
class ZkClient(
    private val host: String,
    private val port: Int = 4370,
    private val password: Long = 0,
    private val zone: ZoneId = ZoneId.of("Asia/Ulaanbaatar"),
    private val timeoutMs: Int = 15_000,
    private val log: ((String) -> Unit)? = null,
) : Closeable {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var sessionId = 0
    private var replyId = ZkProtocol.INITIAL_REPLY_ID

    private fun logf(msg: String) = log?.invoke(msg)

    /** Нээлттэй session-ы дугаар (оношилгоонд). */
    fun sessionId(): Int = sessionId

    // ────────────────────────────────────────────────────────────────────
    //  Холболт
    // ────────────────────────────────────────────────────────────────────

    fun connect() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        socket = s
        input = DataInputStream(s.getInputStream())

        sessionId = 0
        replyId = ZkProtocol.INITIAL_REPLY_ID

        val p = send(ZkProtocol.CMD_CONNECT)
        sessionId = p.sessionId
        logf("холбогдлоо: session=$sessionId код=${p.code}")

        when (p.code) {
            ZkProtocol.CMD_ACK_OK -> return

            ZkProtocol.CMD_ACK_UNAUTH -> {
                logf("Comm Key шаардаж байна")
                val key = ZkProtocol.makeCommKey(password, sessionId)
                val p2 = send(ZkProtocol.CMD_AUTH, key)
                if (p2.code != ZkProtocol.CMD_ACK_OK) {
                    closeQuietly()
                    throw ZkException(
                        "Comm Key буруу (хариу код ${p2.code}). Төхөөрөмж дээрх " +
                            "Menu → COMM. → Comm Key -тэй тааруулна уу"
                    )
                }
                logf("Comm Key баталгаажлаа")
            }

            else -> {
                closeQuietly()
                throw ZkException("холбогдох хүсэлтийг татгалзлаа, код=${p.code}")
            }
        }
    }

    override fun close() {
        try {
            if (socket?.isConnected == true) send(ZkProtocol.CMD_EXIT)
        } catch (_: Exception) {
            // Session хаахад алдаа гарсан ч хамаагүй
        }
        closeQuietly()
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        input = null
    }

    // ────────────────────────────────────────────────────────────────────
    //  Тээвэр
    // ────────────────────────────────────────────────────────────────────

    private data class Packet(
        val code: Int,
        val sessionId: Int,
        val replyId: Int,
        val payload: ByteArray,
        val raw: ByteArray,
    )

    private fun send(cmd: Int, payload: ByteArray = ByteArray(0)): Packet {
        val s = socket ?: throw ZkException("холболт нээгдээгүй байна")

        val pkt = ZkProtocol.buildPacket(cmd, sessionId, replyId, payload)
        s.getOutputStream().write(ZkProtocol.tcpWrap(pkt))
        s.getOutputStream().flush()

        val p = recv()
        // reply_id-г төхөөрөмжийн хариунаас авна
        replyId = p.replyId
        return p
    }

    private fun recv(): Packet {
        val inp = input ?: throw ZkException("холболт нээгдээгүй байна")

        val hdr = ByteArray(8)
        inp.readFully(hdr)

        if (ZkProtocol.le16(hdr, 0) != ZkProtocol.TCP_MAGIC_1 ||
            ZkProtocol.le16(hdr, 2) != ZkProtocol.TCP_MAGIC_2
        ) {
            throw ZkException("TCP толгой таарсангүй — $port порт дээр ZK төхөөрөмж биш байна")
        }

        val size = ZkProtocol.le32(hdr, 4)
        if (size < 8 || size > 64L * 1024 * 1024) {
            throw ZkException("пакетын хэмжээ боломжгүй: $size")
        }

        val raw = ByteArray(size.toInt())
        inp.readFully(raw)

        return Packet(
            code = ZkProtocol.le16(raw, 0),
            sessionId = ZkProtocol.le16(raw, 4),
            replyId = ZkProtocol.le16(raw, 6),
            payload = raw.copyOfRange(8, raw.size),
            raw = raw,
        )
    }

    // ────────────────────────────────────────────────────────────────────
    //  Өгөгдөл унших
    // ────────────────────────────────────────────────────────────────────

    private val maxChunk = 0xFFC0 // 65472

    /** Шинэ firmware-ийн буфер хэлбэрээр унших арга. */
    private fun readWithBuffer(cmd: Int, fct: Int = 0, ext: Int = 0): ByteArray {
        // pack('<bhii', 1, cmd, fct, ext) = 11 байт
        val req = ByteArray(11)
        req[0] = 1
        ZkProtocol.putLe16(req, 1, cmd)
        ZkProtocol.putLe32(req, 3, fct.toLong())
        ZkProtocol.putLe32(req, 7, ext.toLong())

        val p = send(ZkProtocol.CMD_DATA_WRRQ, req)

        return when (p.code) {
            ZkProtocol.CMD_DATA -> {
                logf("буфер: жижиг өгөгдөл шууд ирлээ (${p.payload.size} байт)")
                p.payload
            }

            ZkProtocol.CMD_PREPARE_DATA -> collectData(p)

            ZkProtocol.CMD_ACK_OK -> {
                if (p.payload.size < 5) throw ZkException("ACK_OK богино (${p.payload.size} байт)")
                val size = ZkProtocol.le32(p.payload, 1)
                logf("буферт $size байт бэлэн")
                if (size == 0L) {
                    freeData()
                    return ByteArray(0)
                }

                val out = java.io.ByteArrayOutputStream(size.toInt())
                var start = 0L
                while (start < size) {
                    val n = minOf(size - start, maxChunk.toLong())
                    out.write(readChunk(start, n))
                    start += n
                }
                freeData()
                out.toByteArray()
            }

            else -> throw ZkException("буфер горим дэмжигдээгүй (код ${p.code})")
        }
    }

    private fun readChunk(start: Long, size: Long): ByteArray {
        val req = ByteArray(8)
        ZkProtocol.putLe32(req, 0, start)
        ZkProtocol.putLe32(req, 4, size)
        return collectData(send(ZkProtocol.CMD_READ_BUFFER, req))
    }

    private fun freeData() {
        try {
            send(ZkProtocol.CMD_FREE_DATA)
        } catch (e: Exception) {
            logf("FREE_DATA: ${e.message}")
        }
    }

    /** PREPARE_DATA → DATA… → ACK_OK дарааллыг цуглуулна. */
    private fun collectData(first: Packet): ByteArray {
        when (first.code) {
            ZkProtocol.CMD_DATA -> return first.payload
            ZkProtocol.CMD_ACK_OK -> return ByteArray(0)

            ZkProtocol.CMD_PREPARE_DATA -> {
                if (first.payload.size < 4) throw ZkException("PREPARE_DATA-д хэмжээ алга")
                val want = ZkProtocol.le32(first.payload, 0)
                logf("PREPARE_DATA: $want байт хүлээж байна")

                val out = java.io.ByteArrayOutputStream(want.toInt())
                while (out.size() < want) {
                    val p = recv()
                    when (p.code) {
                        ZkProtocol.CMD_DATA -> out.write(p.payload)
                        ZkProtocol.CMD_ACK_OK -> return out.toByteArray()
                        else -> {
                            // Зарим firmware толгойгүй түүхий блок илгээдэг
                            logf("санаандгүй код ${p.code}, түүхийгээр нь авлаа")
                            out.write(p.raw)
                        }
                    }
                }
                // Эцсийн ACK_OK-ийг залгина
                try {
                    val tail = recv()
                    if (tail.code != ZkProtocol.CMD_ACK_OK) {
                        logf("төгсгөлд ACK_OK хүлээсэн, ${tail.code} ирлээ")
                    }
                } catch (_: Exception) {}
                return out.toByteArray()
            }

            else -> throw ZkException("өгөгдөл хүлээсэн, код ${first.code} ирлээ")
        }
    }

    private fun readDirect(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        collectData(send(cmd, payload))

    // ────────────────────────────────────────────────────────────────────
    //  Нийтийн API
    // ────────────────────────────────────────────────────────────────────

    /**
     * Бүх нэвтрэлтийн лог.
     *
     * ⚠️ ZK протоколд "сүүлийн уншсанаас хойшхийг" гэж хүсэх боломж
     * БАЙХГҮЙ — үргэлж бүгдийг буцаана. Шүүлтийг дуудагч тал хийнэ.
     */
    fun readAttLog(): List<AttRecord> {
        // Бичлэгийн хэмжээг таамаглахгүйн тулд эхлээд тоог нь асууна
        val recordCount = try {
            readSizes().records
        } catch (e: Exception) {
            logf("багтаамж уншиж чадсангүй (${e.message}), хэмжээг таамаглана")
            0
        }

        val data = try {
            readWithBuffer(ZkProtocol.CMD_ATTLOG_RRQ).takeIf { it.isNotEmpty() }
                ?: readDirect(ZkProtocol.CMD_ATTLOG_RRQ)
        } catch (e: Exception) {
            logf("буфер горим бүтсэнгүй (${e.message}), энгийн арга руу шилжиж байна")
            readDirect(ZkProtocol.CMD_ATTLOG_RRQ)
        }

        return AttLogParser.parse(data, zone, recordCount)
    }

    /** Задлахгүйгээр түүхий байтууд (оношилгоонд). */
    fun readAttLogRaw(): ByteArray =
        readWithBuffer(ZkProtocol.CMD_ATTLOG_RRQ).takeIf { it.isNotEmpty() }
            ?: readDirect(ZkProtocol.CMD_ATTLOG_RRQ)

    fun readUsers(): List<ZkUser> {
        val data = try {
            readWithBuffer(ZkProtocol.CMD_USERTEMP_RRQ, fct = 5).takeIf { it.isNotEmpty() }
                ?: readDirect(ZkProtocol.CMD_USERTEMP_RRQ, byteArrayOf(5, 0, 0, 0, 0))
        } catch (e: Exception) {
            logf("буфер горим бүтсэнгүй (${e.message})")
            readDirect(ZkProtocol.CMD_USERTEMP_RRQ, byteArrayOf(5, 0, 0, 0, 0))
        }
        return UserParser.parse(data)
    }

    fun readSizes(): ZkSizes {
        val p = send(ZkProtocol.CMD_GET_FREE_SIZES)
        if (p.payload.size < 80) {
            throw ZkException("GET_FREE_SIZES богино хариу (${p.payload.size} байт)")
        }
        fun f(i: Int) = ZkProtocol.le32(p.payload, i * 4).toInt()
        return ZkSizes(
            users = f(4),
            fingers = f(6),
            records = f(8),
            cards = f(12),
            fingersCap = f(14),
            usersCap = f(15),
            recordsCap = f(16),
        )
    }

    fun getTime(): ZonedDateTime {
        val p = send(ZkProtocol.CMD_GET_TIME)
        if (p.payload.size < 4) throw ZkException("GET_TIME богино хариу")
        return ZkProtocol.decodeTime(ZkProtocol.le32(p.payload, 0), zone)
    }

    fun setTime(dt: ZonedDateTime) {
        val b = ByteArray(4)
        ZkProtocol.putLe32(b, 0, ZkProtocol.encodeTime(dt.withZoneSameInstant(zone)))
        val p = send(ZkProtocol.CMD_SET_TIME, b)
        if (p.code != ZkProtocol.CMD_ACK_OK) throw ZkException("SET_TIME татгалзлаа, код=${p.code}")
    }

    /** Хаалгыг [seconds] секундээр онгойлгоно. */
    fun unlock(seconds: Int = 3) {
        val b = ByteArray(4)
        ZkProtocol.putLe32(b, 0, (seconds.coerceAtLeast(1) * 10).toLong()) // 0.1 сек нэгж
        val p = send(ZkProtocol.CMD_UNLOCK, b)
        if (p.code != ZkProtocol.CMD_ACK_OK) throw ZkException("UNLOCK татгалзлаа, код=${p.code}")
    }

    fun restart() {
        send(ZkProtocol.CMD_RESTART)
    }
}
