#![allow(clippy::not_unsafe_ptr_arg_deref)]

//! JNI implementation of the `me.zolotov.oniguruma.jni.Oniguruma` class.
//!
//! There is a convention for naming JNI methods in native code:
//!
//! 1. prefix is `Java_`
//! 2. all dots in the FQN java class name are replaced with underscores
//! 3. the method name is separated from the class name with an underscore
//!
//! So, for example `java.lang.System::gc()` becomes `Java_java_lang_System_gc`.
//!
//! Since jni 0.22 the environment a native method receives is an `EnvUnowned`, which carries no
//! JNI methods of its own. Upgrading it to an `Env` with `EnvUnowned::with_env` costs attach-guard
//! bookkeeping on every call, and the safe `Env` wrappers wrap each JNI function in
//! `ExceptionCheck` calls, which together cost more than the JNI work these methods do. So the
//! bodies here call JNI through the raw vtable, guard against panics with [`try_catch`], and
//! materialize an `Env` only to report a failure, in [`throw`], where the [`ThrowMapped`] error
//! policy picks the exception class.

use jni::{
    errors::ErrorPolicy,
    jni_str,
    objects::{Global, JByteArray, JClass},
    refs::Reference,
    strings::JNIString,
    sys::{jboolean, jbyteArray, jint, jintArray, jlong, jsize},
    Env, EnvUnowned,
};
use onig::Regex;
use onig::{RegexOptions, Region, SearchOptions, Syntax};
use onig_sys::{ONIG_OPTION_NOT_BEGIN_POSITION, ONIG_OPTION_NOT_BEGIN_STRING};
use std::{
    any::Any,
    cell::RefCell,
    ffi::c_void,
    panic::{catch_unwind, AssertUnwindSafe},
    ptr, str,
    sync::OnceLock,
};

type Result<T> = std::result::Result<T, Error>;

// Cache a Global to RuntimeException so the error path avoids a class lookup on every throw.
// Filled on the first throw rather than in JNI_OnLoad: an `Env` can only be borrowed from a
// thread attachment now, and there is no attachment to borrow from during library load.
static RUNTIME_EXCEPTION_CLASS: OnceLock<Global<JClass<'static>>> = OnceLock::new();

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn JNI_OnLoad(_: *mut jni::sys::JavaVM, _: *mut c_void) -> jint {
    // jni initializes itself on the first `EnvUnowned::with_env`, so all this hook has to do is
    // report the JNI version the library needs.
    jni::sys::JNI_VERSION_1_8
}

// Reuse a Region and the offsets buffer per thread to avoid a malloc/free on every match call.
// Both keep their high-water-mark capacity (a few bytes per capture group of the largest match
// the thread ever saw) for the lifetime of the thread.
thread_local! {
    static REGION: RefCell<Region> = RefCell::new(Region::new());
    static OFFSETS: RefCell<Vec<i32>> = RefCell::new(Vec::new());
}

