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

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        val context = flutterPluginBinding.applicationContext

        // Initialize OID4VCI adapter
        oid4vciAdapter = Oid4vciAdapter(context)
        Oid4vci.setUp(flutterPluginBinding.binaryMessenger, oid4vciAdapter)

        // Initialize CredentialPack adapter
        credentialPackAdapter = CredentialPackAdapter(context)
        CredentialPack.setUp(flutterPluginBinding.binaryMessenger, credentialPackAdapter)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Oid4vci.setUp(binding.binaryMessenger, null)
        CredentialPack.setUp(binding.binaryMessenger, null)
    }
}