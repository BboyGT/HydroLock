package com.hydrolock.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.hydrolock.data.database.dao.*
import com.hydrolock.data.database.entities.*

@Database(
    entities = [
        UserProfile::class,
        DayRecord::class,
        DrinkWindow::class,
        DrinkSession::class,
        PhoneActivity::class,
        StreakEvent::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userProfileDao(): UserProfileDao
    abstract fun dayRecordDao(): DayRecordDao
    abstract fun drinkWindowDao(): DrinkWindowDao
    abstract fun drinkSessionDao(): DrinkSessionDao
    abstract fun phoneActivityDao(): PhoneActivityDao
    abstract fun streakEventDao(): StreakEventDao
}
