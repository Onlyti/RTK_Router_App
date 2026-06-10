# Privacy Policy — RTK Router

Last updated: 2026-06-05

RTK Router ("the app") is an Android utility that routes RTK GNSS correction data
(RTCM3) from an NTRIP caster over the internet to a GNSS receiver connected via USB.
This policy describes how the app handles data. The developer ("we") does not operate
any server and does not collect personal data.

## Data the app handles

- **NTRIP caster credentials** (host, port, username, password): entered by you and
  **stored only on your device** (Android private storage). They are sent **only** to
  the NTRIP caster you configure, to authenticate your connection. We never receive them.
- **Position data (NMEA GGA)**: read from your connected GNSS receiver over USB and, for
  VRS-type casters, uploaded to the caster you configure so it can generate corrections.
  This is sent **only** to your chosen caster. We never receive it.
- **Receiver data (NMEA)**: read from the USB receiver and shown on screen
  (fix status, satellites, position). Processed on-device only.

## Data we do NOT collect

- We collect **no** personal data. There is **no** developer server, **no** analytics,
  **no** advertising, and **no** tracking.
- The app does **not** request location (GPS) permission. Position used for VRS comes
  from your GNSS receiver, not from the phone.

## Third parties

- **Your NTRIP caster**: data you send to the caster you configure is governed by that
  provider's own policy. Choose a caster you trust.
- **OpenStreetMap**: the trajectory map loads map tiles from OpenStreetMap servers; your
  IP address is visible to them when tiles are fetched, per the OSM tile usage policy.

## Permissions

- Internet / network state: connect to your NTRIP caster.
- USB host: communicate with the connected GNSS receiver.
- Foreground service (connected device) + notifications: keep the correction stream running and
  show its status.

## Children

The app is a technical tool not directed at children and collects no data.

## Changes

We may update this policy; the "Last updated" date will change accordingly.

## Contact

pauljiwon96@gmail.com

---

# 개인정보처리방침 — RTK Router (한국어)

최종 수정: 2026-06-05

RTK Router(이하 "앱")는 인터넷의 NTRIP caster 로부터 RTK 보정정보(RTCM3)를 받아 USB 로 연결된
GNSS 수신기에 전달하는 Android 유틸리티입니다. 개발자는 별도의 서버를 운영하지 않으며 개인정보를
수집하지 않습니다.

## 앱이 다루는 데이터
- **NTRIP 자격증명(host/port/id/pw)**: 사용자가 입력하며 **기기 내부에만 저장**됩니다. 사용자가
  설정한 caster 에 접속 인증 목적으로만 전송되고, 개발자는 받지 않습니다.
- **위치(NMEA GGA)**: USB 수신기에서 읽어, VRS caster 의 경우 보정 생성을 위해 사용자가 설정한
  caster 로만 업로드됩니다. 개발자는 받지 않습니다.
- **수신기 데이터(NMEA)**: USB 로 읽어 화면 표시(fix 상태·위성·위치). 기기 내에서만 처리.

## 수집하지 않는 것
- 개인정보 일절 수집 안 함. 개발자 서버·분석·광고·추적 **없음**.
- **위치(GPS) 권한 요청 안 함**. VRS 위치는 폰이 아닌 수신기에서 옴.

## 제3자
- **사용자가 설정한 NTRIP caster**: 그 제공자 정책 적용. 신뢰하는 caster 사용 권장.
- **OpenStreetMap**: 이동경로 지도 타일을 OSM 서버에서 로드하며 타일 요청 시 IP 가 노출됩니다.

## 권한
인터넷/네트워크 상태(caster 접속), USB host(수신기 통신), foreground service+알림(스트림 유지·상태).

## 문의
pauljiwon96@gmail.com
