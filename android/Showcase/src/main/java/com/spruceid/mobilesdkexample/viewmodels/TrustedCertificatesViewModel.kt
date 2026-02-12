package com.spruceid.mobilesdkexample.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spruceid.mobilesdkexample.db.TrustedCertificates
import com.spruceid.mobilesdkexample.db.TrustedCertificatesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FileData(
    val name: String,
    val content: String
)

val DEFAULT_TRUST_ANCHOR_IACA_SPRUCEID_HACI_PROD = FileData(
    name = "spruceid-haci-prod-certificate.pem",
    content = """
-----BEGIN CERTIFICATE-----
MIICJDCCAcqgAwIBAgIJAOE5hRXm8PnwMAoGCCqGSM49BAMCMEcxETAPBgNVBAoM
CFNwcnVjZUlEMQswCQYDVQQIDAJOWTELMAkGA1UEBhMCVVMxGDAWBgNVBAMMD1Nw
cnVjZUlEIG1ETCBDQTAeFw0yNTA1MjAxMDQyMDNaFw0zMDA1MTkxMDQyMDNaMEcx
ETAPBgNVBAoMCFNwcnVjZUlEMQswCQYDVQQIDAJOWTELMAkGA1UEBhMCVVMxGDAW
BgNVBAMMD1NwcnVjZUlEIG1ETCBDQTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IA
BCl9YgK2qfIu4zO1br3YKeys5N7gznqjtW27w8brS4ejqeVYejdsoonT3GMSiJgs
CjUgISZGZGTLD5uj8Qq5xImjgZ4wgZswHQYDVR0OBBYEFFEvLAdYAIUGN5BJiBOz
VFFUphVhMB8GA1UdEgQYMBaGFGh0dHBzOi8vc3BydWNlaWQuY29tMDUGA1UdHwQu
MCwwKqAooCaGJGh0dHBzOi8vY3JsLmhhY2kuc3BydWNlaWQueHl6L2lhY2EtMDAO
BgNVHQ8BAf8EBAMCAQYwEgYDVR0TAQH/BAgwBgEB/wIBADAKBggqhkjOPQQDAgNI
ADBFAiA3KpzFogVdNUCV+NTyBu+pEBDOmRFa735AFJMAOutzbAIhAJaRGpHvii65
3q8/uns9PMOOf6rqN2R2hB7nUK5DEcVW
-----END CERTIFICATE-----"""
)

val DEFAULT_TRUST_ANCHOR_IACA_SPRUCEID_HACI_STAGING = FileData(
    name = "spruceid-haci-staging-certificate.pem",
    content = """
-----BEGIN CERTIFICATE-----
MIICPjCCAeWgAwIBAgIIWVEstjwOoOAwCgYIKoZIzj0EAwIwUTERMA8GA1UECgwI
U3BydWNlSUQxCzAJBgNVBAgMAk5ZMQswCQYDVQQGEwJVUzEiMCAGA1UEAwwZU3By
dWNlSUQgbURMIENBIChTdGFnaW5nKTAeFw0yNTA0MDQxMTAxNDZaFw0zMDA0MDMx
MTAxNDZaMFExETAPBgNVBAoMCFNwcnVjZUlEMQswCQYDVQQIDAJOWTELMAkGA1UE
BhMCVVMxIjAgBgNVBAMMGVNwcnVjZUlEIG1ETCBDQSAoU3RhZ2luZykwWTATBgcq
hkjOPQIBBggqhkjOPQMBBwNCAASJt4BBmA/JSHiIrWI00MMIgi8cJb0n67hpZjS6
NipkOe8UIQtHzH+gyN8EARfwFD14X/vmy0wsWlQx3UlFL9wDo4GmMIGjMB0GA1Ud
DgQWBBSMXLgb59dX6Vway9tD6N+5kGDktjAfBgNVHRIEGDAWhhRodHRwczovL3Nw
cnVjZWlkLmNvbTA9BgNVHR8ENjA0MDKgMKAuhixodHRwczovL2NybC5oYWNpLnN0
YWdpbmcuc3BydWNlaWQueHl6L2lhY2EtMDAOBgNVHQ8BAf8EBAMCAQYwEgYDVR0T
AQH/BAgwBgEB/wIBADAKBggqhkjOPQQDAgNHADBEAiAiOqzt3ToFMtKdfw/ymsLm
YulhRGtKCN95+3sKsGRxBwIgYU/+vwMuO6hY6KZYXb5FUS51xV6PSGUopGiBuTtM
t7U=
-----END CERTIFICATE-----"""
)

