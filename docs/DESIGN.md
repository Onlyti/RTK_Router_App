# rtk-router 설계 (DESIGN)

상태: in-progress 설계안. phone-sensor-stream 구조 분석 기반. 실기기 검증 항목은 본문에 가설로 표기.

## 1. 개요와 방향성

폰을 NTRIP correction 라우터로 사용. NTRIP caster(인터넷)에서 RTCM3 를 받아 USB-C OTG serial 로
NovAtel / u-blox 수신기에 주입 → 수신기가 RTK fix 계산. 폰 = 모바일 회선 + USB 브리지.

phone-sensor-stream 과의 데이터 방향 차이가 설계의 출발점:

| | phone-sensor-stream | rtk-router |
|---|---|---|
| 네트워크 | 폰이 sensor 송신 (UDP/WS/Foxglove) | 폰이 RTCM3 수신 (NTRIP TCP), GGA 송신 |
| USB | AOA write-only, 길이접두 프레임 | usb-serial-for-android, 양방향 raw byte stream |
| 코어 루프 | sensor -> queue -> transport | NTRIP socket -> serial TX / serial RX -> socket |

결론: 아키텍처 골격(service / lifecycle / config / UI / CI)은 복사, transport 2종(NTRIP client,
USB serial)은 신규 구현. AoaTransport 는 USB 권한 흐름만 참고하고 폐기.

## 2. 재사용 매핑 (phone-sensor-stream -> rtk-router)

### 2.1 그대로 복사

| 모듈 | 원본 경로 | 변경 |
|---|---|---|
| Gradle / CI | `build.gradle.kts`, `settings.gradle.kts`, `.github/workflows/android.yml` | applicationId -> `com.ailab.rtkrouter`. 버전 유지: AGP 8.5.2 / Kotlin 1.9.24 / minSdk 26 / compileSdk 34 / Java 17 |
| App class | `StreamApp.kt` | notification channel -> `rtk_correction` |
| Foreground service 골격 | `service/StreamService.kt` | LifecycleService + 500ms status 폴링 + `startForeground(DATA_SYNC\|LOCATION)` 유지, sink 파이프 교체 |
| 상태 singleton | `service/StreamState.kt` | `StreamStatus` -> `RtkStatus` |
| Config 영속화 | `config/StreamConfig.kt`, `ui/Prefs.kt` | `RtkConfig` 로 필드 교체, SharedPreferences + kotlinx-serialization JSON 패턴 유지 |
| ViewModel / UI | `ui/StreamViewModel.kt`, `MainActivity.kt`, `StatusCard` | StateFlow 바인딩 유지, 입력폼 / 상태카드 내용 교체 |
| 로깅 | `recording/Recorder.kt` | RTCM / fix 세션 로깅 (M5) |

### 2.2 GNSS 코드 (역방향 GGA 용)

- `sensors/LocationHub.kt` (LocationManager wrapper, Play Services 불필요) -> lat/lon/alt 추출 ->
  NMEA GGA 생성 (M4). 체크섬 계산만 신규.
- `GnssRawHub` / `GnssNavHub` 는 M1~M4 범위 밖, 보류.

주의: 수신기가 fix 를 계산하므로 역방향 GGA 위치는 폰 GPS(수십 m) 로 VRS 진입에 충분.
정밀 fix 후에는 수신기 NMEA GGA(serial RX) 를 되받아 올리는 게 정석 -> M4 에서 전환.

## 3. 엔드포인트 / 위치 기반 전환 설계

원칙 1: caster 자격증명(host / port / mount / user / pass)은 항상 사전 등록. NTRIP 은 인증 기반이며
런타임 caster 자동 발견 메커니즘이 없다. -> 프로파일 리스트로 영속화 (강제).

원칙 2 (정책): **VRS 가능하면 VRS 를 기본, 불가하면 고정 endpoint 를 fallback 옵션으로.**
mount 가 VRS(GGA 구동)인지 고정형인지는 추측하지 않고 sourcetable 로 판별한다.

### 3.1 판별 — sourcetable nmea 플래그

