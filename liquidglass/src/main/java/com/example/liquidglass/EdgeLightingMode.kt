package com.example.liquidglass

/**
 * API 33+ 透镜管线的边缘光照风格。
 *
 * [IOS_BALANCED] 以左上—右下对角线为轴，在两个对角位置绘制等强白色折射边；
 * [PHYSICAL] 保留原有的单向主光、弱回光与背光侧内阴影，便于已有界面维持原观感。
 */
enum class EdgeLightingMode(internal val shaderValue: Float) {
    /** iOS 风格：对角双白边与克制的对称内侧阴影。 */
    IOS_BALANCED(0f),

    /** 原有物理单光源风格：迎光侧更亮，背光侧只有弱回光。 */
    PHYSICAL(1f)
}
