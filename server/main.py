import asyncio
import hashlib
import html
import importlib.util
import json
import os
import re
import secrets
import shutil
import sys
import time
from datetime import datetime, timezone

from aiohttp import web
import aiohttp

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
DB_PATH = os.path.join(DATA_DIR, "db.json")
VERSION_PATH = os.path.join(DATA_DIR, "app_version.json")


def load_env():
    path = os.path.join(BASE_DIR, ".env")
    if not os.path.exists(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip())


load_env()

HOST = os.getenv("HOST", "0.0.0.0")
PORT = int(os.getenv("PORT", "8081") or "8081")
ADMIN_PASSWORD = os.getenv("ADMIN_PASSWORD", "admin")
APP_APK_PATH = os.getenv("APP_APK_PATH", os.path.join(DATA_DIR, "app", "MusicFind.apk"))
AUTO_UPDATE = os.getenv("AUTO_UPDATE", "1") != "0"
AUTO_UPDATE_INTERVAL = int(os.getenv("AUTO_UPDATE_INTERVAL_SECONDS", str(24 * 3600)) or str(24 * 3600))

FAVORITES_NAME = "Liked"
LEGACY_FAVORITES_NAMES = ("Мне нравится",)

_lock = asyncio.Lock()

ENGINE_FILE = os.path.join(BASE_DIR, "music_engine.py")


def load_engine():
    """Load the music engine (search, Shazam, yt-dlp download, media, lyrics)."""
    if "mfind_engine" in sys.modules:
        return sys.modules["mfind_engine"]
    spec = importlib.util.spec_from_file_location("mfind_engine", ENGINE_FILE)
    module = importlib.util.module_from_spec(spec)
    sys.modules["mfind_engine"] = module
    spec.loader.exec_module(module)
    return module


def configure_engine(module):
    """Point the engine's file paths at this server's data directory."""
    if module is None:
        return
    engine_data = os.path.join(DATA_DIR, "engine")
    os.makedirs(engine_data, exist_ok=True)
    module.SC_COOKIES = os.path.join(DATA_DIR, "sc_cookies.txt")
    module.YT_COOKIES = os.path.join(DATA_DIR, "yt_cookies.txt")
    module.PLAYLIST_DIR = os.path.join(engine_data, "playlists")
    module.RECOMMENDATIONS_CACHE = os.path.join(engine_data, "recommendations_cache.json")
    module.KNOWN_USERS_FILE = os.path.join(engine_data, "known_users.json")
    module.WHITELIST_FILE = os.path.join(engine_data, "whitelist.json")
    module.NOW_PLAYING_FILE = os.path.join(engine_data, "now_playing.json")
    module.ACCOUNTS_FILE = os.path.join(engine_data, "accounts.json")
    module.TELEGRAM_PROFILES_FILE = os.path.join(engine_data, "telegram_profiles.json")
    module.SESSIONS_FILE = os.path.join(engine_data, "sessions.json")
    venv_ytdlp = os.path.join(os.path.dirname(sys.executable), "yt-dlp")
    if os.path.exists(venv_ytdlp):
        module.YTDLP = venv_ytdlp


try:
    engine = load_engine()
    configure_engine(engine)
except Exception as _engine_error:
    engine = None
    print(f"Failed to load music engine: {_engine_error}")


def engine_register_session(uid, token, user):
    """Mirror our session into the engine so its API handlers accept our token."""
    if engine is None or uid is None:
        return
    try:
        engine.known_users.add(uid)
    except Exception:
        pass
    try:
        engine.android_sessions[token] = {
            "uid": uid,
            "user": {
                "id": uid,
                "first_name": user.get("display_name") or user.get("username", ""),
                "last_name": "",
                "username": user.get("username", ""),
                "photo_url": user.get("avatar_url", ""),
                "is_account": True,
                "account_username": user.get("username"),
            },
            "created_at": datetime.now(timezone.utc).timestamp(),
        }
        engine.save_sessions(engine.android_sessions)
    except Exception:
        pass


def new_mb_uid():
    return 800000000 + secrets.randbelow(200000000)




def _empty_db():
    return {"users": {}, "sessions": {}, "playlists": {}, "requests": {}}


def _read_db_file(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        for key, value in _empty_db().items():
            data.setdefault(key, value)
        return data
    except Exception:
        return None


def load_db():
    data = _read_db_file(DB_PATH) if os.path.exists(DB_PATH) else None
    if data is None:
        backup = DB_PATH + ".bak"
        if os.path.exists(backup):
            print(f"[db] restoring from backup {backup}", flush=True)
            data = _read_db_file(backup)
    return data if data is not None else _empty_db()


def save_db(db):
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp = DB_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
    if os.path.exists(DB_PATH):
        try:
            shutil.copyfile(DB_PATH, DB_PATH + ".bak")
        except Exception:
            pass
    os.replace(tmp, DB_PATH)


def now_iso():
    return datetime.now(timezone.utc).isoformat()


def hash_password(password, salt=None):
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), 120000)
    return salt, digest.hex()


def verify_password(password, salt, expected):
    _, digest = hash_password(password, salt)
    return secrets.compare_digest(digest, expected)


def ensure_mb_uid(user):
    uid = user.get("mb_uid")
    if not uid:
        uid = new_mb_uid()
        user["mb_uid"] = uid
    return uid


def user_brief(user):
    return {
        "id": user["id"],
        "username": user.get("username", ""),
        "display_name": user.get("display_name", ""),
        "avatar_url": user.get("avatar_url", ""),
        "status": user.get("status", "pending"),
        "created_at": user.get("created_at", ""),
    }


def user_profile(user):
    return {
        "id": user.get("mb_uid") or 0,
        "first_name": user.get("display_name") or user.get("username", ""),
        "last_name": "",
        "username": user.get("username", ""),
        "photo_url": user.get("avatar_url", ""),
        "is_account": True,
        "display_name": user.get("display_name", ""),
        "telegram_id": None,
    }


def user_account(user):
    return {
        "uid": user.get("mb_uid") or 0,
        "username": user.get("username", ""),
        "display_name": user.get("display_name", ""),
        "avatar_url": user.get("avatar_url", ""),
        "telegram_id": None,
    }


