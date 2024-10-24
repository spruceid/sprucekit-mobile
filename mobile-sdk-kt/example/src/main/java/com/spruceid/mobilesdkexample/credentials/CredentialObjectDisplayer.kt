package com.spruceid.mobilesdkexample.credentials

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.ui.theme.ColorStone500
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.removeUnderscores
import com.spruceid.mobilesdkexample.utils.splitCamelCase
import org.json.JSONArray
import org.json.JSONObject


@Composable
fun genericObjectDisplayer(obj: JSONObject, filter: List<String>, level: Int = 1): List<Unit> {

    fun tryGetJSONObject(key: String): JSONObject? {
        try {
            obj.getJSONObject(key).let {
                return it
            }
        } catch (_: Exception) {
            return null
        }
    }

    fun tryGetJSONArray(key: String): JSONArray? {
        try {
            obj.getJSONArray(key).let {
                return it
            }
        } catch (_: Exception) {
            return null
        }
    }

    fun tryGetJSONObjectFromJSONArray(idx: Int, jsonArray: JSONArray): JSONObject? {
        try {
            jsonArray.getJSONObject(idx).let {
                return it
            }
        } catch (_: Exception) {
            return null
        }
    }

    val res = mutableListOf<Unit>()

    obj
        .keys()
        .asSequence()
        .filter { !filter.contains(it) }
        .sorted()
        .forEach { key ->
            val jsonObject = tryGetJSONObject(key)
            if (jsonObject != null) {
                res.add(
                    Column {
                        Text(
                            key.splitCamelCase().removeUnderscores(),
                            fontFamily = Inter,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = ColorStone500,
                        )
                        Column(
                            Modifier
                                .drawWithCache {
                                    onDrawWithContent {

                                        // draw behind the content the vertical line on the left
                                        drawLine(
                                            color = Color.Black,
                                            start = Offset.Zero,
                                            end = Offset(0f, this.size.height),
                                            strokeWidth = 1f
                                        )

                                        // draw the content
                                        drawContent()
                                    }
                                }
                                .padding(start = (level * 4).dp)
                        ) {
                            genericObjectDisplayer(jsonObject, filter, level + 1)
                        }
                    }
                )
            } else {
                val jsonArray = tryGetJSONArray(key)
                if (jsonArray != null) {
                    for (i in 0 until jsonArray.length()) {
                        val jsonObjectElem = tryGetJSONObjectFromJSONArray(i, jsonArray)
                        if (jsonObjectElem != null) {
                            res.add(
                                Column {
                                    Text(
                                        "${key.splitCamelCase().removeUnderscores()}.$i",
                                        fontFamily = Inter,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = ColorStone500,
                                    )
                                    Column(
                                        Modifier
                                            .drawWithCache {
                                                onDrawWithContent {

                                                    // draw behind the content the vertical line on the left
                                                    drawLine(
                                                        color = Color.Black,
                                                        start = Offset.Zero,
                                                        end = Offset(0f, this.size.height),
                                                        strokeWidth = 1f
                                                    )

                                                    // draw the content
                                                    drawContent()
                                                }
                                            }
                                            .padding(start = (level * 4).dp)
                                    ) {
                                        genericObjectDisplayer(jsonObjectElem, filter, level + 1)
                                    }
                                }
                            )
                        }
                    }
                } else {
                    val value = obj.get(key).toString()
                    if (key.lowercase().contains("image") ||
                        key.lowercase().contains("portrait") ||
                        value.contains("data:image")
                    ) {
                        res.add(
                            Column(Modifier.padding(vertical = 10.dp)) {
                                Text(
                                    key.splitCamelCase().removeUnderscores(),
                                    fontFamily = Inter,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 16.sp,
                                    color = ColorStone500,
                                )
                                CredentialImage(value, key)
                            }
                        )
                    } else if (key.lowercase().contains("date") ||
                        key.lowercase().contains("from") ||
                        key.lowercase().contains("until")
                    ) {

                        res.add(
                            Column(Modifier.padding(vertical = 10.dp)) {
                                Text(
                                    key.splitCamelCase().removeUnderscores(),
                                    fontFamily = Inter,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 16.sp,
                                    color = ColorStone500,
                                )
                                CredentialDate(value)
                            }
                        )
                    } else {
                        res.add(
                            Column(Modifier.padding(vertical = 10.dp)) {
                                Text(
                                    key.splitCamelCase().removeUnderscores(),
                                    fontFamily = Inter,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 16.sp,
                                    color = ColorStone500
                                )
                                Text(
                                    value,
                                    fontFamily = Inter,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 17.sp,
                                    color = ColorStone950,
                                )
                            }
                        )
                    }

                }
            }
        }


    return res.toList()
}