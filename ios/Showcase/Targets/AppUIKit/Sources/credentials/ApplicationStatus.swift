import SpruceIDMobileSdkRs
import SwiftUI

struct ApplicationStatusSmall: View {
    var status: FlowState?

    var body: some View {
        if status != nil {
            switch status! {
            case .proofingRequired:
                HStack {
                    Image("Unknown")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 14, height: 14)
                        .foregroundColor(Color("ColorStone950"))
                    Text("Proofing Required")
                        .font(
                            .customFont(
                                font: .inter,
                                style: .regular,
                                size: .small
                            )
                        )
                        .foregroundStyle(Color("ColorStone950"))
                }
            case .awaitingManualReview:
                HStack {
                    Image("Pending")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 14, height: 14)
                        .foregroundColor(Color("ColorBlue600"))
                    Text("Awaiting Manual Review")
                        .font(
                            .customFont(
                                font: .inter,
                                style: .regular,
                                size: .small
                            )
                        )
                        .foregroundStyle(Color("ColorBlue600"))
                }

            case .readyToProvision:
                HStack {
                    Image("Valid")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 14, height: 14)
                        .foregroundColor(Color("ColorEmerald600"))
                    Text("Ready to Provision")
                        .font(
                            .customFont(
                                font: .inter,
                                style: .regular,
                                size: .small
                            )
                        )
                        .foregroundStyle(Color("ColorEmerald600"))
                }

            case .applicationDenied:
                HStack {
                    Image("Invalid")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 14, height: 14)
                        .foregroundColor(Color("ColorRose700"))
                    Text("Application Denied")
                        .font(
                            .customFont(
                                font: .inter,
                                style: .regular,
                                size: .small
                            )
                        )
                        .foregroundStyle(Color("ColorRose700"))
                }
            }
        } else {
            EmptyView()
        }

    }
}

struct ApplicationStatus: View {
    var status: FlowState?

    var body: some View {
        if status != nil {
            switch status! {
            case .proofingRequired:
                VStack {
                    HStack(alignment: .center) {
                        Text("Status")
                            .font(
                                .customFont(
                                    font: .inter,
                                    style: .regular,
                                    size: .h4
                                )
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
                        Text("PROOFING REQUIRED")
                            .font(
                                .customFont(
                                    font: .inter,
                                    style: .regular,
                                    size: .h3
                                )
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

            case .awaitingManualReview:
                VStack {
                    HStack(alignment: .center) {
                        Text("Status")
                            .font(
                                .customFont(
                                    font: .inter,
                                    style: .regular,
                                    size: .h4
                                )
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
                        Text("AWAITING MANUAL REVIEW")
                            .font(
                                .customFont(
                                    font: .inter,
                                    style: .regular,
                                    size: .h3
                                )
                            )
                            .foregroundStyle(Color("ColorBase50"))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background(Color("ColorBlue600"))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .padding(.leading, CGFloat(4))

            case .readyToProvision:
                VStack {
                    HStack(alignment: .center) {
                        Text("Status")
                            .font(
                                .customFont(
                                    font: .inter,
                                    style: .regular,
                                    size: .h4
                                )
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
                        Text("READY TO PROVISION")
                            .font(
                                .customFont(
                                    font: .inter,
                                    style: .regular,
                                    size: .h3
                                )
                            )
                            .foregroundStyle(Color("ColorBase50"))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background(Color("ColorEmerald600"))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .padding(.leading, CGFloat(4))

            case .applicationDenied:
                VStack {
                    HStack(alignment: .center) {
                        Text("Status")
                            .font(
                                .customFont(
                                    font: .inter,
                                    style: .regular,
                                    size: .h4
                                )
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
                        Text("APPLICATION DENIED")
                            .font(
                                .customFont(
                                    font: .inter,
                                    style: .regular,
                                    size: .h3
                                )
                            )
                            .foregroundStyle(Color("ColorBase50"))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background(Color("ColorRose700"))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .padding(.leading, CGFloat(4))
            }
        } else {
            EmptyView()
        }

    }
}
