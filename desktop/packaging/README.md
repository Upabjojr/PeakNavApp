# Desktop installers

```bash
./gradlew :desktop:installers            # everything below
./gradlew :desktop:installerWindows
./gradlew :desktop:installerMacM1        # Apple Silicon
./gradlew :desktop:installerMacX64       # Intel
./gradlew :desktop:installerLinuxDeb     # Debian/Ubuntu
./gradlew :desktop:installerLinuxAppImage
```

Output lands in `desktop/build/installers/`. The first run of each target downloads
a JRE 17 for that platform (~180 MB) into `desktop/build/construo/jdk`; later runs
reuse it.

| artefact | size | contains |
| --- | --- | --- |
| `peaknav-1.0.0-windows-x64-setup.exe` | ~89 MB | NSIS installer |
| `peaknav-1.0.0-macos-aarch64.dmg` | ~136 MB | `PeakNav.app` + a link to `/Applications` |
| `peaknav-1.0.0-macos-x86_64.dmg` | ~137 MB | as above, Intel |
| `peaknav_1.0.0_amd64.deb` | ~93 MB | installs to `/opt/peaknav` (~135 MB) |
| `peaknav-1.0.0-x86_64.AppImage` | ~98 MB | one executable file, no install |

Each one is self-contained: the fat jar, the native libraries, the 31 MB of bundled
assets and a JRE 17. **The user does not need Java installed.**

## Build requirements

Two tools beyond the JDK, both of which cross-compile — which is the whole reason
they are used instead of the JDK's own `jpackage`. `jpackage` only emits an
installer for the OS it runs on, so it cannot produce a Windows or macOS installer
from a Linux machine.

```bash
sudo apt install nsis genisoimage squashfs-tools   # Debian/Ubuntu
brew install makensis cdrtools squashfs            # macOS
```

`dpkg-deb` (for the `.deb`) ships with `dpkg` and is already present on any Debian
or Ubuntu machine. The AppImage task downloads the ~1 MB AppImage type-2 runtime
from GitHub the first time it runs and caches it in `desktop/build/installers`;
that is the only step in the whole chain that needs the network at build time.

Neither has to be on `PATH`; point at one explicitly if not:

```bash
./gradlew :desktop:installerWindows -Pmakensis=/opt/nsis/bin/makensis
./gradlew :desktop:installerMacM1 -Pgenisoimage=/usr/local/bin/mkisofs
```

The `.dmg` is an ISO9660 image with Rock Ridge extensions rather than Apple's UDIF
format, because `hdiutil` exists only on macOS. macOS mounts it happily, but two
consequences follow: it is **not compressed** (hence 136 MB against the 97 MB zip
construo produces), and the mounted window has no custom background or icon
layout, just the app and the `Applications` link side by side.

## Linux

Two formats, because no single one serves the platform:

- **`.deb`** — a real install. The app goes to `/opt/peaknav` (where Debian policy
  puts software that is not managed by the distribution, which is right for
  something carrying its own JRE), `peaknav` lands on `PATH` as a symlink, and the
  desktop entry and eight icon sizes go into the freedesktop hicolor theme, so it
  appears in the applications menu. `apt remove peaknav` undoes all of it.
- **`.AppImage`** — one executable file that runs on any distribution, installs
  nothing and needs no root. This is what to offer users who are not on Debian or
  Ubuntu.

The `.deb`'s `Depends` are written by hand rather than derived with
`dpkg-shlibdeps`, and that is deliberate: GLFW `dlopen`s the X11 and OpenGL
libraries at runtime, so they never appear as ELF `NEEDED` entries and no automatic
tool can see them. Getting that list wrong does not fail the install — it produces a
window that silently never opens. There is no `java` dependency; the JRE is inside
the package.

No RPM is built (`rpmbuild` is not installed here). Fedora/openSUSE users are served
by the AppImage.

Unlike Windows and macOS, nothing on Linux refuses to run unsigned software, so the
signing section below does not apply. A `.deb` signature only matters when serving
packages from an apt repository, which this is not.

## Signing — read this before publishing

**Nothing here is code-signed, and no build tool can change that.** Signing needs
certificates that cost money and, for macOS, tooling that only runs on a Mac.
Both systems will warn about the download:

**Windows.** SmartScreen shows "Windows protected your PC" for an unrecognised
publisher; the user clicks *More info* → *Run anyway*. It stops once the installer
builds enough download reputation, or immediately with an EV code-signing
certificate (~€300/year). The installer is per-user — it goes to
`%LOCALAPPDATA%\Programs\PeakNav` and needs no administrator rights — which at
least avoids UAC's red unsigned-publisher prompt on top of SmartScreen.

**macOS.** Gatekeeper refuses a quarantined unsigned app outright on first launch.
The user has to either right-click the app and choose *Open* (which offers an
override the double-click path does not), or run:

```bash
xattr -dr com.apple.quarantine /Applications/PeakNav.app
```

Doing it properly means an Apple Developer ID ($99/year), `codesign --deep`, and
notarization through `notarytool` — all of which require macOS. A GitHub Actions
`macos-latest` runner is the usual way to get that without owning a Mac.

Worth knowing: every arm64 Mach-O binary in the bundle *is* already signed — the
JRE by Eclipse Adoptium, the launcher and the libGDX/LWJGL natives ad-hoc. That
matters because Apple Silicon refuses to execute an unsigned binary at all, so the
app does run once quarantine is cleared. The Intel natives are unsigned, which is
fine; x86_64 has no such requirement.

## Where the app keeps its data

`DesktopFiles.getGdxFilesExternalPath` puts downloaded maps, elevation tiles and
preferences outside the install directory:

- Windows — `%APPDATA%\PeakNav`
- macOS — `~/Library/Application Support/PeakNav`
- Linux — `~/.peaknav`

So an uninstall or a reinstall does not touch gigabytes of downloaded data. The
Windows uninstaller asks before removing it and defaults to keeping it; on macOS,
dragging the app to the Trash leaves it behind entirely; `apt remove peaknav` never
touches the home directory.

## Icons

`make_icons.py` regenerates every launcher icon from `assets/icons/peaknav_icon.jpg`,
the same medallion the Android launcher icon is cut from:

- `desktop/icons/logo.png` — the Windows executable (construo injects it)
- `desktop/icons/logo.icns` — the macOS `.app`
- `desktop/icons/logo.ico` — the Windows installer itself
- `desktop/icons/linux/<size>.png` — the hicolor theme sizes for the `.deb`, and the
  512px one doubles as the AppImage icon

Note that the repository's `.gitignore` excludes `*.png` wholesale, so these need
their explicit exceptions there or a fresh clone cannot build an installer.