val DEFAULT_TRUST_ANCHOR_CERTIFICATES = listOf(
    // rust/tests/res/mdl/iaca-certificate.pem
    FileData(
        name = "spruceid-iaca-certificate.pem",
        content = """
-----BEGIN CERTIFICATE-----
MIICHzCCAcWgAwIBAgIUPH2x3sBmPlTqGNOcR8LxMuuFy70wCgYIKoZIzj0EAwIw
SjELMAkGA1UEBhMCVVMxCzAJBgNVBAgMAk5ZMREwDwYDVQQKDAhTcHJ1Y2VJRDEb
MBkGA1UEAwwSU3BydWNlSUQgVGVzdCBJQUNBMB4XDTI2MDIxMjE0NTkxN1oXDTMx
MDIxMjE0NTkxN1owSjELMAkGA1UEBhMCVVMxCzAJBgNVBAgMAk5ZMREwDwYDVQQK
DAhTcHJ1Y2VJRDEbMBkGA1UEAwwSU3BydWNlSUQgVGVzdCBJQUNBMFkwEwYHKoZI
zj0CAQYIKoZIzj0DAQcDQgAEmAZFZftRxWrlIuf1ZY4DW7QfAfTu36RumpvYZnKV
FUNmyrNxGrtQlp2Tbit+9lUzjBjF9R8nvdidmAHOMg3zg6OBiDCBhTAdBgNVHQ4E
FgQUJpZofWBt6ci5UVfOl8E9odYu8lcwDgYDVR0PAQH/BAQDAgEGMBIGA1UdEwEB
/wQIMAYBAf8CAQAwGwYDVR0SBBQwEoEQdGVzdEBleGFtcGxlLmNvbTAjBgNVHR8E
HDAaMBigFqAUhhJodHRwOi8vZXhhbXBsZS5jb20wCgYIKoZIzj0EAwIDSAAwRQIg
QhPEuy4Iq9uuE+Qnf6FHcUo9kUQPj3enYprmpQoVqwUCIQC9QFOWun+UB5JdR+xI
xQxcFGlOMkuKmUGnMq/YPh1gnA==
-----END CERTIFICATE-----"""
    ),
    FileData(
        name = "spruceid-iaca-certificate-old.pem",
        content = """
-----BEGIN CERTIFICATE-----
MIIB0zCCAXqgAwIBAgIJANVHM3D1VFaxMAoGCCqGSM49BAMCMCoxCzAJBgNVBAYT
AlVTMRswGQYDVQQDDBJTcHJ1Y2VJRCBUZXN0IElBQ0EwHhcNMjUwMTA2MTA0MDUy
WhcNMzAwMTA1MTA0MDUyWjAqMQswCQYDVQQGEwJVUzEbMBkGA1UEAwwSU3BydWNl
SUQgVGVzdCBJQUNBMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmAZFZftRxWrl
Iuf1ZY4DW7QfAfTu36RumpvYZnKVFUNmyrNxGrtQlp2Tbit+9lUzjBjF9R8nvdid
mAHOMg3zg6OBiDCBhTAdBgNVHQ4EFgQUJpZofWBt6ci5UVfOl8E9odYu8lcwDgYD
VR0PAQH/BAQDAgEGMBIGA1UdEwEB/wQIMAYBAf8CAQAwGwYDVR0SBBQwEoEQdGVz
dEBleGFtcGxlLmNvbTAjBgNVHR8EHDAaMBigFqAUhhJodHRwOi8vZXhhbXBsZS5j
b20wCgYIKoZIzj0EAwIDRwAwRAIgJFSMgE64Oiq7wdnWA3vuEuKsG0xhqW32HdjM
LNiJpAMCIG82C+Kx875VNhx4hwfqReTRuFvZOTmFDNgKN0O/1+lI
-----END CERTIFICATE-----"""
    ),
    // rust/tests/res/mdl/utrecht-certificate.pem
    FileData(
        name = "spruceid-utrecht-certificate.pem",
        content = """
-----BEGIN CERTIFICATE-----
MIICWTCCAf+gAwIBAgIULZgAnZswdEysOLq+G0uNW0svhYIwCgYIKoZIzj0EAwIw
VjELMAkGA1UEBhMCVVMxCzAJBgNVBAgMAk5ZMREwDwYDVQQKDAhTcHJ1Y2VJRDEn
MCUGA1UEAwweU3BydWNlSUQgVGVzdCBDZXJ0aWZpY2F0ZSBSb290MB4XDTI1MDIx
MjEwMjU0MFoXDTI2MDIxMjEwMjU0MFowVjELMAkGA1UEBhMCVVMxCzAJBgNVBAgM
Ak5ZMREwDwYDVQQKDAhTcHJ1Y2VJRDEnMCUGA1UEAwweU3BydWNlSUQgVGVzdCBD
ZXJ0aWZpY2F0ZSBSb290MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEwWfpUAMW
HkOzSctR8szsMNLeOCMyjk9HAkAYZ0HiHsBMNyrOcTxScBhEiHj+trE5d5fVq36o
cvrVkt2X0yy/N6OBqjCBpzAdBgNVHQ4EFgQU+TKkY3MApIowvNzakcIr6P4ZQDQw
EgYDVR0TAQH/BAgwBgEB/wIBADA+BgNVHR8ENzA1MDOgMaAvhi1odHRwczovL2lu
dGVyb3BldmVudC5zcHJ1Y2VpZC5jb20vaW50ZXJvcC5jcmwwDgYDVR0PAQH/BAQD
AgEGMCIGA1UdEgQbMBmBF2lzb2ludGVyb3BAc3BydWNlaWQuY29tMAoGCCqGSM49
BAMCA0gAMEUCIAJrzCSS/VIjf7uTq+Kt6+97VUNSvaAAwdP6fscIvp4RAiEA0dOP
Ld7ivuH83lLHDuNpb4NShfdBG57jNEIPNUs9OEg=
-----END CERTIFICATE-----"""
    ),
    // IACA Spruce HACI Staging
    DEFAULT_TRUST_ANCHOR_IACA_SPRUCEID_HACI_STAGING,
    // IACA Spruce HACI Prod
    DEFAULT_TRUST_ANCHOR_IACA_SPRUCEID_HACI_PROD
)

