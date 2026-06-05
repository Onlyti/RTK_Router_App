# rtk-router 핸드오프

갱신 2026-06-05. 세션 라이프사이클 규율(6항목).

## 1. 목표

스마트폰을 NTRIP correction 라우터로: NTRIP caster(RTCM3) → USB-C OTG serial → GNSS 수신기
(u-blox F9P / NovAtel OEM7) 주입 → RTK fix. 폰 = 모바일 회선 + USB 브리지. 상업화 대상(비공개).

## 2. 현재 상태

동작 검증 완료 (u-blox ZED-F9P, 서울시 VRS `gnss.eseoul.go.kr:2101` / `VRS-RTCM32`):
- M1 serial(native CDC) → M2 NTRIP 수신 → M3 브리지 주입 → GGA(VRS) → **RTK float 도달**.
- FIXED 는 개활지 환경 의존(앱 무관).

빌드: GitHub Actions `Onlyti/RTK_Router_App` (main). 최신 통과 4158cdf. 로컬 SDK 없어 CI 의존.
package `com.onlyti.rtkrouter` (구 com.ailab — AILab 분리, 개인 상업 프로젝트).

## 3. 완료 (구현됨)

- NTRIP v1/v2 client (핸드셰이크, sourcetable nmea 판별, RTCM passthrough, GGA 업로드)
- USB serial bridge (usb-serial 3.10.0, 포트 인덱스 선택 = NovAtel 다중포트)
- 엔드포인트 AUTO: VRS 우선(RTCM3.2 > generic > 3.1 랭킹, CMR/CMR+ 배제), 없으면 최근접 고정국
- **국가별 preset 콤보박스** (KR: gnssdata.or.kr:2101, gnss.eseoul.go.kr:2101) — host/port 자동채움
- **다중 caster 프로파일**: profiles 리스트 UI(카드별 host/port/user/pass/mount + enable + 삭제),
  비활성은 한 줄 접기 / 선택 시 펼침. Scan(sourcetable) 은 active 프로파일 대상
- **다중망 hot-standby**: enabled 프로파일들 priority 순으로 각 sourcetable 해석, 타깃 합산
  (MAX_STREAMS=6) 병렬 접속. active 죽으면 최우선 healthy 로 즉시 전환 — 같은망=L1, 다른망=L2.
  망 전체 장애에도 warm 한 타망으로 연속 RTK (make-before-break). 각 스트림 backoff 자동재연결.
- **VRS 자동감지**: GGA 토글 제거. AUTO=nmea, MANUAL=접속됐는데 RTCM 0byte 6s 지속+위치있음→GGA on.
  per-stream ggaActive.
- **수신기 GGA 포워딩**: VRS 업로드 GGA 를 폰 GPS 대신 수신기 자체 GGA(serial 파싱)로. quality≥1 이면
  수신기 GGA 원문, 아니면 폰 GPS 폴백. (위치권한 제거 기반)
- **이동경로 지도**: osmdroid(OSM, API key 불필요) + 최근 60s 수신기 trajectory Polyline + 마커
- byte-rate watchdog, RTCM rate 4s moving-window, 수신기 NMEA 파싱(GGA quality/pos), 라인버퍼링
- 구조화 에러(failureMode/level/reason), 데이터 사용량 표시, 로그 `adb logcat -s rtk`

## 4. 미완 (다음 할 일)

- **NovAtel 실기검증**: VID 0x09D7 `lsusb` 확인, RTCM 입력 CDC 포트(0/1/2 시도),
  `LOG GPGGA ONTIME 1`, `INTERFACEMODE USBx NOVATEL RTCM ON`. enclosure 면 USB-RS232 어댑터+baud.
- **위치권한 제거 (Play 출시용)**: 수신기 GGA 포워딩 실기검증되면 ACCESS_FINE_LOCATION +
  FOREGROUND_SERVICE_LOCATION + LocationHub 제거 → Play 심사 대폭 완화.
- **NTRIP 자동재연결 강화**: 현재 per-stream backoff 있음. chunked transfer-encoding(v2) 미처리.
- **USB detach/재연결**: 케이블 흔들림 시 자동 재open 없음(차량 필수).
- **Play 출시 절차**: 개발자 등록 $25, AAB+release 서명, 개인정보처리방침, closed test 20명/14일.
- validateRtcm3(0xD3+CRC24Q) 미구현(현재 passthrough).

## 5. 주의·함정

- **VRS=GGA 필수**(없으면 접속만 되고 RTCM 0). 자동감지로 처리되지만 폰 GPS fix 필요(야외).
- **포맷**: F9P/OEM7 은 CMR/CMR+ 불가, RTCM3 만. eseoul `VRS-RTCM31` 은 0 byte 서빙(서버측) → 32 사용.
- **native USB CDC 는 baud 무의미**(가상포트). UART 어댑터 경유 시에만 baud 일치.
- **F9P fix 안 뜨면 안테나/하늘 먼저**(quality 0=위성 자체 없음).
- **ExposedDropdownMenu/menuAnchor 는 scope 멤버 — import 금지**(CI 컴파일 에러 났던 지점).
  정적 리뷰 에이전트가 이걸 반대로 판단했음 → 이 류는 CI 결과 신뢰.
- 다중망 전환 시 물리적으로 다른 기지국이면 1005 좌표 변경 → 수신기 ambiguity 재수렴(수초). VRS 만 무재수렴.

## 6. 관련 경로

- 설계: `docs/DESIGN.md`, `docs/VERIFY.md`(F9P), 본 핸드오프
- 코어: `app/src/main/java/com/onlyti/rtkrouter/`
  - `ntrip/NtripClient.kt`(NTRIP·sourcetable·rtcmFormatRank)
  - `serial/SerialLink.kt`(USB·포트선택)
  - `service/RtkService.kt`(다중망 supervisor·watchdog·NMEA·trajectory), `service/RtkState.kt`
  - `config/{RtkConfig,CasterPresets}.kt`, `gnss/{Nmea,LocationHub}.kt`
  - `ui/{MainActivity(탭없음, 단일 스크롤),RtkViewModel,Prefs}.kt`
- 형제: `~/git/NovaGNSSConfigurator`(NovAtel 설정앱, SerialLink 공유 원본)
