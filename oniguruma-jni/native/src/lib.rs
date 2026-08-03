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
//! JNI methods of its own; `EnvUnowned::with_env` upgrades it to a `&mut Env` for the duration of
//! a closure and catches any panic. Errors and panics are turned into Java exceptions by the
//! [`ThrowMapped`] error policy.

use jni::{
    errors::ErrorPolicy,
    jni_str,
    objects::{Global, JByteArray, JClass, JIntArray, ReleaseMode},
    refs::Reference,
    strings::JNIString,
    sys::{jboolean, jint, jlong},
    Env, EnvUnowned,
};
use onig::Regex;
use onig::{RegexOptions, Region, SearchOptions, Syntax};
use onig_sys::{ONIG_OPTION_NOT_BEGIN_POSITION, ONIG_OPTION_NOT_BEGIN_STRING};
use std::{any::Any, cell::RefCell, ffi::c_void, slice, str, sync::OnceLock};

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

    #[error("Null Pointer")]
    NullPointer,
}

#[no_mangle]
pub extern "system" fn Java_me_zolotov_oniguruma_jni_Oniguruma_createRegex<'caller>(
    mut env: EnvUnowned<'caller>,
    _: JClass<'caller>,
    pattern: JByteArray<'caller>,
) -> jlong {
    env.with_env(|env| create_regex(env, &pattern))
        .resolve::<ThrowMapped>()
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
) -> JIntArray<'caller> {
    env.with_env(|env| {
        match_pattern(
            env,
            regex_ptr,
            string_ptr,
            byte_offset,
            match_begin_position,
            match_begin_string,
        )
    })
    .resolve::<ThrowMapped>()
}

#[no_mangle]
pub extern "system" fn Java_me_zolotov_oniguruma_jni_Oniguruma_createString<'caller>(
    mut env: EnvUnowned<'caller>,
    _: JClass<'caller>,
    utf8: JByteArray<'caller>,
) -> jlong {
    env.with_env(|env| create_string(env, &utf8))
        .resolve::<ThrowMapped>()
}

#[no_mangle]
pub extern "system" fn Java_me_zolotov_oniguruma_jni_Oniguruma_freeString<'caller>(
    mut env: EnvUnowned<'caller>,
    _: JClass<'caller>,
    ptr: jlong,
) {
    // Be careful to restore the owned type from the pointer
    env.with_env(|_| free::<String>(ptr))
        .resolve::<ThrowMapped>()
}

#[no_mangle]
pub extern "system" fn Java_me_zolotov_oniguruma_jni_Oniguruma_freeRegex<'caller>(
    mut env: EnvUnowned<'caller>,
    _: JClass<'caller>,
    ptr: jlong,
) {
    // Be careful to restore the owned type from the pointer
    env.with_env(|_| free::<Regex>(ptr))
        .resolve::<ThrowMapped>()
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

fn create_regex(env: &Env, pattern: &JByteArray) -> Result<jlong> {
    if pattern.is_null() {
        return Ok(0);
    }
    let byte_array: Vec<u8> = env.convert_byte_array(pattern)?;
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

fn match_pattern<'local>(
    env: &mut Env<'local>,
    regex_ptr: jlong,
    string_ptr: jlong,
    byte_offset: jint,
    match_begin_position: jboolean,
    match_begin_string: jboolean,
) -> Result<JIntArray<'local>> {
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
    if match_begin_position == 0 {
        options |= SearchOptions::from_bits_retain(ONIG_OPTION_NOT_BEGIN_POSITION);
    }
    if match_begin_string == 0 {
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
                create_jni_int_array(env, offsets.as_slice())
            })
        } else {
            // A null array reference is how a mismatch is reported to Java.
            Ok(JIntArray::null())
        }
    })
}

fn create_string(env: &Env, utf8: &JByteArray) -> Result<jlong> {
    if utf8.is_null() {
        Result::<jlong>::Ok(0)
    } else {
        unsafe {
            // Critical: pins the Java heap object without copying (GC is suspended for duration).
            let elements = utf8.get_elements_critical(env, ReleaseMode::NoCopyBack)?;
            let slice = slice::from_raw_parts(elements.as_ptr() as *const u8, elements.len());
            // SAFETY: valid UTF-8 is the documented caller contract of Oniguruma.createString,
            // matching the FFM binding, which copies the bytes into native memory without
            // validating either. The bytes go straight through to onig_search; nothing on the
            // Rust side inspects them as text. Skipping validation keeps createString a single
            // copy, which is most of its cost for large texts.
            let str = String::from_utf8_unchecked(slice.to_vec());
            drop(elements); // Release critical section before any further JNI calls.
            Ok(Box::into_raw(Box::<String>::new(str)) as jlong)
        }
    }
}

fn create_jni_int_array<'local>(env: &mut Env<'local>, input: &[i32]) -> Result<JIntArray<'local>> {
    let array = JIntArray::new(env, input.len())?;
    array.set_region(env, 0, input)?;
    Ok(array)
}

/// Turns native method failures into Java exceptions.
///
/// `EnvUnowned::with_env` already wraps the closure in a `catch_unwind`, so a panic arrives here
/// as [`ErrorPolicy::on_panic`] instead of having to be caught by hand.
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
