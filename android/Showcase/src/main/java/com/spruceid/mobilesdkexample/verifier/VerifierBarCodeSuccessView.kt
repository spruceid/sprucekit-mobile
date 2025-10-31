package com.spruceid.mobilesdkexample.verifier

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.credentials.ICredentialView
import com.spruceid.mobilesdkexample.ui.theme.ColorBase300
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald50
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald500
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald900
import com.spruceid.mobilesdkexample.ui.theme.ColorRose50
import com.spruceid.mobilesdkexample.ui.theme.ColorRose500
import com.spruceid.mobilesdkexample.ui.theme.ColorRose900
import com.spruceid.mobilesdkexample.ui.theme.ColorStone200
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone500
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone700
import com.spruceid.mobilesdkexample.ui.theme.ColorStone800
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Switzer
import com.spruceid.mobilesdkexample.utils.activityHiltViewModel
import com.spruceid.mobilesdkexample.utils.credentialDisplaySelector
import com.spruceid.mobilesdkexample.utils.splitCamelCase
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import org.json.JSONObject

@Composable
fun VerifierBarCodeSuccessView(
    jsonCredential: String,
    isValid: Boolean,
    onClose: () -> Unit,
    onRestart: () -> Unit,
    allDataContent: @Composable () -> Unit,
) {
    val statusListViewModel: StatusListViewModel = activityHiltViewModel()
    var credentialItem by remember { mutableStateOf<ICredentialView?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var issuer by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(0) } // 0 = Personal Data, 1 = All Data

    LaunchedEffect(Unit) {
        try {
            credentialItem = credentialDisplaySelector(
                jsonCredential,
                statusListViewModel = statusListViewModel,
                null,
                null,
                null
            )
            Log.d("VERIFIER BAR CODE", credentialItem.toString())

            if (credentialItem != null) {
                statusListViewModel.fetchAndUpdateStatus(credentialItem!!.credentialPack)
                val credentials = credentialItem!!.credentialPack.list()

                Log.d("VERIFIER BAR CODE", credentials.toString())

                // Only process if we have credentials
                if (credentials.isNotEmpty()) {
                    val credential = credentials.first()
                    val claims = credentialItem!!.credentialPack.getCredentialClaims(
                        credential,
                        listOf("name", "type", "description", "issuer", "Given Names", "Family Name")
                    )

                    try {
                        title = claims.optString("name")?.takeIf { it.isNotBlank() }
                        if (title.isNullOrBlank()) {
                            val arrayTypes = claims.optJSONArray("type")
                            if (arrayTypes != null) {
                                for (i in 0 until arrayTypes.length()) {
                                    if (arrayTypes.get(i).toString() != "VerifiableCredential") {
                                        title = arrayTypes.get(i).toString().splitCamelCase()
                                        break
                                    }
                                }
                            }
                        }
                        if (title.isNullOrBlank()) {
                            // Try to get name from credentialSubject
                            val credentialSubject = claims.optJSONObject("credentialSubject")
                            if (credentialSubject != null) {
                                val givenName = credentialSubject.optString("given_name", "")
                                val familyName = credentialSubject.optString("family_name", "")
                                if (givenName.isNotBlank() || familyName.isNotBlank()) {
                                    title = "$givenName $familyName".trim()
                                }
                            }
                        }
                        if (title.isNullOrBlank()) {
                            val names = claims.optString("Given Names", "")
                            val family = claims.optString("Family Name", "")
                            if (names.isNotBlank() || family.isNotBlank()) {
                                title = "$names $family".trim()
                            }
                        }
                    } catch (_: Exception) {
                    }

                    try {
                        issuer = claims.getJSONObject("issuer").getString("name").toString()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VERIFIER BAR CODE", "Error processing credential", e)
            // Credential display selector doesn't support this format
            // That's okay, we'll just show default title/issuer
        }
    }

    Column(
        Modifier
            .padding(all = 20.dp)
            .padding(top = 20.dp)
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .padding(top = 30.dp)
        ) {
            CredentialInfoCard(
                title = title,
                issuer = issuer,
                image = null
            )

            StatusBanner(isValid = isValid)

            // Tab Menu
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    // Personal Data Tab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = 0 }
                            .padding(top = 4.dp, start = 12.dp, end = 12.dp, bottom = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                painter = painterResource(id = com.spruceid.mobilesdkexample.R.drawable.user),
                                contentDescription = "Personal Data",
                                modifier = Modifier.width(16.dp).height(16.dp),
                                tint = if (selectedTab == 0) ColorStone950 else ColorStone500
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Personal Data",
                                fontFamily = Switzer,
                                fontWeight = if (selectedTab == 0) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 14.sp,
                                color = if (selectedTab == 0) ColorStone950 else ColorStone500,
                            )
                        }
                    }

                    // All Data Tab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = 1 }
                            .padding(top = 4.dp, start = 12.dp, end = 12.dp, bottom = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                painter = painterResource(id = com.spruceid.mobilesdkexample.R.drawable.verification_activity_log),
                                contentDescription = "All Data",
                                modifier = Modifier.width(18.dp).height(18.dp),
                                tint = if (selectedTab == 1) ColorStone950 else ColorStone500
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "All Data",
                                fontFamily = Switzer,
                                fontWeight = if (selectedTab == 1) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 14.sp,
                                color = if (selectedTab == 1) ColorStone950 else ColorStone500,
                            )
                        }
                    }
                }

                // Continuous border line
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(if (selectedTab == 0) ColorStone700 else ColorStone300)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(if (selectedTab == 1) ColorStone700 else ColorStone300)
                    )
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .weight(weight = 1f, fill = false)
        ) {
            if (selectedTab == 0) {
                PersonalDataView(credentialItem = credentialItem)
            } else {
                allDataContent()
            }
        }

        Button(
            onClick = {
                onRestart()
            },
            shape = RoundedCornerShape(100.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ColorStone800,
                contentColor = Color.White,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
            ) {
                Image(
                    modifier = Modifier.size(20.dp),
                    painter = painterResource(id = R.drawable.arrow_circle),
                    contentDescription = stringResource(id = R.string.arrow_circle),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Rescan",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }

        Button(
            onClick = {
                onClose()
            },
            shape = RoundedCornerShape(100.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = ColorStone950,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = ColorStone300,
                    shape = RoundedCornerShape(100.dp)
                )
        ) {
            Text(
                text = "Close",
                fontFamily = Switzer,
                fontWeight = FontWeight.SemiBold,
                color = ColorStone950,
                modifier = Modifier.padding(vertical = 2.dp, horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun CredentialInfoCard(
    title: String?,
    issuer: String?,
    image: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .border(
                width = 1.dp,
                shape = RoundedCornerShape(8.dp),
                color = ColorBase300
            )
    ) {
        Row(
            modifier = Modifier.padding(12.dp)
        ) {
            image?.let {
                it()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                Text(
                    text = title ?: "Credential",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                    color = ColorStone950,
                )
                Spacer(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = issuer ?: "SpruceID",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Normal,
                    fontSize = 16.sp,
                    color = ColorStone600,
                )
            }
        }
    }
}

@Composable
private fun StatusBanner(isValid: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(
                width = 1.dp,
                color = if (isValid) ColorEmerald500 else ColorRose500,
                shape = RoundedCornerShape(8.dp)
            )
            .background(
                color = if (isValid) ColorEmerald50 else ColorRose50,
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        Row (
            modifier = Modifier
                .align(Alignment.Center)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(
                    id = if (isValid)
                        R.drawable.valid_check
                    else
                        R.drawable.invalid_check
                ),
                contentDescription = if (isValid)
                    stringResource(id = com.spruceid.mobilesdkexample.R.string.valid_check)
                else
                    stringResource(id = com.spruceid.mobilesdkexample.R.string.invalid_check),
                modifier = Modifier
                    .width(16.dp)
                    .height(16.dp),
                colorFilter = ColorFilter.tint(if (isValid) ColorEmerald900 else ColorRose900)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isValid) "Valid" else "Invalid",
                fontFamily = Switzer,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                color = if (isValid) ColorEmerald900 else ColorRose900,
            )
        }
    }
}

@Composable
private fun PersonalDataView(credentialItem: ICredentialView?) {
    if (credentialItem != null) {
        credentialItem.CredentialDetails()
    }
}
