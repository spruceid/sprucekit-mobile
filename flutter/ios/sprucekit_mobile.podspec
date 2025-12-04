#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint sprucekit_mobile.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'sprucekit_mobile'
  s.version          = '0.0.1'
  s.summary          = 'Flutter plugin for SpruceKit Mobile SDK'
  s.description      = <<-DESC
Flutter plugin providing access to SpruceKit Mobile SDK functionality
for credential issuance (OID4VCI) and credential management.
                       DESC
  s.homepage         = 'https://github.com/spruceid/sprucekit-mobile'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'SpruceID' => 'hello@spruceid.com' }
  s.source           = { :path => '.' }
  s.platform = :ios, '14.0'

  # Dependencies
  s.dependency 'Flutter'

  # Plugin adapter source files
  s.source_files = 'Classes/*.swift'

  # Pre-built SDK frameworks (run scripts/build_xcframeworks.sh to generate)
  s.vendored_frameworks = 'Frameworks/RustFramework.xcframework'
  s.vendored_libraries = 'Frameworks/libSpruceIDMobileSdk.a'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386',
    'SWIFT_INCLUDE_PATHS' => '$(inherited) "${PODS_TARGET_SRCROOT}/Frameworks/SwiftModules"',
    'OTHER_LDFLAGS' => '$(inherited) -ObjC'
  }
  s.swift_version = '5.0'
end
