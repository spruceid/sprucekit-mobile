package com.spruceid.mobilesdkexample.wallet.wallethomeview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader

/**
 * Creates an elliptical radial gradient shader that emanates from the top center
 * @param radiusYFactor The vertical radius as a factor of height (e.g., 0.95 for 95% of height)
 */
fun createEllipticalGradientShader(
    size: Size,
    colors: List<Color>,
    radiusYFactor: Float = 0.95f
): Shader {
    val centerX = size.width * 0.5f  // 50% horizontal center
    val centerY = 0f                 // 0% (top)
    val radiusX = size.width * 1.0f  // 100% of width
    val radiusY = size.height * radiusYFactor

    // Create transformation matrix to make ellipse
    val matrix = android.graphics.Matrix()
    matrix.preScale(1f, radiusY / radiusX)
    matrix.preTranslate(0f, -centerY * (radiusX / radiusY - 1f))

    val shader = RadialGradientShader(
        center = Offset(centerX, centerY * radiusX / radiusY),
        radius = radiusX,
        colors = colors,
        colorStops = listOf(0f, 1f)
    )
    shader.setLocalMatrix(matrix)
    return shader
}