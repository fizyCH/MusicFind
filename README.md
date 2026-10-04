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
  small admin page, and handing out the APK for updates. Runs either via the
  install script (systemd) or with Docker.

I tried to keep the client dumb — it only ever talks to your server.

## Requirements

- **Docker way:** just Docker (with Compose).
- **Install-script way:** Linux with systemd, Python 3.10+ and `ffmpeg`.
- **App:** Android Studio / Android SDK 35 and JDK 17.

## Running the server

Two ways to run the backend — pick whichever you prefer.

### Option 1 — Docker

```bash
docker compose up -d --build
```

That builds the image, exposes the API on `8081`, keeps `server/data`
(accounts, playlists, the database, avatars) on the host and uses a named volume
for the downloaded media cache.

Set the admin password (and port) in a `.env` file next to `docker-compose.yml`:

```env
ADMIN_PASSWORD=something-secret
PORT=8081
```

or inline:

```bash
ADMIN_PASSWORD=something-secret docker compose up -d --build
```

Logs and shutdown:

```bash
docker compose logs -f
docker compose down
```

Auto-updating yt-dlp/ffmpeg is off inside the container (rebuild the image to
update); set `AUTO_UPDATE=1` if you want it anyway. Drop your built APK into
`server/data/app/MusicFind.apk` on the host and the in-app updater picks it up.

### Option 2 — Install script

Clone it and run the installer:

```bash
git clone <repo-url> musicfind
cd musicfind
sudo ./install.sh
```

Or run the installer on its own — it fetches the scripts from the repo for you:

```bash
sudo ./install.sh
# or:  curl -fsSL <raw-install.sh-url> | sudo bash
```

By default it installs into `/opt/musicfind`; override with `--dir`, `--repo`,
`--branch` or `--port`.

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

Both ways use the same settings. With the install script they live in
`server/.env` (there's a `server/.env.example` to copy from); with Docker set
them in the `.env` next to `docker-compose.yml`.

| Variable | Default | What it does |
|---|---|---|
| `HOST` / `PORT` | `0.0.0.0` / `8081` | where the API listens |
| `ADMIN_PASSWORD` | — | password for the admin page |
| `APP_APK_PATH` | `server/data/app/MusicFind.apk` | the APK the app downloads for updates |
| `AUTO_UPDATE` | `1` (script) / `0` (Docker) | auto-update yt-dlp/ffmpeg in the background |
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
Dockerfile                  server image (python:3.12-slim + ffmpeg)
docker-compose.yml          one-command Docker setup
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