@HiltViewModel
class TrustedCertificatesViewModel @Inject constructor(
    private val trustedCertificatesRepository: TrustedCertificatesRepository
) : ViewModel() {
    private val _trustedCertificates = MutableStateFlow(listOf<TrustedCertificates>())
    val trustedCertificates = _trustedCertificates.asStateFlow()

    init {
        viewModelScope.launch {
            _trustedCertificates.value =
                trustedCertificatesRepository.trustedCertificates
            if (_trustedCertificates.value.isEmpty()) {
                populateTrustedCertificates()
            }
        }
    }

    private suspend fun populateTrustedCertificates() {
        DEFAULT_TRUST_ANCHOR_CERTIFICATES.forEach {
            saveCertificate(
                TrustedCertificates(
                    name = it.name,
                    content = it.content
                )
            )
        }
    }

    suspend fun saveCertificate(certificate: TrustedCertificates) {
        trustedCertificatesRepository.insertCertificate(certificate)
        _trustedCertificates.value = trustedCertificatesRepository.getCertificates()
    }

    fun getCertificate(id: Long): TrustedCertificates {
        return trustedCertificatesRepository.getCertificate(id)
    }

    fun deleteAllCertificates() {
        trustedCertificatesRepository.deleteAllCertificates()
        _trustedCertificates.value = trustedCertificatesRepository.getCertificates()
    }

    fun deleteCertificate(id: Long) {
        trustedCertificatesRepository.deleteCertificate(id = id)
        _trustedCertificates.value = trustedCertificatesRepository.getCertificates()
    }
}
