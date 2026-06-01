package com.spruceid.sprucekit_mobile

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

/**
 * SprucekitMobilePlugin
 *
 * Flutter plugin providing access to SpruceKit Mobile SDK functionality.
 *
 * Implements [ActivityAware] so that adapters which need an [android.app.Activity]
 * reference (currently [MdlReaderAdapter] for NFC reader-mode) can receive
 * the binding when the plugin attaches to a host Activity.
 */
class SprucekitMobilePlugin : FlutterPlugin, ActivityAware {
    private lateinit var oid4vciAdapter: Oid4vciAdapter
    private lateinit var credentialPackAdapter: CredentialPackAdapter
    private lateinit var oid4vpAdapter: Oid4vpAdapter
    private lateinit var spruceUtilsAdapter: SpruceUtilsAdapter
    private lateinit var mdlPresentationAdapter: MdlPresentationAdapter
    private lateinit var mdlReaderAdapter: MdlReaderAdapter
    private lateinit var oid4vpMdocAdapter: Oid4vpMdocAdapter
    private lateinit var dcApiAdapter: DcApiAdapter

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        val context = flutterPluginBinding.applicationContext

        // Initialize OID4VCI adapter
        oid4vciAdapter = Oid4vciAdapter(context)
        Oid4vci.setUp(flutterPluginBinding.binaryMessenger, oid4vciAdapter)

        // Initialize CredentialPack adapter
        credentialPackAdapter = CredentialPackAdapter(context)
        CredentialPack.setUp(flutterPluginBinding.binaryMessenger, credentialPackAdapter)

        // Initialize OID4VP adapter (needs access to credential pack adapter)
        oid4vpAdapter = Oid4vpAdapter(context, credentialPackAdapter)
        Oid4vp.setUp(flutterPluginBinding.binaryMessenger, oid4vpAdapter)

        // Initialize SpruceUtils adapter (needs access to credential pack adapter)
        spruceUtilsAdapter = SpruceUtilsAdapter(context, credentialPackAdapter)
        SpruceUtils.setUp(flutterPluginBinding.binaryMessenger, spruceUtilsAdapter)

        // Initialize mDL Presentation adapter (needs access to credential pack adapter)
        mdlPresentationAdapter = MdlPresentationAdapter(context, credentialPackAdapter)
        val mdlCallback = MdlPresentationCallback(flutterPluginBinding.binaryMessenger)
        mdlPresentationAdapter.setCallback(mdlCallback)
        MdlPresentation.setUp(flutterPluginBinding.binaryMessenger, mdlPresentationAdapter)

        // Initialize mDL Reader adapter (NFC + QR engagement → BLE session)
        mdlReaderAdapter = MdlReaderAdapter(context)
        val mdlReaderCallback = MdlReaderCallback(flutterPluginBinding.binaryMessenger)
        mdlReaderAdapter.setCallback(mdlReaderCallback)
        MdlReader.setUp(flutterPluginBinding.binaryMessenger, mdlReaderAdapter)

        // Initialize OID4VP mDoc adapter (ISO 18013-7)
        oid4vpMdocAdapter = Oid4vpMdocAdapter(context, credentialPackAdapter)
        Oid4vpMdoc.setUp(flutterPluginBinding.binaryMessenger, oid4vpMdocAdapter)

        // Initialize DC API adapter (needs access to credential pack adapter)
        dcApiAdapter = DcApiAdapter(context, credentialPackAdapter)
        DcApi.setUp(flutterPluginBinding.binaryMessenger, dcApiAdapter)

        // Register Scanner Platform View
        flutterPluginBinding.platformViewRegistry.registerViewFactory(
            "com.spruceid.sprucekit_mobile/scanner",
            ScannerPlatformViewFactory(flutterPluginBinding.binaryMessenger)
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Oid4vci.setUp(binding.binaryMessenger, null)
        CredentialPack.setUp(binding.binaryMessenger, null)
        Oid4vp.setUp(binding.binaryMessenger, null)
        SpruceUtils.setUp(binding.binaryMessenger, null)
        MdlPresentation.setUp(binding.binaryMessenger, null)
        MdlReader.setUp(binding.binaryMessenger, null)
        Oid4vpMdoc.setUp(binding.binaryMessenger, null)
        DcApi.setUp(binding.binaryMessenger, null)
        if (::mdlReaderAdapter.isInitialized) {
            // Cancels mainScope + releases NFC/BLE handles so callbacks can't
            // try to invoke the Flutter binary messenger after it's gone.
            mdlReaderAdapter.dispose()
        }
    }

    // ----- ActivityAware -----

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        mdlReaderAdapter.setActivityBinding(binding)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        mdlReaderAdapter.setActivityBinding(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        mdlReaderAdapter.setActivityBinding(null)
    }

    override fun onDetachedFromActivity() {
        mdlReaderAdapter.setActivityBinding(null)
    }
}
