/**
 * gauss_iir.cpp - IIR 递归高斯模糊实现
 * 
 * 系数公式来源：Deriche (1993) 递归高斯滤波器
 * 
 * 核心思想：
 * 高斯核 G(x) = exp(-x²/2σ²) 可以近似为 IIR 滤波器：
 *   y[n] = a0*x[n] + a1*x[n-1] - b1*y[n-1] - b2*y[n-2]  (前向)
 *   y[n] = a2*x[n+1] + a3*x[n+2] - b1*y[n+1] - b2*y[n+2] (后向)
 * 
 * 参数化（Deriche 稳定版本）：
 *   alpha = 1.695 / σ
 *   ema = exp(-alpha)
 *   ema2 = ema * ema
 *   b1 = -2 * ema
 *   b2 = ema2
 *   k = (1 - ema)² / (1 + 2*alpha*ema - ema2)
 *   a0 = k
 *   a1 = k * ema * (alpha - 1)
 *   a2 = k * ema * (alpha + 1)
 *   a3 = -k * ema2
 * 
 * 数值稳定性：
 * - 使用双精度计算系数，单精度执行滤波
 * - alpha 范围 [0.034, 1.695]，对应 σ ∈ [1, 50]
 * - 边界条件采用稳态增益补偿，避免振铃
 * 
 * 时间复杂度：O(W×H)，每像素约 12 次乘法 + 8 次加法（单通道）
 * 空间复杂度：O(max(W,H))，仅需一维工作缓冲
 */

#include "gauss_iir.h"
#include <cmath>
#include <cstring>
#include <algorithm>
#include <memory>
#include <new>
#include <android/log.h>

#define LOG_TAG "GaussIIR"
// 热路径日志（JNI 入口每帧调用）：默认编译为空操作，避免每帧字符串
// 格式化 + 日志 I/O 的固定开销；排查问题时定义 NATIVEGAUSS_VERBOSE 打开
#ifdef NATIVEGAUSS_VERBOSE
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#endif
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace gauss_color {
namespace {
float to_linear(float value) {
    return value <= 0.04045f ? value / 12.92f : std::pow((value + 0.055f) / 1.055f, 2.4f);
}

float to_srgb(float value) {
    return value <= 0.0031308f ? value * 12.92f : 1.055f * std::pow(value, 1.0f / 2.4f) - 0.055f;
}
}

TransferTables::TransferTables() {
    for (int index = 0; index <= TABLE_SIZE; ++index) {
        const float value = static_cast<float>(index) / TABLE_SIZE;
        decoded[index] = to_linear(value);
        encoded[index] = to_srgb(value);
    }
    for (int index = 0; index < 256; ++index) {
        decodedOpaque[index] = to_linear(index / 255.0f);
    }
    // 高密度表直接输出不透明的 8 位 sRGB，半透明通道仍在线性光中按 Alpha 加权。
    for (int index = 0; index <= OPAQUE_ENCODE_SIZE; ++index) {
        const float value = static_cast<float>(index) / OPAQUE_ENCODE_SIZE;
        encodedOpaque[index] = static_cast<uint8_t>(interpolate(encoded, value) * 255.0f + 0.5f);
    }
}

const TransferTables& transfer_tables() {
    static const TransferTables tables;
    return tables;
}
}

// Deriche 系数结构
struct DericheCoeffs {
    float a0, a1, a2, a3; // 前向/后向系数
    float b1, b2;         // 递归系数
    float coefp, coefn;   // 边界增益补偿
};

/**
 * 计算 Deriche IIR 滤波器系数
 * 
 * @param sigma 高斯标准差
 * @return 滤波器系数
 */
static DericheCoeffs compute_deriche_coeffs(float sigma) {
    DericheCoeffs c;
    
    // Deriche 参数化（双精度计算）
    double alpha = 1.695 / sigma;
    double ema = exp(-alpha);
    double ema2 = ema * ema;
    
    // 递归系数
    c.b1 = static_cast<float>(-2.0 * ema);
    c.b2 = static_cast<float>(ema2);
    
    // 归一化因子
    double k = (1.0 - ema) * (1.0 - ema) / (1.0 + 2.0 * alpha * ema - ema2);
    
    // 前向/后向系数
    c.a0 = static_cast<float>(k);
    c.a1 = static_cast<float>(k * ema * (alpha - 1.0));
    c.a2 = static_cast<float>(k * ema * (alpha + 1.0));
    c.a3 = static_cast<float>(-k * ema2);
    
    // 边界增益补偿（稳态响应）
    c.coefp = static_cast<float>((c.a0 + c.a1) / (1.0 + c.b1 + c.b2));
    c.coefn = static_cast<float>((c.a2 + c.a3) / (1.0 + c.b1 + c.b2));
    
    return c;
}

