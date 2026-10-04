# MusicFind

A personal music streaming setup I put together for myself: an Android app and my
own little backend that does the heavy lifting.

The idea is simple — the phone talks to server, and the server searches and
downloads tracks from SoundCloud / YouTube Music, keeps my playlists, and serves
the audio back. No third-party keys, no accounts on someone else's service, and
my data stays on my own machine.

## What's inside

There are two parts:

- **`app/`** — the Android client. Kotlin + Jetpack Compose. Search, playback,
  Shazam-style recognition straight from the microphone, synced lyrics,
  playlists, downloads for offline listening, and in-app updates.
- **`server/`** — Python (aiohttp). This is where the actual work happens:
  searching and downloading with `yt-dlp`, streaming media, recognizing tracks
  with `shazamio`, fetching lyrics from LRCLIB, accounts, listening stats, a
  small admin page, and handing out the APK for updates.

I tried to keep the client dumb — it only ever talks to your server.

## Requirements

- Server: Linux with systemd, Python 3.10+, and `ffmpeg`.
- App: Android Studio / Android SDK 35 and JDK 17.

## Setting up the server

Clone it and run the installer:

```bash
git clone <repo-url> musicfind
cd musicfind
sudo ./install.sh
```

The script installs the system packages it needs (`python3`, `python3-venv`,
`ffmpeg`), sets up a virtualenv in `server/.venv`, installs the Python deps,
creates `server/data/` and a `server/.env` with a random admin password, then
registers and starts a systemd service called `musicfind-api`.

When it's done you'll have:

- API at `http://<server-ip>:8081`
- Admin page at `http://<server-ip>:8081/admin`
- The admin password is printed once at the end (it's also in `server/.env`)

A few useful commands:

```bash
systemctl status musicfind-api
journalctl -u musicfind-api -f
sudo ./install.sh            # run again to update
sudo ./install.sh --uninstall
```

### Configuration

Everything lives in `server/.env` (there's a `server/.env.example` to copy from).
The important bits:

| Variable | Default | What it does |
|---|---|---|
| `HOST` / `PORT` | `0.0.0.0` / `8081` | where the API listens |
| `ADMIN_PASSWORD` | — | password for the admin page |
| `APP_APK_PATH` | `server/data/app/MusicFind.apk` | the APK the app downloads for updates |
| `AUTO_UPDATE` | `1` | auto-update yt-dlp/ffmpeg in the background |
| `AUTO_UPDATE_INTERVAL_SECONDS` | `86400` | how often to check |

## Building the Android app

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
```

The APK ends up at `app/build/outputs/apk/debug/app-debug.apk`. On first launch
the app asks for your server address, so just point it at
`http://<server-ip>:8081`.

If you want the in-app updater to work, drop the APK where the server expects it:

```bash
scp app/build/outputs/apk/debug/app-debug.apk \
    user@server:/opt/musicfind/server/data/app/MusicFind.apk
```

## Project layout

```
app/                        Android client (Kotlin, Compose)
  .../data/                 models, network, repository, local library
  .../player/               playback controller
  .../service/              foreground playback service (Media3/ExoPlayer)
  .../ui/                   screens and components
  res/values/               English strings (default)
  res/values-ru/            Russian strings
server/
  main.py                   HTTP API, accounts, playlists, stats, admin, OTA
  music_engine.py           search, Shazam, yt-dlp, media, lyrics
  requirements.txt
  .env.example
install.sh                  server installer (systemd)
```

## A few notes

- The first time you play a track it has to be downloaded on the server first,
  so expect a short delay before it starts. After that it's cached.
- `ffmpeg` is required — the server uses it to transcode to mp3.
- `yt-dlp` tends to break whenever SoundCloud/YouTube change something. There's
  a background updater that usually keeps it working; if something stops
  downloading, updating `yt-dlp` is the first thing to try.
- If you want restricted content, put cookies in `server/data/yt_cookies.txt`
  and `server/data/sc_cookies.txt`.
