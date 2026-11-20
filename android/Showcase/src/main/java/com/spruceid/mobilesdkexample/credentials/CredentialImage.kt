package com.spruceid.mobilesdkexample.credentials

import android.util.Base64
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.spruceid.mobilesdkexample.utils.BitmapImage
import com.spruceid.mobilesdkexample.utils.isRemoteImageUrl
import com.spruceid.mobilesdkexample.utils.jsonArrayToByteArray
import org.json.JSONArray

@Composable
fun CredentialImage(image: String, alt: String) {
    if (image.isRemoteImageUrl()) {
        AsyncImage(
            model = image,
            contentDescription = alt,
            modifier = Modifier
                .width(50.dp)
                .padding(end = 12.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        // `Base64.decode` throws on bad length or padding, which must not take the app down.
        val byteArray = remember(image) {
            runCatching {
                Base64.decode(
                    image
                        .replace("data:image/png;base64,", "")
                        .replace("data:image/jpeg;base64,", ""),
                    Base64.DEFAULT
                )
            }.getOrNull()
        }

        if (byteArray != null) {
            BitmapImage(
                byteArray,
                contentDescription = alt,
                modifier = Modifier
                    .width(50.dp)
                    .padding(end = 12.dp)
            )
        }
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
