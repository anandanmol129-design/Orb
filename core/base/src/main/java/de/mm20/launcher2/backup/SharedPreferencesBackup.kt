package de.mm20.launcher2.backup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Backs up a whole SharedPreferences file, keeping each value's type so it restores exactly.
 * Used for state that lives outside the settings data store and the database, such as the
 * orb's pie menu and layout.
 */
class SharedPreferencesBackup(
    context: Context,
    private val prefsName: String,
) : Backupable {

    private val context = context.applicationContext
    private val fileName = "prefs.$prefsName"

    override suspend fun backup(toDir: File) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val json = JSONObject()
        for ((key, value) in prefs.all) {
            val entry = when (value) {
                is String -> typed("string", value)
                is Boolean -> typed("boolean", value)
                is Int -> typed("int", value)
                is Long -> typed("long", value)
                is Float -> typed("float", value.toDouble())
                is Set<*> -> typed("stringset", JSONArray(value.filterIsInstance<String>()))
                else -> null
            } ?: continue
            json.put(key, entry)
        }
        File(toDir, fileName).writeText(json.toString())
    }

    override suspend fun restore(fromDir: File) = withContext(Dispatchers.IO) {
        val file = File(fromDir, fileName)
        if (!file.exists()) return@withContext
        val json = try {
            JSONObject(file.readText())
        } catch (e: JSONException) {
            Log.e("MM20", "Cannot restore $fileName", e)
            return@withContext
        }
        val editor = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear()
        for (key in json.keys()) {
            val entry = json.optJSONObject(key) ?: continue
            when (entry.optString("type")) {
                "string" -> editor.putString(key, entry.getString("value"))
                "boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
                "int" -> editor.putInt(key, entry.getInt("value"))
                "long" -> editor.putLong(key, entry.getLong("value"))
                "float" -> editor.putFloat(key, entry.getDouble("value").toFloat())
                "stringset" -> {
                    val array = entry.getJSONArray("value")
                    editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                }
            }
        }
        editor.commit()
        Unit
    }

    private fun typed(type: String, value: Any) = JSONObject().put("type", type).put("value", value)
}
