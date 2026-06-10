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

## 설치 & 실행

플랫폼별로 본다. prebuilt 는 GitHub [Releases](https://github.com/Onlyti/RTK_Router_App/releases) 에서 받는다.
헤드리스 CLI 와 ROS `/rtcm` 모드는 **Linux 전용**.

### Android (휴대폰)
1. 받기: Releases 의 `app-release.apk` (개발 빌드는 `./gradlew assembleDebug`).
2. 설치: 폰으로 apk 옮겨 설치(설정에서 "알 수 없는 출처/이 출처 허용").
3. 실행: NTRIP 설정(host/port/mount/auth) → USB-OTG 로 수신기 연결 → baud 선택 → START.
   - 헤드리스/CLI/ROS 모드는 없음(데스크톱 전용).

### Linux (desktop)
받아서 설치:
```bash
sudo apt install ./rtk-router_<버전>_amd64.deb     # 의존성 자동 (dpkg -i 후 apt -f install 도 가능)
```
- `.deb` 는 **설치 패키지**다. `./*.deb` 직접 실행 시 셸이 아카이브를 파싱해
  `syntax error ... 'newline'` / `` `!<arch>' `` 에러 → 반드시 install 명령.
- `/opt/rtk-router/` 에 설치, `/usr/local/bin/rtk-router`(+`rtk-router-cli`) symlink 자동(제거 시 정리).

소스에서 빌드해 설치(서버/헤드리스, Releases 없이). JDK 17 만 있으면 됨(없으면 sudo 없이 홈에 받음):
```bash
git clone https://github.com/Onlyti/RTK_Router_App.git rtk-router && cd rtk-router
if ! ~/.jdks/jdk-17/bin/javac -version 2>/dev/null; then
  mkdir -p ~/.jdks && ( cd ~/.jdks &&
    curl -fsSL -o jdk17.tgz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" &&
    tar xzf jdk17.tgz && mv jdk-17* jdk-17 && rm jdk17.tgz )
fi
JAVA_HOME=~/.jdks/jdk-17 ./gradlew :desktopApp:packageReleaseDeb \
  -Porg.gradle.java.installations.paths=$HOME/.jdks/jdk-17 --no-daemon
sudo apt install -y ./desktopApp/build/compose/binaries/main-release/deb/rtk-router_*_amd64.deb
```

실행:
- GUI: 앱 메뉴 "RTK Router" 또는 터미널 `rtk-router`.
- 헤드리스(서버): `rtk-router-cli` — GUI 가 저장한 `~/.config/rtk-router/settings.json` 을 읽어 실행
  (`-c <path>` 로 지정, `--help`). 설정 JSON 은 GUI 로 한번 만들어 복사하거나 직접 작성.
- ROS `/rtcm (u-blox)` 모드: START 시 번들 rospy 노드를 띄워 RTCM3 를 `rtcm_msgs/Message`(`/rtcm`)로
  떠 있는 ROS master 에 publish(`ublox_gps` → RTK fix). ROS source 된 환경에서 실행, `rtcm_msgs`
  없으면 자동 설치. 상세·검증: [ros/README.md](ros/README.md).

시리얼 권한(`/dev/tty*`): 앱 내 pkexec/sudo 또는 `dialout` 그룹(`sudo usermod -aG dialout $USER`).
제거: `sudo apt remove rtk-router`.

### Windows (desktop)
1. 받기: Releases 의 `rtk-router-<버전>.msi`.
2. 설치: `.msi` 실행 → 설치 마법사(단축아이콘 체크박스 모두 체크 권장).
3. 실행: 시작메뉴 "RTK Router" 또는 `C:\Program Files\rtk-router\rtk-router.exe`.
4. 시리얼: USB-UART / u-blox CDC 드라이버 설치 후 장치관리자에서 `COM*` 번호 확인. 별도 권한 불필요.
   - ROS 모드·헤드리스 CLI 없음(Linux 전용).

플랫폼 상세(Windows 단축아이콘 트러블슈팅·디바이스 포트/baud): [desktopApp/README.md](desktopApp/README.md).

## 개발 / 빌드 (소스)
- Android APK: `./gradlew assembleDebug` (산출물 `app/build/outputs/apk/debug/`).
- Desktop dev 실행: `./gradlew :desktopApp:run` (JDK 17).
- 로컬 패키지: `./gradlew :desktopApp:packageReleaseDeb` (Linux) / `packageReleaseMsi` (Windows).
- 릴리즈: 태그 `v*` push → GitHub Release 에 APK/AAB + `.deb` + `.msi` 자동 첨부.

## 참조
- phone-sensor-stream: 같은 Kotlin/Compose 스택, USB/GNSS(gnsstest) 코드 재사용 후보.
- NovAtel: RTKASSIST / RTCM 입력 포트 문서. u-blox: u-center, UBX-CFG.
