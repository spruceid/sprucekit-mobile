use std::sync::Arc;

use tracing::Level;
use tracing_subscriber::fmt::MakeWriter;

#[derive(Clone)]
struct Logger(Arc<dyn LogWriter>);

impl Drop for Logger {
    fn drop(&mut self) {
        self.0.flush();
    }
}

#[uniffi::export(with_foreign)]
trait LogWriter: Send + Sync {
    fn write_to_buffer(&self, message: Vec<u8>);
    fn flush(&self);
}

impl<'a> MakeWriter<'a> for Logger {
    type Writer = Self;

    fn make_writer(&'a self) -> Self::Writer {
        self.clone()
    }
}

impl std::io::Write for Logger {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.0.write_to_buffer(buf.to_vec());
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.0.flush();
        Ok(())
    }
}

/// Configure the global logger for the mobile SDK.
///
/// This method should be called once per application lifecycle. Subsequent calls will be ignored.
// Improvements:
// - Support native log levels through a direct Subscriber implementation.
#[uniffi::export]
fn configure_logger(writer: Arc<dyn LogWriter>) {
    let _ = tracing_subscriber::fmt()
        .with_level(false)
        .with_ansi(false)
        .without_time()
        .with_max_level(Level::DEBUG)
        .with_writer(Logger(writer))
        .try_init();
}
