package app.saveit.core.error

import app.saveit.core.model.Platform

enum class ErrorKind(val retryable: Boolean) {
    PRIVATE(false),
    DELETED(false),
    LOGIN_REQUIRED(false),
    AGE_RESTRICTED(false),
    REGION_BLOCKED(false),
    NETWORK(true),
    RATE_LIMITED(true),
    ENGINE_OUTDATED(false),
    UNSUPPORTED_URL(false),
    UNSUPPORTED_CONTENT(false),
    NO_MEDIA(false),
    SILENT_RESULT(true),
    STORAGE(false),
    CANCELLED(false),
    UNKNOWN(true);

    /** Higher = more informative. Used to pick which error to show when several extractors failed. */
    val specificity: Int
        get() = when (this) {
            PRIVATE, DELETED, LOGIN_REQUIRED, AGE_RESTRICTED, REGION_BLOCKED, UNSUPPORTED_CONTENT -> 5
            RATE_LIMITED, NETWORK -> 4
            UNSUPPORTED_URL -> 3
            ENGINE_OUTDATED -> 2
            NO_MEDIA -> 1
            else -> 0
        }
}

class SaveItException(
    val kind: ErrorKind,
    val platform: Platform? = null,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail ?: kind.name, cause) {
    /** Human readable text for the UI. */
    val userMessage: String get() = ErrorMessages.message(kind, platform, detail)
}

object ErrorMessages {
    fun title(kind: ErrorKind): String = when (kind) {
        ErrorKind.PRIVATE -> "This post is private"
        ErrorKind.DELETED -> "Post not found"
        ErrorKind.LOGIN_REQUIRED -> "Login required"
        ErrorKind.AGE_RESTRICTED -> "Age-restricted content"
        ErrorKind.REGION_BLOCKED -> "Not available in your region"
        ErrorKind.NETWORK -> "Network problem"
        ErrorKind.RATE_LIMITED -> "Too many requests"
        ErrorKind.ENGINE_OUTDATED -> "Extractor needs an update"
        ErrorKind.UNSUPPORTED_URL -> "Link not supported"
        ErrorKind.UNSUPPORTED_CONTENT -> "Content type not supported"
        ErrorKind.NO_MEDIA -> "No downloadable media"
        ErrorKind.SILENT_RESULT -> "Audio could not be merged"
        ErrorKind.STORAGE -> "Could not save the file"
        ErrorKind.CANCELLED -> "Cancelled"
        ErrorKind.UNKNOWN -> "Something went wrong"
    }

    fun message(kind: ErrorKind, platform: Platform?, detail: String? = null): String {
        val p = platform?.takeIf { it != Platform.DIRECT }?.displayName ?: "The platform"
        return when (kind) {
            ErrorKind.PRIVATE ->
                "$p says this post is private or only visible to approved followers. " +
                    "If you follow this account, log in to $p in Settings › Accounts and try again."
            ErrorKind.DELETED -> "This post no longer exists. It may have been deleted or the link is wrong."
            ErrorKind.LOGIN_REQUIRED -> when (platform) {
                Platform.SNAPCHAT -> "Snapchat only shares public Spotlight videos and public stories. " +
                    "Private snaps and chats cannot be downloaded. For restricted public content, log in to Snapchat in Settings › Accounts."
                null, Platform.DIRECT -> "This content requires you to be logged in."
                else -> "$p requires a logged-in session to show this post. Log in to $p in Settings › Accounts and try again."
            }
            ErrorKind.AGE_RESTRICTED -> "$p marks this post as age-restricted. Log in to $p in Settings › Accounts to access it."
            ErrorKind.REGION_BLOCKED -> "$p blocks this post in your country."
            ErrorKind.NETWORK -> "Couldn't reach $p. Check your internet connection and try again."
            ErrorKind.RATE_LIMITED -> "$p is temporarily limiting requests. Wait a few minutes, or log in to $p in Settings › Accounts."
            ErrorKind.ENGINE_OUTDATED ->
                "$p changed how its pages work and the extractor engine could not read this post. Update the engine and try again."
            ErrorKind.UNSUPPORTED_URL ->
                "SaveIt can't open this link. Supported: Reddit, X, Instagram, TikTok, Snapchat, Facebook and Pinterest post links."
            ErrorKind.UNSUPPORTED_CONTENT -> detail ?: "SaveIt can't download this type of content from $p."
            ErrorKind.NO_MEDIA -> "This post has no video or image to download (it may be text-only or a link to another site)."
            ErrorKind.SILENT_RESULT -> "The video's sound track could not be merged. Try a different quality."
            ErrorKind.STORAGE -> "The file could not be saved to your gallery. Check free storage space."
            ErrorKind.CANCELLED -> "Download cancelled."
            ErrorKind.UNKNOWN -> "Something went wrong while reading this post." + (detail?.let { " ($it)" }.orEmpty())
        }
    }
}

