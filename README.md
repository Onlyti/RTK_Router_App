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

## 설치 (Installation) — Desktop (Linux/Windows)

> Android 는 APK/AAB 설치(아래 "개발 / 빌드").

### A. 받아서 설치 (prebuilt, 권장)
GitHub [Releases](https://github.com/Onlyti/RTK_Router_App/releases) 에서 받는다:
Linux `rtk-router_<버전>_amd64.deb`, Windows `rtk-router-<버전>.msi`, Android `app-release.apk`.

Linux:
```bash
sudo apt install ./rtk-router_<버전>_amd64.deb     # 의존성 자동. (dpkg -i 후 apt -f install 도 가능)
```
- `.deb` 는 **설치 패키지**다. `./*.deb` 로 직접 실행하면 셸이 아카이브를 파싱해
  `syntax error near unexpected token 'newline'` / `` `!<arch>' `` 에러가 난다 → 반드시 install 명령.
- `/opt/rtk-router/` 에 설치되고 `/usr/local/bin/rtk-router`(+`rtk-router-cli`) symlink 가
  자동 생성되어 PATH 로 바로 실행된다(제거 시 정리).

### B. 소스에서 빌드해 설치 (서버 / 헤드리스)
Releases 를 못 쓰거나 서버에서 직접 빌드할 때. JDK 17 만 있으면 된다(없으면 sudo 없이 홈에 받음):
```bash
git clone https://github.com/Onlyti/RTK_Router_App.git rtk-router && cd rtk-router

# JDK17 확보 (이미 있으면 생략)
if ! ~/.jdks/jdk-17/bin/javac -version 2>/dev/null; then
  mkdir -p ~/.jdks && ( cd ~/.jdks &&
    curl -fsSL -o jdk17.tgz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" &&
    tar xzf jdk17.tgz && mv jdk-17* jdk-17 && rm jdk17.tgz )
fi

# .deb 빌드 → 설치
JAVA_HOME=~/.jdks/jdk-17 ./gradlew :desktopApp:packageReleaseDeb \
  -Porg.gradle.java.installations.paths=$HOME/.jdks/jdk-17 --no-daemon
sudo apt install -y ./desktopApp/build/compose/binaries/main-release/deb/rtk-router_*_amd64.deb
```

### C. 실행
- GUI: 앱 메뉴 "RTK Router" 또는 터미널 `rtk-router`.
- 헤드리스(서버): `rtk-router-cli` — GUI 가 저장한 `~/.config/rtk-router/settings.json` 을 읽어 실행.
  ```bash
  rtk-router-cli                 # 기본 설정 경로. -c <path> 로 지정, --help 참고
  ```
  설정 JSON 은 GUI 로 한번 만들어(자동 저장) 서버에 복사하거나 직접 작성한다.
- ROS `/rtcm` 출력 모드는 [ros/README.md](ros/README.md) 참고 (ROS source 된 환경에서 실행).
- 제거: `sudo apt remove rtk-router`.

플랫폼별 상세(시리얼 권한·Windows 단축아이콘·디바이스 포트/baud): [desktopApp/README.md](desktopApp/README.md).

## 개발 / 빌드

### Android
- APK: `./gradlew assembleDebug` — 산출물 `app/build/outputs/apk/debug/`.
- 폰: NTRIP 설정(host/port/mount/auth) 입력 → USB 수신기 연결(OTG) → baud 선택 → START.

### Desktop (Linux & Windows)
- dev 실행: `./gradlew :desktopApp:run` (JDK 17)
- 로컬 패키지: `./gradlew :desktopApp:packageReleaseDeb` (Linux) / `packageReleaseMsi` (Windows)
- 릴리즈: 태그 `v*` push → GitHub Release 에 APK/AAB + `.deb` + `.msi` 자동 첨부
- Linux 시리얼 권한은 앱 내 pkexec/sudo 또는 `dialout` 그룹 — 상세 [desktopApp/README.md](desktopApp/README.md)

### ROS1 `/rtcm` 출력 (u-blox)
Connection 모드에 `ROS /rtcm (u-blox)` 가 있다. 선택 후 START 하면 앱이 번들된 rospy 노드를
띄워 수신 RTCM3 를 `rtcm_msgs/Message`(기본 `/rtcm`)로 떠 있는 ROS master 에 publish 한다.
본체는 ROS 의존성이 없고(ROS 모드일 때만 `python3` 실행), rospy 가 ROS 프로토콜을 처리한다.
`ublox_gps` 등이 `/rtcm` 을 subscribe → M8P RTK fix. 앱은 ROS source 된 터미널에서 실행할 것.
ROS 모드 START 시 `rtcm_msgs` 가 없으면 경고 후 pkexec 로 자동 설치한다(ROS 모드 한정).
상세·검증: [ros/README.md](ros/README.md)

#### Headless CLI (Linux 서버)
디스플레이 없는 서버용 CLI 런처가 같이 설치된다(`rtk-router-cli`, GUI 와 동일 .deb).
GUI 로 한번 설정하면 `~/.config/rtk-router/settings.json` 에 자동 저장되므로, 그 파일을 서버에
복사하거나 직접 작성해 CLI 로 실행한다(GUI 불필요).
```bash
rtk-router-cli                       # 기본 설정 경로 사용
rtk-router-cli -c /path/settings.json
rtk-router-cli --help
```
- 설정의 `connectionMode` = `RS232` / `NOVATEL_USB` / `ROS_RTCM`. ROS 모드면 ROS source 된
  환경에서 실행(rospy/rtcm_msgs 필요). 상태를 stdout 으로 주기 출력, Ctrl-C 로 종료.

## 참조
- phone-sensor-stream: 같은 Kotlin/Compose 스택, USB/GNSS(gnsstest) 코드 재사용 후보.
- NovAtel: RTKASSIST / RTCM 입력 포트 문서. u-blox: u-center, UBX-CFG.
