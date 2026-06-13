cmake_minimum_required(VERSION 3.14)
project(tcm_dispenser LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(JNI_INCLUDE_DIRS "")
if(APPLE)
    set(JAVA_HOME "$ENV{JAVA_HOME}" CACHE PATH "Java home directory")
    if(NOT JAVA_HOME)
        execute_process(COMMAND /usr/libexec/java_home
            OUTPUT_VARIABLE JAVA_HOME
            OUTPUT_STRIP_TRAILING_WHITESPACE
            ERROR_QUIET)
    endif()
    if(JAVA_HOME)
        set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include" "${JAVA_HOME}/include/darwin")
    endif()
elseif(WIN32)
    set(JAVA_HOME "$ENV{JAVA_HOME}" CACHE PATH "Java home directory")
    if(JAVA_HOME)
        set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include" "${JAVA_HOME}/include/win32")
    endif()
else()
    set(JAVA_HOME "$ENV{JAVA_HOME}" CACHE PATH "Java home directory")
    if(JAVA_HOME)
        set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include" "${JAVA_HOME}/include/linux")
    endif()
endif()

if(JNI_INCLUDE_DIRS)
    message(STATUS "JAVA_HOME: ${JAVA_HOME}")
    message(STATUS "JNI include dirs: ${JNI_INCLUDE_DIRS}")
else()
    message(WARNING "JAVA_HOME not found, generating stub jni.h for compilation verification")
    configure_file(${CMAKE_CURRENT_SOURCE_DIR}/include/jni_stub.h.in
                   ${CMAKE_CURRENT_BINARY_DIR}/jni/jni.h COPYONLY)
    set(JNI_INCLUDE_DIRS "${CMAKE_CURRENT_BINARY_DIR}/jni")
endif()

add_library(tcm_dispenser SHARED
    src/tcm_dispenser.cpp
)

target_include_directories(tcm_dispenser PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${JNI_INCLUDE_DIRS}
)

find_package(Threads REQUIRED)
target_link_libraries(tcm_dispenser PRIVATE Threads::Threads)

if(APPLE)
    set_target_properties(tcm_dispenser PROPERTIES
        OUTPUT_NAME "tcm_dispenser"
        PREFIX "lib"
        SUFFIX ".dylib"
    )
elseif(WIN32)
    set_target_properties(tcm_dispenser PROPERTIES
        OUTPUT_NAME "tcm_dispenser"
        PREFIX ""
        SUFFIX ".dll"
    )
else()
    set_target_properties(tcm_dispenser PROPERTIES
        OUTPUT_NAME "tcm_dispenser"
        PREFIX "lib"
        SUFFIX ".so"
    )
endif()

install(TARGETS tcm_dispenser
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
)
