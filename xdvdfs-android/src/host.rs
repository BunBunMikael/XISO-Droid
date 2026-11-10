//! Bridge to the Kotlin `Native.Host` object.
//!
//! All calls are made from the thread that entered the native method (the job
//! worker thread owned by the app), but the xdvdfs traits require `Send + Sync`,
//! so instead of keeping a `JNIEnv` we keep the `JavaVM` and a global reference
//! and (re)attach on every call. Attaching an already attached thread is cheap
//! and does not detach it again on drop.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use jni::objects::{GlobalRef, JObject, JValue};
use jni::{JNIEnv, JavaVM};

/// Progress event kinds, mirrored in `Native.kt`.
pub const EV_TOTAL: i32 = 0;
pub const EV_ITEM: i32 = 1;
pub const EV_STAGE: i32 = 2;
pub const EV_SECT_TOTAL: i32 = 3;
pub const EV_SECT_DONE: i32 = 4;

/// Stage codes, mirrored in `Native.kt`.
pub const ST_PACKING: i64 = 1;
pub const ST_FLUSH: i64 = 2;
pub const ST_COMPRESSING: i64 = 3;

static CANCEL: AtomicBool = AtomicBool::new(false);

/// Panic payload used to unwind out of the xdvdfs image writers when the user
/// cancels (their progress callbacks cannot return errors).
pub struct Cancelled;

pub fn set_cancel(value: bool) {
    CANCEL.store(value, Ordering::SeqCst);
}

/// Unwinds with [`Cancelled`] if cancellation was requested.
/// `resume_unwind` does not invoke the panic hook, so nothing is logged.
pub fn check_cancel() {
    if CANCEL.load(Ordering::Relaxed) {
        std::panic::resume_unwind(Box::new(Cancelled));
    }
}

struct Inner {
    vm: JavaVM,
    obj: GlobalRef,
}

#[derive(Clone)]
pub struct Host(Arc<Inner>);

impl Host {
    pub fn new(env: &mut JNIEnv, obj: &JObject) -> jni::errors::Result<Host> {
        let vm = env.get_java_vm()?;
        let obj = env.new_global_ref(obj)?;
        Ok(Host(Arc::new(Inner { vm, obj })))
    }

    /// `int openSourceFile(int index)`: detached fd for entry `index` of the
    /// folder manifest, or a negative value on failure.
    pub fn open_source_file(&self, index: usize) -> i32 {
        let mut env = match self.0.vm.attach_current_thread() {
            Ok(env) => env,
            Err(_) => return -1,
        };
        let result = env.call_method(
            &self.0.obj,
            "openSourceFile",
            "(I)I",
            &[JValue::Int(index as i32)],
        );
        match result.and_then(|v| v.i()) {
            Ok(fd) => fd,
            Err(_) => {
                let _ = env.exception_clear();
                -1
            }
        }
    }

    /// `int createOutputFile(String path)`: detached, writable fd for a new file
    /// at the relative `path` inside the output folder (missing parent
    /// directories are created by the app), or a negative value on failure.
    pub fn create_output_file(&self, path: &str) -> i32 {
        self.call_with_string("createOutputFile", path)
    }

    /// `int createOutputDir(String path)`: 0 on success, negative on failure.
    pub fn create_output_dir(&self, path: &str) -> i32 {
        self.call_with_string("createOutputDir", path)
    }

    fn call_with_string(&self, method: &str, arg: &str) -> i32 {
        let mut env = match self.0.vm.attach_current_thread() {
            Ok(env) => env,
            Err(_) => return -1,
        };
        let jstr = match env.new_string(arg) {
            Ok(s) => s,
            Err(_) => {
                let _ = env.exception_clear();
                return -1;
            }
        };

        let result = {
            let as_obj: &JObject = &jstr;
            env.call_method(
                &self.0.obj,
                method,
                "(Ljava/lang/String;)I",
                &[JValue::Object(as_obj)],
            )
        };
        // Local references pile up in a long running native frame (ART aborts at
        // 512), so release the string right away.
        let _ = env.delete_local_ref(jstr);

        match result.and_then(|v| v.i()) {
            Ok(value) => value,
            Err(_) => {
                let _ = env.exception_clear();
                -1
            }
        }
    }

    /// `void onProgress(int kind, long a, long b, String text)`
    pub fn progress(&self, kind: i32, a: i64, b: i64, text: Option<&str>) {
        let mut env = match self.0.vm.attach_current_thread() {
            Ok(env) => env,
            Err(_) => return,
        };

        let jstr = match text {
            Some(t) => env.new_string(t).ok(),
            None => None,
        };
        let null = JObject::null();

        let result = {
            let text_obj: &JObject = match &jstr {
                Some(s) => &**s,
                None => &null,
            };
            env.call_method(
                &self.0.obj,
                "onProgress",
                "(IJJLjava/lang/String;)V",
                &[
                    JValue::Int(kind),
                    JValue::Long(a),
                    JValue::Long(b),
                    JValue::Object(text_obj),
                ],
            )
        };

        if let Some(s) = jstr {
            let _ = env.delete_local_ref(s);
        }
        if result.is_err() {
            let _ = env.exception_clear();
        }
    }
}
