#![allow(clippy::not_unsafe_ptr_arg_deref)]

//! JNI implementation of `org.jetbrains.plugins.textmate.regex.oniguruma.OnigurumaRegexFactory`
//! and `org.jetbrains.plugins.textmate.regex.oniguruma.OnigurumaRegexFacade` classes
//!
//! There is convention for naming JNI method in native code:
//!
//! 1. prefix is `Java_`
//! 2. all dots in FQN java class name replaced with underscore
//! 3. method name is separated from class name with underscore
//!
//! So, for exampe `java.lang.System::gc()` becomes `Java_java_lang_System_gc`.

use jni::{
    objects::{GlobalRef, JByteArray, JClass, JPrimitiveArray, ReleaseMode},
    sys::{jboolean, jbyteArray, jint, jintArray, jlong},
    JavaVM, JNIEnv,
};
use onig::Regex;
use onig::{RegexOptions, Region, SearchOptions, Syntax};
use onig_sys::{ONIG_OPTION_NOT_BEGIN_POSITION, ONIG_OPTION_NOT_BEGIN_STRING};
use std::{
    any::Any,
    cell::RefCell,
    ffi::c_void,
    panic::{catch_unwind, RefUnwindSafe},
    ptr, slice,
    str,
    sync::OnceLock,
};

type Result<T> = std::result::Result<T, Error>;

// Cache a GlobalRef to RuntimeException so the error path avoids a class lookup on every throw.
static RUNTIME_EXCEPTION_CLASS: OnceLock<GlobalRef> = OnceLock::new();

#[no_mangle]
#[allow(non_snake_case)]
pub unsafe extern "system" fn JNI_OnLoad(raw_vm: *mut jni::sys::JavaVM, _: *mut c_void) -> jint {
    let vm = JavaVM::from_raw(raw_vm).unwrap();
    let mut env: JNIEnv = vm.get_env().unwrap();
    let cls = env.find_class("java/lang/RuntimeException").unwrap();
    RUNTIME_EXCEPTION_CLASS.set(env.new_global_ref(cls).unwrap()).ok();
    jni::sys::JNI_VERSION_1_8
}

// Reuse a Region and the offsets buffer per thread to avoid a malloc/free on every match call.
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
}

#[no_mangle]
pub extern "C" fn Java_me_zolotov_oniguruma_jni_Oniguruma_createRegex(
    env: JNIEnv,
    _: JClass,
    pattern: jbyteArray,
) -> jlong {
    try_catch(|| create_regex(&env, pattern))
        .propagate_exception(env)
        .unwrap_or_default()
}

#[no_mangle]
pub extern "C" fn Java_me_zolotov_oniguruma_jni_Oniguruma_match(
    env: JNIEnv,
    _: JClass,
    regex_ptr: jlong,
    string_ptr: jlong,
    byte_offset: jint,
    match_begin_position: jboolean,
    match_begin_string: jboolean,
) -> jintArray {
    try_catch(|| {
        match_pattern(
            &env,
            regex_ptr,
            string_ptr,
            byte_offset,
            match_begin_position,
            match_begin_string,
        )
    })
    .propagate_exception(env)
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn Java_me_zolotov_oniguruma_jni_Oniguruma_createString(
    mut env: JNIEnv,
    _: JClass,
    utf8: jbyteArray,
) -> jlong {
    // This is the only place where we can not use try_catch(), because &mut JNIEnv can not cross FFI boundary
    create_string(&mut env, utf8)
        .propagate_exception(env)
        .unwrap_or_default()
}

#[no_mangle]
pub extern "C" fn Java_me_zolotov_oniguruma_jni_Oniguruma_freeString(
    env: JNIEnv,
    _: JClass,
    ptr: jlong,
) {
    // Be carefult to restore owned type from pointer
    try_catch(|| free::<String>(ptr)).propagate_exception(env);
}

#[no_mangle]
pub extern "C" fn Java_me_zolotov_oniguruma_jni_Oniguruma_freeRegex(
    env: JNIEnv,
    _: JClass,
    ptr: jlong,
) {
    // Be carefult to restore owned type from pointer
    try_catch(|| free::<Regex>(ptr)).propagate_exception(env);
}

fn free<T: 'static>(ptr: i64) -> Result<()> {
    // Freeing the 0 handle is a no-op, like free(NULL): createRegex/createString return 0 for a
    // null input, so the natural create/free pairing must not throw on it.
    if ptr != 0 {
        unsafe {
            drop(Box::<T>::from_raw(ptr as *mut _));
        }
    }
    Ok(())
}

