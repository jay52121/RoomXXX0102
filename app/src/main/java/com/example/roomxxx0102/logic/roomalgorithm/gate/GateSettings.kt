package com.example.roomxxx0102.logic.roomalgorithm.gate

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.widget.*
import com.example.roomxxx0102.data.repository.AppSettings
import org.json.JSONObject

/** Per-engine parameter namespace. Changing a parameter changes Registry.configurationKey. */
object GateSettings {
    private fun prefs(context:Context)=context.getSharedPreferences("gate_engines",Context.MODE_PRIVATE)
    internal fun read(context:Context?,method:GateMethod):GateParams {
        val d=GateParams.defaults(method)
        if(context==null) return d
        return try {
            val j=JSONObject(prefs(context).getString("params:${method.id}","{}")!!)
            d.copy(captureEdge=j.optInt("captureEdge",d.captureEdge),sampleHz=j.optInt("sampleHz",d.sampleHz),
                imageEdge=j.optInt("imageEdge",d.imageEdge),frameGapMs=j.optLong("frameGapMs",d.frameGapMs),
                differenceThreshold=j.optInt("differenceThreshold",d.differenceThreshold),warmupMs=j.optLong("warmupMs",d.warmupMs),
                backgroundRate=j.optDouble("backgroundRate",d.backgroundRate),contactHeight=j.optDouble("contactHeight",d.contactHeight),
                crossedHoldMs=j.optLong("crossedHoldMs",d.crossedHoldMs),vanishedHoldMs=j.optLong("vanishedHoldMs",d.vanishedHoldMs),
                coastMs=j.optLong("coastMs",d.coastMs),minOwnedCells=j.optInt("minOwnedCells",d.minOwnedCells),
                restoredFraction=j.optDouble("restoredFraction",d.restoredFraction),minForegroundPixels=j.optInt("minForegroundPixels",d.minForegroundPixels),
                footConfidence=j.optDouble("footConfidence",d.footConfidence),admissionMs=j.optLong("admissionMs",d.admissionMs),
                pointsPerPerson=j.optInt("pointsPerPerson",d.pointsPerPerson),maxPoints=j.optInt("maxPoints",d.maxPoints),
                pyramidLevel=j.optInt("pyramidLevel",d.pyramidLevel),lkWindow=j.optInt("lkWindow",d.lkWindow),
                fbError=j.optDouble("fbError",d.fbError),workBudgetMs=j.optLong("workBudgetMs",d.workBudgetMs),
                mogHistory=j.optInt("mogHistory",d.mogHistory),mogVariance=j.optDouble("mogVariance",d.mogVariance)).validated()
        } catch(_:Exception) { d }
    }
    private fun json(p:GateParams)=JSONObject().apply {
        put("captureEdge",p.captureEdge);put("sampleHz",p.sampleHz);put("imageEdge",p.imageEdge);put("frameGapMs",p.frameGapMs)
        put("differenceThreshold",p.differenceThreshold);put("warmupMs",p.warmupMs);put("backgroundRate",p.backgroundRate)
        put("contactHeight",p.contactHeight);put("crossedHoldMs",p.crossedHoldMs);put("vanishedHoldMs",p.vanishedHoldMs)
        put("coastMs",p.coastMs);put("minOwnedCells",p.minOwnedCells);put("restoredFraction",p.restoredFraction)
        put("minForegroundPixels",p.minForegroundPixels);put("footConfidence",p.footConfidence);put("admissionMs",p.admissionMs)
        put("pointsPerPerson",p.pointsPerPerson);put("maxPoints",p.maxPoints);put("pyramidLevel",p.pyramidLevel)
        put("lkWindow",p.lkWindow);put("fbError",p.fbError);put("workBudgetMs",p.workBudgetMs)
        put("mogHistory",p.mogHistory);put("mogVariance",p.mogVariance)
    }
    private fun save(context:Context,method:GateMethod,p:GateParams) {
        prefs(context).edit().putString("params:${method.id}",json(p.validated()).toString()).apply()
        Toast.makeText(context,"\u53c2\u6570\u5df2\u4fdd\u5b58\uff0c\u8bf7\u4ece\u89c6\u9891\u5f00\u5934\u91cd\u653e",Toast.LENGTH_LONG).show()
    }
    internal fun key(context:Context?,method:GateMethod)="${method.id}|${read(context,method).hashCode()}"

