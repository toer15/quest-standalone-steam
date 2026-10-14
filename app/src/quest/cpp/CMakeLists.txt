cmake_minimum_required(VERSION 3.22.1)
project(qssxr C CXX)

# Quest-only immersive VR activity: OpenXR (Khronos loader via prefab) + GLES3.
find_package(OpenXR REQUIRED CONFIG)

add_library(native_app_glue STATIC ${ANDROID_NDK}/sources/android/native_app_glue/android_native_app_glue.c)
target_include_directories(native_app_glue PUBLIC ${ANDROID_NDK}/sources/android/native_app_glue)

add_library(qssxr SHARED qss_xr.cpp)
set_target_properties(qssxr PROPERTIES CXX_STANDARD 17)
target_include_directories(qssxr PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../openvr-bridge/include)
# Keep ANativeActivity_onCreate from native_app_glue.
target_link_options(qssxr PRIVATE -u ANativeActivity_onCreate)
target_link_libraries(qssxr native_app_glue OpenXR::openxr_loader android EGL GLESv3 log)

# The game window: native parts of Winlator's X server (LGPL-2.1, github.com/brunodev85/winlator v10.1.0),
# used by com.winlator.* (client sockets + epoll, SysV shared memory, drawables, GPU images).
set(WINLATOR_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp/winlator)
add_library(winlator SHARED
            ${WINLATOR_SRC}/drawable.c
            ${WINLATOR_SRC}/gpu_image.c
            ${WINLATOR_SRC}/sysvshared_memory.c
            ${WINLATOR_SRC}/xconnector_epoll.c)
target_include_directories(winlator PRIVATE ${WINLATOR_SRC})
target_compile_options(winlator PRIVATE -O2 -Wno-unused-function -Wimplicit-function-declaration)
target_link_libraries(winlator log android jnigraphics EGL GLESv2 GLESv3 dl)
