package com.gamefibre.assets

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.gamefibre.LibraryEntry
import com.gamefibre.LibraryRecords
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal fun assetDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal data class SelectedAsset(val group: String, val blob: String, val source: String, val fingerprint: String)
internal data class StoredGame(val scope: String, val game: LibraryEntry, val group: String, val fingerprint: String, val selected: SelectedAsset?, val failure: String?)

/** Index commits reference immutable, completely written image and metadata revisions. No migrations. */
internal class AssetStore(context: Context, databaseName: String = "library-assets.sqlite", val directory: File = File(context.filesDir, "library-assets")) : SQLiteOpenHelper(context, databaseName, null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE games (key TEXT PRIMARY KEY, scope TEXT NOT NULL, candidate TEXT NOT NULL, published TEXT, group_id TEXT NOT NULL, fingerprint TEXT NOT NULL, failure TEXT)")
        db.execSQL("CREATE TABLE selections (group_id TEXT PRIMARY KEY, blob TEXT NOT NULL, source TEXT NOT NULL, fingerprint TEXT NOT NULL)")
        db.execSQL("CREATE TABLE sources (key TEXT PRIMARY KEY, blob TEXT NOT NULL, etag TEXT, modified TEXT, checked INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE provider_records (key TEXT PRIMARY KEY, json TEXT NOT NULL, checked INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE steam (id INTEGER PRIMARY KEY, portrait TEXT NOT NULL, landscape TEXT NOT NULL, checked INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE jobs (key TEXT PRIMARY KEY, priority INTEGER NOT NULL, attempt INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0, revision TEXT NOT NULL, lease INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported asset schema; automatic migration/reset is not permitted")
    fun blob(id: String) = File(directory, "$id.jpg")
    @Synchronized fun write(bytes: ByteArray): String {
        val id = assetDigest(bytes)
        val target = blob(id)
        if (target.isFile) { target.setLastModified(System.currentTimeMillis());return id }
        directory.mkdirs()
        val pending = File.createTempFile("asset-", ".new", directory)
        try {
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
        return id
    }
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, Pair<String, LibraryEntry>>()
    /** Candidate JSON rarely changes between reads; compare the text before paying for a parse. */
    private fun entry(key: String, json: String): LibraryEntry {
        parsed[key]?.let { if (it.first == json) return it.second }
        return LibraryRecords.read(JSONArray().put(JSONObject(json))).single().also { parsed[key] = json to it }
    }
    /** Run several staging writes as one commit. Do not call publish() inside: it returns false without marking success. */
    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { return block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    fun games(): List<StoredGame> = readableDatabase.rawQuery("SELECT games.key,games.scope,COALESCE(games.published,games.candidate),games.group_id,games.fingerprint,games.failure,selections.blob,selections.source,selections.fingerprint FROM games LEFT JOIN selections ON selections.group_id=games.group_id", null).use { rows ->
        val present = HashMap<String, Boolean>()
        buildList {
            while (rows.moveToNext()) {
                val key = rows.getString(0)
                val game = entry(key, rows.getString(2))
                val group = rows.getString(3)
                val selected = if (rows.isNull(6)) null else SelectedAsset(group, rows.getString(6), rows.getString(7), rows.getString(8))
                    .takeIf { asset -> present.getOrPut(asset.blob) { blob(asset.blob).isFile } }
                add(StoredGame(rows.getString(1), game, group, rows.getString(4), selected, rows.getString(5)))
            }
        }
    }
    fun selection(group: String): SelectedAsset? = readableDatabase.rawQuery("SELECT blob,source,fingerprint FROM selections WHERE group_id=?", arrayOf(group)).use {
        if (!it.moveToFirst()) null else SelectedAsset(group, it.getString(0), it.getString(1), it.getString(2)).takeIf { selected -> blob(selected.blob).isFile }
    }
    fun candidate(key: String): LibraryEntry? = readableDatabase.rawQuery("SELECT candidate FROM games WHERE key=?", arrayOf(key)).use {
        if (!it.moveToFirst()) null else entry(key, it.getString(0))
    }
    fun stage(game: LibraryEntry, scope: String, group: String, fingerprint: String, priority: Int, refresh: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = db.rawQuery("SELECT fingerprint,scope,group_id FROM games WHERE key=?", arrayOf(game.key)).use {
                if (it.moveToFirst()) Triple(it.getString(0), it.getString(1), it.getString(2)) else null
            }
            if (old != null && old.second != scope) db.delete("games", "key=?", arrayOf(game.key))
            val values = ContentValues().apply {
                put("key", game.key); put("scope", scope); put("candidate", LibraryRecords.json(game).toString()); put("group_id", group); put("fingerprint", fingerprint)
            }
            if (old == null || old.second != scope) db.insertOrThrow("games", null, values) else db.update("games", values, "key=?", arrayOf(game.key))
            val selected = selection(group)
            if (selected != null && selected.fingerprint == fingerprint) {
                db.execSQL("UPDATE games SET published=candidate,failure=NULL WHERE key=?", arrayOf(game.key))
            }
            if (refresh || old?.first != fingerprint || selected == null) {
                val existing = db.rawQuery("SELECT jobs.key FROM jobs JOIN games ON jobs.key=games.key WHERE games.group_id=? LIMIT 1", arrayOf(group)).use { if(it.moveToFirst()) it.getString(0) else game.key }
                db.insertWithOnConflict("jobs",null,ContentValues().apply { put("key",existing);put("priority",priority);put("revision",fingerprint) },SQLiteDatabase.CONFLICT_IGNORE)
                db.execSQL("UPDATE jobs SET priority=MIN(priority,?),lease=CASE WHEN revision=? THEN lease ELSE 0 END,next_at=CASE WHEN revision=? AND ?=0 THEN next_at ELSE 0 END,attempt=CASE WHEN revision=? THEN attempt ELSE 0 END,revision=? WHERE key=?",arrayOf<Any>(priority,fingerprint,fingerprint,if(refresh)1 else 0,fingerprint,fingerprint,existing))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun publish(key: String, scope: String, fingerprint: String, blob: String, source: String): Boolean {
        val image = this.blob(blob)
        check(image.isFile)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(image.path, bounds)
        check(bounds.outWidth > 3 && bounds.outHeight > 5) { "Cannot publish invalid or preview artwork" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val group = db.rawQuery("SELECT group_id FROM games WHERE key=? AND scope=? AND fingerprint=?", arrayOf(key, scope, fingerprint)).use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
            val conflicting=db.rawQuery("SELECT COUNT(*) FROM games WHERE group_id=? AND fingerprint<>?",arrayOf(group,fingerprint)).use {it.moveToFirst();it.getInt(0)>0}
            if(conflicting)return false
            db.insertWithOnConflict("selections", null, ContentValues().apply {
                put("group_id", group); put("blob", blob); put("source", source); put("fingerprint", fingerprint)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL("UPDATE games SET published=candidate,failure=NULL WHERE group_id=?", arrayOf(group))
            db.execSQL("DELETE FROM jobs WHERE revision=? AND key IN (SELECT key FROM games WHERE group_id=?)", arrayOf(fingerprint,group))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    data class Job(val key: String, val priority: Int, val revision: String)
    fun claim(interactiveOnly:Boolean): Job? {
        val db=writableDatabase;db.beginTransaction()
        try {
            val now=System.currentTimeMillis()
            val job=db.rawQuery("SELECT key,priority,revision FROM jobs WHERE next_at<=? AND lease<=?"+(if(interactiveOnly)" AND priority=0" else "")+" ORDER BY priority,rowid LIMIT 1",arrayOf(now.toString(),now.toString())).use {
                if(it.moveToFirst())Job(it.getString(0),it.getInt(1),it.getString(2))else null
            }
            if(job!=null)db.execSQL("UPDATE jobs SET lease=? WHERE key=?",arrayOf<Any>(now+120_000,job.key))
            db.setTransactionSuccessful();return job
        }finally{db.endTransaction()}
    }
    fun queued(limit: Int=50): List<Job> = readableDatabase.rawQuery("SELECT key,priority,revision FROM jobs WHERE next_at<=? ORDER BY priority,rowid LIMIT ?", arrayOf(System.currentTimeMillis().toString(),limit.toString())).use {
        buildList { while(it.moveToNext()) add(Job(it.getString(0),it.getInt(1),it.getString(2))) }
    }
    fun row(key: String): StoredGame? = readableDatabase.rawQuery("SELECT scope,COALESCE(published,candidate),group_id,fingerprint,failure FROM games WHERE key=?", arrayOf(key)).use {
        if(!it.moveToFirst()) null else StoredGame(it.getString(0),entry(key,it.getString(1)),it.getString(2),it.getString(3),selection(it.getString(2)),it.getString(4))
    }
    fun nextWorkAt():Long?=readableDatabase.rawQuery("SELECT MIN(MAX(next_at,lease)) FROM jobs",null).use {
        it.moveToFirst();if(it.isNull(0))null else it.getLong(0)
    }
    fun jobs() = readableDatabase.rawQuery("SELECT COUNT(*) FROM jobs", null).use { it.moveToFirst(); it.getInt(0) }
    fun promote(keys:Set<String>) {
        if(keys.isEmpty()) {writableDatabase.execSQL("UPDATE jobs SET priority=2 WHERE priority<>2");return}
        val placeholders=keys.joinToString(","){"?"}
        // Resolve demanded groups once, rather than running a group join per visible card.
        writableDatabase.execSQL("UPDATE jobs SET priority=CASE WHEN key IN (SELECT key FROM games WHERE group_id IN (SELECT group_id FROM games WHERE key IN ($placeholders))) THEN 0 ELSE 2 END",keys.toTypedArray())
    }
    fun failed(job: Job, message: String) {
        writableDatabase.execSQL("UPDATE jobs SET lease=0,attempt=attempt+1,next_at=?+MIN(3600000,60000*(1 << MIN(attempt,6))) WHERE key=? AND revision=?", arrayOf<Any>(System.currentTimeMillis(),job.key,job.revision))
        writableDatabase.execSQL("UPDATE games SET failure=? WHERE key=? AND fingerprint=?", arrayOf(message,job.key,job.revision))
    }
    fun retireInactive(scopes: Map<String, String>, localKeys: Set<String>) {
        // Only key and scope decide retirement; avoid parsing every candidate record for it.
        val retired = readableDatabase.rawQuery("SELECT key,scope FROM games", null).use { rows ->
            buildList { while (rows.moveToNext()) {
                val key = rows.getString(0); val scope = rows.getString(1)
                val inactive = if (scope == "local") key !in localKeys else scopes[key.substringBefore(':')] != scope
                if (inactive) add(key)
            } }
        }
        if (retired.isEmpty()) return
        transaction {
            retired.forEach { key ->
                writableDatabase.delete("games", "key=?", arrayOf(key)); writableDatabase.delete("jobs", "key=?", arrayOf(key))
                parsed.remove(key)
            }
        }
    }
    data class Source(val blob: String, val etag: String?, val modified: String?, val checked: Long)
    @Synchronized fun source(key: String): Source? = readableDatabase.rawQuery("SELECT blob,etag,modified,checked FROM sources WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) Source(it.getString(0),it.getString(1),it.getString(2),it.getLong(3)).takeIf { s -> blob(s.blob).isFile } else null
    }
    fun source(key: String, blob: String, etag: String?, modified: String?) { writableDatabase.insertWithOnConflict("sources",null,ContentValues().apply {
        put("key",key); put("blob",blob); put("etag",etag); put("modified",modified); put("checked",System.currentTimeMillis())
    },SQLiteDatabase.CONFLICT_REPLACE) }
    /** Evict only unselected old transport objects. Published library masters are never budgeted. */
    @Synchronized fun trimTemporaryImages(limit:Long=128L*1024*1024,now:Long=System.currentTimeMillis()) {
        val pinned=readableDatabase.rawQuery("SELECT DISTINCT selections.blob FROM selections JOIN games ON games.group_id=selections.group_id",null).use { cursor ->
            buildSet { while(cursor.moveToNext())add(cursor.getString(0)) }
        }
        val temporary=directory.listFiles().orEmpty().filter { it.isFile && it.nameWithoutExtension !in pinned }
        var bytes=temporary.sumOf { it.length() }
        for(file in temporary.sortedBy { it.lastModified() }) {
            if(bytes<=limit)break
            if(file.lastModified()>now-24L*60*60*1000)continue
            val length=file.length()
            if(file.delete()) { writableDatabase.delete("sources","blob=?",arrayOf(file.nameWithoutExtension));bytes-=length }
        }
    }
    fun groupCandidates(group:String):List<LibraryEntry> = readableDatabase.rawQuery("SELECT key,candidate FROM games WHERE group_id=?",arrayOf(group)).use { cursor ->
        buildList { while(cursor.moveToNext())add(entry(cursor.getString(0),cursor.getString(1))) }
    }
    fun repair(blob:String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO jobs(key,priority,revision) SELECT games.key,0,games.fingerprint FROM games JOIN selections ON games.group_id=selections.group_id WHERE selections.blob=?",arrayOf(blob))
            db.delete("selections","blob=?",arrayOf(blob));db.delete("sources","blob=?",arrayOf(blob))
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
        this.blob(blob).delete()
    }
    fun provider(key:String):Pair<JSONObject,Long>? = readableDatabase.rawQuery("SELECT json,checked FROM provider_records WHERE key=?",arrayOf(key)).use {
        if(it.moveToFirst())JSONObject(it.getString(0)) to it.getLong(1)else null
    }
    fun provider(key:String,json:JSONObject) {writableDatabase.insertWithOnConflict("provider_records",null,ContentValues().apply{put("key",key);put("json",json.toString());put("checked",System.currentTimeMillis())},SQLiteDatabase.CONFLICT_REPLACE)}
    fun steam(id: Int): Pair<com.gamefibre.SteamArtworkUrls,Long>? = readableDatabase.rawQuery("SELECT portrait,landscape,checked FROM steam WHERE id=?",arrayOf(id.toString())).use {
        if(it.moveToFirst()) com.gamefibre.SteamArtworkUrls(it.getString(0),it.getString(1)) to it.getLong(2) else null
    }
    fun steam(id: Int, urls: com.gamefibre.SteamArtworkUrls) { writableDatabase.insertWithOnConflict("steam",null,ContentValues().apply {
        put("id",id);put("portrait",urls.portrait);put("landscape",urls.landscape);put("checked",System.currentTimeMillis())
    },SQLiteDatabase.CONFLICT_REPLACE) }
}
