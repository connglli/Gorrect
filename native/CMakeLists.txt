cmake_minimum_required(VERSION 3.24)
project(gorrect_observer LANGUAGES CXX)

find_package(JNI REQUIRED COMPONENTS JVM)

add_library(gorrect_observer SHARED src/agent.cpp)
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
    target_include_directories(snapshot_wire_test PRIVATE src)
    set_target_properties(snapshot_wire_test PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED YES CXX_EXTENSIONS NO)
    if(MSVC)
        target_compile_options(snapshot_wire_test PRIVATE /W4 /WX)
    elseif(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
        target_compile_options(snapshot_wire_test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    endif()
    add_test(NAME snapshot_wire COMMAND snapshot_wire_test "${CMAKE_CURRENT_SOURCE_DIR}/../protocol/src/test/resources/snapshot-v1.hex")
endif()
