package com.spiritwisestudios.gpstracker.data.db.dao

import androidx.room.*
import com.spiritwisestudios.gpstracker.data.db.entity.PointOfInterestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PointOfInterestDao {
    
    @Query("SELECT * FROM points_of_interest")
    fun getAllPointsOfInterest(): Flow<List<PointOfInterestEntity>>
    
    @Query("SELECT * FROM points_of_interest WHERE is_visited = 1 ORDER BY visited_date DESC")
    fun getVisitedPlaces(): Flow<List<PointOfInterestEntity>>

    @Query("SELECT id, visited_date FROM points_of_interest WHERE is_visited = 1")
    suspend fun getVisitedPlaceRecords(): List<VisitedPlaceRecord>
    
    @Query("SELECT * FROM points_of_interest WHERE id = :id")
    suspend fun getPointOfInterestById(id: String): PointOfInterestEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPointOfInterest(point: PointOfInterestEntity): Long
    
    @Update
    suspend fun updatePointOfInterest(point: PointOfInterestEntity)
    
    @Delete
    suspend fun deletePointOfInterest(point: PointOfInterestEntity)
    
    @Query("DELETE FROM points_of_interest")
    suspend fun deleteAllPointsOfInterest()
}

/**
 * Projection for the visited-state overlay: which places were narrated
 * and when, so the revisit cooldown can be evaluated without loading
 * full entities.
 */
data class VisitedPlaceRecord(
    val id: String,
    @ColumnInfo(name = "visited_date")
    val visitedDate: Long?
)