package com.gamefibre.steam


import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.gamefibre.NetworkAvailability

/** Private app storage survives process death. The account library is cleared on sign-out. */
class LibraryCache(private val context: Context) {
    private val library = AtomicFile(File(context.filesDir, "library.json"))
    @Volatile private var savedOwner: String? = null
    fun owner(): String? = savedOwner

    @Synchronized fun load(ownerId: String): JSONArray? = runCatching {
        val saved = JSONObject(library.openRead().bufferedReader().use { it.readText() })
        saved.getJSONArray("games").takeIf { saved.getString("ownerId") == ownerId }?.also { savedOwner = ownerId }
    }.getOrNull()

    @Synchronized fun save(ownerId: String, games: JSONArray) {
        savedOwner = ownerId
        require(ownerId.matches(Regex("[0-9]+"))) { "Missing Steam ID" }
        val stream = library.startWrite()
        try {
            stream.write(JSONObject().put("ownerId", ownerId).put("games", games).toString().toByteArray())
            library.finishWrite(stream)
        } catch (e: Exception) { library.failWrite(stream); throw e }
    }

    @Synchronized fun clear() { library.delete(); savedOwner = null }

}
