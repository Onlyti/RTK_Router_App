# rtk-router ROS bridge (`/rtcm` 출력)

rtk-router 데스크톱(Linux) 버전이 수신/relay 하는 RTCM3 스트림을 ROS1 토픽
`rtcm_msgs/Message`(기본 `/rtcm`)으로 내보내기 위한 **분리된 브리지 노드**.

설계 원칙: rtk-router 본체(Kotlin/JVM)에는 ROS 의존성이 전혀 없다. 본체는 RTCM3 바이트
스트림을 로컬 TCP 서버로 미러링만 하고(순수 JDK 소켓, opt-in·기본 off), ROS를 아는 부분은
이 디렉토리의 얇은 Python 노드뿐이다. → ROS 미설치 환경에서도 rtk-router 빌드·실행이 깨지지 않음.

```
NTRIP caster ──> rtk-router (NTRIP client)
                     │  (기존 경로 그대로 재사용)
                     ├──> USB/serial 수신기   (옵션, 기존 기능)
                     └──> TCP :8531 미러       (옵션, 신규, 기본 off)
                                │
                                ▼
                    rtcm_tcp_bridge.py  ──publish──>  /rtcm (rtcm_msgs/Message)
                                                          │
                                                          ▼
                                              ublox_gps (subscribe /rtcm)
                                              → ttyACM* 단독 점유, RTCM passthrough
                                              → u-blox M8P RTK fix
```

## 1. rtk-router 쪽 설정

1. 데스크톱 앱 실행 → "ROS /rtcm 출력 (TCP 미러)" 스위치 ON.
2. TCP port 확인(기본 `8531`).
3. 시리얼 동작 분기:
   - rodea처럼 `ublox_gps`가 수신기 포트(ttyACM1)를 단독 점유 → **시리얼 포트를 비워두면** TCP 전용으로 동작.
   - 같은 수신기에 직접 시리얼 + ROS 동시 출력도 가능(포트 선택 + 스위치 ON → 둘 다 송신).
4. NTRIP 입력(caster/mount/계정)은 기존 방식 그대로 설정 후 START.

## 2. 브리지 노드 빌드 / 실행

전제: `ros-noetic-rtcm-msgs` 설치.
```bash
sudo apt install ros-noetic-rtcm-msgs
```

### catkin 패키지로 (권장)
```bash
cp -r rtk-router/ros/rtcm_tcp_bridge ~/catkin_ws/src/
cd ~/catkin_ws && catkin_make        # 또는 catkin build
source devel/setup.bash

roslaunch rtcm_tcp_bridge rtcm_tcp_bridge.launch \
    tcp_host:=127.0.0.1 tcp_port:=8531 rtcm_topic:=/rtcm
```

### 빠른 실행 (패키지 빌드 없이)
`rtcm_msgs`만 환경에 있으면 스크립트 단독 실행 가능:
```bash
source /opt/ros/noetic/setup.bash      # rtcm_msgs 가 보이는 워크스페이스 source
python3 rtk-router/ros/rtcm_tcp_bridge/scripts/rtcm_tcp_bridge.py \
    _tcp_host:=127.0.0.1 _tcp_port:=8531 _rtcm_topic:=/rtcm
```

## 파라미터

| param | default | 설명 |
|---|---|---|
| `~tcp_host` | `127.0.0.1` | rtk-router 호스트 (다른 PC면 그 IP) |
| `~tcp_port` | `8531` | rtk-router RTCM TCP 포트 |
| `~rtcm_topic` | `/rtcm` | 출력 토픽 이름 |
| `~frame_id` | `""` | Header.frame_id (비워도 됨) |
| `~frame_mode` | `true` | true=RTCM3 1프레임당 1메시지, false=받은 TCP chunk 그대로 |
| `~verify_crc` | `true` | CRC24Q 실패 프레임 폐기(노이즈 시 resync) |
| `~reconnect_sec` | `2.0` | TCP 재접속 간격(초) |

프레이밍: `~frame_mode=true`면 0xD3 preamble + 10-bit length + CRC24Q 경계로 잘라
"1프레임=1메시지"로 publish (ublox_gps가 프레임 단위 write 하기 좋음). CRC로 false
preamble을 걸러 byte 단위 resync 한다.

## 3. 검증

메시지 흐름·프레임 경계:
```bash
rostopic hz /rtcm
rostopic echo -n1 /rtcm        # message[]가 0xD3(=211)로 시작하는지 확인
```

RTK fix (보정원 연결 상태에서):
```bash
roslaunch ublox_gps ublox_device.launch param_file_name:=c94_m8p_rover
# rtcm_tcp_bridge가 /rtcm publish 중이어야 함
rostopic echo /ublox/navpvt/flags     # carrSoln 비트: 0(none) → 1(float) → 2(fixed)
```
`carrSoln`이 0→1→2로 올라가면 성공.

## 주의: serial-less(TCP 전용) 모드와 VRS/GGA

시리얼 포트를 비워 TCP 전용으로 돌리면 rtk-router는 수신기 NMEA(GGA)를 받을 경로가 없다.
따라서 GGA 업로드가 필요한 **VRS/면보정(FKP) mount는 보정이 안 내려올 수 있다**(caster가 대략
위치를 모름). 대응:

- 권장: GGA 불필요한 single-base/고정국 mount 사용 (NGII 고정국, RTK2GO 등). 다수의 NGII
  관측소-RTCM32 mount는 GGA 없이 동작.
- VRS를 꼭 써야 하면: (a) 시리얼 입력을 유지(수신기 NMEA가 rtk-router로 되돌아오게)하거나,
  (b) 정적 GGA(수동 위경도) 업로드 기능이 필요 — 현재 미구현. 필요 시 추가 가능.

ublox_gps 가 포트를 단독 점유하는 rodea 구성에서는 (a)가 불가하므로, VRS 대신 고정국 mount를
쓰거나 정적 GGA 기능을 추가하는 방향.

## 참고
- rtk-router를 쓰지 않는 fallback은 `ntrip_client` ROS 패키지(NTRIP→/rtcm 직접). 본 구성은
  rtk-router(멀티 caster hot-standby·failover·NovAtel 설정 등)를 그 자리에 자산으로 넣는 것.
- TCP 미러는 wildcard 바인드 → 브리지를 rtk-router와 다른 PC에서 돌려도 됨. RTCM 보정
  스트림은 비밀은 아니지만 신뢰 네트워크에서만 노출할 것.
