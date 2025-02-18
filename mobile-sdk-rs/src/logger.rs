/// Initiate the global logger for the mobile SDK.
///
/// This method should be called once per application lifecycle.
#[uniffi::export]
pub fn init_global_logger() {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Trace)
            .with_tag("MOBILE_SDK_RS"),
    );
}
