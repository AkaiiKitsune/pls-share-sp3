package io.github.akaiikitsune.plsshare

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** The user's own looks, saved as JSON in the preferences. */
class Presets(private val prefs: SharedPreferences) {
    companion object {
        const val KEY = "presets"
    }

    class Entry(val name: String, val look: EditState)

    fun load(): MutableList<Entry> {
        val list = mutableListOf<Entry>()
        val raw = prefs.getString(KEY, null) ?: return list
        val array = try { JSONArray(raw) } catch (e: Exception) { return list }
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val state = EditState()
            fromJson(o, state)
            list += Entry(o.optString("name", "?"), state)
        }
        return list
    }

    fun save(list: List<Entry>) {
        val array = JSONArray()
        for (e in list) array.put(toJson(e.look).put("name", e.name))
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun add(name: String, look: EditState) {
        val list = load().filter { it.name != name }.toMutableList()
        val copy = EditState().apply { copyLookFrom(look) }
        list += Entry(name, copy)
        save(list)
    }

    fun remove(name: String) = save(load().filter { it.name != name })

    private fun toJson(s: EditState) = JSONObject()
        .put("filter", s.filter)
        .put("brightness", s.brightness.toDouble()).put("contrast", s.contrast.toDouble())
        .put("saturation", s.saturation.toDouble()).put("shadows", s.shadows.toDouble())
        .put("highlights", s.highlights.toDouble()).put("blacks", s.blacks.toDouble()).put("whites", s.whites.toDouble())
        .put("temperature", s.temperature.toDouble()).put("tint", s.tint.toDouble())
        .put("sharpness", s.sharpness.toDouble()).put("vignette", s.vignette.toDouble())
        .put("frameWidth", s.frameWidth.toDouble()).put("frameBand", s.frameBand.toDouble())
        .put("cornerRadius", s.cornerRadius.toDouble()).put("frameColor", s.frameColor)

    private fun fromJson(o: JSONObject, s: EditState) {
        s.filter = o.optString("filter", "normal")
        s.brightness = o.optDouble("brightness", 0.0).toFloat(); s.contrast = o.optDouble("contrast", 0.0).toFloat()
        s.saturation = o.optDouble("saturation", 1.0).toFloat(); s.shadows = o.optDouble("shadows", 0.0).toFloat()
        s.highlights = o.optDouble("highlights", 0.0).toFloat(); s.blacks = o.optDouble("blacks", 0.0).toFloat()
        s.whites = o.optDouble("whites", 0.0).toFloat(); s.temperature = o.optDouble("temperature", 0.0).toFloat()
        s.tint = o.optDouble("tint", 0.0).toFloat(); s.sharpness = o.optDouble("sharpness", 0.0).toFloat()
        s.vignette = o.optDouble("vignette", 0.0).toFloat()
        s.frameWidth = o.optDouble("frameWidth", 0.0).toFloat(); s.frameBand = o.optDouble("frameBand", 0.0).toFloat()
        s.cornerRadius = o.optDouble("cornerRadius", 0.0).toFloat(); s.frameColor = o.optInt("frameColor", android.graphics.Color.WHITE)
    }
}
