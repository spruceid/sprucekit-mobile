package com.spruceid.sprucekit_mobile

import io.flutter.embedding.engine.plugins.FlutterPlugin

/**
 * SprucekitMobilePlugin
 *
 * Flutter plugin providing access to SpruceKit Mobile SDK functionality.
 */
class SprucekitMobilePlugin : FlutterPlugin {
    private lateinit var oid4vciAdapter: Oid4vciAdapter
    private lateinit var credentialPackAdapter: CredentialPackAdapter
    private lateinit var oid4vpAdapter: Oid4vpAdapter
    private lateinit var spruceUtilsAdapter: SpruceUtilsAdapter
    private lateinit var mdlPresentationAdapter: MdlPresentationAdapter
    private lateinit var oid4vpMdocAdapter: Oid4vpMdocAdapter

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

        // Initialize OID4VP mDoc adapter (ISO 18013-7)
        oid4vpMdocAdapter = Oid4vpMdocAdapter(context, credentialPackAdapter)
        Oid4vpMdoc.setUp(flutterPluginBinding.binaryMessenger, oid4vpMdocAdapter)

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
        Oid4vpMdoc.setUp(binding.binaryMessenger, null)
    }
}
