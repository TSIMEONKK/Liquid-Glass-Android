package com.example.liquidglass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 渲染路径矩阵：
 * - Android 13+：透镜、GPU 模糊、GPU 色差、CPU
 * - Android 12/12L：GPU 模糊、CPU
 * - Android 12 以下：CPU
 * - 软件画布和自定义捕获必须回退 CPU
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class RenderPathMatrixTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun belowAndroid13SelectsSupportedGpuOrCpu() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        withGlass { scenario, glass ->
            // 模拟库的默认配置：API 31/32 默认跳过位移贴图，只要求模糊；
            // 此时不能因为“色差开关仍为 true”误判成必须回退 CPU。
            onMain(scenario) { glass.enableChromaticAberration = true }
            val expected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "GPU Blur" else "None"
            assertEquals(expected, awaitEffect(scenario, glass, expected))

            // 关闭硬件路径后必须落到 CPU，而不是继续引用旧 GPU 渲染器。
            onMain(scenario) { glass.useHardwareBlurWhenPossible = false }
            assertEquals("None", awaitEffect(scenario, glass, "None"))

            // 显式开启经典色差后，API 31/32 与更低版本都应落到 CPU 并真正执行色差。
            onMain(scenario) { glass.skipMapGenOnApi33 = false }
            assertEquals(
                "Aberration",
                awaitEffect(scenario, glass, "Aberration", timeoutMs = 15000L)
            )

            // 物理色散同样只能由 CPU 管线交付。
            onMain(scenario) {
                glass.enableChromaticAberration = false
                glass.enableChromaticDispersion = true
            }
            assertEquals("Dispersion", awaitEffect(scenario, glass, "Dispersion"))

            // API 钳制只能降低路径，不能在高版本设备上伪造 Android 12 的能力。
            onMain(scenario) {
                glass.enableChromaticDispersion = false
                glass.skipMapGenOnApi33 = true
                glass.useHardwareBlurWhenPossible = true
                glass.debugApiLevelCap = Build.VERSION_CODES.R
            }
            assertEquals("None", awaitEffect(scenario, glass, "None"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 回到 API 31/32 的经典 GPU 层级，默认仍应保留 GPU 模糊。
                onMain(scenario) {
                    glass.debugApiLevelCap = Build.VERSION_CODES.S
                    glass.useShaderPipeline = false
                    glass.enableChromaticAberration = true
                }
                assertEquals("GPU Blur", awaitEffect(scenario, glass, "GPU Blur"))
            }
        }
    }

    @Test
    fun android13AndAboveSelectsLensGpuAndCpuWithoutStalePath() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        withGlass { scenario, glass ->
            // 默认：Android 13+ 的透镜路径。
            assertEquals("GPU Lens", awaitEffect(scenario, glass, "GPU Lens"))

            // 关闭透镜后切到 API 31+ 的 RenderEffect 模糊路径。
            onMain(scenario) { glass.useShaderPipeline = false }
            assertEquals("GPU Blur", awaitEffect(scenario, glass, "GPU Blur"))

            // 再关闭硬件路径，必须真正落到 CPU。
            onMain(scenario) { glass.useHardwareBlurWhenPossible = false }
            assertEquals("None", awaitEffect(scenario, glass, "None"))

            // 在 Android 13+ 上把能力钳到 Android 12L（API 32）和 Android 12（API 31）。
            onMain(scenario) {
                glass.useHardwareBlurWhenPossible = true
                glass.useShaderPipeline = true
                glass.debugApiLevelCap = Build.VERSION_CODES.S_V2
            }
            assertEquals("GPU Blur", awaitEffect(scenario, glass, "GPU Blur"))

            onMain(scenario) { glass.debugApiLevelCap = Build.VERSION_CODES.S }
            assertEquals("GPU Blur", awaitEffect(scenario, glass, "GPU Blur"))

            // 在 Android 13+ 上钳制到 API 32，默认跳过位移贴图时仍应保留 GPU 模糊，
            // 不能因为默认打开色差就错误回退 CPU。
            onMain(scenario) {
                glass.debugApiLevelCap = Build.VERSION_CODES.S_V2
                glass.useShaderPipeline = false
                glass.enableChromaticAberration = true
                glass.skipMapGenOnApi33 = true
            }
            assertEquals("GPU Blur", awaitEffect(scenario, glass, "GPU Blur"))

            // 显式要求经典色差后，API 32 层级没有 RuntimeShader，必须走 CPU。
            onMain(scenario) { glass.skipMapGenOnApi33 = false }
            assertEquals(
                "Aberration",
                awaitEffect(scenario, glass, "Aberration", timeoutMs = 15000L)
            )

            // 钳到 API 30 以下，RenderEffect 不可用，只能走 CPU。
            onMain(scenario) {
                glass.debugApiLevelCap = Build.VERSION_CODES.R
                glass.enableChromaticAberration = false
            }
            assertEquals("None", awaitEffect(scenario, glass, "None"))
        }
    }

    @Test
    fun android13GpuAberrationUsesDisplacementMapsAndFallsBackWithoutThem() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        withGlass { scenario, glass ->
            onMain(scenario) {
                glass.enableChromaticAberration = false
                glass.useShaderPipeline = false
                glass.skipMapGenOnApi33 = true
            }
            assertEquals("GPU Blur", awaitEffect(scenario, glass, "GPU Blur"))

            // 位移贴图被跳过时，色差没有可交付内容，只应保持 GPU 模糊。
            onMain(scenario) { glass.enableChromaticAberration = true }
            assertEquals("GPU Blur", awaitEffect(scenario, glass, "GPU Blur"))

            // 开启贴图后，异步完成前保持 GPU 模糊，完成后自动升级到 GPU 色差。
            onMain(scenario) {
                glass.skipMapGenOnApi33 = false
            }
            assertEquals(
                "GPU Blur+CA",
                awaitEffect(scenario, glass, "GPU Blur+CA", timeoutMs = 15000L)
            )
        }
    }

    @Test
    fun softwareCanvasAndCustomCaptureForceCpu() {
        withGlass { scenario, glass ->
            val bitmap = Bitmap.createBitmap(240, 96, Bitmap.Config.ARGB_8888)
            try {
                scenario.onActivity {
                    glass.enableChromaticAberration = false
                    glass.draw(Canvas(bitmap))
                    assertFalse(
                        "软件截图不能误走硬件绘制",
                        glass.lastFrameStats?.effectName?.startsWith("GPU") == true
                    )
                }

                scenario.onActivity {
                    glass.setCustomBackdropCapture { bitmap }
                    glass.useHardwareBlurWhenPossible = true
                    glass.useShaderPipeline = true
                    glass.debugApiLevelCap = Int.MAX_VALUE
                }
                assertEquals("None", awaitEffect(scenario, glass, "None"))
            } finally {
                onMain(scenario) { glass.setCustomBackdropCapture { null } }
                bitmap.recycle()
            }
        }
    }

    private fun withGlass(check: (ActivityScenario<GlassTestActivity>, LiquidGlassView) -> Unit) {
        ActivityScenario.launch(GlassTestActivity::class.java).use { scenario ->
            lateinit var glass: LiquidGlassView
            scenario.onActivity { activity ->
                val root = FrameLayout(activity).apply {
                    setBackgroundColor(Color.WHITE)
                }
                glass = LiquidGlassView(activity).apply {
                    enableAdaptiveTint = false
                    enableDynamicBackground = false
                    enableEdgeHighlight = false
                    enableShadow = false
                    enableChromaticAberration = false
                }
                root.addView(
                    glass,
                    FrameLayout.LayoutParams(240, 96, android.view.Gravity.CENTER)
                )
                activity.setContentView(root)
            }
            try {
                check(scenario, glass)
            } finally {
                scenario.onActivity { glass.setCustomBackdropCapture { null } }
            }
        }
    }

    private fun awaitEffect(
        scenario: ActivityScenario<GlassTestActivity>,
        glass: LiquidGlassView,
        expected: String,
        timeoutMs: Long = 10000L
    ): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var actual: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { glass.invalidate() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(50L)
            scenario.onActivity { actual = glass.lastFrameStats?.effectName }
            if (actual == expected) return actual!!
        }
        fail("Expected render path $expected, but last frame was $actual")
        error("unreachable")
    }

    private fun onMain(
        scenario: ActivityScenario<GlassTestActivity>,
        block: () -> Unit
    ) {
        scenario.onActivity { block() }
    }
}