/**
 * 一维 IIR 递归滤波（单通道）
 * 
 * @param src 源数据
 * @param dst 目标数据
 * @param len 数据长度
 * @param c 滤波器系数
 */
static void iir_filter_1d(const float* src, float* dst, int len, const DericheCoeffs& c) {
    if (len <= 0) return;
    
    // 前向递归（causal）
    float yp1 = src[0] * c.coefp; // 稳态初始化
    float yp2 = yp1;
    float xp1 = src[0];

    for (int i = 0; i < len; ++i) {
        float xc = src[i];
        float yc = c.a0 * xc + c.a1 * xp1 - c.b1 * yp1 - c.b2 * yp2;

        dst[i] = yc; // 暂存前向结果

        xp1 = xc;
        yp2 = yp1; yp1 = yc;
    }
    
    // 后向递归（anti-causal）
    float yn1 = src[len - 1] * c.coefn; // 稳态初始化
    float yn2 = yn1;
    float xn1 = src[len - 1];
    float xn2 = xn1;
    
    for (int i = len - 1; i >= 0; --i) {
        float xc = src[i];
        float yc = c.a2 * xn1 + c.a3 * xn2 - c.b1 * yn1 - c.b2 * yn2;
        
        dst[i] += yc; // 累加后向结果
        
        xn2 = xn1; xn1 = xc;
        yn2 = yn1; yn1 = yc;
    }
}

static void filter_channels(float* channels, int len, const DericheCoeffs& coeffs) {
    // 反向递归仍需读取原始样本，四个通道依次复用同一输出缓冲。
    float* filtered = channels + 4 * len;
    for (int channel = 0; channel < 4; ++channel) {
        float* samples = channels + channel * len;
        iir_filter_1d(samples, filtered, len, coeffs);
        std::copy_n(filtered, len, samples);
    }
}

/**
 * 横向模糊（处理所有行）
 */
static void blur_horizontal(
    uint8_t* base,
    int w, int h, int stride,
    const DericheCoeffs& c,
    float* buffer,
    bool doLinear
) {
    for (int y = 0; y < h; ++y) {
        uint8_t* row = base + y * stride;
        
        // 加载到浮点缓冲（分离 RGBA 通道）
        for (int x = 0; x < w; ++x) {
            uint8_t b = row[x * 4 + 0];
            uint8_t g = row[x * 4 + 1];
            uint8_t r = row[x * 4 + 2];
            uint8_t a = row[x * 4 + 3];
            
            float fa = a / 255.0f;
            float fr = r / 255.0f;
            float fg = g / 255.0f;
            float fb = b / 255.0f;
            
            if (doLinear) {
                fr = gauss_color::decode(fr, fa);
                fg = gauss_color::decode(fg, fa);
                fb = gauss_color::decode(fb, fa);
            }
            
            buffer[x] = fr;
            buffer[w + x] = fg;
            buffer[2 * w + x] = fb;
            buffer[3 * w + x] = fa;
        }
        
        // 对每个通道执行 IIR 滤波
        filter_channels(buffer, w, c);
        
        // 写回像素
        for (int x = 0; x < w; ++x) {
            float fr = buffer[x];
            float fg = buffer[w + x];
            float fb = buffer[2 * w + x];
            float fa = buffer[3 * w + x];
            
            // 钳位
            fa = std::max(0.0f, std::min(1.0f, fa));
            
            if (doLinear) {
                // Linear→sRGB + 再预乘
                fr = gauss_color::encode(fr, fa);
                fg = gauss_color::encode(fg, fa);
                fb = gauss_color::encode(fb, fa);
            }
            
            // 钳位并转换为 uint8
            int r = static_cast<int>(std::max(0.0f, std::min(255.0f, fr * 255.0f + 0.5f)));
            int g = static_cast<int>(std::max(0.0f, std::min(255.0f, fg * 255.0f + 0.5f)));
            int b = static_cast<int>(std::max(0.0f, std::min(255.0f, fb * 255.0f + 0.5f)));
            int a = static_cast<int>(fa * 255.0f + 0.5f);
            
            row[x * 4 + 0] = static_cast<uint8_t>(b);
            row[x * 4 + 1] = static_cast<uint8_t>(g);
            row[x * 4 + 2] = static_cast<uint8_t>(r);
            row[x * 4 + 3] = static_cast<uint8_t>(a);
        }
    }
}

