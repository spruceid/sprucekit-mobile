macro_rules! algorithms {
    ($($(#[$meta:meta])* $variant:ident : $name:literal),*) => {
        #[derive(uniffi::Enum)]
        pub enum JwkAlgorithm {
            None,
            $($variant),*
        }

        impl From<ssi::jwk::Algorithm> for JwkAlgorithm {
            fn from(value: ssi::jwk::Algorithm) -> Self {
                match value {
                    ssi::jwk::Algorithm::None => Self::None,
                    $(ssi::jwk::Algorithm::$variant => Self::$variant),*
                }
            }
        }

        impl From<JwkAlgorithm> for ssi::jwk::Algorithm {
            fn from(value: JwkAlgorithm) -> Self {
                match value {
                    JwkAlgorithm::None => Self::None,
                    $(JwkAlgorithm::$variant => Self::$variant),*
                }
            }
        }
    };
}

algorithms! {
    /// HMAC using SHA-256.
    ///
    /// See: <https://www.rfc-editor.org/rfc/rfc7518.txt>
    HS256: "HS256",

    /// HMAC using SHA-384.
    ///
    /// See: <https://www.rfc-editor.org/rfc/rfc7518.txt>
    HS384: "HS384",

    /// HMAC using SHA-512.
    ///
    /// See: <https://www.rfc-editor.org/rfc/rfc7518.txt>
    HS512: "HS512",

    /// RSASSA-PKCS1-v1_5 using SHA-256.
    ///
    /// See: <https://www.rfc-editor.org/rfc/rfc7518.txt>
    RS256: "RS256",

    /// RSASSA-PKCS1-v1_5 using SHA-384.
    ///
    /// See: <https://www.rfc-editor.org/rfc/rfc7518.txt>
    RS384: "RS384",

    /// RSASSA-PKCS1-v1_5 using SHA-512.
    ///
    /// See: <https://www.rfc-editor.org/rfc/rfc7518.txt>
    RS512: "RS512",

    /// RSASSA-PSS using SHA-256 and MGF1 with SHA-256.
    ///
    /// See: <https://www.rfc-editor.org/rfc/rfc7518.txt>
    PS256: "PS256",

    /// RSASSA-PSS using SHA-384 and MGF1 with SHA-384.
    ///
    /// See: <https://www.rfc-editor.org/rfc/rfc7518.txt>
    PS384: "PS384",

    /// RSASSA-PSS using SHA-512 and MGF1 with SHA-512.
    ///
    /// See: <https://www.rfc-editor.org/rfc/rfc7518.txt>
    PS512: "PS512",

    /// Edwards-curve Digital Signature Algorithm (EdDSA) using SHA-256.
    ///
    /// The following curves are defined for use with `EdDSA`:
    ///  - `Ed25519`
    ///  - `Ed448`
    ///
    /// See: <https://www.rfc-editor.org/rfc/rfc8037>
    EdDSA: "EdDSA",

    /// EdDSA using SHA-256 and Blake2b as pre-hash function.
    EdBlake2b: "EdBlake2b", // TODO Blake2b is supposed to replace SHA-256

    /// ECDSA using P-256 and SHA-256.
    ///
    /// See: <https://www.rfc-editor.org/rfc/rfc7518.txt>
    ES256: "ES256",

    /// ECDSA using P-384 and SHA-384.
    ///
    /// See: <https://www.rfc-editor.org/rfc/rfc7518.txt>
    ES384: "ES384",

    /// ECDSA using secp256k1 (K-256) and SHA-256.
    ///
    /// See: <https://datatracker.ietf.org/doc/html/rfc8812>
    ES256K: "ES256K",

    /// ECDSA using secp256k1 (K-256) and SHA-256 with a recovery bit.
    ///
    /// `ES256K-R` is similar to `ES256K` with the recovery bit appended, making
    /// the signature 65 bytes instead of 64. The recovery bit is used to
    /// extract the public key from the signature.
    ///
    /// See: <https://github.com/decentralized-identity/EcdsaSecp256k1RecoverySignature2020#es256k-r>
    ES256KR: "ES256K-R",

    /// ECDSA using secp256k1 (K-256) and Keccak-256.
    ///
    /// Like `ES256K` but using Keccak-256 instead of SHA-256.
    ESKeccakK: "ESKeccakK",

    /// ECDSA using secp256k1 (K-256) and Keccak-256 with a recovery bit.
    ///
    /// Like `ES256K-R` but using Keccak-256 instead of SHA-256.
    ESKeccakKR: "ESKeccakKR",

    /// ECDSA using P-256 and Blake2b.
    ESBlake2b: "ESBlake2b",

    /// ECDSA using secp256k1 (K-256) and Blake2b.
    ESBlake2bK: "ESBlake2bK",

    #[doc(hidden)]
    AleoTestnet1Signature: "AleoTestnet1Signature"
}