`GET /` 응답의 STR 레코드 nmea 필드로 mount 종류가 확정된다:

```
STR;<mount>;...;<nmea>;<solution>;...
                  ^ nmea=1 -> 클라이언트 GGA 필수 = GGA 구동 VRS (가상기준국이 나를 따라옴)
                    nmea=0 -> GGA 불필요        = 고정형 (물리 단일기준국 / 지역 고정 VRS)
```

주의: RTCM 1005/1006 기준국 좌표는 VRS·고정형 모두에 항상 실린다(수신기엔 늘 "특정 좌표 base"로
보임). 따라서 1005 좌표만으로는 구분 불가 — nmea 플래그가 유일한 판별자.

### 3.2 동작 모드 (우선순위)

| 모드 | 조건 | 위치 사전등록 | 동작 |
|---|---|---|---|
| A. VRS (기본) | 대상 mount nmea=1 | 불필요 | 단일 mount 접속 + 폰 GGA(~1Hz) 업로드 -> 서버가 내 위치에 가상기준국 생성. 전국 mount 1개, 위치 전환 없음 |
| B. NEAREST_MOUNT (fallback) | mount 들이 nmea=0 고정형 | 자동 | sourcetable STR 의 기준국 lat/lon(필드 10·11) 과 폰 GPS haversine -> 최근접 mount 자동 선택·재접속 |
| C. BY_REGION / MANUAL | 다중 공급자 또는 sourcetable 좌표 부실 | 필요/수동 | 내장 BaseStation 테이블 또는 사용자가 mount 직접 선택 |

선택 로직 `endpointMode = AUTO` (기본):
1. activeProfile 로 sourcetable fetch.
2. VRS mount(nmea=1) 존재 -> 모드 A 채택, GGA 업로드 on, 그 mount 고정.
3. 없으면 -> 모드 B, nmea=0 mount 중 폰 GPS 최근접 선택, GGA off.
4. sourcetable 미제공/좌표 부실 -> 모드 C, 내장 테이블/수동.

고정형(B)에서 이동 중 기준국 경계를 넘으면 자동 재선택·재접속. 경계 깜빡임 방지 위해
`switchHysteresisKm`(예 2km) 적용. single-base 안전 baseline 통상 10~20km — 초과 시 정확도 저하 경고.

결론: 사용자가 GPS 좌표를 직접 입력해야 하는 건 (C) 뿐. VRS 가능 망이면 위치 전환 로직이 통째로
비활성. 데이터 모델은 세 모드를 모두 수용하되 런타임 AUTO 가 VRS 를 우선 채택.

### 3.3 망/엔드포인트 장애 시 우회 (failover ladder)

실측 근거: gnssdata.or.kr 의 모든 endpoint 가 RTK 미제공인 사례 발생. "TCP 는 붙는데 RTCM 0 byte"는
단순 reconnect 가 정상으로 오판하므로 별도 watchdog·우회가 필수.

failure mode 분류:

| # | 증상 | 감지 신호 | same-mount 재연결로 해결 |
|---|---|---|---|
| 1 | 폰 인터넷 끊김 | 소켓 connect 실패 | 부분적 |
| 2 | caster down / port refused | connect timeout | 아니오 -> 다른 caster |
| 3 | 인증 실패 401 | HTTP 응답코드 | 아니오 -> 계정/설정 |
| 4 | 접속됐는데 RTCM 0 byte | byte-rate watchdog | **아니오** -> 다른 mount/caster |
| 5 | RTCM 흐르는데 fix 안 옴 | 수신기 NMEA quality | 소프트 -> mount/baseline 점검 |

우회 사다리 (단계별 escalation):

```
[L0] same mount 재연결       지수 backoff 1->2->4->...->backoffMaxSec, sameMountRetries 회
       └ 실패 또는 byte watchdog dead 지속
[L1] same caster 다른 mount  sourcetable 차순위(최근접 다음 / 백업 VRS mount)
       └ caster 내 후보 소진
[L2] 다른 caster (provider)  priority 순 profile 목록의 다음으로 전환
       └ 전부 실패
[L3] all-dead 경고 + GPS-only 폴백 표시, reprobeSec 주기 재탐색
```

