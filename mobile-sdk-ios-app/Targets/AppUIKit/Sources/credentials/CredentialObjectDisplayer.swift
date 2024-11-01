import SwiftUI
import SpruceIDMobileSdk

struct CredentialObjectDisplayer: View {
    let display: [AnyView]
    
    init(dict: [String : GenericJSON]) {
        self.display = genericObjectDisplayer(
            object: dict,
            filter: ["id", "identifier", "type", "proof", "renderMethod", "@context"]
        )
    }
    
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            ForEach(0..<display.count, id: \.self) { index in
                display[index]
            }
            
        }
    }
}

func genericObjectDisplayer(object: [String : GenericJSON], filter: [String] = [], level: Int = 1) -> [AnyView] {
    var res: [AnyView] = []
    object
        .filter { !filter.contains($0.key) }
        .sorted(by: { $0.0 < $1.0 })
        .forEach { (key, value) in
            if let dictValue = value.dictValue {
                let tmpViews = genericObjectDisplayer(object: dictValue, filter: filter, level: level+1)
                
                if (key.count > 2) {
                    res.append(AnyView(
                        VStack(alignment: .leading) {
                            Accordion(title: key, startExpanded: level < 3, content: AnyView(
                                VStack(alignment: .leading, spacing: 20) {
                                    ForEach(0..<tmpViews.count, id: \.self) { index in
                                        tmpViews[index]
                                    }
                                }
                                .padding(.leading, CGFloat(level * 12))
                            ))
                            .padding(.leading, level > 1 ? CGFloat(level * 12) : 0)
                        }
                    ))
                } else {
                    res.append(AnyView(
                        VStack(alignment: .leading) {
                            VStack(alignment: .leading, spacing: 24) {
                                ForEach(0..<tmpViews.count, id: \.self) { index in
                                    tmpViews[index]
                                }
                            }
                            .padding(.leading, CGFloat(level * 12))
                        }
                    ))
                }
            } else if let arrayValue = value.arrayValue {
                var tmpSections: [AnyView] = []
                for (idx, item) in arrayValue.enumerated() {
                    let tmpViews = genericObjectDisplayer(object: ["\(idx)": item], filter: filter, level: level+1)
                    tmpSections.append(AnyView(
                        VStack(alignment: .leading) {
                            ForEach(0..<tmpViews.count, id: \.self) { index in
                                tmpViews[index]
                            }
                        }
                    ))
                }
                
                res.append(AnyView(
                    VStack(alignment: .leading) {
                        Accordion(title: key, startExpanded: level < 3, content: AnyView(
                            VStack(alignment: .leading, spacing: 24) {
                                VStack(alignment: .leading, spacing: 24) {
                                    ForEach(0..<tmpSections.count, id: \.self) { index in
                                        tmpSections[index]
                                    }
                                }
                            }
                            .padding(.leading, CGFloat(level * 12))
                        ))
                        .padding(.leading, level > 1 ? CGFloat(level * 12) : 0)
                    }
                ))
                
                
            } else {
                res.append(AnyView(
                    VStack(alignment: .leading) {
                        Text(key.camelCaseToWords().capitalized.replaceUnderscores())
                            .font(.customFont(font: .inter, style: .regular, size: .h4))
                            .foregroundStyle(Color("TextBody"))
                        if key.lowercased().contains("image") ||
                            key.lowercased().contains("portrait") ||
                            value.toString().contains("data:image") {
                            CredentialImage(image: value.toString())
                        } else if key.lowercased().contains("date") ||
                                    key.lowercased().contains("from") ||
                                    key.lowercased().contains("until") {
                            CredentialDate(dateString: value.toString())
                        } else if key.lowercased().contains("url") {
                            Link(value.toString(),
                                 destination: URL(string: value.toString())!)
                        } else {
                            Text(value.toString())
                        }
                    }))
            }
        }
    return res
}
