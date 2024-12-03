package com.spruceid.mobilesdkexample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.rive.runtime.kotlin.RiveAnimationView
import com.spruceid.mobilesdkexample.ui.theme.ColorBase800
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.Inter

@Composable
fun LoadingView(
    loadingText: String,
    cancelButtonLabel: String = "Cancel",
    onCancel: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 40.dp)
            .padding(horizontal = 30.dp)
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AndroidView(
                modifier = Modifier.size(60.dp),
                factory = { context ->
                    RiveAnimationView(context).also {
                        it.setRiveResource(
                            resId = R.raw.loading_spinner,
                        )
                    }
                }
            )
            Text(
                text = loadingText,
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                color = ColorBase800,
                fontSize = 20.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        if (onCancel != null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Button(
                    onClick = {
                        onCancel()
                    },
                    shape = RoundedCornerShape(5.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.Black,
                    ),
                    border = BorderStroke(1.dp, ColorStone300),
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text(
                        text = cancelButtonLabel,
                        fontFamily = Inter,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black,
                    )
                }
            }
        }
    }
}
