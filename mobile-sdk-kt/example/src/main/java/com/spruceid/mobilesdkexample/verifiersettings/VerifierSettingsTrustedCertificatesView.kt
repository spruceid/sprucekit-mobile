package com.spruceid.mobilesdkexample.verifiersettings

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.db.TrustedCertificates
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.SimpleAlertDialog
import com.spruceid.mobilesdkexample.viewmodels.FileData
import com.spruceid.mobilesdkexample.viewmodels.TrustedCertificatesViewModel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

@Composable
fun VerifierSettingsTrustedCertificatesView(
    navController: NavController,
    trustedCertificatesViewModel: TrustedCertificatesViewModel
) {
    Column(
        Modifier
            .padding(all = 20.dp)
            .padding(top = 20.dp)
    ) {
        VerifierSettingsTrustedCertificatesHeader(
            onBack = {
                navController.popBackStack()
            }
        )
        VerifierSettingsTrustedCertificatesBody(
            trustedCertificatesViewModel = trustedCertificatesViewModel
        )
    }
}

@Composable
fun VerifierSettingsTrustedCertificatesHeader(onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(36.dp)
            .clickable {
                onBack()
            }
    ) {
        Image(
            painter = painterResource(id = R.drawable.chevron),
            contentDescription = stringResource(id = R.string.chevron),
            modifier = Modifier
                .rotate(180f)
                .scale(0.4f)
        )
        Text(
            text = "Trusted Certificates",
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            color = ColorStone950
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
fun VerifierSettingsTrustedCertificatesBody(
    trustedCertificatesViewModel: TrustedCertificatesViewModel
) {
    val trustedCertificates by trustedCertificatesViewModel.trustedCertificates.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        uris?.let {
            val selectedFiles = it.mapNotNull { uri ->
                val name = getFileName(context, uri)
                val content = readFileContent(context, uri)
                if (name != null && content != null) {
                    FileData(name, content)
                } else {
                    null
                }
            }

            selectedFiles.forEach { file ->
                scope.launch {
                    trustedCertificatesViewModel.saveCertificate(
                        TrustedCertificates(
                            name = file.name,
                            content = file.content
                        )
                    )
                }
            }
        }
    }
    Row(
        modifier = Modifier
            .padding(top = 20.dp)
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = "+ New Certificate",
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = ColorBlue600,
            modifier = Modifier.clickable {
                filePickerLauncher.launch(arrayOf("application/x-x509-ca-cert"))
            }
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    LazyColumn {
        items(trustedCertificates) { certificate ->
            Column(
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                SimpleAlertDialog(
                    trigger = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = certificate.name,
                                fontFamily = Inter,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp,
                                color = ColorStone950,
                                modifier = Modifier.weight(4f)
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(id = R.string.delete),
                                tint = ColorStone950,
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        trustedCertificatesViewModel.deleteCertificate(certificate.id)
                                    }
                                }
                            )
                        }
                    },
                    message = certificate.content
                )
            }
            HorizontalDivider()
        }
    }
}

fun readFileContent(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).readText()
        }
    } catch (e: Exception) {
        "Error reading file: ${e.message}"
    }
}

fun getFileName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor: Cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        }
    } catch (e: Exception) {
        "Unknown Name"
    }
}