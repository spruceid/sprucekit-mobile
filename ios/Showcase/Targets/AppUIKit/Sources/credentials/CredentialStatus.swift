import SpruceIDMobileSdk
import SwiftUI

struct CredentialStatusSmall: View {
    var status: CredentialStatusList?

    var body: some View {
        if status != nil {
            switch status! {
            case .valid:
                HStack {
                    Image("Valid")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 14, height: 14)
                        .foregroundColor(Color("ColorEmerald600"))
                    Text("Valid")
                        .font(
                            .customFont(
                                font: .inter, style: .regular, size: .small)
                        )
                        .foregroundStyle(Color("ColorEmerald600"))
                }
            case .revoked:
                HStack {
                    Image("Invalid")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 14, height: 14)
                        .foregroundColor(Color("ColorRose700"))
                    Text("Revoked")
                        .font(
                            .customFont(
                                font: .inter, style: .regular, size: .small)
                        )
                        .foregroundStyle(Color("ColorRose700"))
                }
            case .suspended:
                HStack {
                    Image("Suspended")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 14, height: 14)
                        .foregroundColor(Color("ColorYellow700"))
                    Text("Suspended")
                        .font(
                            .customFont(
                                font: .inter, style: .regular, size: .small)
                        )
                        .foregroundStyle(Color("ColorYellow700"))
                }
            case .unknown:
                HStack {
                    Image("Unknown")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 14, height: 14)
                        .foregroundColor(Color("ColorStone950"))
                    Text("Unknown")
                        .font(
                            .customFont(
                                font: .inter, style: .regular, size: .small)
                        )
                        .foregroundStyle(Color("ColorStone950"))
                }
            case .invalid:
                HStack {
                    Image("Invalid")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 14, height: 14)
                        .foregroundColor(Color("ColorRose700"))
                    Text("Invalid")
                        .font(
                            .customFont(
                                font: .inter, style: .regular, size: .small)
                        )
                        .foregroundStyle(Color("ColorRose700"))
                }
            case .undefined:
                EmptyView()
            case .pending:
                HStack {
                    Image("Pending")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 14, height: 14)
                        .foregroundColor(Color("ColorBlue600"))
                    Text("Pending")
                        .font(
                            .customFont(
                                font: .inter, style: .regular, size: .small)
                        )
                        .foregroundStyle(Color("ColorBlue600"))
                }
            case .ready:
                HStack {
                    Image("Valid")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 14, height: 14)
                        .foregroundColor(Color("ColorEmerald600"))
                    Text("Ready")
                        .font(
                            .customFont(
                                font: .inter, style: .regular, size: .small)
                        )
                        .foregroundStyle(Color("ColorEmerald600"))
                }
            }
        } else {
            EmptyView()
        }

    }
}

struct CredentialStatus: View {
    var status: CredentialStatusList?

