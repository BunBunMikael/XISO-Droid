//! JNI entry points for `dev.xdvdfs.android.Native`.
//!
//! Every operation is a blocking call that returns `null` on success or an
//! error message (the full `anyhow` chain) on failure. The special message
//! [`CANCELLED`] is returned when the user cancelled the operation.

use std::panic::AssertUnwindSafe;

use jni::objects::{JObject, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;

mod host;
mod manifest;
mod ops;
mod safs;

use host::{set_cancel, Cancelled, Host};

/// Returned instead of an error message when the operation was cancelled.
const CANCELLED: &str = "__CANCELLED__";

fn read_string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value)
        .map(String::from)
        .map_err(|e| format!("failed to read string argument: {e}"))
}

fn panic_message(payload: Box<dyn std::any::Any + Send>) -> String {
    if payload.is::<Cancelled>() {
        return CANCELLED.to_string();
    }
    if let Some(s) = payload.downcast_ref::<&str>() {
        return format!("internal error: {s}");
    }
    if let Some(s) = payload.downcast_ref::<String>() {
        return format!("internal error: {s}");
    }
    "internal error".to_string()
}

fn to_jstring(env: &mut JNIEnv, message: Option<String>) -> jstring {
    match message {
        None => std::ptr::null_mut(),
        Some(message) => match env.new_string(message) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
    }
}

/// Runs `job` with a `Host` bound to the Kotlin callback object, converting
/// errors, panics and cancellation into the returned message.
fn run<F>(env: &mut JNIEnv, host_obj: &JObject, job: F) -> jstring
where
    F: FnOnce(&Host) -> anyhow::Result<()>,
{
    let host = match Host::new(env, host_obj) {
        Ok(host) => host,
        Err(e) => {
            return to_jstring(env, Some(format!("failed to bind to the app: {e}")));
        }
    };

    set_cancel(false);
    let result = std::panic::catch_unwind(AssertUnwindSafe(|| job(&host)));

    let message = match result {
        Ok(Ok(())) => None,
        Ok(Err(e)) => Some(format!("{e:?}")),
        Err(payload) => Some(panic_message(payload)),
    };

    to_jstring(env, message)
}

/// `String? packImage(int srcFd, int outFd, Host host)`
#[no_mangle]
pub extern "system" fn Java_dev_xdvdfs_android_Native_packImage(
    mut env: JNIEnv,
    _this: JObject,
    src_fd: jint,
    out_fd: jint,
    host: JObject,
) -> jstring {
    run(&mut env, &host, |h| ops::pack_image(src_fd, out_fd, h))
}

/// `String? packFolder(String manifest, int outFd, Host host)`
#[no_mangle]
pub extern "system" fn Java_dev_xdvdfs_android_Native_packFolder(
    mut env: JNIEnv,
    _this: JObject,
    manifest: JString,
    out_fd: jint,
    host: JObject,
) -> jstring {
    let manifest = match read_string(&mut env, &manifest) {
        Ok(m) => m,
        Err(e) => return to_jstring(&mut env, Some(e)),
    };
    run(&mut env, &host, |h| ops::pack_folder(&manifest, out_fd, h))
}

/// `String? unpack(int srcFd, Host host)`
#[no_mangle]
pub extern "system" fn Java_dev_xdvdfs_android_Native_unpack(
    mut env: JNIEnv,
    _this: JObject,
    src_fd: jint,
    host: JObject,
) -> jstring {
    run(&mut env, &host, |h| ops::unpack(src_fd, h))
}

/// `String? compressImage(int srcFd, String baseName, Host host)`
#[no_mangle]
pub extern "system" fn Java_dev_xdvdfs_android_Native_compressImage(
    mut env: JNIEnv,
    _this: JObject,
    src_fd: jint,
    base_name: JString,
    host: JObject,
) -> jstring {
    let base_name = match read_string(&mut env, &base_name) {
        Ok(n) => n,
        Err(e) => return to_jstring(&mut env, Some(e)),
    };
    run(&mut env, &host, |h| {
        ops::compress_image(src_fd, &base_name, h)
    })
}

/// `String? compressFolder(String manifest, String baseName, Host host)`
#[no_mangle]
pub extern "system" fn Java_dev_xdvdfs_android_Native_compressFolder(
    mut env: JNIEnv,
    _this: JObject,
    manifest: JString,
    base_name: JString,
    host: JObject,
) -> jstring {
    let manifest = match read_string(&mut env, &manifest) {
        Ok(m) => m,
        Err(e) => return to_jstring(&mut env, Some(e)),
    };
    let base_name = match read_string(&mut env, &base_name) {
        Ok(n) => n,
        Err(e) => return to_jstring(&mut env, Some(e)),
    };
    run(&mut env, &host, |h| {
        ops::compress_folder(&manifest, &base_name, h)
    })
}

/// `void cancel()`: asks the running operation to stop at the next opportunity.
#[no_mangle]
pub extern "system" fn Java_dev_xdvdfs_android_Native_cancel(_env: JNIEnv, _this: JObject) {
    set_cancel(true);
}
