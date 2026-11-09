cmake_minimum_required(VERSION 3.22.1)

project("adbpairing")

set(CMAKE_CXX_STANDARD 17)

# 只编 adb_pairing：SPAKE2 + AES-128-GCM 的 JNI 桥，供 AdbPairingClient 使用
add_library(adb SHARED adb_pairing.cpp)

target_link_libraries(adb
        log
        ${PREFAB_CRYPTO_STATIC_LIB})
