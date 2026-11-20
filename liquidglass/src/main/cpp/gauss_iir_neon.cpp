/**
 * gauss_iir_neon.cpp - IIR 递归高斯模糊 NEON 向量化实现
 * 
 * 实现细节：
 * - 使用 ARM NEON intrinsics (arm_neon.h)
 * - 向量化处理 RGBA 4 个通道
 * - 优化内存访问和寄存器使用
 * 
 * 编译要求：
 * - ARMv7: -mfpu=neon -mfloat-abi=softfp
 * - ARMv8: -march=armv8-a (默认支持 NEON)
 */

#include "gauss_iir_neon.h"
#include "gauss_iir.h"
#include <cmath>
#include <cstring>
#include <algorithm>
#include <memory>
#include <new>
#include <android/log.h>

// NEON intrinsics
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define NEON_AVAILABLE 1
#else
#define NEON_AVAILABLE 0
#endif

#define LOG_TAG "GaussIIR_NEON"
// 热路径日志（JNI 入口每帧调用）：默认编译为空操作，避免每帧字符串
// 格式化 + 日志 I/O 的固定开销；排查问题时定义 NATIVEGAUSS_VERBOSE 打开
#ifdef NATIVEGAUSS_VERBOSE
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#endif
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if NEON_AVAILABLE
// Deriche 系数结构（与标量版本相同）
struct DericheCoeffs {
    float a0, a1, a2, a3;
    float b1, b2;
    float coefp, coefn;
};

static DericheCoeffs compute_deriche_coeffs(float sigma) {
    DericheCoeffs c;
    
    double alpha = 1.695 / sigma;
    double ema = exp(-alpha);
    double ema2 = ema * ema;
    
    c.b1 = static_cast<float>(-2.0 * ema);
    c.b2 = static_cast<float>(ema2);
    
    double k = (1.0 - ema) * (1.0 - ema) / (1.0 + 2.0 * alpha * ema - ema2);
    
    c.a0 = static_cast<float>(k);
    c.a1 = static_cast<float>(k * ema * (alpha - 1.0));
    c.a2 = static_cast<float>(k * ema * (alpha + 1.0));
    c.a3 = static_cast<float>(-k * ema2);
    
    c.coefp = static_cast<float>((c.a0 + c.a1) / (1.0 + c.b1 + c.b2));
    c.coefn = static_cast<float>((c.a2 + c.a3) / (1.0 + c.b1 + c.b2));
    
    return c;
}

/**
 * 一维 IIR 递归滤波（NEON 向量化版本）
 * 
 * 处理 4 个通道（RGBA），使用 NEON 并行计算
 */
static void iir_filter_1d_neon(
    const float32x4_t* __restrict src, float32x4_t* __restrict dst, int len, const DericheCoeffs& c
) {
    if (len <= 0) return;
    
    // 加载系数到 NEON 寄存器
    float32x4_t va0 = vdupq_n_f32(c.a0);
    float32x4_t va1 = vdupq_n_f32(c.a1);
    float32x4_t va2 = vdupq_n_f32(c.a2);
    float32x4_t va3 = vdupq_n_f32(c.a3);
    float32x4_t vb1 = vdupq_n_f32(c.b1);
    float32x4_t vb2 = vdupq_n_f32(c.b2);
    float32x4_t vcoefp = vdupq_n_f32(c.coefp);
    float32x4_t vcoefn = vdupq_n_f32(c.coefn);
    
    // 前向递归（causal）
    float32x4_t vxp1 = src[0];
    float32x4_t vyp1 = vmulq_f32(vxp1, vcoefp);
    float32x4_t vyp2 = vyp1;
    
    for (int i = 0; i < len; ++i) {
        float32x4_t vxc = src[i];
        
        // yc = a0*xc + a1*xp1 - b1*yp1 - b2*yp2
        float32x4_t vyc = vmulq_f32(va0, vxc);
        vyc = vmlaq_f32(vyc, va1, vxp1);
        vyc = vmlsq_f32(vyc, vb1, vyp1);
        vyc = vmlsq_f32(vyc, vb2, vyp2);
        
        dst[i] = vyc;
        
        vxp1 = vxc;
        vyp2 = vyp1;
        vyp1 = vyc;
    }
    
    // 后向递归（anti-causal）
    float32x4_t vxn1 = src[len - 1];
    float32x4_t vxn2 = vxn1;  // 初始化 xn2
    float32x4_t vyn1 = vmulq_f32(vxn1, vcoefn);
    float32x4_t vyn2 = vyn1;

    for (int i = len - 1; i >= 0; --i) {
        float32x4_t vxc = src[i];

        // yc = a2*xn1 + a3*xn2 - b1*yn1 - b2*yn2
        float32x4_t vyc = vmulq_f32(va2, vxn1);
        vyc = vmlaq_f32(vyc, va3, vxn2);
        vyc = vmlsq_f32(vyc, vb1, vyn1);
        vyc = vmlsq_f32(vyc, vb2, vyn2);

        // 累加前向和后向结果
        dst[i] = vaddq_f32(dst[i], vyc);

        vxn2 = vxn1;
        vxn1 = vxc;
        vyn2 = vyn1;
        vyn1 = vyc;
    }
}

