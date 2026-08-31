
cmake_minimum_required(VERSION 3.13)


project(NativeVNC C CXX ASM)

set(AVNC_EXTERN_DIR ${PROJECT_SOURCE_DIR}/../extern)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared Libs" FORCE)

###############################################################################
# Utilities
###############################################################################

# Make sure given submodule is checked out
macro(avnc_check_submodule name)
    if (NOT EXISTS "${AVNC_EXTERN_DIR}/${name}/CMakeLists.txt")
        message(FATAL_ERROR "git submodule for ${name} is not initialized.
                             Please run 'git submodule update --init'.")
    endif ()
endmacro()


# Required to enable SIMD support on ARM
if (CMAKE_ANDROID_ARCH STREQUAL "arm64")
    set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} --target=aarch64-linux-android${ANDROID_NATIVE_API_LEVEL}")
elseif (CMAKE_ANDROID_ARCH STREQUAL "arm")
    set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} --target=arm-linux-androideabi${ANDROID_NATIVE_API_LEVEL}")
endif ()

# Clear build id in release builds to allow reproducible builds on F-Droid
add_link_options("LINKER:$<$<NOT:$<CONFIG:Debug>>:--build-id=none>")


###############################################################################
# JPEG
###############################################################################
avnc_check_submodule(libjpeg-turbo)

set(AVNC_LIBJPEG_SRC_DIR ${AVNC_EXTERN_DIR}/libjpeg-turbo)
set(AVNC_LIBJPEG_BUILD_DIR ${CMAKE_BINARY_DIR}/libjpeg-turbo)

add_subdirectory(${AVNC_LIBJPEG_SRC_DIR} ${AVNC_LIBJPEG_BUILD_DIR})

# Set these variables so FindJPEG can find the library
set(JPEG_LIBRARY ${AVNC_LIBJPEG_BUILD_DIR}/libturbojpeg.a)
set(JPEG_INCLUDE_DIR ${AVNC_LIBJPEG_SRC_DIR})

include_directories(${AVNC_LIBJPEG_SRC_DIR} ${AVNC_LIBJPEG_BUILD_DIR})


###############################################################################
# SSL
###############################################################################
avnc_check_submodule(wolfssl)

set(AVNC_LIBSSL_SRC_DIR ${AVNC_EXTERN_DIR}/wolfssl)
set(AVNC_LIBSSL_BUILD_DIR ${CMAKE_BINARY_DIR}/wolfssl)

# CMake support in wolfSSl is still under development, so we have to
# manually set some flags to enable OpenSSL compatibility layer
set(WOLFSSL_CRL yes)
set(WOLFSSL_DES3 yes)
add_definitions(-DOPENSSL_ALL -DOPENSSL_EXTRA -DHAVE_CRL -DHAVE_EX_DATA
        -DHAVE_ANON -DWOLFSSL_AES_DIRECT -DHAVE_AES_ECB -DWOLFSSL_DES_ECB
        -DWC_NO_HARDEN -DWOLFSSL_NO_OPTIONS_H)

add_subdirectory(${AVNC_LIBSSL_SRC_DIR} ${AVNC_LIBSSL_BUILD_DIR})

# Set these variables so FindOpenSSL can find the library
set(OPENSSL_SSL_LIBRARY ${AVNC_LIBSSL_BUILD_DIR}/libwolfssl.a)
set(OPENSSL_CRYPTO_LIBRARY ${AVNC_LIBSSL_BUILD_DIR}/libwolfssl.a)
set(OPENSSL_INCLUDE_DIR ${AVNC_LIBSSL_SRC_DIR}/wolfssl)

include_directories(${AVNC_LIBSSL_SRC_DIR})


###############################################################################
# LibVNC
###############################################################################
avnc_check_submodule(libvncserver)

