package com.spruceid.mobilesdkexample.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Switzer
import com.spruceid.mobilesdkexample.wallet.wallethomeview.createEllipticalGradientShader

data class HeaderButton(
    val icon: Painter,
    val contentDescription: String,
    val onClick: () -> Unit
)

@Composable
fun HomeHeader(
    title: String,
    gradientColors: List<Color>,
    buttons: List<HeaderButton> = emptyList()
) {
    var boxWidth by remember { mutableIntStateOf(0) }
    var boxHeight by remember { mutableIntStateOf(0) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged {
                boxWidth = it.width
                boxHeight = it.height
                boxSize = it
            }
            .background(
                brush = if (boxSize != IntSize.Zero) {
                    object : ShaderBrush() {
                        override fun createShader(size: Size): Shader {
                            return createEllipticalGradientShader(
                                size = size,
                                colors = gradientColors,
                                radiusYFactor = 0.95f
                            )
                        }
                    }
                } else {
                    // Fallback gradient
                    Brush.radialGradient(
                        colors = gradientColors,
                        center = Offset(0f, 0f),
                        radius = 1f
                    )
                }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 50.dp, bottom = 40.dp)
                .padding(horizontal = 26.dp)
        ) {
            // SpruceKit title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.spruce_logo),
                    contentDescription = "SpruceID Logo",
                    tint = ColorStone950,
                    modifier = Modifier.size(21.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SpruceKit",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = ColorStone950
                )
            }

            Spacer(modifier = Modifier.height(30.dp))

            // Title and buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    color = ColorStone950,
                    modifier = Modifier.weight(1f)
                )

                // Dynamic buttons
                buttons.forEachIndexed { index, button ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.3f))
                            .border(
                                width = 0.5.dp,
                                color = Color.White,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { button.onClick() }
                            .padding(6.dp)
                    ) {
                        Icon(
                            painter = button.icon,
                            contentDescription = button.contentDescription,
                            tint = ColorStone950,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}