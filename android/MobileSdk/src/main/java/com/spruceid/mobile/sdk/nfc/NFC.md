# NFC for credential presentation

### Creating the service handler

You must create a class that inherits from SpruceKit's `BaseNfcPresentationService` for handling NFC messages.

> Please note that as this is a Service, the app may be in any state when the callbacks in this class are executed.

### Adding the service to the application's manifest

You'll have to add this Service to your application's manifest. In these examples, I will assume your package path is `com.my.wallet` and the fully qualified classpath to your NFC service is `com.my.wallet.presentation.NfcPresentationService`

Add to your manifest, under the `<application>` tag:
```xml
<service android:name=".presentation.NfcPresentationService" android:exported="true"
    android:permission="android.permission.BIND_NFC_SERVICE" android:enabled="true">
    <intent-filter>
        <action android:name="android.nfc.cardemulation.action.HOST_APDU_SERVICE" />
    </intent-filter>
    <meta-data android:name="android.nfc.cardemulation.host_apdu_service"
        android:resource="@xml/nfc_hce_service" />
</service>
```

Then, in your resources directory (probably `src/main/res`), create an `xml` directory, and write this to `res/xml/nfc_hce_service.xml`:
```xml
<host-apdu-service xmlns:android="http://schemas.android.com/apk/res/android"
           android:description="[SERVICE DESC]"
           android:requireDeviceUnlock="true">
    <aid-group android:description="[NFC HCE GROUP NAME]"
               android:category="other">
        <aid-filter android:name="A0000002480400" /> <!-- mdoc, per ISO 18013-5 -->
    </aid-group>
</host-apdu-service>
```

Where:
 - `[SERVICE DESC]` is replaced with either:
   - a plaintext string describing the service, along the lines of "My Wallet NFC service"
   - a localized string resource reference, like "@string/nfc_hce_service_desc"
 - `[NFC HCE GROUP NAME]` is replaced with either:
   - a plaintext string describing what readers the app supports, like "mDL Readers"
   - a localized string resource reference, like "@string/nfc_hce_group_name"

The defaults in the service definition provided above are sensible for an mDL wallet.
You may need to change these values if you're developing a different type of application.

For more information, see the following pages from the Android developer documentation:
 - [Services](https://developer.android.com/develop/background-work/services#Basics)
 - [Manifest `<service>` element](https://developer.android.com/guide/topics/manifest/service-element)
 - [Host-based Card Emulation services](https://developer.android.com/develop/connectivity/nfc/hce#manifest-declaration)

### Ensuring that your wallet is always used when in the foreground

Android allows users to determine which wallet they wish to use for specific NFC transactions.
You likely want to use `setPreferredService` to give your wallet priority while it's in the foreground.

Google recommends calling `setPreferredService` in your `Activity.onResume` and `unsetPreferredService` in your `Activity.onPause`.

Using these functions may look something like this, in practice:
```kotlin
class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()

        NfcAdapter.getDefaultAdapter(this)?.let {
            val cardEmulation = CardEmulation.getInstance(it)
            if(!cardEmulation.setPreferredService(this, ComponentName(this, NfcPresentationService::class.java))) {
                Log.w("MainActivity", "cardEmulation.setPreferredService() failed")
            }
        }
    }
    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.let {
            val cardEmulation = CardEmulation.getInstance(it)
            if (!cardEmulation.unsetPreferredService(this)) {
               Log.i("MainActivity", "cardEmulation.unsetPreferredService() failed")
            }
        }
    }
    // ...
}
```