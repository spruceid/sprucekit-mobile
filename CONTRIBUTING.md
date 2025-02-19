# Releases

1. Execute a release in each submodule:

  - `mobile-sdk-rs`
      - Update the version number in `Cargo.toml`, `SpruceIDMobileSdkRs.podspec`**, and** `SpruceIDMobileSdkRsRustFramework.podspec`
      - Commit and push these changes
      - Use the `Create a new release` link in the right side bar of the Github repo to trigger the workflow and create the release
  - `mobile-sdk-swift`
      - Update the version number in `SpruceIDMobileSdk.podspec` and commit and push the change
      - Create a tag with the new version number and push the results
  - `mobile-sdk-ios-app`
      - Update the version number listed under `CFBundleVersion` and `CFBundleShortVersionString` in the `project.yml` file and commit the change
      - Create a tag  with the new version number and push the results
  - `mobile-sdk-kt`
      - Create a tag with the new version and push it to the Github repo
      - Use the `Create a new release` link on the right side bar of the Github repo to trigger the workflow and create the release

2. Pull releases into the monorepo:

  - Switch to `sprucekit-mobile` and make sure you are on the `main` branch and have pulled the latest updates from origin

  - **Execute `git subtree pull --prefix=mobile-sdk-*/ [https://github.com/spruceid/mobile-sdk-*.git](https://github.com/spruceid/mobile-sdk-rs.git) main` for each submodule**
