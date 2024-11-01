package com.spruceid.mobilesdkexample.verifier

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.decapitalize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.spruceid.mobile.sdk.rs.MDocItem
import com.spruceid.mobilesdkexample.HomeView
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.BgSurfacePrimaryContrast
import com.spruceid.mobilesdkexample.ui.theme.BgSurfacePureWhite
import com.spruceid.mobilesdkexample.ui.theme.BorderSecondary
import com.spruceid.mobilesdkexample.ui.theme.ColorBase800
import com.spruceid.mobilesdkexample.ui.theme.ColorBase900
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald700
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald900
import com.spruceid.mobilesdkexample.ui.theme.ColorStone200
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.TextBase
import com.spruceid.mobilesdkexample.ui.theme.TextPrimary
import com.spruceid.mobilesdkexample.ui.theme.bodyMdDefault
import com.spruceid.mobilesdkexample.ui.theme.bodyXsRegular
import com.spruceid.mobilesdkexample.ui.theme.buttonText
import com.spruceid.mobilesdkexample.ui.theme.buttonTextSmall
import com.spruceid.mobilesdkexample.ui.theme.headerH2
import com.spruceid.mobilesdkexample.utils.BitmapImage

fun getDiscriminant(element: MDocItem): String =
    when (element) {
        is MDocItem.Array -> ""
        is MDocItem.Bool -> element.v1.toString()
        is MDocItem.Integer -> element.v1.toString()
        is MDocItem.ItemMap -> ""
        is MDocItem.Text -> element.v1
    }

fun intToGender(repr: MDocItem.Integer): String =
    when (repr.v1) {
        0L -> "Not Known"
        1L -> "Male"
        2L -> "Female"
        else -> "Other"
    }

fun mDocArrayToByteArray(repr: MDocItem.Array): ByteArray =
    repr.v1.filterIsInstance<MDocItem.Integer>().map {
        it.v1.toInt().toByte()
    }.toByteArray()


