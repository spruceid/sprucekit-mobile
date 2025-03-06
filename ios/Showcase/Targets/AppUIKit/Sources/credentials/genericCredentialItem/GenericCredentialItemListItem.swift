import SpruceIDMobileSdk
import SwiftUI

struct GenericCredentialItemListItem: View {
    @EnvironmentObject private var statusListObservable: StatusListObservable
    let credentialPack: CredentialPack
    let onDelete: (() -> Void)?
    let withOptions: Bool
    let leadingIconFormatter: (([String: [String: GenericJSON]]) -> any View)? =
        nil
    let descriptionFormatter: (([String: [String: GenericJSON]]) -> any View)? =
        nil
    @State var optionsOpen: Bool = false

    @ViewBuilder
    func listItem() -> some View {
        Card(
            credentialPack: credentialPack,
            rendering: CardRendering.list(
                CardRenderingListView(
                    titleKeys: ["name", "type"],
                    titleFormatter: { (values) in
                        let credential: [String: GenericJSON] =
                            values.first(where: {
                                let credential = credentialPack.get(
                                    credentialId: $0.key)
                                return credential?.asJwtVc() != nil
                                    || credential?.asJsonVc() != nil
                                    || credential?.asSdJwt() != nil
                                    || credential?.asMsoMdoc() != nil
                                    || credential?.asCwt() != nil

                            }).map {
                                // Assume mDL.
                                if credentialPack.get(
                                    credentialId: $0.key)?.asMsoMdoc() != nil {
                                    var newValue = $0.value
                                    newValue["name"] = GenericJSON.string(
                                        "Mobile Drivers License")
                                    return newValue
                                }
                                return $0.value
                            } ?? [:]

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
                                .font(
                                    .customFont(
                                        font: .inter, style: .semiBold,
                                        size: .h1)
                                )
                                .foregroundStyle(Color("ColorStone950"))
                        }
                        .padding(.leading, 12)
                    },
                    descriptionKeys: ["description", "issuer"],
                    descriptionFormatter: descriptionFormatter ?? { values in
                        genericCredentialListItemDescriptionFormatter(
                            credentialPack: credentialPack,
                            statusListObservable: statusListObservable,
                            values: values
                        )
                    },
                    leadingIconKeys: ["issuer"],
                    leadingIconFormatter: leadingIconFormatter ?? { values in
                        genericCredentialListItemLeadingIconFormatter(
                            credentialPack: credentialPack,
                            values: values
                        )
                    }
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
                        let credential: [String: GenericJSON] =
                            values.first(where: {
                                let credential = credentialPack.get(
                                    credentialId: $0.key)
                                return credential?.asJwtVc() != nil
                                    || credential?.asJsonVc() != nil
                                    || credential?.asSdJwt() != nil
                                    || credential?.asMsoMdoc() != nil
                            }).map {
                                // Assume mDL.
                                if credentialPack.get(
                                    credentialId: $0.key)?.asMsoMdoc() != nil {
                                    var newValue = $0.value
                                    newValue["name"] = GenericJSON.string(
                                        "Mobile Drivers License")
                                    return newValue
                                }
                                return $0.value
                            } ?? [:]

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
                                    Spacer()
                                    Image("ThreeDotsHorizontal")
                                    Spacer()
                                }
                                .frame(width: 32, height: 32)
                                .background(Color.white)
                                .onTapGesture {
                                    optionsOpen = true
                                }
                            }
                            .padding(.trailing, -12)
                            HStack {
                                Text(title ?? "")
                                    .padding(.trailing, 12)
                                    .font(
                                        .customFont(
                                            font: .inter, style: .semiBold,
                                            size: .h1)
                                    )
                                    .foregroundStyle(Color("ColorStone950"))
                            }
                            .confirmationDialog(
                                Text("Credential Options"),
                                isPresented: $optionsOpen,
                                titleVisibility: .visible,
                                actions: {
                                    CredentialOptionsDialogActions(
                                        onDelete: onDelete,
                                        exportFileName:
                                            "\(title ?? "credential").json",
                                        credentialPack: credentialPack
                                    )
                                }
                            )
                        }
                        .padding(.leading, 12)
                    },
                    descriptionKeys: ["description", "issuer"],
                    descriptionFormatter: descriptionFormatter ?? { values in
                        genericCredentialListItemDescriptionFormatter(
                            credentialPack: credentialPack,
                            statusListObservable: statusListObservable,
                            values: values
                        )
                    },
                    leadingIconKeys: ["issuer"],
                    leadingIconFormatter: leadingIconFormatter ?? { values in
                        genericCredentialListItemLeadingIconFormatter(
                            credentialPack: credentialPack,
                            values: values
                        )
                    }
                ))
        )
    }

    var body: some View {
        VStack {
            VStack {
                if withOptions {
                    listItemWithOptions()
                } else {
                    listItem()
                }
            }
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.white)
                    .shadow(color: .black.opacity(0.03), radius: 5)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color("ColorBase300"), lineWidth: 1)
            )
        }
    }
}

@ViewBuilder
func genericCredentialListItemDescriptionFormatter(
    credentialPack: CredentialPack,
    statusListObservable: StatusListObservable,
    values: [String: [String: GenericJSON]]
)
    -> some View {
    let credential: [String: GenericJSON] =
        values.first(where: {
            let credential = credentialPack.get(credentialId: $0.key)
            return credential?.asJwtVc() != nil
                || credential?.asJsonVc() != nil
                || credential?.asSdJwt() != nil
                || credential?.asMsoMdoc() != nil

        }).map {
            // Assume mDL.
            let mdoc = credentialPack.get(
                credentialId: $0.key)?.asMsoMdoc()
            if mdoc != nil {
                let details = mdoc?.jsonEncodedDetails()
                var newValue = $0.value
                newValue["issuer"] = details?["issuing_authority"]
                return newValue
            }
            return $0.value
        } ?? [:]

    var description = ""
    if let issuerName = credential["issuer"]?.dictValue?["name"]?.toString() {
        description = issuerName
    } else if let descriptionString = credential["description"]?.toString() {
        description = descriptionString
    } else if let issuerName = credential["issuer"]?.toString() {
        description = issuerName
    }

    return VStack(alignment: .leading, spacing: 12) {
        Text(description)
            .font(.customFont(font: .inter, style: .regular, size: .p))
            .foregroundStyle(Color("ColorStone600"))
            .padding(.top, 4)
        CredentialStatusSmall(
            status:
                statusListObservable.statusLists[
                    credentialPack.id.uuidString]
        )
    }
    .padding(.leading, 12)
}

@ViewBuilder
func genericCredentialListItemLeadingIconFormatter(
    credentialPack: CredentialPack,
    values: [String: [String: GenericJSON]]
)
    -> some View {
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
