/* Quest Steam: Android's native_handle_t layout (system/core/libcutils/include/cutils/native_handle.h),
 * which Winlator's gpu_image.c uses; not part of the NDK headers. */
#pragma once

typedef struct native_handle {
    int version; /* sizeof(native_handle_t) */
    int numFds;  /* number of file descriptors at &data[0] */
    int numInts; /* number of ints at &data[numFds] */
    int data[0];
} native_handle_t;
