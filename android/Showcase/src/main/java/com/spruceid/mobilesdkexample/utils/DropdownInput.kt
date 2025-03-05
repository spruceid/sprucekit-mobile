package com.spruceid.mobilesdkexample.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.ColorBase300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone500
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.MobileSdkTheme

@Composable
fun DropdownInput(
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var selected by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var buttonSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopStart
    ) {
        // Input field
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    buttonSize = coordinates.size
                }
                .border(
                    width = 1.dp,
                    color = ColorBase300,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(
                    start = 16.dp,
                    end = 10.dp,
                    top = 10.dp,
                    bottom = 10.dp
                )
                .clickable {
                    expanded = !expanded
                }
        ) {
            if (selected.isEmpty()) {
                Text(
                    text = "Select...",
                    color = ColorStone500
                )
            } else {
                Text(
                    text = selected,
                    color = ColorStone950
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                painter = painterResource(id = R.drawable.chevron),
                contentDescription = "Collapse menu button",
                tint = ColorStone600,
                modifier = Modifier
                    .rotate(90f)
                    .height(12.dp)
            )
        }

        // Dropdown menu
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(with(LocalDensity.current) {
                    buttonSize.width.toDp()
                })
                .background(ColorBase1)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        selected = option
                        onSelect(option)
                        expanded = false
                    },
                    colors = MenuDefaults.itemColors(textColor = ColorStone600),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DropdownInputPreview() {
    MobileSdkTheme {
        DropdownInput(
            options = listOf("Option 1", "Option 2", "Option 3"),
            onSelect = { }
        )
    }
}