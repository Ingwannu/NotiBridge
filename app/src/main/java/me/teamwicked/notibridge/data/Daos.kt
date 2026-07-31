package me.teamwicked.notibridge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HookDao {
    @Query("SELECT * FROM hooks ORDER BY sortOrder ASC, updatedAt DESC")
    fun observeAll(): Flow<List<HookEntity>>

    @Query("SELECT * FROM hooks WHERE id = :id")
    suspend fun findById(id: String): HookEntity?

    @Query("SELECT * FROM hooks")
    suspend fun listAll(): List<HookEntity>

    @Query("SELECT * FROM hooks WHERE enabled = 1")
    suspend fun listEnabled(): List<HookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HookEntity)

    @Query("DELETE FROM hooks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE hooks SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM hooks")
    suspend fun nextSortOrder(): Int
}

@Dao
interface DeliveryTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DeliveryTaskEntity)

    @Update
    suspend fun update(entity: DeliveryTaskEntity)

    @Query("SELECT * FROM delivery_tasks WHERE id = :id")
    suspend fun findById(id: String): DeliveryTaskEntity?

    /** Claims up to [limit] due tasks; caller flips them to RUNNING transactionally. */
    @Query(
        "SELECT * FROM delivery_tasks " +
            "WHERE status IN ('PENDING', 'RETRY_WAIT') AND nextAttemptAt <= :now " +
            "ORDER BY nextAttemptAt ASC LIMIT :limit",
    )
    suspend fun dueTasks(now: Long, limit: Int): List<DeliveryTaskEntity>

    @Query("SELECT COUNT(*) FROM delivery_tasks WHERE status IN ('PENDING', 'RETRY_WAIT', 'RUNNING')")
    suspend fun activeCount(): Int

    /** Next scheduled retry time; used to re-arm the dispatcher. */
    @Query("SELECT MIN(nextAttemptAt) FROM delivery_tasks WHERE status = 'RETRY_WAIT'")
    suspend fun nextRetryAt(): Long?

    @Query(
        "SELECT COUNT(*) FROM delivery_tasks WHERE dedupeKey = :dedupeKey " +
            "AND hookId = :hookId AND createdAt >= :since",
    )
    suspend fun countRecentTaskDuplicates(hookId: String, dedupeKey: String, since: Long): Int

    @Query(
        "SELECT COUNT(*) FROM send_logs WHERE dedupeKeyMirror = :dedupeKey " +
            "AND hookId = :hookId AND createdAt >= :since",
    )
    suspend fun countRecentLogDuplicates(hookId: String, dedupeKey: String, since: Long): Int

    @Query("DELETE FROM delivery_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM delivery_tasks WHERE hookId = :hookId")
    suspend fun deleteByHookId(hookId: String)

    @Query("DELETE FROM delivery_tasks WHERE status IN ('SUCCESS', 'FAILED')")
    suspend fun deleteTerminal()
}

@Dao
interface SendLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SendLogEntity)

    @Query("SELECT * FROM send_logs ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SendLogEntity>>

    @Query("DELETE FROM send_logs")
    suspend fun clear()

    /** Keeps only the newest [keep] rows; called after every insert. */
    @Query(
        "DELETE FROM send_logs WHERE id NOT IN " +
            "(SELECT id FROM send_logs ORDER BY createdAt DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)
}

@Dao
interface GlobalVariableDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: GlobalVariableEntity)

    @Query("SELECT * FROM global_variables")
    suspend fun listAll(): List<GlobalVariableEntity>

    @Query("SELECT * FROM global_variables")
    fun observeAll(): Flow<List<GlobalVariableEntity>>

    @Query("DELETE FROM global_variables WHERE name = :name")
    suspend fun delete(name: String)
}
