package com.local.attendance.data

import android.content.Context
import androidx.room.*
import com.local.attendance.core.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val key: String,
    val date: String,
    val shift: String,
    val scheduledAt: Long,
    val mode: String = ExecutionMode.LIVE.name,
    val state: String = TaskState.SCHEDULED.name,
    val startedAt: Long = 0,
    val deadlineAt: Long = 0,
    val submitReserved: Boolean = false,
    val reason: String = "",
    val acknowledgedAt: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val status get() = TaskState.valueOf(state)
    val expectedShift get() = Shift.valueOf(shift)
}
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskKey: String,
    val at: Long,
    val stage: String,
    val reason: String
)
@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(task: TaskEntity): Long
    @Query("SELECT * FROM tasks WHERE `key` = :key") suspend fun get(key: String): TaskEntity?
    @Query("UPDATE tasks SET state='SCHEDULED', reason='', updatedAt=:now WHERE `key`=:key AND state='CANCELLED' AND submitReserved=0 AND scheduledAt>:now")
    suspend fun replanFuture(key: String, now: Long): Int
    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC LIMIT 15") fun observeRecent(): Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE state = 'FAILED_RINGING' ORDER BY updatedAt") suspend fun ringing(): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE state IN ('SCHEDULED','STARTING','NAVIGATING','INSPECTING','WAITING_RANGE','SUBMITTING','VERIFYING')")
    suspend fun unfinished(): List<TaskEntity>
    @Query("UPDATE tasks SET state=:next, reason=:reason, updatedAt=:now WHERE `key`=:key AND state=:previous")
    suspend fun transition(key: String, previous: String, next: String, reason: String, now: Long): Int
    @Query("UPDATE tasks SET startedAt=:now, deadlineAt=:deadline WHERE `key`=:key AND state='STARTING'")
    suspend fun setDeadline(key: String, now: Long, deadline: Long)
    @Query("UPDATE tasks SET submitReserved=1, state='SUBMITTING', updatedAt=:now WHERE `key`=:key AND state='INSPECTING' AND submitReserved=0 AND mode='LIVE' AND deadlineAt>:now")
    suspend fun reserveSubmit(key: String, now: Long): Int
    @Query("UPDATE tasks SET acknowledgedAt=:now WHERE `key`=:key AND state='FAILED_ACKNOWLEDGED'")
    suspend fun acknowledge(key: String, now: Long)
    @Insert suspend fun event(event: EventEntity)
    @Query("SELECT COUNT(*) FROM events WHERE taskKey=:key AND stage=:stage")
    suspend fun stageCount(key: String, stage: String): Int
    @Query("DELETE FROM events WHERE at < :cutoff") suspend fun pruneEvents(cutoff: Long)
    @Query("DELETE FROM tasks WHERE updatedAt < :cutoff AND state IN ('SUCCEEDED','ALREADY_DONE','FAILED_ACKNOWLEDGED','CANCELLED')")
    suspend fun pruneTasks(cutoff: Long)
}
@Database(entities = [TaskEntity::class, EventEntity::class], version = 1, exportSchema = false)
abstract class AttendanceDatabase : RoomDatabase() { abstract fun tasks(): TaskDao }

class TaskRepository(context: Context) {
    private val db = Room.databaseBuilder(context, AttendanceDatabase::class.java, "attendance.db").build()
    val dao = db.tasks()
    suspend fun transition(key: String, to: TaskState, reason: String = ""): Boolean = db.withTransaction {
        val task = dao.get(key) ?: return@withTransaction false
        if (!TransitionRules.allows(task.status, to)) return@withTransaction false
        val now = System.currentTimeMillis()
        val changed = dao.transition(key, task.state, to.name, reason, now) == 1
        if (changed) {
            dao.event(EventEntity(taskKey = key, at = now, stage = to.name, reason = reason))
            if (to == TaskState.FAILED_ACKNOWLEDGED) dao.acknowledge(key, now)
        }
        changed
    }
    suspend fun reserveSubmit(key: String): Boolean = db.withTransaction {
        val now = System.currentTimeMillis()
        val changed = dao.reserveSubmit(key, now) == 1
        if (changed) dao.event(EventEntity(taskKey = key, at = now, stage = "SUBMITTING", reason = ""))
        changed
    }
    suspend fun prune() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        dao.pruneEvents(cutoff)
        dao.pruneTasks(cutoff)
    }
}
