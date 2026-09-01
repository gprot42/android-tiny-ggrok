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
            .connectTimeout(60, TimeUnit.SECONDS)
            // Idle gap between SSE events / first byte. Grok + web_search can think
            // for several minutes between tokens; HTTP/2 PING keeps NAT mappings alive.
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(3, TimeUnit.MINUTES)
            // Whole request, including several web_search rounds. xAI's SDK default is 27 min.
            .callTimeout(30, TimeUnit.MINUTES)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
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
