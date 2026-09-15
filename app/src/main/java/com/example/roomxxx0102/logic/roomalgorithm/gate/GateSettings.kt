package com.example.roomxxx0102.logic.roomalgorithm.gate

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.*
import org.json.JSONObject

object GateSettings {
    private fun prefs(c:Context)=c.getSharedPreferences("portal_v4_methods",Context.MODE_PRIVATE)
    private data class Field(val key:String,val title:String,val min:Double,val max:Double)
    private val common=listOf(
        Field("sampleMs","\u91c7\u6837\u5468\u671f ms\uff0850=\u6700\u9ad820\u5e27\uff09",33.0,200.0),
        Field("captureEdge","\u91c7\u5e27\u957f\u8fb9\u50cf\u7d20\uff08\u5c0f ROI \u5efa\u8bae1920\uff09",640.0,2560.0),
        Field("visionEdge","\u95e8\u53e3\u89c6\u89c9\u5206\u8fa8\u7387\u957f\u8fb9",320.0,960.0),
        Field("maxGapMs","\u8fde\u7eed\u89c6\u89c9\u6700\u5927\u95f4\u9694 ms",150.0,500.0),
        Field("pixelThreshold","\u4eae\u5ea6\u5dee\u9608\u503c\uff08\u8d8a\u5927\u8d8a\u4e0d\u654f\u611f\uff09",8.0,60.0),
        Field("backgroundMs","\u7a7a\u95e8\u80cc\u666f\u9884\u70ed ms",300.0,5000.0),
        Field("clearMs","\u80cc\u666f\u6062\u590d\u786e\u8ba4 ms",120.0,1000.0),
        Field("clearRatio","\u5141\u8bb8\u6b8b\u4f59\u524d\u666f\u6bd4\u4f8b",0.02,0.20),
        Field("contactScale","\u95e8\u69db\u63a5\u89e6\u5e26\uff08\u4eba\u9ad8\u6bd4\u4f8b\uff09",0.05,0.20),
        Field("confirmMs","\u53ef\u89c1\u8de8\u95e8\u786e\u8ba4 ms",100.0,600.0),
        Field("admissionTravel","\u771f\u4eba\u51c6\u5165\u6700\u5c0f\u4f4d\u79fb",0.005,0.04),
        Field("episodeMs","\u906e\u6321\u8bc1\u636e\u6700\u957f\u4fdd\u7559 ms",600.0,2500.0))
    private val optical=listOf(
        Field("points","\u5355\u95e8\u7279\u5f81\u70b9\u4e0a\u9650",64.0,384.0),
        Field("fbError","\u6b63\u53cd\u5411\u5149\u6d41\u5bb9\u5dee\u50cf\u7d20",0.5,3.0),
        Field("pyramidLevel","\u5149\u6d41\u91d1\u5b57\u5854\u6700\u5927\u5c42\u53f7",1.0,3.0),
        Field("optionalBudgetMs","\u53ef\u9009\u5149\u6d41\u8ba1\u7b97\u9884\u7b97 ms",10.0,60.0))
    private val mog=listOf(Field("mogVariance","MOG2 \u65b9\u5dee\u9608\u503c",8.0,64.0),Field("backgroundRate","\u80cc\u666f\u5b66\u4e60\u901f\u7387",0.002,0.08))
    internal fun load(c:Context?,m:GateMethod):GateConfig {
        val d=GateConfig(m,captureEdge=if(m==GateMethod.OPTICAL_FLOW) 1920 else 1280)
        if(c==null) return d
        return try { decode(d,JSONObject(prefs(c).getString(m.id,"{}")!!)).checked() } catch(_:Exception) { d }
    }
    private fun encode(d:GateConfig)=JSONObject().apply {
        put("sampleMs",d.sampleMs);put("captureEdge",d.captureEdge);put("visionEdge",d.visionEdge);put("maxGapMs",d.maxGapMs)
        put("pixelThreshold",d.pixelThreshold);put("backgroundMs",d.backgroundMs);put("clearMs",d.clearMs);put("clearRatio",d.clearRatio)
        put("contactScale",d.contactScale);put("confirmMs",d.confirmMs);put("admissionTravel",d.admissionTravel);put("episodeMs",d.episodeMs)
        put("points",d.points);put("fbError",d.fbError);put("pyramidLevel",d.pyramidLevel);put("optionalBudgetMs",d.optionalBudgetMs)
        put("mogVariance",d.mogVariance);put("backgroundRate",d.backgroundRate)
    }
    private fun decode(d:GateConfig,j:JSONObject)=d.copy(
        sampleMs=j.optInt("sampleMs",d.sampleMs),captureEdge=j.optInt("captureEdge",d.captureEdge),visionEdge=j.optInt("visionEdge",d.visionEdge),
        maxGapMs=j.optInt("maxGapMs",d.maxGapMs),pixelThreshold=j.optInt("pixelThreshold",d.pixelThreshold),backgroundMs=j.optInt("backgroundMs",d.backgroundMs),
        clearMs=j.optInt("clearMs",d.clearMs),clearRatio=j.optDouble("clearRatio",d.clearRatio),contactScale=j.optDouble("contactScale",d.contactScale),
        confirmMs=j.optInt("confirmMs",d.confirmMs),admissionTravel=j.optDouble("admissionTravel",d.admissionTravel),episodeMs=j.optInt("episodeMs",d.episodeMs),
        points=j.optInt("points",d.points),fbError=j.optDouble("fbError",d.fbError),pyramidLevel=j.optInt("pyramidLevel",d.pyramidLevel),
        optionalBudgetMs=j.optInt("optionalBudgetMs",d.optionalBudgetMs),mogVariance=j.optDouble("mogVariance",d.mogVariance),backgroundRate=j.optDouble("backgroundRate",d.backgroundRate))
    fun isNewMethod(id:String?)=GateMethod.from(id)!=null
    fun activate(c:Context?,id:String?) { GateRuntime.configure(GateMethod.from(id)?.let { load(c,it) }) }
    fun showEditor(context:Context,id:String?) {
        val method=GateMethod.from(id)
        if(method==null) { Toast.makeText(context,"\u8bf7\u5148\u9009\u62e9 V4-A/B/C",Toast.LENGTH_SHORT).show();return }
        val config=load(context,method);val json=encode(config)
        val fields=common+when(method) { GateMethod.OPTICAL_FLOW->optical;GateMethod.MOG2->mog;else->emptyList() }
        val layout=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(24,16,24,16) }
        layout.addView(TextView(context).apply { text="\u53c2\u6570\u6309\u65b9\u6848\u72ec\u7acb\u4fdd\u5b58\u3002\u4fdd\u5b58\u540e\u8fd4\u56de\u753b\u9762\u5e76\u4ece\u5934\u91cd\u64ad\uff0c\u4e0d\u6cbf\u7528\u4e0a\u6b21\u7684\u4eba\u6570\u72b6\u6001\u3002\u8ba1\u7b97\u9884\u7b97\u4e0d\u4f1a\u5f3a\u884c\u4e2d\u65ad\u6b63\u5728\u8fd0\u884c\u7684\u539f\u751f\u51fd\u6570\u3002" })
        val edits=fields.associate { f ->
            layout.addView(TextView(context).apply { text="${f.title} [${f.min}..${f.max}]" })
            f.key to EditText(context).apply { inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;setText(json.get(f.key).toString());layout.addView(this) }
        }
        val dialog=AlertDialog.Builder(context).setTitle(method.label).setView(ScrollView(context).apply { addView(layout) })
            .setPositiveButton("\u4fdd\u5b58",null).setNegativeButton("\u53d6\u6d88",null)
            .setNeutralButton("\u6062\u590d\u672c\u65b9\u6848\u9ed8\u8ba4") { _,_->prefs(context).edit().remove(method.id).apply() }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            for(f in fields) {
                val value=edits.getValue(f.key).text.toString().toDoubleOrNull()
                if(value==null || !value.isFinite() || value !in f.min..f.max) {
                    edits.getValue(f.key).error="\u8bf7\u8f93\u5165\u8303\u56f4\u5185\u7684\u6570\u503c";return@setOnClickListener
                }
                json.put(f.key,value)
            }
            prefs(context).edit().putString(method.id,encode(decode(config,json).checked()).toString()).apply();dialog.dismiss()
        } }
        dialog.show()
    }
}
