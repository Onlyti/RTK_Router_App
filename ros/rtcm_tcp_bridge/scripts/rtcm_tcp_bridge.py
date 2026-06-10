#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
rtcm_tcp_bridge — connect to rtk-router's RTCM TCP mirror and republish on ROS /rtcm.

rtk-router (desktop) emits the relayed RTCM3 byte stream on a local TCP server when
"ROS /rtcm 출력" is enabled (default off). This node connects to that server, splits the
octet stream on RTCM3 frame boundaries (preamble 0xD3 + 10-bit length + CRC24Q), and
publishes one rtcm_msgs/Message per frame so a driver such as KumarRobotics ublox_gps can
passthrough each frame to the receiver.

This is the ONLY ROS-aware component. rtk-router itself has no ROS dependency.

Params (private ~):
  ~tcp_host   (str,  default 127.0.0.1)  rtk-router host
  ~tcp_port   (int,  default 8531)       rtk-router RTCM TCP port
  ~rtcm_topic (str,  default /rtcm)      output topic
  ~frame_id   (str,  default "")         Header.frame_id (may be empty)
  ~frame_mode (bool, default True)       True = 1 RTCM3 frame per message; False = raw chunk
  ~verify_crc (bool, default True)       drop frames whose CRC24Q fails (resync on noise)
  ~reconnect_sec (float, default 2.0)    delay between TCP reconnect attempts
"""

import socket
import time

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
    """
    Pull complete RTCM3 frames out of bytearray `buf`.
    Returns (list_of_frame_bytes, leftover_bytearray).
    On a bad CRC (false preamble inside payload/noise) advance one byte and resync.
    """
    frames = []
    idx = 0
    n = len(buf)
    while True:
        # seek next preamble
        while idx < n and buf[idx] != RTCM3_PREAMBLE:
            idx += 1
        if idx >= n:
            return frames, bytearray()          # nothing usable left
        if n - idx < 3:
            return frames, bytearray(buf[idx:])  # need length bytes
        length = ((buf[idx + 1] & 0x03) << 8) | buf[idx + 2]
        frame_len = 3 + length + 3               # header(3) + payload + CRC24Q(3)
        if n - idx < frame_len:
            return frames, bytearray(buf[idx:])  # frame not fully arrived yet
        frame = buf[idx:idx + frame_len]
        if verify:
            got = (frame[-3] << 16) | (frame[-2] << 8) | frame[-1]
            if crc24q(frame[:-3]) != got:
                idx += 1                         # false preamble; resync
                continue
        frames.append(bytes(frame))
        idx += frame_len


class RtcmTcpBridge(object):
    def __init__(self):
        self.host = rospy.get_param('~tcp_host', '127.0.0.1')
        self.port = int(rospy.get_param('~tcp_port', 8531))
        self.topic = rospy.get_param('~rtcm_topic', '/rtcm')
        self.frame_id = rospy.get_param('~frame_id', '')
        self.frame_mode = bool(rospy.get_param('~frame_mode', True))
        self.verify = bool(rospy.get_param('~verify_crc', True))
        self.reconnect_sec = float(rospy.get_param('~reconnect_sec', 2.0))
        self.pub = rospy.Publisher(self.topic, Message, queue_size=100)
        self.n_msgs = 0
        rospy.loginfo(
            "rtcm_tcp_bridge: %s:%d -> %s (frame_mode=%s verify_crc=%s)",
            self.host, self.port, self.topic, self.frame_mode, self.verify,
        )

    def publish(self, payload):
        msg = Message()
        msg.header = Header()
        msg.header.stamp = rospy.Time.now()
        msg.header.frame_id = self.frame_id
        msg.message = list(payload)   # uint8[]
        self.pub.publish(msg)
        self.n_msgs += 1
        if self.n_msgs % 100 == 0:
            rospy.loginfo_throttle(5.0, "rtcm_tcp_bridge: %d messages published", self.n_msgs)

    def run(self):
        while not rospy.is_shutdown():
            try:
                sock = socket.create_connection((self.host, self.port), timeout=5.0)
            except OSError as e:
                rospy.logwarn_throttle(10.0, "rtcm_tcp_bridge: connect %s:%d failed (%s) — 재시도",
                                       self.host, self.port, e)
                time.sleep(self.reconnect_sec)
                continue

            rospy.loginfo("rtcm_tcp_bridge: connected to %s:%d", self.host, self.port)
            sock.settimeout(1.0)
            buf = bytearray()
            try:
                while not rospy.is_shutdown():
                    try:
                        data = sock.recv(4096)
                    except socket.timeout:
                        continue
                    if not data:
                        rospy.logwarn("rtcm_tcp_bridge: server closed connection")
                        break
                    if not self.frame_mode:
                        self.publish(data)
                        continue
                    buf.extend(data)
                    frames, buf = extract_frames(buf, self.verify)
                    for f in frames:
                        self.publish(f)
            except OSError as e:
                rospy.logwarn("rtcm_tcp_bridge: socket error (%s)", e)
            finally:
                try:
                    sock.close()
                except OSError:
                    pass
            time.sleep(self.reconnect_sec)


def main():
    rospy.init_node('rtcm_tcp_bridge')
    RtcmTcpBridge().run()


if __name__ == '__main__':
    try:
        main()
    except rospy.ROSInterruptException:
        pass
