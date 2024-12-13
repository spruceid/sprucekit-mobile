use openid4vp::core::presentation_definition::PresentationDefinition;
use serde_json::json;
use uniffi::deps::anyhow;

// NOTE: This function is a temporary shim and should be removed when possible.
pub(crate) fn shim_definition(
    presentation_definition: &mut PresentationDefinition,
) -> Result<(), anyhow::Error> {
    shim_mdl_jwt_definition(presentation_definition)?;

    // Add shims as needed (NOTE: these are meant to be temporary and should be removed when possible)

    Ok(())
}

// NOTE: This is a shim to work with W3C MDL presentation definitions
// provided by DB, and will likely be removed in the future.
//
// Notably, this shim updates the locations where the vc type field may be located.
// Additionally, this shim changes the type filter constraint to an array to check for
// the matching pattern, i.e. `Iso18013DriversLicenseCredential`. This ensures the the pattern
// is found amongst an array of types, rather than a single type.
pub(crate) fn shim_mdl_jwt_definition(
    presentation_definition: &mut PresentationDefinition,
) -> Result<(), anyhow::Error> {
    // TODO: Move this to an `edge_case_utils` file to be removed
    // after a better solution is found.
    // This is a shim to work with w3c mdl presentation definition.
    presentation_definition
        .input_descriptors_mut()
        .iter_mut()
        .for_each(|descriptor| {
            descriptor
                .constraints
                .fields_mut()
                .iter_mut()
                .for_each(|field| {
                    let vc_type_path = "$.vc.type".parse().unwrap();
                    let type_path = "$.type".parse().unwrap();

                    if field.path.contains(&vc_type_path) && !field.path.contains(&type_path) {
                        field.path.push(type_path);
                        if let Ok(fld) = field.clone().set_filter(&json!({
                            "pattern": "Iso18013DriversLicenseCredential",
                            "type": "array"
                        })) {
                            *field = fld;
                        }
                    }
                });
        });

    println!(
        "Presentation Definition: {}",
        serde_json::to_string_pretty(&presentation_definition)?
    );

    Ok(())
}
