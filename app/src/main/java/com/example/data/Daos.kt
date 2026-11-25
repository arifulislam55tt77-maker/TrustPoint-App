package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedAccountDao {
    @Query("SELECT * FROM saved_accounts ORDER BY savedAt DESC")
    fun observeAll(): Flow<List<SavedAccountEntity>>

    @Query("SELECT * FROM saved_accounts ORDER BY savedAt DESC")
    suspend fun getAll(): List<SavedAccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: SavedAccountEntity)

    @Query("UPDATE saved_accounts SET isLive = :isLive WHERE uid = :uid")
    suspend fun updateLiveStatus(uid: String, isLive: Boolean)

    @Query("DELETE FROM saved_accounts WHERE uid = :uid")
    suspend fun deleteByUid(uid: String)

    @Query("DELETE FROM saved_accounts")
    suspend fun clearAll()
}

@Dao
interface MetaAccountDao {
    @Query("SELECT * FROM meta_accounts ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<MetaAccountEntity>>

    @Query("SELECT * FROM meta_accounts ORDER BY addedAt ASC")
    suspend fun getAll(): List<MetaAccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(accounts: List<MetaAccountEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(account: MetaAccountEntity): Long

    @Query("UPDATE meta_accounts SET status = :status WHERE phone = :phone")
    suspend fun updateStatus(phone: String, status: String)

    @Query("UPDATE meta_accounts SET status = 'fresh'")
    suspend fun resetAllToFresh()

    @Query("DELETE FROM meta_accounts WHERE phone = :phone")
    suspend fun deleteByPhone(phone: String)

    @Query("DELETE FROM meta_accounts")
    suspend fun clearAll()
}

@Dao
interface OtpHistoryDao {
    @Query("SELECT * FROM otp_history ORDER BY timestamp DESC LIMIT 100")
    fun observeRecent(): Flow<List<OtpHistoryEntity>>

    @Query("SELECT * FROM otp_history ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecent(): List<OtpHistoryEntity>

    @Query("SELECT COUNT(*) FROM otp_history WHERE number = :number AND otp = :otp AND timestamp > :sinceMs")
    suspend fun countRecentDuplicate(number: String, otp: String, sinceMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: OtpHistoryEntity)

    @Query("DELETE FROM otp_history")
    suspend fun clearAll()
}

@Dao
interface AutomationStateDao {
    @Query("SELECT * FROM automation_states")
    fun observeAll(): Flow<List<AutomationStateEntity>>

    @Query("SELECT * FROM automation_states WHERE automationKey = :key LIMIT 1")
    suspend fun getByKey(key: String): AutomationStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: AutomationStateEntity)
}

@Dao
interface ExecutionLogDao {
    @Query("SELECT * FROM execution_logs ORDER BY timestamp DESC LIMIT 200")
    fun observeRecent(): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs ORDER BY timestamp DESC LIMIT 200")
    suspend fun getRecent(): List<ExecutionLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ExecutionLogEntity)

    @Query("DELETE FROM execution_logs WHERE id NOT IN (SELECT id FROM execution_logs ORDER BY timestamp DESC LIMIT 300)")
    suspend fun pruneOld()

    @Query("DELETE FROM execution_logs")
    suspend fun clearAll()
}
