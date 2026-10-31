cmake_minimum_required(VERSION 3.24)
project(gorrect_observer LANGUAGES CXX)

find_package(JNI REQUIRED COMPONENTS JVM)

add_library(gorrect_observer SHARED src/agent.cpp src/retained_observer.cpp)
target_link_libraries(gorrect_observer PRIVATE JNI::JNI)
set_target_properties(gorrect_observer PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED YES
    CXX_EXTENSIONS NO
    CXX_VISIBILITY_PRESET hidden
)

if(MSVC)
    target_compile_options(gorrect_observer PRIVATE /W4 /WX)
elseif(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(gorrect_observer PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()

install(TARGETS gorrect_observer LIBRARY DESTINATION lib RUNTIME DESTINATION lib)

include(CTest)
if(BUILD_TESTING)
    add_executable(snapshot_wire_test tests/wire_test.cpp)
    add_executable(retained_wire_test tests/retained_wire_test.cpp)
    add_executable(queue_capture_test tests/queue_capture_test.cpp)
    target_link_libraries(queue_capture_test PRIVATE JNI::JNI)
    add_executable(retained_failure_test tests/retained_failure_test.cpp src/retained_observer.cpp)
    target_link_libraries(retained_failure_test PRIVATE JNI::JNI)
    foreach(native_test snapshot_wire_test retained_wire_test retained_failure_test queue_capture_test)
        target_include_directories(${native_test} PRIVATE src)
        set_target_properties(${native_test} PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED YES CXX_EXTENSIONS NO)
        if(MSVC)
            target_compile_options(${native_test} PRIVATE /W4 /WX)
        elseif(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
            target_compile_options(${native_test} PRIVATE -Wall -Wextra -Wpedantic -Werror)
        endif()
    endforeach()
    add_test(NAME snapshot_wire COMMAND snapshot_wire_test "${CMAKE_CURRENT_SOURCE_DIR}/../protocol/src/test/resources/snapshot-v1.hex")
    add_test(NAME retained_wire COMMAND retained_wire_test
        "${CMAKE_CURRENT_SOURCE_DIR}/../protocol/src/test/resources/code-v1.hex"
        "${CMAKE_CURRENT_SOURCE_DIR}/../protocol/src/test/resources/frame-v1.hex")
    add_test(NAME queue_capture COMMAND queue_capture_test
        "${CMAKE_CURRENT_SOURCE_DIR}/../protocol/src/test/resources/queue-capture-v1.hex")
    foreach(failure load-allocation prepare-allocation class-limit method-limit jvmti-resource jvmti-failure)
        add_test(NAME retained_${failure} COMMAND retained_failure_test "${failure}"
            "${CMAKE_CURRENT_BINARY_DIR}/retained-${failure}.gcode")
    endforeach()
endif()
