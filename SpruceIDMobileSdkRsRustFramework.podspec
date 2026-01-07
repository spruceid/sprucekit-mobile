Pod::Spec.new do |spec|
  spec.name         = 'SpruceIDMobileSdkRsRustFramework'
  spec.version      = '0.13.16'
  spec.summary      = 'Rust-generated Framework for Swift Mobile SDK.'
  spec.description  = <<-DESC
                   Rust layer framework for the Swift Mobile SDK.
                   DESC
  spec.homepage     = 'https://github.com/spruceid/sprucekit-mobile'
  spec.license      = { :type => 'MIT & Apache License, Version 2.0', :text => <<-LICENSE
                          Refer to LICENSE-MIT and LICENSE-APACHE in the repository.
                        LICENSE
                      }
  spec.author       = { 'Spruce Systems, Inc.' => 'hello@spruceid.com' }
  spec.platform     = :ios

  spec.ios.deployment_target = '14.0'

  spec.static_framework = true
  spec.source = { :http => "https://github.com/spruceid/sprucekit-mobile/releases/download/#{spec.version}/RustFramework.xcframework.zip" }
  spec.vendored_frameworks = 'rust/MobileSdkRs/RustFramework.xcframework'
end