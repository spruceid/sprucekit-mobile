package com.spruceid.mobilesdkexample

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spruceid.mobilesdkexample.ui.theme.ColorBase150
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.MobileSdkTheme

@Composable
fun Loader() {
    Box(
        Modifier
            .background(Color.White),
    ) {
        Column(
            Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceAround,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
            ) {
                Indicator()
            }
        }
    }
}

@Composable
fun Indicator(
    // indicator size
    size: Dp = 107.dp,
    // angle (length) of indicator arc
    sweepAngle: Float = 90f,
    color: Color = ColorBase150,
    strokeWidth: Dp = 6.dp,
) {
    val transition = rememberInfiniteTransition("Infinite loader indicator")

    val currentArcStartAngle by transition.animateValue(
        0,
        360,
        Int.VectorConverter,
        infiniteRepeatable(
            animation =
            tween(
                durationMillis = 1100,
                easing = LinearEasing,
            ),
        ),
        "Infinite loader animation",
    )

    val stroke =
        with(LocalDensity.current) {
            Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        }

    // draw on canvas
    Canvas(
        Modifier
            .progressSemantics()
            .size(size)
            .padding(strokeWidth / 2),
    ) {
        drawCircle(ColorBlue600, style = stroke)
        drawArc(
            color,
            // arc start angle
            // -90 shifts the start position towards the y-axis
            startAngle = currentArcStartAngle.toFloat() - 90,
            sweepAngle = sweepAngle,
            useCenter = false,
            style = stroke,
        )
    }
}


@Preview(showBackground = true)
@Composable
fun LoaderPreview() {
    MobileSdkTheme {
        Loader()
    }
}