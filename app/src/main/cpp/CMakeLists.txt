cmake_minimum_required(VERSION 3.22.1)
project(tpan_client)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# TODO: add BoringSSL or mbedTLS for mTLS support
# find_package(OpenSSL REQUIRED)

add_library(tpan_client SHARED
    tpan_client.cpp
    socks5h_server.cpp
)

target_link_libraries(tpan_client
    android
    log
    # OpenSSL::SSL OpenSSL::Crypto   # uncomment when mTLS is added
)
