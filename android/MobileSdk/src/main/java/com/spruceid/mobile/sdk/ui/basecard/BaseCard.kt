package com.spruceid.mobile.sdk.ui.basecard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.spruceid.mobile.sdk.CredentialPack
import org.json.JSONObject

/**
 * Data class with styling options for card rendering
 * @property topLeftLogoResId Optional resource ID for the top-left logo
 * @property topLeftLogoTint Optional color for the top-left logo tint
 * @property backgroundImageResId Optional resource ID for the card background image
 */
data class CardStyle(
    val topLeftLogoResId: Int? = null,
    val topLeftLogoTint: Color? = null,
    val backgroundImageResId: Int? = null,
    val credentialImageKeys: List<String>? = null,
    val credentialImageFormatter: @Composable ((values: Map<String, JSONObject>) -> Unit)? = null,
)

/**
 * Data class with the specification to display the credential pack in a list view
 * @property titleKeys A list of keys that will be used to generate a list of values extracted from the credentials
 * @property titleFormatter Method used to create a custom title field. Receives an array of values based on the array of keys for the same field
 * @property descriptionKeys A list of keys that will be used to generate a list of values extracted from the credentials
 * @property descriptionFormatter Method used to create a custom description field. Receives an array of values based on the array of keys for the same field
 * @property leadingIconKeys A list of keys that will be used to generate a list of values extracted from the credentials
 * @property leadingIconFormatter Method used to create a custom leading icon formatter. Receives an array of values based on the array of keys for the same field
 * @property trailingActionKeys A list of keys that will be used to generate a list of values extracted from the credentials
 * @property trailingActionButton Method used to create a custom  trailing action button. Receives an array of values based on the array of keys for the same field
 * @property cardStyle Optional styling configuration for the card
 */
data class CardRenderingListView(
    val titleKeys: List<String>,
    val titleFormatter: @Composable ((values: Map<String, JSONObject>) -> Unit)? = null,
    val descriptionKeys: List<String>? = null,
    val descriptionFormatter: @Composable ((values: Map<String, JSONObject>) -> Unit)? = null,
    val leadingIconKeys: List<String>? = null,
    val leadingIconFormatter: @Composable ((values: Map<String, JSONObject>) -> Unit)? = null,
    val trailingActionKeys: List<String>? = null,
    val trailingActionButton: @Composable ((values: Map<String, JSONObject>) -> Unit)? = null,
    val cardStyle: CardStyle? = null
)

/**
 * Data class with the specification to display the credential field in a details view
 * @property keys A list of keys that will be used to generate a list of values extracted from the credentials
 * @property formatter Method used to create a custom field. Receives an array of values based on the array of keys for the same field
 */
data class CardRenderingDetailsField(
    val keys: List<String>,
    val formatter: @Composable ((values: Map<String, JSONObject>) -> Unit)? = null
)

/**
 * Data class with the specification to display the credential in a details view
 * @property fields A list of field render settings that will be used to generate a UI element with the defined keys
 */
data class CardRenderingDetailsView(
    val fields: List<CardRenderingDetailsField>
)


/**
 * Interface  aggregating two types:
 * (LIST == CardRenderingListView) and
 * (DETAILS == CardRenderingDetailsView)
 */
sealed interface CardRendering
@JvmInline
value class LIST(val rendering: CardRenderingListView) : CardRendering
@JvmInline
value class DETAILS(val rendering: CardRenderingDetailsView) : CardRendering

/**
 * Method to convert CardRenderingListView to CardRendering
 */
fun CardRenderingListView.toCardRendering() = LIST(this)
/**
 * Method to convert CardRenderingDetailsView to CardRendering
 */
fun CardRenderingDetailsView.toCardRendering() = DETAILS(this)

/**
 * Manages the card rendering type according with the render object
 * @property credentialPack CredentialPack instance
 * @property rendering CardRendering instance
 */
@Composable
fun BaseCard(
    credentialPack: CredentialPack,
    rendering: CardRendering
) {
    when(rendering) {
        is LIST ->
            CardListView(credentialPack = credentialPack, rendering = rendering.rendering)
        is DETAILS ->
            CardDetailsView(credentialPack = credentialPack, rendering = rendering.rendering)
    }
}

/**
 * Renders the credential as a details view
 * @property credentialPack CredentialPack instance
 * @property rendering CardRenderingDetailsView instance
 */
@Composable
fun CardDetailsView(
    credentialPack: CredentialPack,
    rendering: CardRenderingDetailsView
) {
    Column(
        Modifier.verticalScroll(rememberScrollState())
    ) {
        rendering.fields.forEach {
            val values = credentialPack.findCredentialClaims(it.keys)

            if(it.formatter != null) {
                it.formatter.invoke(values)
            } else {
                Text(text = values.values
                    .fold(emptyList<String>()) { acc, next -> acc +
                            next.keys()
                                .asSequence()
                                .map { key -> next.get(key)}
                                .joinToString(" ") { value -> value.toString() }
                    }.joinToString("").trim())
            }
        }
    }

}
