# hellOS app

An open source Android app to control Denon and Marantz HEOS devices in the local network. It is
an alternative to the official HEOS app: no HEOS account, no cloud, only the HEOS CLI protocol
between your phone and your devices.

The app is built on [hellOS](https://github.com/D4RK463/hellOS), a Java library for the HEOS CLI
protocol.

> hellOS app is not affiliated with or endorsed by Denon, Marantz or Sound United. HEOS, Denon and
> Marantz are trademarks of their respective owners.

## Features

- **Connect:** finds HEOS devices in the network automatically, or connects to an IP address.
  The last device is remembered. One device is enough to control the whole HEOS system.
- **Now playing:** cover, title, artist, album or station, progress, play/pause, previous/next,
  shuffle and repeat (off / all / one).
- **Volume and input:** volume slider, mute and input selection (HDMI, optical, AUX, …) are always
  at hand at the bottom of every screen. The hardware volume keys of the phone control the
  selected player.
- **Music from your network:** browse DLNA/UPnP media servers (e.g. a NAS) and USB drives
  connected to a HEOS device. Play a title, play it next, add it to the queue, or play or queue a
  whole folder.
- **Queue:** shows the queue of the selected player; tap to play a title, swipe to remove it, clear
  the whole queue.
- **Rooms:** all players and groups; select a player, group players, ungroup, group volume and
  mute.
- **Live updates:** changes made on the device, with the remote or in another app appear
  immediately.
- English and German user interface.

### Not included, on purpose

Everything that needs a HEOS account: favorites, HEOS playlists, history and streaming services
(Spotify, TIDAL, TuneIn, …). Shared folders over SMB are not supported by HEOS devices; enable
DLNA/UPnP on your NAS instead.

The app controls the devices while it is open. It does not keep a connection in the background.

### Planned

- Sound mode of Denon/Marantz AVRs (Direct, Pure Direct, Stereo, …)
- Quick selects of AVRs, playing stream URLs, reordering the queue, and more

See [docs/IDEAS.md](docs/IDEAS.md) for the full list of ideas.

## Requirements

- Android 14 (API 34) or newer
- Phone and HEOS devices in the same Wi-Fi network
- HEOS devices reachable on TCP port 1255; discovery uses SSDP (UDP multicast)

### Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Connection to the HEOS device and loading cover art |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Network state |
| `CHANGE_WIFI_MULTICAST_STATE` | Device discovery: Android drops multicast packets unless the app holds a multicast lock |

Cover art is often served over plain HTTP by devices and media servers in the local network, so
cleartext HTTP is allowed (`res/xml/network_security_config.xml`).

## Build

### What you need

- JDK 21 or newer (built with JDK 25)
- Android SDK with the platform for API 36. With Android Studio this is installed for you;
  without, Gradle downloads missing SDK packages on the first build if the licenses are accepted.
- The location of the SDK, either in `local.properties`:

  ```properties
  sdk.dir=/home/you/Android/Sdk
  ```

  or in the environment variable `ANDROID_HOME`.

Everything else (Gradle 9.9.1, Android Gradle Plugin, libraries) is downloaded by the Gradle
wrapper.

### Commands

```sh
./gradlew assembleDebug        # debug APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug            # Android lint
./gradlew assembleRelease      # shrunk release APK (unsigned): app/build/outputs/apk/release/
```

Install the debug APK on a phone with USB debugging enabled:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A release APK has to be signed before it can be installed. Signed APKs are attached to the
[GitHub releases](https://github.com/D4RK463/hellOS_app/releases); to sign locally, set the
environment variables `RELEASE_KEYSTORE_FILE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD` before `assembleRelease`. The version is `0.1.0` unless it is passed with
`-PappVersionName=X.Y.Z`.

You can also open the project in Android Studio and run it from there.

## Releases

Every pull request merged into `main` becomes a release (`.github/workflows/release.yml`): the
workflow derives the next version from the commits since the last tag, builds and signs the APK,
and creates the tag `vX.Y.Z` and a GitHub release with generated release notes.

Pull requests are merged with **squash merge**, and the title becomes the commit message. The title
follows [Conventional Commits](https://www.conventionalcommits.org/), which is checked for every
pull request (`.github/workflows/pr-title.yml`):

| Title | Version | Release notes section |
|---|---|---|
| `feat!: …`, `fix!: …` or `BREAKING CHANGE:` in the body | major | Breaking changes |
| `feat: …`, `feat(scope): …` | minor | Features |
| `fix: …`, `perf: …` | patch | Fixes |
| `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `style`, `revert` | patch | Other changes |

Each pull request is also built and linted (`.github/workflows/ci.yml`).

### Setup of the repository (once)

1. Create the release keystore and **keep it and its passwords safe**. Updates of an installed
   app must be signed with the same key; if it is lost, users have to uninstall the app.

   ```sh
   keytool -genkeypair -keystore hellos-release.jks -alias hellos -keyalg RSA -keysize 4096 \
       -validity 10000 -dname "CN=hellOS app"
   ```

2. Add these secrets under *Settings → Secrets and variables → Actions*:

   | Secret | Value |
   |---|---|
   | `RELEASE_KEYSTORE_BASE64` | Output of `base64 -w0 hellos-release.jks` |
   | `RELEASE_KEYSTORE_PASSWORD` | Password of the keystore |
   | `RELEASE_KEY_ALIAS` | `hellos` |
   | `RELEASE_KEY_PASSWORD` | Password of the key (same as the keystore password with the command above) |

3. Under *Settings → General → Pull Requests*: allow only *squash merging* and set the default
   commit message to *Pull request title* (or *Pull request title and description*).

### The hellOS library

The library is not published to a Maven repository yet. Its jar from the
[GitHub release](https://github.com/D4RK463/hellOS/releases) is part of this project in
`app/libs/` (currently `hellOS-0.1.1.jar`; Android support starts with 0.1.1). The jar has no POM,
so its dependency Jackson (`tools.jackson.core:jackson-databind`) is declared in
`app/build.gradle.kts`.

To update the library, put the new jar into `app/libs/`, remove the old one and change the file
name and, if needed, the Jackson version in `app/build.gradle.kts` and
`gradle/libs.versions.toml`.

## Project structure

| Path | Content |
|---|---|
| `app/src/main/java/org/hellos/app/heos` | Connection to the HEOS system (`HeosRepository`), discovery, settings |
| `app/src/main/java/org/hellos/app/ui` | Activity, the screens (fragments) and the volume/input bar |
| `app/src/main/res` | Layouts, icons, texts (`values` English, `values-de` German) |
| `app/libs` | hellOS library jar |
| `docs/IDEAS.md` | Ideas for later |
| `.github` | CI and release workflows |

## License

GNU General Public License v3.0 or later, see [LICENSE](LICENSE).
