package com.spiritwisestudios.gpstracker.di

import android.content.Context
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.net.PlacesClient
import com.spiritwisestudios.gpstracker.BuildConfig
import com.spiritwisestudios.gpstracker.data.api.PlacesApiService
import com.spiritwisestudios.gpstracker.data.api.WikipediaApiService
import com.spiritwisestudios.gpstracker.data.db.AppDatabase
import com.spiritwisestudios.gpstracker.data.db.dao.PointOfInterestDao
import com.spiritwisestudios.gpstracker.data.db.dao.TourContentDao
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
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun providePlacesClient(@ApplicationContext context: Context): PlacesClient {
        if (!Places.isInitialized()) {
            Places.initialize(context, BuildConfig.MAPS_API_KEY)
            Timber.d("Places SDK initialized")
        }
        return Places.createClient(context)
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
    fun provideTourContentDao(database: AppDatabase): TourContentDao {
        return database.tourContentDao()
    }

    @Provides
    @Singleton
    fun providePlacesApiService(
        placesClient: PlacesClient,
        okHttpClient: OkHttpClient
    ): PlacesApiService {
        return PlacesApiService(placesClient, okHttpClient, BuildConfig.MAPS_API_KEY)
    }

    @Provides
    @Singleton
    fun provideWikipediaApiService(okHttpClient: OkHttpClient): WikipediaApiService {
        return WikipediaApiService(okHttpClient)
    }

    @Provides
    @Singleton
    fun providePlacesRepository(
        placesApiService: PlacesApiService,
        pointOfInterestDao: PointOfInterestDao
    ): PlacesRepository {
        return PlacesRepositoryImpl(placesApiService, pointOfInterestDao)
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
    fun provideContentService(
        wikipediaApiService: WikipediaApiService,
        tourContentDao: TourContentDao
    ): ContentService {
        return ContentServiceImpl(wikipediaApiService, tourContentDao)
    }

    @Provides
    @Singleton
    fun provideTourContentRepository(contentService: ContentService): TourContentRepository {
        return TourContentRepository(contentService)
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
}
