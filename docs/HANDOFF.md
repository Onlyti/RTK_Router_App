# rtk-router 핸드오프

작성 2026-06-04. 세션 라이프사이클 규율(6항목) 준수.

## 1. 목표

스마트폰을 NTRIP correction 라우터로: NTRIP caster(RTCM3) → USB-C OTG serial → GNSS 수신기
(u-blox F9P / NovAtel OEM7) 주입 → RTK fix. 폰 = 모바일 회선 + USB 브리지. 상업화 대상(비공개).

## 2. 현재 상태

동작 검증 완료 (u-blox ZED-F9P, 서울시 VRS `gnss.eseoul.go.kr:2101` / `VRS-RTCM32`):
- M1 serial 인식(native CDC "u-blox GNSS receiver") → M2 NTRIP 수신 → M3 브리지 주입 →
  GGA(VRS) 업로드 → **RTK float 도달**. 전 경로 폰 하나로 검증.
- FIXED 는 개활지에서 확인 필요(환경 의존, 앱 무관).

빌드: GitHub Actions (push 시 자동, artifact `rtk-router-debug-apk`). 로컬 SDK 없음 → CI 의존.
repo: github.com/Onlyti/RTK_Router_App (private). 브랜치 main.

## 3. 완료 (구현됨)

- NTRIP v1/v2 client (핸드셰이크, sourcetable nmea 판별, RTCM passthrough, GGA 업로드)
- USB serial bridge (usb-serial-for-android 3.10.0, 권한 흐름, **포트 인덱스 선택** = NovAtel 다중포트 대응)
- AUTO 엔드포인트 선택 (VRS 우선, **RTCM3.2 > generic > 3.1 랭킹**, CMR/CMR+ 배제)
- MANUAL + Send GGA 토글, NEAREST(최근접 고정국, haversine)
- **Scan**: sourcetable 목록(포맷·VRS/FIX, RTCM3.2 우선 정렬) → 탭 선택
- byte-rate watchdog(L0 재연결), RTCM rate 4s moving-window
- 수신기 NMEA 파싱(GGA quality/lat/lon/sats/HDOP/alt) + 라인버퍼링(split 방지) + StatusCard 표시
- 구조화 에러(failureMode/level/reason), 데이터 사용량 표시
- 로그 `adb logcat -s rtk`

## 4. 미완 (다음 할 일) — 우선순위순

P0~P1 (필드 신뢰성·NovAtel):
- **NovAtel 실기검증**: VID 0x09D7 맞는지 `lsusb`, 어느 CDC 포트가 RTCM 입력인지(0/1/2 시도),
  GGA 출력 설정(`LOG GPGGA ONTIME 1`), RTCM 입력 활성(`INTERFACEMODE USBx NOVATEL RTCM ON`).
- **NTRIP 자동 재연결**: 현재 스트림 정상종료/소켓에러 시 재시작 없음(watchdog 은 connected 상태만).
  FailoverPolicy(backoff/retries/reprobe) 미사용 — 구현 필요.
- **USB detach/재연결**: 케이블 흔들림·재전원 시 자동 재open 없음(차량 필수).
- **다중기지국 hot-standby (make-before-break)**: 아래 §6 설계. 끊김 없는 RTK.
- **수신기 GGA 포워딩**: 현재 GGA 는 폰 GPS. 수신기 자체 GGA(이미 파싱됨) 전달 시 VRS 정밀↑ +
  위치권한/LOCATION FGS 제거 가능.

P2 (polish):
- NTRIP v2 chunked transfer-encoding 미처리(엄격한 caster 에서 RTCM 오염 가능)
- foreground service type: GGA 수신기전환 시 LOCATION 제거, `connectedDevice` 고려
- START_REDELIVER_INTENT (프로세스 사망 후 복구)
- validateRtcm3(0xD3+CRC24Q) 미구현(현재 순수 passthrough)
- 다중 provider 프로파일 + failover L2/L3

