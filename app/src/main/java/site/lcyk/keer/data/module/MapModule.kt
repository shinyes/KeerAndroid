package site.lcyk.keer.data.module

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import site.lcyk.keer.util.Gcj02GeoCoordinateTransformer
import site.lcyk.keer.util.GeoCoordinateTransformer

@Module
@InstallIn(SingletonComponent::class)
object MapModule {

    @Provides
    @Singleton
    fun provideGeoCoordinateTransformer(): GeoCoordinateTransformer {
        return Gcj02GeoCoordinateTransformer()
    }
}
