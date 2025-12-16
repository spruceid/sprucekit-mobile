package com.spruceid.sprucekit_mobile

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.spruceid.mobile.sdk.ui.MRZScanner
import com.spruceid.mobile.sdk.ui.PDF417Scanner
import com.spruceid.mobile.sdk.ui.QRCodeScanner
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Factory for creating scanner platform views
 */
class ScannerPlatformViewFactory(
    private val messenger: BinaryMessenger
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val creationParams = args as? Map<*, *>
        return ScannerPlatformView(context, viewId, messenger, creationParams)
    }
}

/**
 * Platform view wrapper for the scanner
 *
 * ComposeView requires a LifecycleOwner, SavedStateRegistryOwner, and ViewModelStoreOwner
 * to function properly. Since Flutter's Platform View doesn't provide these,
 * we create our own implementation using a wrapper FrameLayout that provides
 * these owners to the ComposeView.
 */
class ScannerPlatformView(
    context: Context,
    viewId: Int,
    messenger: BinaryMessenger,
    creationParams: Map<*, *>?
) : PlatformView {

    private val channel: MethodChannel = MethodChannel(
        messenger,
        "com.spruceid.sprucekit_mobile/scanner_$viewId"
    )

    private val scannerType: String = creationParams?.get("type") as? String ?: "qrCode"
    private val title: String = creationParams?.get("title") as? String ?: "Scan QR Code"
    private val subtitle: String = creationParams?.get("subtitle") as? String ?: "Please align within the guides"

    // Create the lifecycle-aware container
    private val lifecycleOwnerView = ComposeLifecycleOwnerView(context)

    // Coroutine scope for recomposer
    private val coroutineContext = AndroidUiDispatcher.CurrentThread + SupervisorJob()
    private val coroutineScope = CoroutineScope(coroutineContext)
    private val recomposer = Recomposer(coroutineContext)

    private val composeView: ComposeView

    init {
        composeView = ComposeView(context).apply {
            // Set composition context to our custom recomposer
            compositionContext = recomposer

            setContent {
                ScannerContent(
                    scannerType = scannerType,
                    title = title,
                    subtitle = subtitle,
                    onRead = { content ->
                        channel.invokeMethod("onRead", content)
                    },
                    onCancel = {
                        channel.invokeMethod("onCancel", null)
                    }
                )
            }
        }

        // Add ComposeView to the lifecycle-aware container
        lifecycleOwnerView.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Start the recomposer
        coroutineScope.launch {
            recomposer.runRecomposeAndApplyChanges()
        }

        // Resume lifecycle
        lifecycleOwnerView.resume()
    }

    override fun getView(): View = lifecycleOwnerView

    override fun dispose() {
        lifecycleOwnerView.destroy()
        recomposer.cancel()
        coroutineScope.cancel()
    }
}

/**
 * A FrameLayout that implements LifecycleOwner, SavedStateRegistryOwner, and ViewModelStoreOwner.
 * This provides the necessary tree owners for ComposeView to function properly
 * when embedded in Flutter's Platform View system.
 */
private class ComposeLifecycleOwnerView(
    context: Context
) : FrameLayout(context), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    init {
        // Initialize saved state before any view operations
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)

        // Set tree owners on this view so child views can find them
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        setViewTreeViewModelStoreOwner(this)

        // Move to CREATED state
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun resume() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}

@Composable
private fun ScannerContent(
    scannerType: String,
    title: String,
    subtitle: String,
    onRead: (String) -> Unit,
    onCancel: () -> Unit
) {
    when (scannerType) {
        "qrCode" -> QRCodeScanner(
            title = title,
            subtitle = subtitle,
            onRead = onRead,
            onCancel = onCancel
        )
        "pdf417" -> PDF417Scanner(
            title = title,
            subtitle = subtitle,
            onRead = onRead,
            onCancel = onCancel
        )
        "mrz" -> MRZScanner(
            title = title,
            subtitle = subtitle,
            onRead = onRead,
            onCancel = onCancel
        )
        else -> QRCodeScanner(
            title = title,
            subtitle = subtitle,
            onRead = onRead,
            onCancel = onCancel
        )
    }
}
