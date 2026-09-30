package app.saveit.verifier

import app.saveit.core.MediaResolver
import app.saveit.core.download.MediaDownloader
import app.saveit.core.download.ProcessMediaTools
import app.saveit.core.download.ProbeResult
import app.saveit.core.error.SaveItException
import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.Quality
import app.saveit.core.net.Http
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Real-URL verification: runs every matrix row through the same resolver + downloader the app uses,
 * then inspects each output with ffprobe and prints a platform / type / result table.
 *
 *   ./gradlew -p tools/jvm-build :verifier:run --args="verifier/test-matrix.tsv"
 *   ./gradlew -p tools/jvm-build :verifier:run --args="--url https://www.reddit.com/r/.../comments/..."
 *
 * Options: --out DIR (default verification-output), --quality best|1080|720|480|m4a|mp3,
 *          --cookies FILE (Netscape cookie file for login-only rows), --no-engine (fallback extractors only).
 */
fun main(argv: Array<String>) = runBlocking {
    val args = argv.toMutableList()
    fun opt(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { i -> args.removeAt(i); args.removeAt(i) }
    fun flag(name: String): Boolean = args.remove(name)

    val outDir = File(opt("--out") ?: "verification-output")
    val quality = when (opt("--quality")) {
        "1080" -> Quality.P1080; "720" -> Quality.P720; "480" -> Quality.P480
        "m4a" -> Quality.AUDIO_M4A; "mp3" -> Quality.AUDIO_MP3; else -> Quality.BEST
    }
    val cookieFile = opt("--cookies")?.let(::File)
    val singleUrl = opt("--url")
    val noEngine = flag("--no-engine")

    val rows = if (singleUrl != null) listOf(Row("?", "?", null, singleUrl, "")) else {
        val matrix = File(args.firstOrNull() ?: "verifier/test-matrix.tsv")
        if (!matrix.isFile) {
            System.err.println("Matrix file not found: ${matrix.absolutePath}")
            exitProcess(2)
        }
        parseMatrix(matrix)
    }

    val engine = if (noEngine) null else CliYtDlpEngine().takeIf { it.version() != null }
    println("SaveIt verifier - yt-dlp ${engine?.version() ?: "not used"}, quality ${quality.label}\n")
    val http = Http.create()
    val resolver = MediaResolver(http, engine, cookieFileFor = { cookieFile })
    val downloader = MediaDownloader(http, engine, ProcessMediaTools("ffmpeg", "ffprobe"), cookieFileFor = { cookieFile })

    val results = ArrayList<Result>()
    for ((n, row) in rows.withIndex()) {
        if (row.url == "ASK") {
            results += Result(row, "SKIPPED", "no URL yet - add a real public post to the matrix")
            continue
        }
        print("[${n + 1}/${rows.size}] ${row.platform} ${row.type} ${row.url} ... ")
        val r = verifyRow(row, resolver, downloader, File(outDir, "%02d-%s-%s".format(n + 1, row.platform, row.type)), quality)
        println(r.result)
        results += r
    }

    val table = buildString {
        appendLine("| Platform | Type | Result | Notes |")
        appendLine("|---|---|---|---|")
        results.forEach { appendLine("| ${it.row.platform} | ${it.row.type} | ${it.result} | ${it.notes.replace("|", "\\|")} |") }
    }
    println("\n$table")
    outDir.mkdirs()
    File(outDir, "report.md").writeText("# SaveIt verification report\n\n$table")
    println("Report written to ${File(outDir, "report.md").absolutePath}")
    if (results.any { it.result == "FAIL" }) exitProcess(1)
}

data class Row(val platform: String, val type: String, val expected: Int?, val url: String, val note: String)
data class Result(val row: Row, val result: String, val notes: String)

fun parseMatrix(f: File): List<Row> = f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
    val c = line.split('\t')
    Row(c[0], c.getOrElse(1) { "?" }, c.getOrElse(2) { "-" }.toIntOrNull(), c.getOrElse(3) { "ASK" }, c.getOrElse(4) { "" })
}

private suspend fun verifyRow(row: Row, resolver: MediaResolver, downloader: MediaDownloader, dir: File, quality: Quality): Result {
    val post = try {
        resolver.resolve(row.url)
    } catch (e: Exception) {
        val err = e as? SaveItException ?: app.saveit.core.error.ErrorClassifier.toException(e, null)
        return fail(row, err, resolver)
    }
    return downloadAll(row, post, downloader, dir, quality)
}

