project(uvc-custom-network)

# 1. Find FFmpeg components needed for H.264 decoding
find_package(FFmpeg REQUIRED COMPONENTS avcodec avutil)

# 2. Define the source files
set(uvc-custom-network_SOURCES
    uvc_custom_network.cpp
    uvc_custom_network.h
    net_discovery.cpp
    net_discovery.h
)

# 3. Create the module
add_library(uvc-custom-network MODULE
    ${uvc-custom-network_SOURCES}
)

# 3. Link OBS Core Library + FFmpeg
target_link_libraries(uvc-custom-network
    PRIVATE
        libobs
        FFmpeg::avcodec
        FFmpeg::avutil
)

# 4. Handle Platform-Specific Networking Libraries
if(WIN32)
    # Windows needs WinSock 2 for the networking code in net_discovery.cpp
    target_link_libraries(uvc-custom-network
        PRIVATE
            ws2_32
    )
    
    # Ensure we use the correct subsystem for Windows
    set_target_properties(uvc-custom-network PROPERTIES 
        PREFIX ""
    )
endif()

# 5. Setup Installation path (standard OBS plugin structure)
if(APPLE)
    set_target_properties(uvc-custom-network PROPERTIES 
        LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/rundir/${CMAKE_BUILD_TYPE}/obs-plugins"
    )
elseif(WIN32)
    set_target_properties(uvc-custom-network PROPERTIES 
        LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/rundir/${CMAKE_BUILD_TYPE}/bin/64bit"
    )
endif()

# 6. Localization/Data helper (if you have folder 'data' for locales)
# install_obs_plugin_with_data(uvc-custom-network data)