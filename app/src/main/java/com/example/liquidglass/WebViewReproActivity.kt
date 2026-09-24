package com.example.liquidglass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.liquidglass.demo.R

/** 用静态原生 WebView 对照玻璃取样与 WebView 自身的绘制次数。 */
class WebViewReproActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "GlassWebViewRepro"
    }

    private lateinit var webView: CountingWebView
    private lateinit var glass: CountingGlassView
    private lateinit var counters: TextView
    private lateinit var glassToggle: Button
    private lateinit var backgroundToggle: Button
    private lateinit var samplingToggle: Button
    private lateinit var scrollToggle: Button
    private val handler = Handler(Looper.getMainLooper())
    private var lastWebDraws = 0
    private var lastGlassDraws = 0
    private var transparentBackground = false
    private var samplingEnabled = true
    private var autoScrolling = false
    private var scrollDirection = 1
    private var captureTotalMs = 0f
    private var captureMaxMs = 0f
    private var captureSamples = 0

    private val autoScroll = object : Runnable {
        override fun run() {
            if (!autoScrolling) return
            val previousY = webView.scrollY
            webView.scrollBy(0, scrollDirection * dp(3))
            if (webView.scrollY == previousY) scrollDirection = -scrollDirection
            webView.postOnAnimation(this)
        }
    }

    private val report = object : Runnable {
        override fun run() {
            val webDraws = webView.drawCount
            val glassDraws = glass.drawCount
            val webDelta = webDraws - lastWebDraws
            val glassDelta = glassDraws - lastGlassDraws
            lastWebDraws = webDraws
            lastGlassDraws = glassDraws

            // 统计控件位于背景来源之外，不参与玻璃对 WebView 的取样。
            counters.text = getString(R.string.webview_repro_counters, webDelta, glassDelta)
            Log.i(
                TAG,
                "webDraw=$webDelta glassDraw=$glassDelta glassVisible=${glass.visibility == View.VISIBLE} " +
                    "sampling=$samplingEnabled transparent=$transparentBackground " +
                    "autoScroll=$autoScrolling scrollY=${webView.scrollY} " +
                    "captureAvgMs=${if (captureSamples > 0) captureTotalMs / captureSamples else 0f} " +
                    "captureMaxMs=$captureMaxMs " +
                    "webHash=${System.identityHashCode(webView).toString(16)}"
            )
            captureTotalMs = 0f
            captureMaxMs = 0f
            captureSamples = 0
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF2F2F7.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(16) + bars.bottom)
            insets
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.webview_repro_title)
            textSize = 22f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(8))
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.webview_repro_instructions)
            textSize = 14f
            setTextColor(0xFF333333.toInt())
        })

        counters = TextView(this).apply {
            text = getString(R.string.webview_repro_counters, 0, 0)
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(counters)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        glassToggle = Button(this).apply {
            text = getString(R.string.webview_repro_hide_glass)
            setOnClickListener {
                glass.visibility = if (glass.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = getString(
                    if (glass.visibility == View.VISIBLE) R.string.webview_repro_hide_glass
                    else R.string.webview_repro_show_glass
                )
            }
        }
        controls.addView(glassToggle, LinearLayout.LayoutParams(0, dp(52), 1f))

        backgroundToggle = Button(this).apply {
            text = getString(R.string.webview_repro_transparent)
            setOnClickListener {
                transparentBackground = !transparentBackground
                loadStaticPage()
                text = getString(
                    if (transparentBackground) R.string.webview_repro_opaque
                    else R.string.webview_repro_transparent
                )
            }
        }
        controls.addView(backgroundToggle, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(controls)

        samplingToggle = Button(this).apply {
            text = getString(R.string.webview_repro_disable_sampling)
            setOnClickListener {
                samplingEnabled = !samplingEnabled
                // 不透明降级路径会跳过背景捕获，可区分“玻璃存在”与“取样 WebView”。
                glass.accessibilityMode = if (samplingEnabled) {
                    GlassAccessibilityMode.FORCE_FULL
                } else {
                    GlassAccessibilityMode.FORCE_OPAQUE
                }
                text = getString(
                    if (samplingEnabled) R.string.webview_repro_disable_sampling
                    else R.string.webview_repro_enable_sampling
                )
            }
        }
        root.addView(samplingToggle, LinearLayout.LayoutParams(-1, dp(52)))

        scrollToggle = Button(this).apply {
            text = getString(R.string.webview_repro_start_scroll)
            setOnClickListener {
                autoScrolling = !autoScrolling
                if (autoScrolling) {
                    scrollDirection = 1
                    webView.postOnAnimation(autoScroll)
                } else {
                    webView.removeCallbacks(autoScroll)
                }
                text = getString(
                    if (autoScrolling) R.string.webview_repro_stop_scroll
                    else R.string.webview_repro_start_scroll
                )
            }
        }
        // 固定速度往返滚动，便于观察滚动期间和停止后的绘制计数。
        root.addView(scrollToggle, LinearLayout.LayoutParams(-1, dp(52)))

        val stage = FrameLayout(this).apply {
            setBackgroundColor(0xFFCFD8E4.toInt())
            clipChildren = false
            clipToPadding = false
        }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(12)
        })

        webView = CountingWebView(this).apply {
            // 页面没有脚本、计时器或网络请求，便于区分 WebView 与玻璃引发的绘制。
            settings.javaScriptEnabled = false
            setBackgroundColor(Color.WHITE)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    // 网页内容真正可绘制后通知玻璃更新局部背景。
                    view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            glass.invalidate()
                        }
                    })
                }
            }
        }
        stage.addView(webView, FrameLayout.LayoutParams(-1, -1))

        glass = CountingGlassView(this).apply {
            enableDynamicBackground = false
            enableAdaptiveTint = false
            enableSensorHighlight = false
            accessibilityMode = GlassAccessibilityMode.FORCE_FULL
            cornerRadius = dp(28).toFloat()
            // 模拟业务页：背景来源是同时包含 WebView 与玻璃的父容器。
            backdropSource = stage
            frameStatsListener = { stats ->
                // 仅累计每帧局部取样耗时，统计打印由页面每秒完成一次。
                if (stats.captureMs > 0f) {
                    captureTotalMs += stats.captureMs
                    captureMaxMs = maxOf(captureMaxMs, stats.captureMs)
                    captureSamples++
                }
            }
            isClickable = true
            addView(TextView(this@WebViewReproActivity).apply {
                text = "+"
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, FrameLayout.LayoutParams(-1, -1))
        }
        stage.addView(glass, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(180)
            rightMargin = dp(48)
        })

        setContentView(root)
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = true
        loadStaticPage()
    }

    private fun loadStaticPage() {
        val pageColor = if (transparentBackground) "transparent" else "#ffffff"
        webView.setBackgroundColor(if (transparentBackground) Color.TRANSPARENT else Color.WHITE)
        webView.loadDataWithBaseURL(
            null,
            """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;background:$pageColor;color:#202b3a;font-family:sans-serif">
              <div style="padding:24px"><h2>Static WebView</h2>
              <p>Tap the glass +, then leave the screen still.</p>
              <div style="height:180px;background:repeating-linear-gradient(45deg,#6daee7 0 12px,#edf5fc 12px 24px)"></div>
              <p>No JavaScript, animation, timer, or network request.</p></div>
              <div style="height:1800px;background:linear-gradient(#e6f1ff,#3564a0)"></div>
            </body></html>
            """.trimIndent(),
            "text/html",
            "UTF-8",
            null
        )
    }

    override fun onResume() {
        super.onResume()
        lastWebDraws = webView.drawCount
        lastGlassDraws = glass.drawCount
        handler.postDelayed(report, 1000L)
    }

    override fun onPause() {
        autoScrolling = false
        webView.removeCallbacks(autoScroll)
        scrollToggle.text = getString(R.string.webview_repro_start_scroll)
        handler.removeCallbacks(report)
        super.onPause()
    }

    override fun onDestroy() {
        (webView.parent as? FrameLayout)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class CountingWebView(context: Context) : WebView(context) {
        var drawCount = 0
            private set

        override fun onDraw(canvas: Canvas) {
            drawCount++
            super.onDraw(canvas)
        }
    }

    private class CountingGlassView(context: Context) : LiquidGlassView(context) {
        var drawCount = 0
            private set

        override fun onDraw(canvas: Canvas) {
            drawCount++
            super.onDraw(canvas)
        }
    }
}