private fun fail(row: Row, e: SaveItException, resolver: MediaResolver): Result {
    val trail = resolver.lastAttempts.joinToString("; ") { "${it.extractor}: ${it.detail}" }
    val verdict = if (e.kind.name in setOf("LOGIN_REQUIRED", "PRIVATE", "AGE_RESTRICTED")) "NEEDS LOGIN" else "FAIL"
    return Result(row, verdict, "${e.kind}: ${e.userMessage} [$trail]")
}

private suspend fun downloadAll(row: Row, post: app.saveit.core.model.PostInfo, downloader: MediaDownloader, dir: File, quality: Quality): Result {
    val items = post.items.filter { it.selectedByDefault }
    val problems = ArrayList<String>()
    val notes = ArrayList<String>()
    notes += "via ${post.source}, ${items.size} item(s)"
    if (row.expected != null && items.size != row.expected) problems += "expected ${row.expected} item(s), got ${items.size}"

    items.forEachIndexed { i, item ->
        try {
            val out = downloader.download(post, item, quality, dir, "verify-$i")
            val target = File(dir, "${i + 1}.${out.ext}")
            out.file.renameTo(target)
            val check = inspect(item, out.ext, target.length(), out.probe, quality)
            if (check != null) problems += "#${i + 1} $check" else notes += "#${i + 1} ${describe(out.ext, target.length(), out.probe)} (${out.via})"
        } catch (e: Exception) {
            val err = e as? SaveItException ?: app.saveit.core.error.ErrorClassifier.toException(e, post.platform)
            problems += "#${i + 1} download failed: ${err.kind} ${err.detail ?: ""}".trim()
        }
    }
    return if (problems.isEmpty()) Result(row, "PASS", notes.joinToString("; "))
    else Result(row, "FAIL", (problems + notes).joinToString("; "))
}

private fun describe(ext: String, size: Long, p: ProbeResult?): String {
    if (p == null) return "$ext ${size / 1024} KiB"
    val dims = if (p.width != null) "${p.width}x${p.height}" else ""
    val streams = listOfNotNull(p.videoCodec?.let { "v:$it" }, p.audioCodec?.let { "a:$it" }).joinToString("+")
    val dur = p.durationSec?.let { "%.1fs".format(it) }.orEmpty()
    return listOf(ext, dims, streams, dur, "${size / 1024} KiB").filter { it.isNotEmpty() }.joinToString(" ")
}

/** Returns a problem description, or null if the file looks right. */
private fun inspect(item: MediaItem, ext: String, size: Long, p: ProbeResult?, quality: Quality): String? {
    if (size <= 0) return "empty file"
    if (p == null) return "ffprobe could not read the file ($ext)"
    if (quality.isAudioOnly || item.type == MediaType.AUDIO) {
        return when {
            !p.hasAudio -> "no audio stream"
            (p.durationSec ?: 0.0) < 1 -> "implausible duration ${p.durationSec}"
            else -> null
        }
    }
    return when (item.type) {
        MediaType.VIDEO -> when {
            ext != "mp4" -> "unexpected extension .$ext"
            !p.hasVideo -> "no video stream"
            !p.hasAudio && item.hasAudio != false -> "no audio stream (source ${if (item.hasAudio == true) "has" else "may have"} audio)"
            (p.durationSec ?: 0.0) < 0.5 -> "implausible duration ${p.durationSec}"
            (p.height ?: 0) < 100 -> "implausible resolution ${p.width}x${p.height}"
            else -> null
        }
        MediaType.GIF -> when {
            ext !in setOf("gif", "mp4") -> "unexpected extension .$ext"
            (p.width ?: 0) < 50 -> "implausible size ${p.width}x${p.height}"
            else -> null
        }
        MediaType.IMAGE -> when {
            ext !in setOf("jpg", "png", "webp", "gif", "heic", "avif") -> "unexpected extension .$ext"
            (p.width ?: 0) < 200 || (p.height ?: 0) < 200 -> "implausibly small image ${p.width}x${p.height}"
            else -> null
        }
        MediaType.AUDIO -> null
    }
}
