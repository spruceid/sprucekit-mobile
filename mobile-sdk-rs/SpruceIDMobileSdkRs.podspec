Pod::Spec.new do |spec|
  spec.name         = "SpruceIDMobileSdkRs"
  spec.version      = "0.7.0"
  spec.summary      = "Rust-generated Swift Mobile SDK."
  spec.description  = <<-DESC
                   Rust layer for the Swift Mobile SDK.
                   DESC
  spec.homepage     = "https://github.com/spruceid/mobile-sdk-rs"
  spec.license      = { :type => "MIT & Apache License, Version 2.0", :text => <<-LICENSE
                          Refer to LICENSE-MIT and LICENSE-APACHE in the repository.
                        LICENSE
                      }
  spec.author       = { "Spruce Systems, Inc." => "hello@spruceid.com" }
  spec.platform     = :ios
  spec.swift_version = '5.9'

  spec.ios.deployment_target  = '14.0'

  spec.static_framework = true
  spec.source        = { :git => "https://github.com/spruceid/mobile-sdk-rs.git", :tag => "#{spec.version}" }
  spec.source_files  = "MobileSdkRs/Sources/MobileSdkRs/*.swift"
  spec.frameworks = 'Foundation'

  spec.dependency 'SpruceIDMobileSdkRsRustFramework', "#{spec.version}"
end
