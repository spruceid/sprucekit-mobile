package com.spruceid.mobilesdkexample.utils

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ui.theme.ColorStone500
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.Inter

@Composable
fun Accordion(
    title: String,
    startExpanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(startExpanded) }
    val density = LocalDensity.current

    AccordionHeader(title = title, isExpanded = expanded) {
        expanded = !expanded
    }
    AnimatedVisibility(visible = expanded,
        enter = slideInVertically {
            with(density) { -40.dp.roundToPx() }
        } + expandVertically(
            expandFrom = Alignment.Top
        ) + fadeIn(
            initialAlpha = 0.3f
        ),
        exit = slideOutVertically() + shrinkVertically() + fadeOut()

    ) {
        Column(content = content, modifier = modifier)
    }
}

@Composable
private fun AccordionHeader(
    title: String,
    isExpanded: Boolean = false,
    onTapped: () -> Unit = {}
) {
    val degrees = if (!isExpanded) 90f else 270f

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onTapped() },
    ) {
        Text(
            title,
            fontFamily = Inter,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            color = ColorStone500,
        )
        Icon(
            painter = painterResource(id = R.drawable.chevron),
            contentDescription = "Collapse menu button",
            tint = ColorStone600,
            modifier = Modifier
                .rotate(degrees)
                .height(12.dp)
        )
    }
}