static inline uint32_t load_pixel(const uint8_t* pixel) {
    uint32_t bytes;
    std::memcpy(&bytes, pixel, sizeof(bytes));
    return bytes;
}

static inline float32x4_t unpack_pixel(uint32_t bytes) {
    const uint8x8_t packed = vreinterpret_u8_u32(vdup_n_u32(bytes));
    const uint16x4_t channels = vget_low_u16(vmovl_u8(packed));
    return vcvtq_f32_u32(vmovl_u16(channels));
}

static inline void store_pixel(uint8_t* pixel, float32x4_t channels) {
    const auto rounded = vaddq_f32(channels, vdupq_n_f32(0.5f));
    const auto half = vqmovn_u32(vcvtq_u32_f32(rounded));
    const auto packed = vqmovn_u16(vcombine_u16(half, half));
    const uint32_t bytes = vget_lane_u32(vreinterpret_u32_u8(packed), 0);
    std::memcpy(pixel, &bytes, sizeof(bytes));
}

template<bool Linear>
static void blur_lines_neon(
    uint8_t* base, int length, int lines, size_t pixelStep, size_t lineStep,
    const DericheCoeffs& coeffs, float32x4_t* samples
) {
    // 输入与前向结果分开，后向递归可完整读取原始样本。
    float32x4_t* filtered = samples + length;
    const auto* tables = Linear ? &gauss_color::transfer_tables() : nullptr;
    for (int line = 0; line < lines; ++line) {
        uint8_t* start = base + line * lineStep;
        uint32_t alphaBits = 0xFF000000;
        for (int index = 0; index < length; ++index) {
            const uint32_t bytes = load_pixel(start + index * pixelStep);
            if constexpr (Linear) {
                alphaBits &= bytes;
                const uint32_t alphaByte = bytes >> 24;
                const uint32_t first = bytes & 0xFF;
                const uint32_t second = (bytes >> 8) & 0xFF;
                const uint32_t third = (bytes >> 16) & 0xFF;
                if (alphaByte == 255) {
                    samples[index] = {
                        tables->decodedOpaque[first], tables->decodedOpaque[second],
                        tables->decodedOpaque[third], 1.0f
                    };
                } else if (alphaByte != 0) {
                    const float alpha = alphaByte / 255.0f;
                    const float inverse = 1.0f / alphaByte;
                    samples[index] = {
                        gauss_color::interpolate(tables->decoded, first * inverse) * alpha,
                        gauss_color::interpolate(tables->decoded, second * inverse) * alpha,
                        gauss_color::interpolate(tables->decoded, third * inverse) * alpha,
                        alpha
                    };
                } else {
                    samples[index] = vdupq_n_f32(0.0f);
                }
            } else {
                samples[index] = unpack_pixel(bytes);
            }
        }
        iir_filter_1d_neon(samples, filtered, length, coeffs);
        if constexpr (Linear) {
            if (alphaBits == 0xFF000000) {
                // 不透明行的 Alpha 经过归一化滤波后仍为 1，可直接查 8 位输出。
                const auto upper = vdupq_n_f32(1.0f);
                const auto zero = vdupq_n_f32(0.0f);
                for (int index = 0; index < length; ++index) {
                    const auto clamped = vminq_f32(vmaxq_f32(filtered[index], zero), upper);
                    const auto scaled = vmulq_n_f32(clamped, gauss_color::OPAQUE_ENCODE_SIZE);
                    const auto entries = vcvtq_u32_f32(vaddq_f32(scaled, vdupq_n_f32(0.5f)));
                    const uint32_t bytes =
                        static_cast<uint32_t>(tables->encodedOpaque[vgetq_lane_u32(entries, 0)]) |
                        (static_cast<uint32_t>(tables->encodedOpaque[vgetq_lane_u32(entries, 1)]) << 8) |
                        (static_cast<uint32_t>(tables->encodedOpaque[vgetq_lane_u32(entries, 2)]) << 16) |
                        0xFF000000;
                    std::memcpy(start + index * pixelStep, &bytes, sizeof(bytes));
                }
                continue;
            }
        }
        for (int index = 0; index < length; ++index) {
            auto channels = filtered[index];
            if constexpr (Linear) {
                const float alpha = std::clamp(vgetq_lane_f32(channels, 3), 0.0f, 1.0f);
                if (alpha > 0.0f) {
                    const float inverse = 1.0f / alpha;
                    const float scaledAlpha = alpha * 255.0f;
                    channels = {
                        gauss_color::interpolate(tables->encoded, vgetq_lane_f32(channels, 0) * inverse) * scaledAlpha,
                        gauss_color::interpolate(tables->encoded, vgetq_lane_f32(channels, 1) * inverse) * scaledAlpha,
                        gauss_color::interpolate(tables->encoded, vgetq_lane_f32(channels, 2) * inverse) * scaledAlpha,
                        scaledAlpha
                    };
                } else {
                    channels = vdupq_n_f32(0.0f);
                }
            }
            store_pixel(start + index * pixelStep, channels);
        }
    }
}

