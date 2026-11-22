# Angel OS

A clean, dark, minimal Android system you build for your own phone.

| | |
|---|---|
| **Look** | dark theme, ivory monochrome accent, rounded system shapes, Angel Icons, own boot logo and animation |
| **Angel Island** | a live island around the camera: notifications, music with controls, charging, tap for time and battery |
| **Angel Store** | one store for RuStore, F-Droid and (when present) Google Play; installs Angel apps and F-Droid apps directly |
| **Angel Update** | over-the-air updates from Settings: small deltas, verified by the recovery, **signed with your own key** |
| **Flash mode** | your own recovery with a live progress screen; flashes only changed chunks in seconds, no BROM |
| **Fast and private** | 1 GB compressed memory (zram), no telemetry: statistics reporting is switched off |

Currently supported device: **OPPO A1k (CPH1923, MT6765)**. The toolkit is organised so that more devices can be added.

## Build it

Angel OS ships as a **build kit**, like LineageOS: you build the system for your phone from your own firmware backup.
No vendor firmware, Google apps or anyone else's device dumps are included here.

1. Follow the device steps in [`docs/INSTALL.md`](docs/INSTALL.md) up to the verified backup and bootloader unlock
   (`tools/setup_macos.sh`, `tools/device.py backup`, `tools/device.py unlock`). Your backup lands in `work/device/`.
2. Install the Android build tools (JDK 17, Android SDK platform 30, build-tools 34) — see `tools/sdk_install.sh`.
3. Download the base system image (LineageOS 18.1 GSI, `arm64_bvS` for a Google-free build).
4. Build everything:

   ```sh
   tools/build_all.sh work/lineage-18.1-…-arm64_bvS.img.xz 100 "Angel OS 1.0" https://your-angel-server:8787
   ```

   This builds the overlays, the Angel apps, the boot animation, creates **your OTA signing key** (`work/keys/ota_ec.pem` — back it up)
   and writes `work/public/angelos-100.img`.
5. Build your flash-mode recovery (`tools/build_recovery.py`); only **your computer's adb key** can talk to it.
6. Flash once through BROM (`tools/device.py flash`), afterwards use `tools/fastflash.py` or Angel Update.

### Your own server

`server/` contains the small Angel Store / Angel Update API (Python standard library only) and a hardened systemd unit.
Point your build at it with the last argument of `build_all.sh` (stored as `ro.angel.server`).
Large downloads (Angel app APKs, OTA packages) are meant to be published as **GitHub Releases**, not served from a small VPS.

### Releasing an update

```sh
python tools/make_ota.py old.img new.img 100 101 "Angel OS 1.0.1" "What's new…"
```

The package is signed with your key; phones running your build refuse anything else.

## Layout

| Path | What |
|---|---|
| `apps/` | Angel Store, Angel Island, Angel Icons, Angel Update (Java, no Gradle — built by `tools/build_app.sh`) |
| `overlays/` | theme, shapes, shade, recents, branding (static resource overlays) |
| `media/` | boot animation generator, wallpapers, boot logo |
| `tools/` | image builders, recovery builder, OTA builder, fast flasher, recovery UI renderer |
| `server/` | Angel API |
| `docs/`, `patches/`, `assets/`, `tests/` | the device toolkit Angel OS is built on |

## License

GPL-3.0 (see [`LICENSE`](LICENSE)). Third-party notices: [`NOTICE.md`](NOTICE.md).

<sub>Angel OS is based on LineageOS 18.1 and phh-treble (via the AndyYan GSI builds) and on the
[oppo-a1k-lineageos-18.1](https://github.com/itsdanielsultan/oppo-a1k-lineageos-18.1) device toolkit by its author.
Their licenses and copyright notices apply to their parts. Angel OS is not affiliated with or endorsed by the LineageOS project, Google, Apple or OPPO.</sub>
