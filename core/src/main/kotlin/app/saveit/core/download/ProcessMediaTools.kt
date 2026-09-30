package app.saveit.core.download

import app.saveit.core.error.ErrorKind
import app.saveit.core.error.SaveItException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs ffmpeg/ffprobe executables. The Android app points it at the binaries bundled with the engine
 * (plus their LD_LIBRARY_PATH); the verifier uses the system ones.
 */
class ProcessMediaTools(
    private val ffmpegPath: String,
    private val ffprobePath: String,
    private val environment: Map<String, String> = emptyMap(),
    private val timeoutMinutes: Long = 30,
) : MediaTools {

    override suspend fun ffmpeg(args: List<String>) {
        val (code, output) = run(listOf(ffmpegPath, "-hide_banner", "-loglevel", "error", "-nostdin") + args)
        if (code != 0) throw SaveItException(ErrorKind.UNKNOWN, null, "ffmpeg failed ($code): ${output.takeLast(400)}")
    }

    override suspend fun probe(file: File): ProbeResult? {
        val (code, output) = run(listOf(ffprobePath, "-v", "error", "-print_format", "json", "-show_streams", "-show_format", file.absolutePath))
        return if (code == 0) ProbeResult.fromFfprobeJson(output) else null
    }

    private suspend fun run(cmd: List<String>): Pair<Int, String> = runInterruptible(Dispatchers.IO) {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().putAll(environment)
        val p = pb.start()
        try {
            val out = StringBuilder()
            val reader = Thread { p.inputStream.bufferedReader().use { r -> r.lineSequence().forEach { out.appendLine(it) } } }
            reader.start()
            if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                p.destroyForcibly()
                throw SaveItException(ErrorKind.UNKNOWN, null, "${File(cmd.first()).name} timed out")
            }
            reader.join(5_000)
            p.exitValue() to out.toString()
        } catch (e: InterruptedException) {
            p.destroyForcibly()
            throw e
        }
    }
}
