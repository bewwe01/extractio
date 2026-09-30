package app.saveit.verifier

import app.saveit.core.engine.EngineException
import app.saveit.core.engine.EngineProgress
import app.saveit.core.engine.YtDlpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.ConcurrentHashMap

/** [YtDlpEngine] backed by the `yt-dlp` executable on PATH (same arguments the Android engine receives). */
class CliYtDlpEngine(private val executable: String = "yt-dlp") : YtDlpEngine {
    private val running = ConcurrentHashMap<String, Process>()
    private val progressRe = Regex("""\[download]\s+([\d.]+)%""")

    override suspend fun run(args: List<String>, processId: String?, progress: EngineProgress?): String = runInterruptible(Dispatchers.IO) {
        val p = ProcessBuilder(listOf(executable) + args).start()
        processId?.let { running[it] = p }
        try {
            val err = StringBuilder()
            val errThread = Thread { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }.apply { start() }
            val out = StringBuilder()
            p.inputStream.bufferedReader().forEachLine { line ->
                out.appendLine(line)
                progressRe.find(line)?.let { progress?.invoke(it.groupValues[1].toFloat(), -1, line) }
            }
            errThread.join()
            val code = p.waitFor()
            if (code != 0) throw EngineException(err.toString().ifBlank { out.toString() })
            out.toString()
        } finally {
            processId?.let { running.remove(it) }
        }
    }

    override fun cancel(processId: String) {
        running.remove(processId)?.destroy()
    }

    override suspend fun version(): String? = runCatching { run(listOf("--version")).trim() }.getOrNull()
}
