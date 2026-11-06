package com.example.smartscheduler.di

import android.content.Context
import androidx.room.Room
import com.example.smartscheduler.data.local.AppDatabase
import com.example.smartscheduler.data.local.dao.*
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
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            // ต่อไปถ้าเพิ่ม version ต้องเพิ่ม Migration ด้วย ไม่งั้นข้อมูลผู้ใช้จะหายตอนอัปเดต
            .fallbackToDestructiveMigrationFrom(1)
            .build()

    @Provides fun provideUserDao(db: AppDatabase): UserDao = db.userDao()
    @Provides fun provideRoutineDao(db: AppDatabase): RoutineDao = db.routineDao()
    @Provides fun provideScheduleDao(db: AppDatabase): ScheduleDao = db.scheduleDao()
    @Provides fun provideWatchlistDao(db: AppDatabase): WatchlistDao = db.watchlistDao()
    @Provides fun provideStockNoteDao(db: AppDatabase): StockNoteDao = db.stockNoteDao()
    @Provides fun provideMarketNewsDao(db: AppDatabase): MarketNewsDao = db.marketNewsDao()
    @Provides fun provideStockRecommendationDao(db: AppDatabase): StockRecommendationDao = db.stockRecommendationDao()
}
