package com.spiritwisestudios.gpstracker.di

import android.content.Context
import com.spiritwisestudios.gpstracker.data.service.AudioServiceImpl
import com.spiritwisestudios.gpstracker.domain.service.AudioService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {
    
    @Provides
    @Singleton
    fun provideAudioService(@ApplicationContext context: Context): AudioService {
        return AudioServiceImpl(context)
    }
} 