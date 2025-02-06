package com.spruceid.mobilesdkexample.credentials

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.CredentialStatusList
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.utils.credentialDisplaySelector
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import java.util.UUID

@Composable
fun CredentialDetailsView(
    navController: NavController,
    credentialPacksViewModel: CredentialPacksViewModel,
    statusListViewModel: StatusListViewModel,
    credentialPackId: String
) {
    var credentialItem by remember { mutableStateOf<ICredentialView?>(null) }
    var credentialPack by remember { mutableStateOf<CredentialPack?>(null) }
    val statusList by statusListViewModel.observeStatusForId(UUID.fromString(credentialPackId))
        .collectAsState()

    LaunchedEffect(Unit) {
        credentialPack = credentialPacksViewModel.getById(credentialPackId) ?: CredentialPack()
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

    credentialItem?.let {
        Column(
            Modifier
                .padding(all = 20.dp)
                .padding(top = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.chevron),
                    contentDescription = stringResource(id = R.string.chevron),
                    modifier = Modifier
                        .scale(0.75f)
                        .rotate(180f)
                        .padding(start = 10.dp)
                        .clickable {
                            back()
                        }
                )
                it.credentialListItem()
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            if (statusList != CredentialStatusList.REVOKED) {
                credentialItem!!.credentialDetails()
            } else {
                credentialItem!!.credentialRevokedInfo({
                    back()
                })
            }
        }
    }

}