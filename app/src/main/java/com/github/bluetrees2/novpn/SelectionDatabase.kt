package com.github.bluetrees2.novpn

import android.content.Context
import androidx.room.*

@Entity(tableName = "selected_apps")
data class SelectedApp(
    @PrimaryKey val name: String
)

@Dao
interface SelectedAppsDao {
    @Query("SELECT * FROM selected_apps")
    suspend fun getAll(): List<SelectedApp>

    @Query("SELECT * FROM selected_apps WHERE name LIKE :name LIMIT 1")
    suspend fun findByName(name: String): SelectedApp

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: SelectedApp)

    suspend fun insert(name: String) = insert(SelectedApp(name))

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vararg apps: SelectedApp)

    suspend fun insertAll(names: List<String>) = insertAll(*names.map { SelectedApp(it) }.toTypedArray())

    @Delete
    suspend fun delete(app: SelectedApp)

    suspend fun delete(name: String) = delete(SelectedApp(name))

    @Delete
    suspend fun deleteAll(vararg apps: SelectedApp)

    suspend fun deleteAll(names: List<String>) = deleteAll(*names.map { SelectedApp(it) }.toTypedArray())
}

@Database(entities = [SelectedApp::class], version = 1)
abstract class SelectedAppsDatabase : RoomDatabase() {
    abstract fun dao(): SelectedAppsDao

    companion object {
        fun newInstance(context: Context): SelectedAppsDatabase {
            return Room.databaseBuilder(
                context, SelectedAppsDatabase::class.java, "selected_apps"
            ).build()
        }
    }
}