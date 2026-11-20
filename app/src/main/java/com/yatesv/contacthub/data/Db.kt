package com.yatesv.contacthub.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Per-conversation settings. [key] is the conversation key from [Phone.convKey]. */
@Entity
data class ConvMeta(
    @PrimaryKey val key: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val hidden: Boolean = false,
    val folderId: Long = 0,
    val customName: String = "",
    val photoUri: String = "",
    val wallpaperUri: String = "",
    val bubbleColor: Int = 0,
    val soundUri: String = "",
    val lastRead: Long = 0,
    /** "auto" = whatever app the contact is set up on, "google", "samsung" */
    val engine: String = "auto",
)

/** Messages that aren't in the phone's SMS database: RCS chats seen through Google/Samsung Messages. */
@Entity
data class LocalMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val convKey: String,
    val body: String,
    val date: Long,
    val outgoing: Boolean,
    val sender: String = "",
    /** "rcs" (seen in a notification), "sent" (sent through the Messages app), "failed" */
    val source: String = "rcs",
)

@Entity
data class Reaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val convKey: String,
    /** Id of the message reacted to, see [Msg.key] */
    val msgKey: String,
    /** Message text, used to find the message inside the Messages app when sending */
    val msgBody: String,
    val emoji: String,
    val fromMe: Boolean,
    /** Mine, waiting for RCS. Never sent as text. */
    val pending: Boolean = false,
    val date: Long = System.currentTimeMillis(),
)

@Entity
data class Starred(@PrimaryKey val msgKey: String, val convKey: String, val body: String, val date: Long)

@Entity
data class Scheduled(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val convKey: String,
    val addresses: String,
    val body: String,
    val sendAt: Long,
    /** "waiting", "sent", "failed" */
    val status: String = "waiting",
)

@Entity
data class Folder(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val sort: Int = 0)

/** Extra info about a contact, keyed by normalized phone number. */
@Entity
data class ContactExtra(
    @PrimaryKey val phoneKey: String,
    val instagram: String = "",
    val snapchat: String = "",
    val tiktok: String = "",
    val notes: String = "",
    /** "MM-dd" or "yyyy-MM-dd", empty = use the birthday saved in Contacts */
    val birthday: String = "",
    /** Manually linked Life360 member id, empty = match by phone number */
    val life360Id: String = "",
    val priority: Boolean = false,
    val groupColor: Int = 0,
)

/** Local name + photo for SMS group chats (never sent to the group). */
@Entity
data class GroupInfo(@PrimaryKey val convKey: String, val name: String = "", val photoUri: String = "")

@Dao
interface HubDao {
    @Query("SELECT * FROM ConvMeta") fun metaFlow(): Flow<List<ConvMeta>>
    @Query("SELECT * FROM ConvMeta") suspend fun allMeta(): List<ConvMeta>
    @Query("SELECT * FROM ConvMeta WHERE `key` = :key") suspend fun meta(key: String): ConvMeta?
    @Upsert suspend fun putMeta(m: ConvMeta)

    @Query("SELECT * FROM LocalMessage WHERE convKey = :key ORDER BY date") fun localFlow(key: String): Flow<List<LocalMessage>>
    @Query("SELECT * FROM LocalMessage WHERE convKey = :key ORDER BY date") suspend fun local(key: String): List<LocalMessage>
    @Query("SELECT * FROM LocalMessage ORDER BY date") suspend fun allLocal(): List<LocalMessage>
    @Query("SELECT * FROM LocalMessage ORDER BY date") fun allLocalFlow(): Flow<List<LocalMessage>>
    @Query("SELECT COUNT(*) FROM LocalMessage WHERE convKey = :key AND body = :body AND outgoing = :out AND date > :since")
    suspend fun countSimilar(key: String, body: String, out: Boolean, since: Long): Int
    @Insert suspend fun addLocal(m: LocalMessage): Long
    @Query("UPDATE LocalMessage SET source = :source WHERE id = :id") suspend fun setLocalSource(id: Long, source: String)
    @Query("DELETE FROM LocalMessage WHERE id = :id") suspend fun deleteLocal(id: Long)

