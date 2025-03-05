# Same Device OpenID4VP

If you already have the OpenID4VP working on your app, you need to configure a deep link to get the `openid4vp://` URL and start the flow.

## Configuring the AndroidManifest.xml to Declare Intent Filters

Add the following inside the `<activity android:name=".MainActivity">` tag.

```xml
<intent-filter>
  <!-- Intent action and categories -->
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <category android:name="android.intent.category.BROWSABLE" />
  <!-- Deep link data -->
  <data
      android:scheme="openid4vp"
  />
</intent-filter>
```

## Handle the Intent

Add the following inside the `override fun onCreate(...)` method.

```kotlin
super.onCreate(savedInstanceState)

// ...

val deepLinkUri: Uri? = intent.data
if (deepLinkUri != null) {
    // OID4VP flow integration
}

// ...
```

And now your app is ready to handle `openid4vp://` URLs!