@Composable
fun VerifierMDocResultView(
    navController: NavController,
    result: Map<String, Map<String, MDocItem>>
) {
    val givenName = getDiscriminant(result["org.iso.18013.5.1"]?.get("given_name")!!)
    val familyName = getDiscriminant(result["org.iso.18013.5.1"]?.get("family_name")!!)
    val dob = getDiscriminant(result["org.iso.18013.5.1"]?.get("birth_date")!!)
    val address = getDiscriminant(result["org.iso.18013.5.1"]?.get("resident_address")!!)
    val expires = getDiscriminant(result["org.iso.18013.5.1"]?.get("expiry_date")!!)
    val height = getDiscriminant(result["org.iso.18013.5.1"]?.get("height")!!)
    val weight = getDiscriminant(result["org.iso.18013.5.1"]?.get("weight")!!)
    val gender = intToGender(result["org.iso.18013.5.1"]?.get("sex")!! as MDocItem.Integer)
    val eyeColor = getDiscriminant(result["org.iso.18013.5.1"]?.get("eye_colour")!!)
    val hairColor = getDiscriminant(result["org.iso.18013.5.1"]?.get("hair_colour")!!)
    val portrait = result["org.iso.18013.5.1"]?.get("portrait")!! as MDocItem.Array

    val portraitBytes = mDocArrayToByteArray(portrait)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 48.dp, bottom = 64.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .weight(1f),
                Arrangement.spacedBy(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    //BitmapImage(portraitBytes, "Holder's Portrait", Modifier)
                    Text(
                        text = "Driver's License",
                        color = ColorStone950,
                        style = MaterialTheme.typography.headerH2
                    )
                    Text(
                        text = "Issuer",
                        color = ColorStone600,
                        style = MaterialTheme.typography.bodyMdDefault
                    )
                }
                HorizontalDivider(color = ColorStone200)
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "Status",
                        color = ColorStone600,
                        style = MaterialTheme.typography.bodyXsRegular
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(53.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(color = ColorEmerald700)
                            .border(width = 1.dp, color = ColorBase900)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically)
                        {
                            Icon(
                                painter = painterResource(id = R.drawable.valid_check),
                                contentDescription = "Valid Checkmark",
                                tint = Color.White,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "VALID",
                                color = TextBase,
                                style = MaterialTheme.typography.bodyMdDefault
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "Name",
                            color = ColorStone600,
                            style = MaterialTheme.typography.bodyXsRegular
                        )
                        Text(
                            text = "${
                                givenName.lowercase().replaceFirstChar(Char::titlecase)
                            } ${familyName.lowercase().replaceFirstChar(Char::titlecase)}",
                            color = ColorStone950,
                            style = MaterialTheme.typography.bodyMdDefault
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "Date of Birth",
                            color = ColorStone600,
                            style = MaterialTheme.typography.bodyXsRegular
                        )
                        Text(
                            text = dob,
                            color = ColorStone950,
                            style = MaterialTheme.typography.bodyMdDefault
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "Address",
                            color = ColorStone600,
                            style = MaterialTheme.typography.bodyXsRegular
                        )
                        Text(
                            text = address,
                            color = ColorStone950,
                            style = MaterialTheme.typography.bodyMdDefault
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "Height",
                            color = ColorStone600,
                            style = MaterialTheme.typography.bodyXsRegular
                        )
                        Text(
                            text = "${height}cm",
                            color = ColorStone950,
                            style = MaterialTheme.typography.bodyMdDefault
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "Weight",
                            color = ColorStone600,
                            style = MaterialTheme.typography.bodyXsRegular
                        )
                        Text(
                            text = "${weight}kg",
                            color = ColorStone950,
                            style = MaterialTheme.typography.bodyMdDefault
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "Eye Color",
                            color = ColorStone600,
                            style = MaterialTheme.typography.bodyXsRegular
                        )
                        Text(
                            text = eyeColor.replaceFirstChar(Char::titlecase),
                            color = ColorStone950,
                            style = MaterialTheme.typography.bodyMdDefault
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "Hair Color",
                            color = ColorStone600,
                            style = MaterialTheme.typography.bodyXsRegular
                        )
                        Text(
                            text = hairColor.replaceFirstChar(Char::titlecase),
                            color = ColorStone950,
                            style = MaterialTheme.typography.bodyMdDefault
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "Gender",
                            color = ColorStone600,
                            style = MaterialTheme.typography.bodyXsRegular
                        )
                        Text(
                            text = gender,
                            color = ColorStone950,
                            style = MaterialTheme.typography.bodyMdDefault
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    onClick = { navController.popBackStack() },
                    colors = ButtonColors(
                        containerColor = BgSurfacePrimaryContrast, contentColor = TextBase,
                        disabledContainerColor = Color.Black,
                        disabledContentColor = TextBase
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    )
                    {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Rescan Icon",
                            tint = Color.White,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Rescan",
                            color = TextBase,
                            style = MaterialTheme.typography.buttonText
                        )
                    }
                }
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(width = 1.dp, color = BorderSecondary),
                    shape = RoundedCornerShape(8.dp),
                    onClick = {
                        navController.navigate(Screen.HomeScreen.route) {
                            popUpTo(0)
                        }
                    },
                    colors = ButtonColors(
                        containerColor = BgSurfacePureWhite, contentColor = TextPrimary,
                        disabledContainerColor = BgSurfacePureWhite,
                        disabledContentColor = TextPrimary
                    )

                ) {
                    Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                    {
                        Text(
                            text = "Close",
                            color = TextPrimary,
                            style = MaterialTheme.typography.buttonTextSmall
                        )
                    }
                }
            }
        }
    }
}


@Preview
@Composable
fun MDocVerifyPreview() {
    val example: Map<String, Map<String, MDocItem>> =
        mapOf(
            "org.iso.18013.5.1" to mapOf(
                "portrait" to MDocItem.Array(listOf()),
                "family_name" to MDocItem.Text("ONEZERO"),
                "given_name" to MDocItem.Text("IRVINGTEST"),
                "birth_date" to MDocItem.Text("1999-03-13"),
                "expiry_date" to MDocItem.Text("2038-03-16"),
                "sex" to MDocItem.Integer(9),
                "height" to MDocItem.Integer(185),
                "weight" to MDocItem.Integer(55),
                "eye_colour" to MDocItem.Text("green"),
                "hair_colour" to MDocItem.Text("unknown"),
                "resident_address" to MDocItem.Text("2415 1ST AVE, SACRAMENTO 95818"),
                "document_number" to MDocItem.Text("I8882610")
            ),
            "org.iso.18013.5.1.aamva" to mapOf(
                "DHS_compliance" to MDocItem.Text("F"),
                "domestic_driving_privileges" to MDocItem.Array(listOf()),
                "veteran" to MDocItem.Integer(1)
            )
        )
    VerifierMDocResultView(rememberNavController(), example)
}