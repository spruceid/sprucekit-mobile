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

        // Initialize SpruceUtils adapter (needs access to credential pack adapter)
        let spruceUtilsAdapter = SpruceUtilsAdapter(credentialPackAdapter: credentialPackAdapter)
        SpruceUtilsSetup.setUp(binaryMessenger: messenger, api: spruceUtilsAdapter)

        // Initialize mDL Presentation adapter (needs access to credential pack adapter)
        let mdlPresentationAdapter = MdlPresentationAdapter(credentialPackAdapter: credentialPackAdapter)
        let mdlCallback = MdlPresentationCallback(binaryMessenger: messenger)
        mdlPresentationAdapter.setCallback(mdlCallback)
        MdlPresentationSetup.setUp(binaryMessenger: messenger, api: mdlPresentationAdapter)

        // Initialize OID4VP mDoc adapter (ISO 18013-7)
        let oid4vpMdocAdapter = Oid4vpMdocAdapter(credentialPackAdapter: credentialPackAdapter)
        Oid4vpMdocSetup.setUp(binaryMessenger: messenger, api: oid4vpMdocAdapter)

        // Initialize DC API adapter (needs access to credential pack adapter)
        let dcApiAdapter = DcApiAdapter(credentialPackAdapter: credentialPackAdapter)
        DcApiSetup.setUp(binaryMessenger: messenger, api: dcApiAdapter)

        // Register Scanner Platform View
        let scannerFactory = ScannerPlatformViewFactory(messenger: messenger)
        registrar.register(
            scannerFactory,
            withId: "com.spruceid.sprucekit_mobile/scanner"
        )
    }
}
