from pathlib import Path

path = Path('app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt')
text = path.read_text(encoding='utf-8')

def replace_once(old: str, new: str, marker: str):
    global text
    if marker in text:
        return
    if old not in text:
        raise RuntimeError(f'anchor missing: {old[:120]!r}')
    text = text.replace(old, new, 1)

replace_once(
    'import com.example.roomxxx0102.logic.validation.MarkedEvent\n',
    'import com.example.roomxxx0102.logic.validation.MarkedEvent\nimport com.example.roomxxx0102.logic.validation.MarkedPortalInferenceOverlayBus\n',
    'import com.example.roomxxx0102.logic.validation.MarkedPortalInferenceOverlayBus'
)

replace_once(
'''    private val unlockTextPaint = Paint().apply {
        color = Color.LTGRAY
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
''',
'''    private val unlockTextPaint = Paint().apply {
        color = Color.LTGRAY
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val inferredPortalBannerPaint = Paint().apply {
        color = Color.parseColor("#D0003C4A")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val inferredPortalTextPaint = Paint().apply {
        color = Color.parseColor("#80DEEA")
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
''',
    'private val inferredPortalBannerPaint = Paint().apply'
)

# audio-only branch
replace_once(
'''            val markerRect = drawEventMarkerBar(canvas)
            drawUnlockBannerBelowMarker(canvas, markerRect, now)
            return
''',
'''            val markerRect = drawEventMarkerBar(canvas)
            drawUnlockBannerBelowMarker(canvas, markerRect, now)
            drawInferredPortalBanner(canvas, markerRect, now)
            return
''',
    'drawInferredPortalBanner(canvas, markerRect, now)\n            return'
)

# normal branch: this occurrence remains after audio branch was changed.
old = '''        val markerRect = drawEventMarkerBar(canvas)
        drawUnlockBannerBelowMarker(canvas, markerRect, now)

        if (showHandOnly) {
'''
new = '''        val markerRect = drawEventMarkerBar(canvas)
        drawUnlockBannerBelowMarker(canvas, markerRect, now)
        drawInferredPortalBanner(canvas, markerRect, now)

        if (showHandOnly) {
'''
if 'drawInferredPortalBanner(canvas, markerRect, now)\n\n        if (showHandOnly)' not in text:
    if old not in text:
        raise RuntimeError('normal draw anchor missing')
    text = text.replace(old, new, 1)

replace_once(
'''    override fun onTouchEvent(event: MotionEvent): Boolean {
''',
'''    private fun drawInferredPortalBanner(canvas: Canvas, markerRect: RectF?, nowMs: Long) {
        val message = MarkedPortalInferenceOverlayBus.snapshot(nowMs) ?: return
        val centerX = width / 2f
        val baseTop = (markerRect?.bottom ?: 30f) + 10f
        val top = if (isUnlockBannerVisible() && unlockBannerRect != null) {
            unlockBannerRect!!.bottom + 8f
        } else {
            baseTop
        }
        val bannerHeight = 44f
        val textPadding = 24f
        val desiredWidth = inferredPortalTextPaint.measureText(message) + textPadding * 2f
        val bannerWidth = desiredWidth.coerceIn(220f, width * 0.9f)
        val left = centerX - bannerWidth / 2f
        val right = centerX + bannerWidth / 2f
        val bottom = top + bannerHeight

        canvas.drawRoundRect(left, top, right, bottom, 10f, 10f, inferredPortalBannerPaint)
        val fm = inferredPortalTextPaint.fontMetrics
        val baseline = top + (bannerHeight - (fm.bottom - fm.top)) / 2f - fm.top
        canvas.drawText(message, centerX, baseline, inferredPortalTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
''',
    'private fun drawInferredPortalBanner(canvas: Canvas, markerRect: RectF?, nowMs: Long)'
)

path.write_text(text, encoding='utf-8')
print('patched DetectionOverlayView.kt')
