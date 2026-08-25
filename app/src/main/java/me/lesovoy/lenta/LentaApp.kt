package me.lesovoy.lenta

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.android.material.color.DynamicColors
import me.lesovoy.lenta.data.nextcloud.NextcloudPreferences
import me.lesovoy.lenta.data.source.SourceClientFactory
import me.lesovoy.lenta.data.source.StorageSourceManager
import me.lesovoy.lenta.data.source.StorageSourceType
import me.lesovoy.lenta.data.thumbnail.CbzThumbnailFetcher
import me.lesovoy.lenta.data.thumbnail.VideoThumbnailFetcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LentaApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Apply dynamic Material 3 colors to Activities (Settings, File Browser, etc.)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    override fun newImageLoader(): ImageLoader {
        val sourceManager = StorageSourceManager(this)
        val nextcloudPreferences = NextcloudPreferences(this)
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.header("Authorization") == null) {
                    val sources = sourceManager.getAllSources()
                    for (source in sources) {
                        if (!source.isConfigured()) continue
                        val client = SourceClientFactory.getClient(this@LentaApp, source)
                        val authHeaders = client.getAuthHeaders()
                        if (authHeaders.isEmpty()) continue

                        val rawServer = source.serverUrl.trim()
                        val normalizedServer = if (rawServer.isNotEmpty() && !rawServer.startsWith("http://") && !rawServer.startsWith("https://")) {
                            "https://$rawServer"
                        } else {
                            rawServer
                        }.trimEnd('/')

                        val reqUrl = request.url.toString()
                        val targetHost = try { android.net.Uri.parse(normalizedServer).host } catch (_: Throwable) { null }
                        val isHost = targetHost != null && targetHost.equals(request.url.host, ignoreCase = true)
                        val isPrefix = (rawServer.isNotEmpty() && reqUrl.startsWith(rawServer)) ||
                                (normalizedServer.isNotEmpty() && reqUrl.startsWith(normalizedServer))

                        val isDrive = (source.type == StorageSourceType.GOOGLE_DRIVE && request.url.host.contains("googleapis.com")) ||
                                (source.type == StorageSourceType.ONEDRIVE && request.url.host.contains("graph.microsoft.com"))

                        if (isHost || isPrefix || isDrive) {
                            val authRequest = request.newBuilder()
                            for ((k, v) in authHeaders) {
                                authRequest.header(k, v)
                            }
                            return@addInterceptor chain.proceed(authRequest.build())
                        }
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
