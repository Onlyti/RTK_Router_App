#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
rtcm_ros_pub — bridge between rtk-router (desktop) and ROS for RTK on a u-blox/NovAtel rover.

rtk-router spawns this node on START in "ROS /rtcm" mode and:
  - pipes the relayed RTCM3 stream to this node's stdin -> node publishes rtcm_msgs/Message
    on the topic (default /rtcm) for a driver (e.g. ublox_gps) to passthrough to the receiver.
  - (optional, for VRS/면보정) this node subscribes to the receiver's position topic and feeds
    a synthesized GGA back to rtk-router on stdout (sentinel "@@GGA@@ <nmea>"), so rtk-router can
    upload it to the NTRIP caster. Without this, a VRS mount gets no corrections (no rover pos).

rtk-router itself has no ROS dependency; rospy here handles the ROS protocol.

Private params (rospy `_name:=value`):
  ~rtcm_topic (str,  default /rtcm)        RTCM output topic
  ~frame_id   (str,  default "")           Header.frame_id
  ~frame_mode (bool, default True)         True = 1 RTCM3 frame per message
  ~verify_crc (bool, default True)         drop frames failing CRC24Q (resync on noise)
  ~fix_topic  (str,  default "")           receiver position topic (empty = no GGA feedback)
  ~fix_type   (str,  default navsatfix)    navsatfix | navpvt | bestpos | nmea
  ~gga_hz     (float,default 1.0)          GGA upload rate to rtk-router

Reads raw bytes from fd 0; EOF -> clean shutdown. GGA lines go to stdout with the sentinel.
"""

import os
import sys
import threading
import time

import rospy
from std_msgs.msg import Header
from rtcm_msgs.msg import Message

RTCM3_PREAMBLE = 0xD3
GGA_SENTINEL = "@@GGA@@"


# ---------------- RTCM3 framing ----------------

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


# ---------------- GGA synthesis ----------------

def _nmea_checksum(s):
    cs = 0
    for ch in s:
        cs ^= ord(ch)
    return "%02X" % cs


def build_gga(lat, lon, alt, quality=1, sats=10, hdop=1.0, geoid=0.0):
    """Build a $GPGGA sentence (UTC now). VRS casters key on lat/lon; other fields approximate."""
    t = time.gmtime()
    hhmmss = "%02d%02d%02d.00" % (t.tm_hour, t.tm_min, t.tm_sec)
    la = abs(lat); ns = 'N' if lat >= 0 else 'S'
    lad = int(la); lam = (la - lad) * 60.0
    lo = abs(lon); ew = 'E' if lon >= 0 else 'W'
    lod = int(lo); lom = (lo - lod) * 60.0
    body = "GPGGA,%s,%02d%08.5f,%s,%03d%08.5f,%s,%d,%02d,%.1f,%.1f,M,%.1f,M,," % (
        hhmmss, lad, lam, ns, lod, lom, ew, int(quality), int(sats), hdop, alt, geoid)
    return "$" + body + "*" + _nmea_checksum(body)


class FixToGga(object):
    """Subscribe to the receiver position topic and emit GGA to stdout at gga_hz."""

    def __init__(self, topic, ftype, hz):
        self.topic = topic
        self.ftype = ftype.lower()
        self.hz = hz if hz > 0 else 1.0
        self.lock = threading.Lock()
        self.latest = None  # (lat, lon, alt, quality, sats, hdop) or a passthrough str

    def start(self):
        t = self.ftype
        try:
            if t == "navsatfix":
                from sensor_msgs.msg import NavSatFix
                rospy.Subscriber(self.topic, NavSatFix, self._navsatfix, queue_size=10)
            elif t == "navpvt":
                from ublox_msgs.msg import NavPVT
                rospy.Subscriber(self.topic, NavPVT, self._navpvt, queue_size=10)
            elif t == "bestpos":
                from novatel_oem7_msgs.msg import BESTPOS
                rospy.Subscriber(self.topic, BESTPOS, self._bestpos, queue_size=10)
            elif t == "nmea":
                from nmea_msgs.msg import Sentence
                rospy.Subscriber(self.topic, Sentence, self._nmea, queue_size=10)
            else:
                rospy.logerr("rtcm_ros_pub: unknown fix_type '%s' — GGA feedback disabled", t)
                return
        except Exception as e:  # noqa: BLE001 — message pkg may be absent
            rospy.logerr("rtcm_ros_pub: fix_type '%s' subscribe 실패 (%s) — GGA feedback disabled",
                         t, e)
            return
        rospy.loginfo("rtcm_ros_pub: GGA feedback from %s [%s] @ %.1f Hz", self.topic, t, self.hz)
        rospy.Timer(rospy.Duration(1.0 / self.hz), self._emit)

    def _set(self, val):
        with self.lock:
            self.latest = val

    def _navsatfix(self, m):
        if m.status.status < 0:  # NavSatStatus.STATUS_NO_FIX
            return
        q = 4 if m.status.status >= 2 else (2 if m.status.status >= 1 else 1)
        self._set((m.latitude, m.longitude, m.altitude, q, 10, 1.0))

    def _navpvt(self, m):
        lat = m.lat * 1e-7; lon = m.lon * 1e-7; alt = m.hMSL / 1000.0
        carr = (m.flags >> 6) & 0x03 if hasattr(m, "flags") else 0
        q = 4 if carr == 2 else (5 if carr == 1 else (1 if m.fixType >= 3 else 0))
        if q == 0:
            return
        self._set((lat, lon, alt, q, m.numSV, max(m.pDOP * 0.01, 0.1)))

    def _bestpos(self, m):
        # novatel_oem7_msgs/BESTPOS: lat, lon, hgt, num_sol_svs, sol_status, pos_type
        q = 4 if getattr(m, "pos_type", None) and m.pos_type.type in (48, 50, 56) else 1
        self._set((m.lat, m.lon, m.hgt, q, getattr(m, "num_sol_svs", 10), 1.0))

    def _nmea(self, m):
        s = m.sentence.strip()
        if "GGA" in s[:6] and s.startswith("$"):
            self._set(s)  # passthrough

    def _emit(self, _evt):
        with self.lock:
            v = self.latest
        if v is None:
            return
        gga = v if isinstance(v, str) else build_gga(*v)
        sys.stdout.write("%s %s\n" % (GGA_SENTINEL, gga))
        sys.stdout.flush()


# ---------------- main ----------------

def main():
    rospy.init_node('rtcm_ros_pub')
    topic = rospy.get_param('~rtcm_topic', '/rtcm')
    frame_id = rospy.get_param('~frame_id', '')
    frame_mode = bool(rospy.get_param('~frame_mode', True))
    verify = bool(rospy.get_param('~verify_crc', True))
    fix_topic = rospy.get_param('~fix_topic', '')
    fix_type = rospy.get_param('~fix_type', 'navsatfix')
    gga_hz = float(rospy.get_param('~gga_hz', 1.0))

    pub = rospy.Publisher(topic, Message, queue_size=100)
    rospy.loginfo("rtcm_ros_pub: stdin -> %s (frame_mode=%s verify_crc=%s)", topic, frame_mode, verify)

    if fix_topic:
        FixToGga(fix_topic, fix_type, gga_hz).start()

    n_msgs = 0
    buf = bytearray()

    def publish(payload):
        nonlocal n_msgs
        msg = Message()
        msg.header = Header()
        msg.header.stamp = rospy.Time.now()
        msg.header.frame_id = frame_id
        msg.message = list(payload)
        pub.publish(msg)
        n_msgs += 1

    while not rospy.is_shutdown():
        try:
            data = os.read(0, 65536)
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
