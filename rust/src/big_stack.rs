//! Run async work on a dedicated thread with a large stack.
//!
//! ssi's JSON-LD context expansion (used by `ssi-claims`'s data-integrity
//! signing and by `cbor-ld`'s CBOR-LD encoder) recurses deep enough to blow
//! iOS's default ~512 KB child-thread stack on realistic credentials. macOS
//! and Linux give threads 2+ MB by default so this only manifests on iOS
//! (and crashes show up as `EXC_BAD_ACCESS code=2` — guard-page hit).
//!
//! [`run_async`] spawns a fresh OS thread with an 8 MB stack, runs a
//! single-threaded `tokio` runtime on it, drives the supplied future to
//! completion, and ferries the result back via a oneshot channel.
//!
//! Use this for **anything that does ssi JSON-LD work** (signing,
//! verifying, CBOR-LD encode/decode) when the public API is exposed through
//! UniFFI to mobile platforms.

use std::future::Future;
use tokio::sync::oneshot;

#[derive(Debug, thiserror::Error)]
pub enum BigStackError {
    #[error("thread spawn failed: {0}")]
    Spawn(#[from] std::io::Error),
    #[error("worker thread dropped result: {0}")]
    Recv(#[from] oneshot::error::RecvError),
    #[error("tokio runtime build failed: {0}")]
    Runtime(String),
}

/// Run an async block on a fresh 8 MB-stack thread and `await` its result.
///
/// `f` is a closure returning the future to drive. The closure is invoked on
/// the worker thread (so neither the closure nor its returned future need to
/// be `Send` between threads — only the captured environment does).
pub async fn run_async<F, Fut, T>(f: F) -> Result<T, BigStackError>
where
    F: FnOnce() -> Fut + Send + 'static,
    Fut: Future<Output = T>,
    T: Send + 'static,
{
    let (tx, rx) = oneshot::channel();
    std::thread::Builder::new()
        .stack_size(8 * 1024 * 1024)
        .name("big-stack".into())
        .spawn(move || {
            let rt = match tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            {
                Ok(rt) => rt,
                Err(e) => {
                    let _ = tx.send(Err(BigStackError::Runtime(e.to_string())));
                    return;
                }
            };
            let value = rt.block_on(f());
            let _ = tx.send(Ok(value));
        })?;

    rx.await?
}
