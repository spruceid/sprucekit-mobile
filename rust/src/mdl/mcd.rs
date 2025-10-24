use isomdl::definitions::{helpers::ByteStr, mcd::*, CoseKey};
use ssi::claims::cose::coset::TaggedCborSerializable;
use std::sync::Arc;

use crate::crypto::{cose_key_ec2_p256_public_key, CryptoError};

#[derive(uniffi::Object)]
pub struct MobileIdCapabilityDescriptorBuilder {
    version: u64,
    app_supported_dev_features: AppSupportedDevFeatures,
    app_engagement_interface: AppEngagementInterfaces,
    app_data_transmission_interface: AppDataTransmissionInterfaces,
    app_attestation_key_bytes: Option<AppAttestationKeyBytes>,
    certification: Certifications,
    secure_area_attestation_objects: Vec<SecureAreaAttestationObject>,
}

impl Default for MobileIdCapabilityDescriptorBuilder {
    fn default() -> Self {
        Self::new()
    }
}

#[uniffi::export]
impl MobileIdCapabilityDescriptorBuilder {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self {
            version: 1,
            app_supported_dev_features: Vec::new(),
            app_engagement_interface: Vec::new(),
            app_data_transmission_interface: Vec::new(),
            app_attestation_key_bytes: None,
            certification: Vec::new(),
            secure_area_attestation_objects: Vec::new(),
        }
    }

    pub fn version(self: Arc<Self>, version: u64) -> Arc<Self> {
        Arc::new(Self {
            version,
            ..(*self).clone()
        })
    }

    pub fn add_app_supported_dev_feature(self: Arc<Self>, feature: i64) -> Arc<Self> {
        let mut features = self.app_supported_dev_features.clone();
        features.push(feature);
        Arc::new(Self {
            app_supported_dev_features: features,
            ..(*self).clone()
        })
    }

    pub fn add_app_engagement_interface(self: Arc<Self>, interface: i64) -> Arc<Self> {
        let mut interfaces = self.app_engagement_interface.clone();
        interfaces.push(interface);
        Arc::new(Self {
            app_engagement_interface: interfaces,
            ..(*self).clone()
        })
    }

    pub fn add_app_data_transmission_interface(self: Arc<Self>, interface: i64) -> Arc<Self> {
        let mut interfaces = self.app_data_transmission_interface.clone();
        interfaces.push(interface);
        Arc::new(Self {
            app_data_transmission_interface: interfaces,
            ..(*self).clone()
        })
    }

    pub fn app_attestation_key_from_coordinates(
        self: Arc<Self>,
        x: Vec<u8>,
        y: Vec<u8>,
        kid: Vec<u8>,
    ) -> Result<Arc<Self>, CryptoError> {
        let cose_key_bytes = cose_key_ec2_p256_public_key(x, y, kid)?;
        self.app_attestation_key_from_cose_key_bytes(cose_key_bytes)
    }

    pub fn app_attestation_key_from_cose_key_bytes(
        self: Arc<Self>,
        cose_key_bytes: Vec<u8>,
    ) -> Result<Arc<Self>, CryptoError> {
        Ok(Arc::new(Self {
            app_attestation_key_bytes: Some(ByteStr::from(cose_key_bytes)),
            ..(*self).clone()
        }))
    }

    pub fn add_certification_bytes(self: Arc<Self>, cert: Vec<u8>) -> Arc<Self> {
        let mut certs = self.certification.clone();
        certs.push(CertificationItem::Bytes(
            isomdl::definitions::helpers::ByteStr::from(cert),
        ));
        Arc::new(Self {
            certification: certs,
            ..(*self).clone()
        })
    }

    pub fn add_certification_text(self: Arc<Self>, cert: String) -> Arc<Self> {
        let mut certs = self.certification.clone();
        certs.push(CertificationItem::Text(cert));
        Arc::new(Self {
            certification: certs,
            ..(*self).clone()
        })
    }

    pub fn add_secure_area_attestation_object(
        self: Arc<Self>,
        sa_encoding: u8,
        sa_attestation_object_value: Arc<SaAttestationObjectValueBuilder>,
    ) -> Result<Arc<Self>, CryptoError> {
        let mut objects = self.secure_area_attestation_objects.clone();
        objects.push(SecureAreaAttestationObject {
            sa_encoding,
            sa_attestation_object_value: sa_attestation_object_value.build()?,
        });
        Ok(Arc::new(Self {
            secure_area_attestation_objects: objects,
            ..(*self).clone()
        }))
    }

    pub fn build(self: Arc<Self>) -> Result<Vec<u8>, CryptoError> {
        let mcd = MobileIdCapabilityDescriptor {
            version: self.version,
            mobile_id_application_descriptor: MobileIdApplicationDescriptor {
                app_supported_dev_features: self.app_supported_dev_features.clone(),
                app_engagement_interface: self.app_engagement_interface.clone(),
                app_data_transmission_interface: self.app_data_transmission_interface.clone(),
                app_attestation_key_bytes: self.app_attestation_key_bytes.clone(),
                certification: self.certification.clone(),
            },
            secure_area_attestation_objects: self.secure_area_attestation_objects.clone(),
        };

        mcd.to_tagged_vec()
            .map_err(|e| CryptoError::General(format!("Failed to serialize MCD: {e:?}")))
    }

    pub fn get_version(&self) -> u64 {
        self.version
    }

    pub fn get_app_supported_dev_features(&self) -> Vec<i64> {
        self.app_supported_dev_features.clone()
    }

    pub fn get_app_engagement_interfaces(&self) -> Vec<i64> {
        self.app_engagement_interface.clone()
    }

    pub fn get_app_data_transmission_interfaces(&self) -> Vec<i64> {
        self.app_data_transmission_interface.clone()
    }

    pub fn get_certifications_count(&self) -> u32 {
        self.certification.len() as u32
    }

    pub fn get_secure_area_attestation_objects_count(&self) -> u32 {
        self.secure_area_attestation_objects.len() as u32
    }
}

