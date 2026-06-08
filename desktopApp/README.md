# RTK Router — Desktop (Linux & Windows)

NTRIP caster에서 RTCM3 보정 데이터를 받아 USB/serial로 GNSS 수신기(u-blox F9P 등)에 주입하는 GUI 앱.

| OS | Serial port | Package |
|----|-------------|---------|
| Linux | `/dev/ttyACM*`, `/dev/ttyUSB*` | `.deb` |
| Windows | `COM*` | `.msi` |

## Build & Run (dev)

JDK 17 required.

```bash
# from repo root
./gradlew :desktopApp:run
```

## Release packages (local)

```bash
# Linux .deb
./gradlew :desktopApp:packageReleaseDeb

# Windows .msi (run on Windows)
.\gradlew :desktopApp:packageReleaseMsi
```

Outputs under `desktopApp/build/compose/binaries/main-release/`.

## GitHub Release (CI)

`v*` tag push (e.g. `v1.0.1`) triggers [.github/workflows/release.yml](../.github/workflows/release.yml):

- Android: `app-release.apk`, `app-release.aab`
- Linux: `rtk-router_*_amd64.deb`
- Windows: `rtk-router-*.msi`

```bash
git tag v1.0.1
git push origin v1.0.1
```

## Linux serial permissions

`/dev/tty*` 장치에 읽기/쓰기 권한이 필요합니다.

1. **앱 내 (임시)**: START 시 권한이 없으면 다이얼로그에서
   - **pkexec로 권한 부여** — `chmod a+rw /dev/ttyACM0`
   - **sudo로 권한 부여** — 앱 내 비밀번호 입력
   - **dialout 영구 추가** — `usermod -aG dialout $USER` (재로그인)

2. **수동 (권장, 영구)**:

```bash
sudo usermod -aG dialout $USER
# log out and back in
```

## Windows install & launch

Default install folder:

```
C:\Program Files\rtk-router\rtk-router.exe
```

If the Start menu has no shortcut:

1. Reinstall with MSI **1.0.4+** (installer shows shortcut checkboxes — leave both checked).
2. After install, check **Start → All apps → RTK Router** (folder) or search `rtk-router`.
3. Machine-wide shortcut path: `C:\ProgramData\Microsoft\Windows\Start Menu\Programs\RTK Router\`
4. Manual launch: `C:\Program Files\rtk-router\rtk-router.exe` or Win+R → paste that folder.

MSI 1.0.1 had no shortcuts. 1.0.2–1.0.3 CI/config issues; 1.0.4 adds `--win-shortcut-prompt`.

## Windows serial

USB-UART 또는 u-blox CDC 드라이버 설치 후 Device Manager에서 COM 포트 번호를 확인하세요. 별도 권한 설정은 필요 없습니다.

## Device notes

| Device | Linux | Windows | Baud |
|--------|-------|---------|------|
| u-blox ZED-F9P (USB CDC) | `/dev/ttyACM0` | `COM3` (varies) | ignored (native CDC) |
| USB-UART (FTDI/CP210x/CH340) | `/dev/ttyUSB0` | `COM4` (varies) | must match receiver UART |

Settings: Linux `~/.config/rtk-router/settings.json`, Windows `%USERPROFILE%\.config\rtk-router\settings.json`.
