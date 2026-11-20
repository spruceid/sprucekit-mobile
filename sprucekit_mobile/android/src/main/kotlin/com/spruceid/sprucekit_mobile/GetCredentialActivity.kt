package com.spruceid.sprucekit_mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spruceid.mobile.sdk.dcapi.Activity as DcApiActivity
import com.spruceid.mobile.sdk.rs.FieldId180137
import com.spruceid.mobile.sdk.rs.RequestMatch180137

/**
 * Activity that handles DC API credential requests from browsers.
 *
 * This activity is launched by the system when a website requests credentials
 * via the Digital Credentials API. It shows a consent UI allowing the user
 * to select which fields to share.
 *
 * To use this activity, add the following to your app's AndroidManifest.xml:
 *
 * ```xml
 * <activity
 *     android:name="com.spruceid.sprucekit_mobile.GetCredentialActivity"
 *     android:exported="true"
 *     android:theme="@android:style/Theme.Translucent.NoTitleBar">
 *     <intent-filter>
 *         <action android:name="androidx.credentials.registry.provider.action.GET_CREDENTIAL" />
 *         <action android:name="androidx.identitycredentials.action.GET_CREDENTIALS" />
 *         <category android:name="android.intent.category.DEFAULT" />
 *     </intent-filter>
 * </activity>
 * ```
 */
class GetCredentialActivity : DcApiActivity() {

    @Composable
    override fun ConsentView(
        match: RequestMatch180137,
        origin: String,
        onContinue: (List<FieldId180137>) -> Unit,
        onCancel: () -> Unit
    ) {
        val requestedFields = match.requestedFields()
        val selectedFields = remember {
            mutableStateListOf<FieldId180137>().apply {
                // Pre-select all required fields
                addAll(requestedFields.filter { it.required }.map { it.id })
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(16.dp)
        ) {
            Text(
                text = "Share Information",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "\"$origin\" is requesting the following information:",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(requestedFields) { field ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selectedFields.contains(field.id),
                            onCheckedChange = { checked ->
                                if (checked) {
                                    if (!selectedFields.contains(field.id)) {
                                        selectedFields.add(field.id)
                                    }
                                } else {
                                    // Don't allow unchecking required fields
                                    if (!field.required) {
                                        selectedFields.remove(field.id)
                                    }
                                }
                            },
                            enabled = !field.required
                        )
                        Column {
                            Text(
                                text = field.displayableName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (field.required) {
                                Text(
                                    text = "Required",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Red
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = { onContinue(selectedFields.toList()) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Share")
                }
            }
        }
    }

    @Composable
    override fun LoadingView() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(60.dp)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Loading...",
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