impl Clone for MobileIdCapabilityDescriptorBuilder {
    fn clone(&self) -> Self {
        Self {
            version: self.version,
            app_supported_dev_features: self.app_supported_dev_features.clone(),
            app_engagement_interface: self.app_engagement_interface.clone(),
            app_data_transmission_interface: self.app_data_transmission_interface.clone(),
            app_attestation_key_bytes: self.app_attestation_key_bytes.clone(),
            certification: self.certification.clone(),
            secure_area_attestation_objects: self.secure_area_attestation_objects.clone(),
        }
    }
}

#[derive(uniffi::Object)]
pub struct SaAttestationObjectValueBuilder {
    sa_index: u64,
    sa_type: Option<i64>,
    sa_supported_user_auth: Vec<i64>,
    sa_crypto_suites: SecureAreaCryptoSuites,
    sa_crypto_key_definition: SaCryptoKeyDefinitions,
    sa_interface: i64,
    sa_attestation_bytes: Option<SaAttestationKeyBytes>,
    sa_attestation_statement: Option<SaAttestationStatement>,
    sa_attestation_format: Option<i64>,
    certification: Certifications,
}

#[uniffi::export]
impl SaAttestationObjectValueBuilder {
    #[uniffi::constructor]
    pub fn new(sa_index: u64, sa_interface: i64) -> Self {
        Self {
            sa_index,
            sa_type: None,
            sa_supported_user_auth: Vec::new(),
            sa_crypto_suites: Vec::new(),
            sa_crypto_key_definition: Vec::new(),
            sa_interface,
            sa_attestation_bytes: None,
            sa_attestation_statement: None,
            sa_attestation_format: None,
            certification: Vec::new(),
        }
    }

    pub fn sa_type(self: Arc<Self>, sa_type: i64) -> Arc<Self> {
        Arc::new(Self {
            sa_type: Some(sa_type),
            ..(*self).clone()
        })
    }

    pub fn add_sa_supported_user_auth(self: Arc<Self>, auth: i64) -> Arc<Self> {
        let mut auths = self.sa_supported_user_auth.clone();
        auths.push(auth);
        Arc::new(Self {
            sa_supported_user_auth: auths,
            ..(*self).clone()
        })
    }

