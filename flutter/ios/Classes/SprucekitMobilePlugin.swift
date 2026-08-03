import Flutter
import SpruceIDMobileSdkRs
import UIKit

/// SprucekitMobilePlugin
///
/// Flutter plugin providing access to SpruceKit Mobile SDK functionality.
public class SprucekitMobilePlugin: NSObject, FlutterPlugin {

    /// Dynamic credential providers registered by the host application.
    /// Providers issue per-presentation credentials natively; offers surface
    /// through `Oid4vp.getDynamicOffers` and selected offers are issued
    /// during `Oid4vp.submitResponseWithOffers`.
    ///
    /// Register before any OID4VP flow starts; a snapshot is taken per
    /// `Oid4vp.createHolder` and lives for that holder. Global to the
    /// process; lock-guarded so a late registration cannot race an in-flight
    /// `createHolder` snapshot.
    public static var dynamicCredentialProviders: [DynamicCredentialProvider] {
        get {
            providersLock.lock()
            defer { providersLock.unlock() }
            return _dynamicCredentialProviders
        }
        set {
            providersLock.lock()
            defer { providersLock.unlock() }
            _dynamicCredentialProviders = newValue
        }
    }

    private static let providersLock = NSLock()
    private static var _dynamicCredentialProviders: [DynamicCredentialProvider] = []

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

        // Initialize VCALM adapter (needs access to credential pack adapter)
        let vcalmAdapter = VcalmAdapter(credentialPackAdapter: credentialPackAdapter)
        VcalmSetup.setUp(binaryMessenger: messenger, api: vcalmAdapter)

        // Initialize SpruceUtils adapter (needs access to credential pack adapter)
        let spruceUtilsAdapter = SpruceUtilsAdapter(credentialPackAdapter: credentialPackAdapter)
        SpruceUtilsSetup.setUp(binaryMessenger: messenger, api: spruceUtilsAdapter)

        // Initialize mDL Presentation adapter (needs access to credential pack adapter)
        let mdlPresentationAdapter = MdlPresentationAdapter(credentialPackAdapter: credentialPackAdapter)
        let mdlCallback = MdlPresentationCallback(binaryMessenger: messenger)
        mdlPresentationAdapter.setCallback(mdlCallback)
        MdlPresentationSetup.setUp(binaryMessenger: messenger, api: mdlPresentationAdapter)

        // Initialize mDL Reader adapter (NFC + QR engagement → BLE session)
        let mdlReaderAdapter = MdlReaderAdapter()
        let mdlReaderCallback = MdlReaderCallback(binaryMessenger: messenger)
        mdlReaderAdapter.setCallback(mdlReaderCallback)
        MdlReaderSetup.setUp(binaryMessenger: messenger, api: mdlReaderAdapter)

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
