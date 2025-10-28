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
                    titleKeys: ["name", "type", "credentialSubject.name", "id"],
                    titleFormatter: { (values) in
                        let credential: [String: GenericJSON] =
                            values.first(where: {
                                let credential = credentialPack.get(
                                    credentialId: $0.key
                                )
                                return credential?.asJwtVc() != nil
                                    || credential?.asJsonVc() != nil
                                    || credential?.asSdJwt() != nil
                                    || credential?.asMsoMdoc() != nil
                                    || credential?.asCwt() != nil

                            }).map {
                                // Assume mDL.
                                if credentialPack.get(
                                    credentialId: $0.key
                                )?.asMsoMdoc() != nil {
                                    var newValue = $0.value
                                    newValue["name"] = GenericJSON.string(
                                        "Mobile Drivers License"
                                    )
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
                                        font: .inter,
                                        style: .semiBold,
                                        size: .h2
                                    )
                                )
                                .foregroundStyle(Color.white)
                                .shadow(color: .black, radius: 12, x: 2.5, y: 2.5)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    },
                    descriptionKeys: ["description", "issuer"],
                    descriptionFormatter: descriptionFormatter ?? { values in
                        genericCredentialListItemDescriptionFormatter(
                            credentialPack: credentialPack,
                            statusListObservable: statusListObservable,
                            values: values
                        )
                    },
                    cardStyle: CardStyle(
                        topLeftLogoImage: "SpruceLogo",
                        topLeftLogoTint: .white,
                        backgroundImage: "CredentialBg",
                        credentialImageKeys: [
                            "portrait",
                            "image",
                            "issuer.image",
                            "credentialSubject.image",
                            "credentialSubject.issuer.image",
                            "issuer.name",
                            "type",
                            "credentialSubject.achievement.image.id"
                        ],
                        credentialImageFormatter: { values in
                            credentialImageFormatter(
                                credentialPack: credentialPack,
                                values: values
                            )
                        }
                    )
                )
            )
        )
    }

    @ViewBuilder
    func listItemWithOptions() -> some View {
        Card(
            credentialPack: credentialPack,
            rendering: CardRendering.list(
                CardRenderingListView(
                    titleKeys: ["name", "type", "credentialSubject.name", "id"],
                    titleFormatter: { (values) in
                        let credential: [String: GenericJSON] =
                            values.first(where: {
                                let credential = credentialPack.get(
                                    credentialId: $0.key
                                )
                                return credential?.asJwtVc() != nil
                                    || credential?.asJsonVc() != nil
                                    || credential?.asSdJwt() != nil
                                    || credential?.asMsoMdoc() != nil
                            }).map {
                                // Assume mDL.
                                if credentialPack.get(
                                    credentialId: $0.key
                                )?.asMsoMdoc() != nil {
                                    var newValue = $0.value
                                    newValue["name"] = GenericJSON.string(
                                        "Mobile Drivers License"
                                    )
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

                        return HStack(alignment: .center, spacing: 8) {
                            Text(title ?? "")
                                .font(
                                    .customFont(
                                        font: .inter,
                                        style: .semiBold,
                                        size: .h2
                                    )
                                )
                                .foregroundStyle(Color.white)
                                .shadow(color: .black, radius: 10, x: 2.5, y: 2.5)

                            Spacer()

                            Image("ThreeDotsHorizontal")
                                .resizable()
                                .renderingMode(.template)
                                .foregroundColor(.white)
                                .aspectRatio(contentMode: .fit)
                                .frame(width: 15)
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
                                            exportFileName:
                                                "\(title ?? "credential").json",
                                            credentialPack: credentialPack
                                        )
                                    }
                                )
                        }
                    },
                    descriptionKeys: ["description", "issuer"],
                    descriptionFormatter: descriptionFormatter ?? { values in
                        genericCredentialListItemDescriptionFormatter(
                            credentialPack: credentialPack,
                            statusListObservable: statusListObservable,
                            values: values
                        )
                    },
                    cardStyle: CardStyle(
                        topLeftLogoImage: "SpruceLogo",
                        topLeftLogoTint: .white,
                        backgroundImage: "CredentialBg",
                        credentialImageKeys: [
                            "portrait",
                            "image",
                            "issuer.image",
                            "credentialSubject.image",
                            "credentialSubject.issuer.image",
                            "issuer.name",
                            "type",
                            "credentialSubject.achievement.image.id"
                        ],
                        credentialImageFormatter: { values in
                            credentialImageFormatter(
                                credentialPack: credentialPack,
                                values: values
                            )
                        }
                    )
                )
            )
        )
    }

    var body: some View {
        VStack {
            if withOptions {
                listItemWithOptions()
            } else {
                listItem()
            }
        }
    }
}

func genericCredentialListItemDescriptionFormatter(
    credentialPack: CredentialPack,
    statusListObservable: StatusListObservable,
    values: [String: [String: GenericJSON]]
)
    -> some View
{
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
                credentialId: $0.key
            )?.asMsoMdoc()
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

    let status = statusListObservable.statusLists[credentialPack.id.uuidString]

    return HStack(alignment: .bottom) {
        Text(description)
            .font(.customFont(font: .inter, style: .medium, size: .p))
            .foregroundStyle(Color.white)
            .shadow(color: .black, radius: 12, x: 3, y: 3)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)

        CredentialStatusSmall(status: status)
    }
    .frame(maxWidth: .infinity)
}


func credentialImageFormatter(
    credentialPack: CredentialPack,
    values: [String: [String: GenericJSON]]
)
    -> some View
{
    let credential =
        values.first(where: {
            let credential = credentialPack.get(credentialId: $0.key)
            return credential?.asJwtVc() != nil
                || credential?.asJsonVc() != nil
                || credential?.asSdJwt() != nil
                || credential?.asMsoMdoc() != nil
        }).map { $0.value } ?? [:]

    var image = ""

    // First priority: Look for portrait field
    if let portraitImage = credential["portrait"]?.toString(), !portraitImage.isEmpty {
        image = portraitImage
    }

    // Second priority: Existing image options
    if image.isEmpty {
        let issuerImg = credential["issuer"]?.dictValue?["image"]

        if let dictValue = issuerImg?.dictValue {
            if let imageValue = dictValue["image"]?.toString() {
                image = imageValue
            } else if let idValue = dictValue["id"]?.toString() {
                image = idValue
            }
        } else {
            image = issuerImg?.toString() ?? ""
        }
    }

    // Try parse OB3
    if image.isEmpty {
        image =
            credential["credentialSubject"]?.dictValue?[
                "achievement"
            ]?.dictValue?["image"]?.dictValue?["id"]?.toString() ?? ""
    }

    var alt = ""
    if let issuerName = credential["issuer"]?.dictValue?["name"]?.toString() {
        alt = issuerName
    }

    if !image.isEmpty {
        return AnyView(
            FullSizeCredentialImage(image: image, contentDescription: alt)
                .frame(width: 40, height: 40)
                .clipShape(RoundedRectangle(cornerRadius: 4))
                .overlay(
                    RoundedRectangle(cornerRadius: 4)
                        .stroke(Color.black.opacity(0.1), lineWidth: 1)
                )
        )
    } else {
        return AnyView(EmptyView())
    }
}