핵심 메커니즘:
- byte-rate watchdog: 소켓 open 인데 RTCM < `minByteRate` 가 `deadTimeoutSec` 지속 -> dead 선언,
  소켓 생존 무관하게 강제 escalate (failure #4 직격).
- priority profile 목록: 사용자가 백업 망 계정을 추가 등록한 경우 L2 가능. 단일 계정뿐이면 L3 까지.
- recovery 재탐색: 하위 우회 중 상위 provider 를 `reprobeSec` 마다 백그라운드 재시도,
  복구 시 `recoveryHysteresisSec` 안정 확인 후 자동 승격(깜빡임 방지).
- 상태 UI: 현재 단계/provider 노출 ("1순위 dead -> 2순위 사용 중").

한계(사용자 준비물): L2 우회는 백업 caster 계정이 사전 등록돼 있어야만 가능. 막아주는 게 아니라
실운영 시 백업 망 계정 1개 추가 확보가 권장됨.

### 3.4 자격증명 제공 방식 (BYO vs 프리셋)

턴키 RTK-over-LTE 제품은 벤더가 caster 계정/구독을 기기에 임베드해 사용자 설정을 숨긴다. 우리 앱은
반대로 사용자가 NTRIP client 운영 주체인 "BYO 계정" 모델. 절충안으로 `preset` 프로파일 지원:

| 방식 | 사용자 입력 | 적용 |
|---|---|---|
| BYO (기본) | host/port/id/pw 전부 | 범용. 무료망(NGII/기상청) |
| 반프리셋 (preset=true) | id/pw 만 (host/port/version 내장) | 자주 쓰는 망 진입 간소화 |
| 완전 임베드 | 없음 | 우리가 줄 계정·ToS·비용 부담 -> 개인/연구 도구엔 부적합, 미채택 |

폐쇄 API 구독형(u-blox PointPerfect, Swift Skylark 등 비-NTRIP)은 본 앱 대상 외 — NTRIP(공개/인증)
caster 만 지원.

## 4. 데이터 모델

```
CasterProfile(
  id, name,
  host, port, user, pass,
  ntripVersion,          # V1 | V2
  preferredMount,        # nullable — 비우면 AUTO 가 sourcetable 로 선택
  priority,              # 낮을수록 우선 (failover L2 순서)
  preset,                # true -> host/port/version 내장, id/pw 만 입력 (3.4)
)

BaseStation(            # sourcetable STR 파싱 결과 또는 내장 테이블 (모드 B/C)
  mount, name,
  lat, lon, ellipHeight,
  requiresGga,           # = STR nmea 플래그. true -> VRS(모드 A), false -> 고정형
)

FailoverPolicy(
  minByteRate,           # dead 판정 RTCM 하한 (예 10 B/s)
  deadTimeoutSec,        # 그 하한 지속 시 dead (예 10)
  sameMountRetries,      # L0 재시도 횟수 (예 3)
  backoffMaxSec,         # 지수 backoff 상한 (예 30)
  reprobeSec,            # 상위 provider 복구 재탐색 주기 (예 120)
  recoveryHysteresisSec, # 승격 전 안정 확인 (예 30)
)

RtkConfig(
  profiles: List<CasterProfile>,
  activeProfileId,
  endpointMode,          # AUTO(기본) | MANUAL | NEAREST_MOUNT | BY_REGION
  fallbackStations,      # List<BaseStation> — sourcetable 없을 때 내장 테이블 (예: SWON ...)
  baud,                  # 115200 | 57600 | 460800 ...
  sendGga,               # AUTO 가 선택 mount.requiresGga 로 자동 설정
  switchHysteresisKm,    # 고정형 이동 중 mount 재선택 깜빡임 방지 (예: 2.0)
  failover,              # FailoverPolicy
  validateRtcm3,         # 0xD3 프레이밍 검증 옵션
  autoReconnect,
  showDataUsage,         # 데이터 사용량 카드 표시 토글 (옵션, 기본 on)
)

RtkStatus(
  # --- 연결 상태: 어디/어떤 옵션으로 (질문 2) ---
  ntripConnected, serialConnected, deviceName,
  activeProfileName, activeMount,    # 현재 provider / mountpoint
  activeMode,                        # VRS | NEAREST(고정) | MANUAL — 실제 채택된 모드
  ggaActive,                         # GGA 업로드 중인지 (VRS 여부 가시화)
  failoverLevel,                     # L0~L3 (현재 escalation 단계)

  # --- 데이터 사용량 (질문 1, 옵션 표시) ---
  rtcmBytesPerSec,                   # 순간 수신 rate
  sessionRxBytes, sessionTxBytes,    # 이번 세션 누적 (RTCM 수신 / GGA 송신)
  lifetimeRxBytes, lifetimeTxBytes,  # 영속 누적 (월 데이터량 추적), Prefs 저장
  usageResetAt,                      # 영속 카운터 리셋 시점 (사용자 리셋 버튼)

  # --- 에러: 왜/어느 레벨 (질문 3) ---
  lastError = ErrorInfo(
    failureMode,    # 1 NoInternet | 2 CasterDown | 3 AuthFailed | 4 NoRtcmData | 5 NoFix
    level,          # 어느 단계에서 발생/감지 (L0~L3)
    code,           # HTTP 401/404, ECONNREFUSED, TIMEOUT, WATCHDOG_DEAD ...
    reason,         # 사람용 설명 ("gnssdata 1순위: 접속됐으나 RTCM 0 byte 10s -> dead")
    atUptimeSec,    # 발생 시각
  ),

  ggaSentCount, lastFixQuality,      # NMEA GGA quality 0/1/2/4/5
  uptimeSec, warning,                # warning = 현재 활성 경고 요약(lastError.reason 등)
)
```

에러 검출 ↔ failure mode ↔ 레벨 매핑은 §3.3 표(#1~5)와 사다리(L0~L3)에 1:1 대응. UI 는
"어느 provider/mount/모드로 연결됐는지" + "마지막 에러가 왜·어느 레벨에서 났는지"를 항상 노출한다.

엔드포인트 선택 흐름:
- AUTO(기본): sourcetable fetch -> VRS(nmea=1) 있으면 그 mount + GGA on / 없으면 최근접 고정 mount + GGA off
- MANUAL: activeProfile + preferredMount 그대로 접속
- NEAREST_MOUNT: 고정형만, sourcetable/fallbackStations 중 폰 GPS 최근접 mount 선택 (LocationHub 필요)
- BY_REGION: 다중 profile 일 때 폰 위치 최근접 profile 선택 후 위 흐름

수원 SWON 예시 (고정형, 모드 B 검증용): lat 37.2725, lon 126.9855, ellipHeight 70.678,
ITRF ECEF (-3057267.195, 4059274.722, 3841546.864). fallbackStations 내장 샘플로 사용 가능.

## 5. 신규 구현 (transport 2종)

### 5.1 NTRIP client

기반: `transport/Transport.kt` 의 `QueuedTransport`(스레딩 / backpressure / status) 패턴 +
`WebSocketJsonTransport` 의 socket lifecycle(open/deliver/close) 참고하여 신규 작성.

- 핸드셰이크: `GET /<MOUNT> HTTP/1.1`, `Host`, `Ntrip-Version: Ntrip/2.0`,
  `Authorization: Basic <b64>`, `User-Agent: NTRIP rtk-router`
- 응답 분기: v1 `ICY 200 OK` / v2 `HTTP/1.1 200 OK`
- sourcetable: mount 없이 `GET /` -> STR 레코드 파싱(이름, lat, lon) — NEAREST_MOUNT 용
- 수신 byte stream -> serial TX passthrough (validateRtcm3 시 0xD3 + length + CRC24Q 검증)
- GGA 업로드: sendGga 시 ~1Hz, LocationHub 또는 수신기 RX GGA 소스
- byte-rate watchdog: minByteRate/deadTimeoutSec 로 dead 감지 -> §3.3 우회 사다리 트리거
- 자동 재연결/우회: L0 지수 backoff -> L1 다른 mount -> L2 다른 caster -> L3 경고+재탐색

### 5.2 USB serial bridge

신규 의존성: usb-serial-for-android (mik3y). 버전은 추가 시 최신 안정 확인 후 고정 (미확정).

- `UsbSerialProber` 로 CDC-ACM / FTDI / CP210x / CH340 탐지. NovAtel OEM7(CDC) / u-blox(CDC/FTDI) 인식
- `UsbManager.requestPermission` + BroadcastReceiver (AoaTransport 권한 흐름 참고)
- baud 선택 UI
- 2 스레드: NTRIP -> serial write / serial -> read (NMEA 파싱, M4)
- byte-rate 카운터 -> RtkStatus

## 6. 사용자 입력 요건

앱이 사용자에게 요구하는 정보를 최소화하는 것이 §3 AUTO 설계의 목적. RTK 내부지식(VRS/단일base/
최근접)을 사용자에게 요구하지 않는다.

필수 (없으면 동작 불가):

| 항목 | 내용 | 출처 | 빈도 |
|---|---|---|---|
| Caster host | NTRIP caster 주소 | 망 운영기관 | 1회 (preset 이면 내장) |
| Caster port | 보통 2101 | 운영기관 | 1회 (preset 이면 내장) |
| user / pass | NTRIP 로그인 | 가입 시 발급 | 1회. 가입은 앱 밖 선행 |
| Baud | 수신기 RTCM 포트 속도 | 수신기 설정 | 1회 |

자동 (입력 불필요): mountpoint 선택, 기준국 좌표, VRS/고정형 판별, GGA on/off, 내 위치(폰 GPS),
USB 장치 종류 인식.

선택 (고급): mount 수동지정, NTRIP 버전 수동, 다중/백업 프로파일, RTCM3 검증 토글,
데이터 사용량 카드 표시(showDataUsage), 누적 사용량 리셋.

StatusCard 구성(StateFlow 바인딩): (a) 연결 — provider/mount/모드/GGA여부/failover 단계,
(b) 데이터 — rate + 세션·누적 Rx/Tx(showDataUsage 시), (c) 에러 — lastError 의 why·레벨,
(d) fix — lastFixQuality. phone-sensor `StatusCard` 패턴 재사용.

권한 동의: 위치(GGA·최근접 선택), USB 접근, 알림(Android 13+ foreground service).

최소 절차: (앱 밖) 망 가입 -> 앱에 host/port/id/pw + baud 입력 -> USB 연결·권한 동의 -> START.
나머지(mount/VRS/GGA/좌표/우회)는 전부 자동.

## 7. 마일스톤 매핑 (README 정합)

| M | 신규/재사용 | 완료기준 |
|---|---|---|
| M1 USB serial | 신규 usb-serial + 권한흐름 참고 | 수신기 인식 + baud + byte TX/RX echo |
| M2 NTRIP | 신규 (QueuedTransport 기반) + sourcetable 파싱 | caster 접속 + RTCM3 수신, byte-rate > 0, nmea 플래그 판별 |
| M3 브리지 | M1 + M2 연결 + AUTO 엔드포인트 선택 | NovAtel / u-blox 각각 RTK FIX |
| M4 안정화 | LocationHub GGA + byte watchdog + L0/L1 우회 + StatusCard | VRS 역방향 + 자동복구 + 상태 UI |
| M5 운영 | Recorder + 다중 프로파일 + L2/L3 우회·recovery 승격 | 세션 로깅 + 멀티 provider failover |

## 8. 미확정 / 검증 필요 (가설)

- usb-serial-for-android 버전 핀 — 추가 시 확인.
- NovAtel OEM7 USB 가 CDC-ACM 으로 enumeration 되는지 — 실기기 검증 필요 (가설).
- 한국 VRS 공급자 계정/mount 명세(NGII 등) — 사용자 계정 확보 후 확정.
- 수신기 RTCM 입력 포트 baud 기본값 — 수신기 설정에 의존.
