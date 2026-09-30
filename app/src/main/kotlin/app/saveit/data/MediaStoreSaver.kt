package app.saveit.data

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.saveit.core.error.ErrorKind
import app.saveit.core.error.SaveItException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Publishes finished files to the shared gallery: Movies/SaveIt, Pictures/SaveIt, Music/SaveIt.
 * Android 10+ uses MediaStore (no permission); Android 8–9 writes the public folder and scans it.
 */
class MediaStoreSaver(private val context: Context) {

    data class Saved(val uri: Uri, val displayName: String, val sizeBytes: Long)

    sealed interface DeleteResult {
        data object Deleted : DeleteResult
        data object AlreadyGone : DeleteResult
        /** The user must confirm (file created by a previous install of the app). */
        data class NeedsConfirmation(val intentSender: IntentSender) : DeleteResult
    }

    private enum class Kind { VIDEO, IMAGE, AUDIO }

    private fun kindOf(mime: String) = when {
        mime.startsWith("video/") -> Kind.VIDEO
        mime.startsWith("audio/") -> Kind.AUDIO
        else -> Kind.IMAGE
    }

    suspend fun save(file: File, baseName: String, ext: String, mime: String): Saved = withContext(Dispatchers.IO) {
        val kind = kindOf(mime)
        val name = "$baseName.$ext"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveScoped(file, name, mime, kind) else saveLegacy(file, baseName, ext, mime, kind)
        } catch (e: SaveItException) {
            throw e
        } catch (e: Exception) {
            throw SaveItException(ErrorKind.STORAGE, null, e.message, e)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(file: File, name: String, mime: String, kind: Kind): Saved {
        val resolver = context.contentResolver
        val (collection, dir) = when (kind) {
            Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_MOVIES}/SaveIt"
            Kind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_PICTURES}/SaveIt"
            Kind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_MUSIC}/SaveIt"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw SaveItException(ErrorKind.STORAGE, null, "MediaStore refused the file")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
                ?: throw SaveItException(ErrorKind.STORAGE, null, "Could not open gallery file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        val finalName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: name
        return Saved(uri, finalName, file.length())
    }

    @Suppress("DEPRECATION")
    private suspend fun saveLegacy(file: File, baseName: String, ext: String, mime: String, kind: Kind): Saved {
        val type = when (kind) {
            Kind.VIDEO -> Environment.DIRECTORY_MOVIES
            Kind.IMAGE -> Environment.DIRECTORY_PICTURES
            Kind.AUDIO -> Environment.DIRECTORY_MUSIC
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(type), "SaveIt").apply { mkdirs() }
        var target = File(dir, "$baseName.$ext")
        var n = 1
        while (target.exists()) target = File(dir, "$baseName (${n++}).$ext")
        file.copyTo(target)
        val uri = suspendCancellableCoroutine<Uri?> { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime)) { _, u -> if (cont.isActive) cont.resume(u) }
        } ?: throw SaveItException(ErrorKind.STORAGE, null, "Media scanner did not index the file")
        return Saved(uri, target.name, target.length())
    }

    suspend fun delete(uri: Uri): DeleteResult = withContext(Dispatchers.IO) {
        try {
            if (context.contentResolver.delete(uri, null, null) > 0) DeleteResult.Deleted else DeleteResult.AlreadyGone
        } catch (e: SecurityException) {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    DeleteResult.NeedsConfirmation(MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender)
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                    DeleteResult.NeedsConfirmation(e.userAction.actionIntent.intentSender)
                else -> throw e
            }
        } catch (e: IllegalArgumentException) {
            DeleteResult.AlreadyGone
        }
    }

    fun exists(uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)
}
