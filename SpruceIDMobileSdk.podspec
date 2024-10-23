Pod::Spec.new do |spec|
  spec.name         = "SpruceIDMobileSdk"
  spec.version      = "0.3.0"
  spec.summary      = "Swift Mobile SDK."
  spec.description  = <<-DESC
                   SpruceID Swift Mobile SDK.
                   DESC
  spec.homepage     = "https://github.com/spruceid/mobile-sdk-swift"
  spec.license      = { :type => "MIT & Apache License, Version 2.0", :text => <<-LICENSE
                          Refer to LICENSE-MIT and LICENSE-APACHE in the repository.
                        LICENSE
                      }
  spec.author       = { "Spruce Systems, Inc." => "hello@spruceid.com" }
  spec.platform     = :ios
  spec.swift_version = '5.9'

  spec.ios.deployment_target  = '14.0'

  spec.source        = { :git => "https://github.com/spruceid/mobile-sdk-swift.git", :tag => "#{spec.version}" }
  spec.source_files  = "Sources/MobileSdk/*.swift"

  spec.static_framework = true
  spec.dependency 'SpruceIDMobileSdkRs', "~> 0.2.2"
  spec.dependency 'SwiftAlgorithms', "~> 1.0.0"
  spec.frameworks = 'Foundation', 'CoreBluetooth', 'CryptoKit'
end
