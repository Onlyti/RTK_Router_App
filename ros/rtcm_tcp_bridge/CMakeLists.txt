cmake_minimum_required(VERSION 3.0.2)
project(rtcm_tcp_bridge)

find_package(catkin REQUIRED COMPONENTS
  rospy
  std_msgs
  rtcm_msgs
)

catkin_package()

catkin_install_python(PROGRAMS
  scripts/rtcm_tcp_bridge.py
  DESTINATION ${CATKIN_PACKAGE_BIN_DESTINATION}
)
