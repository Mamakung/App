package com.example.smartscheduler.data.local.dao

import androidx.room.*
import com.example.smartscheduler.data.local.entity.NoteEntity
import com.example.smartscheduler.data.local.entity.NoteFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM note_folders WHERE ownerUid = :uid ORDER BY name COLLATE NOCASE ASC")
    fun observeFolders(uid: String): Flow<List<NoteFolderEntity>>

    @Query("SELECT * FROM notes WHERE ownerUid = :uid ORDER BY updatedAt DESC")
    fun observeNotes(uid: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE noteId = :id")
    suspend fun getNote(id: Long): NoteEntity?

    @Query("SELECT * FROM note_folders WHERE ownerUid = :uid")
    suspend fun allFolders(uid: String): List<NoteFolderEntity>

    @Query("SELECT * FROM notes WHERE ownerUid = :uid AND folderId IN (:folderIds)")
    suspend fun notesIn(uid: String, folderIds: List<Long>): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: NoteFolderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: NoteEntity): Long

    @Query("UPDATE notes SET folderId = :folderId, updatedAt = :now WHERE noteId = :noteId")
    suspend fun moveNote(noteId: Long, folderId: Long?, now: Long = System.currentTimeMillis())

    @Query("UPDATE note_folders SET parentId = :parentId WHERE folderId = :folderId")
    suspend fun moveFolder(folderId: Long, parentId: Long?)

    @Query("DELETE FROM notes WHERE noteId = :id")
    suspend fun deleteNote(id: Long)

    @Query("DELETE FROM notes WHERE noteId IN (:ids)")
    suspend fun deleteNotes(ids: List<Long>)

    @Query("DELETE FROM note_folders WHERE folderId IN (:ids)")
    suspend fun deleteFolders(ids: List<Long>)
}
