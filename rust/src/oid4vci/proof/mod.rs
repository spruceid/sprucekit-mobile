use crate::jws::Jws;

pub mod jwt;

#[derive(uniffi::Enum)]
pub enum Proofs {
    Jwt(Vec<Jws>),
}

impl From<Proofs> for oid4vci::proof::Proofs {
    fn from(value: Proofs) -> Self {
        match value {
            Proofs::Jwt(jwts) => Self::Jwt(jwts.into_iter().map(Into::into).collect()),
        }
    }
}
