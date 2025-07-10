package com.spiritwisestudios.gpstracker.data.db.dao

import androidx.room.*
import com.spiritwisestudios.gpstracker.data.db.entity.PointOfInterestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PointOfInterestDao {
    
    @Query("SELECT * FROM points_of_interest")
    fun getAllPointsOfInterest(): Flow<List<PointOfInterestEntity>>
    
    @Query("SELECT * FROM points_of_interest WHERE is_visited = 1")
    fun getVisitedPlaces(): Flow<List<PointOfInterestEntity>>
    
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