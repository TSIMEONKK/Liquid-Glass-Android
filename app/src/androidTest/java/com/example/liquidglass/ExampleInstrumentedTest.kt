package com.example.liquidglass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.liquidglass", appContext.packageName)
    }

    @Test
    fun cpuBackdropReusesIdenticalFramesAndDetectsEdgeChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val blurRecomputed = mutableListOf<Boolean?>()
        var reusedBackdropWasRecycled = false
        instrumentation.runOnMainSync {
            val size = 64
            val glass = LiquidGlassView(instrumentation.targetContext).apply {
                useHardwareBlurWhenPossible = false
                enableChromaticAberration = false
                enableAdaptiveTint = false
                layout(0, 0, size, size)
            }
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            var backdrop = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            glass.setCustomBackdropCapture { backdrop }

            // 先重复使用同一张图，再换成像素相同的新图，最后验证边缘和原地修改。
            glass.draw(canvas)
            blurRecomputed += glass.lastFrameStats?.blurRecomputed
            glass.draw(canvas)
            blurRecomputed += glass.lastFrameStats?.blurRecomputed
            backdrop.recycle()
            backdrop = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            glass.draw(canvas)
            blurRecomputed += glass.lastFrameStats?.blurRecomputed
            backdrop.recycle()
            backdrop = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                setPixel(size - 1, size - 1, Color.WHITE)
            }
            glass.draw(canvas)
            blurRecomputed += glass.lastFrameStats?.blurRecomputed
            backdrop.setPixel(size - 1, size - 1, Color.BLACK)
            glass.draw(canvas)
            blurRecomputed += glass.lastFrameStats?.blurRecomputed
            reusedBackdropWasRecycled = backdrop.isRecycled
            backdrop.recycle()
            output.recycle()
        }

        assertEquals(listOf(true, false, false, true, true), blurRecomputed)
        assertFalse(reusedBackdropWasRecycled)
    }

    @Test
    fun cpuBackdropCanReuseDownsampledInputAndToggleBlur() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val blurRecomputed = mutableListOf<Boolean?>()
        val processedWidths = mutableListOf<Int?>()
        var backdropWasRecycled = false
        instrumentation.runOnMainSync {
            val size = 64
            val glass = LiquidGlassView(instrumentation.targetContext).apply {
                useHardwareBlurWhenPossible = false
                enableChromaticAberration = false
                enableAdaptiveTint = false
                globalDownsampleFactor = 0.5f
                layout(0, 0, size, size)
            }
            val backdrop = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            glass.setCustomBackdropCapture { backdrop }

            // 全程复用同一张原图，背景静止时重新开启模糊也要触发重算。
            for (blur in listOf(true, true, false, true)) {
                glass.enableBackdropBlur = blur
                glass.draw(canvas)
                blurRecomputed += glass.lastFrameStats?.blurRecomputed
                processedWidths += glass.lastFrameStats?.processedWidth
            }
            backdrop.eraseColor(Color.WHITE)
            glass.draw(canvas)
            blurRecomputed += glass.lastFrameStats?.blurRecomputed
            backdropWasRecycled = backdrop.isRecycled
            backdrop.recycle()
            output.recycle()
        }

        assertEquals(listOf(true, false, false, true, true), blurRecomputed)
        assertEquals(listOf(32, 32, 32, 32), processedWidths)
        assertFalse(backdropWasRecycled)
    }

    @Test
    fun cpuDispersionHandlesSmallAndRgb565Backdrops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val stats = mutableListOf<LiquidGlassView.FrameStats?>()
        instrumentation.runOnMainSync {
            val glass = LiquidGlassView(instrumentation.targetContext).apply {
                useHardwareBlurWhenPossible = false
                enableBackdropBlur = false
                enableChromaticDispersion = true
                globalDownsampleFactor = 0.25f
                dispersionFactor = 0.5f
                layout(0, 0, 64, 64)
            }
            val output = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            // 窄图连续降采样仍可绘制，随后用无缩放的 RGB_565 覆盖 JNI 格式转换。
            val narrow = Bitmap.createBitmap(3, 16, Bitmap.Config.ARGB_8888)
            val rgb565 = Bitmap.createBitmap(16, 16, Bitmap.Config.RGB_565)
            try {
                glass.setCustomBackdropCapture { narrow }
                glass.draw(canvas)
                stats += glass.lastFrameStats
                glass.globalDownsampleFactor = 1f
                glass.dispersionDownsample = 1f
                glass.setCustomBackdropCapture { rgb565 }
                glass.draw(canvas)
                stats += glass.lastFrameStats
            } finally {
                narrow.recycle()
                rgb565.recycle()
                output.recycle()
            }
        }

        assertEquals(listOf(1, 16), stats.map { it?.processedWidth })
        assertTrue(stats.all { it?.effectName == "Dispersion" && it.effectRecomputed })
    }

    @Test
    fun nativeDispersionAcceptsInvalidRefractionAndNonFiniteOffsets() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val edges = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.WHITE)
            source.setPixel(0, 0, Color.RED)
            edges.eraseColor(Color.BLACK)
            // 直接调用 JNI 绕过 Kotlin 参数处理，覆盖两种插值方式。
            for (bilinear in listOf(true, false)) {
                NativeChromaticDispersion.chromaticDispersionInplace(
                    source, edges, null, output, 100f, 0.5f, 7f, 1f, bilinear
                )
                assertEquals(Color.WHITE, output.getPixel(4, 4))
                NativeChromaticDispersion.chromaticDispersionInplace(
                    source, edges, null, output, 100f, 0.5f, Float.NaN, 1f, bilinear
                )
                assertEquals(Color.RED, output.getPixel(4, 4))
            }
        } finally {
            source.recycle()
            edges.recycle()
            output.recycle()
        }
    }

    @Test
    fun customCaptureCanResizeGlassWithoutUsingRecycledCache() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var stats: LiquidGlassView.FrameStats? = null
        instrumentation.runOnMainSync {
            val glass = LiquidGlassView(instrumentation.targetContext).apply {
                useHardwareBlurWhenPossible = false
                enableChromaticAberration = false
                skipMapGenOnApi33 = true
                layout(0, 0, 32, 32)
            }
            val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val target = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(target)
            var resize = false
            glass.setCustomBackdropCapture {
                // 第二次捕获同步触发尺寸变化，此时上一帧缓存会被清空。
                if (resize) glass.layout(0, 0, 64, 64)
                source
            }
            try {
                glass.draw(canvas)
                resize = true
                glass.draw(canvas)
                stats = glass.lastFrameStats
            } finally {
                source.recycle()
                target.recycle()
            }
        }

        assertEquals(32, stats?.processedWidth)
        assertEquals(true, stats?.blurRecomputed)
    }

    @Test
    fun rapidResizeOnlyPublishesDisplacementForFinalSize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var glass: LiquidGlassView? = null
        val backdrop = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        try {
            instrumentation.runOnMainSync {
                glass = LiquidGlassView(instrumentation.targetContext).apply {
                    useHardwareBlurWhenPossible = false
                    enableBackdropBlur = false
                    enableAdaptiveTint = false
                    skipMapGenOnApi33 = false
                    setCustomBackdropCapture { backdrop }
                    // 同一次主线程回调内覆盖多个大尺寸请求，最后恢复小尺寸。
                    for (size in 4096 downTo 3600 step 32) layout(0, 0, size, size)
                    layout(0, 0, 96, 64)
                }
            }
            val resized = checkNotNull(glass)
            val deadline = SystemClock.uptimeMillis() + 5000L
            do {
                instrumentation.runOnMainSync { resized.draw(Canvas(output)) }
                if (resized.lastFrameStats?.effectRecomputed == true) break
                SystemClock.sleep(20)
            } while (SystemClock.uptimeMillis() < deadline)
            assertEquals("Aberration", resized.lastFrameStats?.effectName)
            assertEquals(true, resized.lastFrameStats?.effectRecomputed)
            assertEquals(96, resized.lastFrameStats?.processedWidth)
        } finally {
            instrumentation.runOnMainSync {
                glass?.skipMapGenOnApi33 = true
                backdrop.recycle()
                output.recycle()
            }
        }
    }

    @Test
    fun cpuBackdropDrawsNonPremultipliedInputAtBothScales() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val size = 64
            val color = Color.argb(128, 220, 72, 32)
            val original = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                setPremultiplied(false)
                eraseColor(color)
            }
            val reference = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
            }
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val glass = LiquidGlassView(instrumentation.targetContext).apply {
                useHardwareBlurWhenPossible = false
                enableBackdropBlur = false
                enableChromaticAberration = false
                enableAdaptiveTint = false
                layout(0, 0, size, size)
            }
            try {
                for (factor in listOf(1f, 0.5f)) {
                    glass.globalDownsampleFactor = factor
                    // 先绘制非预乘输入，再以同色预乘位图校验实际输出像素。
                    glass.setCustomBackdropCapture { original }
                    glass.draw(Canvas(output))
                    assertEquals((size * factor).toInt(), glass.lastFrameStats?.processedWidth)
                    val actual = output.getPixel(size / 2, size / 2)

                    output.eraseColor(Color.TRANSPARENT)
                    glass.setCustomBackdropCapture { reference }
                    glass.draw(Canvas(output))
                    assertEquals(output.getPixel(size / 2, size / 2), actual)
                    output.eraseColor(Color.TRANSPARENT)
                }
                assertFalse(original.isRecycled)
                assertFalse(original.isPremultiplied)
                assertEquals(color, original.getPixel(0, 0))
            } finally {
                original.recycle()
                reference.recycle()
                output.recycle()
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun cpuBackdropCanDrawHardwareInputWithoutBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var processedWidth: Int? = null
        var hardwareWasRecycled = false
        instrumentation.runOnMainSync {
            val size = 64
            val glass = LiquidGlassView(instrumentation.targetContext).apply {
                useHardwareBlurWhenPossible = false
                enableBackdropBlur = false
                enableChromaticAberration = false
                layout(0, 0, size, size)
            }
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val source = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            val hardware = checkNotNull(source.copy(Bitmap.Config.HARDWARE, false))
            source.recycle()
            glass.setCustomBackdropCapture { hardware }

            // 关闭模糊时，结果层会直接使用背景快照，必须仍能绘制到软件画布。
            glass.draw(Canvas(output))
            processedWidth = glass.lastFrameStats?.processedWidth
            hardwareWasRecycled = hardware.isRecycled
            hardware.recycle()
            output.recycle()
        }

        assertEquals(40, processedWidth)
        assertFalse(hardwareWasRecycled)
    }

    @Test
    fun dispersionCacheInvalidatesWhenCornerShapeChanges() {
        val size = 64
        val source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size) { index ->
            Color.rgb((index % size) * 4, (index / size) * 4, 128)
        }
        source.setPixels(pixels, 0, size, 0, 0, size, size)

        val reusedEffect = ChromaticDispersionEffect()
        val squareEffect = ChromaticDispersionEffect()
        val rounded = reusedEffect.apply(
            source = source,
            refThickness = 16f,
            refFactor = 1.5f,
            refDispersion = 8f,
            downscale = 1f,
            cornerRadius = 28f
        )
        val afterCornerChange = reusedEffect.apply(
            source = source,
            refThickness = 16f,
            refFactor = 1.5f,
            refDispersion = 8f,
            downscale = 1f,
            cornerRadius = 0f
        )
        val freshSquare = squareEffect.apply(
            source = source,
            refThickness = 16f,
            refFactor = 1.5f,
            refDispersion = 8f,
            downscale = 1f,
            cornerRadius = 0f
        )

        try {
            // 圆角变化后应重建边缘贴图，结果必须与全新实例一致。
            assertTrue(afterCornerChange.sameAs(freshSquare))
        } finally {
            source.recycle()
            rounded.recycle()
            afterCornerChange.recycle()
            freshSquare.recycle()
        }
    }
}
