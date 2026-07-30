/*
 * config.h for compiling Oniguruma with the clang bundled in the Kotlin/Native distribution.
 *
 * Oniguruma's own build systems (autotools, cmake) probe the toolchain to generate this header;
 * here the sources are compiled by cinterop's -Xcompile-source, which runs no probes. Every
 * Kotlin/Native target compiles with clang against either a glibc/darwin or a mingw-w64 sysroot,
 * so the probe results are known statically: the type sizes come from the compiler itself and
 * the header availability splits on _WIN32.
 */
#ifndef ONIGURUMA_KONAN_CONFIG_H
#define ONIGURUMA_KONAN_CONFIG_H

#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_SYS_TYPES_H 1

#ifndef _WIN32
#define HAVE_ALLOCA 1
#define HAVE_ALLOCA_H 1
#define HAVE_UNISTD_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_SYS_TIMES_H 1
#endif

#define SIZEOF_INT __SIZEOF_INT__
#define SIZEOF_LONG __SIZEOF_LONG__
#define SIZEOF_LONG_LONG __SIZEOF_LONG_LONG__
#define SIZEOF_VOIDP __SIZEOF_POINTER__

#define PACKAGE "onig"
#define PACKAGE_VERSION "6.9.10"
#define VERSION PACKAGE_VERSION

#endif
