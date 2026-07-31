cmake_minimum_required(VERSION 3.25)

project(DroidFS)

add_library(memfile SHARED src/main/native/memfile.c)
target_link_libraries(memfile log)

add_library(
        avformat
        SHARED
        IMPORTED
)

set_target_properties(
        avformat
        PROPERTIES IMPORTED_LOCATION
        ${PROJECT_SOURCE_DIR}/ffmpeg/build/${ANDROID_ABI}/libavformat/libavformat.so
)

add_library(
        avcodec
        SHARED
        IMPORTED
)

set_target_properties(
        avcodec
        PROPERTIES IMPORTED_LOCATION
        ${PROJECT_SOURCE_DIR}/ffmpeg/build/${ANDROID_ABI}/libavcodec/libavcodec.so
)

add_library(
        avutil
        SHARED
        IMPORTED
)

set_target_properties(
        avutil
        PROPERTIES IMPORTED_LOCATION
        ${PROJECT_SOURCE_DIR}/ffmpeg/build/${ANDROID_ABI}/libavutil/libavutil.so
)

add_library(
        mux
        SHARED
        src/main/native/libmux.c
)

target_include_directories(mux PRIVATE ${PROJECT_SOURCE_DIR}/ffmpeg/build/${ANDROID_ABI})

target_link_libraries(
        mux
        avformat
        avcodec
        avutil
        log
)
