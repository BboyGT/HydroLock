package com.hydrolock.di

import android.content.Context
import androidx.room.Room
import com.hydrolock.data.database.AppDatabase
import com.hydrolock.data.database.dao.*
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
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "hydrolock_db"
        ).build()
    }

    @Provides fun provideUserProfileDao(db: AppDatabase): UserProfileDao = db.userProfileDao()
    @Provides fun provideDayRecordDao(db: AppDatabase): DayRecordDao = db.dayRecordDao()
    @Provides fun provideDrinkWindowDao(db: AppDatabase): DrinkWindowDao = db.drinkWindowDao()
    @Provides fun provideDrinkSessionDao(db: AppDatabase): DrinkSessionDao = db.drinkSessionDao()
    @Provides fun providePhoneActivityDao(db: AppDatabase): PhoneActivityDao = db.phoneActivityDao()
    @Provides fun provideStreakEventDao(db: AppDatabase): StreakEventDao = db.streakEventDao()
}
