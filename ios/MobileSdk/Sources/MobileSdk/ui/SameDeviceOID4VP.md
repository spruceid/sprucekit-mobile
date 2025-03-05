# Same Device OpenID4VP

If you already have the OpenID4VP working on your app, you need to configure a deep link to get the `openid4vp://` URL and start the flow.

## Configuring the app Info

1. Go to the Info tab
2. Find the "URL Types" section and click on the add symbol (+) to create a new one
3. Fill the "Identifier" field with anything that you want to help you identify this URL type (e.g., OID4VP)
4. Fill the "URL Schemes" field with `openid4vp`

## Handle the Deep Link

Add the following to your `@main struct : App`.

```swift
@main
struct MyApp: App {
  var body: some Scene {
    WindowGroup {
      ContentView()
        .onOpenURL { url in
          // OID4VP flow integration
        }
    }
  }
}
```

And now your app is ready to handle `openid4vp://` URLs!
