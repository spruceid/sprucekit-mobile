import SwiftUI
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

struct AchievementCredentialItem: ICredentialView {
    let credentialPack: CredentialPack
    let onDelete: (() -> Void)?
    
    @State var sheetOpen: Bool = false
    @State var optionsOpen: Bool = false
    
    init(rawCredential: String, onDelete: (() -> Void)? = nil) {
        self.onDelete = onDelete
        self.credentialPack = CredentialPack()
        if let _ = try? self.credentialPack.addSdJwt(sdJwt: Vcdm2SdJwt.newFromCompactSdJwt(input: rawCredential)) {}
        else {
            print("Couldn't parse SdJwt credential: \(rawCredential)")
        }
    }
    
    init(credentialPack: CredentialPack, onDelete: (() -> Void)? = nil) {
        self.onDelete = onDelete
        self.credentialPack = credentialPack
    }
    
    @ViewBuilder
    func descriptionFormatter(values: [String: [String: GenericJSON]]) -> some View {
        let credential = values.first(where: {
            credentialPack.get(credentialId: $0.key)?.asSdJwt() != nil
        }).map { $0.value } ?? [:]
        
        var description = ""
        if let issuerName = credential["issuer"]?.dictValue?["name"]?.toString() {
            description = issuerName
        } else if let descriptionString = credential["description"]?.toString() {
            description = descriptionString
        }
        
        return VStack(alignment: .leading, spacing: 12) {
            Text(description)
                .font(.customFont(font: .inter, style: .regular, size: .p))
                .foregroundStyle(Color("TextBody"))
                .padding(.top, 4)
        }
        .padding(.leading, 12)
    }
    
    @ViewBuilder
    func listItem() -> some View {
        Card(
            credentialPack: credentialPack,
            rendering: CardRendering.list(CardRenderingListView(
                titleKeys: ["name", "type"],
                titleFormatter: { (values) in
                    let credential = values.first(where: {
                        credentialPack.get(credentialId: $0.key)?.asSdJwt() != nil
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
                            .foregroundStyle(Color("TextHeader"))
                    }
                    .padding(.leading, 12)
                },
                descriptionKeys: ["description", "issuer"],
                descriptionFormatter: descriptionFormatter
            ))
        )
    }
    
    @ViewBuilder
    func listItemWithOptions() -> some View {
        Card(
            credentialPack: credentialPack,
            rendering: CardRendering.list(CardRenderingListView(
                titleKeys: ["name", "type"],
                titleFormatter: { (values) in
                    let credential = values.first(where: {
                        credentialPack.get(credentialId: $0.key)?.asSdJwt() != nil
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
                            .confirmationDialog(
                                Text("Credential Options"),
                                isPresented: $optionsOpen,
                                titleVisibility: .visible,
                                actions: {
                                    CredentialOptionsDialogActions(
                                        onDelete: onDelete,
                                        exportFileName: "\(title ?? "credential").txt",
                                        credentialPack: credentialPack
                                    )
                                }
                            )
                        }
                        .padding(.trailing, -12)
                        HStack {
                            Text(title ?? "")
                                .padding(.trailing, 12)
                                .font(.customFont(font: .inter, style: .semiBold, size: .h1))
                                .foregroundStyle(Color("TextHeader"))
                        }
                    }
                    .padding(.leading, 12)
                },
                descriptionKeys: ["description", "issuer"],
                descriptionFormatter: descriptionFormatter
            ))
        )
    }
    
    @ViewBuilder
    public func credentialDetails() -> any View {
        Card(
            credentialPack: credentialPack,
            rendering: CardRendering.details(CardRenderingDetailsView(
                fields: [
                    CardRenderingDetailsField(
                        keys: ["awardedDate", "credentialSubject"],
                        formatter: { (values) in
                            let credential = values.first(where: {
                                credentialPack.get(credentialId: $0.key)?.asSdJwt() != nil
                            }).map { $0.value } ?? [:]
                            
                            let awardedDate = credential["awardedDate"]?.toString() ?? ""
                            
                            let identity = credential["credentialSubject"]?.dictValue?["identity"]?.arrayValue
                            
                            let details = identity?.map {
                                return (
                                    $0.dictValue?["identityType"]?.toString() ?? "",
                                    $0.dictValue?["identityHash"]?.toString() ?? ""
                                )
                            }
                            
                            return VStack(alignment: .leading, spacing: 12) {
                                HStack {
                                    VStack(alignment: .leading, spacing: 20) {
                                        VStack(alignment: .leading) {
                                            Text("Awarded Date")
                                                .font(.customFont(font: .inter, style: .regular, size: .p))
                                                .foregroundStyle(Color("TextBody"))
                                            CredentialDate(dateString: awardedDate)
                                        }
                                        ForEach(details ?? [], id: \.self.0) { info in
                                            VStack(alignment: .leading) {
                                                Text(info.0.camelCaseToWords().capitalized)
                                                    .font(.customFont(font: .inter, style: .regular, size: .p))
                                                    .foregroundStyle(Color("TextBody"))
                                                Text(info.1)
                                            }
                                        }
                                    }
                                    Spacer()
                                }
                                .padding(.horizontal, 4)
                            }
                            .padding(.vertical, 20)
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
                if(withOptions){
                    listItemWithOptions()
                } else {
                    listItem()
                }
            }
            .padding(12)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color("CredentialBorder"), lineWidth: 1)
            )
            .padding(.all, 12)
            
        }
    }
    
    @ViewBuilder
    public func credentialPreviewAndDetails() -> any View {
        AnyView(credentialListItem(withOptions: true))
            .onTapGesture {
                sheetOpen.toggle()
            }
            .sheet(isPresented: $sheetOpen) {
                
            } content: {
                VStack {
                    Text("Review Info")
                        .font(.customFont(font: .inter, style: .bold, size: .h0))
                        .foregroundStyle(Color("TextHeader"))
                        .padding(.top, 25)
                    AnyView(credentialListItem())
                        .frame(height: 120)
                    AnyView(credentialDetails())
                }
                .presentationDetents([.fraction(0.85)])
                .presentationDragIndicator(.automatic)
                .presentationBackgroundInteraction(.automatic)
            }
    }
    
    var body: some View {
        AnyView(credentialPreviewAndDetails())
    }
}