#[derive(thiserror::Error, Debug)]
enum Error {
    #[error("JNI Error: {0}")]
    Jni(#[from] jni::errors::Error),

    #[error("Oniguruma Error: {0}")]
    Oniguruma(#[from] onig::Error),

    #[error("String or pattern is null")]
    NullPatternOrString,

    #[error("byteOffset {offset} out of range [0, {length}]")]
    ByteOffsetOutOfRange { offset: i32, length: usize },

    #[error("Panic happened: {0}")]
    Panic(String),

    #[error("Null Pointer")]
    NullPointer,
}

#[no_mangle]
pub extern "system" fn Java_me_zolotov_oniguruma_jni_Oniguruma_createRegex<'caller>(
    mut env: EnvUnowned<'caller>,
    _: JClass<'caller>,
    pattern: JByteArray<'caller>,
) -> jlong {
    // SAFETY: the JVM guarantees this pointer is the calling thread's JNI environment for the
    // duration of the call. The body reaches JNI only through the raw vtable, so it needs no
    // `Env` and skips what `with_env` does on every call.
    let raw_env = env.as_raw();
    match try_catch(|| unsafe { create_regex(raw_env, &pattern) }) {
        Ok(regex_ptr) => regex_ptr,
        Err(error) => {
            throw(&mut env, error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_me_zolotov_oniguruma_jni_Oniguruma_match<'caller>(
    mut env: EnvUnowned<'caller>,
    _: JClass<'caller>,
    regex_ptr: jlong,
    string_ptr: jlong,
    byte_offset: jint,
    match_begin_position: jboolean,
    match_begin_string: jboolean,
) -> jintArray {
    // SAFETY: see the note in createRegex.
    let raw_env = env.as_raw();
    let matched = try_catch(|| unsafe {
        match_pattern(
            raw_env,
            regex_ptr,
            string_ptr,
            byte_offset,
            match_begin_position,
            match_begin_string,
        )
    });
    match matched {
        Ok(offsets) => offsets,
        Err(error) => {
            throw(&mut env, error);
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_me_zolotov_oniguruma_jni_Oniguruma_createString<'caller>(
    mut env: EnvUnowned<'caller>,
    _: JClass<'caller>,
    utf8: JByteArray<'caller>,
) -> jlong {
    // SAFETY: see the note in createRegex.
    let raw_env = env.as_raw();
    match try_catch(|| unsafe { create_string(raw_env, &utf8) }) {
        Ok(string_ptr) => string_ptr,
        Err(error) => {
            throw(&mut env, error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_me_zolotov_oniguruma_jni_Oniguruma_freeString<'caller>(
    mut env: EnvUnowned<'caller>,
    _: JClass<'caller>,
    ptr: jlong,
) {
    // Be careful to restore the owned type from the pointer
    if let Err(error) = try_catch(|| free::<String>(ptr)) {
        throw(&mut env, error);
    }
}

#[no_mangle]
pub extern "system" fn Java_me_zolotov_oniguruma_jni_Oniguruma_freeRegex<'caller>(
    mut env: EnvUnowned<'caller>,
    _: JClass<'caller>,
    ptr: jlong,
) {
    // Be careful to restore the owned type from the pointer
    if let Err(error) = try_catch(|| free::<Regex>(ptr)) {
        throw(&mut env, error);
    }
}

fn free<T: 'static>(ptr: i64) -> Result<()> {
    if ptr != 0 {
        unsafe {
            drop(Box::<T>::from_raw(ptr as *mut _));
        }
        Ok(())
    } else {
        Err(Error::NullPointer)
    }
}

/// # Safety
///
/// `raw_env` must be the JNI environment of the calling thread.
unsafe fn create_regex(raw_env: *mut jni::sys::JNIEnv, pattern: &JByteArray) -> Result<jlong> {
    if pattern.is_null() {
        return Ok(0);
    }
    let byte_array = copy_byte_array(raw_env, pattern.as_raw());
    // SAFETY: valid UTF-8 is the documented caller contract of Oniguruma.createRegex, matching
    // the FFM binding, which hands the bytes to oniguruma without validating either. The bytes
    // go straight through to onig_new; nothing on the Rust side inspects them as text.
    let pattern_str = unsafe { str::from_utf8_unchecked(&byte_array) };
    let regex = Regex::with_options(
        pattern_str,
        RegexOptions::REGEX_OPTION_CAPTURE_GROUP,
        Syntax::default(),
    )?;
    Ok(Box::into_raw(Box::<Regex>::new(regex)) as jlong)
}

/// # Safety
///
/// `raw_env` must be the JNI environment of the calling thread.
unsafe fn match_pattern(
    raw_env: *mut jni::sys::JNIEnv,
    regex_ptr: jlong,
    string_ptr: jlong,
    byte_offset: jint,
    match_begin_position: jboolean,
    match_begin_string: jboolean,
) -> Result<jintArray> {
    // Creating a null reference is UB even if it is not used
    if regex_ptr == 0 || string_ptr == 0 {
        return Err(Error::NullPatternOrString);
    }
    let regex = unsafe { &*(regex_ptr as *const Regex) };
    let str = unsafe { &*(string_ptr as *mut String) };

    // The onig crate offsets the string pointer by `from` before it bounds-checks, so an
    // out-of-range offset is out-of-bounds pointer arithmetic (UB) before it is anything else.
    // Reject it here instead, with the same exception the FFM binding throws.
    if byte_offset < 0 || byte_offset as usize > str.len() {
        return Err(Error::ByteOffsetOutOfRange {
            offset: byte_offset,
            length: str.len(),
        });
    }

    let mut options = SearchOptions::SEARCH_OPTION_NONE;
    // `SearchOptions` has no named constants for these two, so they go in as raw bits.
    // Note that `jboolean` is a `bool` since jni-sys 0.4, not a `u8`.
    if !match_begin_position {
        options |= SearchOptions::from_bits_retain(ONIG_OPTION_NOT_BEGIN_POSITION);
    }
    if !match_begin_string {
        options |= SearchOptions::from_bits_retain(ONIG_OPTION_NOT_BEGIN_STRING);
    }

    REGION.with(|r| {
        let mut region = r.borrow_mut();
        // The reused region needs no clearing before the search: onig_search resize-clears a
        // non-null region unconditionally on entry, on both the match and the mismatch paths
        // (regexec.c, search_in_range).
        let matched = regex.search_with_options(
            str,
            byte_offset as usize,
            str.len(),
            options,
            Some(&mut *region),
        );
        if matched.is_some() {
            // Filling a reused buffer with all the start and end offsets one after the other;
            // unmatched groups report (-1, -1).
            OFFSETS.with(|o| {
                let mut offsets = o.borrow_mut();
                offsets.clear();
                for i in 0..region.len() {
                    let (s, e) = region
                        .pos(i)
                        .map(|(s, e)| (s as i32, e as i32))
                        .unwrap_or((-1, -1));
                    offsets.push(s);
                    offsets.push(e);
                }
                Ok(new_int_array(raw_env, offsets.as_slice()))
            })
        } else {
            // A null array reference is how a mismatch is reported to Java.
            Ok(ptr::null_mut())
        }
    })
}

/// # Safety
///
/// `raw_env` must be the JNI environment of the calling thread.
unsafe fn create_string(raw_env: *mut jni::sys::JNIEnv, utf8: &JByteArray) -> Result<jlong> {
    if utf8.is_null() {
        Result::<jlong>::Ok(0)
    } else {
        let bytes = copy_byte_array(raw_env, utf8.as_raw());
        // SAFETY: valid UTF-8 is the documented caller contract of Oniguruma.createString,
        // matching the FFM binding, which copies the bytes into native memory without
        // validating either. The bytes go straight through to onig_search; nothing on the
        // Rust side inspects them as text. Skipping validation keeps createString a single
        // copy, which is most of its cost for large texts.
        let str = unsafe { String::from_utf8_unchecked(bytes) };
        Ok(Box::into_raw(Box::<String>::new(str)) as jlong)
    }
}

/// Copies a Java `byte[]` into a fresh `Vec<u8>`.
///
/// Two JNI calls and no zeroing pass: `Vec::with_capacity` leaves the buffer uninitialized and
/// `GetByteArrayRegion` copies straight into it. `Env::convert_byte_array` instead zeroes a
/// `vec![0u8; len]` that the copy immediately overwrites, and wraps both JNI calls in
/// `ExceptionCheck` calls (one before each, one after the region copy). Neither call can fail
/// here: `GetArrayLength` has no failure mode, and `[0, len)` is exact by construction because a
/// Java array cannot resize after `GetArrayLength` returned its length.
///
/// # Safety
///
/// `raw_env` must be the JNI environment of the calling thread and `array` a non-null local
/// reference to a `byte[]`.
unsafe fn copy_byte_array(raw_env: *mut jni::sys::JNIEnv, array: jbyteArray) -> Vec<u8> {
    let interface = *raw_env;
    let len = ((*interface).v1_1.GetArrayLength)(raw_env, array) as usize;
    let mut bytes: Vec<u8> = Vec::with_capacity(len);
    if len > 0 {
        // Skipped when empty so the dangling pointer of a zero-capacity Vec never reaches JNI.
        ((*interface).v1_1.GetByteArrayRegion)(
            raw_env,
            array,
            0,
            len as jsize,
            bytes.as_mut_ptr().cast(),
        );
    }
    bytes.set_len(len);
    bytes
}

/// Creates a Java `int[]` holding `values`.
///
/// Returns null if `NewIntArray` fails, leaving the pending `OutOfMemoryError` for the JVM to
/// see. `SetIntArrayRegion` cannot throw over `[0, len)` of an array just allocated with exactly
/// that length, so both calls skip the `ExceptionCheck` pairs that `JIntArray::new` and
/// `JPrimitiveArray::set_region` add, along with the `Env::assert_top` thread-local read.
///
/// # Safety
///
/// `raw_env` must be the JNI environment of the calling thread.
unsafe fn new_int_array(raw_env: *mut jni::sys::JNIEnv, values: &[jint]) -> jintArray {
    let interface = *raw_env;
    let len = values.len() as jsize;
    let array = ((*interface).v1_1.NewIntArray)(raw_env, len);
    if !array.is_null() {
        ((*interface).v1_1.SetIntArrayRegion)(raw_env, array, 0, len, values.as_ptr());
    }
    array
}

/// Runs a native method body, turning a panic into an [`Error`] rather than letting it unwind
/// into the JVM, which would abort the process.
///
/// `EnvUnowned::with_env` also catches panics, but only as part of materializing an `Env`: it
/// pushes and pops the attach-guard nesting level in thread-local storage, calls `get_java_vm`
/// and builds an `EnvOutcome`, all of which cost more than the JNI work in these methods. The
/// bodies here reach JNI through the raw vtable and need no `Env` at all unless they fail, so
/// the `Env` is left to [`throw`] on the cold path.
///
/// `AssertUnwindSafe` is sound here because a panic leaves nothing observable behind: the only
/// state the bodies share between calls is the thread-local `Region` and offsets buffer, whose
/// `RefCell` guards are released while unwinding, and both are cleared before use on the next
/// call.
fn try_catch<T>(body: impl FnOnce() -> Result<T>) -> Result<T> {
    catch_unwind(AssertUnwindSafe(body))
        .unwrap_or_else(|payload| Err(Error::Panic(describe_panic(&*payload))))
}

/// Throws `error` as a Java exception.
///
/// This is the only path that needs a real `Env`, so it is the only one that pays for
/// [`EnvUnowned::with_env`]. Handing the error to the closure lets [`ThrowMapped`] pick the
/// exception class, exactly as it does for a failure inside `with_env` itself.
fn throw(env: &mut EnvUnowned, error: Error) {
    env.with_env(|_| Err::<(), Error>(error))
        .resolve::<ThrowMapped>()
}

/// Turns native method failures into Java exceptions.
///
/// A panic in a native method body is caught by [`try_catch`] and arrives as [`Error::Panic`], so
/// [`ErrorPolicy::on_panic`] only runs in the far rarer case of a panic inside [`throw`] itself.
struct ThrowMapped;

impl<T: Default> ErrorPolicy<T, Error> for ThrowMapped {
    type Captures<'unowned_env_local: 'native_method, 'native_method> = ();

    fn on_error<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut Self::Captures<'unowned_env_local, 'native_method>,
        error: Error,
    ) -> jni::errors::Result<T> {
        // Only throw if there is no pending exception yet.
        if !env.exception_check() {
            let message = JNIString::from(error.to_string());
            match error {
                // Caller errors surface as IllegalArgumentException, matching the FFM binding.
                // This path is rare enough that the class lookup is fine.
                Error::ByteOffsetOutOfRange { .. } => {
                    let _ = env.throw_new(jni_str!("java/lang/IllegalArgumentException"), &message);
                }
                _ => throw_runtime_exception(env, &message),
            }
        }
        Ok(T::default())
    }

    fn on_panic<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut Self::Captures<'unowned_env_local, 'native_method>,
        payload: Box<dyn Any + Send + 'static>,
    ) -> jni::errors::Result<T> {
        if !env.exception_check() {
            let message =
                JNIString::from(format!("Panic happened: {}", describe_panic(&*payload)));
            throw_runtime_exception(env, &message);
        }
        Ok(T::default())
    }
}

fn throw_runtime_exception(env: &mut Env, message: &JNIString) {
    // Use the cached Global to avoid a class lookup on every throw. The string descriptor of the
    // fallback resolves to RuntimeException as well, so both paths throw the same class.
    let class = runtime_exception_class(env);
    let _ = match class {
        Ok(class) => env.throw_new(class, message),
        Err(_) => env.throw_new(jni_str!("java/lang/RuntimeException"), message),
    };
}

fn runtime_exception_class(env: &mut Env) -> jni::errors::Result<&'static Global<JClass<'static>>> {
    if let Some(class) = RUNTIME_EXCEPTION_CLASS.get() {
        return Ok(class);
    }
    let class = env.find_class(jni_str!("java/lang/RuntimeException"))?;
    // A racing thread may win the set(); the loser's Global is simply dropped.
    let _ = RUNTIME_EXCEPTION_CLASS.set(env.new_global_ref(&class)?);
    Ok(RUNTIME_EXCEPTION_CLASS
        .get()
        .expect("cache was just populated"))
}

fn describe_panic(payload: &(dyn Any + Send + 'static)) -> String {
    if let Some(description) = payload.downcast_ref::<String>() {
        description.clone()
    } else if let Some(description) = payload.downcast_ref::<&'static str>() {
        (*description).to_string()
    } else {
        "Unknown".to_string()
    }
}
