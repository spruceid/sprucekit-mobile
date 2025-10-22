use json_syntax::{Parse, Value};

/// Helper function to parse JSON-LD context and create RemoteDocument
pub fn load_context(json_str: &str) -> json_ld::RemoteDocument {
    let (value, _) = Value::parse_str(json_str).unwrap();
    json_ld::RemoteDocument::new(None, None, value)
}
