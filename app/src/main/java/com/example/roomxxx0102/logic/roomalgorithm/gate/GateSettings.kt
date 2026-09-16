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
        Field("sampleMs","采样周期 ms（50=最高20帧）",33.0,200.0),
        Field("captureEdge","源截图长边像素（2560=当前约2232宽视频不缩小）",960.0,2560.0),
        Field("maxGapMs","连续视觉最大间隔 ms",150.0,500.0),
        Field("pixelThreshold","局部门口亮度差阈值",8.0,60.0),
        Field("historyMs","每扇门只存不算的回溯历史 ms",600.0,1600.0),
        Field("holdMs","人体掉检后继续观察该门 ms",400.0,1600.0),
        Field("armScore","允许启动门观察的候选人体最低分",0.10,0.80),
        Field("armDistanceScale","候选靠门距离（人高比例）",0.12,0.50),
        Field("cropPadding","门局部裁块额外边距（画面比例）",0.0,0.08),
        Field("maxActiveGates","同时运行视觉算法的门上限",1.0,3.0),
        Field("contourMinArea","调试前景最小轮廓面积（局部像素）",2.0,80.0),
        Field("clearMs","背景恢复确认 ms",120.0,1000.0),
        Field("clearRatio","允许残余前景比例",0.02,0.20),
        Field("contactScale","门槛接触带（人高比例）",0.05,0.20),
        Field("confirmMs","可见跨门确认 ms",100.0,600.0),
        Field("admissionTravel","真人准入最小位移",0.005,0.04),
        Field("episodeMs","遮挡证据最长保留 ms",600.0,2500.0))
    private val optical=listOf(
        Field("points","单个活跃门特征点上限",48.0,256.0),
        Field("fbError","正反向光流容差像素",0.5,3.0),
        Field("pyramidLevel","光流金字塔最大层号",1.0,3.0),
        Field("optionalBudgetMs","可选光流/回溯预算 ms",10.0,80.0))
    private val mog=listOf(Field("mogVariance","MOG2 方差阈值",8.0,64.0),Field("backgroundRate","MOG2 预热学习率",0.002,0.08))
    internal fun load(c:Context?,m:GateMethod):GateConfig {
        val d=GateConfig(m,captureEdge=2560,points=128)
        if(c==null) return d
        return try {
            val j=JSONObject(prefs(c).getString(m.id,"{}")!!)
            // Settings saved by pre-Event-ROI V4 used captureEdge as part of an additional whole-frame
            // downscale chain. Its old 1280/1920 value must not silently keep the new local crops soft.
            if(!j.has("historyMs")) {
                j.remove("captureEdge")
                j.remove("visionEdge")
            }
            decode(d,j).checked()
        } catch(_:Exception) { d }
    }
    private fun encode(d:GateConfig)=JSONObject().apply {
        put("sampleMs",d.sampleMs);put("captureEdge",d.captureEdge);put("visionEdge",d.visionEdge);put("maxGapMs",d.maxGapMs)
        put("pixelThreshold",d.pixelThreshold);put("backgroundMs",d.backgroundMs);put("clearMs",d.clearMs);put("clearRatio",d.clearRatio)
        put("contactScale",d.contactScale);put("confirmMs",d.confirmMs);put("admissionTravel",d.admissionTravel);put("episodeMs",d.episodeMs)
        put("points",d.points);put("fbError",d.fbError);put("pyramidLevel",d.pyramidLevel);put("optionalBudgetMs",d.optionalBudgetMs)
        put("mogVariance",d.mogVariance);put("backgroundRate",d.backgroundRate)
        put("historyMs",d.historyMs);put("holdMs",d.holdMs);put("armScore",d.armScore);put("armDistanceScale",d.armDistanceScale)
        put("cropPadding",d.cropPadding);put("maxActiveGates",d.maxActiveGates);put("contourMinArea",d.contourMinArea)
    }
    private fun decode(d:GateConfig,j:JSONObject)=d.copy(
        sampleMs=j.optInt("sampleMs",d.sampleMs),captureEdge=j.optInt("captureEdge",d.captureEdge),visionEdge=j.optInt("visionEdge",d.visionEdge),
        maxGapMs=j.optInt("maxGapMs",d.maxGapMs),pixelThreshold=j.optInt("pixelThreshold",d.pixelThreshold),backgroundMs=j.optInt("backgroundMs",d.backgroundMs),
        clearMs=j.optInt("clearMs",d.clearMs),clearRatio=j.optDouble("clearRatio",d.clearRatio),contactScale=j.optDouble("contactScale",d.contactScale),
        confirmMs=j.optInt("confirmMs",d.confirmMs),admissionTravel=j.optDouble("admissionTravel",d.admissionTravel),episodeMs=j.optInt("episodeMs",d.episodeMs),
        points=j.optInt("points",d.points),fbError=j.optDouble("fbError",d.fbError),pyramidLevel=j.optInt("pyramidLevel",d.pyramidLevel),
        optionalBudgetMs=j.optInt("optionalBudgetMs",d.optionalBudgetMs),mogVariance=j.optDouble("mogVariance",d.mogVariance),backgroundRate=j.optDouble("backgroundRate",d.backgroundRate),
        historyMs=j.optInt("historyMs",d.historyMs),holdMs=j.optInt("holdMs",d.holdMs),armScore=j.optDouble("armScore",d.armScore),
        armDistanceScale=j.optDouble("armDistanceScale",d.armDistanceScale),cropPadding=j.optDouble("cropPadding",d.cropPadding),
        maxActiveGates=j.optInt("maxActiveGates",d.maxActiveGates),contourMinArea=j.optDouble("contourMinArea",d.contourMinArea))
    fun isNewMethod(id:String?)=GateMethod.from(id)!=null
    fun activate(c:Context?,id:String?) { GateRuntime.configure(GateMethod.from(id)?.let { load(c,it) }) }
    fun showEditor(context:Context,id:String?) {
        val method=GateMethod.from(id)
        if(method==null) { Toast.makeText(context,"请先选择 V4-A/B/C",Toast.LENGTH_SHORT).show();return }
        val config=load(context,method);val json=encode(config)
        val fields=common+when(method) { GateMethod.OPTICAL_FLOW->optical;GateMethod.MOG2->mog;else->emptyList() }
        val layout=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(24,16,24,16) }
        layout.addView(TextView(context).apply { text="V4.1 不再把整幅视频缩成 480/640 后做视觉。当前约 2232 宽视频默认保留原始截图尺寸；所有门平时只以约 10fps 保存 1 秒局部历史，只有候选人体真正接触后的 1～2 扇门才运行差分/MOG2/LK。参数按方案独立保存。" })
        val edits=fields.associate { f ->
            layout.addView(TextView(context).apply { text="${f.title} [${f.min}..${f.max}]" })
            f.key to EditText(context).apply { inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;setText(json.get(f.key).toString());layout.addView(this) }
        }
        val dialog=AlertDialog.Builder(context).setTitle(method.label+" · Event ROI").setView(ScrollView(context).apply { addView(layout) })
            .setPositiveButton("保存",null).setNegativeButton("取消",null)
            .setNeutralButton("恢复本方案默认") { _,_->prefs(context).edit().remove(method.id).apply() }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            for(f in fields) {
                val value=edits.getValue(f.key).text.toString().toDoubleOrNull()
                if(value==null || !value.isFinite() || value !in f.min..f.max) {
                    edits.getValue(f.key).error="请输入范围内的数值";return@setOnClickListener
                }
                json.put(f.key,value)
            }
            prefs(context).edit().putString(method.id,encode(decode(config,json).checked()).toString()).apply();dialog.dismiss()
        } }
        dialog.show()
    }
}
