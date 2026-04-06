package com.techyshishy.beadmanager.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bead_manager_prefs"
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @AppDataStore
    fun provideDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.appDataStore
}
