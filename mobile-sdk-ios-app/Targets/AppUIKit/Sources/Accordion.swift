import Foundation
import SwiftUI

struct Accordion: View {
    let title: String
    let content: AnyView
    @State var expanded: Bool

    init(title: String, startExpanded: Bool, content: AnyView) {
        self.title = title
        self.content = content
        self.expanded = startExpanded
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            AccordionHeader(title: title, isExpanded: $expanded)
            VStack {
                AnyView(content)
            }
            .frame(height: expanded ? nil : 0, alignment: .top)
            .clipped()
        }
    }
}

struct AccordionHeader: View {
    @Binding var isExpanded: Bool
    var degrees: Angle = Angle(degrees: 90)
    var title: String

    init(title: String, isExpanded: Binding<Bool>) {
        self.title = title
        self.degrees =
            !isExpanded.wrappedValue
            ? Angle(degrees: 90.0) : Angle(degrees: 270)
        self._isExpanded = isExpanded
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center) {
                Text(title.camelCaseToWords().capitalized.replaceUnderscores())
                    .font(.customFont(font: .inter, style: .regular, size: .h4))
                    .foregroundStyle(Color("ColorStone600"))
                Spacer()
                Image(systemName: "chevron.right")
                    .rotationEffect(degrees)
                    .foregroundColor(Color("ColorStone600"))
                    .padding(.trailing, 4)
            }
            .onTapGesture {
                withAnimation {
                    isExpanded.toggle()
                }
            }
        }
    }
}