def sanitize_name(value):
    return re.sub(r'[\\/:*?"<>|]', "_", (value or "").strip()) or "item"


def user_dir(user):
    path = os.path.join(DATA_DIR, "playlists", sanitize_name(user.get("username")))
    os.makedirs(path, exist_ok=True)
    return path


def _playlist_path(user, name):
    return os.path.join(user_dir(user), sanitize_name(name) + ".json")


def read_playlist(user, name):
    path = _playlist_path(user, name)
    if not os.path.exists(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def write_playlist(user, name, data):
    data = dict(data)
    data["name"] = name
    with open(_playlist_path(user, name), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def delete_playlist_file(user, name):
    path = _playlist_path(user, name)
    if os.path.exists(path):
        os.remove(path)
    shutil.rmtree(os.path.join(user_dir(user), sanitize_name(name)), ignore_errors=True)


def playlist_audio_dir(user, name):
    directory = os.path.join(user_dir(user), sanitize_name(name))
    os.makedirs(directory, exist_ok=True)
    return directory


def playlist_audio_path(user, name, track):
    base = sanitize_name(f"{track.get('artist') or ''} - {track.get('title') or ''}")
    return os.path.join(playlist_audio_dir(user, name), base + ".mp3")


_track_download_tasks = set()


def start_track_download(user, playlist_name, track):
    """Download the audio for a playlist track onto the server in the background."""
    if engine is None:
        return
    task = asyncio.create_task(_download_playlist_track(user, playlist_name, dict(track)))
    _track_download_tasks.add(task)
    task.add_done_callback(_track_download_tasks.discard)


async def _download_playlist_track(user, playlist_name, track):
    path = playlist_audio_path(user, playlist_name, track)
    if os.path.exists(path) and os.path.getsize(path) > 1000:
        _mark_playlist_file(user, playlist_name, track, path)
        return
    try:
        rc = await engine.download_track_with_fallback(
            {}, track, path[:-4],
            track.get("title") or "", track.get("artist") or "",
            show_progress=False,
        )
    except Exception:
        rc = 1
    if rc == 0 and os.path.exists(path) and os.path.getsize(path) > 1000:
        _mark_playlist_file(user, playlist_name, track, path)


def _mark_playlist_file(user, playlist_name, track, path):
    playlist = read_playlist(user, playlist_name)
    if playlist is None:
        return
    changed = False
    for stored in playlist.get("tracks", []):
        if track_key(stored) == track_key(track):
            stored["playlist_file"] = path
            try:
                duration = engine.probe_local_track_duration(path)
                if duration:
                    stored["duration_seconds"] = int(duration)
                    stored["duration"] = int(duration)
            except Exception:
                pass
            changed = True
    if changed:
        write_playlist(user, playlist_name, playlist)


def _clean_track(track):
    """Ensure numeric fields stay numeric so the app never fails to parse a track."""
    if not isinstance(track, dict):
        return track
    cleaned = dict(track)
    for field in ("duration", "duration_seconds", "bitrate_kbps"):
        if field not in cleaned or cleaned[field] is None:
            continue
        value = cleaned[field]
        if isinstance(value, bool):
            cleaned.pop(field, None)
            continue
        if isinstance(value, (int, float)):
            cleaned[field] = int(value)
            continue
        try:
            cleaned[field] = int(float(str(value)))
        except Exception:
            cleaned.pop(field, None)
    return cleaned


def user_playlists(user):
    _migrate_favorites(user)
    directory = user_dir(user)
    result = []
    for filename in os.listdir(directory):
        if not filename.endswith(".json") or filename == "artists.json":
            continue
        try:
            with open(os.path.join(directory, filename), "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            continue
        name = data.get("name") or filename[:-5]
        tracks = [_clean_track(t) for t in data.get("tracks", [])]
        result.append({
            "name": name,
            "track_count": len(tracks),
            "cover_url": data.get("cover_url", ""),
            "is_favorites": name == FAVORITES_NAME or name in LEGACY_FAVORITES_NAMES,
            "tracks": tracks,
        })
    result.sort(key=lambda p: (not p["is_favorites"], p["name"].lower()))
    return result


def _migrate_favorites(user):
    """Rename a legacy favorites playlist (e.g. "Мне нравится") to FAVORITES_NAME."""
    directory = user_dir(user)
    if read_playlist(user, FAVORITES_NAME) is not None:
        return
    for legacy in LEGACY_FAVORITES_NAMES:
        legacy_json = os.path.join(directory, sanitize_name(legacy) + ".json")
        if not os.path.exists(legacy_json):
            continue
        try:
            with open(legacy_json, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {"tracks": [], "cover_url": ""}
        write_playlist(user, FAVORITES_NAME, data)
        try:
            os.remove(legacy_json)
        except OSError:
            pass
        legacy_dir = os.path.join(directory, sanitize_name(legacy))
        new_dir = os.path.join(directory, sanitize_name(FAVORITES_NAME))
        if os.path.isdir(legacy_dir) and not os.path.exists(new_dir):
            try:
                os.rename(legacy_dir, new_dir)
            except OSError:
                pass
        return


def ensure_favorites(user):
    _migrate_favorites(user)
    if read_playlist(user, FAVORITES_NAME) is None:
        write_playlist(user, FAVORITES_NAME, {"tracks": [], "cover_url": ""})


def artists_path(user):
    return os.path.join(user_dir(user), "artists.json")


def _load_artists(user):
    path = artists_path(user)
    if not os.path.exists(path):
        return []
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception:
        return []


def _save_artists(user, artists):
    with open(artists_path(user), "w", encoding="utf-8") as f:
        json.dump(artists, f, ensure_ascii=False, indent=2)


def record_listen(user, track):
    """Accumulate listening time per artist (favorite artists are time-based).

    Collaborations are split into separate artists ('A feat B', 'A, B', 'A & B'),
    and channel junk like '- Topic' is stripped.
    """
    raw = (track.get("artist") or "").strip()
    names = engine.split_artists(raw) if engine is not None else [raw]
    names = [n for n in names if n and n.lower() != "unknown artist"]
    if not names:
        return
    cover = (track.get("cover") or track.get("cover_url") or "").strip()
    try:
        seconds = int(track.get("duration") or track.get("duration_seconds") or 0)
    except Exception:
        seconds = 0
    if seconds <= 0:
        seconds = 180
    per = max(1, seconds // len(names))
    artists = _load_artists(user)
    for name in names:
        key = name.lower()
        found = next((a for a in artists if (a.get("name", "").strip().lower() == key)), None)
        if found:
            found["seconds"] = int(found.get("seconds") or 0) + per
            if cover and not found.get("avatar_url"):
                found["avatar_url"] = cover
        else:
            artists.append({"name": name, "avatar_url": cover, "seconds": per})
    _save_artists(user, artists)


def migrate_artists():
    """One-time cleanup of existing artists.json files (strip junk, merge duplicates)."""
    if engine is None:
        return
    root = os.path.join(DATA_DIR, "playlists")
    if not os.path.isdir(root):
        return
    for login in os.listdir(root):
        path = os.path.join(root, login, "artists.json")
        if not os.path.isfile(path):
            continue
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            continue
        if not isinstance(data, list):
            continue
        merged = {}
        for entry in data:
            name = engine.clean_artist(entry.get("name", ""))
            if not name:
                continue
            key = name.lower()
            secs = int(entry.get("seconds") or 0)
            if key in merged:
                merged[key]["seconds"] += secs
                if not merged[key].get("avatar_url"):
                    merged[key]["avatar_url"] = entry.get("avatar_url", "")
            else:
                merged[key] = {"name": name, "avatar_url": entry.get("avatar_url", ""), "seconds": secs}
        try:
            with open(path, "w", encoding="utf-8") as f:
                json.dump(list(merged.values()), f, ensure_ascii=False, indent=2)
        except Exception:
            pass



def list_favorite_artists(user, limit=None):
    artists = [a for a in _load_artists(user) if int(a.get("seconds") or 0) > 0]
    artists.sort(key=lambda a: int(a.get("seconds") or 0), reverse=True)
    result = []
    for a in artists:
        secs = int(a.get("seconds") or 0)
        result.append({
            "name": a.get("name", ""),
            "avatar_url": a.get("avatar_url", ""),
            "seconds": secs,
            "hours": round(secs / 3600, 2),
        })
    return result[:limit] if limit else result


def track_key(track):
    return f"{(track.get('artist') or '').strip().lower()}|{(track.get('title') or '').strip().lower()}"


def current_user(request):
    db = request.app["db"]
    token = request.headers.get("X-Session-Token", "") or request.cookies.get("session_token", "")
    if not token:
        return None, None
    session = db["sessions"].get(token)
    if not session:
        return None, None
    user = db["users"].get(session["uid"])
    if not user or user.get("status") != "approved":
        return None, None
    return token, user


@web.middleware
async def db_middleware(request, handler):
    db = request.app.get("db")
    if db is None:
        db = load_db()
        request.app["db"] = db
    if engine is not None:
        token = request.headers.get("X-Session-Token", "") or request.cookies.get("session_token", "")
        if token and token not in engine.android_sessions:
            session = db["sessions"].get(token)
            if session:
                user = db["users"].get(session["uid"])
                if user and user.get("status") == "approved":
                    if not user.get("mb_uid"):
                        ensure_mb_uid(user)
                    engine_register_session(user["mb_uid"], token, user)
    return await handler(request)



async def api_register(request):
    db = request.app["db"]
    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные."}, status=400)
    username = (payload.get("username") or "").strip()
    password = payload.get("password") or ""
    if len(username) < 3 or len(password) < 4:
        return web.json_response({"ok": False, "error": "BAD_CREDENTIALS", "message": "Username must be at least 3 and password at least 4 characters."}, status=400)
    for user in db["users"].values():
        if user.get("username", "").lower() == username.lower():
            return web.json_response({"ok": False, "error": "USERNAME_TAKEN", "message": "That username is already taken."}, status=409)
    uid = secrets.token_hex(8)
    salt, digest = hash_password(password)
    db["users"][uid] = {
        "id": uid,
        "mb_uid": new_mb_uid(),
        "username": username,
        "display_name": username,
        "avatar_url": "",
        "salt": salt,
        "password_hash": digest,
        "status": "pending",
        "created_at": now_iso(),
    }
    db["requests"][uid] = {"uid": uid, "created_at": now_iso()}
    async with _lock:
        save_db(db)
    return web.json_response({"ok": True, "message": "Заявка отправлена. Дождитесь одобрения администратора."})


async def api_login(request):
    db = request.app["db"]
    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные."}, status=400)
    username = (payload.get("username") or "").strip()
    password = payload.get("password") or ""
    user = next((u for u in db["users"].values() if u.get("username", "").lower() == username.lower()), None)
    if not user or not verify_password(password, user["salt"], user["password_hash"]):
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Wrong username or password."}, status=401)
    if user.get("status") == "pending":
        return web.json_response({"ok": False, "error": "PENDING", "message": "Your account is still waiting for approval."}, status=403)
    if user.get("status") == "rejected":
        return web.json_response({"ok": False, "error": "REJECTED", "message": "Your request was rejected."}, status=403)
    ensure_favorites(user)
    token = secrets.token_urlsafe(32)
    db["sessions"][token] = {"uid": user["id"], "created_at": now_iso()}
    ensure_mb_uid(user)
    async with _lock:
        save_db(db)
    engine_register_session(user["mb_uid"], token, user)
    return web.json_response({"ok": True, "token": token})



async def api_bootstrap(request):
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    ensure_favorites(user)
    if not user.get("mb_uid"):
        ensure_mb_uid(user)
        async with _lock:
            save_db(request.app["db"])
    return web.json_response({
        "ok": True,
        "authorized": True,
        "profile": user_profile(user),
        "account": user_account(user),
        "playlists": user_playlists(user),
        "favorite_artists": list_favorite_artists(user),
    })


async def api_playlists(request):
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    return web.json_response({"ok": True, "playlists": user_playlists(user)})


async def api_create_playlist(request):
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    payload = await request.json()
    name = (payload.get("playlist_name") or "").strip()
    if not name:
        return web.json_response({"ok": False, "error": "PLAYLIST_NAME_MISSING"}, status=400)
    if read_playlist(user, name) is not None:
        return web.json_response({"ok": False, "error": "PLAYLIST_EXISTS", "message": "Уже существует."}, status=409)
    write_playlist(user, name, {"tracks": [], "cover_url": ""})
    return web.json_response({"ok": True, "playlist_name": name, "playlists": user_playlists(user)})


async def api_remove_playlist(request):
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    payload = await request.json()
    name = (payload.get("playlist_name") or "").strip()
    if name == FAVORITES_NAME or name in LEGACY_FAVORITES_NAMES:
        return web.json_response(
            {"ok": False, "error": "PLAYLIST_DELETE_FORBIDDEN", "message": "The favorites playlist cannot be deleted."},
            status=400,
        )
    delete_playlist_file(user, name)
    return web.json_response({"ok": True, "playlists": user_playlists(user)})


async def api_add_track(request):
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    payload = await request.json()
    name = (payload.get("playlist_name") or "").strip()
    track = payload.get("track") or {}
    playlist = read_playlist(user, name)
    if playlist is None:
        return web.json_response({"ok": False, "error": "PLAYLIST_NOT_FOUND"}, status=404)
    tracks = playlist.get("tracks", [])
    added = False
    if not any(track_key(t) == track_key(track) for t in tracks):
        tracks.append(track)
        added = True
    playlist["tracks"] = tracks
    write_playlist(user, name, playlist)
    if added:
        start_track_download(user, name, track)
    return web.json_response({"ok": True, "playlists": user_playlists(user)})


async def api_remove_track(request):
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    payload = await request.json()
    name = (payload.get("playlist_name") or "").strip()
    track = payload.get("track") or {}
    playlist = read_playlist(user, name)
    if playlist is not None:
        removed = [t for t in playlist.get("tracks", []) if track_key(t) == track_key(track)]
        playlist["tracks"] = [t for t in playlist.get("tracks", []) if track_key(t) != track_key(track)]
        write_playlist(user, name, playlist)
        for t in removed:
            f = t.get("playlist_file")
            if f:
                try:
                    if os.path.exists(f):
                        os.remove(f)
                except Exception:
                    pass
    return web.json_response({"ok": True, "playlists": user_playlists(user)})


async def api_rename_track(request):
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    payload = await request.json()
    name = (payload.get("playlist_name") or "").strip()
    track = payload.get("track") or {}
    title = (payload.get("title") or "").strip()
    artist = (payload.get("artist") or "").strip()
    if not title and not artist:
        return web.json_response({"ok": False, "error": "EMPTY_NAME"}, status=400)
    playlist = read_playlist(user, name)
    if playlist is None:
        return web.json_response({"ok": False, "error": "PLAYLIST_NOT_FOUND"}, status=404)
    for stored in playlist.get("tracks", []):
        if track_key(stored) == track_key(track):
            stored["title"] = title or stored.get("title", "")
            stored["artist"] = artist
            stored["full_title"] = f"{artist} - {title}".strip(" -")
            break
    write_playlist(user, name, playlist)
    return web.json_response({"ok": True, "playlists": user_playlists(user)})


async def api_playlist_cover(request):
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    payload = await request.json()
    name = (payload.get("playlist_name") or "").strip()
    cover = (payload.get("cover_url") or "").strip()
    playlist = read_playlist(user, name)
    if playlist is None:
        return web.json_response({"ok": False, "error": "PLAYLIST_NOT_FOUND"}, status=404)
    playlist["cover_url"] = cover
    write_playlist(user, name, playlist)
    return web.json_response({"ok": True, "playlists": user_playlists(user)})


async def api_toggle_favorite(request):
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    payload = await request.json()
    track = payload.get("track") or {}
    playlist = read_playlist(user, FAVORITES_NAME) or {"tracks": [], "cover_url": ""}
    tracks = playlist.get("tracks", [])
    added = False
    if any(track_key(t) == track_key(track) for t in tracks):
        playlist["tracks"] = [t for t in tracks if track_key(t) != track_key(track)]
    else:
        tracks.append(track)
        playlist["tracks"] = tracks
        added = True
    write_playlist(user, FAVORITES_NAME, playlist)
    if added:
        start_track_download(user, FAVORITES_NAME, track)
    return web.json_response({"ok": True, "playlists": user_playlists(user)})


async def api_favorite_artists(request):
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    return web.json_response({"ok": True, "favorite_artists": list_favorite_artists(user)})


async def api_play_track(request):
    """Play a track: record listening time (for favorite artists) then delegate to the engine."""
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    try:
        payload = await request.json()
    except Exception:
        payload = {}
    track = payload.get("track") or {}
    playlist_name = (payload.get("playlist_name") or "").strip()
    record_listen(user, track)
    if playlist_name:
        playlist = read_playlist(user, playlist_name)
        if playlist:
            stored = next(
                (t for t in playlist.get("tracks", []) if track_key(t) == track_key(track)),
                None,
            )
            path = (stored or {}).get("playlist_file")
            if path and os.path.exists(path) and os.path.getsize(path) > 1000:
                media = engine.register_webapp_media(
                    user.get("mb_uid") or 0,
                    path,
                    track.get("title") or (stored or {}).get("title") or "",
                    track.get("artist") or (stored or {}).get("artist") or "",
                    track.get("src") or (stored or {}).get("src") or "SC",
                    track.get("cover") or track.get("cover_url") or (stored or {}).get("cover") or "",
                )
                return web.json_response({"ok": True, "status": "ready", "track": media})
    return await engine.webapp_play_track(request)


async def api_statistics(request):
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    playlists = user_playlists(user)
    track_count = sum(p["track_count"] for p in playlists)
    favorites = list_favorite_artists(user, limit=6)
    top_artists = [
        {
            "key": a["name"].lower(),
            "name": a["name"],
            "seconds": a["seconds"],
            "hours": a["hours"],
            "avatar_url": a["avatar_url"],
        }
        for a in favorites
    ]
    return web.json_response({
        "ok": True,
        "statistics": {
            "total_active_seconds": sum(a["seconds"] for a in list_favorite_artists(user)),
            "total_active_hours": round(sum(a["seconds"] for a in list_favorite_artists(user)) / 3600, 2),
            "total_active_days": 0.0,
            "playlist_count": len(playlists),
            "track_count": track_count,
            "top_artists": top_artists,
        },
    })


async def api_leaderboard(request):
    """Rank approved users by total listening time."""
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    db = request.app["db"]
    entries = []
    for u in db["users"].values():
        if u.get("status") != "approved":
            continue
        total = 0
        for artist in _load_artists(u):
            total += int(artist.get("seconds") or 0)
        entries.append({
            "name": u.get("display_name") or u.get("username") or "—",
            "username": u.get("username", ""),
            "avatar_url": u.get("avatar_url", ""),
            "seconds": total,
        })
    entries.sort(key=lambda e: e["seconds"], reverse=True)
    me_rank = 0
    me_entry = None
    for i, e in enumerate(entries, start=1):
        e["rank"] = i
        e["hours"] = round(e["seconds"] / 3600, 2)
        if e["username"] == user.get("username"):
            me_rank = i
            me_entry = e
    if me_entry is None:
        total = sum(int(a.get("seconds") or 0) for a in _load_artists(user))
        me_entry = {
            "name": user.get("display_name") or user.get("username") or "—",
            "username": user.get("username", ""),
            "avatar_url": user.get("avatar_url", ""),
            "seconds": total,
            "hours": round(total / 3600, 2),
            "rank": 0,
        }
    return web.json_response({
        "ok": True,
        "top": entries[:10],
        "me": me_entry,
    })


async def api_account_profile(request):
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    return web.json_response({"ok": True, "profile": user_profile(user), "account": user_account(user)})


async def api_update_profile(request):
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    db = request.app["db"]
    payload = await request.json()
    if "display_name" in payload:
        db["users"][user["id"]]["display_name"] = (payload.get("display_name") or "").strip()
    if "avatar_url" in payload:
        db["users"][user["id"]]["avatar_url"] = (payload.get("avatar_url") or "").strip()
    async with _lock:
        save_db(db)
    return web.json_response({"ok": True})


async def api_avatar_upload(request):
    _, user = current_user(request)
    if not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    reader = await request.multipart()
    field = await reader.next()
    if field is None:
        return web.json_response({"ok": False, "error": "NO_FILE"}, status=400)
    data = await field.read()
    ext = os.path.splitext(field.filename or "")[1] or ".jpg"
    avatar_dir = os.path.join(DATA_DIR, "avatars")
    os.makedirs(avatar_dir, exist_ok=True)
    name = f"{user['id']}{ext}"
    with open(os.path.join(avatar_dir, name), "wb") as f:
        f.write(data)
    db = request.app["db"]
    db["users"][user["id"]]["avatar_url"] = f"/api/avatar/{name}"
    async with _lock:
        save_db(db)
    return web.json_response({"ok": True, "account": user_account(db["users"][user["id"]])})


async def api_avatar_serve(request):
    name = os.path.basename(request.match_info.get("name", ""))
    path = os.path.join(DATA_DIR, "avatars", name)
    if not os.path.isfile(path):
        raise web.HTTPNotFound()
    return web.FileResponse(path)


async def api_ok(request):
    return web.json_response({"ok": True})


async def api_not_implemented(request):
    return web.json_response({
        "ok": False,
        "error": "NOT_IMPLEMENTED",
        "message": "Функция ещё не реализована на этом сервере.",
    }, status=501)


async def api_app_version(request):
    info = {"version_code": 0, "version_name": "", "notes": ""}
    if os.path.exists(VERSION_PATH):
        try:
            with open(VERSION_PATH, "r", encoding="utf-8") as f:
                info = json.load(f)
        except Exception:
            pass
    apk_exists = os.path.isfile(APP_APK_PATH)
    return web.json_response({
        "ok": True,
        "version_code": int(info.get("version_code") or 0),
        "version_name": str(info.get("version_name") or ""),
        "notes": str(info.get("notes") or ""),
        "size": os.path.getsize(APP_APK_PATH) if apk_exists else 0,
        "download_url": "/api/app/download" if apk_exists else "",
    })


async def api_app_download(request):
    if not os.path.isfile(APP_APK_PATH):
        raise web.HTTPNotFound(text="APK not found")
    return web.FileResponse(
        APP_APK_PATH,
        headers={
            "Content-Type": "application/vnd.android.package-archive",
            "Content-Disposition": 'attachment; filename="MusicFind.apk"',
        },
    )



def page(title, body):
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>{html.escape(title)}</title>
<style>
  :root {{ color-scheme: dark; }}
  * {{ box-sizing: border-box; }}
  body {{ margin: 0; min-height: 100vh; font-family: -apple-system, Segoe UI, Roboto, sans-serif;
    background: radial-gradient(1200px 800px at 50% -10%, #12321f 0%, #05070b 60%); color: #e2e8f0; }}
  a {{ color: #22c55e; text-decoration: none; }}
  .topbar {{ display: flex; justify-content: flex-end; padding: 16px 20px; }}
  .btn {{ display: inline-block; padding: 10px 16px; border-radius: 14px; border: 1px solid rgba(255,255,255,.15);
    background: rgba(255,255,255,.06); color: #e2e8f0; cursor: pointer; font-size: 14px; }}
  .btn.primary {{ background: #22c55e; color: #04121b; border-color: transparent; font-weight: 600; }}
  .btn.danger {{ background: rgba(244,63,94,.18); color: #fecdd3; border-color: rgba(244,63,94,.4); }}
  .center {{ min-height: calc(100vh - 72px); display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; padding: 24px; }}
  .brand {{ font-size: 44px; font-weight: 800; letter-spacing: -1px; }}
  .tagline {{ margin-top: 8px; color: #94a3b8; font-size: 18px; }}
  .card {{ background: rgba(255,255,255,.05); border: 1px solid rgba(255,255,255,.1); border-radius: 20px; padding: 20px; }}
  .wrap {{ max-width: 960px; margin: 0 auto; padding: 0 20px 40px; }}
  h2 {{ margin: 28px 0 12px; }}
  table {{ width: 100%; border-collapse: collapse; }}
  th, td {{ text-align: left; padding: 10px 12px; border-bottom: 1px solid rgba(255,255,255,.08); font-size: 14px; }}
  input {{ width: 100%; padding: 12px 14px; border-radius: 12px; border: 1px solid rgba(255,255,255,.15);
    background: rgba(0,0,0,.3); color: #e2e8f0; font-size: 15px; margin: 6px 0; }}
  form.inline {{ display: inline; }}
  .row {{ display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }}
  .muted {{ color: #94a3b8; font-size: 13px; }}
  .pill {{ padding: 3px 10px; border-radius: 999px; font-size: 12px; }}
  .pending {{ background: rgba(251,191,36,.2); color: #fde68a; }}
  .approved {{ background: rgba(34,197,94,.2); color: #86efac; }}
  .rejected {{ background: rgba(244,63,94,.2); color: #fecdd3; }}
</style>
</head>
<body>
{body}
</body>
</html>"""


async def page_index(request):
    body = """
    <div class="topbar"><a class="btn" href="/admin">Admin panel</a></div>
    <div class="center">
      <div class="brand">MusicFind</div>
      <div class="tagline">the music you like</div>
    </div>"""
    return web.Response(text=page("MusicFind", body), content_type="text/html")


def admin_logged(request):
    return bool(request.cookies.get("admin_session"))


async def page_admin(request):
    if not admin_logged(request):
        body = """
        <div class="center">
          <div class="card" style="width:340px">
            <h2 style="margin-top:0">Admin login</h2>
            <form method="post" action="/admin/login">
              <input type="password" name="password" placeholder="Password" autofocus>
              <button class="btn primary" style="width:100%" type="submit">Sign in</button>
            </form>
          </div>
        </div>"""
        return web.Response(text=page("Admin panel", body), content_type="text/html")

    db = request.app["db"]
    pending = [(uid, db["users"][uid]) for uid in db["requests"] if uid in db["users"]]
    users = list(db["users"].values())

    requests_rows = "".join(
        f"""<tr>
          <td>{html.escape(u.get('username',''))}</td>
          <td class="muted">{html.escape(u.get('created_at',''))}</td>
          <td class="row">
            <form class="inline" method="post" action="/admin/approve"><input type="hidden" name="uid" value="{uid}"><button class="btn primary">Approve</button></form>
            <form class="inline" method="post" action="/admin/reject"><input type="hidden" name="uid" value="{uid}"><button class="btn danger">Reject</button></form>
          </td>
        </tr>""" for uid, u in pending
    ) or '<tr><td colspan="3" class="muted">No pending requests</td></tr>'

    def status_pill(status):
        return f'<span class="pill {status}">{status}</span>'

    accounts_rows = "".join(
        f"""<tr>
          <td>{html.escape(u.get('username',''))}</td>
          <td>{html.escape(u.get('display_name',''))}</td>
          <td>{status_pill(u.get('status',''))}</td>
          <td>
            <form class="inline" method="post" action="/admin/account/update">
              <input type="hidden" name="uid" value="{u['id']}">
              <input name="display_name" value="{html.escape(u.get('display_name',''))}" style="width:150px;display:inline-block">
              <input name="avatar_url" value="{html.escape(u.get('avatar_url',''))}" placeholder="Avatar URL" style="width:180px;display:inline-block">
              <button class="btn">Save</button>
            </form>
            <form class="inline" method="post" action="/admin/account/delete" onsubmit="return confirm('Delete account?')">
              <input type="hidden" name="uid" value="{u['id']}"><button class="btn danger">Delete</button>
            </form>
          </td>
        </tr>""" for u in users
    ) or '<tr><td colspan="4" class="muted">No accounts</td></tr>'

    playlist_rows = []
    for u in users:
        for pl in user_playlists(u):
            name = pl["name"]
            playlist_rows.append(
                f"""<tr>
                  <td>{html.escape(u.get('username',''))}</td>
                  <td>{html.escape(name)}</td>
                  <td>{pl['track_count']}</td>
                  <td>
                    <form class="inline" method="post" action="/admin/playlist/delete" onsubmit="return confirm('Delete playlist?')">
                      <input type="hidden" name="uid" value="{u['id']}"><input type="hidden" name="name" value="{html.escape(name)}">
                      <button class="btn danger">Delete</button>
                    </form>
                  </td>
                </tr>"""
            )
    playlists_html = "".join(playlist_rows) or '<tr><td colspan="4" class="muted">No playlists</td></tr>'

    body = f"""
    <div class="topbar">
      <form class="inline" method="post" action="/admin/logout"><button class="btn">Log out</button></form>
    </div>
    <div class="wrap">
      <h2>Registration requests</h2>
      <div class="card"><table><tr><th>Username</th><th>Created</th><th>Actions</th></tr>{requests_rows}</table></div>

      <h2>Accounts</h2>
      <div class="card"><table><tr><th>Username</th><th>Name</th><th>Status</th><th>Actions</th></tr>{accounts_rows}</table></div>

      <h2>Playlists</h2>
      <div class="card"><table><tr><th>User</th><th>Playlist</th><th>Tracks</th><th></th></tr>{playlists_html}</table></div>
    </div>"""
    return web.Response(text=page("Admin panel", body), content_type="text/html")


async def admin_login(request):
    form = await request.post()
    if (form.get("password") or "") == ADMIN_PASSWORD:
        response = web.HTTPFound("/admin")
        response.set_cookie("admin_session", secrets.token_urlsafe(16), httponly=True, samesite="Lax")
        return response
    raise web.HTTPFound("/admin?error=1")


async def admin_logout(request):
    response = web.HTTPFound("/admin")
    response.del_cookie("admin_session")
    return response


def require_admin(request):
    if not admin_logged(request):
        raise web.HTTPForbidden()


async def admin_approve(request):
    require_admin(request)
    form = await request.post()
    db = request.app["db"]
    uid = form.get("uid")
    if uid in db["users"]:
        db["users"][uid]["status"] = "approved"
        db["requests"].pop(uid, None)
        ensure_favorites(db["users"][uid])
        async with _lock:
            save_db(db)
    raise web.HTTPFound("/admin")


async def admin_reject(request):
    require_admin(request)
    form = await request.post()
    db = request.app["db"]
    uid = form.get("uid")
    if uid in db["users"]:
        db["users"][uid]["status"] = "rejected"
        db["requests"].pop(uid, None)
        async with _lock:
            save_db(db)
    raise web.HTTPFound("/admin")


async def admin_account_update(request):
    require_admin(request)
    form = await request.post()
    db = request.app["db"]
    uid = form.get("uid")
    if uid in db["users"]:
        db["users"][uid]["display_name"] = (form.get("display_name") or "").strip()
        db["users"][uid]["avatar_url"] = (form.get("avatar_url") or "").strip()
        async with _lock:
            save_db(db)
    raise web.HTTPFound("/admin")


async def admin_account_delete(request):
    require_admin(request)
    form = await request.post()
    db = request.app["db"]
    uid = form.get("uid")
    user_obj = db["users"].get(uid)
    db["users"].pop(uid, None)
    if user_obj:
        shutil.rmtree(os.path.join(DATA_DIR, "playlists", sanitize_name(user_obj.get("username"))), ignore_errors=True)
    db["requests"].pop(uid, None)
    for token in [t for t, s in db["sessions"].items() if s.get("uid") == uid]:
        db["sessions"].pop(token, None)
    async with _lock:
        save_db(db)
    raise web.HTTPFound("/admin")


async def admin_playlist_delete(request):
    require_admin(request)
    form = await request.post()
    db = request.app["db"]
    uid = form.get("uid")
    name = form.get("name")
    user_obj = db["users"].get(uid)
    if user_obj and name:
        delete_playlist_file(user_obj, name)
    raise web.HTTPFound("/admin")


def auth_page(mode="login", error="", message=""):
    notice = ""
    if error:
        notice = f'<div class="muted" style="color:#fecdd3;margin-bottom:8px">{html.escape(error)}</div>'
    elif message:
        notice = f'<div class="muted" style="color:#86efac;margin-bottom:8px">{html.escape(message)}</div>'
    if mode == "register":
        form = f"""
        <h2 style="margin-top:0">Sign up</h2>
        <div class="muted" style="margin-bottom:12px">After signing up, an admin has to approve your account.</div>
        {notice}
        <form method="post" action="/createacc">
          <input name="username" placeholder="Username" autofocus>
          <input type="password" name="password" placeholder="Password">
          <button class="btn primary" style="width:100%" type="submit">Request access</button>
        </form>
        <div style="margin-top:12px" class="muted">Already have an account? <a href="/login">Sign in</a></div>"""
        title = "Sign up"
    else:
        form = f"""
        <h2 style="margin-top:0">Sign in</h2>
        {notice}
        <form method="post" action="/login">
          <input name="username" placeholder="Username" autofocus>
          <input type="password" name="password" placeholder="Password">
          <button class="btn primary" style="width:100%" type="submit">Sign in</button>
        </form>
        <div style="margin-top:12px" class="muted">No account yet? <a href="/login?mode=register">Sign up</a></div>"""
        title = "Sign in"
    body = f"""
    <div class="center">
      <div class="card" style="width:360px">
        <div class="brand" style="font-size:28px;text-align:center">MusicFind</div>
        <div class="tagline" style="font-size:14px;margin-bottom:16px;text-align:center">the music you like</div>
        {form}
      </div>
    </div>"""
    return page(title, body)


async def page_login_get(request):
    mode = request.query.get("mode", "login")
    return web.Response(text=auth_page("register" if mode == "register" else "login"), content_type="text/html")


async def page_login_post(request):
    db = request.app["db"]
    form = await request.post()
    username = (form.get("username") or "").strip()
    password = form.get("password") or ""
    user = next((u for u in db["users"].values() if u.get("username", "").lower() == username.lower()), None)
    if not user or not verify_password(password, user["salt"], user["password_hash"]):
        return web.Response(text=auth_page("login", error="Wrong username or password"), content_type="text/html", status=401)
    if user.get("status") == "pending":
        return web.Response(text=auth_page("login", error="Your account is still waiting for approval"), content_type="text/html", status=403)
    if user.get("status") == "rejected":
        return web.Response(text=auth_page("login", error="Your request was rejected"), content_type="text/html", status=403)
    token = secrets.token_urlsafe(32)
    db["sessions"][token] = {"uid": user["id"], "created_at": now_iso()}
    ensure_favorites(user)
    ensure_mb_uid(user)
    async with _lock:
        save_db(db)
    engine_register_session(user["mb_uid"], token, user)
    response = web.HTTPFound("/")
    response.set_cookie("session_token", token, httponly=True, samesite="Lax")
    return response


async def page_createacc_get(request):
    return web.Response(text=auth_page("register"), content_type="text/html")


async def page_createacc_post(request):
    db = request.app["db"]
    form = await request.post()
    username = (form.get("username") or "").strip()
    password = form.get("password") or ""
    if len(username) < 3 or len(password) < 4:
        return web.Response(text=auth_page("register", error="Username must be at least 3 and password at least 4 characters"), content_type="text/html", status=400)
    if any(u.get("username", "").lower() == username.lower() for u in db["users"].values()):
        return web.Response(text=auth_page("register", error="That username is already taken"), content_type="text/html", status=409)
    uid = secrets.token_hex(8)
    salt, digest = hash_password(password)
    db["users"][uid] = {
        "id": uid, "mb_uid": new_mb_uid(), "username": username, "display_name": username, "avatar_url": "",
        "salt": salt, "password_hash": digest, "status": "pending", "created_at": now_iso(),
    }
    db["requests"][uid] = {"uid": uid, "created_at": now_iso()}
    async with _lock:
        save_db(db)
    return web.Response(text=auth_page("register", message="Request sent. Wait for approval."), content_type="text/html")



def register_engine_routes(app):
    """Mount the vendored engine's music handlers in-process (no proxy, no bot)."""
    if engine is None:
        return
    app.router.add_get("/api/search", engine.webapp_search)
    app.router.add_post("/api/recognize-track", engine.webapp_recognize_track)
    app.router.add_post("/api/play-track", api_play_track)
    app.router.add_post("/api/play-playlist-track", api_play_track)
    app.router.add_get("/api/download-status", engine.webapp_download_status)
    app.router.add_post("/api/cancel-download", engine.webapp_cancel_download)
    app.router.add_get("/api/lyrics", engine.webapp_lyrics)
    app.router.add_get("/api/media/{media_id}", engine.webapp_media)
    app.router.add_get("/api/media-recognition/{media_id}", engine.webapp_media_recognition)
    app.router.add_get("/api/cover/{key:.+}", engine.webapp_serve_cover)
    app.router.add_get("/api/dl/{token}", engine.webapp_serve_download)


def build_app():
    app = web.Application(middlewares=[db_middleware])

    app.router.add_get("/", page_index)
    app.router.add_get("/admin", page_admin)
    app.router.add_post("/admin/login", admin_login)
    app.router.add_post("/admin/logout", admin_logout)
    app.router.add_post("/admin/approve", admin_approve)
    app.router.add_post("/admin/reject", admin_reject)
    app.router.add_post("/admin/account/update", admin_account_update)
    app.router.add_post("/admin/account/delete", admin_account_delete)
    app.router.add_post("/admin/playlist/delete", admin_playlist_delete)

    app.router.add_get("/login", page_login_get)
    app.router.add_post("/login", page_login_post)
    app.router.add_get("/createacc", page_createacc_get)
    app.router.add_post("/createacc", page_createacc_post)

    app.router.add_post("/api/account/register", api_register)
    app.router.add_post("/api/account/login", api_login)
    app.router.add_get("/api/account/profile", api_account_profile)
    app.router.add_post("/api/account/profile/update", api_update_profile)
    app.router.add_post("/api/account/avatar", api_avatar_upload)
    app.router.add_post("/api/account/unlink-telegram", api_ok)
    app.router.add_get("/api/avatar/{name}", api_avatar_serve)

    app.router.add_get("/api/bootstrap", api_bootstrap)
    app.router.add_get("/api/playlists", api_playlists)
    app.router.add_post("/api/create-playlist", api_create_playlist)
    app.router.add_post("/api/remove-playlist", api_remove_playlist)
    app.router.add_post("/api/add-track-to-playlist", api_add_track)
    app.router.add_post("/api/remove-playlist-track", api_remove_track)
    app.router.add_post("/api/rename-track", api_rename_track)
    app.router.add_post("/api/playlist-cover", api_playlist_cover)
    app.router.add_post("/api/toggle-favorite", api_toggle_favorite)
    app.router.add_route("*", "/api/artists/favorite", api_favorite_artists)
    app.router.add_get("/api/statistics", api_statistics)
    app.router.add_get("/api/leaderboard", api_leaderboard)
    app.router.add_post("/api/activity-ping", api_ok)
    app.router.add_post("/api/release-media", api_ok)

    app.router.add_get("/api/app/version", api_app_version)
    app.router.add_get("/api/app/download", api_app_download)

    register_engine_routes(app)

    async def _on_startup(application):
        application["db"] = load_db()
        engine_init()
        if AUTO_UPDATE:
            application["updater"] = asyncio.create_task(auto_update_loop())

    async def _on_cleanup(application):
        task = application.get("updater")
        if task:
            task.cancel()

    app.on_startup.append(_on_startup)
    app.on_cleanup.append(_on_cleanup)

    return app


async def _run_tool(cmd, timeout=900, env=None):
    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
            env=env,
        )
        out, _ = await asyncio.wait_for(proc.communicate(), timeout=timeout)
        return proc.returncode or 0, (out or b"").decode(errors="ignore")[-1500:]
    except Exception as exc:
        return 1, str(exc)


async def update_ytdlp():
    """Update yt-dlp inside this server's virtualenv."""
    code, out = await _run_tool([
        sys.executable, "-m", "pip", "install", "-U",
        "--disable-pip-version-check", "yt-dlp",
    ], timeout=900)
    print(f"[auto-update] yt-dlp rc={code}: {out.strip()[-300:]}", flush=True)


async def update_ffmpeg():
    """Update ffmpeg/ffprobe from the system package manager (best effort)."""
    if not shutil.which("apt-get"):
        return
    env = dict(os.environ, DEBIAN_FRONTEND="noninteractive")
    await _run_tool(["apt-get", "update"], timeout=600, env=env)
    code, out = await _run_tool(
        ["apt-get", "install", "-y", "--only-upgrade", "ffmpeg"],
        timeout=1200, env=env,
    )
    print(f"[auto-update] ffmpeg rc={code}: {out.strip()[-300:]}", flush=True)


async def auto_update_loop():
    """Periodically update yt-dlp and ffmpeg so downloads keep working."""
    await asyncio.sleep(60)
    while True:
        try:
            await update_ytdlp()
            await update_ffmpeg()
        except Exception as exc:
            print(f"[auto-update] error: {exc}", flush=True)
        await asyncio.sleep(max(1800, AUTO_UPDATE_INTERVAL))



def engine_init():
    """Initialize the vendored engine's state (playlists, sessions, caches)."""
    if engine is None:
        return
    for method in (
        "load_known_users", "load_whitelist", "load_accounts",
        "load_telegram_profiles", "load_now_playing", "load_recommendations_cache",
    ):
        fn = getattr(engine, method, None)
        if fn:
            try:
                fn()
            except Exception:
                pass
    migrate_artists()


if __name__ == "__main__":
    os.makedirs(DATA_DIR, exist_ok=True)
    print(f"MusicFind server on http://{HOST}:{PORT}")
    web.run_app(build_app(), host=HOST, port=PORT, print=None)
