use std::collections::HashMap;

use ssi::json_ld::{ContextLoader, FromContextMapError};

pub fn context_loader_from_map(
    map: HashMap<String, String>,
) -> Result<ContextLoader, FromContextMapError> {
    ContextLoader::empty()
        .with_static_loader()
        .with_context_map_from(map)
}
