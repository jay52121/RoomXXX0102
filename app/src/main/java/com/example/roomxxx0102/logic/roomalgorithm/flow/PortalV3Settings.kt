package com.example.roomxxx0102.logic.roomalgorithm.flow

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.*
import com.example.roomxxx0102.data.repository.RoomRepository
import com.example.roomxxx0102.data.repository.AppSettings
import com.example.roomxxx0102.logic.presence.PresencePoint
import com.example.roomxxx0102.logic.presence.PresenceRoomSnapshot
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest

/** Explicit startup baseline, separate from learned weak directional priors. */
object PortalV3Settings {
    internal data class Baseline(val known: Boolean, val counts: Map<String, Int>, val revision: Long)
    private fun prefs(context: Context) = context.getSharedPreferences("portal_v3", Context.MODE_PRIVATE)
    internal fun sceneKey(rooms: List<PresenceRoomSnapshot>): String {
        val text = rooms.sortedBy { it.roomId }.joinToString("|") { r ->
            r.roomId + ":" + r.polygon.joinToString(";") { "${it.x},${it.y}" }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    internal fun baselineKey(scene: String, video: Boolean) = scene + if (video) ":video:${AppSettings.testVideoUri}" else ":camera"
    internal fun baseline(context: Context?, key: String): Baseline {
        if (context == null) return Baseline(false, emptyMap(), 0)
        val raw = prefs(context).getString("baseline:$key", null) ?: return Baseline(false, emptyMap(), 0)
        return try {
            val json = JSONObject(raw)
            val counts = json.getJSONObject("counts")
            Baseline(true, counts.keys().asSequence().associateWith { counts.getInt(it).coerceIn(0, 20) }, json.optLong("revision"))
        } catch (_: Exception) { Baseline(false, emptyMap(), 0) }
    }
    internal fun readProfiles(context: Context?, key: String): Map<String, List<FlowPoint>> {
        if (context == null) return emptyMap()
        return try {
            val j = JSONObject(prefs(context).getString("profile:$key", "{}")!!)
            j.keys().asSequence().associateWith { name -> val a=j.getJSONArray(name); (0 until a.length()).map { i ->
                val v=a.getJSONArray(i); FlowPoint(v.getDouble(0),v.getDouble(1))
            } }
        } catch (_: Exception) { emptyMap() }
    }
    internal fun saveProfiles(context: Context?, key: String, samples: Map<String, List<FlowPoint>>) {
        if (context == null) return
        val j=JSONObject(); samples.forEach { (id, ps) -> j.put(id, JSONArray().apply { ps.takeLast(12).forEach { put(JSONArray().put(it.x).put(it.y)) } }) }
        prefs(context).edit().putString("profile:$key",j.toString()).apply()
    }
    fun showBaselineEditor(context: Context) {
        val rooms=RoomRepository.getAllRooms().filter { !it.isEntranceDoor }
        val snapshots=RoomRepository.getAllRooms().map { r -> PresenceRoomSnapshot(r.id,r.name,if (!r.isLivingBlindZone && r.boundaryPoints.size >= 3) r.boundaryPoints.map { PresencePoint(it.x.toDouble(),it.y.toDouble()) } else emptyList(),r.isSovereignTerritory,r.isLivingBlindZone,r.isEntranceDoor) }
        val scene=sceneKey(snapshots)
        val layout=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(24,16,24,16) }
        val video=CheckBox(context).apply { text="\u7528\u4e8e\u5f53\u524d\u6d4b\u8bd5\u89c6\u9891\uff08\u53d6\u6d88\u5219\u7528\u4e8e\u6444\u50cf\u5934\uff09"; isChecked=AppSettings.testVideoUri!=null }
        layout.addView(video)
        layout.addView(TextView(context).apply { text="\u586b\u5199\u56de\u653e\u5f00\u59cb\u524d\u7684\u4eba\u6570\uff0c\u4e0d\u662f\u5f53\u524d\u753b\u9762\u4eba\u6570\u3002\u9690\u85cf\u623f\u95f4\u4eba\u6570\u672a\u77e5\u65f6\u4fdd\u6301\u201c\u672a\u8bbe\u7f6e\u201d\u3002\u4fdd\u5b58\u540e\u8bf7\u4ece\u89c6\u9891\u5f00\u5934\u91cd\u64ad\u3002" })
        val fields=rooms.associate { r ->
            layout.addView(TextView(context).apply { text=r.name })
            r.id to EditText(context).apply { hint="${r.name}: 0"; inputType=InputType.TYPE_CLASS_NUMBER; setText("0"); layout.addView(this) } }
        fun load() { val b=baseline(context,baselineKey(scene,video.isChecked)); fields.forEach { (id,e)->e.setText((b.counts[id]?:0).toString()) } }
        video.setOnCheckedChangeListener { _,_-> load() }; load()
        AlertDialog.Builder(context).setTitle("V3 \u8d77\u59cb\u4eba\u6570")
            .setView(ScrollView(context).apply { addView(layout) })
            .setPositiveButton("\u4fdd\u5b58\u5df2\u77e5\u4eba\u6570") { _,_->
                val counts=JSONObject(); fields.forEach { (id,e)->counts.put(id,e.text.toString().toIntOrNull()?.coerceIn(0,20)?:0) }
                val j=JSONObject().put("revision",System.currentTimeMillis()).put("counts",counts)
                prefs(context).edit().putString("baseline:${baselineKey(scene,video.isChecked)}",j.toString()).apply()
            }.setNeutralButton("\u672a\u77e5\u002f\u6e05\u9664\u521d\u503c") { _,_-> prefs(context).edit().remove("baseline:${baselineKey(scene,video.isChecked)}").apply() }
            .setNegativeButton("\u53d6\u6d88",null).show()
    }
}
