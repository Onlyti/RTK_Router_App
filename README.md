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

## 참조
- phone-sensor-stream: 같은 Kotlin/Compose 스택, USB/GNSS(gnsstest) 코드 재사용 후보.
- NovAtel: RTKASSIST / RTCM 입력 포트 문서. u-blox: u-center, UBX-CFG.
