package com.example.roomxxx0102.ui.activities

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.roomxxx0102.R

class SplashActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var loadingAnimator: ValueAnimator? = null
    private val enterMainRunnable = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()
        setContentView(R.layout.activity_splash)
        startBreadcrumbLoading()
        handler.postDelayed(enterMainRunnable, SPLASH_DURATION_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(enterMainRunnable)
        loadingAnimator?.cancel()
        loadingAnimator = null
        super.onDestroy()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun startBreadcrumbLoading() {
        val dots = listOf<View>(
            findViewById(R.id.dotOne),
            findViewById(R.id.dotTwo),
            findViewById(R.id.dotThree)
        )
        loadingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                dots.forEachIndexed { index, dot ->
                    val phase = ((progress * dots.size) - index).coerceIn(0f, 1f)
                    val pulse = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
                    dot.alpha = 0.35f + 0.65f * pulse
                    val scale = 0.78f + 0.34f * pulse
                    dot.scaleX = scale
                    dot.scaleY = scale
                }
            }
            start()
        }
    }

    private companion object {
        const val SPLASH_DURATION_MS = 2_000L
    }
}
