Pod::Spec.new do |spec|
  spec.name             = 'SpruceIDMobileSdk'
  spec.version          = '0.13.8'
  spec.summary          = 'SpruceID Mobile SDK for iOS'
  spec.description      = <<-DESC
                   SpruceID Swift Mobile SDK for credential management, OID4VCI issuance, and verifiable credentials.
                   DESC
  spec.homepage         = 'https://github.com/spruceid/sprucekit-mobile'
  spec.license          = { :type => 'MIT & Apache License, Version 2.0', :text => <<-LICENSE
                          Refer to LICENSE-MIT and LICENSE-APACHE in the repository.
                        LICENSE
                      }
  spec.author           = { 'Spruce Systems, Inc.' => 'hello@spruceid.com' }
  spec.platform         = :ios
  spec.swift_version    = '5.9'

  spec.ios.deployment_target = '14.0'

  spec.source           = { :git => 'https://github.com/spruceid/sprucekit-mobile.git', :tag => "#{spec.version}" }
  spec.source_files     = 'ios/MobileSdk/Sources/MobileSdk/**/*.swift'

  spec.static_framework = true
  spec.dependency 'SpruceIDMobileSdkRs', '~> 0.13.8'
  spec.dependency 'SwiftAlgorithms', '~> 1.0.0'
  spec.frameworks = 'Foundation', 'CoreBluetooth', 'CryptoKit'
end