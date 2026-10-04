package com.musicfind.app.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

object ApiClient {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    @Volatile
    var serverUrl: String = ""

    @Volatile
    var token: String = ""

    private val hostInterceptor = Interceptor { chain ->
        val original = chain.request()
        val server = serverUrl
        if (server.isBlank()) {
            chain.proceed(original)
        } else {
            runCatching {
                val base = server.toHttpUrl()
                val newUrl = original.url.newBuilder()
                    .scheme(base.scheme)
                    .host(base.host)
                    .port(base.port)
                    .build()
                chain.proceed(original.newBuilder().url(newUrl).build())
            }.getOrElse { chain.proceed(original) }
        }
    }

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val current = token
        if (current.isBlank()) {
            chain.proceed(original)
        } else {
            chain.proceed(
                original.newBuilder()
                    .header("X-Session-Token", current)
                    .build()
            )
        }
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(hostInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val service: ApiService = retrofit.create(ApiService::class.java)

    fun absoluteUrl(path: String): String {
        if (path.isBlank()) return ""
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        if (path.startsWith("file://") || path.startsWith("content://")) return path
        val server = serverUrl.trimEnd('/')
        return if (path.startsWith("/")) server + path else "$server/$path"
    }
}