/** Maps engine (yt-dlp) error output and HTTP statuses to [ErrorKind]. */
object ErrorClassifier {
    private val rules: List<Pair<ErrorKind, Regex>> = listOf(
        ErrorKind.UNSUPPORTED_URL to Regex("Unsupported URL", RegexOption.IGNORE_CASE),
        ErrorKind.REGION_BLOCKED to Regex(
            "not available in your (country|region)|geo.?restrict|blocked in your country|available from your location",
            RegexOption.IGNORE_CASE,
        ),
        ErrorKind.AGE_RESTRICTED to Regex(
            "age.?restrict|confirm your age|inappropriate for some users|not be comfortable for some audiences|age.?gate|nsfw",
            RegexOption.IGNORE_CASE,
        ),
        ErrorKind.PRIVATE to Regex(
            "private (video|post|account|profile|subreddit)|this (video|post|account) is private|" +
                "registered users who follow|content is private|not authorized to view this protected|protected (tweet|post)",
            RegexOption.IGNORE_CASE,
        ),
        ErrorKind.LOGIN_REQUIRED to Regex(
            "login required|log ?in (is )?required|requires? (a )?log ?in|you need to log in|account authentication is required|" +
                "use --cookies|--cookies-from-browser|rate-limit reached or login required|locked behind the login page|" +
                "requiring login|quarantined|authentication is required|only available for registered users|" +
                "content is unreachable",
            RegexOption.IGNORE_CASE,
        ),
        ErrorKind.RATE_LIMITED to Regex(
            "HTTP Error 429|Too Many Requests|rate.?limit|IP address is (blocked|unable to access)|requiring captcha",
            RegexOption.IGNORE_CASE,
        ),
        ErrorKind.DELETED to Regex(
            "HTTP Error 404|has been (removed|deleted)|video (is )?unavailable|post (was )?deleted|does not exist|" +
                "no longer available|page (was )?not found|content isn.t available|TweetTombstone|status ?code:? ?10204|" +
                "This (tweet|post) (is unavailable|was deleted)",
            RegexOption.IGNORE_CASE,
        ),
        ErrorKind.NO_MEDIA to Regex(
            "There is no video in this post|No video could be found|No media found|No video formats found|" +
                "no (suitable )?formats|does not contain (a )?video|There's no video",
            RegexOption.IGNORE_CASE,
        ),
        ErrorKind.NETWORK to Regex(
            "Unable to download webpage.*(Errno|timed out|resolve)|Failed to resolve|Temporary failure in name resolution|" +
                "Name or service not known|No address associated|Network is unreachable|Connection (refused|reset)|" +
                "timed out|SSL: |CERTIFICATE_VERIFY_FAILED|UnknownHost|ConnectException|SocketTimeout|Unable to connect|" +
                "Tunnel connection failed|Connection aborted|RemoteDisconnected|Network is down",
            RegexOption.IGNORE_CASE,
        ),
        ErrorKind.ENGINE_OUTDATED to Regex(
            "Unable to extract|please report this issue|Confirm you are on the latest version|KeyError|IndexError|" +
                "TypeError|JSONDecodeError|Signature extraction failed|Failed to parse JSON|nsig extraction failed|" +
                "yt-dlp -U|update(d)? to the latest",
            RegexOption.IGNORE_CASE,
        ),
    )

    fun classify(message: String?): ErrorKind {
        if (message.isNullOrBlank()) return ErrorKind.UNKNOWN
        return rules.firstOrNull { (_, re) -> re.containsMatchIn(message) }?.first ?: ErrorKind.UNKNOWN
    }

    fun fromHttpStatus(code: Int): ErrorKind = when (code) {
        401 -> ErrorKind.LOGIN_REQUIRED
        403 -> ErrorKind.LOGIN_REQUIRED
        404, 410 -> ErrorKind.DELETED
        429 -> ErrorKind.RATE_LIMITED
        451 -> ErrorKind.REGION_BLOCKED
        in 500..599 -> ErrorKind.NETWORK
        else -> ErrorKind.UNKNOWN
    }

    /** Only the "ERROR:" lines of engine stderr, which is what users (and the classifier) care about. */
    fun condense(engineOutput: String): String {
        val errors = engineOutput.lineSequence().map { it.trim() }.filter { it.startsWith("ERROR") }.toList()
        val text = if (errors.isNotEmpty()) errors.joinToString("\n") else engineOutput.trim().lines().takeLast(3).joinToString("\n")
        return text.removePrefix("ERROR: ").take(600)
    }

    fun toException(t: Throwable, platform: Platform?): SaveItException = when (t) {
        is SaveItException -> t
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException,
        is javax.net.ssl.SSLException -> SaveItException(ErrorKind.NETWORK, platform, t.message, t)
        is kotlinx.coroutines.CancellationException -> SaveItException(ErrorKind.CANCELLED, platform, null, t)
        is java.io.IOException -> SaveItException(ErrorKind.NETWORK, platform, t.message, t)
        else -> SaveItException(classify(t.message), platform, t.message, t)
    }
}
