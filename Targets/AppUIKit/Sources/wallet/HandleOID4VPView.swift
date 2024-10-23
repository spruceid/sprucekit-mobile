import SpruceIDMobileSdkRs
import SwiftUI

struct HandleOID4VP: Hashable {
    var url: String
}

struct HandleOID4VPView: View {
    @Binding var path: NavigationPath
    var url: String
    @State var rawCredentials: [String] = CredentialDataStore.shared.getAllRawCredentials()
    @State private var holder: Holder? = nil
    @State private var permissionRequest: PermissionRequest? = nil

    func credentialSelector(
        credentials: [ParsedCredential],
        onSelectedCredential: @escaping ([ParsedCredential]) -> Void
    ) {
        // TODO: Implement UI component for selecting a valid
        // credential for satisfying the permission request
    }
    
    func presentCredential() async {
        print("????? URL: \(url)")

        do {
            let credentials = rawCredentials.map { rawCredential in
                // TODO: Update to use VDC collection in the future
                // to detect the type of credential.
                do {
                    return try ParsedCredential.newSdJwt(sdJwtVc: Vcdm2SdJwt.newFromCompactSdJwt(input: rawCredential))
                } catch {
                    return nil
                }
            }.compactMap{ $0 }
                        
            print("#Credentials -- \(credentials.count)")

            let holder = try await Holder.newWithCredentials(
                providedCredentials: credentials, trustedDids: trustedDids)
            
            print("Holder -- \(holder)")

            let permissionRequest = try await holder.authorizationRequest(url: Url(url))
            
            print("PermissionRequest -- \(permissionRequest) --- # \(permissionRequest.credentials().count)")
            
            let permissionResponse = permissionRequest.createPermissionResponse(selectedCredential: credentials.first!)
            
            print("PermissionResponse -- \(permissionResponse)")
            
            _ = try await holder.submitPermissionResponse(response: permissionResponse)

        } catch {
            print("Error: \(error)")
        }
    }

    var body: some View {
        if permissionRequest == nil {
            // Show a loading screen
            ZStack {
                HStack(spacing: 0) {
                    Spacer()
                    Text("Loading... \(url)")
                        .font(.custom("Inter", size: 14))
                        .fontWeight(.regular)
                    Spacer()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.horizontal, 24)
            }
            .task {
                await presentCredential()
            }
        } else {
            // Load the Credential View
            ZStack {
                //credentialSelector(
                //    credentials: permissionRequest!.credentials()
                //) { selectedCredentials in
                //    Task {
                //        do {
                //            guard let selectedCredential = selectedCredentials.first else { return }
                //            let permissionResponse = permissionRequest!.createPermissionResponse(
                //                selectedCredential: selectedCredential)

                //            print("Submitting permission response")

                //            holder!.submitPermissionResponse(response: permissionResponse)
                //        } catch {
                //            print("Error: \(error)")
                //        }
                //    }
                //}
            }
        }
    }
}
