package com.spruceid.mobilesdkexample.credentials

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.ui.theme.ColorStone500
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.Switzer
import com.spruceid.mobilesdkexample.utils.Accordion
import com.spruceid.mobilesdkexample.utils.RenderCredentialFieldValue
import com.spruceid.mobilesdkexample.utils.formatCredentialFieldValue
import com.spruceid.mobilesdkexample.utils.getCredentialFieldType
import com.spruceid.mobilesdkexample.utils.getKeyReadable
import com.spruceid.mobilesdkexample.utils.isDate
import com.spruceid.mobilesdkexample.utils.isImage
import com.spruceid.mobilesdkexample.utils.removeUnderscores
import com.spruceid.mobilesdkexample.utils.splitCamelCase
import com.spruceid.mobilesdkexample.utils.toTitle
import org.json.JSONObject

@Composable
fun genericObjectDisplayer(obj: JSONObject, filter: List<String>, level: Int = 1): List<Unit> {
    val res = mutableListOf<Unit>()

    obj
        .keys()
        .asSequence()
        .sorted()
        .filter { !filter.contains(it) }
        .forEach { key ->
            if (obj.optJSONObject(key) != null) {
                val jsonObject = obj.getJSONObject(key)
                res.add(
                    0,
                    Accordion(
                        title = key.splitCamelCase().removeUnderscores(),
                        startExpanded = level < 3,
                        modifier = Modifier
                            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
                    ) {
                        genericObjectDisplayer(jsonObject, filter, level + 1)
                    }
                )
            } else if (obj.optJSONArray(key) != null) {
                val jsonArray = obj.getJSONArray(key)
                Accordion(
                    title = key.splitCamelCase().removeUnderscores(),
                    startExpanded = level < 3,
                    modifier = Modifier
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
                ) {
                    if (key.isImage()) {
                        CredentialImage(jsonArray, key)
                    } else {
                        for (i in 0 until jsonArray.length()) {
                            if (jsonArray.optJSONObject(i) != null) {
                                val arrayJsonObject = jsonArray.getJSONObject(i)
                                genericObjectDisplayer(
                                    arrayJsonObject,
                                    filter,
                                    level + 1
                                )
                            } else {
                                Column(
                                    Modifier.padding(bottom = 12.dp)
                                ) {
                                    if (i == 0) {
                                        Text(
                                            key.getKeyReadable().splitCamelCase().removeUnderscores(),
                                            fontFamily = Inter,
                                            fontWeight = FontWeight.Normal,
                                            fontSize = 16.sp,
                                            color = ColorStone500,
                                        )
                                    }
                                    Text(
                                        jsonArray.get(i).toString().toTitle(),
                                        fontFamily = Inter,
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 17.sp,
                                        color = ColorStone950,
                                    )
                                }
                            }
                        }
                    }

                }
            } else {
                val value = obj.get(key).toString()
                if (value != "null") {
                    res.add(
                        0,
                        Column(
                            Modifier.padding(bottom = 12.dp)
                        ) {
                            Text(
                                key.getKeyReadable().splitCamelCase().removeUnderscores(),
                                fontFamily = Inter,
                                fontWeight = FontWeight.Normal,
                                fontSize = 16.sp,
                                color = ColorStone500,
                            )
                            if (key.isImage() || value.isImage()) {
                                CredentialImage(value, key)
                            } else if (key.isDate()) {
                                CredentialDate(value)
                            } else {
                                Text(
                                    text = value.toTitle(),
                                    fontFamily = Inter,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 17.sp,
                                    color = ColorStone950,
                                )
                            }
                        }
                    )
                }
            }
        }

    return res.toList()
}

/**
 * Displays a JSONObject with all fields flattened to the same level.
 * Nested objects are flattened with field names like "Parent Subfield".
 */
@Composable
fun flattenedRowDisplayer(
    obj: JSONObject,
    filter: List<String>,
    prefix: String = ""
): List<Unit> {
    val res = mutableListOf<Unit>()

    obj
        .keys()
        .asSequence()
        .sorted()
        .filter { !filter.contains(it) }
        .forEach { key ->
            val readableKey = key.getKeyReadable().splitCamelCase().removeUnderscores()
            val displayKey = if (prefix.isEmpty()) readableKey else "$prefix $readableKey"

            when {
                obj.optJSONObject(key) != null -> {
                    // Recursively flatten nested objects
                    val jsonObject = obj.getJSONObject(key)
                    res.addAll(flattenedRowDisplayer(jsonObject, filter, displayKey))
                }
                obj.optJSONArray(key) != null -> {
                    val jsonArray = obj.getJSONArray(key)
                    val arrayValues = mutableListOf<String>()

                    for (i in 0 until jsonArray.length()) {
                        when {
                            jsonArray.optJSONObject(i) != null -> {
                                // Flatten nested objects in array
                                val arrayJsonObject = jsonArray.getJSONObject(i)
                                res.addAll(
                                    flattenedRowDisplayer(
                                        arrayJsonObject,
                                        filter,
                                        "$displayKey ${i + 1}"
                                    )
                                )
                            }
                            else -> {
                                arrayValues.add(jsonArray.get(i).toString())
                            }
                        }
                    }

                    // Display comma-separated array values if any
                    if (arrayValues.isNotEmpty()) {
                        res.add(
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                ) {
                                    Text(
                                        text = displayKey,
                                        fontFamily = Switzer,
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 14.sp,
                                        color = ColorStone500,
                                        modifier = Modifier.weight(1f),
                                        softWrap = true
                                    )
                                    Spacer(modifier = Modifier.width(20.dp))
                                    Text(
                                        text = arrayValues.joinToString(", "),
                                        fontFamily = Switzer,
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 15.sp,
                                        color = ColorStone950,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                HorizontalDivider(
                                    color = Color.Black.copy(alpha = 0.2f),
                                    thickness = 1.dp
                                )
                            }
                        )
                    }
                }
                else -> {
                    // Primitive values shown in rows
                    val value = obj.get(key).toString()
                    if (value != "null") {
                        res.add(
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = displayKey,
                                        fontFamily = Switzer,
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 14.sp,
                                        color = ColorStone500,
                                        modifier = Modifier.weight(1f),
                                        softWrap = true
                                    )
                                    Spacer(modifier = Modifier.width(40.dp))
                                    val fieldType = getCredentialFieldType(displayKey, value)
                                    val formattedValue = formatCredentialFieldValue(value, fieldType, key, maxLength = 21)
                                    RenderCredentialFieldValue(
                                        fieldType = fieldType,
                                        rawFieldValue = value,
                                        formattedValue = formattedValue,
                                        displayName = displayKey
                                    )
                                }
                                HorizontalDivider(
                                    color = Color.Black.copy(alpha = 0.2f),
                                    thickness = 1.dp
                                )
                            }
                        )
                    }
                }
            }
        }

    return res.toList()
}
