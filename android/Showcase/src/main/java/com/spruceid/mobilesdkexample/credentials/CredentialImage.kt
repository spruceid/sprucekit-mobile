package com.spruceid.mobilesdkexample.credentials

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.spruceid.mobilesdkexample.utils.BitmapImage
import com.spruceid.mobilesdkexample.utils.jsonArrayToByteArray
import org.json.JSONArray

@Composable
fun CredentialImage(image: String, alt: String) {
    if (image.startsWith("https://")) {
        AsyncImage(
            model = image,
            contentDescription = alt,
            modifier = Modifier
                .width(50.dp)
                .padding(end = 12.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        val byteArray = Base64.decode(
            image
                .replace("data:image/png;base64,", "")
                .replace("data:image/jpeg;base64,", ""),
            Base64.DEFAULT
        ).apply {
            BitmapFactory.decodeByteArray(this, 0, size)
        }

        BitmapImage(
            byteArray,
            contentDescription = alt,
            modifier = Modifier
                .width(50.dp)
                .padding(end = 12.dp)
        )
    }
}

@Composable
fun CredentialImage(image: JSONArray, alt: String) {
    val byteArray = remember { jsonArrayToByteArray(image) }

    BitmapImage(
        byteArray,
        contentDescription = alt,
        modifier = Modifier
            .width(50.dp)
            .padding(end = 12.dp)
    )
}

@Composable
fun FullSizeCredentialImage(
    imageUrl: String,
    contentDescription: String
) {
    // Custom image implementation that fills the space with shadow on the image itself
    if (imageUrl.startsWith("https://")) {
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else {
        // Handle base64 images
        val byteArray = remember(imageUrl) {
            try {
                Base64.decode(
                    imageUrl
                        .replace("data:image/png;base64,", "")
                        .replace("data:image/jpeg;base64,", ""),
                    Base64.DEFAULT
                )
            } catch (e: Exception) {
                null
            }
        }

        byteArray?.let {
            BitmapImage(
                it,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(
                        elevation = 3.dp,
                        ambientColor = Color.Black.copy(alpha = 0.08f),
                        spotColor = Color.Black.copy(alpha = 0.12f)
                    )
            )
        }
    }
}