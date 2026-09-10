# PTRK-KVN

Android VPN client with dual engines: **Mihomo** (Clash Meta / Remnawave YAML) and **olcRTC**.

Package id: `ptrkkvn.app`  
Updates: [GitHub Releases](https://github.com/sweetbat/PTRK-KVN-app/releases)

## Features

- Mihomo TUN via `libclash` / `libcore` (subscription YAML import, rule / global / direct modes)
- olcRTC path via Mobile SOCKS + `hev-socks5-tunnel` + Android `VpnService`
- Russian / English UI with first-launch language picker
- Subscription import, ping, traffic quota, split tunneling, SOCKS5 proxy mode
- In-app update checks against this repository

## Status

Active development. Android arm64 is the primary target.

## Build (Android)

Requirements:

- JDK 17+
- Android SDK + NDK
- Gradle wrapper in this repo

```bash
./gradlew :androidApp:assembleDebug
```

APK output:

```text
androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Native Mihomo libs are under `androidApp/src/main/jniLibs/`.  
Bundled olcRTC AAR is under `sharedUI/olcrtc-bin/libs/`.

## Engines

| Engine | Config | Tunnel |
| --- | --- | --- |
| Mihomo | Clash / Remnawave YAML subscription | libclash TUN |
| olcRTC | `olcrtc://` / JSON locations | Mobile SOCKS → hev-socks5-tunnel |

olcRTC ping runs in a separate `:olcrtc` process so Go runtimes (`libclash` and `libgojni`) do not share one address space.

## Credits

Forked from [alananisimov/olcbox](https://github.com/alananisimov/olcbox) (MIT).  
Mihomo integration inspired by FlClashX / PTRK libclash packaging.

## License

MIT — see [LICENSE](LICENSE).