    pub fn sa_attestation_key_from_coordinates(
        self: Arc<Self>,
        x: Vec<u8>,
        y: Vec<u8>,
        kid: Vec<u8>,
    ) -> Result<Arc<Self>, CryptoError> {
        let cose_key_bytes = cose_key_ec2_p256_public_key(x, y, kid)?;
        self.sa_attestation_key_from_cose_key_bytes(cose_key_bytes)
    }

    pub fn sa_attestation_key_from_cose_key_bytes(
        self: Arc<Self>,
        cose_key_bytes: Vec<u8>,
    ) -> Result<Arc<Self>, CryptoError> {
        let cose_key: CoseKey = ciborium::from_reader(&cose_key_bytes[..])
            .map_err(|e| CryptoError::General(format!("Failed to decode COSE key: {e:?}")))?;

        let sa_attestation_bytes = isomdl::definitions::helpers::Tag24::new(cose_key)
            .map_err(|e| CryptoError::General(format!("Failed to create Tag24: {e:?}")))?;

        Ok(Arc::new(Self {
            sa_attestation_bytes: Some(sa_attestation_bytes),
            ..(*self).clone()
        }))
    }

    pub fn sa_attestation_statement(self: Arc<Self>, statement: Vec<u8>) -> Arc<Self> {
        Arc::new(Self {
            sa_attestation_statement: Some(isomdl::definitions::helpers::ByteStr::from(statement)),
            ..(*self).clone()
        })
    }

    pub fn sa_attestation_format(self: Arc<Self>, format: i64) -> Arc<Self> {
        Arc::new(Self {
            sa_attestation_format: Some(format),
            ..(*self).clone()
        })
    }

    pub fn add_certification_bytes(self: Arc<Self>, cert: Vec<u8>) -> Arc<Self> {
        let mut certs = self.certification.clone();
        certs.push(CertificationItem::Bytes(
            isomdl::definitions::helpers::ByteStr::from(cert),
        ));
        Arc::new(Self {
            certification: certs,
            ..(*self).clone()
        })
    }

    pub fn add_certification_text(self: Arc<Self>, cert: String) -> Arc<Self> {
        let mut certs = self.certification.clone();
        certs.push(CertificationItem::Text(cert));
        Arc::new(Self {
            certification: certs,
            ..(*self).clone()
        })
    }

    pub fn get_sa_index(&self) -> u64 {
        self.sa_index
    }

    pub fn get_sa_interface(&self) -> i64 {
        self.sa_interface
    }

    pub fn get_sa_type(&self) -> Option<i64> {
        self.sa_type
    }

    pub fn get_sa_supported_user_auth(&self) -> Vec<i64> {
        self.sa_supported_user_auth.clone()
    }
}

impl SaAttestationObjectValueBuilder {
    fn build(&self) -> Result<SaAttestationObjectValue, CryptoError> {
        Ok(SaAttestationObjectValue {
            sa_index: self.sa_index,
            sa_type: self.sa_type,
            sa_supported_user_auth: self.sa_supported_user_auth.clone(),
            sa_crypto_suites: self.sa_crypto_suites.clone(),
            sa_crypto_key_definition: self.sa_crypto_key_definition.clone(),
            sa_interface: self.sa_interface,
            sa_attestation_bytes: self.sa_attestation_bytes.clone(),
            sa_attestation_statement: self.sa_attestation_statement.clone(),
            sa_attestation_format: self.sa_attestation_format,
            certification: self.certification.clone(),
        })
    }
}

impl Clone for SaAttestationObjectValueBuilder {
    fn clone(&self) -> Self {
        Self {
            sa_index: self.sa_index,
            sa_type: self.sa_type,
            sa_supported_user_auth: self.sa_supported_user_auth.clone(),
            sa_crypto_suites: self.sa_crypto_suites.clone(),
            sa_crypto_key_definition: self.sa_crypto_key_definition.clone(),
            sa_interface: self.sa_interface,
            sa_attestation_bytes: self.sa_attestation_bytes.clone(),
            sa_attestation_statement: self.sa_attestation_statement.clone(),
            sa_attestation_format: self.sa_attestation_format,
            certification: self.certification.clone(),
        }
    }
}