# The modern Apple Screen Sharing implementation currently gates its SRP
# BIGNUM code behind __APPLE__, even though that code only depends on OpenSSL
# BIGNUM APIs. Android AVNC provides those APIs through wolfSSL's OpenSSL
# compatibility layer. Work from a build-directory copy so the checked-out
# submodule remains clean, then relax only those BIGNUM guards. Kerberos/GSS
# support remains Apple-only in the upstream source.
set(AVNC_LIBVNC_UPSTREAM_SRC_DIR ${AVNC_EXTERN_DIR}/libvncserver)
set(AVNC_LIBVNC_SRC_DIR ${CMAKE_BINARY_DIR}/libvncserver-src)
set(AVNC_LIBVNC_BUILD_DIR ${CMAKE_BINARY_DIR}/libvncserver)

file(REMOVE_RECURSE ${AVNC_LIBVNC_SRC_DIR})
file(COPY ${AVNC_LIBVNC_UPSTREAM_SRC_DIR}/ DESTINATION ${AVNC_LIBVNC_SRC_DIR})

set(AVNC_ARD_AUTH_SOURCE ${AVNC_LIBVNC_SRC_DIR}/src/libvncclient/ardauth.c)
file(READ ${AVNC_ARD_AUTH_SOURCE} AVNC_ARD_AUTH_CONTENT)
string(REPLACE
        "#if defined(__APPLE__) && defined(LIBVNCSERVER_HAVE_LIBSSL) && defined(__has_include)\n#if __has_include(<openssl/bn.h>)\n#include <openssl/bn.h>\n#define LIBVNCCLIENT_APPLE_HAS_OPENSSL_BN 1\n#endif\n#endif"
        "#if defined(LIBVNCSERVER_HAVE_LIBSSL) && defined(__has_include)\n#if __has_include(<openssl/bn.h>)\n#include <openssl/bn.h>\n#define LIBVNCCLIENT_HAS_OPENSSL_BN 1\n#endif\n#endif"
        AVNC_ARD_AUTH_CONTENT "${AVNC_ARD_AUTH_CONTENT}")
string(REPLACE
        "#if defined(__APPLE__) && defined(LIBVNCCLIENT_APPLE_HAS_OPENSSL_BN)"
        "#if defined(LIBVNCCLIENT_HAS_OPENSSL_BN)"
        AVNC_ARD_AUTH_CONTENT "${AVNC_ARD_AUTH_CONTENT}")
string(REPLACE
        "#if !defined(__APPLE__) || !defined(LIBVNCCLIENT_APPLE_HAS_OPENSSL_BN)"
        "#if !defined(LIBVNCCLIENT_HAS_OPENSSL_BN)"
        AVNC_ARD_AUTH_CONTENT "${AVNC_ARD_AUTH_CONTENT}")
file(WRITE ${AVNC_ARD_AUTH_SOURCE} "${AVNC_ARD_AUTH_CONTENT}")

set(WITH_LIBSSH2 OFF CACHE BOOL "Find LibSSH" FORCE)

add_subdirectory(${AVNC_LIBVNC_SRC_DIR} ${AVNC_LIBVNC_BUILD_DIR})  # (source dir, build dir)

include_directories(${AVNC_LIBVNC_SRC_DIR}/include ${AVNC_LIBVNC_BUILD_DIR}/include)


###############################################################################
# Native VNC
#
# It contains implementation of JNI native methods, some NDK scaffolding and
# some helpers for OpenGL ES rendering. This is the library loaded from Java.
###############################################################################
set(AVNC_NATIVE_SOURCE src/main/cpp/native-vnc.cpp)

add_library(native-vnc SHARED ${AVNC_NATIVE_SOURCE})

target_link_libraries(native-vnc vncclient)


# Link NDK libraries
find_library(LIB_LOG log)
target_link_libraries(native-vnc ${LIB_LOG})

find_library(LIB_GLES GLESv2)
target_link_libraries(native-vnc ${LIB_GLES})
