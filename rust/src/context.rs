use std::collections::HashMap;

/// Return the default context for the mobile SDK
///
/// Includes VC playground contexts
#[uniffi::export]
pub fn default_ld_json_context() -> HashMap<String, String> {
    let mut context: HashMap<String, String> = HashMap::new();

    // Add the vc playground context
    context = vc_playground_context(context);

    // add more contexts here as needed

    context
}

/// Add the vc playground context to the provided context
pub fn vc_playground_context(mut context: HashMap<String, String>) -> HashMap<String, String> {
    context.insert(
        "https://w3id.org/first-responder/v1".into(),
        include_str!("../tests/context/w3id_org_first_responder_v1.json").into(),
    );
    context.insert(
        "https://w3id.org/vdl/aamva/v1".into(),
        include_str!("../tests/context/w3id_org_vdl_aamva_v1.json").into(),
    );
    context.insert(
        "https://w3id.org/citizenship/v3".into(),
        include_str!("../tests/context/w3id_org_citizenship_v3.json").into(),
    );
    context.insert(
        "https://purl.imsglobal.org/spec/ob/v3p0/context-3.0.2.json".into(),
        include_str!("../tests/context/purl_imsglobal_org_spec_ob_v3p0_context_3_0_2.json").into(),
    );
    context.insert(
        "https://w3id.org/citizenship/v4rc1".into(),
        include_str!("../tests/context/w3id_org_citizenship_v4rc1.json").into(),
    );
    context.insert(
        "https://w3id.org/vc/render-method/v2rc1".into(),
        include_str!("../tests/context/w3id_org_vc_render_method_v2rc1.json").into(),
    );
    context.insert(
        "https://examples.vcplayground.org/contexts/alumni/v2.json".into(),
        include_str!("../tests/context/examples_vcplayground_org_contexts_alumni_v2.json").into(),
    );
    context.insert(
        "https://examples.vcplayground.org/contexts/first-responder/v1.json".into(),
        include_str!("../tests/context/examples_vcplayground_org_contexts_first_responder_v1.json")
            .into(),
    );
    context.insert(
        "https://examples.vcplayground.org/contexts/shim-render-method-term/v1.json".into(),
        include_str!(
            "../tests/context/examples_vcplayground_org_contexts_shim_render_method_term_v1.json"
        )
        .into(),
    );
    context.insert("https://examples.vcplayground.org/contexts/shim-VCv1.1-common-example-terms/v1.json".into(), include_str!("../tests/context/examples_vcplayground_org_contexts_shim_vcv1_1_common_example_terms_v1.json").into());
    context.insert(
        "https://examples.vcplayground.org/contexts/utopia-natcert/v1.json".into(),
        include_str!("../tests/context/examples_vcplayground_org_contexts_utopia_natcert_v1.json")
            .into(),
    );
    context.insert(
        "https://www.w3.org/ns/controller/v1".into(),
        include_str!("../tests/context/w3_org_ns_controller_v1.json").into(),
    );
    context.insert(
        "https://examples.vcplayground.org/contexts/movie-ticket/v2.json".into(),
        include_str!("../tests/context/examples_vcplayground_org_contexts_movie_ticket_v2.json")
            .into(),
    );
    context.insert(
        "https://examples.vcplayground.org/contexts/food-safety-certification/v1.json".into(),
        include_str!(
            "../tests/context/examples_vcplayground_org_contexts_food_safety_certification_v1.json"
        )
        .into(),
    );
    context.insert("https://examples.vcplayground.org/contexts/academic-course-credential/v1.json".into(), include_str!("../tests/context/examples_vcplayground_org_contexts_academic_course_credential_v1.json").into());
    context.insert(
        "https://examples.vcplayground.org/contexts/gs1-8110-coupon/v2.json".into(),
        include_str!("../tests/context/examples_vcplayground_org_contexts_gs1_8110_coupon_v2.json")
            .into(),
    );
    context.insert(
        "https://examples.vcplayground.org/contexts/customer-loyalty/v1.json".into(),
        include_str!(
            "../tests/context/examples_vcplayground_org_contexts_customer_loyalty_v1.json"
        )
        .into(),
    );
    context.insert(
        "https://examples.vcplayground.org/contexts/movie-ticket-vcdm-v2/v1.json".into(),
        include_str!(
            "../tests/context/examples_vcplayground_org_contexts_movie_ticket_vcdm_v2_v1.json"
        )
        .into(),
    );

    context
}
