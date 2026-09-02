# For more information on using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples of CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

project("chameleon")

# 主库：JNI 桥接 + Crypto1 算法（移植自 ChameleonUltra 上位机 software/src）
add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        crypto1/crypto1.c
        crypto1/crapto1.c
        crypto1/bucketsort.c
        crypto1/parity.c
        crypto1/nested_util.c)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log)
