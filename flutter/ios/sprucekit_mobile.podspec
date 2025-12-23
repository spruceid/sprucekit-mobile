Pod::Spec.new do |s|
  s.name             = 'sprucekit_mobile'
  s.version          = '0.13.14'
  s.summary          = 'Flutter plugin for SpruceKit Mobile SDK'
  s.description      = <<-DESC
Flutter plugin providing access to SpruceKit Mobile SDK functionality
for credential issuance (OID4VCI) and credential management.
                       DESC
  s.homepage         = 'https://github.com/spruceid/sprucekit-mobile'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'SpruceID' => 'hello@spruceid.com' }
  s.source           = { :path => '.' }
  s.platform         = :ios, '14.0'
  s.swift_version    = '5.0'

  s.source_files = 'Classes/*.swift'

  s.dependency 'Flutter'
  s.dependency 'SpruceIDMobileSdk', '~> 0.13.14'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386'
  }
end