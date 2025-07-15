package com.spruceid.mobile.sdk.dcapi

import android.app.Application
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import androidx.compose.ui.text.capitalize
import androidx.compose.ui.text.intl.Locale
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.registry.provider.RegisterCredentialsRequest
import androidx.credentials.registry.provider.RegistryManager
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.rs.Element
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.ParsedCredential
import okio.ByteString.Companion.decodeBase64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The credential registry for wallet selection over Digital Credentials API.
 *
 * This class should be instantiated once by the application, and kept up-to-date by calling
 * [register] each time credentials are added or removed.
 *
 * Currently only supports the [CredentialPack] format of credential storage.
 *
 * @param application the application that is registering credentials for presentation over DC-API.
 * @param iconName the name of an image file in the application's assets directory, to be shown in
 * the wallet selection modal.
 */
class Registry(
    application: Application,
    iconName: String
) {
    private val icon: ByteArray = loadAsset(application.assets, iconName)
    private val registryManager = RegistryManager.Companion.create(application)

    @OptIn(ExperimentalDigitalCredentialApi::class)
    suspend fun register(credentialPacks: List<CredentialPack>) {
        val mdocs = toMdocStream(credentialPacks);
        val database = toRegistryDatabase(mdocs);

        try {
            val matcher = matcherWasm ?: throw Exception("failed to load matcher")

            registryManager.registerCredentials(request = object : RegisterCredentialsRequest(
                "com.credman.IdentityCredential", "openid4vp", database, matcher
            ) {})

            registryManager.registerCredentials(request = object : RegisterCredentialsRequest(
                DigitalCredential.Companion.TYPE_DIGITAL_CREDENTIAL, "openid4vp", database, matcher
            ) {})
        } catch (e: Exception) {
            Log.e(TAG, "failed to register credentials $e")
        }
    }


    @OptIn(ExperimentalEncodingApi::class)
    private fun toRegistryDatabase(mdocs: List<Pair<String, Mdoc>>): ByteArray {
        val out = ByteArrayOutputStream()

        val iconMap: Map<String, RegistryIcon> = mdocs.associate {
            Pair(
                it.first, RegistryIcon(icon)
            )
        }
        // Write the offset to the json
        val jsonOffset = 4 + iconMap.values.sumOf { it.iconValue.size }
        val buffer = ByteBuffer.allocate(4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(jsonOffset)
        out.write(buffer.array())

        // Write the icons
        var currIconOffset = 4
        iconMap.values.forEach {
            it.iconOffset = currIconOffset
            out.write(it.iconValue)
            currIconOffset += it.iconValue.size
        }

        val mdocCredentials = JSONObject()
        mdocs.forEach { mdoc ->
            val credJson = JSONObject()
            credJson.putCommon(mdoc.first, mdoc.second.details(), iconMap)

            val pathJson = JSONObject()
            mdoc.second.details().forEach { (namespace, elements) ->
                val namespaceJson = JSONObject()
                elements.forEach { (element, value) ->
                    Log.d(TAG, "Registering claim: $element")
                    val namespaceDataJson = JSONObject()
                    namespaceDataJson.put(
                        DISPLAY,
                        element.split('_')
                            .joinToString(" ", transform = { s -> s.capitalize(Locale.Companion.current) })
                    )
                    val parsedValue = JSONTokener(value).nextValue()
                    namespaceDataJson.putOpt(VALUE, parsedValue)
                    namespaceJson.put(element, namespaceDataJson)
                }
                pathJson.put(namespace, namespaceJson)
            }
            credJson.put(PATHS, pathJson)

            if (Build.VERSION.SDK_INT >= 33) {
                mdocCredentials.append(mdoc.second.doctype(), credJson)
            } else {
                when (val current = mdocCredentials.opt(mdoc.second.doctype())) {
                    is JSONArray -> {
                        mdocCredentials.put(mdoc.second.doctype(), current.put(credJson))
                    }

                    null -> {
                        mdocCredentials.put(
                            mdoc.second.doctype(), JSONArray().put(credJson)
                        )
                    }

                    else -> throw IllegalStateException(
                        "Unexpected namespaced data that's" + " not a JSONArray. Instead it is ${current::class.java}"
                    )
                }
            }
        }
        val registryCredentials = JSONObject()
        registryCredentials.put("mso_mdoc", mdocCredentials)
        val registryJson = JSONObject()
        registryJson.put(CREDENTIALS, registryCredentials)
        out.write(registryJson.toString().toByteArray())
        Log.d(TAG, "Registry: $registryJson")
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "dcapi.Registry"

        // Wasm database json keys
        private const val CREDENTIALS = "credentials"
        private const val ID = "id"
        private const val TITLE = "title"
        private const val SUBTITLE = "subtitle"
        private const val ICON = "icon"
        private const val START = "start"
        private const val LENGTH = "length"
        private const val PATHS = "paths"
        private const val VALUE = "value"
        private const val DISPLAY = "display"

        private fun loadAsset(assets: AssetManager, name: String): ByteArray {
            val stream = assets.open(name);
            val data = ByteArray(stream.available())
            stream.read(data)
            stream.close()
            return data
        }

        private fun toMdocStream(credentialPacks: List<CredentialPack>) =
            credentialPacks.flatMap { pack ->
                pack.list()
                    .mapNotNull { credential -> credentialToComboIdPlusMdoc(pack.id(), credential) }
            }

        private fun credentialToComboIdPlusMdoc(
            packId: UUID, credential: ParsedCredential
        ): Pair<String, Mdoc>? {
            val mdoc = credential.asMsoMdoc() ?: return null
            return Pair("$packId" + "~" + mdoc.id(), mdoc)
        }

        fun idsFromComboId(it: String): Pair<String, String> {
            val parts = it.split("~", limit = 2)
            val packId = parts[0]
            val mdocId = parts[1]
            return Pair(packId, mdocId)
        }

        class RegistryIcon(
            val iconValue: ByteArray, var iconOffset: Int = 0
        )

        private fun JSONObject.putCommon(
            id: String, mdocDetails: Map<String, List<Element>>, iconMap: Map<String, RegistryIcon>
        ) {
            put(ID, id)

            val name =
                mdocDetails["org.iso.18013.5.1"]?.first { element -> element.identifier == "given_name" }?.value?.filterNot { c -> c == '"' }
            val title = if (name != null) {
                "Driver's License ($name)"
            } else {
                "Driver's License"
            }

            put(TITLE, title)
            putOpt(SUBTITLE, "SpruceKit Showcase Wallet")
            val iconJson = JSONObject().apply {
                put(START, iconMap[id]!!.iconOffset)
                put(LENGTH, iconMap[id]!!.iconValue.size)
            }
            put(ICON, iconJson)
        }

        const val defaultTrustedApps: String = """
{
  "apps": [
    {
      "type": "android",
      "info": {
        "package_name": "com.android.chrome",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83"
          },
          {
            "build": "userdebug",
            "cert_fingerprint_sha256": "19:75:B2:F1:71:77:BC:89:A5:DF:F3:1F:9E:64:A6:CA:E2:81:A5:3D:C1:D1:D5:9B:1D:14:7F:E1:C8:2A:FA:00"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.chrome.beta",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "DA:63:3D:34:B6:9E:63:AE:21:03:B4:9D:53:CE:05:2F:C5:F7:F3:C5:3A:AB:94:FD:C2:A2:08:BD:FD:14:24:9C"
          },
          {
            "build": "release",
            "cert_fingerprint_sha256": "3D:7A:12:23:01:9A:A3:9D:9E:A0:E3:43:6A:B7:C0:89:6B:FB:4F:B6:79:F4:DE:5F:E7:C2:3F:32:6C:8F:99:4A"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.chrome.dev",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "90:44:EE:5F:EE:4B:BC:5E:21:DD:44:66:54:31:C4:EB:1F:1F:71:A3:27:16:A0:BC:92:7B:CB:B3:92:33:CA:BF"
          },
          {
            "build": "release",
            "cert_fingerprint_sha256": "3D:7A:12:23:01:9A:A3:9D:9E:A0:E3:43:6A:B7:C0:89:6B:FB:4F:B6:79:F4:DE:5F:E7:C2:3F:32:6C:8F:99:4A"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.chrome.canary",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "20:19:DF:A1:FB:23:EF:BF:70:C5:BC:D1:44:3C:5B:EA:B0:4F:3F:2F:F4:36:6E:9A:C1:E3:45:76:39:A2:4C:FC"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "org.chromium.chrome",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "C6:AD:B8:B8:3C:6D:4C:17:D2:92:AF:DE:56:FD:48:8A:51:D3:16:FF:8F:2C:11:C5:41:02:23:BF:F8:A7:DB:B3"
          },
          {
            "build": "userdebug",
            "cert_fingerprint_sha256": "19:75:B2:F1:71:77:BC:89:A5:DF:F3:1F:9E:64:A6:CA:E2:81:A5:3D:C1:D1:D5:9B:1D:14:7F:E1:C8:2A:FA:00"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.google.android.apps.chrome",
        "signatures": [
          {
            "build": "userdebug",
            "cert_fingerprint_sha256": "19:75:B2:F1:71:77:BC:89:A5:DF:F3:1F:9E:64:A6:CA:E2:81:A5:3D:C1:D1:D5:9B:1D:14:7F:E1:C8:2A:FA:00"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "org.mozilla.fennec_webauthndebug",
        "signatures": [
          {
            "build": "userdebug",
            "cert_fingerprint_sha256": "BD:AE:82:02:80:D2:AF:B7:74:94:EF:22:58:AA:78:A9:AE:A1:36:41:7E:8B:C2:3D:C9:87:75:2E:6F:48:E8:48"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "org.mozilla.firefox",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "A7:8B:62:A5:16:5B:44:94:B2:FE:AD:9E:76:A2:80:D2:2D:93:7F:EE:62:51:AE:CE:59:94:46:B2:EA:31:9B:04"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "org.mozilla.firefox_beta",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "A7:8B:62:A5:16:5B:44:94:B2:FE:AD:9E:76:A2:80:D2:2D:93:7F:EE:62:51:AE:CE:59:94:46:B2:EA:31:9B:04"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "org.mozilla.focus",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "62:03:A4:73:BE:36:D6:4E:E3:7F:87:FA:50:0E:DB:C7:9E:AB:93:06:10:AB:9B:9F:A4:CA:7D:5C:1F:1B:4F:FC"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "org.mozilla.fennec_aurora",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "BC:04:88:83:8D:06:F4:CA:6B:F3:23:86:DA:AB:0D:D8:EB:CF:3E:77:30:78:74:59:F6:2F:B3:CD:14:A1:BA:AA"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "org.mozilla.rocket",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "86:3A:46:F0:97:39:32:B7:D0:19:9B:54:91:12:74:1C:2D:27:31:AC:72:EA:11:B7:52:3A:A9:0A:11:BF:56:91"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.microsoft.emmx.canary",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "01:E1:99:97:10:A8:2C:27:49:B4:D5:0C:44:5D:C8:5D:67:0B:61:36:08:9D:0A:76:6A:73:82:7C:82:A1:EA:C9"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.microsoft.emmx.dev",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "01:E1:99:97:10:A8:2C:27:49:B4:D5:0C:44:5D:C8:5D:67:0B:61:36:08:9D:0A:76:6A:73:82:7C:82:A1:EA:C9"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.microsoft.emmx.beta",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "01:E1:99:97:10:A8:2C:27:49:B4:D5:0C:44:5D:C8:5D:67:0B:61:36:08:9D:0A:76:6A:73:82:7C:82:A1:EA:C9"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.microsoft.emmx",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "01:E1:99:97:10:A8:2C:27:49:B4:D5:0C:44:5D:C8:5D:67:0B:61:36:08:9D:0A:76:6A:73:82:7C:82:A1:EA:C9"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.microsoft.emmx.rolling",
        "signatures": [
          {
            "build": "userdebug",
            "cert_fingerprint_sha256": "32:A2:FC:74:D7:31:10:58:59:E5:A8:5D:F1:6D:95:F1:02:D8:5B:22:09:9B:80:64:C5:D8:91:5C:61:DA:D1:E0"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.microsoft.emmx.local",
        "signatures": [
          {
            "build": "userdebug",
            "cert_fingerprint_sha256": "32:A2:FC:74:D7:31:10:58:59:E5:A8:5D:F1:6D:95:F1:02:D8:5B:22:09:9B:80:64:C5:D8:91:5C:61:DA:D1:E0"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.brave.browser",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "9C:2D:B7:05:13:51:5F:DB:FB:BC:58:5B:3E:DF:3D:71:23:D4:DC:67:C9:4F:FD:30:63:61:C1:D7:9B:BF:18:AC"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.brave.browser_beta",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "9C:2D:B7:05:13:51:5F:DB:FB:BC:58:5B:3E:DF:3D:71:23:D4:DC:67:C9:4F:FD:30:63:61:C1:D7:9B:BF:18:AC"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.brave.browser_nightly",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "9C:2D:B7:05:13:51:5F:DB:FB:BC:58:5B:3E:DF:3D:71:23:D4:DC:67:C9:4F:FD:30:63:61:C1:D7:9B:BF:18:AC"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "app.vanadium.browser",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "C6:AD:B8:B8:3C:6D:4C:17:D2:92:AF:DE:56:FD:48:8A:51:D3:16:FF:8F:2C:11:C5:41:02:23:BF:F8:A7:DB:B3"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.vivaldi.browser",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "E8:A7:85:44:65:5B:A8:C0:98:17:F7:32:76:8F:56:89:B1:66:2E:C4:B2:BC:5A:0B:C0:EC:13:8D:33:CA:3D:1E"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.vivaldi.browser.snapshot",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "E8:A7:85:44:65:5B:A8:C0:98:17:F7:32:76:8F:56:89:B1:66:2E:C4:B2:BC:5A:0B:C0:EC:13:8D:33:CA:3D:1E"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.vivaldi.browser.sopranos",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "E8:A7:85:44:65:5B:A8:C0:98:17:F7:32:76:8F:56:89:B1:66:2E:C4:B2:BC:5A:0B:C0:EC:13:8D:33:CA:3D:1E"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.citrix.Receiver",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "3D:D1:12:67:10:69:AB:36:4E:F9:BE:73:9A:B7:B5:EE:15:E1:CD:E9:D8:75:7B:1B:F0:64:F5:0C:55:68:9A:49"
          },
          {
            "build": "release",
            "cert_fingerprint_sha256": "CE:B2:23:D7:77:09:F2:B6:BC:0B:3A:78:36:F5:A5:AF:4C:E1:D3:55:F4:A7:28:86:F7:9D:F8:0D:C9:D6:12:2E"
          },
          {
            "build": "release",
            "cert_fingerprint_sha256": "AA:D0:D4:57:E6:33:C3:78:25:77:30:5B:C1:B2:D9:E3:81:41:C7:21:DF:0D:AA:6E:29:07:2F:C4:1D:34:F0:AB"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.android.browser",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "C9:00:9D:01:EB:F9:F5:D0:30:2B:C7:1B:2F:E9:AA:9A:47:A4:32:BB:A1:73:08:A3:11:1B:75:D7:B2:14:90:25"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.sec.android.app.sbrowser",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8"
          },
          {
            "build": "release",
            "cert_fingerprint_sha256": "34:DF:0E:7A:9F:1C:F1:89:2E:45:C0:56:B4:97:3C:D8:1C:CF:14:8A:40:50:D1:1A:EA:4A:C5:A6:5F:90:0A:42"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.sec.android.app.sbrowser.beta",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8"
          },
          {
            "build": "release",
            "cert_fingerprint_sha256": "34:DF:0E:7A:9F:1C:F1:89:2E:45:C0:56:B4:97:3C:D8:1C:CF:14:8A:40:50:D1:1A:EA:4A:C5:A6:5F:90:0A:42"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.google.android.gms",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "7C:E8:3C:1B:71:F3:D5:72:FE:D0:4C:8D:40:C5:CB:10:FF:75:E6:D8:7D:9D:F6:FB:D5:3F:04:68:C2:90:50:53"
          },
          {
            "build": "release",
            "cert_fingerprint_sha256": "D2:2C:C5:00:29:9F:B2:28:73:A0:1A:01:0D:E1:C8:2F:BE:4D:06:11:19:B9:48:14:DD:30:1D:AB:50:CB:76:78"
          },
          {
            "build": "release",
            "cert_fingerprint_sha256": "F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83"
          },
          {
            "build": "release",
            "cert_fingerprint_sha256": "19:75:B2:F1:71:77:BC:89:A5:DF:F3:1F:9E:64:A6:CA:E2:81:A5:3D:C1:D1:D5:9B:1D:14:7F:E1:C8:2A:FA:00"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.yandex.browser",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "AC:A4:05:DE:D8:B2:5C:B2:E8:C6:DA:69:42:5D:2B:43:07:D0:87:C1:27:6F:C0:6A:D5:94:27:31:CC:C5:1D:BA"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.yandex.browser.beta",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "AC:A4:05:DE:D8:B2:5C:B2:E8:C6:DA:69:42:5D:2B:43:07:D0:87:C1:27:6F:C0:6A:D5:94:27:31:CC:C5:1D:BA"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.yandex.browser.alpha",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "AC:A4:05:DE:D8:B2:5C:B2:E8:C6:DA:69:42:5D:2B:43:07:D0:87:C1:27:6F:C0:6A:D5:94:27:31:CC:C5:1D:BA"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.yandex.browser.corp",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "AC:A4:05:DE:D8:B2:5C:B2:E8:C6:DA:69:42:5D:2B:43:07:D0:87:C1:27:6F:C0:6A:D5:94:27:31:CC:C5:1D:BA"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.yandex.browser.canary",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "1D:A9:CB:AE:2D:CC:C6:A5:8D:6C:94:7B:E9:4C:DB:B7:33:D6:5D:A4:D1:77:0F:A1:4A:53:64:CB:4A:28:EB:49"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.yandex.browser.broteam",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "1D:A9:CB:AE:2D:CC:C6:A5:8D:6C:94:7B:E9:4C:DB:B7:33:D6:5D:A4:D1:77:0F:A1:4A:53:64:CB:4A:28:EB:49"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.talonsec.talon",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "A3:66:03:44:A6:F6:AF:CA:81:8C:BF:43:96:A2:3C:CF:D5:ED:7A:78:1B:B4:A3:D1:85:03:01:E2:F4:6D:23:83"
          },
          {
            "build": "release",
            "cert_fingerprint_sha256": "E2:A5:64:74:EA:23:7B:06:67:B6:F5:2C:DC:E9:04:5E:24:88:3B:AE:D0:82:59:9A:A2:DF:0B:60:3A:CF:6A:3B"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.talonsec.talon_beta",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "F5:86:62:7A:32:C8:9F:E6:7E:00:6D:B1:8C:34:31:9E:01:7F:B3:B2:BE:D6:9D:01:01:B7:F9:43:E7:7C:48:AE"
          },
          {
            "build": "release",
            "cert_fingerprint_sha256": "9A:A1:25:D5:E5:5E:3F:B0:DE:96:72:D9:A9:5D:04:65:3F:49:4A:1E:C3:EE:76:1E:94:C4:4E:5D:2F:65:8E:2F"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.duckduckgo.mobile.android.debug",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "C4:F0:9E:2B:D7:25:AD:F5:AD:92:0B:A2:80:27:66:AC:16:4A:C1:53:B3:EA:9E:08:48:B0:57:98:37:F7:6A:29"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.duckduckgo.mobile.android",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "BB:7B:B3:1C:57:3C:46:A1:DA:7F:C5:C5:28:A6:AC:F4:32:10:84:56:FE:EC:50:81:0C:7F:33:69:4E:B3:D2:D4"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.naver.whale",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "0B:8B:85:23:BB:4A:EF:FA:34:6E:4B:DD:4F:BF:7D:19:34:50:56:9A:A1:4A:AA:D4:AD:FD:94:A3:F7:B2:27:BB"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.fido.fido2client",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "FC:98:DA:E6:3A:D3:96:26:C8:C6:7F:BE:83:F2:F0:6F:74:93:2A:9C:D1:46:B9:2C:EC:FC:6A:04:7A:90:43:86"
          }
        ]
      }
    },
    {
      "type": "android",
      "info": {
        "package_name": "com.heytap.browser",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "AF:F8:A7:49:CF:0E:7D:75:44:65:D0:FB:FA:7B:8D:0C:64:5E:22:5C:10:C6:E2:32:AD:A0:D9:74:88:36:B8:E5"
          },
          {
            "build": "release",
            "cert_fingerprint_sha256": "A8:FE:A4:CA:FB:93:32:DA:26:B8:E6:81:08:17:C1:DA:90:A5:03:0E:35:A6:0A:79:E0:6C:90:97:AA:C6:A4:42"
          }
        ]
      }
    }
  ]
}
        """

        private val matcherWasm by lazy { "AGFzbQEAAAABsAEaYAF/AGABfwF/YAJ/fwF/YAN/f38Bf2ADf35/AX5gC39/f39/f39/f39/AGAHf39/f39/fwBgA39/fwBgBH9+f38Bf2AEf39/fwF/YAAAYAABf2ABfwF8YAF8AX9gAn9/AGACfH8BfGAFf39/f38Bf2AFf39/f38AYAJ/fgBgBH9/f34BfmACfHwBfGADf39/AXxgBX9/f39/AXxgAn9/AX5gAn9/AXxgBX9+fn5+AAL5AgwHY3JlZG1hbg5HZXRSZXF1ZXN0U2l6ZQAAB2NyZWRtYW4QR2V0UmVxdWVzdEJ1ZmZlcgAAB2NyZWRtYW4SR2V0Q3JlZGVudGlhbHNTaXplAAAHY3JlZG1hbhVSZWFkQ3JlZGVudGlhbHNCdWZmZXIAAwdjcmVkbWFuD0FkZFBheW1lbnRFbnRyeQAFB2NyZWRtYW4QQWRkU3RyaW5nSWRFbnRyeQAGB2NyZWRtYW4YQWRkRmllbGRGb3JTdHJpbmdJZEVudHJ5AAcWd2FzaV9zbmFwc2hvdF9wcmV2aWV3MQhmZF9jbG9zZQABFndhc2lfc25hcHNob3RfcHJldmlldzENZmRfZmRzdGF0X2dldAACFndhc2lfc25hcHNob3RfcHJldmlldzEHZmRfc2VlawAIFndhc2lfc25hcHNob3RfcHJldmlldzEIZmRfd3JpdGUACRZ3YXNpX3NuYXBzaG90X3ByZXZpZXcxCXByb2NfZXhpdAAAA25tCgoLAgICAQEMAAkCAgABAQICAQICAQICAwICAw0LCwEDAQIBAQAAAg4BAggJAAoBAQoKAgMDAQEDAwEDBAQLCgEDCQICAQMCDwIDEAcRCgkDAwEBEgETDxQVFhcJAQMKAwMYAwMDAgICAQMCGQQFAXABCgoFAwEAAgYIAX8BQYDwBAsHEwIGbWVtb3J5AgAGX3N0YXJ0AA0JDwEAQQELCS8xM0VDR0lcbQr2rANtAgALRAEBfwJAAkBBACgCwOOAgAANAEEAQQE2AsDjgIAAEIyAgIAAEI6AgIAAIQAQvoCAgAAgAA0BDwsAAAsgABC5gICAAAAL6w0EB38BfAx/AXwjgICAgABB8ABrIgAkgICAgAAgAEHoAGoQgoCAgAAgACgCaCIBEK+AgIAAIgJBACABEIOAgIAAGiAAIAIoAgAiATYCYEGtjICAACAAQeAAahC/gICAABogACACIAFqEJqAgIAAQcmJgIAAEKOAgIAAIgMQm4CAgAA2AlBBgIyAgAAgAEHQAGoQv4CAgAAaIABB7ABqEICAgIAAIAAoAmwQr4CAgAAiARCBgICAACAAIAEQmoCAgAAiARCbgICAADYCQEHvi4CAACAAQcAAahC/gICAABoCQCABQaSJgIAAQbiJgIAAIAFBpImAgAAQpYCAgAAiBBsQo4CAgAAiBRChgICAACIGQQFIDQBEAAAAAAAAAAAhB0EAIQhBACEJQQAhCkEAIQtBACEMA0ACQCAFIAwQooCAgAAiAUGiioCAABCjgICAABCSgICAAEGPioCAABD0gICAAA0AAkACQAJAIARFDQAgAUG0i4CAABCjgICAACIBEJOAgIAADQEMAgsgAUHRiICAABCjgICAACEBCyABEJKAgIAAEJqAgIAAIQELAkAgAUHRiICAABClgICAAEUNACABQdGIgIAAEKOAgIAAEJKAgIAAQS4Q8oCAgABBAWoiAUEuEPKAgIAAQQA6AAAgASAAQewAahCugICAABogACgCbBCagICAACEBCyABQYSIgIAAEKOAgIAAIQ0gAUGJioCAABClgICAACEOQQAhDwJAIAFBqIuAgAAQo4CAgAAiAUUNACABEKGAgIAAQQFHDQAgAUEAEKKAgIAAEJKAgIAAIABB7ABqEK6AgIAAGiAAKAJsEJqAgIAAIgFB+omAgAAQo4CAgAAhDyABQeuKgIAAEKOAgIAAEJKAgIAAIQogAUHfiICAABCjgICAABCSgICAACELC0EBIAkgDhshCSANIAMQkYCAgABBABCigICAACINQY+LgIAAEKOAgIAAIQEgDUGMi4CAABCjgICAACENIAFFDQAgASgCCCIQRQ0AAkAgD0UNACAPQQhqIREDQBCpgICAACIBQYyLgIAAIBBBjIuAgAAQo4CAgAAQp4CAgAAaIAFBgouAgAAgDRCngICAABogAUGXiICAACAHEKiAgIAAEKeAgIAAGiABEJ6AgIAAIRIgACAPEJuAgIAANgIwQdaLgIAAIABBMGoQv4CAgAAaIBEhAQJAA0AgASgCACIBRQ0BIA0Qm4CAgAAhDiAAIAEQm4CAgAA2AiQgACAONgIgQcOMgIAAIABBIGoQv4CAgAAaIAEgDUECEKyAgIAARQ0ACyAQ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DAELIAJB///DAEsNAUHwASELQQQhCQsgAyAJQX9qQf8BcWoiDCACQT9xQYABcjoAACACQQZ2IQYCQCAJQQJGDQAgDEF/aiAGQT9xQYABcjoAACACQQx2IQYgCUEDRg0AIAxBfmogBkE/cUGAAXI6AAAgAkESdiEGCyAGIAtyIQIMAgsgBSABQRRqKAIAEYCAgIAAAAwKC0EBIQlBBiEHQQAhAgsgAyACOgAAIAMgCWohAwwBCyADQQFqIQNBAiEHCyAEIAdqIQQLIAQgCEkNAAsLIANBADoAACAAIAU2AhAgAEEQNgIMIAEgCCABKAIAa0EBajYCCEEBDwsgAyAHQQFqIgdqIAJrIAVPDQIgBkEBaiEGCyADIAdBAWoiB2ogAmsgBUkNAAsLIAEgBCABKAIAazYCCEEAC2MBA38CQCAARQ0AIAAoAgAiAUUNACAAKAIIIgIgACgCBCIDTw0AAkACQANAIAEgAmotAABBIEsNASAAIAJBAWoiAjYCCCADIAJHDQAMAgsLIAMgAkcNAQsgACADQX9qNgIICwsjAAJAIAANAEEADwsgACAAEPWAgIAAQQFqQQBBABCWgICAAAsMACAAQQEQnICAgAAL/gIBA38jgICAgABBMGsiAiSAgICAACACQRhqIgNCADcDACACQRBqQgA3AwAgAkIANwMIIAJCADcDAEGAAkEAKAK84YCAABGBgICAAAAhBCADQQApArzhgIAANwMAIAJBIGpBACgCxOGAgAA2AgAgAiABNgIUIAJBgAI2AgQgAiAENgIAAkACQCAERQ0AIAAgAhCdgICAAEUNAAJAIAIoAgAiBEUNACACIAQgAigCCCIBahD1gICAACABajYCCAsCQEEAKALE4YCAACIBRQ0AIAQgAigCCEEBaiABEYKAgIAAACIEDQIMAQsgAigCCEEBakEAKAK84YCAABGBgICAAAAiBEUNACAEIAIoAgAgAigCBCIBIAIoAghBAWoiAyABIANJGxDwgICAACACKAIIakEAOgAAIAIoAgBBACgCwOGAgAARgICAgAAADAELQQAhBCACKAIAIgFFDQBBACEEIAFBACgCwOGAgAARgICAgAAACyACQTBqJICAgIAAIAQL0A0DA38DfAF/I4CAgIAAQeAAayICJICAgIAAAkACQCAADQBBACEDDAELAkAgAQ0AQQAhAwwB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KQMANwMAIAMgASkDADcDACAGIAFBCGopAwA3AwAgBSABQRBqKQMANwMAIAIgAUEYaikDADcDAEEAIQIgBEEANgIAIANCADcDACADIAMoAgxBgAJyNgIMIAMgAEYNAAJAAkACQCAAKAIIIgENACADQQRqIQEgACADNgIIDAELIAEoAgQiAkUNASABQQRqIQEgAyACNgIEIAIgAzYCAAsgASADNgIAC0EBIQILIAILkAMBBX9BACEDAkAgAEUNACABRQ0AIAJFDQBBACEDQShBACgCvOGAgAARgYCAgAAAIgRFDQAgBEIANwMAIARBIGoiBUIANwMAIARBGGoiA0IANwMAIARBEGoiBkIANwMAIARBCGoiB0IANwMAIAUgAkEgaikDADcDACAEIAIpAwA3AwAgByACQQhqKQMANwMAIAYgAkEQaikDADcDACADIAJBGGopAwA3AwBBACEDIAVBADYCACAEQgA3AwAgBCAEKAIMQYACcjYCDCAEIABGDQBBACEDIAEQ9YCAgABBAWoiAkEAKAK84YCAABGBgICAAAAiBUUNACAFIAEgAhDwgICAACECIAQoAgwiA0H/e3EhAQJAIANBgARxDQAgBCgCICIDRQ0AIANBACgCwOGAgAARgICAgAAACyAEIAE2AgwgBCACNgIgAkAgACgCCCICDQAgACAENgIIIARBADYCACAEIAQ2AgRBAQ8LQQEhAyACKAIEIgBFDQAgBCAANgIEIAAgBDYCACACIAQ2AgQLIAMLrgEBAn8CQEEoQQAoArzhgIAAEYGAgIAAACIBRQ0AIAFBCGpCADcDACABQgA3AwAgAUEgakIANwMAIAFBEGpCADcDACABQRhqIAA5AwAgAUEINgIMQf////8HIQICQCAARAAAwP///99BZg0AQYCAgIB4IQIgAEQAAAAAAADgwWUNAAJAIACZRAAAAAAAAOBBY0UNACAAqiECDAELQYCAgIB4IQILIAEgAjYCFAsgAQtXAQF/AkBBKEEAKAK84YCAABGBgICAAAAiAEUNACAAQQhqQgA3AwAgAEIANwMAIABBIGpCADcDACAAQRhqQgA3AwAgAEEQakIANwMAIABBwAA2AgwLIAALVgEBfwJAQShBACgCvOGAgAARgYCAgAAAIgBFDQAgAEEIakIANwMAIABCADcDACAAQSBqQgA3AwAgAEEY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EIqAgIAAQf//A3ELCwAgABCLgICAAAALBAAAAAtOAAJAIAANAD8AQRB0DwsCQCAAQf//A3ENACAAQX9MDQACQCAAQRB2QAAiAEF/Rw0AQQBBMDYCvOeAgABBfw8LIABBEHQPCxC6gICAAAALEwAgAEEgciAAIABBv39qQRpJGwsCAAsOABC9gICAABDLgICAAAs7AQF/I4CAgIAAQRBrIgIkgICAgAAgAiABNgIMQcjhgIAAIAAgARDWgICAACEBIAJBEGokgICAgAAgAQs3AQF/I4CAgIAAQRBrIgMkgICAgAAgAyACNgIMIAAgASACEN2AgIAAIQIgA0EQaiSAgICAACACCzcBAX8jgICAgABBEGsiAySAgICAACADIAI2AgwgACABIAIQ7ICAgAAhAiADQRBqJICAgIAAIAILIQACQCAAELWAgIAAIgANAEEADwtBACAANgK854CAAEF/Cw0AIAAoAjgQwoCAgAALcQECfyOAgICAAEEQayIDJICAgIAAQX8hBAJAAkAgAkF/Sg0AQQBBHDYCvOeAgAAMAQsCQCAAIAEgAiADQQxqELiAgIAAIgJFDQBBACACNgK854CAAEF/IQQMAQsgAygCDCEECyADQRBqJICAgIAAIAQLuwIBB38jgICAgABBEGsiAySAgICAACADIAI2AgwgAyABNgIIIAMgACgCGCIBNgIAIAMgACgCFCABayIBNgIEQQIhBAJAAkAgASACaiIFIAAoAjggA0ECEMSAgIAAIgFGDQAgAyEGA0ACQCABQX9KDQBBACEBIABBADYCGCAAQgA3AxAgACAAKAIAQSByNgIAIARBAkYNAyACIAYoAgRrIQEMAwsgBiABIAYoAgQiB0siCEEDdGoiCSAJKAIAIAEgB0EAIAgbayIHajYCACAGQQxBBCAIG2oiBiAGKAIAIAdrNgIAIAkhBiAFIAFrIgUgACgCOCAJIAQgCGsiBBDEgICAACIBRw0ACwsgACAAKAIoIgE2AhggACABNgIUIAAgASAAKAIsajYCECACIQELIANBEGokgICAgAAgAQtmAQJ/I4CAgIAAQSBrIgEkgICAgAACQAJAIAAgAUEIahC2gICAACIADQBBOyEAIAEtAAhBAkcNACABLQAQQSRxDQBBASECDAELQQAhAkEAIAA2ArzngIAACyABQSBqJICAgIAAIAIL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gIAARxsLjAMBA38jgICAgABB0AFrIgMkgICAgAAgAyACNgLMASADQaABakEgakIANwMAIANBuAFqQgA3AwAgA0GwAWpCADcDACADQgA3A6gBIANCADcDoAEgAyACNgLIAQJAAkBBACABIANByAFqIANB0ABqIANBoAFqENeAgIAAQQBODQBBfyEADAELIAAoAgAhBAJAIAAoAjxBAEoNACAAIARBX3E2AgALAkACQAJAAkAgACgCLA0AIABB0AA2AiwgAEEANgIYIABCADcDECAAKAIoIQUgACADNgIoDAELQQAhBSAAKAIQDQELQX8hAiAAEMyAgIAADQELIAAgASADQcgBaiADQdAAaiADQaABahDXgICAACECCyAEQSBxIQECQCAFRQ0AIABBAEEAIAAoAiARg4CAgAAAGiAAQQA2AiwgACAFNgIoIABBADYCGCAAKAIUIQUgAEIANwMQIAJBfyAFGyECCyAAIAAoAgAiBSABcjYCAEF/IAIgBUEgcRshAAsgA0HQAWokgICAgAAgAAvsRgUbfwJ+AXwIfwF8I4CAgIAAQfAGayIFJICAgIAAIAVBxABqQQxqIQZBACAFQfAAamshByAFQexgaiEIIAVBN2ohCSAFQdAAakF+cyEKIAVBxABqQQtqIQsgBUHQAGpBCHIhDCAFQdAAakEJciENQXYgBUHEAGprIQ4gBUHEAGpBCmohDyAFQThqIRBBACERQQAhEkEAIRMCQAJAAkADQCABIRQgEyASQf////8Hc0oNASATIBJqIRICQAJAAkACQAJAAkACQAJAAkAgFC0AACITRQ0AIBQhAQNAAkACQAJAIBNB/wFxIhNFDQAgE0ElRw0CIAEhFSABIRMDQAJAIBMtAAFBJUYNACATIQEMAwsgFUEBaiEVIBMtAAIhFiATQQJqIgEhEyAWQSVGDQAMAgsLIAEhFQsgFSAUayITIBJB/////wdzIhVKDQwCQCAARQ0AIAAtAABBIHENACAUIBMgABDNgICAABoLIBMNCyABQQFqIRNBfyEXAkAgASwAASIYQVBqIhZBCUsNACABLQACQSRHDQAgAUEDaiETIAEsAAMhGEEBIREgFiEXC0EAIRkCQCAYQWBqIgFBH0sNAEEBIAF0IgFBidEEcUUNACATQQFqIRZBACEZA0AgASAZciEZIBYiEywAACIYQWBq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CGo2AgAgACABKQMANwMADwsgAiACKAIAIgFBBGo2AgAgACABNAIANwMADwsgAiACKAIAIgFBBGo2AgAgACABNQIANwMADwsgAiACKAIAQQdqQXhxIgFBCGo2AgAgACABKwMAOQMADwsQ2oCAgAAACyACIAIoAgAiAUEEajYCACAAIAEoAgA2AgALC54BAQF/I4CAgIAAQYACayIFJICAgIAAAkAgAiADTA0AIARBgMAEcQ0AIAUgASACIANrIgNBgAIgA0GAAkkiBBsQ8YCAgAAhAgJAIAQNAANAAkAgAC0AAEEgcQ0AIAJBgAIgABDNgICAABoLIANBgH5qIgNB/wFLDQALCyAALQAAQSBxDQAgAiADIAAQzYCAgAAaCyAFQYACaiSAgICAAAscAEH2jICAAEHA4oCAABDVgICAABoQuoCAgAAAC7IBAQN/I4CAgIAAQYABayIEJICAgIAAIAQgACAEQf4AaiABGyIFNgJwQX8hACAEQQAgAUF/aiIGIAYgAUsbNgJ0IARBAEHwABDxgICAACIEQX82AkAgBEGIgICAADYCICAEIARB8ABqNgJEIAQgBEH/AGo2AigCQAJAIAFBf0oNAEEAQT02ArzngIAADAELIAVBADoAACAEIAIgAxDWgICAACEACyAEQYABaiSAgICAACAAC7cBAQR/AkAgACgCRCIDKAIEIgQgACgCFCAAKAIYIgVrIgYgBCAGSRsiBkUNACADKAIAIAUgBhDwgICAABogAyADKAIAIAZqNgIAIAMgAygCBCAGayIENgIECyADKAIAIQYCQCAEIAIgBCACSRsiBEUNACAGIAEgBBDwgICAABogAyADKAIAIARqIgY2AgAgAyADKAIEIARrNgIECyAGQQA6AAAgACAAKAIoIgM2AhggACADNgIUIAILFAAgAEH/////ByABIAIQ24CAgAALhQEBAn8gACAAKAI8IgFBf2ogAXI2AjwCQCAAKAIUIAAoAhhGDQAgAEEAQQAgACgCIBGDgICAAAAaCyAAQQA2AhggAEIANwMQAkAgACgCACIBQQRxRQ0AIAAgAUEgcjYCAEF/DwsgACAAKAIoIAAoAixqIgI2AgggACACNgIEIAFBG3RBH3ULVAECfyOAgICAAEEQayIBJICAgIAAQX8hAgJAIAAQ3oCAgAANACAAIAFBD2pBASAAKAIc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IGluIENNV2FsbGV0AGZvcm1hdAByZXF1ZXN0cwBjbGFpbV9zZXRzAHByb3ZpZGVycwBjbGFpbXMAY3JlZGVudGlhbHMAcGF0aHMAdmN0X3ZhbHVlcwBtYXRjaGVkX2NsYWltX25hbWVzAGNyZWRlbnRpYWxfaWRzAG9mZmVyAG9wZW5pZDR2cABpY29uAG5hbgBwcm90b2NvbABudWxsAGxlbmd0aABwYXRoACVsZwAlMS4xN2cAJTEuMTVnAGluZgB0cnVlAGRvY3R5cGVfdmFsdWUAZmFsc2UAbWVyY2hhbnRfbmFtZQBzdWJ0aXRsZQBkY3FsX2NyZWRfaWQAbWF0Y2hlZAAlZABtc29fbWRvYwBtZXRhAHRyYW5zYWN0aW9uX2RhdGEATkFOAElORgBJU1NVQU5DRQAuAChudWxsKQAiIgB0cmFuc2FjdGlvbiBjcmVkIGlkcyAlcwoAUmVxdWVzdCBKU09OICVzCgBDcmVkcyBKU09OICVzCgBpY29uX3N0YXJ0IGludCAlZCwgZG91YmxlICVmCgBDcmVkcyBKU09OIG9mZnNldCAlZAoAY29tcGFyaW5nIGNyZWQgaWQgJXMgd2l0aCB0cmFuc2FjdGlvbiBjcmVkIGlkICVzLgoAU3VwcG9ydCBmb3IgZm9ybWF0dGluZyBsb25nIGRvdWJsZSB2YWx1ZXMgaXMgY3VycmVudGx5IGRpc2FibGVkLgpUbyBlbmFibGUgaXQsIGFkZCAtbGMtcHJpbnRzY2FuLWxvbmctZG91YmxlIHRvIHRoZSBsaW5rIGNvbW1hbmQuCgBTdWNjZXNzAElsbGVnYWwgYnl0ZSBzZXF1ZW5jZQBEb21haW4gZXJyb3IAUmVzdWx0IG5vdCByZXByZXNlbnRhYmxlAE5vdCBhIHR0eQBQZXJtaXNzaW9uIGRlbmllZABPcGVyYXRpb24gbm90IHBlcm1pdHRlZABObyBzdWNoIGZpbGUgb3IgZGlyZWN0b3J5AE5vIHN1Y2ggcHJvY2VzcwBGaWxlIGV4aXN0cwBWYWx1ZSB0b28gbGFyZ2UgZm9yIGRhdGEgdHlwZQBObyBzcGFjZSBsZWZ0IG9uIGRldmljZQBPdXQgb2YgbWVtb3J5AFJlc291cmNlIGJ1c3kASW50ZXJydXB0ZWQgc3lzdGVtIGNhbGwAUmVzb3VyY2UgdGVtcG9yYXJpbHkgdW5hdmFpbGFibGUASW52YWxpZCBzZWVrAENyb3NzLWRldmljZSBsaW5rAFJlYWQtb25seSBmaWxlIHN5c3RlbQBEaXJlY3Rvcnkgbm90IGVtcHR5AENvbm5lY3Rpb24gcmVzZXQgYnkgcGVlcgBPcGVyYXRpb24gdGltZWQgb3V0AENvbm5lY3Rpb24gcmVmdXNlZABIb3N0IGlzIHVucmVhY2hhYmxlAEFkZHJlc3MgaW4gdXNlAEJyb2tlbiBwaXBlAEkvTyBlcnJvcgBObyBzdWNoIGRldmljZSBvciBhZGRyZXNzAE5vIHN1Y2ggZGV2aWNlAE5vdCBhIGRpcmVjdG9yeQBJcyBhIGRpcmVjdG9yeQBUZXh0IGZpbGUgYnVzeQBFeGVjIGZvcm1hdCBlcnJvcgBJbnZhbGlkIGFyZ3VtZW50AEFyZ3VtZW50IGxpc3QgdG9vIGxvbmcAU3ltYm9saWMgbGluayBsb29wAEZpbGVuYW1lIHRvbyBsb25nAFRvbyBtYW55IG9wZW4gZmlsZXMgaW4gc3lzdGVtAE5vIGZpbGUgZGVzY3JpcHRvcnMgYXZhaWxhYmxlAEJhZCBmaWxlIGRlc2NyaXB0b3IATm8gY2hpbGQgcHJvY2VzcwBCYWQgYWRkcmVzcwBGaWxlIHRvbyBsYXJnZQBUb28gbWFueSBsaW5rcwBObyBsb2NrcyBhdmFpbGFibGUAUmVzb3VyY2UgZGVhZGxvY2sgd291bGQgb2NjdXIAU3RhdGUgbm90IHJlY292ZXJhYmxlAFByZXZpb3VzIG93bmVyIGRpZWQAT3BlcmF0aW9uIGNhbmNlbGVkAEZ1bmN0aW9uIG5vdCBpbXBsZW1lbnRlZABObyBtZXNzYWdlIG9mIGRlc2lyZWQgdHlwZQBJZGVudGlmaWVyIHJlbW92ZWQATGluayBoYXMgYmVlbiBzZXZlcmVkAFByb3RvY29sIGVycm9yAEJhZCBtZXNzYWdlAE5vdCBhIHNvY2tldABEZXN0aW5hdGlvbiBhZGRyZXNzIHJlcXVpcmVkAE1lc3NhZ2UgdG9vIGxhcmdlAFByb3RvY29sIHdyb25nIHR5cGUgZm9yIHNvY2tldABQcm90b2NvbCBub3QgYXZhaWxhYmxlAFByb3RvY29sIG5vdCBzdXBwb3J0ZWQATm90IHN1cHBvcnRlZABBZGRyZXNzIGZhbWlseSBub3Qgc3VwcG9ydGVkIGJ5IHByb3RvY29sAEFkZHJlc3Mgbm90IGF2YWlsYWJsZQBOZXR3b3JrIGlzIGRvd24ATmV0d29yayB1bnJlYWNoYWJsZQBDb25uZWN0aW9uIHJlc2V0IGJ5IG5ldHdvcmsAQ29ubmVjdGlvbiBhYm9ydGVkAE5vIGJ1ZmZlciBzcGFjZSBhdmFpbGFibGUAU29ja2V0IGlzIGNvbm5lY3RlZABTb2NrZXQgbm90IGNvbm5lY3RlZABPcGVyYXRpb24gYWxyZWFkeSBpbiBwcm9ncmVzcwBPcGVyYXRpb24gaW4gcHJvZ3Jlc3MAU3RhbGUgZmlsZSBoYW5kbGUAUXVvdGEgZXhjZWVkZWQATXVsdGlob3AgYXR0ZW1wdGVkAENhcGFiaWxpdGllcyBpbnN1ZmZpY2llbnQAAAAAAAAAAAAAdQJOANYB4gS5BBgBjgXtAhYE8gCXAwEDOAWvAYIBTwMvBB4A1AWiABIDHgPCAd4DCACsBQABZALxAWUFNAKMAs8CLQNMBOMFnwL4BBwFCAWxAksFFQJ4AFICPAPxA+QAwwN9BMwAqgN5BSQCbgFtAyIEqwREAPsBrgCDA2AA5QEHBJQEXgQrAFgBOQGSAMIFmwFDAkYB9gUAAAAAAAAZAAoAGRkZAAAAAAUAAAAAAAAJAAAAAAsAAAAAAAAAABkAEQoZGRkDCgcAARsJCxgAAAkGCwAACwAGGQAAABkZGQAAAAAAAAAAAAAAAAAAAAAOAAAAAAAAAAAZAAoNGRkZAA0AAAIACQ4AAAAJAA4AAA4AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAEwAAAAATAAAAAAkMAAAAAAAMAAAMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAA8AAAAEDwAAAAAJEAAAAAAAEAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAASAAAAAAAAAAAAAAARAAAAABEAAAAACRIAAAAAABIAABIAABoAAAAaGhoAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGgAAABoaGgAAAAAAAAkAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABQAAAAAAAAAAAAAABcAAAAAFwAAAAAJFAAAAAAAFAAAFAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWAAAAAAAAAAAAAAAVAAAAABUAAAAACRYAAAAAABYAABYAADAxMjM0NTY3ODlBQkNERUb/////////////////////////////////////////////////////////////////AAECAwQFBgcICf////////8KCwwNDg8QERITFBUWFxgZGhscHR4fICEiI////////woLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIj/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////wABAgQHAwYFAAAAAAAAAAoAAABkAAAA6AMAABAnAACghgEAQEIPAICWmAAA4fUFAgAAwAMAAMAEAADABQAAwAYAAMAHAADACAAAwAkAAMAKAADACwAAwAwAAMANAADADgAAwA8AAMAQAADAEQAAwBIAAMATAADAFAAAwBUAAMAWAADAFwAAwBgAAMAZAADAGgAAwBsAAMAcAADAHQAAwB4AAMAfAADAAAAAswEAAMMCAADDAwAAwwQAAMMFAADDBgAAwwcAAMMIAADDCQAAwwoAAMMLAADDDAAAww0AANMOAADDDwAAwwAADLsBAAzDAgAMwwMADMMEAAzbAEGQIwukQIlQTkcNChoKAAAADUlIRFIAAABFAAAARwgGAAAAUUWKIgAAAAlwSFlzAAALEwAACxMBAJqcGAAAAAFzUkdCAK7OHOkAAAAEZ0FNQQAAsY8L/GEFAAAev0lEQVR4Ae17a7BkV3Xe2nufc/p1H3PvPKQZSaNBT0s24iFBABvVgHnIYAsqGCdEUVWEUQpjl01sbEOl4iIp45hgBAEnOGJiuQzmkYQqnGAQBGFKVlQCIlMSQmKEpNEwM3dm7txn336ec/be/tba+3T3HQ2ai62R/3BGre57uvucvdde61vf+tZuoh8fPz7+UY99+6n+iS/94n68NPQMHB++44YX3Xbba+fpWTg0naPjvP58duml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VRdYC1kLVwsAAA8FAAIYMRMAABA0AAIXMRMAABE0ABwNMRMAABI0ABwPMRMAAAAA5AILLmRlYnVnX2xpbmVUAQAABADCAAAAAQEB+w4NAAEBAQEAAAABAAABd2FzaXNkazovL3YyMC4wL2J1aWxkL2luc3RhbGwvb3B0L3dhc2ktc2RrL3NoYXJlL3dhc2ktc3lzcm9vdC9pbmNsdWRlL2JpdHMAd2FzaXNkazovL3YyMC4wL3NyYy9sbHZtLXByb2plY3QvY29tcGlsZXItcnQvbGliL2J1aWx0aW5zAABhbGx0eXBlcy5oAAEAAGludF90eXBlcy5oAAIAAG11bHRpMy5jAAIAAAAEAwAFAgHWAAADJwEFLApDBRgGdAUMBgNyWAULVm8FJigFDAY8BSMGA3WQBRAGrAUeIANpPAUWBgMYSgUfIgUFBlgDZiAFEAYDHEoFIgMTIAYDUSAFDwYDHYIFHyIFBQZYA2EgBREGAyFKBQwDDiAFAyEGA1A8BR8GAyBmBQsDeZAnBQMDECACBAABAQCLAgouZGVidWdfc3RyeQB4AGxvdwB3YXNpc2RrOi8vdjIwLjAvYnVpbGQvY29tcGlsZXItcnQAZHVfaW50AHRpX2ludABkaV9pbnQAdWludDY0X3QAdHdvcmRzAHIAYWxsAGxvd2VyX21hc2sAaGlnaAB1bnNpZ25lZCBsb25nIGxvbmcAd2FzaXNkazovL3YyMC4wL3NyYy9sbHZtLXByb2plY3QvY29tcGlsZXItcnQvbGliL2J1aWx0aW5zL211bHRpMy5jAGIAYQBfX2ludDEyOABfX211bHRpMwBfX211bGRkaTMAYml0c19pbl9kd29yZF8yAGNsYW5nIHZlcnNpb24gMTYuMC4wAACXDgRuYW1lAegNeQAOR2V0UmVxdWVzdFNpemUBEEdldFJlcXVlc3RCdWZmZXICEkdldENyZWRlbnRpYWxzU2l6ZQMVUmVhZENyZWRlbnRpYWxzQnVmZmVyBA9BZGRQYXltZW50RW50cnkFEEFkZFN0cmluZ0lkRW50cnkGGEFkZEZpZWxkRm9yU3RyaW5nSWRFbnRyeQcqX19pbXBvcnRlZF93YXNpX3NuYXBzaG90X3ByZXZpZXcxX2ZkX2Nsb3NlCC9fX2ltcG9ydGVkX3dhc2lfc25hcHNob3RfcHJldmlldzFfZmRfZmRzdGF0X2dldAkpX19pbXBvcnRlZF93YXNpX3NuYXBzaG90X3ByZXZpZXcxX2ZkX3NlZWsKKl9faW1wb3J0ZWRfd2FzaV9zbmFwc2hvdF9wcmV2aWV3MV9mZF93cml0ZQsrX19pbXBvcnRlZF93YXNpX3NuYXBzaG90X3ByZXZpZXcxX3Byb2NfZXhpdAwRX193YXNtX2NhbGxfY3RvcnMNBl9zdGFydA4PX19vcmlnaW5hbF9tYWluDwxBZGRBbGxDbGFpbXMQD01hdGNoQ3JlZGVudGlhbBEKZGNxbF9xdWVyeRIUY0pTT05fR2V0U3RyaW5nVmFsdWUTDmNKU09OX0lzU3RyaW5nFBRjSlNPTl9HZXROdW1iZXJWYWx1ZRUMY0pTT05fRGVsZXRlFhljSlNPTl9QYXJzZVdpdGhMZW5ndGhPcHRzFwtwYXJzZV92YWx1ZRgMcGFyc2Vfc3RyaW5nGRZidWZmZXJfc2tpcF93aGl0ZXNwYWNlGgtjSlNPTl9QYXJzZRsLY0pTT05fUHJpbnQcBXByaW50HQtwcmludF92YWx1ZR4WY0pTT05fUHJpbnRVbmZvcm1hdHRlZB8GZW5zdXJlIBBwcmludF9zdHJpbmdfcHRyIRJjSlNPTl9HZXRBcnJheVNpemUiEmNKU09OX0dldEFycmF5SXRlbSMTY0pTT05fR2V0T2JqZWN0SXRlbSQPZ2V0X29iamVjdF9pdGVtJRNjSlNPTl9IYXNPYmplY3RJdGVtJh1jSlNPTl9BZGRJdGVtUmVmZXJlbmNlVG9BcnJheSceY0pTT05fQWRkSXRlbVJlZmVyZW5jZVRvT2JqZWN0KBJjSlNPTl9DcmVhdGVOdW1iZXIpEmNKU09OX0NyZWF0ZU9iamVjdCoRY0pTT05fQ3JlYXRlQXJyYXkrDmNKU09OX0lzT2JqZWN0LA1jSlNPTl9Db21wYXJlLQpwYXJzZV9oZXg0LgxCNjREZWNvZGVVUkwvBm1hbGxvYzAIZGxtYWxsb2MxBGZyZWUyBmRsZnJlZTMHcmVhbGxvYzQNZGlzcG9zZV9jaHVuazUPX193YXNpX2ZkX2Nsb3NlNhRfX3dhc2lfZmRfZmRzdGF0X2dldDcOX193YXNpX2ZkX3NlZWs4D19fd2FzaV9mZF93cml0ZTkQX193YXNpX3Byb2NfZXhpdDoFYWJvcnQ7BHNicms8B3RvbG93ZXI9BWR1bW15PhFfX3dhc21fY2FsbF9kdG9ycz8GcHJpbnRmQAdzcHJpbnRmQQZzc2NhbmZCBWNsb3NlQw1fX3N0ZGlvX2Nsb3NlRAZ3cml0ZXZFDV9fc3RkaW9fd3JpdGVGCF9faXNhdHR5Rw5fX3N0ZG91dF93cml0ZUgHX19sc2Vla0kMX19zdGRpb19zZWVrSgpfX29mbF9sb2NrSwxfX3N0ZGlvX2V4aXRMCV9fdG93cml0ZU0JX19md3JpdGV4TgZmd3JpdGVPBWR1bW15UAlfX2xjdHJhbnNRCHN0cmVycm9yUgd3Y3J0b21iUwZ3Y3RvbWJUBWZyZXhwVQVmcHV0c1YIdmZwcmludGZXC3ByaW50Zl9jb3JlWAdwb3BfYXJnWQNwYWRaGWxvbmdfZG91YmxlX25vdF9zdXBwb3J0ZWRbCXZzbnByaW50ZlwIc25fd3JpdGVdCHZzcHJpbnRmXghfX3RvcmVhZF8HX191Zmxvd2AHX19zaGxpbWEIX19zaGdldGNiCV9faW50c2NhbmMGc2NhbGJuZARmbW9kZQtfX2Zsb2F0c2NhbmYIaGV4ZmxvYXRnB3NjYW5leHBoB21icnRvd2NpB21ic2luaXRqB3Zmc2NhbmZrGWxvbmdfZG91YmxlX25vdF9zdXBwb3J0ZWRsB3Zzc2NhbmZtC3N0cmluZ19yZWFkbgZzdHJ0b2RvBm1lbWNocnAGbWVtY3B5cQZtZW1zZXRyBnN0cmNocnMLX19zdHJjaHJudWx0BnN0cmNtcHUGc3RybGVudgdzdHJuY21wdwdzdHJubGVueAhfX211bHRpMwcSAQAPX19zdGFja19wb2ludGVyCRECAAcucm9kYXRhAQUuZGF0YQA1CXByb2R1Y2VycwIIbGFuZ3VhZ2UBA0MxMQAMcHJvY2Vzc2VkLWJ5AQVjbGFuZwYxNi4wLjAAOQ90YXJnZXRfZmVhdHVyZXMDKwtidWxrLW1lbW9yeSsPbXV0YWJsZS1nbG9iYWxzKwhzaWduLWV4dA==".decodeBase64()?.toByteArray() }
    }
}