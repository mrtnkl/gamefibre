package com.gamefibre.stores

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal object StorePayloads {
    private const val GOG_CDN="https://gog-cdn-fastly.gog.com"
    private const val GOG_CONTENT="https://content-system.gog.com"
    private const val EPIC_LAUNCHER="https://launcher-public-service-prod06.ol.epicgames.com"
    suspend fun resolve(request: StoreDownloadRequest, session: StoreSession, io: DownloadIo): StorePayload =
        when (request.store) {
            GameStore.EPIC -> epic(request,session,io)
            GameStore.GOG -> gog(request,session,io)
            GameStore.XBOX -> error("Xbox downloads are not supported. Open the game in Xbox instead.")
        }
    private fun objects(array:JSONArray)=(0 until array.length()).map { array.getJSONObject(it) }
    private fun values(array:JSONArray?)=if(array==null)emptyList()else (0 until array.length()).map { array.get(it).toString() }
    private fun galaxy(hash:String):String { require(hash.matches(Regex("[A-Fa-f0-9]{32,64}"))); return "${hash.take(2)}/${hash.substring(2,4)}/$hash" }
    private fun json(bytes:ByteArray):JSONObject {
        val data=if(bytes.take(8).toByteArray().toString(Charsets.UTF_8).trimStart().startsWith("{"))bytes else inflate(bytes,128*1024*1024)
        return JSONObject(String(data,Charsets.UTF_8))
    }
    private suspend fun epic(r:StoreDownloadRequest,s:StoreSession,io:DownloadIo):StorePayload {
        val platform=if(r.platform=="macos")"Mac" else "Windows"
        var namespace=r.game.namespace; var catalog=r.game.catalogId
        if(namespace.isBlank()||catalog.isBlank()) {
            val assets=StoreApi.authorizedArray("$EPIC_LAUNCHER/launcher/api/public/assets/$platform?label=Live",s)
            val asset=objects(assets).firstOrNull { it.optString("appName")==r.game.id }
                ?:error("This game has no Epic download for the selected platform. It may require another launcher.")
            namespace=asset.getString("namespace"); catalog=asset.getString("catalogItemId")
        }
        val response=StoreApi.authorizedJson("$EPIC_LAUNCHER/launcher/api/public/assets/v2/platform/$platform/namespace/${Uri.encode(namespace)}/catalogItem/${Uri.encode(catalog)}/app/${Uri.encode(r.game.id)}/label/Live",s)
        val elements=objects(response.getJSONArray("elements")); check(elements.size==1) { "Epic returned no usable game build for this platform." }
        val build=elements.single(); check(!build.optBoolean("isPreloaded")) { "This game is preloaded and cannot be exported before release." }
        val expected=build.getString("hash")
        val manifests=objects(build.getJSONArray("manifests")); check(manifests.isNotEmpty()) { "Epic returned no game manifest." }
        val urls=manifests.map { manifest -> val b=Uri.parse(manifest.getString("uri")).buildUpon()
            objects(manifest.optJSONArray("queryParams")?:JSONArray()).forEach { b.appendQueryParameter(it.getString("name"),it.getString("value")) }; b.build().toString() }
        val bytes=io.bytes(urls,128*1024*1024); check(digest(bytes,"SHA-1").equals(expected,true)) { "Epic's game manifest failed verification." }
        val secrets=build.optJSONObject("secrets")?:JSONObject()
        val keys=secrets.keys().asSequence().associate { it.uppercase() to secrets.getString(it) }
        return EpicManifest.parse(bytes,manifests.map { it.getString("uri").substringBeforeLast('/') }.distinct(),keys)
    }
    private fun language(value:String,selected:String):Boolean {
        val aliases=mapOf("en" to "english", "nl" to "dutch", "de" to "german", "fr" to "french", "es" to "spanish", "it" to "italian", "pt" to "portuguese", "pt-br" to "brazilian", "pl" to "polish", "ru" to "russian", "ja" to "japanese", "ko" to "korean", "zh-hans" to "chinese", "zh-hant" to "chinese")
        val candidate=value.lowercase().replace('_','-'); val wanted=selected.lowercase()
        val extra=when(wanted) {
            "pt-br" -> listOf("brazilian portuguese","portuguese (brazil)","portuguese brazilian")
            "zh-hans" -> listOf("zh-cn","simplified chinese","chinese (simplified)","chinesesimplified")
            "zh-hant" -> listOf("zh-tw","traditional chinese","chinese (traditional)","chinesetraditional")
            else -> emptyList()
        }
        return candidate in listOf("*","neutral",wanted,aliases[wanted]) || candidate in extra || (wanted!="pt" && candidate.startsWith("$wanted-"))
    }
    private fun secureUrls(data:JSONObject,suffix:String):List<String> = objects(data.getJSONArray("urls")).map { endpoint ->
        if(endpoint.has("url_format")) {
            val parameters=JSONObject(endpoint.getJSONObject("parameters").toString())
            parameters.put("path",parameters.optString("path").trimEnd('/')+"/"+suffix.trimStart('/'))
            var url=endpoint.getString("url_format")
            parameters.keys().forEach { key -> url=url.replace("{$key}",parameters.get(key).toString()) }; url
        } else endpoint.getString("url").trimEnd('/')+"/"+suffix.trimStart('/')
    }
    private fun secure(product:String,path:String,generation:Int,s:StoreSession):JSONObject {
        val b=Uri.parse("$GOG_CONTENT/products/${Uri.encode(product)}/secure_link").buildUpon().appendQueryParameter("_version","2")
            .appendQueryParameter(if(generation==2)"generation"else"type",if(generation==2)"2"else"depot").appendQueryParameter("path",path)
        return StoreApi.authorizedJson(b.build().toString(),s)
    }
    private suspend fun gog(r:StoreDownloadRequest,s:StoreSession,io:DownloadIo):StorePayload {
        if(r.platform=="linux") {
            val product=StoreApi.authorizedJson("https://api.gog.com/products/${Uri.encode(r.game.id)}?expand=downloads",s)
            val installers=objects(product.getJSONObject("downloads").getJSONArray("installers")).filter { it.optString("os")=="linux" }
            val installer=installers.firstOrNull { language(it.optString("language"),r.language) }
                ?:error("This game has no Linux installer for the selected language.")
            val parts=objects(installer.getJSONArray("files")); check(parts.size==1) { "This Linux build uses an unsupported multipart installer." }
            val file=parts.single(); val downlink=file.getString("downlink")
            val uri=Uri.parse(downlink); check(uri.scheme=="https" && uri.host in listOf("api.gog.com","content-system.gog.com","embed.gog.com"))
            val url=StoreApi.authorizedJson(downlink,s).getString("downlink")
            val identity="${r.game.id}:${installer.optString("id")}:${installer.optString("version")}:${file.optLong("size")}"
            return GogLinuxPayload(digest(identity.toByteArray()),url,file.optLong("size"))
        }
        val os=if(r.platform=="macos")"osx"else"windows"
        val builds=StoreApi.authorizedJson("$GOG_CONTENT/products/${Uri.encode(r.game.id)}/os/$os/builds?generation=2&_version=2",s)
        val build=objects(builds.getJSONArray("items")).firstOrNull { it.optString("branch").let { b->b.isBlank()||b=="null" } }
            ?:error("This game has no public GOG build for the selected platform.")
        val bytes=io.bytes(gogBuildUrls(build),128*1024*1024)
        val meta=json(bytes)
        val generation=build.getInt("generation")
        check(generation in 1..2) { "This GOG build uses an unsupported depot format." }
        val root=if(generation==2)meta else meta.getJSONObject("product")
        val product=root.get(if(generation==2)"baseProductId"else"rootGameID").toString()
        val applicable=objects(root.getJSONArray("depots")).filter { depot ->
            !depot.has("redist") && (if(generation==2)depot.opt("productId")?.toString()==product else product in values(depot.optJSONArray("gameIDs")))
        }
        val specific=applicable.flatMap { values(it.optJSONArray("languages")) }.filterNot { it.lowercase() in listOf("*","neutral") }
        check(specific.isEmpty() || specific.any { language(it,r.language) }) { "This GOG build does not include the selected language." }
        val depots=applicable.filter { values(it.optJSONArray("languages")).any { lang->language(lang,r.language) } }
        val has64=depots.any { "64" in values(it.optJSONArray("osBitness")) }
        val selected=depots.filter { val bits=values(it.optJSONArray("osBitness")); bits.isEmpty() || (if(has64)"64"else"32") in bits }
        check(selected.isNotEmpty()) { "No GOG game files match the selected language." }
        check(specific.isEmpty() || selected.any { values(it.optJSONArray("languages")).any { lang->lang.lowercase() !in listOf("*","neutral") && language(lang,r.language) } }) { "This GOG build has no matching language files for the selected architecture." }
        val secure=secure(product,if(generation==2)"/"else"/$os/${root.get("timestamp")}/",generation,s)
        val files=linkedMapOf<String,StoreFile>()
        for(depot in selected) {
            val manifest=depot.getString("manifest")
            val url=if(generation==2)"$GOG_CDN/content-system/v2/meta/${galaxy(manifest)}"
                else "$GOG_CDN/content-system/v1/manifests/${Uri.encode(values(depot.getJSONArray("gameIDs")).first())}/$os/${Uri.encode(root.get("timestamp").toString())}/${Uri.encode(manifest)}"
            val contents=json(io.bytes(listOf(url),128*1024*1024)).getJSONObject("depot")
            val entries=objects(contents.getJSONArray(if(generation==2)"items"else"files"))
            for(file in entries) {
                if(file.optBoolean("support") || "support" in values(file.optJSONArray("flags"))) continue
                val path=file.getString("path").replace('\\','/').removePrefix("/")
                if(generation==2 && file.optString("type")=="DepotLink") { files[path]=StoreFile(path,0,"","MD5",emptyList(),file.getString("target")); continue }
                if((generation==2 && file.optString("type")!="DepotFile") || (generation==1 && file.has("directory")))continue
                val algorithm=if(file.optString("sha256").let { it.isNotBlank() && it!="null" })"SHA-256"else"MD5"
                val hash=file.optString(if(algorithm=="SHA-256")"sha256"else if(generation==2)"md5"else"hash").takeUnless { it=="null" }.orEmpty()
                val parts=if(generation==2) objects(file.getJSONArray("chunks")).map { chunk ->
                    val size=chunk.getLong("size").also { require(it in 0..64*1024*1024) }.toInt()
                    val packed=chunk.getString("compressedMd5"); val md5=chunk.getString("md5")
                    val blob=StoreBlob("gog:$packed:$md5",size,{secureUrls(secure,galaxy(packed))},md5,"MD5",decode={ raw ->
                        check(digest(raw,"MD5").equals(packed,true)) { "A GOG chunk failed verification." }; inflate(raw,size)
                    })
                    StorePart(blob,0,size)
                } else {
                    val size=file.getLong("size"); require(size>=0)
                    check(size==0L || (file.has("offset") && !file.isNull("offset") && hash.matches(Regex("[a-fA-F0-9]{32}")))) { "This legacy GOG build has no usable file ranges or checksums." }
                    var left=size; var offset=file.optLong("offset"); val result=mutableListOf<StorePart>()
                    while(left>0) { val length=minOf(left,8L*1024*1024).toInt(); val start=offset
                        val blob=StoreBlob("gog-v1:${build.get("build_id")}:$start:$length:$hash",length,{secureUrls(secure,"main.bin")},range=start to length.toLong())
                        result.add(StorePart(blob,0,length)); offset+=length; left-=length }
                    result
                }
                files[path]=StoreFile(path,parts.sumOf { it.size.toLong() },hash,algorithm,parts)
            }
        }
        return ChunkPayload(digest(bytes+r.language.toByteArray()),files.values.toList())
    }
}
