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
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -u ANativeActivity_onCreate")
target_link_libraries(qssxr native_app_glue OpenXR::openxr_loader android EGL GLESv3 log)
