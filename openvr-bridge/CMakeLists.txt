cmake_minimum_required(VERSION 3.22)
project(openvr_bridge CXX)
set(CMAKE_CXX_STANDARD 17)

add_library(openvr_bridge SHARED src/openvr_bridge.cpp)
# TODO: find_package(OpenXR) via Khronos OpenXR-SDK loader for Android (Meta OpenXR Mobile SDK)
# target_link_libraries(openvr_bridge openxr_loader log android)
