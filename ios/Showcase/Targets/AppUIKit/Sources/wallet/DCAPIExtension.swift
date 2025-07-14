#if canImport(IdentityDocumentServices) && canImport(IdentityDocumentServicesUI)
import IdentityDocumentServices
import IdentityDocumentServicesUI
import SwiftUI
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

enum DCAPIState {
    case err(MdocOID4VPError)
    case selectCredential
    case selectiveDisclosure(RequestMatch180137, [ParsedCredential], URL)
}

@available(iOS 26.0, *)
public struct DocumentProviderExtensionView: View {
    @State private var state: DCAPIState = .selectCredential
    @State private var initLoad: MdocSelector? = nil;
    let context: ISO18013MobileDocumentRequestContext;
    
    public init(context: ISO18013MobileDocumentRequestContext) {
        self.context = context
    }

    public var body: some View {
        switch state {
        case .err(let e):
            ErrorView(
                errorTitle: e.title,
                errorDetails: e.details,
                onClose: {
                    context.cancel()
                }
            )
        case .selectCredential:
            if (initLoad == nil) {
                Text("Loading...")
                    .task {
                        do {
                            let presentmentRequests = context.request.presentmentRequests.map({presentmentRequest in
                                let requestSets = presentmentRequest.documentRequestSets.map({documentRequestSet in
                                    let requests = documentRequestSet.requests.map({request in
                                        let namespaces = Dictionary(uniqueKeysWithValues: request.namespaces.map({(key, value) in
                                            let v = Dictionary(uniqueKeysWithValues: value.map({(key, value) in
                                                let v = Iosiso18013MobileDocumentRequestElementInfo(isRetaining: value.isRetaining)
                                                return (key, v)
                                            }))
                                            return (key, v)
                                        }))
                                        return Iosiso18013MobileDocumentRequestDocumentRequest(documentType: request.documentType, namespaces: namespaces)
                                    })
                                    return Iosiso18013MobileDocumentRequestDocumentRequestSet(requests: requests)
                                })
                                return Iosiso18013MobileDocumentRequestPresentmentRequest(documentRequestSets: requestSets, isMandatory: presentmentRequest.isMandatory)
                            })
                            let document_request = Iosiso18013MobileDocumentRequest(presentmentRequests: presentmentRequests);
                            let credentials = try await CredentialPackObservable().loadAndUpdateAll().flatMap({pack in
                                pack.list()
                            })
                            let matches = document_request.toMatches(parsedCredentials: credentials)
                            let origin = context.requestingWebsiteOrigin!
                            initLoad = MdocSelector(
                                matches: matches,
                                onContinue: { match in
                                    state = .selectiveDisclosure(match, credentials, origin)
                                },
                                onCancel: {
                                    context.cancel()
                                }
                            )
                        } catch {
                            state = .err(MdocOID4VPError(
                                title: "Failed to load credentials",
                                details: error.localizedDescription
                            ))
                        }
                    }
            } else {
                initLoad
            }
        case .selectiveDisclosure(let selectedMatch, let credentials, let origin):
            MdocFieldSelector(
                match: selectedMatch,
                onContinue: { approvedResponse in
                    Task {
                        do {
                            try await context.sendResponse { rawRequest in
                                var origin = origin.absoluteString
                                if origin.last == "/" {
                                    let _ = origin.popLast()
                                }
                                let responseData = try await buildAnnexCResponse(request: rawRequest.requestData, origin: origin, selectedMatch: selectedMatch, parsedCredentials: credentials, approvedResponse: approvedResponse, keyStore: KeyManager())
                                return ISO18013MobileDocumentResponse(responseData: responseData)
                            }
                        } catch {
                            state = .err(MdocOID4VPError(
                                title: "Failed to selective disclose fields",
                                details: error.localizedDescription
                            ))
                        }
                    }
                },
                onCancel: {
                    context.cancel()
                }
            )
        }
    }
}
#endif
