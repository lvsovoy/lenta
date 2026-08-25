package com.example.lenta

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.lenta.data.nextcloud.NextcloudPreferences
import com.example.lenta.data.thumbnail.CbzThumbnailFetcher
import com.example.lenta.data.thumbnail.VideoThumbnailFetcher
import com.google.android.material.color.DynamicColors
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LentaApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Apply dynamic Material 3 colors to Activities (Settings, File Browser, etc.)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    override fun newImageLoader(): ImageLoader {
        val nextcloudPreferences = NextcloudPreferences(this)
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                if (nextcloudPreferences.isConfigured() && request.header("Authorization") == null) {
                    val rawServer = nextcloudPreferences.serverUrl.trim()
                    val normalizedServer = if (rawServer.isNotEmpty() && !rawServer.startsWith("http://") && !rawServer.startsWith("https://")) {
                        "https://$rawServer"
                    } else {
                        rawServer
                    }.trimEnd('/')

                    val reqUrl = request.url.toString()
                    val targetHost = try { android.net.Uri.parse(normalizedServer).host } catch (_: Throwable) { null }
                    val isNextcloudHost = targetHost != null && targetHost.equals(request.url.host, ignoreCase = true)
                    val isNextcloudPrefix = (rawServer.isNotEmpty() && reqUrl.startsWith(rawServer)) ||
                            (normalizedServer.isNotEmpty() && reqUrl.startsWith(normalizedServer))

                    if (isNextcloudHost || isNextcloudPrefix) {
                        val authHeader = Credentials.basic(nextcloudPreferences.username.trim(), nextcloudPreferences.password)
                        val authRequest = request.newBuilder()
                            .header("Authorization", authHeader)
                            .build()
                        return@addInterceptor chain.proceed(authRequest)
                    }
                }
                chain.proceed(request)
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(VideoThumbnailFetcher.Factory(this@LentaApp))
                add(CbzThumbnailFetcher.Factory(this@LentaApp))
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("thumbnail_cache/coil"))
                    .maxSizeBytes(200L * 1024 * 1024) // 200 MB
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }
}