fn create_regex(env: &JNIEnv, pattern: jbyteArray) -> Result<jlong> {
    if pattern.is_null() {
        return Ok(0);
    }
    let p = unsafe { JByteArray::from_raw(pattern) };
    let byte_array: Vec<u8> = env.convert_byte_array(p)?;
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

fn match_pattern(
    env: &JNIEnv,
    regex_ptr: jlong,
    string_ptr: jlong,
    byte_offset: jint,
    match_begin_position: jboolean,
    match_begin_string: jboolean,
) -> Result<jintArray> {
    // Creating a null pointer is UB event if it is not used
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
    if match_begin_position == 0 {
        options |= unsafe { SearchOptions::from_bits_unchecked(ONIG_OPTION_NOT_BEGIN_POSITION) };
    }
    if match_begin_string == 0 {
        options |= unsafe { SearchOptions::from_bits_unchecked(ONIG_OPTION_NOT_BEGIN_STRING) };
    }

    REGION.with(|r| {
        let mut region = r.borrow_mut();
        // The reused region needs no clearing before the search: onig_search resize-clears a
        // non-null region unconditionally on entry, on both the match and the mismatch paths
        // (oniguruma 6.9.8, as bundled by onig_sys 69.8.1: regexec.c, search_in_range).
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
                Ok(create_jni_int_array(env, offsets.as_slice())?.into_raw())
            })
        } else {
            Ok(ptr::null_mut())
        }
    })
}

fn create_string(env: &mut JNIEnv, utf8: jbyteArray) -> Result<jlong> {
    if utf8.is_null() {
        Result::<jlong>::Ok(0)
    } else {
        unsafe {
            let p = JByteArray::from_raw(utf8);
            // Allocate the destination before entering the critical section: the JNI spec forbids
            // anything that can block between Get/ReleasePrimitiveArrayCritical, and a malloc can.
            // With the capacity reserved up front, the critical section is just the memcpy.
            let mut bytes = Vec::with_capacity(env.get_array_length(&p)? as usize);
            {
                // Critical: pins the Java heap object without copying (GC is suspended for duration).
                let elements = env.get_array_elements_critical(&p, ReleaseMode::NoCopyBack)?;
                let slice = slice::from_raw_parts(elements.as_ptr() as *const u8, elements.len());
                bytes.extend_from_slice(slice);
                // elements drops here, releasing the critical section before any further JNI calls.
            }
            // SAFETY: valid UTF-8 is the documented caller contract of Oniguruma.createString,
            // matching the FFM binding, which copies the bytes into native memory without
            // validating either. The bytes go straight through to onig_search; nothing on the
            // Rust side inspects them as text. Skipping validation keeps createString a single
            // copy, which is most of its cost for large texts.
            let str = String::from_utf8_unchecked(bytes);
            Ok(Box::into_raw(Box::<String>::new(str)) as jlong)
        }
    }
}

fn create_jni_int_array<'a>(env: &JNIEnv<'a>, input: &[i32]) -> Result<JPrimitiveArray<'a, i32>> {
    let array = env.new_int_array(input.len() as i32)?;
    env.set_int_array_region(&array, 0, input)?;
    Ok(array)
}

trait ToJavaException<T> {
    fn propagate_exception(self, env: JNIEnv) -> Option<T>;
}

impl<T> ToJavaException<T> for Result<T> {
    fn propagate_exception(self, mut env: JNIEnv) -> Option<T> {
        match self {
            Ok(r) => Some(r),
            Err(error) => {
                if !env.exception_check().unwrap() {
                    // Only throw if there is no pending exception yet.
                    match &error {
                        // Caller errors surface as IllegalArgumentException, matching the FFM
                        // binding. This path is rare enough that the class lookup is fine.
                        Error::ByteOffsetOutOfRange { .. } => {
                            let class = env.find_class("java/lang/IllegalArgumentException").unwrap();
                            env.throw_new(class, error.to_string()).unwrap();
                        }
                        // Use the cached GlobalRef to avoid a class lookup on every throw.
                        _ => match RUNTIME_EXCEPTION_CLASS.get() {
                            Some(cls) => env.throw_new(cls, error.to_string()).unwrap(),
                            None => {
                                let class = env.find_class("java/lang/RuntimeException").unwrap();
                                env.throw_new(class, error.to_string()).unwrap();
                            }
                        },
                    }
                }
                None
            }
        }
    }
}

// Wraps all panics into Result with a string description of a problem
fn try_catch<T>(f: impl Fn() -> Result<T> + RefUnwindSafe) -> Result<T> {
    catch_unwind(&f).map_err(downcast_error)?
}

fn downcast_error(e: Box<dyn Any + Send>) -> Error {
    if let Some(description) = e.downcast_ref::<String>() {
        Error::Panic(description.to_string())
    } else {
        Error::Panic("Unknown".to_string())
    }
}