## 5. 주의·함정

- **VRS 는 GGA 필수**: GGA off 면 접속만 되고 RTCM 0. AUTO 는 nmea 플래그로 자동 on, MANUAL 은 토글.
- **포맷**: F9P/OEM7 은 CMR/CMR+ 불가, RTCM3 만. AUTO 가 자동 회피하지만 MANUAL 수동선택 시 주의.
  서울 caster 의 `VRS-RTCM31` 은 0 byte(서버측 비서빙 추정) — `VRS-RTCM32` 사용.
- **native USB CDC 는 baud 무의미**(가상포트). UART 브리지 경유 시에만 baud 일치 필요.
- **NovAtel 다중포트**: RTCM 입력 포트가 ports[0] 아닐 수 있음 → UI 포트 칩(0/1/2)으로 시도.
- **F9P fix 안 뜨면 안테나/하늘 먼저**: quality 0 = 위성 자체 없음(보정 무관).
- **ETH NTRIP Master 적층**: 폰 테스트 시 랜선 빼서 UART2 중복주입 차단.
- 로컬 빌드 불가(Java 11, SDK 없음) → 모든 빌드 CI. GitHub API 가끔 rate-limit.

## 6. 다중기지국 연속 RTK 설계 (미구현, 핵심 backlog)

문제: 이동 중 기지국 전환 시 단일 직렬연결이면 재연결 갭 동안 RTK 끊김.

핵심 사실:
- 수신기 RTCM 입력 포트는 1개 → **두 스트림 동시 주입 불가**(interleave = 깨짐). 수신기단 다중수신 불가.
- 따라서 폰단에서 **N 개 NTRIP 스트림 병렬 open(hot-standby)**, 그중 1개만 serial 로 forward.
  active 가 나빠지면 **이미 흐르는 backup 으로 forward 대상만 즉시 교체** → 네트워크 재연결 갭 제거.
- 단 물리적으로 다른 기지국으로 바꾸면 1005/1006(기준국 좌표) 변경 → 수신기 ambiguity 재수렴
  (float→fixed 수초). 이 재수렴은 make-before-break 로도 못 없앤다.

결론:
- **이동 중 끊김 없는 RTK = VRS 가 정답**(기지국 전환 자체가 없음, 서버가 가상기준국을 따라오게 함).
  현재 eseoul VRS-RTCM32 가 이미 이 방식 → 서울 내 이동 시 연속 RTK. 추가 구현 불필요.
- **hot-standby 다중스트림 = 장애 내성**용(provider/mount 다운 대비). failover ladder 를
  cold-reconnect → make-before-break 로 업그레이드. 품질선택은 liveness(byte-rate/latency) 기반
  (진짜 fix 품질 비교는 수신기 입력 1개라 불가, 전환·관찰해야 알 수 있어 seamless 와 상충).

구현 스케치: RtkService 가 List<NtripClient> 관리, 각 스트림 health 모니터, selector 가 최고 health
스트림을 serial 로 pipe, hysteresis 로 깜빡임 방지. RtkConfig 에 streams: List<mount> 추가.

## 7. 관련 경로

- 설계: `docs/DESIGN.md` (전체), `docs/VERIFY.md` (F9P 검증), 본 핸드오프
- 코어: `app/src/main/java/com/ailab/rtkrouter/`
  - `ntrip/NtripClient.kt` (NTRIP·sourcetable·rtcmFormatRank)
  - `serial/SerialLink.kt` (USB·포트선택)
  - `service/RtkService.kt` (오케스트레이션·watchdog·NMEA파싱), `service/RtkState.kt`
  - `gnss/Nmea.kt` (GGA 생성·파싱), `gnss/LocationHub.kt`
  - `config/RtkConfig.kt`, `ui/{MainActivity,RtkViewModel,Prefs}.kt`
- 형제 repo: `~/phone-sensor-stream` (스택 동일, 골격 참조원)
