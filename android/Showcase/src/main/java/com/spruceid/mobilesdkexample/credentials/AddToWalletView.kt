package com.spruceid.mobilesdkexample.credentials

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobilesdkexample.DEFAULT_SIGNING_KEY_ID
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.db.WalletActivityLogs
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorBase150
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald700
import com.spruceid.mobilesdkexample.ui.theme.ColorRose600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.Toast
import com.spruceid.mobilesdkexample.utils.activityHiltViewModel
import com.spruceid.mobilesdkexample.utils.credentialDisplaySelector
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import kotlinx.coroutines.launch

/**
 * A single step in the acceptance flow: either the credential parsed
 * successfully and is ready to review, or it didn't and the step can only
 * be skipped.
 */
sealed class CredentialStepItem {
    data class Parsed(val item: ICredentialView) : CredentialStepItem()
    data class Failed(val message: String) : CredentialStepItem()
}

@Composable
fun AddToWalletView(
    navController: NavHostController,
    rawCredentials: List<String>,
    onSuccess: (() -> Unit)? = null
) {
    val credentialPacksViewModel: CredentialPacksViewModel = activityHiltViewModel()
    val walletActivityLogsViewModel: WalletActivityLogsViewModel = activityHiltViewModel()
    val statusListViewModel: StatusListViewModel = activityHiltViewModel()
    var stepItems by remember { mutableStateOf<List<CredentialStepItem>>(emptyList()) }
    var acceptedCount by remember { mutableIntStateOf(0) }
    var storing by remember { mutableStateOf(false) }
    // Swiping the pager back to an already-decided step shouldn't let the
    // user accept/decline it again.
    var decidedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { stepItems.size })

    LaunchedEffect(Unit) {
        stepItems = rawCredentials.map { rawCredential ->
            try {
                CredentialStepItem.Parsed(
                    credentialDisplaySelector(rawCredential, statusListViewModel, null, null, null)
                )
            } catch (e: Exception) {
                CredentialStepItem.Failed(e.localizedMessage ?: "Unable to parse credential")
            }
        }
        if (stepItems.isEmpty()) {
            navController.navigate(Screen.HomeScreen.route) { popUpTo(0) }
        }
    }

    // Advances past the current step, or finishes the whole flow if this was
    // the last one. Accept and decline are independent per-credential
    // actions: declining one credential has no effect on the others.
    fun advance() {
        val currentIndex = pagerState.currentPage
        if (currentIndex + 1 >= stepItems.size) {
            Toast.showSuccess("$acceptedCount of ${stepItems.size} credentials accepted")
            onSuccess?.invoke()
            navController.navigate(Screen.HomeScreen.route) { popUpTo(0) }
        } else {
            scope.launch {
                pagerState.animateScrollToPage(currentIndex + 1)
            }
        }
    }

    fun acceptCurrent() {
        val currentIndex = pagerState.currentPage
        if (decidedIndices.contains(currentIndex)) return
        decidedIndices = decidedIndices + currentIndex
        val rawCredential = rawCredentials[currentIndex]
        scope.launch {
            storing = true
            try {
                val credentialPack = CredentialPack()
                credentialPack.tryAddAnyFormat(rawCredential, DEFAULT_SIGNING_KEY_ID)
                credentialPacksViewModel.saveCredentialPack(credentialPack)
                val credentialInfo = getCredentialIdTitleAndIssuer(credentialPack)
                walletActivityLogsViewModel.saveWalletActivityLog(
                    walletActivityLogs = WalletActivityLogs(
                        credentialPackId = credentialPack.id().toString(),
                        credentialId = credentialInfo.first,
                        credentialTitle = credentialInfo.second,
                        issuer = credentialInfo.third,
                        action = "Claimed",
                        dateTime = getCurrentSqlDate(),
                        additionalInformation = ""
                    )
                )
                acceptedCount += 1
            } catch (e: Exception) {
                // Treat a save failure like a decline for this credential
                // rather than blocking the rest of the flow.
            }
            storing = false
            advance()
        }
    }

    fun declineCurrent() {
        val currentIndex = pagerState.currentPage
        if (decidedIndices.contains(currentIndex)) return
        decidedIndices = decidedIndices + currentIndex
        advance()
    }

    if (storing) {
        LoadingView(
            loadingText = "Storing credential..."
        )
    } else if (stepItems.isNotEmpty()) {
        val currentStep = stepItems[pagerState.currentPage]

        // The app draws edge-to-edge (see MainActivity's enableEdgeToEdge()),
        // so this screen has to claim the system bar insets itself, or the
        // stepper/card content render underneath the status bar and the
        // bottom button bar underneath the navigation bar.
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            StepProgressView(current = pagerState.currentPage, total = stepItems.size)
            Spacer(Modifier.height(12.dp))

            // `weight(1f)` claims the space remaining after StepProgressView
            // and the bottom button bar; a bare `fillMaxSize()` here would
            // claim the whole Column's height on top of that, overflowing
            // the screen and clipping the credential card's content.
            Box(Modifier.weight(1f)) {
                HorizontalPager(
                    state = pagerState,
                    // Swiping back to an already-decided step is safe: the
                    // button bar below only renders for undecided, parsed
                    // steps, guarded by `decidedIndices`.
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (val step = stepItems[page]) {
                        is CredentialStepItem.Failed -> {
                            ErrorView(
                                errorTitle = "Unable to Parse Credential",
                                errorDetails = step.message,
                                closeButtonLabel = "Skip",
                                onClose = { declineCurrent() }
                            )
                        }

                        is CredentialStepItem.Parsed -> {
                            // No verticalScroll wrapper here: CredentialReviewInfo's
                            // own body section uses Modifier.weight(1f, fill = false)
                            // internally, which requires a bounded-height parent.
                            // Scrollable columns give unbounded height, which
                            // collapses that weighted body to zero height.
                            // fillMaxSize() + the Column's default top
                            // arrangement keeps the card's content anchored
                            // to the top of the page instead of centered.
                            Column(Modifier.fillMaxSize()) {
                                step.item.CredentialReviewInfo(footerActions = {})
                            }
                        }
                    }
                }
            }

            // A fixed bottom bar, independent of the pager's content, so the
            // buttons don't move around with cards of different lengths —
            // matches the iOS layout. Only shown for a not-yet-decided,
            // successfully parsed step; a failed step only offers ErrorView's
            // own "Skip" action.
            if (currentStep is CredentialStepItem.Parsed &&
                !decidedIndices.contains(pagerState.currentPage)
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Button(
                        onClick = {
                            acceptCurrent()
                        },
                        shape = RoundedCornerShape(5.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ColorEmerald700,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = "Add to Wallet",
                            fontFamily = Inter,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }

                    Button(
                        onClick = {
                            declineCurrent()
                        },
                        shape = RoundedCornerShape(5.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = ColorRose600,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = "Decline",
                            fontFamily = Inter,
                            fontWeight = FontWeight.SemiBold,
                            color = ColorRose600,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shows "Credential X of Y" plus a row of segments indicating progress
 * through a multi-credential acceptance flow.
 */
@Composable
fun StepProgressView(current: Int, total: Int) {
    if (total > 1) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = "Credential ${current + 1} of $total",
                fontFamily = Inter,
                fontWeight = FontWeight.Medium,
                color = ColorStone950,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                for (idx in 0 until total) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .padding(end = if (idx == total - 1) 0.dp else 4.dp)
                            .background(
                                color = if (idx <= current) ColorEmerald700 else ColorBase150,
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
        }
    }
}
