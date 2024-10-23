import SwiftUI
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

struct AchievementCredentialItem: View {
    var credential: GenericJSON?
    var onDelete: (() -> Void)?
    
    @State var sheetOpen: Bool = false
    @State var optionsOpen: Bool = false
    
    let isoDateFormatter = ISO8601DateFormatter()
    
    let dateFormatter = {
        let dtFormatter = DateFormatter()
        dtFormatter.dateStyle = .medium
        dtFormatter.timeStyle = .short
        dtFormatter.locale = Locale(identifier: "en_US_POSIX")
        dtFormatter.timeZone = .gmt
        return dtFormatter
    }()
    
    init(credential: GenericJSON?, onDelete: (() -> Void)? = nil) {
        self.credential = credential
        self.onDelete = onDelete
    }
    
    init(rawCredential: String, onDelete: (() -> Void)? = nil) {
        do {
            let res = try decodeRevealSdJwt(input: rawCredential)
            self.credential = getGenericJSON(jsonString: res)
        } catch {
           print(error)
        }
        self.onDelete = onDelete
    }
    
    @ViewBuilder
    private var listComponentTitleWithOptions: some View {

        let achievementName = credential?["name"]?.toString() ?? ""
        
        // Title
        VStack(alignment: .leading) {
            HStack {
                Spacer()
                Image("ThreeDotsHorizontal")
                    .frame(height: 12)
                    .onTapGesture {
                        optionsOpen = true
                    }
            }
            Text(achievementName)
                .font(.customFont(font: .inter, style: .semiBold, size: .h2))
                .foregroundStyle(Color("TextHeader"))
        }
        .padding(.leading, 12)
        .confirmationDialog(
            Text("Credential Options"),
            isPresented: $optionsOpen,
            titleVisibility: .visible,
            actions: {
                if(onDelete != nil) {
                    Button("Delete", role: .destructive) { onDelete?() }
                }
                Button("Cancel", role: .cancel) { }
            }
        )
    }
    
    @ViewBuilder
    private var listComponentTitle: some View {
        let achievementName = credential?["name"]?.toString() ?? ""

        // Title
        VStack(alignment: .leading) {
            Text(achievementName)
                .font(.customFont(font: .inter, style: .semiBold, size: .h2))
                .foregroundStyle(Color("TextHeader"))
        }
        .padding(.leading, 12)
    }
    
    @ViewBuilder
    private var listComponentDescription: some View {
        let issuerName = credential?.dictValue?["issuer"]?.dictValue?["name"]?.toString() ?? ""

        // Description
        VStack(alignment: .leading) {
            Text(issuerName)
                .font(.customFont(font: .inter, style: .regular, size: .p))
                .foregroundStyle(Color("TextBody"))
                .padding(.top, 6)
            Spacer()
            HStack {
                Image("Valid")
                Text("Valid")
                    .font(.customFont(font: .inter, style: .medium, size: .p))
                    .foregroundStyle(Color("GreenValid"))
            }
        }
        .padding(.leading, 12)
    }
    
    @ViewBuilder
    public var listComponent: some View {
        HStack {
            // Leading icon
            VStack(alignment: .leading) {
                listComponentTitle
                listComponentDescription
            }
            Spacer()
            // Trailing action button
        }
        .frame(height: 100)
        .padding(.vertical, 12)
        .padding(.horizontal, 12)
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color("CredentialBorder"), lineWidth: 1)
        )
        .padding(.all, 12)
    }
    
    @ViewBuilder
    public var listComponentWithOptions: some View {
        HStack {
            // Leading icon
            VStack(alignment: .leading) {
                listComponentTitleWithOptions
                listComponentDescription
            }
            Spacer()
            // Trailing action button
        }
        .frame(height: 100)
        .padding(.vertical, 12)
        .padding(.horizontal, 12)
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color("CredentialBorder"), lineWidth: 1)
        )
        .padding(.all, 12)
    }
    
    @ViewBuilder
    public var detailsComponent: some View {
        let awardedDate = credential?.dictValue?["awardedDate"]?.toString() ?? ""
        let isoDate = isoDateFormatter.date(from: awardedDate)!
        let date = dateFormatter.string(from: isoDate)
        
        let identity = credential?.dictValue?["credentialSubject"]?.dictValue?["identity"]?.arrayValue
        let details = identity?.map {
            return (
                $0.dictValue?["identityType"]?.toString() ?? "",
                $0.dictValue?["identityHash"]?.toString() ?? ""
            )
        }
        
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading) {
                        Text("Awarded Date")
                            .font(.customFont(font: .inter, style: .regular, size: .p))
                            .foregroundStyle(Color("TextBody"))
                        Text(date)
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
            .padding(.horizontal, 20)
            
        }
        .padding(.vertical, 20)
    }
    
    
    var body: some View {
        listComponentWithOptions
            .onTapGesture {
                sheetOpen = true
            }
            .sheet(isPresented: $sheetOpen) {
                
            } content: {
                Text("Review Info")
                    .font(.customFont(font: .inter, style: .bold, size: .h0))
                    .foregroundStyle(Color("TextHeader"))
                    .padding(.top, 25)
                listComponent
                ScrollView(.vertical, showsIndicators: false) {
                    detailsComponent
                }
                
                .presentationDetents([.fraction(0.85)])
                .presentationDragIndicator(.automatic)
                .presentationBackgroundInteraction(.automatic)
            }
    }
}

