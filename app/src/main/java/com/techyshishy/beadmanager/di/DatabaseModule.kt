package com.techyshishy.beadmanager.di

import android.content.Context
import androidx.room.Room
import com.techyshishy.beadmanager.data.db.BeadDao
import com.techyshishy.beadmanager.data.db.BeadDatabase
import com.techyshishy.beadmanager.data.db.VendorLinkDao
import com.techyshishy.beadmanager.data.db.VendorPackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideBeadDatabase(
        @ApplicationContext context: Context,
    ): BeadDatabase = Room.databaseBuilder(
        context,
        BeadDatabase::class.java,
        "bead_catalog.db",
    )
        .addMigrations(BeadDatabase.MIGRATION_1_2, BeadDatabase.MIGRATION_2_3, BeadDatabase.MIGRATION_3_4, BeadDatabase.MIGRATION_4_5)
        .build()

    @Provides
    fun provideBeadDao(db: BeadDatabase): BeadDao = db.beadDao()

    @Provides
    fun provideVendorLinkDao(db: BeadDatabase): VendorLinkDao = db.vendorLinkDao()

    @Provides
    fun provideVendorPackDao(db: BeadDatabase): VendorPackDao = db.vendorPackDao()
}
