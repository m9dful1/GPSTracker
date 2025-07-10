package com.spiritwisestudios.gpstracker.di

import android.content.Context
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.net.PlacesClient
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.data.api.PlacesApiService
import com.spiritwisestudios.gpstracker.data.db.AppDatabase
import com.spiritwisestudios.gpstracker.data.db.dao.PointOfInterestDao
import com.spiritwisestudios.gpstracker.data.repository.PlacesRepositoryImpl
import com.spiritwisestudios.gpstracker.data.repository.TourContentRepository
import com.spiritwisestudios.gpstracker.data.repository.UserPreferencesRepository
import com.spiritwisestudios.gpstracker.data.service.ContentServiceImpl
import com.spiritwisestudios.gpstracker.data.service.LocationAwarenessServiceImpl
import com.spiritwisestudios.gpstracker.data.service.NavigationServiceImpl
import com.spiritwisestudios.gpstracker.domain.repository.PlacesRepository
import com.spiritwisestudios.gpstracker.domain.service.ContentService
import com.spiritwisestudios.gpstracker.domain.service.LocationAwarenessService
import com.spiritwisestudios.gpstracker.domain.service.NavigationService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import timber.log.Timber

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePlacesClient(@ApplicationContext context: Context): PlacesClient {
        if (!Places.isInitialized()) {
            try {
                // Initialize Places with all the necessary features
                val apiKey = com.spiritwisestudios.gpstracker.util.ApiKeyManager.getInstance(context).getGoogleMapsApiKey()
                Places.initialize(context, apiKey)
                
                // Check which APIs are available
                val placesClient = Places.createClient(context)
                
                // Log more detailed initialization information
                Timber.i("Places API initialized with key: ${apiKey.take(5)}...")
                
                // Log isPlaceDetectionEnabled status for debugging
                val packageManager = context.packageManager
                val packageName = context.packageName
                
                Timber.i("Package name: $packageName")
                Timber.i("API key: $apiKey")
                
                return placesClient
            } catch (e: Exception) {
                // Log detailed initialization error
                Timber.e(e, "Failed to initialize Places API")
                throw e
            }
        }
        
        // Create and configure the Places client with more options
        val placesClient = Places.createClient(context)
        Timber.d("Places client created successfully")
        
        return placesClient
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    fun providePointOfInterestDao(database: AppDatabase): PointOfInterestDao {
        return database.pointOfInterestDao()
    }

    @Provides
    @Singleton
    fun providePlacesApiService(placesClient: PlacesClient): PlacesApiService {
        return PlacesApiService(placesClient)
    }

    @Provides
    @Singleton
    fun providePlacesRepository(
        placesClient: PlacesClient, 
        pointOfInterestDao: PointOfInterestDao
    ): PlacesRepository {
        return PlacesRepositoryImpl(placesClient, pointOfInterestDao)
    }
    
    @Provides
    @Singleton
    fun provideUserPreferencesRepository(
        @ApplicationContext context: Context
    ): UserPreferencesRepository {
        return UserPreferencesRepository(context)
    }
    
    @Provides
    @Singleton
    fun provideTourContentRepository(): TourContentRepository {
        return TourContentRepository()
    }
    
    @Provides
    @Singleton
    fun provideLocationAwarenessService(
        @ApplicationContext context: Context
    ): LocationAwarenessService {
        return LocationAwarenessServiceImpl(context)
    }
    
    @Provides
    @Singleton
    fun provideNavigationService(
        @ApplicationContext context: Context
    ): NavigationService {
        return NavigationServiceImpl(context)
    }
    
    @Provides
    @Singleton
    fun provideContentService(): ContentService {
        return ContentServiceImpl()
    }
} 