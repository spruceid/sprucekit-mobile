import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct GenericCredentialItem: ICredentialView {
    let credentialPack: CredentialPack
    let onDelete: (() -> Void)?
    let hasConnection: Binding<Bool>

    @State var sheetOpen: Bool = false
    @State var optionsOpen: Bool = false
    @State private var credentialStatus: CredentialStatusList =
        CredentialStatusList.undefined

    init(
        rawCredential: String,
        onDelete: (() -> Void)? = nil,
        hasConnection: Binding<Bool>? = nil
    ) {
        self.onDelete = onDelete
        self.hasConnection = hasConnection ?? .constant(true)
        do {
            self.credentialPack = try addCredential(
                credentialPack: CredentialPack(), rawCredential: rawCredential)
        } catch {
            print(error)
            self.credentialPack = CredentialPack()
        }
    }

    init(
        credentialPack: CredentialPack,
        onDelete: (() -> Void)? = nil,
        hasConnection: Binding<Bool>? = nil
    ) {
        self.onDelete = onDelete
        self.hasConnection = hasConnection ?? .constant(true)
        self.credentialPack = credentialPack
    }

    func getStatus() async -> CredentialStatusList {
        let statusLists = await credentialPack.getStatusListsAsync(
            hasConnection: hasConnection.wrappedValue)
        if statusLists.isEmpty {
            return CredentialStatusList.undefined
        } else {
            return statusLists.first?.value ?? CredentialStatusList.unknown
        }
    }

    @ViewBuilder
    func descriptionFormatter(values: [String: [String: GenericJSON]])
        -> some View
    {
        let credential =
            values.first(where: {
                let credential = credentialPack.get(credentialId: $0.key)
                return credential?.asJwtVc() != nil
                    || credential?.asJsonVc() != nil
                    || credential?.asSdJwt() != nil
            }).map { $0.value } ?? [:]

        var description = ""
        if let issuerName = credential["issuer"]?.dictValue?["name"]?.toString()
        {
            description = issuerName
        } else if let descriptionString = credential["description"]?.toString()
        {
            description = descriptionString
        }
        return VStack(alignment: .leading, spacing: 12) {
            Text(description)
                .font(.customFont(font: .inter, style: .regular, size: .p))
                .foregroundStyle(Color("ColorStone600"))
                .padding(.top, 4)
            CredentialStatusSmall(status: credentialStatus)
        }
        .padding(.leading, 12)
    }

    @ViewBuilder
    func leadingIconFormatter(values: [String: [String: GenericJSON]])
        -> some View
    {
        let credential =
            values.first(where: {
                let credential = credentialPack.get(credentialId: $0.key)
                return credential?.asJwtVc() != nil
                    || credential?.asJsonVc() != nil
                    || credential?.asSdJwt() != nil
            }).map { $0.value } ?? [:]

        let issuerImg = credential["issuer"]?.dictValue?["image"]
        var stringValue = ""

        if let dictValue = issuerImg?.dictValue {
            if let imageValue = dictValue["image"]?.toString() {
                stringValue = imageValue
            } else if let idValue = dictValue["id"]?.toString() {
                stringValue = idValue
            } else {
                stringValue = ""
            }
        } else {
            stringValue = issuerImg?.toString() ?? ""
        }

        return CredentialImage(image: stringValue)
    }

    @ViewBuilder
    func listItem() -> some View {
        Card(
            credentialPack: credentialPack,
            rendering: CardRendering.list(
                CardRenderingListView(
                    titleKeys: ["name", "type"],
                    titleFormatter: { (values) in
                        let credential =
                            values.first(where: {
                                let credential = credentialPack.get(
                                    credentialId: $0.key)
                                return credential?.asJwtVc() != nil
                                    || credential?.asJsonVc() != nil
                                    || credential?.asSdJwt() != nil
                            }).map { $0.value } ?? [:]

                        var title = credential["name"]?.toString()
                        if title == nil {
                            credential["type"]?.arrayValue?.forEach {
                                if $0.toString() != "VerifiableCredential" {
                                    title = $0.toString().camelCaseToWords()
                                    return
                                }
                            }
                        }
                    
                    return VStack(alignment: .leading, spacing: 12) {
                        Text(title ?? "")
                            .font(.customFont(font: .inter, style: .semiBold, size: .h1))
                            .foregroundStyle(Color("ColorStone950"))
                    }
                    .padding(.leading, 12)
                },
                descriptionKeys: ["description", "issuer"],
                descriptionFormatter: descriptionFormatter,
                leadingIconKeys: ["issuer"],
                leadingIconFormatter: leadingIconFormatter
            ))
        )
    }

    @ViewBuilder
    func listItemWithOptions() -> some View {
        Card(
            credentialPack: credentialPack,
            rendering: CardRendering.list(
                CardRenderingListView(
                    titleKeys: ["name", "type"],
                    titleFormatter: { (values) in
                        let credential =
                            values.first(where: {
                                let credential = credentialPack.get(
                                    credentialId: $0.key)
                                return credential?.asJwtVc() != nil
                                    || credential?.asJsonVc() != nil
                                    || credential?.asSdJwt() != nil
                            }).map { $0.value } ?? [:]

                        var title = credential["name"]?.toString()
                        if title == nil {
                            credential["type"]?.arrayValue?.forEach {
                                if $0.toString() != "VerifiableCredential" {
                                    title = $0.toString().camelCaseToWords()
                                    return
                                }
                            }
                        }

                        return ZStack(alignment: .topLeading) {
                            HStack(alignment: .top) {
                                Spacer()
                                VStack {
                                    Image("ThreeDotsHorizontal")
                                    Spacer()
                                }
                                .frame(width: 24, height: 24)
                                .onTapGesture {
                                    optionsOpen = true
                                }
                            }
                            .padding(.trailing, -12)
                            HStack {
                                Text(title ?? "")
                                    .padding(.trailing, 12)
                                    .font(.customFont(font: .inter, style: .semiBold, size: .h1))
                                    .foregroundStyle(Color("ColorStone950"))
                            }
                            .confirmationDialog(
                                Text("Credential Options"),
                                isPresented: $optionsOpen,
                                titleVisibility: .visible,
                                actions: {
                                    CredentialOptionsDialogActions(
                                        onDelete: onDelete,
                                        exportFileName: "\(title ?? "credential").json",
                                        credentialPack: credentialPack
                                    )
                                }
                            )
                        }
                        .padding(.leading, 12)
                },
                descriptionKeys: ["description", "issuer"],
                descriptionFormatter: descriptionFormatter,
                leadingIconKeys: ["issuer"],
                leadingIconFormatter: leadingIconFormatter
            ))
        )
    }

    @ViewBuilder
    public func credentialDetails() -> any View {
        Card(
            credentialPack: credentialPack,
            rendering: CardRendering.details(
                CardRenderingDetailsView(
                    fields: [
                        CardRenderingDetailsField(
                            keys: [],
                            formatter: { (values) in
                                let credential =
                                    values.first(where: {
                                        let credential = credentialPack.get(
                                            credentialId: $0.key)
                                        return credential?.asJwtVc() != nil
                                            || credential?.asJsonVc() != nil
                                            || credential?.asSdJwt() != nil
                                    }).map { $0.value } ?? [:]

                                return VStack(alignment: .leading, spacing: 20)
                                {
                                    CredentialStatus(status: credentialStatus)
                                    CredentialObjectDisplayer(dict: credential)
                                        .padding(.horizontal, 4)
                                }
                            })
                    ]
                ))
        )
        .padding(.all, 12)
    }

    @ViewBuilder
    public func credentialListItem(withOptions: Bool = false) -> any View {
        VStack {
            VStack {
                if withOptions {
                    listItemWithOptions()
                } else {
                    listItem()
                }
            }
            .padding(12)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color("ColorBase300"), lineWidth: 1)
            )
            .padding(.all, 12)
        }
    }

    @ViewBuilder
    public func credentialReviewInfo() -> any View {
        VStack {
            Text("Review Info")
                .font(.customFont(font: .inter, style: .bold, size: .h0))
                .padding(.horizontal, 20)
                .foregroundStyle(Color("ColorStone950"))
            AnyView(credentialListItem(withOptions: false))
                .frame(height: 100)
            ScrollView(.vertical, showsIndicators: false) {
                AnyView(credentialDetails())
            }
        }
        .onAppear(perform: {
            Task {
                credentialStatus = await getStatus()
            }
        })
    }

    @ViewBuilder
    public func credentialPreviewAndDetails() -> any View {
        AnyView(credentialListItem(withOptions: true))
            .onTapGesture {
                sheetOpen.toggle()
            }
            .sheet(isPresented: $sheetOpen) {

            } content: {
                AnyView(credentialReviewInfo())
                    .padding(.top, 25)
                    .presentationDetents([.fraction(0.85)])
                    .presentationDragIndicator(.automatic)
                    .presentationBackgroundInteraction(.automatic)
            }
            .onAppear(perform: {
                Task {
                    credentialStatus = await getStatus()
                }
            })
    }

    var body: some View {
        AnyView(credentialPreviewAndDetails())
    }
}
