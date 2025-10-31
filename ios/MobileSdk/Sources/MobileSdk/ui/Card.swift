import SwiftUI

/// Struct with styling options for card rendering
public struct CardStyle {
    /// Optional image name for the top-left logo
    var topLeftLogoImage: String?
    /// Optional color for the top-left logo tint
    var topLeftLogoTint: Color?
    /// Optional image name for the card background image
    var backgroundImage: String?
    /// Optional list of keys for credential image
    var credentialImageKeys: [String]?
    /// Optional formatter for credential image
    var credentialImageFormatter: (([String: [String: GenericJSON]]) -> any View)?

    public init(
        topLeftLogoImage: String? = nil,
        topLeftLogoTint: Color? = nil,
        backgroundImage: String? = nil,
        credentialImageKeys: [String]? = nil,
        credentialImageFormatter: (([String: [String: GenericJSON]]) -> any View)? = nil
    ) {
        self.topLeftLogoImage = topLeftLogoImage
        self.topLeftLogoTint = topLeftLogoTint
        self.backgroundImage = backgroundImage
        self.credentialImageKeys = credentialImageKeys
        self.credentialImageFormatter = credentialImageFormatter
    }
}

/// Struct with the specification to display the credential pack in a list view
public struct CardRenderingListView {
    /// An array of keys that will be used to generate an array of values extracted from the credentials
    var titleKeys: [String]
    /**
    [OPTIONAL] - Method used to create a custom title field. 
    Receives an array of values based on the array of keys for the same field
    */
    var titleFormatter: (([String: [String: GenericJSON]]) -> any View)?
    /// [OPTIONAL] - An array of keys that will be used to generate an array of values extracted from the credentials
    var descriptionKeys: [String]?
    /**
    [OPTIONAL] - Method used to create a custom description field. 
    Receives an array of values based on the array of keys for the same field
    */
    var descriptionFormatter: (([String: [String: GenericJSON]]) -> any View)?
    /// [OPTIONAL] - An array of keys that will be used to generate an array of values extracted from the credentials
    var leadingIconKeys: [String]?
    /**
    [OPTIONAL] - Method used to create a custom leading icon formatter. 
    Receives an array of values based on the array of keys for the same field
    */
    var leadingIconFormatter: (([String: [String: GenericJSON]]) -> any View)?
    /// [OPTIONAL] - An array of keys that will be used to generate an array of values extracted from the credentials
    var trailingActionKeys: [String]?
    /**
    [OPTIONAL] - Method used to create a custom trailing action button.
    Receives an array of values based on the array of keys for the same field
    */
    var trailingActionButton: (([String: [String: GenericJSON]]) -> any View)?
    /// [OPTIONAL] - Styling configuration for the card
    var cardStyle: CardStyle?

    public init(
        titleKeys: [String],
        titleFormatter: (([String: [String: GenericJSON]]) -> any View)? = nil,
        descriptionKeys: [String]? = nil,
        descriptionFormatter: (([String: [String: GenericJSON]]) -> any View)? = nil,
        leadingIconKeys: [String]? = nil,
        leadingIconFormatter: (([String: [String: GenericJSON]]) -> any View)? = nil,
        trailingActionKeys: [String]? = nil,
        trailingActionButton: (([String: [String: GenericJSON]]) -> any View)? = nil,
        cardStyle: CardStyle? = nil
    ) {
        self.titleKeys = titleKeys
        self.titleFormatter = titleFormatter
        self.descriptionKeys = descriptionKeys
        self.descriptionFormatter = descriptionFormatter
        self.leadingIconKeys = leadingIconKeys
        self.leadingIconFormatter = leadingIconFormatter
        self.trailingActionKeys = trailingActionKeys
        self.trailingActionButton = trailingActionButton
        self.cardStyle = cardStyle
    }
}

/// Struct with the specification to display the credential in a details view
public struct CardRenderingDetailsView {
    /// An array of field render settings that will be used to generate a UI element with the defined keys
    var fields: [CardRenderingDetailsField]

    public init(fields: [CardRenderingDetailsField]) {
        self.fields = fields
    }
}

/// Struct with the specification to display the credential field in a details view
public struct CardRenderingDetailsField {
    /// Internal identifier
    var id: String?
    /// An array of keys that will be used to generate an array of values extracted from the credentials
    var keys: [String]
    /**
    [OPTIONAL] - Method used to create a custom field. 
    Receives an array of values based on the array of keys for the same field
    */
    var formatter: (([String: [String: GenericJSON]]) -> any View)?

    public init(keys: [String], formatter: (([String: [String: GenericJSON]]) -> any View)?) {
        self.id = NSUUID().uuidString
        self.keys = keys
        self.formatter = formatter
    }

    public init(keys: [String]) {
        self.id = NSUUID().uuidString
        self.keys = keys
    }
}

