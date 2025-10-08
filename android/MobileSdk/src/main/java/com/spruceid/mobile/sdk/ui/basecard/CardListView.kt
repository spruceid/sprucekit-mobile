package com.spruceid.mobile.sdk.ui.basecard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.spruceid.mobile.sdk.CredentialPack

/**
 * Renders the credential as a list view item
 * @property credentialPack CredentialPack instance
 * @property rendering CardRenderingListView instance
 */
@Composable
fun CardListView(
    credentialPack: CredentialPack,
    rendering: CardRenderingListView
) {
    val titleValues = credentialPack.findCredentialClaims(rendering.titleKeys)
    val descriptionValues =
        credentialPack.findCredentialClaims(rendering.descriptionKeys ?: emptyList())

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .border(
                width = 1.dp,
                shape = RoundedCornerShape(16.dp),
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFFC8BFAD),
                        0.1f to Color.White.copy(alpha = 0.2f),
                        0.9f to Color.White.copy(alpha = 0.2f),
                        1.0f to Color(0xFFC8BFAD),
                    ),
                )
            )
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(12.dp),
                ambientColor = Color.Black.copy(alpha = 0.6f),
                spotColor = Color.Black.copy(alpha = 0.6f),
            )
            .clip(RoundedCornerShape(16.dp))
    ) {
        // Card background image
        rendering.cardStyle?.backgroundImageResId?.let { backgroundImageResId ->
            Image(
                painter = painterResource(id = backgroundImageResId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Linear gradient overlay: 5% black at top, 25% black at bottom
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.0f), // Top
                                Color.Black.copy(alpha = 0.35f)  // Bottom
                            )
                        )
                    )
            )
        }

        // Logo + Image
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Top-left logo if specified in cardStyle
            rendering.cardStyle?.topLeftLogoResId?.let { logoResId ->
                Icon(
                    painter = painterResource(id = logoResId),
                    contentDescription = "Logo",
                    tint = rendering.cardStyle.topLeftLogoTint ?: Color.Unspecified,
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp),
                )
            }

            // Credential image if specified in cardStyle
            rendering.cardStyle?.credentialImageFormatter?.let { credentialImageFormatter ->
                credentialImageFormatter.invoke(
                    credentialPack.findCredentialClaims(
                        rendering.cardStyle.credentialImageKeys ?: emptyList()
                    )
                )
            }
        }


        // Title and content - Bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp)
        ) {
            // Title
            if (rendering.titleFormatter != null) {
                rendering.titleFormatter.invoke(titleValues)
            } else {
                Text(
                    text = titleValues.values
                        .fold(emptyList<String>()) { acc, next ->
                            acc +
                                    next.keys()
                                        .asSequence()
                                        .map { key -> next.get(key) }
                                        .joinToString(" ") { value -> value.toString() }
                        }.joinToString("").trim()
                )
            }
            // Issuer and Status row
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (rendering.descriptionFormatter != null) {
                    rendering.descriptionFormatter.invoke(descriptionValues)
                } else {
                    Text(
                        text = descriptionValues.values
                            .fold(emptyList<String>()) { acc, next ->
                                acc +
                                        next.keys()
                                            .asSequence()
                                            .map { key -> next.get(key) }
                                            .joinToString(" ") { value -> value.toString() }
                            }.joinToString("").trim()
                    )
                }
            }
        }
// Trailing action button
//        if (rendering.trailingActionButton != null) {
//            rendering.trailingActionButton.invoke(
//                credentialPack.findCredentialClaims(rendering.trailingActionKeys ?: emptyList())
//            )
//        }

    }
}
