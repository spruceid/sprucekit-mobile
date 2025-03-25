package com.spruceid.mobilesdkexample.utils

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ui.theme.ColorAmber200
import com.spruceid.mobilesdkexample.ui.theme.ColorAmber50
import com.spruceid.mobilesdkexample.ui.theme.ColorAmber900
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald200
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald50
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald900
import com.spruceid.mobilesdkexample.ui.theme.ColorRose200
import com.spruceid.mobilesdkexample.ui.theme.ColorRose50
import com.spruceid.mobilesdkexample.ui.theme.ColorRose900
import com.spruceid.mobilesdkexample.ui.theme.Inter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ToastType {
    SUCCESS,
    WARNING,
    ERROR
}

object Toast {
    private val toastMessage = mutableStateOf<String?>(null)
    private val toastType = mutableStateOf(ToastType.SUCCESS)

    fun showSuccess(message: String) {
        toastType.value = ToastType.SUCCESS
        toastMessage.value = message
    }

    fun showWarning(message: String) {
        toastType.value = ToastType.WARNING
        toastMessage.value = message
    }

    fun showError(message: String) {
        toastType.value = ToastType.ERROR
        toastMessage.value = message
    }

    @Composable
    fun Host(
        duration: Long = 3000L,
        onDismiss: () -> Unit = { toastMessage.value = null }
    ) {
        val scope = rememberCoroutineScope()
        val currentMessage = rememberUpdatedState(toastMessage.value)

        currentMessage.value?.let { message ->
            LaunchedEffect(message) {
                scope.launch {
                    delay(duration)
                    onDismiss()
                }
            }
            Box(
                modifier = Modifier
                    .wrapContentHeight()
                    .padding(all = 20.dp)
                    .padding(top = 20.dp)
            ) {
                when (toastType.value) {
                    ToastType.SUCCESS -> SuccessToast(message = message)
                    ToastType.WARNING -> WarningToast(message = message)
                    ToastType.ERROR -> ErrorToast(message = message)
                }
            }
        }
    }
}

@Composable
fun SuccessToast(
    message: String,
) {
    Box(
        modifier = Modifier
            .wrapContentHeight()
            .background(
                color = ColorEmerald50,
                shape = RoundedCornerShape(6.dp)
            )
            .border(
                width = 1.dp,
                color = ColorEmerald200,
                shape = RoundedCornerShape(6.dp)
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.success_toast_icon),
                contentDescription = stringResource(id = R.string.success_toast_icon),
                modifier = Modifier
                    .width(20.dp)
                    .height(20.dp)
            )
            Text(
                text = message,
                color = ColorEmerald900,
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}

@Composable
fun WarningToast(
    message: String,
) {
    Box(
        modifier = Modifier
            .wrapContentHeight()
            .background(
                color = ColorAmber50,
                shape = RoundedCornerShape(6.dp)
            )
            .border(
                width = 1.dp,
                color = ColorAmber200,
                shape = RoundedCornerShape(6.dp)
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.warning_toast_icon),
                contentDescription = stringResource(id = R.string.warning_toast_icon),
                modifier = Modifier
                    .width(20.dp)
                    .height(20.dp)
            )
            Text(
                text = message,
                color = ColorAmber900,
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}

@Composable
fun ErrorToast(
    message: String,
) {
    Box(
        modifier = Modifier
            .wrapContentHeight()
            .background(
                color = ColorRose50,
                shape = RoundedCornerShape(6.dp)
            )
            .border(
                width = 1.dp,
                color = ColorRose200,
                shape = RoundedCornerShape(6.dp)
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.error_toast_icon),
                contentDescription = stringResource(id = R.string.error_toast_icon),
                modifier = Modifier
                    .width(20.dp)
                    .height(20.dp)
            )
            Text(
                text = message,
                color = ColorRose900,
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}