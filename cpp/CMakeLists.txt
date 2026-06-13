cmake_minimum_required(VERSION 3.14)
project(tcm_dispenser LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(APPLE)
    set(JAVA_HOME "$ENV{JAVA_HOME}" CACHE PATH "Java home directory")
    if(NOT JAVA_HOME)
        execute_process(COMMAND /usr/libexec/java_home
            OUTPUT_VARIABLE JAVA_HOME
            OUTPUT_STRIP_TRAILING_WHITESPACE)
    endif()
    set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include" "${JAVA_HOME}/include/darwin")
elseif(WIN32)
    set(JAVA_HOME "$ENV{JAVA_HOME}" CACHE PATH "Java home directory")
    set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include" "${JAVA_HOME}/include/win32")
else()
    set(JAVA_HOME "$ENV{JAVA_HOME}" CACHE PATH "Java home directory")
    set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include" "${JAVA_HOME}/include/linux")
endif()

message(STATUS "JAVA_HOME: ${JAVA_HOME}")
message(STATUS "JNI include dirs: ${JNI_INCLUDE_DIRS}")

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