    @Query("SELECT * FROM Reaction WHERE convKey = :key") fun reactionsFlow(key: String): Flow<List<Reaction>>
    @Query("SELECT * FROM Reaction WHERE pending = 1 ORDER BY date") suspend fun pendingReactions(): List<Reaction>
    @Query("SELECT * FROM Reaction") suspend fun allReactions(): List<Reaction>
    @Query("SELECT * FROM Reaction WHERE convKey = :key AND msgKey = :msgKey AND fromMe = :fromMe AND emoji = :emoji LIMIT 1")
    suspend fun findReaction(key: String, msgKey: String, fromMe: Boolean, emoji: String): Reaction?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addReaction(r: Reaction): Long
    @Query("UPDATE Reaction SET pending = 0 WHERE id = :id") suspend fun reactionSent(id: Long)
    @Query("SELECT * FROM Reaction WHERE id = :id") suspend fun reaction(id: Long): Reaction?
    @Query("DELETE FROM Reaction WHERE id = :id") suspend fun deleteReaction(id: Long)

    @Query("SELECT * FROM Starred ORDER BY date DESC") fun starredFlow(): Flow<List<Starred>>
    @Query("SELECT * FROM Starred") suspend fun allStarred(): List<Starred>
    @Upsert suspend fun star(s: Starred)
    @Query("DELETE FROM Starred WHERE msgKey = :msgKey") suspend fun unstar(msgKey: String)

    @Query("SELECT * FROM Scheduled WHERE status = 'waiting' ORDER BY sendAt") fun scheduledFlow(): Flow<List<Scheduled>>
    @Query("SELECT * FROM Scheduled WHERE status = 'waiting' AND sendAt <= :now") suspend fun dueScheduled(now: Long): List<Scheduled>
    @Query("SELECT * FROM Scheduled") suspend fun allScheduled(): List<Scheduled>
    @Insert suspend fun schedule(s: Scheduled): Long
    @Query("UPDATE Scheduled SET status = :status WHERE id = :id") suspend fun setScheduled(id: Long, status: String)
    /** Returns 1 if this worker got the message, 0 if another one already took it. */
    @Query("UPDATE Scheduled SET status = 'sending' WHERE id = :id AND status = 'waiting'") suspend fun claimScheduled(id: Long): Int
    /** Sends that got cut off (phone restarted mid-send) are marked failed rather than retried, to avoid doubles. */
    @Query("UPDATE Scheduled SET status = 'failed' WHERE status = 'sending' AND sendAt < :before") suspend fun failStuckScheduled(before: Long): Int
    @Query("DELETE FROM Scheduled WHERE id = :id") suspend fun deleteScheduled(id: Long)
    @Query("SELECT * FROM Scheduled WHERE status = 'sending'") suspend fun sendingScheduled(): List<Scheduled>

    @Query("SELECT * FROM Folder ORDER BY sort, id") fun foldersFlow(): Flow<List<Folder>>
    @Query("SELECT * FROM Folder") suspend fun allFolders(): List<Folder>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFolder(f: Folder): Long
    @Query("DELETE FROM Folder WHERE id = :id") suspend fun deleteFolder(id: Long)

    @Query("SELECT * FROM ContactExtra") fun extrasFlow(): Flow<List<ContactExtra>>
    @Query("SELECT * FROM ContactExtra") suspend fun allExtras(): List<ContactExtra>
    @Query("SELECT * FROM ContactExtra WHERE phoneKey = :key") suspend fun extra(key: String): ContactExtra?
    @Upsert suspend fun putExtra(e: ContactExtra)

    @Query("SELECT * FROM GroupInfo") fun groupsFlow(): Flow<List<GroupInfo>>
    @Query("SELECT * FROM GroupInfo") suspend fun allGroups(): List<GroupInfo>
    @Upsert suspend fun putGroup(g: GroupInfo)
}

@Database(
    entities = [ConvMeta::class, LocalMessage::class, Reaction::class, Starred::class, Scheduled::class,
        Folder::class, ContactExtra::class, GroupInfo::class],
    version = 1,
    exportSchema = false,
)
abstract class HubDb : RoomDatabase() {
    abstract fun dao(): HubDao

    companion object {
        fun create(ctx: Context): HubDb =
            Room.databaseBuilder(ctx, HubDb::class.java, "hub.db").build()
    }
}
