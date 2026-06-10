#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
rtcm_ros_pub — read an RTCM3 byte stream from stdin and publish it on ROS /rtcm.

rtk-router (desktop) spawns this node on START when the connection mode is
"ROS /rtcm (u-blox)" and pipes the relayed RTCM3 stream to its stdin. The node
connects to the already-running ROS master (from the inherited ROS_MASTER_URI),
splits the stream on RTCM3 frame boundaries (preamble 0xD3 + 10-bit length +
CRC24Q) and publishes one rtcm_msgs/Message per frame, so a driver like
KumarRobotics ublox_gps can passthrough each frame to the receiver.

This is the only ROS-aware component; rtk-router itself has no ROS dependency and
only launches this script in ROS mode. rospy handles the ROS protocol (md5sum,
TCPROS, reconnect), so correctness does not depend on hand-rolled networking.

Private params (rospy `_name:=value` argv):
  ~rtcm_topic (str,  default /rtcm)
  ~frame_id   (str,  default "")
  ~frame_mode (bool, default True)   True = 1 RTCM3 frame per message
  ~verify_crc (bool, default True)   drop frames failing CRC24Q (resync on noise)

Requires rospy + rtcm_msgs on PYTHONPATH (i.e. the app must be launched from a
ROS-sourced environment). Reads raw bytes from fd 0; EOF -> clean shutdown.
"""

import os
import sys

import rospy
from std_msgs.msg import Header
from rtcm_msgs.msg import Message

RTCM3_PREAMBLE = 0xD3


def crc24q(data):
    """RTCM3 / Qualcomm CRC-24Q, poly 0x1864CFB, init 0, MSB-first, no final xor."""
    crc = 0
    for b in data:
        crc ^= (b << 16)
        for _ in range(8):
            crc <<= 1
            if crc & 0x1000000:
                crc ^= 0x1864CFB
    return crc & 0xFFFFFF


def extract_frames(buf, verify):
    """Pull complete RTCM3 frames out of bytearray buf. Returns (frames, leftover)."""
    frames = []
    idx = 0
    n = len(buf)
    while True:
        while idx < n and buf[idx] != RTCM3_PREAMBLE:
            idx += 1
        if idx >= n:
            return frames, bytearray()
        if n - idx < 3:
            return frames, bytearray(buf[idx:])
        length = ((buf[idx + 1] & 0x03) << 8) | buf[idx + 2]
        frame_len = 3 + length + 3
        if n - idx < frame_len:
            return frames, bytearray(buf[idx:])
        frame = buf[idx:idx + frame_len]
        if verify:
            got = (frame[-3] << 16) | (frame[-2] << 8) | frame[-1]
            if crc24q(frame[:-3]) != got:
                idx += 1
                continue
        frames.append(bytes(frame))
        idx += frame_len


def main():
    rospy.init_node('rtcm_ros_pub')
    topic = rospy.get_param('~rtcm_topic', '/rtcm')
    frame_id = rospy.get_param('~frame_id', '')
    frame_mode = bool(rospy.get_param('~frame_mode', True))
    verify = bool(rospy.get_param('~verify_crc', True))
    pub = rospy.Publisher(topic, Message, queue_size=100)
    rospy.loginfo("rtcm_ros_pub: stdin -> %s (frame_mode=%s verify_crc=%s)",
                  topic, frame_mode, verify)

    n_msgs = 0
    buf = bytearray()
    fd = 0  # stdin

    def publish(payload):
        nonlocal n_msgs
        msg = Message()
        msg.header = Header()
        msg.header.stamp = rospy.Time.now()
        msg.header.frame_id = frame_id
        msg.message = list(payload)
        pub.publish(msg)
        n_msgs += 1
        if n_msgs % 100 == 0:
            rospy.loginfo_throttle(5.0, "rtcm_ros_pub: %d messages published", n_msgs)

    while not rospy.is_shutdown():
        try:
            data = os.read(fd, 65536)
        except OSError as e:
            rospy.logwarn("rtcm_ros_pub: stdin read error (%s)", e)
            break
        if not data:
            rospy.loginfo("rtcm_ros_pub: stdin EOF — shutting down")
            break
        if not frame_mode:
            publish(data)
            continue
        buf.extend(data)
        frames, buf = extract_frames(buf, verify)
        for f in frames:
            publish(f)


if __name__ == '__main__':
    try:
        main()
    except rospy.ROSInterruptException:
        pass