#endif // NEON_AVAILABLE

// 公共接口实现

bool has_neon_support() {
#if NEON_AVAILABLE
    // 编译时检测：如果编译了 NEON 代码，则设备必然支持 NEON
    // 因为 Android 构建系统会为不同 ABI 生成不同的库
    return true;
#else
    return false;
#endif
}

void gaussian_iir_rgba8888_neon(
    uint8_t* base,
    int w,
    int h,
    int stride,
    float sigma,
    bool doLinear
) {
#if NEON_AVAILABLE
    if (!std::isfinite(sigma) || sigma <= 0.1f ||
        w <= 0 || h <= 0 || !base || stride / 4 < w) {
        return;
    }
    
    DericheCoeffs c = compute_deriche_coeffs(std::min(sigma, 50.0f));
    
    const size_t maxDim = std::max(w, h);
    std::unique_ptr<float32x4_t[]> workBuf(new (std::nothrow) float32x4_t[maxDim * 2]);
    if (!workBuf) {
        LOGE("Unable to allocate NEON IIR work buffer");
        return;
    }
    if (doLinear) {
        blur_lines_neon<true>(base, w, h, 4, stride, c, workBuf.get());
        blur_lines_neon<true>(base, h, w, stride, 4, c, workBuf.get());
    } else {
        blur_lines_neon<false>(base, w, h, 4, stride, c, workBuf.get());
        blur_lines_neon<false>(base, h, w, stride, 4, c, workBuf.get());
    }
    
    LOGD("NEON blur: %dx%d, sigma=%.2f, linear=%d", w, h, sigma, doLinear);
#else
    LOGE("NEON not available at compile time");
#endif
}

void gaussian_iir_rgba8888_neon_fast(
    uint8_t* base,
    int w,
    int h,
    int stride,
    float sigma
) {
    gaussian_iir_rgba8888_neon(base, w, h, stride, sigma, false);
}

void gaussian_iir_rgba8888_neon_quality(
    uint8_t* base,
    int w,
    int h,
    int stride,
    float sigma
) {
    gaussian_iir_rgba8888_neon(base, w, h, stride, sigma, true);
}

