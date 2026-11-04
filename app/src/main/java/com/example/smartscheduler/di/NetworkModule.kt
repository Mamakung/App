package com.example.smartscheduler.di

import com.example.smartscheduler.data.remote.AiAssistantApi
import com.example.smartscheduler.data.remote.NewsFeedApi
import com.example.smartscheduler.data.remote.StockQuoteApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

private const val STOCK_API_BASE_URL = "https://api.example-stockdata.com/"
private const val NEWS_API_BASE_URL = "https://api.example-newsfeed.com/"
private const val AI_BACKEND_BASE_URL = "https://api.example-backend.com/" // your proxy, holds LLM key server-side

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            // Attach Firebase ID token / API key headers here via an AuthInterceptor.
            .build()
    }

    @Provides
    @Singleton
    @Named("stockRetrofit")
    fun provideStockRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(STOCK_API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    @Named("newsRetrofit")
    fun provideNewsRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(NEWS_API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    @Named("aiRetrofit")
    fun provideAiRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(AI_BACKEND_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideStockQuoteApi(@Named("stockRetrofit") retrofit: Retrofit): StockQuoteApi =
        retrofit.create(StockQuoteApi::class.java)

    @Provides
    @Singleton
    fun provideNewsFeedApi(@Named("newsRetrofit") retrofit: Retrofit): NewsFeedApi =
        retrofit.create(NewsFeedApi::class.java)

    @Provides
    @Singleton
    fun provideAiAssistantApi(@Named("aiRetrofit") retrofit: Retrofit): AiAssistantApi =
        retrofit.create(AiAssistantApi::class.java)
}
