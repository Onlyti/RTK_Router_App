# rtk-router

스마트폰을 RTK correction 라우터로 만드는 Android 앱. NTRIP caster 의 RTCM3 보정신호를
USB-C OTG serial 로 NovAtel / u-blox 수신기에 주입해 RTK fix 를 얻는다. 폰이 모바일 인터넷
회선 + USB 브리지 역할을 한다. phone-sensor-stream(PhoneLoggingSystem) 파생.

## 데이터 흐름
```
NTRIP caster ──RTCM3/TCP──▶ [폰 NTRIP client] ──▶ [USB serial TX] ──▶ 수신기 RX ──▶ RTK fix
                                                          ▲
수신기 NMEA GGA ──(역방향, 선택: VRS)──────────────────────┘ ──▶ caster 로 위치 업로드
```

## 핵심 기술
- Android USB Host (OTG): `usb-serial-for-android` (mik3y) — CDC-ACM / FTDI / CP210x / CH340.
  NovAtel OEM7 USB(CDC), u-blox(CDC/FTDI) 인식.
- NTRIP client: v1.0/v2.0, HTTP-like 핸드셰이크, Basic auth, mountpoint, GGA 주기 업로드.
- RTCM3 relay: caster byte stream 을 serial 로 그대로 전달(passthrough). 0xD3 프레임 검증은 옵션.
- 상태 모니터: NTRIP 연결 / RTCM byte-rate / serial 연결 / (NMEA 역파싱 시) fix 상태.

## 마일스톤
| M | 목표 | 완료기준(정량) |
|---|---|---|
| M1 | USB serial 연결 | 수신기 인식 + baud 설정 + byte TX/RX echo 확인 |
| M2 | NTRIP client | caster 접속 + RTCM3 수신, byte-rate > 0 표시 |
| M3 | 브리지 | NTRIP→serial 주입으로 NovAtel·u-blox 각각 RTK FIX 달성 |
| M4 | 안정화 | GGA(VRS) 역방향 + 상태 UI + 자동 재연결 |
| M5 | 운영 | 수신기 프로파일 다중 + 세션 로깅 |

## 빌드 / 실행

### Android
- APK: `./gradlew assembleDebug` — 산출물 `app/build/outputs/apk/debug/`.
- 폰: NTRIP 설정(host/port/mount/auth) 입력 → USB 수신기 연결(OTG) → baud 선택 → START.

### Desktop (Linux & Windows)
- 실행: `./gradlew :desktopApp:run` (JDK 17)
- Linux: `/dev/ttyACM0`, `/dev/ttyUSB0` — 시리얼 권한은 앱 내 pkexec/sudo 또는 `dialout` 그룹
- Windows: `COM*` 포트 선택 (드라이버 설치 후)
- 릴리즈: 태그 `v*` push → GitHub Release에 APK/AAB + `.deb` + `.msi` 자동 첨부
- 로컬 패키지: `./gradlew :desktopApp:packageReleaseDeb` (Linux) / `packageReleaseMsi` (Windows)
- 상세: [desktopApp/README.md](desktopApp/README.md)

#### Linux `.deb` 설치 / 실행
`.deb` 는 실행 파일이 아니라 **설치 패키지**다. `./rtk-router_*.deb` 처럼 직접 실행하면
셸이 아카이브를 스크립트로 해석해 `syntax error near unexpected token 'newline'` /
`` `!<arch>' `` 에러가 난다(이건 정상 동작 — 설치 명령을 써야 한다).
```bash
sudo apt install ./rtk-router_1.0.6-1_amd64.deb     # 권장 (의존성 자동 처리)
#   또는
sudo dpkg -i rtk-router_1.0.6-1_amd64.deb
sudo apt -f install                                  # dpkg 가 의존성 부족 시 보충
```
설치 본체는 `/opt/rtk-router/` 에 들어가고, 설치 시 `/usr/local/bin/rtk-router` 심볼릭이
자동 생성되어 PATH 로 바로 실행된다(제거 시 자동 정리). GUI 데스크톱이면 애플리케이션 메뉴의
"RTK Router" 로도 실행된다:
```bash
rtk-router                                           # 설치 후 PATH 로 실행
#   (전체 경로: /opt/rtk-router/bin/rtk-router)
sudo apt remove rtk-router                            # 제거
```

### ROS1 `/rtcm` 출력 (u-blox)
Connection 모드에 `ROS /rtcm (u-blox)` 가 있다. 선택 후 START 하면 앱이 번들된 rospy 노드를
띄워 수신 RTCM3 를 `rtcm_msgs/Message`(기본 `/rtcm`)로 떠 있는 ROS master 에 publish 한다.
본체는 ROS 의존성이 없고(ROS 모드일 때만 `python3` 실행), rospy 가 ROS 프로토콜을 처리한다.
`ublox_gps` 등이 `/rtcm` 을 subscribe → M8P RTK fix. 앱은 ROS source 된 터미널에서 실행할 것.
상세·검증: [ros/README.md](ros/README.md)

## 참조
- phone-sensor-stream: 같은 Kotlin/Compose 스택, USB/GNSS(gnsstest) 코드 재사용 후보.
- NovAtel: RTKASSIST / RTCM 입력 포트 문서. u-blox: u-center, UBX-CFG.
