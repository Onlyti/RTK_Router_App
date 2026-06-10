# rtk-router ROS 출력 (`/rtcm`)

rtk-router 데스크톱(Linux)의 Connection 모드 **"ROS /rtcm (u-blox)"** 동작 설명.

이 모드로 START 하면 앱이 번들된 rospy 노드(`rtcm_ros_pub.py`)를 자식 프로세스로 띄우고,
수신한 RTCM3 스트림을 그 노드의 stdin 으로 흘린다. 노드는 떠 있는 ROS master 에 붙어
RTCM3 프레임(0xD3 + 10-bit length + CRC24Q)마다 `rtcm_msgs/Message` 를 publish 한다.
별도 TCP 미러나 수동 roslaunch 가 없다.

설계 원칙: rtk-router 본체(Kotlin/JVM)에는 ROS 의존성이 없다. ROS 모드일 때만 외부 `python3`
를 실행할 뿐이고, ROS 프로토콜(master 등록·TCPROS·md5sum)은 그 안의 rospy 가 처리한다.
→ ROS 미설치 환경에서도 앱 빌드·실행이 깨지지 않는다.

```
NTRIP caster ──> rtk-router (NTRIP client)
                     │  (기존 입력 경로 재사용)
                     └─[stdin]─> rtcm_ros_pub.py (rospy)
                                     └─publish─> /rtcm (rtcm_msgs/Message)
                                                     └─ ublox_gps(subscribe) ─> M8P RTK fix
```

## 사용

1. 앱을 ROS 가 source 된 터미널에서 실행한다(노드가 `ROS_MASTER_URI`·rospy·rtcm_msgs 를
   inherit 하도록):
   ```bash
   source /opt/ros/noetic/setup.bash        # 또는 catkin 워크스페이스의 devel/setup.bash
   sudo apt install ros-noetic-rtcm-msgs    # 최초 1회
   /opt/rtk-router/bin/rtk-router           # 설치본. 또는 ./gradlew :desktopApp:run
   ```
2. Connection = "ROS /rtcm (u-blox)" 선택 → topic(기본 `/rtcm`)·frame_id(선택) 입력.
3. NTRIP(caster/mount/계정)은 기존대로 설정 후 START.
   - 앱이 `rtcm_ros_pub.py` 를 띄우고, 상태 카드에 "ROS node: running → /rtcm" 표시.

노드 스크립트 원본: `desktopApp/src/main/resources/ros/rtcm_ros_pub.py`
(앱이 실행 시 임시 파일로 추출해 `python3` 로 spawn).

### 노드 단독 실행(디버그용)
RTCM 바이트를 stdin 으로 직접 흘려 테스트:
```bash
source /opt/ros/noetic/setup.bash
some_rtcm_source | python3 desktopApp/src/main/resources/ros/rtcm_ros_pub.py _rtcm_topic:=/rtcm
```

## 파라미터 (rospy `_name:=value`)

| param | default | 설명 |
|---|---|---|
| `~rtcm_topic` | `/rtcm` | 출력 토픽 |
| `~frame_id` | `""` | Header.frame_id |
| `~frame_mode` | `true` | true=RTCM3 1프레임/메시지, false=받은 chunk 그대로 |
| `~verify_crc` | `true` | CRC24Q 실패 프레임 폐기(노이즈 resync) |

## 검증

```bash
rostopic hz /rtcm
rostopic echo -n1 /rtcm        # message[] 가 211(0xD3) 로 시작?

roslaunch ublox_gps ublox_device.launch param_file_name:=c94_m8p_rover
rostopic echo /ublox/navpvt/flags   # carrSoln: 0(none) → 1(float) → 2(fixed)
```

## 주의

- 앱이 ROS source 안 된 환경(예: GUI 메뉴 클릭)에서 실행되면 `python3`/rospy 를 못 찾아
  노드 spawn 이 실패한다 → 상태 카드에 에러 표시. 터미널에서 source 후 실행할 것.
- VRS/FKP 처럼 GGA 업로드가 필요한 mount 는, 이 모드에 수신기 NMEA 역류 경로가 없어 보정이
  안 내려올 수 있다 → 고정국 mount 사용 권장.
- fallback(앱 미사용): `ntrip_client` ROS 패키지(NTRIP→/rtcm 직접). 본 모드는 rtk-router
  (멀티 caster hot-standby·failover·NovAtel 설정)를 그 자리에 넣는 것.
