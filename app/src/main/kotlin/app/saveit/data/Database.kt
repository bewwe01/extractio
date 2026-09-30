package app.saveit.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One saved file. Re-downloading re-extracts [postUrl] (CDN links expire) and picks [itemIndex]. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postUrl: String,
    val platform: String,
    val title: String?,
    val author: String?,
    val itemIndex: Int,
    val itemId: String,
    val mediaType: String,
    val mimeType: String,
    val contentUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val durationSec: Double?,
    val quality: String,
    val createdAt: Long,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads")
    suspend fun clear()
}

@Database(entities = [DownloadEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "saveit.db").build()
    }
}