/**
 Enum  aggregating two types:
 - .list == CardRenderingListView
 - .details == CardRenderingDetailsView
*/
public enum CardRendering {
    case list(CardRenderingListView)
    case details(CardRenderingDetailsView)
}

/// Manages the card rendering type according with the render object
public struct Card: View {
    var credentialPack: CredentialPack
    var rendering: CardRendering

    public init(
        credentialPack: CredentialPack,
        rendering: CardRendering
    ) {
        self.credentialPack = credentialPack
        self.rendering = rendering
    }

    public var body: some View {
        switch rendering {
        case .list(let cardRenderingListView):
            CardListView(credentialPack: credentialPack, rendering: cardRenderingListView)
        case .details(let cardRenderingDetailsView):
            CardDetailsView(credentialPack: credentialPack, rendering: cardRenderingDetailsView)
        }
    }
}

/// Renders the credential as a list view item
public struct CardListView: View {
    var credentialPack: CredentialPack
    var rendering: CardRenderingListView

    public init(
        credentialPack: CredentialPack,
        rendering: CardRenderingListView
    ) {
        self.credentialPack = credentialPack
        self.rendering = rendering
    }

    public var body: some View {
        let descriptionValues = credentialPack.findCredentialClaims(claimNames: rendering.descriptionKeys ?? [])
        let titleValues = credentialPack.findCredentialClaims(claimNames: rendering.titleKeys)

        VStack(spacing: 0) {
                // Top row: Logo + Credential Image
                HStack(alignment: .top) {
                    // Top-left logo
                    if let logoImage = rendering.cardStyle?.topLeftLogoImage {
                        Image(logoImage)
                            .resizable()
                            .renderingMode(.template)
                            .foregroundColor(rendering.cardStyle?.topLeftLogoTint ?? .white)
                            .frame(width: 24, height: 24)
                    }

                    Spacer()

                    // Credential image
                    if let credentialImageFormatter = rendering.cardStyle?.credentialImageFormatter {
                        AnyView(
                            credentialImageFormatter(
                                credentialPack.findCredentialClaims(
                                    claimNames: rendering.cardStyle?.credentialImageKeys ?? []
                                )
                            )
                        )
                    }
                }
                .padding(20)

                Spacer()

                // Bottom content: Title and Description
                VStack(alignment: .leading, spacing: 4) {
                    // Title
                    if rendering.titleFormatter != nil {
                        AnyView(rendering.titleFormatter!(titleValues))
                    } else if titleValues.count > 0 {
                        let value = titleValues.values
                            .reduce("", { $0 + $1.values.map {$0.toString()}
                            .joined(separator: " ")
                        })
                        Text(value)
                            .foregroundColor(.white)
                    }

                    // Issuer and status row
                    HStack {
                         if rendering.descriptionFormatter != nil {
                            AnyView(rendering.descriptionFormatter!(descriptionValues))
                        } else if descriptionValues.count > 0 {
                            let value = descriptionValues.values
                                .reduce("", { $0 + $1.values.map {$0.toString()}
                                .joined(separator: " ")
                            })
                            Text(value)
                                .foregroundColor(.white)
                        }
                    }
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(
            ZStack {
                if let backgroundImage = rendering.cardStyle?.backgroundImage {
                    Image(backgroundImage)
                        .resizable()
                        .scaledToFill()

                    LinearGradient(
                        gradient: Gradient(colors: [
                            Color.black.opacity(0.0),
                            Color.black.opacity(0.9)
                        ]),
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }
            }
        )
        .frame(height: 195)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .strokeBorder(
                    LinearGradient(
                        gradient: Gradient(stops: [
                            .init(color: Color(red: 0.78, green: 0.75, blue: 0.68), location: 0.0),
                            .init(color: Color.white.opacity(0.2), location: 0.1),
                            .init(color: Color.white.opacity(0.2), location: 0.9),
                            .init(color: Color(red: 0.78, green: 0.75, blue: 0.68), location: 1.0)
                        ]),
                        startPoint: .top,
                        endPoint: .bottom
                    ),
                    lineWidth: 1
                )
        )
        .shadow(color: Color.black.opacity(0.4), radius: 3, x: 0, y: 0)
    }
}

/// Renders the credential as a details view
public struct CardDetailsView: View {
    var credentialPack: CredentialPack
    var rendering: CardRenderingDetailsView

    public init(
        credentialPack: CredentialPack,
        rendering: CardRenderingDetailsView
    ) {
        self.credentialPack = credentialPack
        self.rendering = rendering
    }

    public var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            ForEach(rendering.fields, id: \.id) { field in
                let values = credentialPack.findCredentialClaims(claimNames: field.keys)
                if field.formatter != nil {
                    AnyView(field.formatter!(values))
                } else {
                    let value = values.values.reduce("", { $0 + $1.values.map {$0.toString()}.joined(separator: " ")})
                    Text(value)
                }
            }
        }
    }
}