    var body: some View {
        if status != nil {
            switch status! {
            case .valid:
                VStack {
                    HStack(alignment: .center) {
                        Text("Status")
                            .font(
                                .customFont(
                                    font: .inter, style: .regular, size: .h4)
                            )
                            .foregroundStyle(Color("ColorStone600"))
                        Spacer()
                    }
                    HStack {
                        Image("Valid")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 24, height: 24)
                            .foregroundColor(Color("ColorBase50"))
                        Text("VALID")
                            .font(
                                .customFont(
                                    font: .inter, style: .regular, size: .h3)
                            )
                            .foregroundStyle(Color("ColorBase50"))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background(Color("ColorEmerald600"))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .padding(.leading, CGFloat(4))
            case .revoked:
                VStack {
                    HStack(alignment: .center) {
                        Text("Status")
                            .font(
                                .customFont(
                                    font: .inter, style: .regular, size: .h4)
                            )
                            .foregroundStyle(Color("ColorStone600"))
                        Spacer()
                    }
                    HStack {
                        Image("Invalid")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 24, height: 24)
                            .foregroundColor(Color("ColorBase50"))
                        Text("REVOKED")
                            .font(
                                .customFont(
                                    font: .inter, style: .regular, size: .h3)
                            )
                            .foregroundStyle(Color("ColorBase50"))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background(Color("ColorRose700"))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .padding(.leading, CGFloat(4))
            case .suspended:
                VStack {
                    HStack(alignment: .center) {
                        Text("Status")
                            .font(
                                .customFont(
                                    font: .inter, style: .regular, size: .h4)
                            )
                            .foregroundStyle(Color("ColorStone600"))
                        Spacer()
                    }
                    HStack {
                        Image("Suspended")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 24, height: 24)
                            .foregroundColor(Color("ColorBase50"))
                        Text("SUSPENDED")
                            .font(
                                .customFont(
                                    font: .inter, style: .regular, size: .h3)
                            )
                            .foregroundStyle(Color("ColorBase50"))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background(Color("ColorYellow700"))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .padding(.leading, CGFloat(4))
            case .unknown:
                VStack {
                    HStack(alignment: .center) {
                        Text("Status")
                            .font(
                                .customFont(
                                    font: .inter, style: .regular, size: .h4)
                            )
                            .foregroundStyle(Color("ColorStone600"))
                        Spacer()
                    }
                    HStack {
                        Image("Unknown")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 24, height: 24)
                            .foregroundColor(Color("ColorStone950"))
                        Text("UNKNOWN")
                            .font(
                                .customFont(
                                    font: .inter, style: .regular, size: .h3)
                            )
                            .foregroundStyle(Color("ColorStone950"))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background(Color("ColorStone100"))
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(Color("ColorStone300"), lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .padding(.leading, CGFloat(4))
            case .invalid:
                VStack {
                    HStack(alignment: .center) {
                        Text("Status")
                            .font(
                                .customFont(
                                    font: .inter, style: .regular, size: .h4)
                            )
                            .foregroundStyle(Color("ColorStone600"))
                        Spacer()
                    }
                    HStack {
                        Image("Invalid")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 24, height: 24)
                            .foregroundColor(Color("ColorBase50"))
                        Text("INVALID")
                            .font(
                                .customFont(
                                    font: .inter, style: .regular, size: .h3)
                            )
                            .foregroundStyle(Color("ColorBase50"))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background(Color("ColorRose700"))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .padding(.leading, CGFloat(4))
            case .undefined:
                EmptyView()
            case .pending:
                VStack {
                    HStack(alignment: .center) {
                        Text("Status")
                            .font(
                                .customFont(
                                    font: .inter, style: .regular, size: .h4)
                            )
                            .foregroundStyle(Color("ColorBlue600"))
                        Spacer()
                    }
                    HStack {
                        Image("Pending")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 24, height: 24)
                            .foregroundColor(Color("ColorBase50"))
                        Text("PENDING")
                            .font(
                                .customFont(
                                    font: .inter, style: .regular, size: .h3)
                            )
                            .foregroundStyle(Color("ColorBase50"))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background(Color("ColorBlue600"))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .padding(.leading, CGFloat(4))
            case .ready:
                VStack {
                    HStack(alignment: .center) {
                        Text("Status")
                            .font(
                                .customFont(
                                    font: .inter, style: .regular, size: .h4)
                            )
                            .foregroundStyle(Color("ColorStone600"))
                        Spacer()
                    }
                    HStack {
                        Image("Valid")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 24, height: 24)
                            .foregroundColor(Color("ColorBase50"))
                        Text("READY")
                            .font(
                                .customFont(
                                    font: .inter, style: .regular, size: .h3)
                            )
                            .foregroundStyle(Color("ColorBase50"))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background(Color("ColorEmerald600"))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .padding(.leading, CGFloat(4))
            }
        } else {
            EmptyView()
        }

    }
}
