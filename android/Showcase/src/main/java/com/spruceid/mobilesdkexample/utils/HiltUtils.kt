package com.spruceid.mobilesdkexample.utils

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel

/**
 * A helper function to get a Hilt ViewModel scoped to the Activity.
 * This ensures that all Composable within the same Activity share the same ViewModel instance.
 *
 * Usage:
 * ```
 * val viewModel: MyViewModel = activityHiltViewModel()
 * ```
 */
@Composable
inline fun <reified VM : ViewModel> activityHiltViewModel(): VM {
    val activity = LocalActivity.current as ComponentActivity
    return hiltViewModel(activity)
} 