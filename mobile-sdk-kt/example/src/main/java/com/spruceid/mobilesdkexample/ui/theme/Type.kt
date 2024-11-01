package com.spruceid.mobilesdkexample.ui.theme

import android.os.Build
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.R

val Inter =
    FontFamily(
        Font(R.font.inter_black, FontWeight.Black, FontStyle.Normal),
        Font(R.font.inter_black_italic, FontWeight.Black, FontStyle.Italic),
        Font(R.font.inter_extra_bold, FontWeight.ExtraBold, FontStyle.Normal),
        Font(R.font.inter_extra_bold_italic, FontWeight.ExtraBold, FontStyle.Italic),
        Font(R.font.inter_bold, FontWeight.Bold, FontStyle.Normal),
        Font(R.font.inter_bold_italic, FontWeight.Bold, FontStyle.Italic),
        Font(R.font.inter_semibold, FontWeight.SemiBold, FontStyle.Normal),
        Font(R.font.inter_semibold_italic, FontWeight.SemiBold, FontStyle.Italic),
        Font(R.font.inter_medium, FontWeight.Medium, FontStyle.Normal),
        Font(R.font.inter_medium_italic, FontWeight.Medium, FontStyle.Italic),
        Font(R.font.inter_regular, FontWeight.Normal, FontStyle.Normal),
        Font(R.font.inter_italic, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.inter_light, FontWeight.Light, FontStyle.Normal),
        Font(R.font.inter_light_italic, FontWeight.Light, FontStyle.Italic),
        Font(R.font.inter_extra_light, FontWeight.ExtraLight, FontStyle.Normal),
        Font(R.font.inter_extra_light_italic, FontWeight.ExtraLight, FontStyle.Italic),
        Font(R.font.inter_thin, FontWeight.Thin, FontStyle.Normal),
        Font(R.font.inter_thin_italic, FontWeight.Thin, FontStyle.Italic),
    )

object BodyMdDefault {
    const val WEIGHT = 500
}

val Switzer =
    FontFamily(
        Font(
            R.font.switzer_variable,
        )
    )


// Set of Material typography styles to start with
val Typography.bodyMdDefault: TextStyle
    @Composable
    get() {
        return TextStyle(
            fontFamily = Switzer,
            fontWeight = FontWeight.W500,
            fontSize = 18.sp,
            lineHeight = 27.sp
        )
    }

val Typography.bodyXsRegular: TextStyle
    @Composable
    get() {
        return TextStyle(
            fontFamily = Switzer,
            fontWeight = FontWeight.W400,
            fontSize = 16.sp,
            lineHeight = 24.sp
        )
    }

val Typography.headerH2: TextStyle
    @Composable
    get() {
        return TextStyle(
            fontFamily = Switzer,
            fontWeight = FontWeight.W500,
            fontSize = 24.sp,
            lineHeight = 30.sp
        )
    }

val Typography.buttonText: TextStyle
    @Composable
    get() {
        return TextStyle(
            fontFamily = Switzer,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 24.sp
        )
    }

val Typography.buttonTextSmall: TextStyle
    @Composable
    get() {
        return TextStyle(
            fontFamily = Switzer,
            fontWeight = FontWeight.W500,
            fontSize = 14.sp,
            lineHeight = 21.sp
        )
    }


//        bodyMd = TextStyle(
//            fontFamily = FontFamily.Default,
//            fontWeight = FontWeight.Normal,
//            fontSize = 16.sp,
//            lineHeight = 24.sp,
//            letterSpacing = 0.5.sp
//        )
//    )