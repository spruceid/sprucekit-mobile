import Flutter
import UIKit

/// SprucekitMobilePlugin
///
/// Flutter plugin providing access to SpruceKit Mobile SDK functionality.
public class SprucekitMobilePlugin: NSObject, FlutterPlugin {

    public static func register(with registrar: FlutterPluginRegistrar) {
        let messenger = registrar.messenger()

        // Initialize OID4VCI adapter
        let oid4vciAdapter = Oid4vciAdapter()
        Oid4vciSetup.setUp(binaryMessenger: messenger, api: oid4vciAdapter)

        // Initialize CredentialPack adapter
        let credentialPackAdapter = CredentialPackAdapter()
        CredentialPackSetup.setUp(binaryMessenger: messenger, api: credentialPackAdapter)

        // Initialize OID4VP adapter (needs access to credential pack adapter)
        let oid4vpAdapter = Oid4vpAdapter(credentialPackAdapter: credentialPackAdapter)
        Oid4vpSetup.setUp(binaryMessenger: messenger, api: oid4vpAdapter)
    }
}
