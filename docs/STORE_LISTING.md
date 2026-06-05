# Google Play 스토어 등록 자료 — RTK Router

Play Console 입력용 초안. 실제 앱 상태 기준(위치권한 없음, 광고/분석 없음).

## 기본 정보
- 앱 이름(≤30자): `RTK Router`
- 카테고리: Tools (도구)
- 태그: GNSS, RTK, NTRIP, GPS
- 무료 / 인앱구매 없음(현재) / 광고 없음

## 짧은 설명 (≤80자)
- EN: `Route NTRIP RTK corrections to your USB GNSS receiver. Multi-network failover.`
- KR: `NTRIP RTK 보정을 USB GNSS 수신기로 중계. 다중망 자동 failover.`

## 전체 설명 (Full description)

EN:
```
RTK Router turns your phone into an NTRIP correction router. It receives RTCM3 RTK
correction data from an NTRIP caster over your mobile internet and injects it into a
GNSS receiver connected by USB-C OTG — giving the receiver centimetre-level RTK.

Features
- NTRIP v1/v2 client (Basic auth, GGA upload for VRS)
- USB serial to the receiver (CDC-ACM / FTDI / CP210x / CH340 / Prolific)
- AUTO mountpoint: prefers RTCM3 VRS; sourcetable Scan to pick manually
- Multi-network hot-standby: keep several casters connected, fail over seamlessly if a
  whole network drops
- Country presets (Korea: NGII integrated centre, Seoul VRS) + RTK2GO community
- Position for VRS comes from the receiver itself — no phone location permission needed
- Live status: NTRIP / RTCM rate / serial / fix quality, data usage, trajectory map

Works with u-blox (e.g. F9P) and other RTCM3 receivers. You need your own NTRIP account.
Not affiliated with any receiver or caster vendor.
```

KR:
```
RTK Router 는 스마트폰을 NTRIP 보정 라우터로 만듭니다. 모바일 인터넷으로 NTRIP caster 에서
RTCM3 RTK 보정정보를 받아 USB-C OTG 로 연결한 GNSS 수신기에 주입해 cm급 RTK 를 얻습니다.

기능
- NTRIP v1/v2 클라이언트 (Basic 인증, VRS GGA 업로드)
- USB 시리얼 (CDC-ACM / FTDI / CP210x / CH340 / Prolific)
- AUTO mountpoint: RTCM3 VRS 우선, sourcetable Scan 으로 수동 선택
- 다중망 hot-standby: 여러 caster 동시 유지, 한 망 장애 시 끊김 없이 전환
- 국가 preset (국토지리정보원 통합센터, 서울 VRS) + RTK2GO 커뮤니티
- VRS 위치는 수신기에서 — 폰 위치권한 불필요
- 실시간 상태(NTRIP/RTCM rate/serial/fix), 데이터 사용량, 이동경로 지도

u-blox(F9P 등) 및 RTCM3 수신기 호환. 본인 NTRIP 계정 필요. 수신기·caster 벤더와 무관.
```

## 그래픽 자료 (네가 준비)
- 앱 아이콘 512x512 (현재 ic_launcher 사용 가능, 고해상도화 필요)
- 피처 그래픽 1024x500
- 스크린샷 (폰): 메인 화면 + StatusCard(연결됨) + Scan + 지도 — 최소 2장, 권장 4~8장

## Data safety (데이터 보안) 폼 답안
- 수집/공유하는 데이터: **없음** (개발자가 수집하는 데이터 없음)
  - 자격증명·위치는 사용자가 설정한 caster 로만 전송, 개발자 미수집 → "데이터 수집 안 함"
  - 단 폼에서 "앱이 데이터를 제3자(사용자 caster)로 전송"하는지 정직하게 표기 권장:
    위치(대략/정밀)·앱활동 아님. caster 전송은 사용자 지시 기능이므로 "수집" 아님으로 통상 처리.
- 데이터 암호화 전송: NTRIP 은 평문 TCP(2101)일 수 있음 → "전송 중 암호화 안 됨" 정직히 표기.
- 데이터 삭제 요청: 해당 없음(개발자 미보관). 로컬 데이터는 앱 삭제 시 제거.

## 콘텐츠 등급
- 전체이용가 (Everyone). 폭력/성인물 없음.

## 개인정보처리방침 URL
- `docs/PRIVACY.md` 내용을 **공개 URL** 로 호스팅 필요.
- repo 가 private 라 GitHub Pages 무료로 안 됨 → 옵션:
  1. 공개 gist 에 PRIVACY.md 붙여넣기 → raw URL
  2. 별도 작은 공개 repo + GitHub Pages (무료)
  3. Google Sites / Notion 공개 페이지
- Play Console > 앱 콘텐츠 > 개인정보처리방침에 그 URL 입력.

## 출시 트랙 (신규 개인계정)
Internal testing → Closed testing(테스터 20명, 14일 유지) → Production 신청.
테스터는 FSK/연구실 인원으로 충원.

## 체크리스트
- [ ] 개발자 계정 $25 + 신원인증
- [ ] keystore 생성 + GitHub Secrets (release 워크플로)
- [ ] AAB 빌드 (태그 push → artifact 의 app-release.aab)
- [ ] 개인정보처리방침 호스팅 + URL
- [ ] 스토어 등록정보(설명·아이콘·스크린샷)
- [ ] Data safety / 콘텐츠 등급 폼
- [ ] Closed testing 20명/14일 → Production
