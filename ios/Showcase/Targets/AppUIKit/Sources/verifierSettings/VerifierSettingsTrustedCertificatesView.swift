import SwiftUI
import UniformTypeIdentifiers

struct VerifierSettingsTrustedCertificates: Hashable {}

struct VerifierSettingsTrustedCertificatesView: View {
    @Binding var path: NavigationPath

    func onBack() {
        path.removeLast()
    }

    var body: some View {
        VStack {
            VerifierSettingsTrustedCertificatesHeader(onBack: onBack)
            VerifierSettingsTrustedCertificatesBody()
        }
        .navigationBarBackButtonHidden(true)
    }
}

struct VerifierSettingsTrustedCertificatesHeader: View {
    var onBack: () -> Void

    var body: some View {
        HStack {
            Image("Chevron")
                .rotationEffect(.degrees(90))
                .padding(.leading, 30)
            Text("Trusted Certificates")
                .font(.customFont(font: .inter, style: .bold, size: .h2))
                .foregroundStyle(Color("ColorStone950"))
                .padding(.leading, 10)
            Spacer()
        }
        .onTapGesture {
            onBack()
        }
        .padding(.top, 10)
    }
}

struct VerifierSettingsTrustedCertificatesBody: View {
    @State var trustedCertificates = TrustedCertificatesDataStore.shared
        .getAllCertificates()

    @State private var selectedFiles: [(String, String)] = []
    @State private var showFilePicker = false
    @State private var isPresented = false
    @State private var alertContent = ""

    func loadCertificates() {
        trustedCertificates = TrustedCertificatesDataStore.shared
            .getAllCertificates()
    }

    var body: some View {
        VStack {
            HStack {
                Spacer()
                Text("+ New Certificate")
                    .font(
                        .customFont(font: .inter, style: .semiBold, size: .h4)
                    )
                    .foregroundStyle(Color("ColorBlue600"))
                    .onTapGesture {
                        showFilePicker = true
                    }
            }
            if trustedCertificates.isEmpty {
                VStack {
                    Text("No Trusted Certificates Found")
                        .font(
                            .customFont(
                                font: .inter, style: .regular, size: .h2)
                        )
                        .foregroundColor(Color("ColorStone400"))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView(.vertical, showsIndicators: false) {
                    VStack(alignment: .leading) {
                        ForEach(trustedCertificates, id: \.self) {
                            item in
                            HStack {
                                Text(item.name)
                                    .font(
                                        .customFont(
                                            font: .inter, style: .bold,
                                            size: .h4)
                                    )
                                    .foregroundColor(Color("ColorStone950"))
                                    .onTapGesture {
                                        isPresented = true
                                        alertContent = item.content
                                    }
                                Spacer()
                                Image(systemName: "trash")
                                    .foregroundColor(Color("ColorStone950"))
                                    .font(.system(size: 20))
                                    .onTapGesture {
                                        _ = TrustedCertificatesDataStore.shared
                                            .delete(id: item.id)
                                        loadCertificates()
                                    }
                            }

                            Divider()
                        }
                    }
                    .padding(.bottom, 10)
                }
                .padding(.top, 20)
            }
        }
        .padding(.top, 20)
        .padding(.horizontal, 30)
        .fileImporter(
            isPresented: $showFilePicker,
            allowedContentTypes: [UTType.x509Certificate],
            allowsMultipleSelection: true
        ) { result in
            handleFileSelection(result: result)
        }
        .overlay(content: {
            SimpleAlertDialog(
                isPresented: $isPresented,
                message: alertContent
            )
        })
    }

    private func handleFileSelection(result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            urls
                .compactMap { readFileContent(from: $0) }
                .forEach { file in
                    _ = TrustedCertificatesDataStore.shared.insert(
                        name: file.0,
                        content: file.1
                    )
                }
        case .failure(let error):
            ToastManager.shared.showError(
                message: "Error selecting file: \(error.localizedDescription)")
        }
        loadCertificates()
    }

    private func readFileContent(from url: URL) -> (String, String)? {
        do {
            if url.startAccessingSecurityScopedResource() {
                defer { url.stopAccessingSecurityScopedResource() }

                let content = try String(contentsOf: url, encoding: .utf8)
                return (url.lastPathComponent, content)
            } else {
                ToastManager.shared.showError(
                    message: "Error accessing file: \(url.lastPathComponent)")
                return nil
            }
        } catch {
            ToastManager.shared.showError(
                message: "Error reading file: \(error.localizedDescription)")
            return nil
        }
    }
}
