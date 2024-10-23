import CryptoKit
import Foundation
import SpruceIDMobileSdkRs

public typealias MDocNamespace = String
public typealias IssuerSignedItemBytes = Data
public typealias ItemsRequest = SpruceIDMobileSdkRs.ItemsRequest

public class MDoc: Credential {
  var inner: SpruceIDMobileSdkRs.Mdoc
  var keyAlias: String

  /// issuerAuth is the signed MSO (i.e. CoseSign1 with MSO as payload)
  /// namespaces is the full set of namespaces with data items and their value
  /// IssuerSignedItemBytes will be bytes, but its composition is defined here
  /// https://github.com/spruceid/isomdl/blob/f7b05dfa/src/definitions/issuer_signed.rs#L18
  public init?(
    fromMDoc mdocBytes: Data, keyAlias: String
  ) {
    self.keyAlias = keyAlias
    do {
      try self.inner = SpruceIDMobileSdkRs.Mdoc.fromCborEncodedDocument(
        cborEncodedDocument: mdocBytes, keyAlias: keyAlias)
    } catch {
      print("\(error)")
      return nil
    }
    super.init(id: inner.id())
  }

  public init(Mdoc mdoc: SpruceIDMobileSdkRs.Mdoc) {
    self.keyAlias = mdoc.keyAlias()
    self.inner = mdoc
    super.init(id: inner.id())
  }
}
