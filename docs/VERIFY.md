# 검증 방법 (VERIFY) — u-blox ZED-F9P 기준

상태: in-progress. 가용 하드웨어 = u-blox F9P. 마일스톤별 검증 절차와 evidence 항목.

## 0. 사전 격리 검증 (앱 없이, PC u-center)

앱 버그와 "보정 서비스 자체 장애"를 분리하기 위해 먼저 PC 에서 확인. (실제로 gnssdata.or.kr
전체가 RTK 미제공인 날이 있었음 → 망 상태를 먼저 격리.)

1. F9P 를 PC USB 연결 → u-center.
2. u-center 내장 NTRIP client(Receiver > NTRIP Client)로 같은 caster 계정 접속.
3. RTK fix 가 뜨면 → 계정·mount·보정망 정상. 앱 문제만 남음.
4. 안 뜨면 → 망/계정 문제. 앱과 무관. 이날은 앱 검증 보류.

evidence: u-center 의 fix type(3D/DGNSS/FLOAT/FIXED) 캡처.

## 1. F9P 전제 조건 (기본 펌웨어면 대개 충족)

- USB enumeration: ZED-F9P native USB = CDC-ACM 단일 포트. usb-serial 의 CdcAcmSerialDriver 가
  인식. (보드가 CP210x/CH340/FTDI 브리지를 거치면 해당 driver 가 처리.) — 실기기 확인 필요(가설).
- RTCM3 입력: F9P 는 기본적으로 USB·UART 모든 포트에서 RTCM3 입력 수용. USB 주입 그대로 동작.
- NMEA 출력: 기본 GGA 출력 on → 앱이 serial RX 로 fix quality 파싱 가능.
- baud: native USB CDC 는 가상 포트라 baud 값 무의미(앱의 baud 설정 무시됨). UART 브리지 경유 시에만
  baud 일치 필요(F9P UART1 기본 38400).

## 2. 마일스톤별 검증

### M1 — USB serial (수신기 인식 + RX)

1. F9P 를 폰에 USB-C OTG 연결 → 앱 START → USB 권한 팝업 동의.
2. 기대: StatusCard "Serial: <device>" connected, "Serial Tx/Rx" 의 Rx 가 증가(F9P NMEA 유입).
3. 하늘 보이면 "Fix: GPS (single)" 표시 → serial RX·NMEA 파싱 동작 확정. (이 단계는 NTRIP 불필요.)

evidence: StatusCard 스크린샷(serial connected + Rx>0 + Fix=GPS).

### M2 — NTRIP client (caster 수신)

1. host/port/user/pass 입력, mount 비움(AUTO) 또는 명시.
2. (수신기 없어도 가능) START → "NTRIP: connected", "RTCM rate > 0", "Rx (caster)" 증가.
3. AUTO 동작 확인: StatusCard "Mode" = VRS(nmea=1 mount 발견 시) 또는 NEAREST(고정형),
   "GGA" = on/off 가 모드에 맞게 자동 설정됐는지.

evidence: StatusCard(NTRIP connected, RTCM rate>0, Mode/GGA 표시).

### M3 — 브리지 (end-to-end RTK fix)

1. F9P 연결 + NTRIP 접속 + RTCM 흐름 동시.
2. RTCM 이 serial TX 로 주입("Serial Tx" 증가) → F9P 가 보정 적용.
3. 기대 전이: Fix quality 1(GPS) → 5(RTK float) → 4(RTK FIXED), 통상 수십 초~1분.
4. StatusCard "Fix: RTK FIXED" 도달 = M3 완료.

evidence: fix quality 전이 타임라인(시각 + quality), 최종 FIXED 스크린샷, RTCM byte-rate.

주의: F9P 를 폰과 PC u-center 에 동시 연결 불가 → 앱의 NMEA 파싱이 1차 근거. 교차검증은 0단계에서
선행.

### M4 — 안정화 (GGA 역방향 / watchdog / 재연결)

1. VRS mount(nmea=1)면 GGA 업로드 필수 → "Tx GGA" 증가 확인. GGA 안 가면 VRS 보정 안 내려옴.
2. byte watchdog: caster 가 접속 유지하나 RTCM 끊긴 상황 재현(예 mount 잘못) → deadTimeoutSec 후
   "Error [NoRtcmData/L0]" + 자동 재연결 로그.
3. USB 분리/재연결 → serial 재인식.

evidence: GGA Tx>0 로그, watchdog 에러 발생·복구 타임라인.

## 3. 정량 목표 (초안, 실측 후 확정)

| 항목 | 목표 |
|---|---|
| RTK FIXED 도달 시간 | < 60s (개활지, 정상 보정) |
| RTCM rate | > 0, 통상 0.5~2 kB/s |
| watchdog dead 감지 | deadTimeoutSec(기본 10s) 내 |
| 재연결 복구 | autoReconnect 시 backoff 내 자동 |

## 4. 미확정 (가설 표기)

- F9P 보드의 USB enumeration 종류(native CDC vs 브리지칩) — 실기기에서 device name 확인.
- F9P 기본 NMEA 출력에 GGA 포함 여부 — 미포함이면 u-center 로 GGA 활성화 필요.
- caster(gnssdata.or.kr 등) 의 VRS/고정형 여부 — sourcetable nmea 플래그로 M2 에서 자동 판별.