/**
 * 纵向模糊（处理所有列）
 */
static void blur_vertical(
    uint8_t* base,
    int w, int h, int stride,
    const DericheCoeffs& c,
    float* buffer,
    bool doLinear
) {
    for (int x = 0; x < w; ++x) {
        // 加载列到浮点缓冲
        for (int y = 0; y < h; ++y) {
            uint8_t* pixel = base + y * stride + x * 4;
            uint8_t b = pixel[0];
            uint8_t g = pixel[1];
            uint8_t r = pixel[2];
            uint8_t a = pixel[3];
            
            float fa = a / 255.0f;
            float fr = r / 255.0f;
            float fg = g / 255.0f;
            float fb = b / 255.0f;
            
            if (doLinear) {
                fr = gauss_color::decode(fr, fa);
                fg = gauss_color::decode(fg, fa);
                fb = gauss_color::decode(fb, fa);
            }
            
            buffer[y] = fr;
            buffer[h + y] = fg;
            buffer[2 * h + y] = fb;
            buffer[3 * h + y] = fa;
        }
        
        // 对每个通道执行 IIR 滤波
        filter_channels(buffer, h, c);
        
        // 写回列
        for (int y = 0; y < h; ++y) {
            float fr = buffer[y];
            float fg = buffer[h + y];
            float fb = buffer[2 * h + y];
            float fa = buffer[3 * h + y];
            
            fa = std::max(0.0f, std::min(1.0f, fa));
            
            if (doLinear) {
                fr = gauss_color::encode(fr, fa);
                fg = gauss_color::encode(fg, fa);
                fb = gauss_color::encode(fb, fa);
            }
            
            int r = static_cast<int>(std::max(0.0f, std::min(255.0f, fr * 255.0f + 0.5f)));
            int g = static_cast<int>(std::max(0.0f, std::min(255.0f, fg * 255.0f + 0.5f)));
            int b = static_cast<int>(std::max(0.0f, std::min(255.0f, fb * 255.0f + 0.5f)));
            int a = static_cast<int>(fa * 255.0f + 0.5f);
            
            uint8_t* pixel = base + y * stride + x * 4;
            pixel[0] = static_cast<uint8_t>(b);
            pixel[1] = static_cast<uint8_t>(g);
            pixel[2] = static_cast<uint8_t>(r);
            pixel[3] = static_cast<uint8_t>(a);
        }
    }
}

// 主入口函数
void gaussian_iir_rgba8888_inplace(
    uint8_t* base,
    int w, int h, int stride,
    float sigma,
    bool doLinear
) {
    // 参数校验
    if (!base || w <= 0 || h <= 0 || stride < w * 4) {
        LOGE("Invalid parameters: base=%p, w=%d, h=%d, stride=%d", base, w, h, stride);
        return;
    }
    
    // sigma 太小，直接返回
    if (!std::isfinite(sigma) || sigma <= 0.1f) {
        return;
    }
    
    // sigma 太大，钳位到安全范围
    if (sigma > 50.0f) {
        LOGD("Sigma %.2f too large, clamping to 50.0", sigma);
        sigma = 50.0f;
    }
    
    // 计算滤波器系数
    DericheCoeffs c = compute_deriche_coeffs(sigma);
    
    // 四个输入通道和一个可复用的输出通道。
    const size_t maxDim = std::max(w, h);
    std::unique_ptr<float[]> buffer(new (std::nothrow) float[maxDim * 5]);
    if (!buffer) {
        LOGE("Unable to allocate IIR work buffer");
        return;
    }
    
    // 横向模糊
    blur_horizontal(base, w, h, stride, c, buffer.get(), doLinear);
    
    // 纵向模糊
    blur_vertical(base, w, h, stride, c, buffer.get(), doLinear);
}

// 便捷函数
void gaussian_iir_rgba8888_fast(uint8_t* base, int w, int h, int stride, float sigma) {
    gaussian_iir_rgba8888_inplace(base, w, h, stride, sigma, false);
}

void gaussian_iir_rgba8888_quality(uint8_t* base, int w, int h, int stride, float sigma) {
    gaussian_iir_rgba8888_inplace(base, w, h, stride, sigma, true);
}

