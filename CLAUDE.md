# rtk-router — agent 진입점

스마트폰을 RTK correction 라우터로 만드는 Android 앱. NTRIP caster(인터넷)에서 RTCM3 받아 → USB-C OTG serial → NovAtel / u-blox 수신기에 주입. 폰 = 모바일 인터넷 + USB 브리지. phone-sensor-stream(PhoneLoggingSystem) 파생.

## 작업 전 읽기
1. README.md — 데이터 흐름 · 마일스톤 · 핵심 라이브러리
2. 참조: ~/phone-sensor-stream (같은 Native Kotlin/Compose 스택, gnsstest GNSS 코드 재사용 후보)

## 데이터 흐름
NTRIP caster (RTCM3/TCP) -> [폰 NTRIP client] -> [USB serial TX] -> 수신기 RX (RTCM3 주입) -> RTK fix
역방향(선택): 수신기 NMEA GGA -> 폰 -> caster (VRS/MAC)

## 스택 / 핵심
- Android Native Kotlin (Jetpack Compose) — phone-sensor 동일
- USB Host(OTG): usb-serial-for-android (CDC-ACM / FTDI / CP210x / CH340)
- NTRIP v1/v2 client (Basic auth, Ntrip-Version 헤더, GGA 업로드)
- RTCM3 byte-stream relay (0xD3 프레이밍 검증 옵션 — 파싱 불필요, passthrough)
- baud 설정 (수신기 RTCM 포트, 보통 115200/57600/460800)

## 마일스톤 (세션이 사용자와 구체화)
- M1 USB serial 연결 + baud 설정 + byte TX/RX (수신기 인식, echo)
- M2 NTRIP client (caster 접속, RTCM3 수신, byte-rate 표시)
- M3 NTRIP -> serial 브리지 -> NovAtel / u-blox RTK fix (각각 검증)
- M4 GGA 역방향(VRS) + 상태 UI + 재연결/안정성
- M5 멀티 수신기 프로파일 + 로깅

## 빌드
- APK: ./gradlew (phone-sensor 와 동일 패턴) 또는 GitHub Actions
- 공통: ~/.claude/CLAUDE.md
