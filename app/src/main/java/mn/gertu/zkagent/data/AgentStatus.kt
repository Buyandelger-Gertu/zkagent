package mn.gertu.zkagent.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Агентын одоогийн төлөв — service бичиж, дэлгэц уншина.
 *
 * Процессын хэмжээнд ганц хувь. Апп хаагдаад дахин нээхэд түүх
 * алдагдана — тэр нь хэвийн, бодит лог backend дээр байна.
 */
object AgentStatus {

    private const val MAX_LINES = 60
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Notification дээр харуулах богино мөр. */
    private val _headline = MutableStateFlow("Зогссон")
    val headline: StateFlow<String> = _headline.asStateFlow()

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun setRunning(v: Boolean) {
        _running.value = v
        if (!v) _headline.value = "Зогссон"
    }

    fun headline(text: String) {
        _headline.value = text
    }

    fun log(text: String) {
        val stamped = "${LocalTime.now().format(TIME_FMT)}  $text"
        _lines.value = (_lines.value + stamped).takeLast(MAX_LINES)
    }

    /** Хоёуланг нэг дор — ихэнх тохиолдолд ингэж дуудна. */
    fun report(text: String) {
        headline(text)
        log(text)
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
