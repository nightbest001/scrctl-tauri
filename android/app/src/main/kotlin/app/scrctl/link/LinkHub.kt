package app.scrctl.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class Conn { Stopped, Connecting, Connected, Waiting, AuthFailed }

data class LogLine(val text: String, val err: Boolean, val ts: Long)

data class UiState(
    val conn: Conn = Conn.Stopped,
    val pc: String? = null,
    val message: String = "",
    val log: List<LogLine> = emptyList(),
)

/** Titik temu antara service, activity, dan NotificationListener (satu proses). */
object LinkHub {
    val ui = MutableStateFlow(UiState())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var client: LinkClient? = null

    fun setConn(c: Conn, pc: String?, msg: String?) {
        ui.update { it.copy(conn = c, pc = pc, message = msg ?: "") }
    }

    fun log(text: String, err: Boolean) {
        ui.update { it.copy(log = (listOf(LogLine(text, err, System.currentTimeMillis())) + it.log).take(30)) }
    }
}
