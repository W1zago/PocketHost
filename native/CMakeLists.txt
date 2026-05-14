cmake_minimum_required(VERSION 3.22.1)

project("anserver_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(anserver_native SHARED
    src/main/cpp/native_bridge.cpp
    src/main/cpp/process_manager.cpp
    src/main/cpp/system_monitor.cpp
    src/main/cpp/network_manager.cpp
    src/main/cpp/file_system.cpp
    src/main/cpp/proot_manager.cpp
)

target_include_directories(anserver_native PRIVATE
    src/main/cpp/include
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(anserver_native
    ${log-lib}
    ${android-lib}
)
