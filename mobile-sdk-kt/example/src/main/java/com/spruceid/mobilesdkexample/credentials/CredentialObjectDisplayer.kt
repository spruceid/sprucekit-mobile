package com.spruceid.mobilesdkexample.credentials

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.ui.theme.ColorStone500
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.Accordion
import com.spruceid.mobilesdkexample.utils.isDate
import com.spruceid.mobilesdkexample.utils.isImage
import com.spruceid.mobilesdkexample.utils.removeUnderscores
import com.spruceid.mobilesdkexample.utils.splitCamelCase
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
                res.add(0,
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
                                            key.splitCamelCase().removeUnderscores(),
                                            fontFamily = Inter,
                                            fontWeight = FontWeight.Normal,
                                            fontSize = 16.sp,
                                            color = ColorStone500,
                                        )
                                    }
                                    Text(
                                        jsonArray.get(i).toString(),
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
                res.add(0,
                    Column(
                        Modifier.padding(bottom = 12.dp)
                    ) {
                        Text(
                            key.splitCamelCase().removeUnderscores(),
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
                                value,
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

    return res.toList()
}
