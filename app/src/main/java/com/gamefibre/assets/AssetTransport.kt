package com.gamefibre.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.gamefibre.NetworkAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Shared public-image transport. No Activity, view, fallback policy or disposable selected-art cache. */
internal class AssetTransport internal constructor(private val context: Context, internal val store: AssetStore) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val hosts = ConcurrentHashMap<String, Semaphore>()
    private val backgroundHosts = ConcurrentHashMap<String, Semaphore>()
    private val cooldowns = ConcurrentHashMap<String, Long>()
    internal var networkAvailable: () -> Boolean = { NetworkAvailability.canRequest(context) }
    val requests = AtomicLong()
    val bytesReceived = AtomicLong()
    companion object {
        fun get(context: Context): AssetTransport = com.gamefibre.App.of(context).assetTransport
    }
    private fun normalized(address: String, portrait: Boolean, large: Boolean, fullscreen: Boolean): URI {
        var uri = URI(if(address.startsWith("//")) "https:$address" else address)
        if (!large && !fullscreen && uri.host.orEmpty().matches(Regex("images(?:-[0-9]+)?\\.gog(?:-statics)?\\.com",RegexOption.IGNORE_CASE))) {
            val path = if(uri.path.orEmpty().matches(Regex("/[a-fA-F0-9]{32,64}(?:_[^/]+|\\.[a-zA-Z]+)?"))) uri.path.substringBefore('_').substringBefore('.')+"_196.jpg" else uri.path
            uri=URI("https",null,uri.host,-1,path,uri.query,null)
        }
        if(uri.host.orEmpty().let { it.endsWith(".epicgames.com") || it.endsWith(".unrealengine.com") }) {
            val source=Uri.parse(uri.toString());val builder=source.buildUpon().clearQuery()
            source.queryParameterNames.filterNot { it in setOf("w","h","resize","quality") }.forEach { key -> source.getQueryParameters(key).forEach { builder.appendQueryParameter(key,it) } }
            uri=URI(builder.appendQueryParameter("w",if(fullscreen) "1280" else if(large) "640" else "320")
                .appendQueryParameter("h",if(fullscreen) "1920" else if(portrait) "480" else "180").appendQueryParameter("resize","1").appendQueryParameter("quality","medium").build().toString())
        }
        return uri
    }
    suspend fun image(address: String, portrait: Boolean=false, large: Boolean=false, fullscreen: Boolean=false, revalidate: Boolean=false, cachedOnly: Boolean=false, priority:Int=0, requireFresh:Boolean=false): File? {
        if(address.isBlank()) return null
        val uri=runCatching { normalized(address,portrait,large,fullscreen) }.getOrNull() ?: return null
        val key=assetDigest("$uri|$portrait|$large|$fullscreen".toByteArray())
        return locks.getOrPut(key){Mutex()}.withLock {
            val cached=withContext(Dispatchers.IO){ store.source(key) }
            val immutable=uri.host.orEmpty().endsWith(".steamstatic.com") && Regex("/[a-fA-F0-9]{32,64}/").containsMatchIn(uri.path.orEmpty())
            if(cached!=null && (!revalidate || immutable || cachedOnly || System.currentTimeMillis()-cached.checked<6L*60*60*1000)) return@withLock store.blob(cached.blob)
            if(cachedOnly || !networkAvailable()) return@withLock cached?.let { store.blob(it.blob) }
            val host=uri.host ?: return@withLock null
            if((cooldowns[host] ?: 0)>System.currentTimeMillis()) {
                check(!requireFresh){"Image provider is cooling down"}
                return@withLock cached?.let { store.blob(it.blob) }
            }
            val background=if(priority>0)backgroundHosts.getOrPut(host){Semaphore(3)}else null
            background?.acquire()
            try {
                hosts.getOrPut(host){Semaphore(4)}.withPermit {
                    withContext(Dispatchers.IO) {
                        var target=uri
                        repeat(4) {
                            currentCoroutineContext().ensureActive()
                            require(target.scheme=="https" && target.userInfo==null && target.port==-1 &&
                                listOf("gog-statics.com","gog.com","epicgames.com","unrealengine.com","steamstatic.com","cdn2.steamgriddb.com","images-eds-ssl.xboxlive.com","store-images.s-microsoft.com","store-images.microsoft.com").any { target.host==it || target.host.orEmpty().endsWith(".$it") })
                            val connection=target.toURL().openConnection() as HttpURLConnection
                            try {
                                connection.connectTimeout=8000;connection.readTimeout=10000;connection.instanceFollowRedirects=false
                                connection.setRequestProperty("User-Agent","GameFibre/1.0")
                                cached?.etag?.let { connection.setRequestProperty("If-None-Match",it) }
                                cached?.modified?.let { connection.setRequestProperty("If-Modified-Since",it) }
                                requests.incrementAndGet()
                                val status=connection.responseCode
                                if(status==304 && cached!=null) {
                                    store.source(key,cached.blob,cached.etag,cached.modified)
                                    return@withContext store.blob(cached.blob)
                                }
                                if(status==429) { cooldowns[host]=System.currentTimeMillis()+(connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1,3600) ?: 60)*1000;error("Image provider rate limited") }
                                if(status in listOf(301,302,303,307,308)) target=target.resolve(connection.getHeaderField("Location") ?: error("Missing redirect"))
                                else {
                                    check(status==200 && connection.contentType.orEmpty().startsWith("image/")){"Image HTTP $status"}
                                    val bytes=connection.inputStream.use { input ->
                                        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                                        while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;check(output.size()+n<=8*1024*1024){"Image exceeds limit"};output.write(buffer,0,n)}
                                        output.toByteArray()
                                    }
                                    bytesReceived.addAndGet(bytes.size.toLong())
                                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                                    check(bounds.outWidth>3 && bounds.outHeight>5){"Invalid or preview-only image"}
                                    val maxW=if(fullscreen)1280 else if(large)640 else 320
                                    val maxH=if(fullscreen)1920 else if(portrait)480 else 180
                                    val options=BitmapFactory.Options().apply { inSampleSize=1;while(bounds.outWidth/inSampleSize>maxW*2 || bounds.outHeight/inSampleSize>maxH*2)inSampleSize*=2 }
                                    val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: error("Cannot decode image")
                                    val ratio=minOf(1.0,maxW.toDouble()/decoded.width,maxH.toDouble()/decoded.height)
                                    val bitmap=if(ratio==1.0)decoded else Bitmap.createScaledBitmap(decoded,(decoded.width*ratio).toInt().coerceAtLeast(1),(decoded.height*ratio).toInt().coerceAtLeast(1),true)
                                    val output=java.io.ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,output))
                                    val blob=store.write(output.toByteArray())
                                    store.source(key,blob,connection.getHeaderField("ETag"),connection.getHeaderField("Last-Modified"))
                                    if(bitmap!==decoded)bitmap.recycle();decoded.recycle()
                                    return@withContext store.blob(blob)
                                }
                            } finally { connection.disconnect() }
                        }
                        error("Too many image redirects")
                    }
                }
            } catch(error:Exception) {
                if(error is kotlinx.coroutines.CancellationException)throw error
                android.util.Log.w("AssetTransport","$host: ${error.message}")
                if(requireFresh)throw error
                cached?.let { store.blob(it.blob) }
            } finally { background?.release() }
        }
    }
}
