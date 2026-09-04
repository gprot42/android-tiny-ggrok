package com.tinyggrok.app.di

import com.tinyggrok.app.data.api.ResilientDns
import com.tinyggrok.app.data.api.XaiApiService
import com.tinyggrok.app.data.api.XaiManagementApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * DNS-over-HTTPS resolvers used only when the system resolver fails.
     * Bootstrapped with literal IPs so they work even when port-53 DNS is blocked.
     */
    private fun buildFallbackDns(): List<Dns> {
        // Small, short-timeout client for the DoH queries themselves. Must not use the
        // resilient resolver (that would recurse); bootstrap hosts are literal IPs.
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()

        fun doh(url: String, vararg ips: String): Dns =
            DnsOverHttps.Builder()
                .client(bootstrap)
                .url(url.toHttpUrl())
                .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
                .build()

        return listOf(
            doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
            doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4")
        )
    }

    @Provides
    @Singleton
    fun provideDns(): Dns = ResilientDns(fallbacks = buildFallbackDns())

    @Provides
    @Singleton
    fun provideOkHttpClient(dns: Dns): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .dns(dns)
            // A healthy handshake to api.x.ai takes well under a second. When an address
            // is unreachable (dead IPv6 route, blackholed Wi-Fi) we want to move on to the
            // next address / retry quickly instead of hanging the user for a minute.
            .connectTimeout(12, TimeUnit.SECONDS)
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
