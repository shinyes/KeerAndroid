package site.lcyk.keer.data.module

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import site.lcyk.keer.data.local.FileStorage
import site.lcyk.keer.data.local.KeerDatabase
import site.lcyk.keer.data.local.dao.MemoDao
import site.lcyk.keer.data.repository.LocalDatabaseRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Singleton
    @Provides
    fun provideDatabase(@ApplicationContext context: Context): KeerDatabase {
        return KeerDatabase.getDatabase(context)
    }
    
    @Singleton
    @Provides
    fun provideMemoDao(database: KeerDatabase) = database.memoDao()

    @Singleton
    @Provides
    fun provideLocalDatabaseRepository(
        memoDao: MemoDao,
        fileStorage: FileStorage
    ) = LocalDatabaseRepository(memoDao, fileStorage)
} 
