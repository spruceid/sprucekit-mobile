package com.spruceid.mobilesdkexample.credentials

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.CredentialStatusList
import com.spruceid.mobile.sdk.CredentialsViewModel
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorBase600
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone500
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.credentialDisplaySelector
import com.spruceid.mobilesdkexample.utils.credentialPackHasMdoc
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import kotlinx.coroutines.launch
import java.util.UUID

class CredentialDetailsViewTabs(
    val image: @Composable () -> Painter,
    val alt: @Composable () -> String
)

@Composable
fun CredentialDetailsView(
    navController: NavController,
    credentialPacksViewModel: CredentialPacksViewModel,
    statusListViewModel: StatusListViewModel,
    credentialPackId: String
) {
    var credentialTitle by remember { mutableStateOf<String?>(null) }
    var credentialItem by remember { mutableStateOf<ICredentialView?>(null) }
    var credentialPack by remember { mutableStateOf<CredentialPack?>(null) }
    val statusList by statusListViewModel.observeStatusForId(UUID.fromString(credentialPackId))
        .collectAsState()

    var tabs by remember {
        mutableStateOf(
            listOf(
                CredentialDetailsViewTabs(
                    { painterResource(id = R.drawable.info_icon) },
                    { stringResource(id = R.string.details_info) }
                )
            )
        )
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { tabs.size }
    )
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        credentialPack = credentialPacksViewModel.getById(credentialPackId) ?: CredentialPack()
        if (credentialPackHasMdoc(credentialPack!!)) {
            val tmpTabs = tabs.toMutableList()
            tmpTabs.add(
                CredentialDetailsViewTabs(
                    { painterResource(id = R.drawable.qrcode) },
                    { stringResource(id = R.string.details_share) }
                )
            )
            tabs = tmpTabs
        }
        credentialTitle = getCredentialIdTitleAndIssuer(credentialPack!!).second
        credentialItem = credentialDisplaySelector(
            credentialPack!!,
            statusListViewModel,
            null,
            null,
            null
        )
        statusListViewModel.fetchAndUpdateStatus(credentialPack!!)
    }

    fun back() {
        navController.navigate(Screen.HomeScreen.route) {
            popUpTo(0)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 60.dp)
                .padding(bottom = 40.dp)
                .clickable {
                    back()
                }
        ) {
            Image(
                painter = painterResource(id = R.drawable.chevron),
                contentDescription = stringResource(id = R.string.chevron),
                modifier = Modifier
                    .scale(0.75f)
                    .rotate(180f)
                    .padding(start = 10.dp)
            )
            Text(
                text = credentialTitle ?: "",
                fontFamily = Inter,
                fontWeight = FontWeight.Medium,
                fontSize = 20.sp,
                color = ColorStone950,
            )
        }
        HorizontalDivider()
        Box(modifier = Modifier.weight(1f)) {
            HorizontalPager(
                state = pagerState
            ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ColorBase50),
                    contentAlignment = Alignment.Center
                ) {
                    if (page == 0) { // Details
                        Column(
                            Modifier
                                .padding(horizontal = 20.dp)
                                .padding(vertical = 16.dp)
                        ) {
                            credentialItem?.let {
                                if (statusList != CredentialStatusList.REVOKED) {
                                    credentialItem!!.credentialDetails()
                                } else {
                                    credentialItem!!.credentialRevokedInfo({
                                        back()
                                    })
                                }
                            }
                        }
                    } else if (page == 1) { // Share
                        GenericCredentialDetailsShareQRCode(credentialPack!!)
                    }
                }
            }
        }
        if (tabs.count() > 1) {
            DetailsViewBottomTabs(tabs, pagerState) { index ->
                coroutineScope.launch {
                    pagerState.animateScrollToPage(index)
                }
            }
        }
    }
}

@Composable
fun DetailsViewBottomTabs(
    tabs: List<CredentialDetailsViewTabs>,
    pagerState: PagerState,
    changeTabs: (Int) -> Unit
) {
    BottomAppBar(containerColor = ColorBase50) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Row {
                tabs.forEachIndexed { index, tab ->
                    Button(
                        onClick = { changeTabs(index) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = tab.image(),
                                contentDescription = tab.alt(),
                                colorFilter = ColorFilter.tint(
                                    if (pagerState.currentPage == index) ColorBlue600 else ColorBase600,
                                ),
                                modifier = Modifier
                                    .width(32.dp)
                                    .height(32.dp)
                                    .padding(end = 3.dp)
                                    .drawBehind {
                                        drawLine(
                                            color = if (pagerState.currentPage == index) ColorBlue600 else Color.Transparent,
                                            start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                            end = androidx.compose.ui.geometry.Offset(
                                                size.width,
                                                0f
                                            ),
                                            strokeWidth = 4.dp.toPx()
                                        )
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GenericCredentialDetailsShareQRCode(credentialPack: CredentialPack) {
    val context = LocalContext.current
    val application = context.applicationContext as Application

    fun newCredentialViewModel(): CredentialsViewModel {
        val credentialViewModel = ViewModelProvider.AndroidViewModelFactory(application)
            .create(CredentialsViewModel::class.java)
        val parsedCredential: ParsedCredential? =
            credentialPack.list().firstNotNullOfOrNull { credential ->
                try {
                    if (credential.asMsoMdoc() != null) {
                        return@firstNotNullOfOrNull credential
                    }
                } catch (_: Exception) {
                }
                null
            }
        parsedCredential?.let {
            credentialViewModel.storeCredential(parsedCredential)
        }
        return credentialViewModel
    }

    val credentialViewModel by remember {
        mutableStateOf(newCredentialViewModel())
    }

    fun cancel() {
        credentialViewModel.cancel()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            Modifier
                .clip(shape = RoundedCornerShape(12.dp))
                .background(ColorBase1)
                .border(
                    width = 1.dp,
                    color = ColorStone300,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(8.dp)
        ) {
            ShareMdocView(
                credentialViewModel = credentialViewModel,
                onCancel = {
                    cancel()
                }
            )
        }
        Text(
            text = "Present this QR code to a verifier in order to share data. You will see a consent dialogue.",
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp),
            fontFamily = Inter,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            color = ColorStone500,
        )
    }
}