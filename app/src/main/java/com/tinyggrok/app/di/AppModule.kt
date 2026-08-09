package com.tinyggrok.app.di

import com.tinyggrok.app.data.api.XaiApiService
import com.tinyggrok.app.data.api.XaiManagementApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideXaiApiService(okHttpClient: OkHttpClient): XaiApiService {
        return Retrofit.Builder()
            .baseUrl("https://api.x.ai/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(XaiApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideXaiManagementApiService(okHttpClient: OkHttpClient): XaiManagementApiService {
        return Retrofit.Builder()
            .baseUrl("https://management-api.x.ai/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(XaiManagementApiService::class.java)
    }
}
