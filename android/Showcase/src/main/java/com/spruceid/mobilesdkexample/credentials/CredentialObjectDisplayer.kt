package com.spruceid.mobilesdkexample.credentials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.spruceid.mobilesdkexample.ui.theme.ColorBase800
import com.spruceid.mobilesdkexample.ui.theme.ColorStone200
import com.spruceid.mobilesdkexample.ui.theme.ColorStone500
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
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
                                            key.getKeyReadable().splitCamelCase()
                                                .removeUnderscores(),
                                            fontFamily = Switzer,
                                            fontWeight = FontWeight.Normal,
                                            fontSize = 16.sp,
                                            color = ColorStone500,
                                        )
                                    }
                                    Text(
                                        jsonArray.get(i).toString().toTitle(),
                                        fontFamily = Switzer,
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
                                fontFamily = Switzer,
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
                                    fontFamily = Switzer,
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
 * Nested objects show their parent prefix as a section title with hierarchical indentation.
 */
@Composable
fun flattenedRowDisplayer(
    obj: JSONObject,
    filter: List<String>,
    nestingLevel: Int = 0
): List<Unit> {
    val res = mutableListOf<Unit>()

    // Partition keys into primitive and nested
    val allKeys = obj.keys().asSequence().sorted().filter { !filter.contains(it) }.toList()
    val primitiveKeys = allKeys.filter { key ->
        obj.optJSONObject(key) == null && obj.optJSONArray(key) == null && obj.get(key).toString() != "null"
    }
    val nestedKeys = allKeys.filter { key ->
        obj.optJSONObject(key) != null || obj.optJSONArray(key) != null
    }

    // Process primitive keys first
    primitiveKeys.forEach { key ->
        val readableKey = key.getKeyReadable().splitCamelCase().removeUnderscores()
        val value = obj.get(key).toString()
        val fieldType = getCredentialFieldType(readableKey, value)
        val formattedValue =
            formatCredentialFieldValue(value, fieldType, key, maxLength = 100)
        res.add(
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = (nestingLevel * 5).dp,
                        bottom = 8.dp
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = readableKey,
                        fontFamily = Switzer,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        color = ColorStone600,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        RenderCredentialFieldValue(
                            fieldType = fieldType,
                            rawFieldValue = value,
                            formattedValue = formattedValue,
                            displayName = readableKey
                        )
                    }

                }
                HorizontalDivider(
                    modifier = Modifier.padding(top = 8.dp),
                    color = ColorStone200,
                    thickness = 1.dp
                )
            }
        )
    }

    // Then process nested keys
    nestedKeys.forEach { key ->
        val readableKey = key.getKeyReadable().splitCamelCase().removeUnderscores()

        when {
            // Check if the current value is another Json Object
            obj.optJSONObject(key) != null -> {
                    // Add section title for nested object
                    res.add(
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = (nestingLevel * 6).dp,
                                    top = if (nestingLevel == 0) 12.dp else 8.dp,
                                    bottom = 8.dp
                                )
                        ) {
                            Text(
                                text = readableKey,
                                fontFamily = Switzer,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = ColorBase800
                            )
                        }
                    )
                    // Recursively flatten nested objects
                    val jsonObject = obj.getJSONObject(key)
                    res.addAll(flattenedRowDisplayer(jsonObject, filter, nestingLevel + 1))
                }
                // Check if it is an JSON Array
                obj.optJSONArray(key) != null -> {
                    val jsonArray = obj.getJSONArray(key)

                    for (i in 0 until jsonArray.length()) {
                        when {
                            jsonArray.optJSONObject(i) != null -> {
                                // Add section title for array item
                                res.add(
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                start = (nestingLevel * 5).dp,
                                                top = if (nestingLevel == 0) 12.dp else 8.dp,
                                                bottom = 8.dp
                                            )
                                    ) {
                                        Text(
                                            text = "$readableKey ${i + 1}",
                                            fontFamily = Switzer,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            color = ColorBase800
                                        )
                                    }
                                )
                                // Recursively flatten array objects
                                val arrayJsonObject = jsonArray.getJSONObject(i)
                                res.addAll(
                                    flattenedRowDisplayer(
                                        arrayJsonObject,
                                        filter,
                                        nestingLevel + 1
                                    )
                                )
                            }

                            else -> {
                                // Primitive array values
                                val value = jsonArray.get(i).toString()
                                val fieldType = getCredentialFieldType(readableKey, value)
                                val formattedValue = formatCredentialFieldValue(
                                    value,
                                    fieldType,
                                    key,
                                    maxLength = 100
                                )
                                res.add(
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                start = (nestingLevel * 5).dp,
                                                bottom = 8.dp,
                                            )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp),
                                        ) {
                                            Text(
                                                text = "$readableKey ${i + 1}",
                                                fontFamily = Switzer,
                                                fontWeight = FontWeight.Normal,
                                                fontSize = 14.sp,
                                                color = ColorStone600,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Box(
                                                modifier = Modifier.weight(1f),
                                                contentAlignment = Alignment.CenterEnd
                                            ) {
                                                RenderCredentialFieldValue(
                                                    fieldType = fieldType,
                                                    rawFieldValue = value,
                                                    formattedValue = formattedValue,
                                                    displayName = readableKey
                                                )
                                            }
                                        }

                                        HorizontalDivider(
                                            modifier = Modifier.padding(top = 8.dp),
                                            color = ColorStone200,
                                            thickness = 1.dp
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
        }
    }

    return res.toList()
}
