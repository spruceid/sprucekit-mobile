package com.spruceid.mobilesdkexample.credentials

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CredentialDate(dateString: String) {
    var date by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // date time iso8601
        try {
            val ISO8601DateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]Z")
            val parsedDate = OffsetDateTime.parse(dateString, ISO8601DateFormat)
            val localZoneParsedDate = parsedDate.atZoneSameInstant(ZoneId.systemDefault())
            val dateTimeFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' h:mm a")
            date = localZoneParsedDate.format(dateTimeFormatter)
            return@LaunchedEffect
        } catch (_: Exception) {
        }
        
        // date time yyyy-MM-dd'T'HH:mm:ss.SSSSSSSS'Z'
        try {
            val parsedDate = OffsetDateTime.parse(dateString)
            val localZoneParsedDate = parsedDate.atZoneSameInstant(ZoneId.systemDefault())
            val dateTimeFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' h:mm a")
            date = localZoneParsedDate.format(dateTimeFormatter)
            return@LaunchedEffect
        } catch (_: Exception) {
        }

        // date only
        try {
            val zonedDateTime = ZonedDateTime.parse(dateString)
            val dateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy")
            date = dateFormat.format(zonedDateTime)
            return@LaunchedEffect
        } catch (_: Exception) {
        }

        // timestamp
        try {
            val timestamp = dateString.toDouble()
            val instant = Instant.ofEpochSecond(timestamp.toLong())
            val dateTime = instant.atZone(ZoneOffset.UTC)
            val dateFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy").withLocale(Locale.US)
            date = dateFormatter.format(dateTime)
            return@LaunchedEffect
        } catch (_: Exception){
        }

        date = dateString
    }

    date?.let {
        Text(
            it,
            fontFamily = Inter,
            fontWeight = FontWeight.Normal,
            fontSize = 17.sp,
            color = ColorStone950,
        )
    }
}