    fun show(context:Context) {
        val method=GateMethod.fromId(AppSettings.roomAlgorithmId)
        if(method==null) {
            AlertDialog.Builder(context).setTitle("\u95e8\u53e3\u5b9e\u9a8c")
                .setMessage("\u8bf7\u5148\u5728\u623f\u95f4\u7b97\u6cd5\u4e2d\u9009\u62e9\u5dee\u5206\u3001\u5149\u6d41\u6216 MOG2\u3002")
                .setPositiveButton("\u77e5\u9053\u4e86",null).show();return
        }
        val options=arrayOf("\u9ed8\u8ba4\u53c2\u6570","\u4fdd\u5b88\uff1a\u51cf\u5c11\u8bef\u62a5","\u6027\u80fd\u4f18\u5148","\u81ea\u5b9a\u4e49\u53c2\u6570","\u91cd\u5efa\u80cc\u666f\uff08\u4fdd\u7559\u4eba\u6570\uff09","\u5bfc\u51fa\u6700\u8fd1\u8bca\u65ad")
        AlertDialog.Builder(context).setTitle(method.title).setItems(options) { _,which ->
            val d=GateParams.defaults(method)
            when(which) {
                0 -> save(context,method,d)
                1 -> save(context,method,d.copy(crossedHoldMs=200,vanishedHoldMs=400,restoredFraction=0.94,minOwnedCells=6))
                2 -> save(context,method,d.copy(imageEdge=384,pointsPerPerson=96,maxPoints=192,lkWindow=15,workBudgetMs=16))
                3 -> edit(context,method)
                4 -> { GateRuntime.requestBackgroundReset(); Toast.makeText(context,"\u80cc\u666f\u5c06\u5728\u95e8\u53e3\u7a7a\u95f2\u65f6\u91cd\u65b0\u5b66\u4e60",Toast.LENGTH_LONG).show() }
                5 -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT,"Gate diagnostics").putExtra(Intent.EXTRA_TEXT,GateRuntime.diagnostics()),"\u5bfc\u51fa\u8bca\u65ad"))
            }
        }.setNegativeButton("\u5173\u95ed",null).show()
    }
    private fun edit(context:Context,method:GateMethod) {
        val params=read(context,method)
        val values=json(params)
        val specs=linkedMapOf(
            "sampleHz" to "\u91c7\u6837\u4e0a\u9650 Hz (5-30)",
            "captureEdge" to "\u622a\u56fe\u957f\u8fb9 (640-2560)",
            "imageEdge" to "\u89c6\u89c9\u957f\u8fb9 (256-960)",
            "frameGapMs" to "\u5141\u8bb8\u5e27\u95f4\u9694 ms (100-600)",
            "differenceThreshold" to "\u5dee\u5206\u7070\u5ea6\u9608\u503c (8-60)",
            "warmupMs" to "\u7a7a\u95f2\u80cc\u666f\u5efa\u7acb ms (300-4000)",
            "backgroundRate" to "\u80cc\u666f\u5b66\u4e60\u7387 (0.001-0.1)",
            "contactHeight" to "\u95e8\u69db\u63a5\u89e6\u5e26 / \u4eba\u9ad8 (0.04-0.18)",
            "crossedHoldMs" to "\u8de8\u95e8\u786e\u8ba4 ms (60-600)",
            "vanishedHoldMs" to "\u6d88\u5931\u786e\u8ba4 ms (120-800)",
            "coastMs" to "\u906e\u6321\u8bc1\u636e\u6709\u6548\u671f ms (500-2000)",
            "minOwnedCells" to "\u4eba\u4f53\u8986\u76d6\u6700\u5c11\u683c\u6570 (3-12)",
            "restoredFraction" to "\u80cc\u666f\u6062\u590d\u6bd4\u4f8b (0.75-0.98)",
            "minForegroundPixels" to "\u6700\u5c11\u524d\u666f\u50cf\u7d20 (4-80)",
            "footConfidence" to "\u811a\u70b9\u7f6e\u4fe1\u9608\u503c (0.45-0.9)",
            "admissionMs" to "\u771f\u4eba\u51c6\u5165\u89c2\u5bdf ms (120-1000)",
            "workBudgetMs" to "\u89c6\u89c9\u8f6f\u9884\u7b97 ms (8-60)"
        )
        if(method==GateMethod.OPTICAL_FLOW) specs.putAll(linkedMapOf(
            "pointsPerPerson" to "\u5355\u4eba\u5149\u6d41\u70b9 (48-320)","maxPoints" to "\u5168\u5c40\u70b9\u6570 (96-640)",
            "pyramidLevel" to "LK maxLevel (1-3)","lkWindow" to "LK \u7a97\u53e3\u5947\u6570 (11-31)","fbError" to "\u53cc\u5411\u8bef\u5dee\u50cf\u7d20 (0.5-3.0)"))
        if(method==GateMethod.MOG2) specs.putAll(linkedMapOf("mogHistory" to "MOG2 \u5386\u53f2\u5e27 (30-600)","mogVariance" to "MOG2 \u65b9\u5dee\u9608\u503c (8-64)"))
        val layout=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(28,16,28,16) }
        layout.addView(TextView(context).apply { text="\u53ea\u5f71\u54cd\u5f53\u524d\u65b9\u6848\uff1b\u4e0d\u4fee\u6539 YOLO \u9608\u503c\u3002\u6bcf\u6b21\u53ea\u8c03\u4e00\u7ec4\uff0c\u4fdd\u5b58\u540e\u4ece\u5934\u91cd\u653e\u3002\u589e\u5927\u63a5\u89e6\u5e26\u6216\u964d\u4f4e\u6062\u590d\u6bd4\u4f8b\u4f1a\u589e\u52a0\u8bef\u62a5\u98ce\u9669\u3002" })
        val fields=specs.mapValues { (key,label) ->
            layout.addView(TextView(context).apply { text=label })
            EditText(context).apply { inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(values.get(key).toString()); layout.addView(this) }
        }
        val dialog=AlertDialog.Builder(context).setTitle(method.title).setView(ScrollView(context).apply { addView(layout) })
            .setPositiveButton("\u4fdd\u5b58",null).setNegativeButton("\u53d6\u6d88",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var valid=true
                fields.forEach { (key,field) ->
                    val number=field.text.toString().toDoubleOrNull()
                    if(number==null||!number.isFinite()) { field.error="\u8bf7\u586b\u5199\u6709\u6548\u6570\u5b57";valid=false }
                    else values.put(key,number)
                }
                if(valid) {
                    prefs(context).edit().putString("params:${method.id}",values.toString()).apply()
                    save(context,method,read(context,method)); dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
