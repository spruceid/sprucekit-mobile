import SpruceIDMobileSdk
import SwiftUI

struct CredentialObjectDisplayer: View {
    let display: [AnyView]

    init(dict: [String: GenericJSON]) {
        self.display = genericObjectDisplayer(
            object: dict,
            filter: [
                "type", "hashed", "salt", "proof", "renderMethod", "@context",
                "credentialStatus", "-65537",
            ]
        )
    }

    var body: some View {
        VStack(
            alignment: .leading,
            spacing: 20
        ) {
            ForEach(0..<display.count, id: \.self) { index in
                display[index]
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

func formatValueString(_ value: GenericJSON) -> String {
    if case let .number(num) = value {
        if num.truncatingRemainder(dividingBy: 1) == 0 {
            return String(format: "%.0f", num)
        }
    }
    return value.toString()
}

func genericObjectDisplayer(
    object: [String: GenericJSON],
    filter: [String] = [],
    level: Int = 1
) -> [AnyView] {
    var res: [AnyView] = []
    object
        .filter { !filter.contains($0.key) }
        .sorted(by: { $0.0 < $1.0 })
        .forEach { (key, value) in
            if let dictValue = value.dictValue {
                let tmpViews = genericObjectDisplayer(
                    object: dictValue,
                    filter: filter,
                    level: level + 1
                )

                if key.count > 2 {
                    res.append(
                        AnyView(
                            VStack(alignment: .leading) {
                                Accordion(
                                    title: key,
                                    startExpanded: level < 3,
                                    content: AnyView(
                                        VStack(alignment: .leading, spacing: 20)
                                        {
                                            ForEach(
                                                0..<tmpViews.count,
                                                id: \.self
                                            ) { index in
                                                tmpViews[index]
                                            }
                                        }
                                        .padding(.leading, CGFloat(12))
                                    )
                                )
                                .padding(.leading, level > 1 ? CGFloat(12) : 0)
                            }
                        )
                    )
                } else {
                    res.append(
                        AnyView(
                            VStack(alignment: .leading) {
                                VStack(alignment: .leading, spacing: 24) {
                                    ForEach(0..<tmpViews.count, id: \.self) {
                                        index in
                                        tmpViews[index]
                                    }
                                }
                                .padding(.leading, CGFloat(12))
                            }
                        )
                    )
                }
            } else if let arrayValue = value.arrayValue {
                if key.lowercased().contains("image")
                    || (key.lowercased().contains("portrait")
                        && !key.lowercased().contains("date"))
                    || value.toString().contains("data:image")
                {
                    res.append(
                        AnyView(
                            VStack(alignment: .leading) {
                                Text(
                                    key.camelCaseToWords().capitalized
                                        .replaceUnderscores()
                                )
                                .font(
                                    .customFont(
                                        font: .inter,
                                        style: .regular,
                                        size: .h4
                                    )
                                )
                                .foregroundStyle(Color("ColorStone600"))
                                CredentialGenericJSONArrayImage(
                                    image: arrayValue
                                )
                            }
                        )
                    )
                } else {
                    var tmpSections: [AnyView] = []

                    for (idx, item) in arrayValue.enumerated() {
                        let tmpViews = genericObjectDisplayer(
                            object: ["\(idx)": item],
                            filter: filter,
                            level: level + 1
                        )
                        tmpSections.append(
                            AnyView(
                                VStack(alignment: .leading) {
                                    ForEach(0..<tmpViews.count, id: \.self) {
                                        index in
                                        tmpViews[index]
                                    }
                                }
                            )
                        )
                    }
                    res.append(
                        AnyView(
                            VStack(alignment: .leading) {
                                Accordion(
                                    title: key,
                                    startExpanded: level < 3,
                                    content: AnyView(
                                        VStack(alignment: .leading, spacing: 24)
                                        {
                                            VStack(
                                                alignment: .leading,
                                                spacing: 24
                                            ) {
                                                ForEach(
                                                    0..<tmpSections.count,
                                                    id: \.self
                                                ) { index in
                                                    tmpSections[index]
                                                }
                                            }
                                        }
                                        .padding(.leading, CGFloat(12))
                                    )
                                )
                                .padding(.leading, level > 1 ? CGFloat(12) : 0)
                            }
                        )
                    )
                }
            } else {
                if value.toString() == "null" {
                    return
                }
                res.append(
                    AnyView(
                        VStack(alignment: .leading) {
                            Text(
                                key.camelCaseToWords().capitalized
                                    .replaceUnderscores()
                            )
                            .font(
                                .customFont(
                                    font: .inter,
                                    style: .regular,
                                    size: .h4
                                )
                            )
                            .foregroundStyle(Color("ColorStone600"))
                            if key.lowercased().contains("image")
                                || key.lowercased().contains("portrait")
                                    && !key.lowercased().contains("date")
                                || value.toString().contains("data:image")
                            {
                                CredentialImage(image: value.toString())
                            } else if key.lowercased().contains("date")
                                || key.lowercased().contains("from")
                                || key.lowercased().contains("until")
                            {
                                CredentialDate(dateString: value.toString())
                            } else if key.lowercased().contains("url") {
                                Link(
                                    value.toString(),
                                    destination: URL(string: value.toString())!
                                )
                            } else {
                                Text(formatValueString(value))
                            }
                        }
                    )
                )
            }
        }
    return res
}

// MARK: - Flattened Row Displayer

func flattenedRowDisplayer(
    object: [String: GenericJSON],
    filter: [String] = [],
    nestingLevel: Int = 0
) -> [AnyView] {
    var res: [AnyView] = []

    object
        .filter { !filter.contains($0.key) }
        .sorted(by: { $0.0 < $1.0 })
        .forEach { (key, value) in
            let readableKey = key.getKeyReadable().camelCaseToWords().replaceUnderscores().toTitle()

            // If its a json
            if let dictValue = value.dictValue {
                res.append(
                    AnyView(
                        VStack(alignment: .leading, spacing: 4) {
                            Text(readableKey)
                                .font(.customFont(font: .inter, style: .bold, size: .p))
                                .foregroundColor(Color("ColorBase800"))
                        }
                        .padding(.leading, CGFloat(nestingLevel * 12))
                        .padding(.top, nestingLevel == 0 ? 16 : 12)
                        .padding(.bottom, 4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    )
                )

                let nestedViews = flattenedRowDisplayer(
                    object: dictValue,
                    filter: filter,
                    nestingLevel: nestingLevel + 1
                )
                res.append(contentsOf: nestedViews)

            // If its an array
            } else if let arrayValue = value.arrayValue {
                for (index, item) in arrayValue.enumerated() {
                    if let dictItem = item.dictValue {
                        res.append(
                            AnyView(
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("\(readableKey) \(index + 1)")
                                        .font(.customFont(font: .inter, style: .semiBold, size: .p))
                                        .foregroundColor(Color("ColorBase800"))
                                }
                                .padding(.leading, CGFloat(nestingLevel * 10))
                                .padding(.top, nestingLevel == 0 ? 16 : 12)
                                .padding(.bottom, 4)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            )
                        )

                        let arrayViews = flattenedRowDisplayer(
                            object: dictItem,
                            filter: filter,
                            nestingLevel: nestingLevel + 1
                        )
                        res.append(contentsOf: arrayViews)

                    } else {
                        let itemValue = item.toString()
                        let fieldType = getCredentialFieldType(displayName: readableKey, fieldValue: itemValue)
                        let formattedValue = formatCredentialFieldValue(
                            fieldValue: itemValue,
                            fieldType: fieldType,
                            fieldName: key,
                            maxLength: 100
                        )

                        res.append(
                            AnyView(
                                VStack(spacing: 0) {
                                    HStack(alignment: .top) {
                                        Text("\(readableKey) \(index + 1)")
                                            .font(.customFont(font: .inter, style: .regular, size: .p))
                                            .foregroundColor(Color("ColorStone600"))
                                            .frame(maxWidth: .infinity, alignment: .leading)

                                        RenderCredentialFieldValue(
                                            fieldType: fieldType,
                                            rawFieldValue: itemValue,
                                            formattedValue: formattedValue,
                                            displayName: readableKey
                                        )
                                        .frame(maxWidth: .infinity, alignment: .trailing)
                                    }
                                    .padding(.horizontal, 8)
                                    .padding(.bottom, 10)

                                    Divider()
                                        .background(Color("ColorStone200"))
                                }
                                .padding(.leading, CGFloat(nestingLevel * 10))
                                .frame(maxWidth: .infinity)
                            )
                        )
                    }
                }

            } else {
                let fieldValue = value.toString()
                if fieldValue != "null" {
                    let fieldType = getCredentialFieldType(displayName: readableKey, fieldValue: fieldValue)
                    let formattedValue = formatCredentialFieldValue(
                        fieldValue: fieldValue,
                        fieldType: fieldType,
                        fieldName: key,
                        maxLength: 100
                    )

                    res.append(
                        AnyView(
                            VStack(spacing: 0) {
                                HStack(alignment: .top) {
                                    Text(readableKey)
                                        .font(.customFont(font: .inter, style: .regular, size: .p))
                                        .foregroundColor(Color("ColorStone600"))
                                        .frame(maxWidth: .infinity, alignment: .leading)

                                    RenderCredentialFieldValue(
                                        fieldType: fieldType,
                                        rawFieldValue: fieldValue,
                                        formattedValue: formattedValue,
                                        displayName: readableKey
                                    )
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                                }
                                .padding(.horizontal, 8)
                                .padding(.bottom, 10)

                                Divider()
                                    .background(Color("ColorStone200"))
                            }
                            .padding(.leading, CGFloat(nestingLevel * 10))
                            .frame(maxWidth: .infinity)
                        )
                    )
                }
            }
        }

    return res
}
