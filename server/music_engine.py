from __future__ import annotations
import os
import asyncio
import subprocess
import logging
import secrets
import uuid
import shutil
import json
import hashlib
import hmac
import fcntl
import random
import re
import tempfile
import threading
from datetime import datetime, timedelta
from urllib.parse import parse_qsl, quote_plus
from shazamio import Shazam, Serialize
from dotenv import load_dotenv
from aiohttp import web, ClientSession, ClientTimeout

load_dotenv()
TOKEN = os.getenv("TOKEN")
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
SC_COOKIES = os.path.join(BASE_DIR, "sc_cookies.txt")
YT_COOKIES = os.path.join(BASE_DIR, "yt_cookies.txt")
YTDLP = os.getenv("YTDLP_PATH") or shutil.which("yt-dlp") or os.path.join(BASE_DIR, ".venv", "bin", "yt-dlp")
FFMPEG = os.getenv("FFMPEG_PATH") or shutil.which("ffmpeg") or "ffmpeg"
FFPROBE = os.getenv("FFPROBE_PATH") or shutil.which("ffprobe") or ""

PER_PAGE = 6
TMP_DIR = "/tmp/music"
WEBAPP_MEDIA_DIR = os.path.join(TMP_DIR, "webapp_media")
TEMP_STORAGE_MAX_AGE_SECONDS = 24 * 60 * 60
TEMP_STORAGE_CLEANUP_INTERVAL_SECONDS = 24 * 60 * 60
RECOMMENDATIONS_TTL_HOURS = 24
ARTIST_RECOMMENDATIONS_TARGET = 10
GENRE_RECOMMENDATIONS_TARGET = 10
PLAYLIST_DIR = os.path.join(BASE_DIR, "playlists")
RECOMMENDATIONS_CACHE = os.path.join(BASE_DIR, "recommendations_cache.json")
KNOWN_USERS_FILE = os.path.join(BASE_DIR, "known_users.json")
WHITELIST_FILE = os.path.join(BASE_DIR, "whitelist.json")
NOW_PLAYING_FILE = os.path.join(BASE_DIR, "now_playing.json")
ACCOUNTS_FILE = os.path.join(BASE_DIR, "accounts.json")
TELEGRAM_PROFILES_FILE = os.path.join(BASE_DIR, "telegram_profiles.json")
WEBAPP_DIR = os.path.join(BASE_DIR, "webapp")
APP_VERSION_FILE = os.path.join(BASE_DIR, "app_version.json")
APP_APK_PATH = os.getenv("APP_APK_PATH", "").strip() or os.path.join(WEBAPP_DIR, "MusicFind-debug.apk")
DEFAULT_APP_VERSION_CODE = 1
DEFAULT_APP_VERSION_NAME = "1.0.0"
LOCK_FILE = os.getenv("MUSICBOT_LOCK_FILE", "/tmp/musicbot.lock").strip() or "/tmp/musicbot.lock"
PLAYLIST_META_FILENAME = ".playlist_meta.json"
LIKED_PLAYLIST_NAME = "Мне нравится"

BOT_PASSWORD = os.getenv("BOT_PASSWORD", "")
ADMIN_ID = int(os.getenv("ADMIN_ID", "0") or "0")
WEBAPP_URL = os.getenv("WEBAPP_URL", "").strip()
WEBAPP_FALLBACK_URL = os.getenv("WEBAPP_FALLBACK_URL", "").strip()
WEBAPP_EFFECTIVE_URL = ""
WEBAPP_BIND = os.getenv("WEBAPP_BIND", "127.0.0.1").strip() or "127.0.0.1"
WEBAPP_PORT = int(os.getenv("WEBAPP_PORT", "8080") or "8080")
APP_API_BIND = os.getenv("APP_API_BIND", "127.0.0.1").strip() or "127.0.0.1"
APP_API_PORT = int(os.getenv("APP_API_PORT", "8081") or "8081")
CLOUDFLARE_TUNNEL_AUTO = os.getenv("CLOUDFLARE_TUNNEL_AUTO", "").strip().lower() in {"1", "true", "yes", "on"}
WEBAPP_TUNNEL_PROVIDER = os.getenv("WEBAPP_TUNNEL_PROVIDER", "").strip().lower()
CLOUDFLARED = os.getenv("CLOUDFLARED_PATH") or shutil.which("cloudflared") or ""
NGROK = os.getenv("NGROK_PATH") or shutil.which("ngrok") or ""
NGROK_AUTHTOKEN = os.getenv("NGROK_AUTHTOKEN", "").strip()

user_sessions = {}
known_users = set()
whitelist_users = set()
user_stats_cache = {}
user_stats_cache_mtime = {}
artist_avatar_cache = {}
audio_duration_cache = {}

os.makedirs(TMP_DIR, exist_ok=True)
os.makedirs(WEBAPP_MEDIA_DIR, exist_ok=True)
os.makedirs(os.path.join(WEBAPP_MEDIA_DIR, "covers"), exist_ok=True)
os.makedirs(os.path.join(WEBAPP_MEDIA_DIR, "avatars"), exist_ok=True)
USER_STATS_FILENAME = ".bot_stats.json"
ACTIVITY_SESSION_GAP_SECONDS = 15 * 60

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

shazam = Shazam()

def is_authenticated(uid: int) -> bool:
    """Check if user has authenticated session."""
    return user_sessions.get(uid, False)

def is_admin(uid: int) -> bool:
    """Check if user is bot admin."""
    return bool(ADMIN_ID and uid == ADMIN_ID)

def is_whitelisted(uid: int) -> bool:
    """Check if user can use the bot without password."""
    return uid in whitelist_users or is_admin(uid)

def has_access(uid: int) -> bool:
    """Check if user can use the bot right now."""
    return not is_password_set() or is_authenticated(uid) or is_whitelisted(uid)

def authenticate_user(uid: int) -> None:
    """Mark user as authenticated."""
    user_sessions[uid] = True
    known_users.add(uid)
    ensure_default_playlists(uid)
    save_known_users()

def add_user_to_whitelist(uid: int) -> None:
    """Grant permanent password-free access to user."""
    whitelist_users.add(uid)
    save_whitelist()

def is_password_set() -> bool:
    """Check if password is configured."""
    return bool(BOT_PASSWORD and BOT_PASSWORD.strip())

accounts_data = {"accounts": {}, "telegram_map": {}}

def load_accounts():
    global accounts_data
    try:
        if os.path.exists(ACCOUNTS_FILE):
            with open(ACCOUNTS_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict) and "accounts" in data:
                accounts_data = data
                accounts_data.setdefault("telegram_map", {})
                accounts_data.setdefault("accounts", {})
            else:
                accounts_data = {"accounts": {}, "telegram_map": {}}
        else:
            accounts_data = {"accounts": {}, "telegram_map": {}}
        logging.info(f"Accounts loaded: {len(accounts_data.get('accounts', {}))}")
    except Exception as e:
        logging.error(f"Load accounts error: {e}")
        accounts_data = {"accounts": {}, "telegram_map": {}}

def save_accounts():
    try:
        with open(ACCOUNTS_FILE, "w", encoding="utf-8") as f:
            json.dump(accounts_data, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logging.error(f"Save accounts error: {e}")

def normalize_username(name: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_]", "", (name or "").strip().lower())[:32]

def hash_password(password: str, salt: str = None) -> tuple[str, str]:
    if not salt:
        salt = secrets.token_hex(16)
    h = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), (salt + TOKEN).encode("utf-8"), 100000)
    return h.hex(), salt

def verify_password(password: str, stored_hash: str, salt: str) -> bool:
    h, _ = hash_password(password, salt)
    return hmac.compare_digest(h, stored_hash)

def get_account_by_username(username: str):
    key = normalize_username(username)
    return accounts_data.get("accounts", {}).get(key)

def get_account_by_uid(uid):
    if isinstance(uid, str) and uid.startswith("acc_"):
        key = uid[4:]
        return accounts_data.get("accounts", {}).get(key)
    return None

def get_account_by_telegram_id(tid: int):
    key = str(tid)
    uname = accounts_data.get("telegram_map", {}).get(key)
    if uname:
        return accounts_data.get("accounts", {}).get(uname)
    return None

def is_account_uid(uid) -> bool:
    if isinstance(uid, str) and uid.startswith("acc_"):
        return uid[4:] in accounts_data.get("accounts", {})
    return False

def create_account(username: str, password: str, display_name: str = None):
    key = normalize_username(username)
    if not key or len(key) < 3:
        raise ValueError("Ник должен быть от 3 символов (a-z,0-9,_)")
    if key in accounts_data.get("accounts", {}):
        raise ValueError("Ник уже занят")
    if len(password) < 4:
        raise ValueError("Пароль слишком короткий (мин 4)")
    pwd_hash, salt = hash_password(password)
    uid = f"acc_{key}"
    acc = {
        "username": username.strip(),
        "username_key": key,
        "password_hash": pwd_hash,
        "salt": salt,
        "uid": uid,
        "display_name": (display_name or username).strip() or username.strip(),
        "avatar_url": "",
        "telegram_id": None,
        "created_at": datetime.utcnow().isoformat() + "Z",
        "profile_featured": []
    }
    accounts_data["accounts"][key] = acc
    save_accounts()
    ensure_default_playlists(uid)
    logging.info(f"ACCOUNT CREATED | {username} | {uid}")
    return acc

def update_account_profile(username_key: str, display_name: str = None, avatar_url: str = None):
    acc = accounts_data.get("accounts", {}).get(username_key)
    if not acc:
        raise ValueError("Аккаунт не найден")
    if display_name is not None:
        dn = display_name.strip()[:64]
        if dn:
            acc["display_name"] = dn
    if avatar_url is not None:
        acc["avatar_url"] = avatar_url.strip()[:512]
    save_accounts()
    return acc

def link_telegram_to_account(username_key: str, telegram_id: int, telegram_user: dict = None):
    acc = accounts_data.get("accounts", {}).get(username_key)
    if not acc:
        raise ValueError("Аккаунт не найден")
    tid_str = str(telegram_id)
    existing = accounts_data.get("telegram_map", {}).get(tid_str)
    if existing and existing != username_key:
        raise ValueError("Telegram уже привязан к другому аккаунту")
    old_tid = acc.get("telegram_id")
    if old_tid and str(old_tid) != tid_str:
        accounts_data["telegram_map"].pop(str(old_tid), None)
    acc["telegram_id"] = telegram_id
    if telegram_user:
        if not acc.get("avatar_url") and telegram_user.get("photo_url"):
            acc["avatar_url"] = telegram_user.get("photo_url")
    accounts_data["telegram_map"][tid_str] = username_key
    save_accounts()
    return acc

def unlink_telegram_from_account(username_key: str):
    acc = accounts_data.get("accounts", {}).get(username_key)
    if not acc:
        raise ValueError("Аккаунт не найден")
    tid = acc.get("telegram_id")
    if tid:
        accounts_data["telegram_map"].pop(str(tid), None)
    acc["telegram_id"] = None
    save_accounts()
    return acc

def add_featured_to_profile(uid, track: dict):
    acc = None
    if is_account_uid(uid):
        acc = get_account_by_uid(uid)
    else:
        acc = get_account_by_telegram_id(int(uid)) if str(uid).isdigit() else None
    if not acc:
        raise ValueError("Аккаунт не найден для профиля")
    featured = acc.setdefault("profile_featured", [])
    tkey = trackKey(track)
    for existing in featured:
        if trackKey(existing) == tkey:
            return acc
    if len(featured) >= 10:
        featured.pop(0)
    featured.append({
        "title": track.get("title") or "",
        "artist": track.get("artist") or "",
        "url": track.get("url") or "",
        "src": track.get("src") or "",
        "cover": track.get("cover") or track.get("cover_url") or "",
    })
    save_accounts()
    return acc

def remove_featured_from_profile(uid, track: dict):
    acc = None
    if is_account_uid(uid):
        acc = get_account_by_uid(uid)
    else:
        acc = get_account_by_telegram_id(int(uid)) if str(uid).isdigit() else None
    if not acc:
        raise ValueError("Аккаунт не найден")
    featured = acc.get("profile_featured", [])
    tkey = trackKey(track)
    acc["profile_featured"] = [t for t in featured if trackKey(t) != tkey]
    save_accounts()
    return acc

telegram_profiles = {}

def load_telegram_profiles():
    global telegram_profiles
    try:
        if os.path.exists(TELEGRAM_PROFILES_FILE):
            with open(TELEGRAM_PROFILES_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            telegram_profiles = data if isinstance(data, dict) else {}
        else:
            telegram_profiles = {}
        logging.info(f"Telegram profiles loaded: {len(telegram_profiles)}")
    except Exception as e:
        logging.error(f"Load telegram profiles error: {e}")
        telegram_profiles = {}

def save_telegram_profiles():
    try:
        with open(TELEGRAM_PROFILES_FILE, "w", encoding="utf-8") as f:
            json.dump(telegram_profiles, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logging.error(f"Save telegram profiles error: {e}")

def get_telegram_profile(tid: int):
    return telegram_profiles.get(str(tid))

def update_telegram_profile(tid: int, display_name=None, avatar_url=None):
    key = str(tid)
    prof = telegram_profiles.get(key, {})
    if display_name is not None:
        dn = display_name.strip()[:64]
        if dn:
            prof["display_name"] = dn
    if avatar_url is not None:
        prof["avatar_url"] = avatar_url.strip()[:512]
    prof["telegram_id"] = tid
    prof["updated_at"] = datetime.utcnow().isoformat() + "Z"
    telegram_profiles[key] = prof
    save_telegram_profiles()
    return prof

def get_user_stats_path(uid: int) -> str:
    return os.path.join(PLAYLIST_DIR, str(uid), USER_STATS_FILENAME)

def default_user_stats() -> dict:
    return {
        "total_active_seconds": 0,
        "last_activity_ts": 0.0,
        "artist_seconds": {},
        "artist_labels": {},
    }

def load_user_stats(uid: int, force_reload: bool = False) -> dict:
    uid_key = str(uid)
    path = get_user_stats_path(uid)
    try:
        current_mtime = os.path.getmtime(path) if os.path.exists(path) else 0.0
    except OSError:
        current_mtime = 0.0

    if not force_reload and uid_key in user_stats_cache:
        cached_mtime = float(user_stats_cache_mtime.get(uid_key) or 0.0)
        if cached_mtime == current_mtime:
            return user_stats_cache[uid_key]

    stats = default_user_stats()
    try:
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict):
                stats["total_active_seconds"] = int(data.get("total_active_seconds") or 0)
                stats["last_activity_ts"] = float(data.get("last_activity_ts") or 0.0)
                artist_seconds = data.get("artist_seconds") or {}
                if isinstance(artist_seconds, dict):
                    stats["artist_seconds"] = {
                        str(k): max(0, int(v or 0))
                        for k, v in artist_seconds.items()
                        if str(k).strip()
                    }
                artist_labels = data.get("artist_labels") or {}
                if isinstance(artist_labels, dict):
                    stats["artist_labels"] = {
                        str(k): str(v or "").strip()
                        for k, v in artist_labels.items()
                        if str(k).strip()
                    }
    except Exception as e:
        logging.debug(f"Load user stats error | uid={uid} | {e}")

    user_stats_cache[uid_key] = stats
    user_stats_cache_mtime[uid_key] = current_mtime
    return stats

def save_user_stats(uid: int) -> None:
    uid_key = str(uid)
    stats = user_stats_cache.get(uid_key)
    if stats is None:
        return
    path = get_user_stats_path(uid)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    try:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(stats, f, ensure_ascii=False, indent=2)
        try:
            user_stats_cache_mtime[uid_key] = os.path.getmtime(path)
        except OSError:
            user_stats_cache_mtime[uid_key] = 0.0
    except Exception as e:
        logging.debug(f"Save user stats error | uid={uid} | {e}")

def touch_user_activity(uid: int) -> None:
    now_ts = datetime.utcnow().timestamp()
    stats = load_user_stats(uid)
    last_ts = float(stats.get("last_activity_ts") or 0.0)
    if last_ts > 0:
        delta = max(0.0, now_ts - last_ts)
        if delta > 0:
            stats["total_active_seconds"] = int(stats.get("total_active_seconds") or 0) + int(min(delta, ACTIVITY_SESSION_GAP_SECONDS))
    stats["last_activity_ts"] = now_ts
    user_stats_cache[str(uid)] = stats
    save_user_stats(uid)

def record_artist_listen(uid: int, artist: str, seconds: int | float | None) -> None:
    if not artist:
        return
    try:
        duration = int(round(float(seconds or 0)))
    except (TypeError, ValueError):
        duration = 0
    if duration <= 0:
        return

    stats = load_user_stats(uid)
    artist_key = normalize_track_text(artist) or artist.strip().lower()
    if not artist_key:
        return
    labels = stats.setdefault("artist_labels", {})
    seconds_map = stats.setdefault("artist_seconds", {})
    labels[artist_key] = artist.strip() or labels.get(artist_key, artist_key)
    seconds_map[artist_key] = int(seconds_map.get(artist_key) or 0) + duration
    user_stats_cache[str(uid)] = stats
    save_user_stats(uid)

def get_audio_duration_seconds(path: str) -> int | None:
    if not FFPROBE or not path or not os.path.exists(path):
        return None
    try:
        cache_key = f"{path}:{os.path.getmtime(path)}"
    except OSError:
        return None
    if cache_key in audio_duration_cache:
        return audio_duration_cache[cache_key]
    try:
        out = subprocess.check_output(
            [
                FFPROBE,
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                path,
            ],
            text=True,
            errors="ignore",
        ).strip()
        duration = int(round(float(out))) if out else None
    except Exception:
        duration = None
    audio_duration_cache[cache_key] = duration
    return duration

async def fetch_artist_avatar_url(artist: str) -> str:
    artist = (artist or "").strip()
    if not artist:
        return ""
    cache_key = normalize_track_text(artist) or artist.lower()
    if cache_key in artist_avatar_cache:
        return artist_avatar_cache[cache_key]

    avatar = ""
    url = f"https://api.deezer.com/search/artist?q={quote_plus(artist)}&limit=1"
    try:
        timeout = ClientTimeout(total=6)
        async with ClientSession(timeout=timeout) as session:
            async with session.get(url) as resp:
                if resp.status == 200:
                    payload = await resp.json(content_type=None)
                    data = payload.get("data") or []
                    if data:
                        avatar = (data[0].get("picture_medium") or data[0].get("picture_big") or data[0].get("picture") or "").strip()
    except Exception as e:
        logging.debug(f"Artist avatar lookup error | {artist} | {e}")

    if not avatar:
        avatar = f"https://ui-avatars.com/api/?name={quote_plus(artist[:2] or 'A')}&background=0f172a&color=ffffff&size=256"

    artist_avatar_cache[cache_key] = avatar
    return avatar

async def access_filter(message: types.Message) -> bool:
    """Filter to check if user is authenticated."""
    uid = message.from_user.id
    if not has_access(uid):
        logging.warning(f"BLOCKED | {get_user_info(message.from_user)} | Not authenticated")
        await message.answer(
            "🔐 *Доступ ограничен паролем*\n\n"
            "Введите пароль для доступа к боту:",
            parse_mode="Markdown"
        )
        return False
    touch_user_activity(uid)
    return True

async def callback_access_filter(callback: types.CallbackQuery) -> bool:
    """Filter to check if user is authenticated for callback queries."""
    uid = callback.from_user.id
    if not has_access(uid):
        logging.warning(f"BLOCKED | {get_user_info(callback.from_user)} | Callback access denied")
        await callback.answer("🔐 Доступ ограничен. Введите /start", show_alert=True)
        return False
    touch_user_activity(uid)
    return True

async def set_bot_commands():
    commands = [
        BotCommand(command="start", description="🚀 Запуск бота"),
        BotCommand(command="help", description="📖 Список команд"),
        BotCommand(command="playlists", description="🎵 Мои плейлисты"),
        BotCommand(command="add", description="➕ Добавить в плейлист (ответом)"),
        BotCommand(command="recommend", description="🎵 Получить рекомендации"),
    ]
    await bot.set_my_commands(commands)

def get_webapp_url() -> str:
    """Return the active public URL for Telegram entry points."""
    return WEBAPP_EFFECTIVE_URL or WEBAPP_URL or WEBAPP_FALLBACK_URL

async def is_url_reachable(url: str) -> bool:
    """Best-effort server-side check for a public HTTPS URL."""
    if not url:
        return False

    timeout = ClientTimeout(total=7)
    try:
        async with ClientSession(timeout=timeout) as session:
            for method in ("HEAD", "GET"):
                try:
                    async with session.request(method, url, allow_redirects=True) as resp:
                        return 200 <= resp.status < 400
                except Exception:
                    continue
    except Exception:
        return False
    return False

async def resolve_webapp_entry_url() -> str:
    """Pick the first reachable URL for the Telegram entry button."""
    candidates = [WEBAPP_URL, WEBAPP_FALLBACK_URL]
    for url in candidates:
        if await is_url_reachable(url):
            return url
    for url in candidates:
        if url:
            return url
    return ""

async def set_webapp_menu_button():
    """Configure the persistent blue Telegram menu button for the mini app."""
    url = get_webapp_url()
    if not url:
        logging.info("Mini app menu button skipped: no public web app URL is set")
        return

    await bot.set_chat_menu_button(
        menu_button=MenuButtonWebApp(
            text="Насладись музыкой!",
            web_app=WebAppInfo(url=url),
        )
    )
    logging.info(f"Mini app menu button configured: {url}")

def get_webapp_inline_markup() -> InlineKeyboardMarkup | None:
    """Return a reliable inline entry point for the Telegram mini app."""
    url = get_webapp_url()
    if not url:
        return None
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [InlineKeyboardButton(text="Открыть приложение", web_app=WebAppInfo(url=url))]
        ]
    )

async def send_webapp_entrypoint(msg: types.Message) -> None:
    """Send a dedicated mini app button so it is visible in clients that hide menu/reply buttons."""
    markup = get_webapp_inline_markup()
    if not markup:
        return
    await msg.answer(
        "📱 Мини-приложение доступно по кнопке ниже.",
        reply_markup=markup,
    )

def should_start_cloudflare_tunnel() -> bool:
    """Allow the bot to obtain a fresh trycloudflare URL on startup."""
    return WEBAPP_TUNNEL_PROVIDER == "cloudflare" or CLOUDFLARE_TUNNEL_AUTO

def should_start_ngrok_tunnel() -> bool:
    """Allow the bot to obtain a fresh ngrok URL on startup."""
    return WEBAPP_TUNNEL_PROVIDER == "ngrok"

async def start_cloudflare_tunnel(target_url: str) -> str:
    """Start cloudflared and return the public HTTPS URL."""
    global cloudflared_process
    if not CLOUDFLARED:
        raise RuntimeError("cloudflared is not installed, but cloudflare tunnel mode is enabled.")

    log_path = os.path.join(BASE_DIR, "cloudflared.log")
    log_handle = open(log_path, "a", encoding="utf-8")
    cmd = [CLOUDFLARED, "tunnel", "--no-autoupdate", "--url", target_url]
    cloudflared_process = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    logging.info(f"Starting cloudflared tunnel for {target_url}")

    try:
        while True:
            line = await asyncio.wait_for(cloudflared_process.stdout.readline(), timeout=30)
            if not line:
                break
            text = line.decode("utf-8", errors="ignore").strip()
            if text:
                log_handle.write(text + "\n")
                log_handle.flush()
                logging.info(f"CLOUDFLARED | {text}")
            marker = "https://"
            if marker in text and ".trycloudflare.com" in text:
                start = text.index(marker)
                end = text.find(".trycloudflare.com", start)
                if end != -1:
                    return text[start:end + len(".trycloudflare.com")].rstrip("/")
        raise RuntimeError("cloudflared started but did not publish a trycloudflare URL.")
    except asyncio.TimeoutError as e:
        raise RuntimeError("Timed out while waiting for cloudflared to publish a URL.") from e
    finally:
        log_handle.close()

async def start_ngrok_tunnel(target_url: str) -> str:
    """Start ngrok and return the public HTTPS URL."""
    global ngrok_process
    if not NGROK:
        raise RuntimeError("ngrok is not installed, but ngrok tunnel mode is enabled.")
    if not NGROK_AUTHTOKEN:
        raise RuntimeError("NGROK_AUTHTOKEN is not set.")

    config_dir = os.path.join(BASE_DIR, ".ngrok")
    os.makedirs(config_dir, exist_ok=True)
    config_path = os.path.join(config_dir, "ngrok.yml")
    with open(config_path, "w", encoding="utf-8") as f:
        f.write(f'version: "2"\nauthtoken: {NGROK_AUTHTOKEN}\n')

    log_path = os.path.join(BASE_DIR, "ngrok.log")
    log_handle = open(log_path, "a", encoding="utf-8")
    cmd = [NGROK, "http", target_url, "--log", "stdout", "--config", config_path]
    ngrok_process = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    logging.info(f"Starting ngrok tunnel for {target_url}")

    try:
        while True:
            line = await asyncio.wait_for(ngrok_process.stdout.readline(), timeout=30)
            if not line:
                break
            text = line.decode("utf-8", errors="ignore").strip()
            if text:
                log_handle.write(text + "\n")
                log_handle.flush()
                logging.info(f"NGROK | {text}")
            marker = "url=https://"
            if marker in text and ".ngrok-" in text:
                start = text.index(marker) + len("url=")
                url = text[start:].split()[0].rstrip("/")
                return url
        raise RuntimeError("ngrok started but did not publish an HTTPS URL.")
    except asyncio.TimeoutError as e:
        raise RuntimeError("Timed out while waiting for ngrok to publish a URL.") from e
    finally:
        log_handle.close()

async def ensure_webapp_url():
    """Resolve the public mini app URL, starting the configured tunnel when needed."""
    global WEBAPP_URL
    if WEBAPP_URL and WEBAPP_URL.lower() != "auto" and not should_start_cloudflare_tunnel() and not should_start_ngrok_tunnel():
        return

    if not should_start_cloudflare_tunnel() and not should_start_ngrok_tunnel():
        WEBAPP_URL = ""
        logging.info("Mini app public URL is not configured; web app stays local only")
        return

    target_url = f"http://{WEBAPP_BIND}:{WEBAPP_PORT}"
    if should_start_ngrok_tunnel():
        WEBAPP_URL = await start_ngrok_tunnel(target_url)
    else:
        WEBAPP_URL = await start_cloudflare_tunnel(target_url)
    logging.info(f"Mini app public URL resolved: {WEBAPP_URL}")

def get_main_menu(uid: int):
    kb = [
        [KeyboardButton(text="🎧 Мои плейлисты"), KeyboardButton(text="📁 Создать плейлист")],
        [KeyboardButton(text="🎵 Рекомендации"), KeyboardButton(text="❓ Помощь")]
    ]
    if WEBAPP_URL:
        kb.append([KeyboardButton(text="📱 Открыть приложение", web_app=WebAppInfo(url=WEBAPP_URL))])
    if is_admin(uid):
        kb.append([KeyboardButton(text="👤 Добавить пользователя")])
    return ReplyKeyboardMarkup(keyboard=kb, resize_keyboard=True)

cache = {}
download_queue = asyncio.Queue()
user_last_track = {}
playlist_tracks_cache = {}
pl_callback_map = {}
playlist_callback_map = {}
add_to_playlist_map = {}
add_track_state = {}
recommendations_cache = {}
last_recommendation_update = {}
artist_genre_cache = {}
remote_bitrate_cache = {}
local_bitrate_cache = {}
now_playing_state = {}
process_lock_handle = None
webapp_media_cache = {}
webapp_media_recognition_jobs = {}
webapp_download_jobs = {}
cloudflared_process = None
ngrok_process = None

def get_user_info(user) -> str:
    """Get user info string for logging: id and username/name"""
    uid = user.id
    username = user.username
    if username:
        return f"@{username}({uid})"
    name = getattr(user, 'first_name', '') + ' ' + getattr(user, 'last_name', '')
    name = name.strip() if name else 'Unknown'
    return f"{name}({uid})"

def sanitize(text: str) -> str:
    return "".join(c for c in text if c not in "/\\?%*:|\"<>")

ARTIST_SUFFIX_RE = re.compile(
    r"\s*[-–—]\s*(?:topic|вево|vevo|official(?:\s+(?:audio|video|music\s*video|artist(?:\s*channel)?))?|records?|music|lyrics?)\s*$",
    re.IGNORECASE,
)
ARTIST_TRAILING_RE = re.compile(
    r"\s+(?:vevo|official|topic|records?|music)\s*$",
    re.IGNORECASE,
)
ARTIST_BRACKET_RE = re.compile(
    r"\s*[\(\[](?:official\s*(?:audio|video|music\s*video|artist\s*channel)?|topic|vevo|lyrics?|audio|video|hd|hq|4k)[\)\]]\s*",
    re.IGNORECASE,
)
ARTIST_SPLIT_RE = re.compile(
    r"\s*(?:,|;|&|×|\+|\s[xх]\s|\b(?:featuring|feat|ft|vs)\.?|\bпри\s*уч\.?)\s*",
    re.IGNORECASE,
)


def clean_artist(name: str) -> str:
    """Strip channel junk like 'Artist - Topic' / 'Artist VEVO' / 'Artist (Official Video)'."""
    value = re.sub(r"\s+", " ", (name or "").strip())
    if not value:
        return ""
    prev = None
    while prev != value:
        prev = value
        value = ARTIST_SUFFIX_RE.sub("", value)
        value = ARTIST_TRAILING_RE.sub("", value)
        value = ARTIST_BRACKET_RE.sub(" ", value)
        value = re.sub(r"\s+", " ", value).strip()
    return value.strip(" -–—,;|&")


def split_artists(name: str) -> list[str]:
    """Clean a raw artist string and split collaborators into separate names."""
    value = clean_artist(name)
    if not value:
        return []
    result = []
    seen = set()
    for part in ARTIST_SPLIT_RE.split(value):
        cleaned = clean_artist(part)
        if not cleaned:
            continue
        key = cleaned.lower()
        if key in seen:
            continue
        seen.add(key)
        result.append(cleaned)
    return result


def parse_artist(title, uploader):
    if " - " in title:
        a, t = title.split(" - ", 1)
        return clean_artist(a.strip()) or a.strip(), t.strip()
    return clean_artist(uploader or "Unknown") or (uploader or "Unknown"), title

def normalize_track_text(value: str) -> str:
    return "".join(ch.lower() for ch in (value or "") if ch.isalnum())

def trackKey(track: dict) -> str:
    try:
        return f"{(track.get('artist') or '').strip().lower()}::{(track.get('title') or '').strip().lower()}"
    except Exception:
        return "::"

SEARCH_SUFFIX_RE = re.compile(
    r"\s*[\(\[](?:lyrics?|lyric|official\s*(?:video|audio|music\s*video)?|audio|video|mv|live|acoustic|remix|remaster(?:ed)?|deluxe|explicit|clean|edited|hq|hd|4k|8k)[\)\]]*\s*$",
    re.IGNORECASE,
)

def clean_search_query(value: str) -> str:
    """Remove common suffixes that hurt music search matching."""
    query = re.sub(r"\s+", " ", (value or "").strip())
    if not query:
        return ""

    cleaned = query
    while True:
        next_cleaned = SEARCH_SUFFIX_RE.sub("", cleaned).strip()
        if next_cleaned == cleaned:
            break
        cleaned = next_cleaned
    return cleaned

def search_query_variants(query: str) -> list[str]:
    """Return raw and cleaned variants of a search query, preserving order."""
    raw = re.sub(r"\s+", " ", (query or "").strip())
    cleaned = clean_search_query(raw)
    variants = []
    seen = set()
    for item in (raw, cleaned):
        if item and item.lower() not in seen:
            seen.add(item.lower())
            variants.append(item)
    return variants

def lrclib_search_specs(artist: str, title: str) -> list[dict[str, str]]:
    """Return LRCLIB search parameter combinations in priority order."""
    artist = clean_search_query(artist)
    title_raw = re.sub(r"\s+", " ", (title or "").strip())
    title_clean = clean_search_query(title_raw) or title_raw

    specs: list[dict[str, str]] = []
    seen: set[tuple[tuple[str, str], ...]] = set()

    def add(**params: str) -> None:
        normalized = tuple(sorted((k, re.sub(r"\s+", " ", (v or "").strip())) for k, v in params.items() if (v or "").strip()))
        if not normalized or normalized in seen:
            return
        seen.add(normalized)
        specs.append({k: v for k, v in normalized})

    if artist:
        add(track_name=title_raw, artist_name=artist)
        if title_clean != title_raw:
            add(track_name=title_clean, artist_name=artist)
    add(track_name=title_raw)
    if title_clean != title_raw:
        add(track_name=title_clean)
    if artist:
        add(q=f"{artist} {title_raw}".strip())
        if title_clean != title_raw:
            add(q=f"{artist} {title_clean}".strip())
    add(q=title_raw)
    if title_clean != title_raw:
        add(q=title_clean)
    return specs

def split_artist_tokens(value: str) -> list[str]:
    raw = (value or "").strip()
    if not raw:
        return []
    parts = re.split(r"\s*(?:,|&|feat\.?|ft\.?|x|and|/)\s*", raw, flags=re.IGNORECASE)
    tokens = []
    for part in parts:
        normalized = normalize_track_text(part)
        if normalized:
            tokens.append(normalized)
    return tokens

def artist_matches_target(result_artist: str, target_artist: str) -> bool:
    target = normalize_track_text(target_artist)
    if not target:
        return False
    result_tokens = split_artist_tokens(result_artist)
    if target in result_tokens:
        return True
    result_compact = normalize_track_text(result_artist)
    return bool(result_compact and (target in result_compact or result_compact in target))

def looks_like_compilation(title: str, artist: str) -> bool:
    haystack = f"{title} {artist}".lower()
    bad_markers = [
        "best songs", "greatest hits", "top hits", "top songs", "playlist",
        "mix", "megamix", "non stop", "full album", "jukebox", "chart",
        "radio", "summer vibes", "relaxing", "compilation", "collection"
    ]
    return any(marker in haystack for marker in bad_markers)

GENRE_ALIASES = {
    "pop": "pop",
    "поп": "pop",
    "hip hop": "hip-hop-rap",
    "hip-hop": "hip-hop-rap",
    "hip hop rap": "hip-hop-rap",
    "hip-hop/rap": "hip-hop-rap",
    "hiphop": "hip-hop-rap",
    "rap": "hip-hop-rap",
    "рэп": "hip-hop-rap",
    "хип хоп": "hip-hop-rap",
    "dance": "dance",
    "танцевальная": "dance",
    "electronic": "electronic",
    "electronica": "electronic",
    "edm": "electronic",
    "электроника": "electronic",
    "электронная": "electronic",
    "ambient": "electronic",
    "ambience": "electronic",
    "амбиент": "electronic",
    "drill": "hip-hop-rap",
    "phonk": "electronic",
    "лоуфай": "electronic",
    "lofi": "electronic",
    "r&b": "randb-soul",
    "rnb": "randb-soul",
    "r b": "randb-soul",
    "r b soul": "randb-soul",
    "r&b/soul": "randb-soul",
    "soul": "randb-soul",
    "соул": "randb-soul",
    "alternative": "alternative",
    "альтернатива": "alternative",
    "rock": "rock",
    "рок": "rock",
    "latin": "latin",
    "film tv stage": "film-tv-and-stage",
    "film/tv/stage": "film-tv-and-stage",
    "country": "country",
    "afrobeats": "afrobeats",
    "afro beats": "afrobeats",
    "worldwide": "worldwide",
    "world": "worldwide",
    "reggae": "reggae-dancehall",
    "dancehall": "reggae-dancehall",
    "reggae dancehall": "reggae-dancehall",
    "reggae/dancehall": "reggae-dancehall",
    "house": "house",
    "хаус": "house",
    "k-pop": "k-pop",
    "k pop": "k-pop",
    "kpop": "k-pop",
    "french pop": "french-pop",
    "french-pop": "french-pop",
    "singer songwriter": "singer-songwriter",
    "singer-songwriter": "singer-songwriter",
    "regional mexicano": "regional-mexicano",
    "regional-mexicano": "regional-mexicano",
}

GENRE_NAME_BLACKLIST = {
    "мне нравится", "liked", "favorites", "favorite", "любимое",
    "песни", "трек", "треки", "music", "songs", "playlist", "playlists",
    "мотивейшн", "мем", "мемы", "мемасики"
}

GENRE_KEYWORD_HINTS = {
    "ambient": "electronic",
    "ambience": "electronic",
    "амбиент": "electronic",
    "electronic": "electronic",
    "электрон": "electronic",
    "house": "house",
    "хаус": "house",
    "phonk": "electronic",
    "фонк": "electronic",
    "drill": "hip-hop-rap",
    "дрилл": "hip-hop-rap",
    "rap": "hip-hop-rap",
    "рэп": "hip-hop-rap",
    "hip hop": "hip-hop-rap",
    "хип хоп": "hip-hop-rap",
    "rock": "rock",
    "рок": "rock",
    "pop": "pop",
    "поп": "pop",
    "lofi": "electronic",
    "лоуфай": "electronic",
}

def normalize_genre_name(value: str) -> str:
    return re.sub(r"[^a-z0-9а-яё]+", " ", (value or "").strip().lower()).strip()

def infer_genre_from_text(value: str) -> str | None:
    normalized = normalize_genre_name(value)
    if not normalized or normalized in GENRE_NAME_BLACKLIST:
        return None
    direct = GENRE_ALIASES.get(normalized)
    if direct:
        return direct
    for keyword, slug in GENRE_KEYWORD_HINTS.items():
        if keyword in normalized:
            return slug
    return None

def resolve_genre_slug(genre: str) -> str | None:
    normalized = normalize_genre_name(genre)
    if not normalized:
        return None
    if normalized in GENRE_ALIASES:
        return GENRE_ALIASES[normalized]
    compact = normalized.replace(" ", "-")
    valid_slugs = {
        "pop", "hip-hop-rap", "dance", "electronic", "randb-soul", "alternative",
        "rock", "latin", "film-tv-and-stage", "country", "afrobeats", "worldwide",
        "reggae-dancehall", "house", "k-pop", "french-pop", "singer-songwriter",
        "regional-mexicano"
    }
    return compact if compact in valid_slugs else None

def genre_query_variants(genre: str) -> list[str]:
    base = (genre or "").strip()
    if not base:
        return []
    normalized = normalize_genre_name(base)
    extra_terms = {
        "hip hop rap": ["hip hop", "rap"],
        "r b soul": ["r&b", "soul"],
        "k pop": ["k-pop", "kpop"],
        "reggae dancehall": ["reggae", "dancehall"],
        "regional mexicano": ["regional mexicano", "mexican regional"],
        "film tv stage": ["soundtrack", "musical soundtrack"],
    }.get(normalized, [])
    queries = [
        f"{base} music",
        f"{base} tracks",
        f"{base} songs",
    ]
    for term in extra_terms:
        queries.extend([
            f"{term} music",
            f"{term} tracks",
        ])
    seen = set()
    unique_queries = []
    for query in queries:
        key = query.lower()
        if key in seen:
            continue
        seen.add(key)
        unique_queries.append(query)
    return unique_queries

def genre_search_terms(genre: str) -> list[str]:
    slug = resolve_genre_slug(genre)
    slug_to_queries = {
        "electronic": ["electronic", "ambient", "downtempo", "instrumental electronic"],
        "hip-hop-rap": ["hip hop", "rap", "drill"],
        "rock": ["rock", "alternative rock", "indie rock"],
        "house": ["house", "deep house", "electronic house"],
        "randb-soul": ["r&b", "soul", "neo soul"],
        "pop": ["pop", "indie pop", "art pop"],
        "alternative": ["alternative", "indie", "post punk"],
    }
    if slug in slug_to_queries:
        return slug_to_queries[slug]
    base = (genre or "").strip()
    return [base] if base else []

def genre_result_matches(genre: str, track: dict) -> bool:
    normalized_genre = normalize_genre_name(genre)
    haystack = normalize_genre_name(" ".join([
        track.get("artist", ""),
        track.get("title", ""),
        track.get("full_title", ""),
    ]))
    for term in genre_search_terms(genre):
        normalized_term = normalize_genre_name(term)
        if normalized_term and normalized_term in haystack:
            return True
    if normalized_genre and normalized_genre in haystack:
        return True
    return False

def sc_search(q: str):
    logging.info(f"SEARCH | {q}")
    results = []
    seen = set()
    for query in search_query_variants(q):
        sources = {
            "SC": {"query": f"scsearch10:{query}", "args": []},
            "YM": {"query": f"ytsearch10:{query}", "args": ["--extractor-args", "youtube:search_type=music_songs"]},
        }
        for tag, config in sources.items():
            cmd = [YTDLP, config["query"], "--flat-playlist", "--print",
                   "%(title)s|%(uploader)s|%(webpage_url)s|%(thumbnails.-1.url|)s|%(duration)s", "--quiet"] + config["args"]
            try:
                out = subprocess.check_output(cmd, text=True, errors="ignore").splitlines()
            except Exception as e:
                logging.error(f"SEARCH ERROR {tag}: {e}")
                continue
            for l in out:
                parts = l.split("|", 4)
                if len(parts) < 3:
                    continue
                title = parts[0]
                uploader = parts[1]
                url = parts[2]
                thumbnail = (parts[3] if len(parts) > 3 else "").strip()
                duration_raw = (parts[4] if len(parts) > 4 else "").strip()
                try:
                    duration = int(float(duration_raw)) if duration_raw and duration_raw != "NA" else None
                except Exception:
                    duration = None
                artist, real_title = parse_artist(title, uploader)
                cover = thumbnail if thumbnail and thumbnail != "NA" else ""
                dedupe_key = url or f"{tag}:{artist}:{real_title}:{title}"
                if dedupe_key in seen:
                    continue
                seen.add(dedupe_key)
                results.append({"title": real_title, "artist": artist, "full_title": title, "url": url, "src": tag, "cover": cover, "duration": duration})
    results.sort(key=lambda x: 0 if x["src"] == "SC" else 1)
    return results

def sc_search_soundcloud(q: str) -> list[dict]:
    """SoundCloud search only (yt-dlp). Used by download fallbacks."""
    results = []
    seen = set()
    for query in search_query_variants(q):
        try:
            out = subprocess.check_output(
                [YTDLP, f"scsearch10:{query}", "--flat-playlist", "--print",
                 "%(title)s|%(uploader)s|%(webpage_url)s|%(thumbnails.-1.url|)s|%(duration)s", "--quiet"],
                text=True, errors="ignore",
            ).splitlines()
        except Exception as e:
            logging.error(f"SEARCH ERROR SC: {e}")
            out = []
        for l in out:
            parts = l.split("|", 4)
            if len(parts) < 3:
                continue
            title = parts[0]
            uploader = parts[1]
            url = parts[2]
            thumbnail = (parts[3] if len(parts) > 3 else "").strip()
            duration_raw = (parts[4] if len(parts) > 4 else "").strip()
            try:
                duration = int(float(duration_raw)) if duration_raw and duration_raw != "NA" else None
            except Exception:
                duration = None
            artist, real_title = parse_artist(title, uploader)
            cover = thumbnail if thumbnail and thumbnail != "NA" else ""
            dedupe_key = url or f"SC:{artist}:{real_title}:{title}"
            if dedupe_key in seen:
                continue
            seen.add(dedupe_key)
            results.append({"title": real_title, "artist": artist, "full_title": title, "url": url, "src": "SC", "cover": cover, "duration": duration})
    return results

def find_best_search_match(track: dict, results: list[dict]) -> dict | None:
    target_artist = normalize_track_text(track.get("artist", ""))
    target_title = normalize_track_text(track.get("title", ""))
    best_match = None
    best_score = -1

    for result in results:
        score = 0
        result_artist = normalize_track_text(result.get("artist", ""))
        result_title = normalize_track_text(result.get("title", ""))
        if target_artist and target_artist in result_artist:
            score += 3
        if target_title and target_title in result_title:
            score += 4
        if result.get("src") == "SC":
            score += 1
        if score > best_score:
            best_score = score
            best_match = result

    return best_match or (results[0] if results else None)

def build_kb(uid):
    data = cache[uid]
    tracks = data["tracks"]
    page = data["page"]
    builder = InlineKeyboardBuilder()
    start = page * PER_PAGE
    end = start + PER_PAGE
    for i, t in enumerate(tracks[start:end]):
        title = t.get("full_title") or f"{t.get('artist', '')} - {t.get('title', '')}".strip(" -")
        name = title[:57] + "..." if len(title) > 60 else title
        source_label = "SoundCloud" if t.get("src") == "SC" else "YouTube Music" if t.get("src") == "YM" else t.get("src", "Track")
        builder.button(text=f"{source_label} 🎧 {name}", callback_data=f"dl:{start+i}")
    total = (len(tracks)-1)//PER_PAGE +1
    if page>0: builder.button(text="⬅ Назад", callback_data="prev")
    builder.button(text=f"{page+1}/{total}", callback_data="noop")
    if page<total-1: builder.button(text="Вперёд ➡", callback_data="next")
    builder.adjust(1)
    return builder.as_markup()

def build_track_kb(uid, track_idx):
    builder = InlineKeyboardBuilder()
    builder.button(text="➕ Добавить в плейлист", callback_data=f"add_track:{track_idx}")
    builder.adjust(1)
    return builder.as_markup()

def get_user_dir(uid):
    path = os.path.join(PLAYLIST_DIR, str(uid))
    os.makedirs(path, exist_ok=True)
    return path

def get_playlist_dir(uid, playlist_name):
    path = os.path.join(get_user_dir(uid), playlist_name)
    os.makedirs(path, exist_ok=True)
    return path

def get_playlist_dir_if_exists(uid, playlist_name):
    return os.path.join(get_user_dir(uid), playlist_name)

def get_playlist_meta_path(uid, playlist_name):
    return os.path.join(get_playlist_dir(uid, playlist_name), PLAYLIST_META_FILENAME)

def load_playlist_meta(uid, playlist_name):
    path = get_playlist_meta_path(uid, playlist_name)
    if not os.path.exists(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except Exception as e:
        logging.warning(f"Playlist meta load error for {uid}/{playlist_name}: {e}")
        return {}

def save_playlist_meta(uid, playlist_name, meta: dict) -> None:
    path = get_playlist_meta_path(uid, playlist_name)
    try:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logging.warning(f"Playlist meta save error for {uid}/{playlist_name}: {e}")

def ensure_default_playlists(uid: int) -> None:
    get_playlist_dir(uid, LIKED_PLAYLIST_NAME)

def normalize_playlist_name(name: str) -> str:
    cleaned = re.sub(r"\s+", " ", (name or "").strip())
    cleaned = cleaned.strip(".")
    return cleaned[:80]

def playlist_exists(uid: int, playlist_name: str) -> bool:
    if not playlist_name:
        return False
    return os.path.isdir(get_playlist_dir_if_exists(uid, playlist_name))

def create_playlist(uid: int, playlist_name: str) -> tuple[bool, str]:
    name = normalize_playlist_name(playlist_name)
    if not name:
        return False, "Введите название плейлиста."
    if "/" in name or "\\" in name or name in {".", ".."}:
        return False, "Некорректное название плейлиста."
    if playlist_exists(uid, name):
        return False, "Плейлист с таким названием уже существует."
    os.makedirs(get_playlist_dir_if_exists(uid, name), exist_ok=False)
    return True, name

def list_playlists(uid):
    user_path = get_user_dir(uid)
    if not os.path.exists(user_path):
        return []
    playlists = [d for d in os.listdir(user_path) if os.path.isdir(os.path.join(user_path, d))]
    if LIKED_PLAYLIST_NAME in playlists:
        playlists.sort(key=lambda name: (name != LIKED_PLAYLIST_NAME, name.lower()))
    else:
        playlists.sort(key=str.lower)
    return playlists

def list_tracks(uid, playlist_name):
    pl_dir = get_playlist_dir(uid, playlist_name)
    tracks = []
    for f in os.listdir(pl_dir):
        if f == PLAYLIST_META_FILENAME:
            continue
        if os.path.isfile(os.path.join(pl_dir, f)) and f.lower().endswith(".mp3"):
            tracks.append(f)
    return sorted(tracks)

def build_playlist_track_filename(track: dict) -> str:
    artist = sanitize((track.get("artist") or "Unknown artist").strip()) or "Unknown artist"
    title = sanitize((track.get("title") or "Unknown title").strip()) or "Unknown title"
    return f"{artist} - {title}.mp3"

def find_playlist_track_path(uid: int, playlist_name: str, track: dict) -> str | None:
    track_name = build_playlist_track_filename(track)
    candidate = os.path.join(get_playlist_dir(uid, playlist_name), track_name)
    if os.path.exists(candidate):
        return candidate

    target_artist = normalize_track_text(track.get("artist", ""))
    target_title = normalize_track_text(track.get("title", ""))
    for item in list_tracks(uid, playlist_name):
        display_name = strip_ext(item)
        if " - " in display_name:
            artist, title = display_name.split(" - ", 1)
        else:
            artist, title = "Unknown artist", display_name
        if normalize_track_text(artist) == target_artist and normalize_track_text(title) == target_title:
            return os.path.join(get_playlist_dir(uid, playlist_name), item)
    return None

track_duration_cache = {}
artist_avatar_cache = {}
statistics_cache = {}
embedded_cover_cache = {}

def extract_embedded_cover_url(mp3_path: str) -> str:
    """Extract embedded APIC from MP3 and cache as file under WEBAPP_MEDIA_DIR/covers, return /api/media URL or empty."""
    if not mp3_path or not os.path.exists(mp3_path):
        return ""
    try:
        mtime = os.path.getmtime(mp3_path)
    except OSError:
        return ""
    cache_key = f"{mp3_path}:{mtime}"
    if cache_key in embedded_cover_cache:
        cached = embedded_cover_cache[cache_key]
        if cached and os.path.exists(cached.get("path", "")):
            return cached.get("url", "")
    try:
        from mutagen.id3 import ID3, APIC
        from mutagen.mp3 import MP3
        audio = None
        try:
            audio = ID3(mp3_path)
        except Exception:
            try:
                audio = MP3(mp3_path).tags
            except Exception:
                audio = None
        if not audio:
            return ""
        apics = [v for k, v in audio.items() if k.startswith("APIC")]
        if not apics:
            return ""
        apic = apics[0]
        data = getattr(apic, "data", b"")
        mime = getattr(apic, "mime", "image/jpeg")
        if not data:
            return ""
        ext = ".jpg"
        if "png" in mime:
            ext = ".png"
        elif "webp" in mime:
            ext = ".webp"
        covers_dir = os.path.join(WEBAPP_MEDIA_DIR, "covers")
        os.makedirs(covers_dir, exist_ok=True)
        h = hashlib.sha1(f"{mp3_path}:{mtime}".encode()).hexdigest()[:16]
        cover_path = os.path.join(covers_dir, f"{h}{ext}")
        if not os.path.exists(cover_path):
            with open(cover_path, "wb") as f:
                f.write(data)
        url = f"/api/cover/{os.path.basename(cover_path)}"
        embedded_cover_cache[cache_key] = {"path": cover_path, "url": url}
        return url
    except Exception as e:
        logging.debug(f"Extract embedded cover error for {mp3_path}: {e}")
        return ""

async def embed_cover_into_mp3(mp3_path: str, cover_url: str) -> bool:
    """Download cover_url and embed into mp3 as APIC if not already present."""
    if not cover_url or not mp3_path or not os.path.exists(mp3_path):
        return False
    try:
        from mutagen.id3 import ID3, APIC
        from mutagen.mp3 import MP3
        try:
            audio = ID3(mp3_path)
            has_apic = any(k.startswith("APIC") for k in audio.keys())
            if has_apic:
                return True
        except Exception:
            pass
    except Exception:
        pass
    try:
        timeout = ClientTimeout(total=10)
        async with ClientSession(timeout=timeout) as session:
            async with session.get(cover_url) as resp:
                if resp.status != 200:
                    return False
                data = await resp.read()
                ctype = resp.headers.get("Content-Type", "image/jpeg")
                if len(data) < 200:
                    return False
        def _embed():
            from mutagen.id3 import ID3, APIC, TIT2, TPE1
            from mutagen.mp3 import MP3
            try:
                audio = MP3(mp3_path, ID3=ID3)
                if audio.tags is None:
                    audio.add_tags()
                audio.tags.delall("APIC")
                mime = "image/jpeg"
                if "png" in ctype:
                    mime = "image/png"
                elif "webp" in ctype:
                    mime = "image/webp"
                audio.tags.add(APIC(encoding=3, mime=mime, type=3, desc="Cover", data=data))
                audio.save()
                return True
            except Exception as e:
                logging.debug(f"Embed cover mutagen error: {e}")
                return False
        return await asyncio.to_thread(_embed)
    except Exception as e:
        logging.debug(f"Embed cover download error {cover_url}: {e}")
        return False

def probe_local_track_duration(path: str) -> float | None:
    """Read local audio duration with ffprobe and cache by file mtime."""
    try:
        if not path or not os.path.exists(path) or not FFPROBE:
            return None
        mtime = os.path.getmtime(path)
        cached = track_duration_cache.get(path)
        if cached and cached.get("mtime") == mtime:
            return cached.get("duration")

        cmd = [
            FFPROBE,
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            path,
        ]
        out = subprocess.check_output(cmd, text=True, errors="ignore").strip()
        duration = float(out)
        if duration > 0:
            track_duration_cache[path] = {"mtime": mtime, "duration": duration}
            return duration
    except Exception:
        return None
    return None

async def resolve_artist_avatar(session: ClientSession, artist: str) -> str:
    """Resolve an artist avatar from the internet using iTunes artwork."""
    key = normalize_track_text(artist)
    if not key:
        return ""
    if key in artist_avatar_cache:
        return artist_avatar_cache[key]

    avatar = ""
    try:
        lookup_url = f"https://itunes.apple.com/search?term={quote_plus(artist)}&entity=song&limit=1"
        async with session.get(lookup_url, timeout=ClientTimeout(total=8), headers={"User-Agent": "MusicFind/1.0"}) as resp:
            if resp.status == 200:
                data = await resp.json(content_type=None)
                results = data.get("results") or []
                if results:
                    item = results[0]
                    avatar = item.get("artworkUrl100") or item.get("artworkUrl60") or ""
                    if avatar:
                        avatar = avatar.replace("100x100bb", "600x600bb").replace("60x60bb", "600x600bb")
    except Exception:
        avatar = ""

    artist_avatar_cache[key] = avatar
    return avatar

def collect_user_playlist_signature(uid: int) -> tuple[str, list[tuple[str, str]]]:
    """Collect a stable signature for the user's playlist files and the file list."""
    entries: list[tuple[str, str]] = []
    signature_parts: list[str] = []
    for pl_name in list_playlists(uid):
        for filename in list_tracks(uid, pl_name):
            path = os.path.join(get_playlist_dir(uid, pl_name), filename)
            try:
                stat = os.stat(path)
            except FileNotFoundError:
                continue
            entries.append((path, filename))
            signature_parts.append(f"{path}:{stat.st_mtime_ns}:{stat.st_size}")
    signature = hashlib.sha1("|".join(signature_parts).encode("utf-8")).hexdigest() if signature_parts else "empty"
    return signature, entries

async def build_user_statistics(uid: int) -> dict:
    """Aggregate listening-style statistics from the user's playlists."""
    signature, entries = collect_user_playlist_signature(uid)
    cached = statistics_cache.get(uid)
    if cached and cached.get("signature") == signature:
        return cached["data"]

    total_seconds = 0.0
    artist_seconds: dict[str, float] = {}

    for path, filename in entries:
        duration = await asyncio.to_thread(probe_local_track_duration, path)
        if not duration:
            continue
        total_seconds += duration
        artist = filename.split(" - ", 1)[0].strip() if " - " in filename else "Unknown artist"
        artist_seconds[artist] = artist_seconds.get(artist, 0.0) + duration

    top_artists = sorted(artist_seconds.items(), key=lambda item: item[1], reverse=True)[:3]
    async with ClientSession() as session:
        avatar_tasks = [resolve_artist_avatar(session, artist) for artist, _ in top_artists]
        avatars = await asyncio.gather(*avatar_tasks, return_exceptions=True)

    top_artists_payload = []
    for index, (artist, seconds) in enumerate(top_artists):
        avatar = avatars[index] if index < len(avatars) and isinstance(avatars[index], str) else ""
        top_artists_payload.append({
            "artist": artist,
            "seconds": round(seconds, 1),
            "hours": round(seconds / 3600, 1),
            "avatar": avatar or "",
        })

    playlists = list_playlists(uid)
    data = {
        "uid": uid,
        "playlist_count": len(playlists),
        "track_count": len(entries),
        "total_seconds": round(total_seconds, 1),
        "total_hours": round(total_seconds / 3600, 1),
        "total_days": round(total_seconds / 86400, 1),
        "top_artists": top_artists_payload,
    }
    statistics_cache[uid] = {"signature": signature, "data": data}
    return data

def strip_ext(filename: str) -> str:
    if filename.lower().endswith('.mp3'):
        return filename[:-4]
    return filename

def make_short_callback_id(prefix: str, *parts: str) -> str:
    raw = "|".join(str(part) for part in parts)
    digest = hashlib.sha1(raw.encode("utf-8")).hexdigest()[:16]
    return f"{prefix}:{digest}"

def cookies_file_is_valid(path: str) -> bool:
    """Use cookies only when the file looks like a real Netscape cookies export."""
    try:
        if not path or not os.path.exists(path) or os.path.getsize(path) == 0:
            return False
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            first_line = f.readline().strip()
        return first_line.startswith("# Netscape HTTP Cookie File")
    except Exception as e:
        logging.warning(f"Cookie validation error for {path}: {e}")
        return False

COOKIES_RUNTIME_DIR = os.path.join(BASE_DIR, ".cookies_runtime")
_cookies_runtime_lock = threading.Lock()

def runtime_cookies_path(path: str | None) -> str | None:
    """Return a private runtime copy of the cookie export for yt-dlp.

    yt-dlp rewrites the cookie file after a run (rotating __Secure-* tokens).
    If it writes to the user's export, the browser session gets invalidated
    ("YouTube logs me out"). We therefore always hand yt-dlp a copy and never
    touch the original file. The copy is refreshed only when the user replaces
    their export (newer modification time).
    """
    if not path or not cookies_file_is_valid(path):
        return None
    try:
        os.makedirs(COOKIES_RUNTIME_DIR, exist_ok=True)
    except OSError as e:
        logging.warning(f"Cookie runtime dir error: {e}")
        return None
    runtime = os.path.join(COOKIES_RUNTIME_DIR, os.path.basename(path))
    with _cookies_runtime_lock:
        try:
            if (not os.path.exists(runtime)) or os.path.getmtime(path) > os.path.getmtime(runtime):
                shutil.copyfile(path, runtime)
        except OSError as e:
            logging.warning(f"Cookie runtime copy error for {path}: {e}")
            return None
        return runtime

def build_download_cmd(track: dict, base: str, cookies_path: str | None, quiet: bool = True, show_progress: bool = False) -> list[str]:
    """Build yt-dlp download command with cookies only when valid."""
    cmd = [YTDLP]
    runtime_cookies = runtime_cookies_path(cookies_path)
    if runtime_cookies:
        cmd.extend(["--cookies", runtime_cookies])
    elif cookies_path:
        logging.warning(f"Skipping invalid cookies file: {cookies_path}")
    src = (track.get("src") or "").upper()
    if src in {"YT", "YM"}:
        cmd.extend(["--remote-components", "ejs:github"])
    url = track.get("url") or ""
    if not url:
        raise ValueError("Трек не содержит ссылки для скачивания.")
    cmd.extend([url, "-f", "bestaudio/best", "-x", "--audio-format", "mp3", "--embed-thumbnail", "--embed-metadata", "-o", base + ".%(ext)s"])
    if quiet:
        cmd.append("--quiet")
    else:
        cmd.extend(["--newline", "--no-warnings"])
        if show_progress:
            cmd.append("--progress")
    return cmd

def _download_leftovers(base: str) -> list[str]:
    return [base + ext for ext in (".mp3", ".m4a", ".webm", ".opus", ".ogg", ".part")]

async def _exec_download(job: dict, cmd: list[str]) -> int:
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    job["pid"] = proc.pid
    assert proc.stdout is not None
    async for raw_line in proc.stdout:
        line = raw_line.decode("utf-8", errors="ignore").strip()
        progress = parse_download_progress(line)
        if progress is not None:
            job["progress"] = progress
            job["updated_at"] = datetime.utcnow().isoformat() + "Z"
        if job.get("cancelled"):
            proc.kill()
            break
    await proc.wait()
    job["pid"] = None
    return proc.returncode

async def run_track_download(job: dict, track: dict, base: str, show_progress: bool = True) -> int:
    """Download a SoundCloud / YouTube Music track with yt-dlp."""
    src = (track.get("src") or "SC").strip().upper()
    cookies = SC_COOKIES if src == "SC" else (YT_COOKIES if src in {"YM", "YT"} else None)
    cmd = build_download_cmd(track, base, cookies, quiet=False, show_progress=show_progress)
    rc = await _exec_download(job, cmd)
    if rc != 0 and src in {"YM", "YT"} and cookies:
        logging.info(f"Download failed with cookies, retrying without cookies | {track.get('artist')} - {track.get('title')}")
        for leftover in _download_leftovers(base):
            if os.path.exists(leftover):
                os.remove(leftover)
        job["progress"] = 0.0
        cmd = build_download_cmd(track, base, None, quiet=False, show_progress=show_progress)
        rc = await _exec_download(job, cmd)
    return rc

def _candidate_score(candidate: dict, target_duration) -> tuple:
    src = (candidate.get("src") or "").upper()
    duration = candidate.get("duration")
    diff = 999
    if duration and target_duration:
        try:
            diff = abs(int(float(duration)) - int(float(target_duration)))
        except (TypeError, ValueError):
            diff = 999
    return (0 if src == "SC" else 1, diff)

async def download_track_with_fallback(
    job: dict,
    track: dict,
    base: str,
    title: str,
    artist: str,
    show_progress: bool = True,
) -> int:
    """Download the track; on failure try several SoundCloud candidates.

    SoundCloud has DRM-protected uploads (yt-dlp raises "DRM protected"), and
    spotDL's SoundCloud provider may pick such a dead match. Therefore we retry
    through multiple SoundCloud search results, preferring close durations.
    """
    rc = await run_track_download(job, track, base, show_progress=show_progress)
    if job.get("cancelled"):
        return rc
    if rc == 0 and os.path.exists(base + ".mp3"):
        return rc

    target_duration = track.get("duration") or track.get("duration_seconds")
    query = f"{artist} {title}".strip()
    if not query:
        return rc

    try:
        candidates = await asyncio.to_thread(sc_search_soundcloud, query)
    except Exception as e:
        logging.warning(f"FALLBACK SEARCH ERROR | {query} | {e}")
        candidates = []

    tried = {track.get("url")}
    candidates = [c for c in candidates if c.get("url") and c["url"] not in tried]
    candidates.sort(key=lambda c: _candidate_score(c, target_duration))

    for candidate in candidates[:6]:
        if job.get("cancelled"):
            return rc
        for leftover in _download_leftovers(base):
            if os.path.exists(leftover):
                os.remove(leftover)
        job["src"] = candidate.get("src") or job.get("src")
        job["progress"] = 0.0
        logging.info(
            f"DOWNLOAD FALLBACK | {artist} - {title} | trying {candidate.get('src')} | {candidate['url']}"
        )
        rc = await run_track_download(job, candidate, base, show_progress=show_progress)
        if rc == 0 and os.path.exists(base + ".mp3"):
            return rc
    return rc

def parse_bitrate_value(raw: str | None) -> int | None:
    try:
        numeric = float(str(raw or "").strip())
    except (TypeError, ValueError):
        return None
    if numeric <= 0:
        return None
    return int(round(numeric))

def get_local_track_bitrate(path: str) -> int | None:
    """Probe a local audio file bitrate and cache it by path+mtime."""
    if not FFPROBE or not path or not os.path.exists(path):
        return None
    try:
        cache_key = f"{path}:{os.path.getmtime(path)}"
    except OSError:
        return None
    if cache_key in local_bitrate_cache:
        return local_bitrate_cache[cache_key]

    try:
        out = subprocess.check_output(
            [
                FFPROBE,
                "-v",
                "error",
                "-select_streams",
                "a:0",
                "-show_entries",
                "stream=bit_rate",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                path,
            ],
            text=True,
            errors="ignore",
        ).strip()
        bitrate = parse_bitrate_value(float(out) / 1000 if out else None)
    except Exception:
        bitrate = None
    local_bitrate_cache[cache_key] = bitrate
    return bitrate

def cleanup_temp_storage() -> None:
    now = datetime.now().timestamp()
    removed_files = 0
    removed_dirs = 0

    if not os.path.isdir(TMP_DIR):
        return

    for root, dirs, files in os.walk(TMP_DIR, topdown=False):
        for file_name in files:
            path = os.path.join(root, file_name)
            try:
                if now - os.path.getmtime(path) < TEMP_STORAGE_MAX_AGE_SECONDS:
                    continue
                os.remove(path)
                removed_files += 1
            except FileNotFoundError:
                continue
            except Exception as e:
                logging.warning(f"Temp cleanup file error for {path}: {e}")

        for dir_name in dirs:
            path = os.path.join(root, dir_name)
            try:
                if path == WEBAPP_MEDIA_DIR:
                    continue
                if os.listdir(path):
                    continue
                if now - os.path.getmtime(path) < TEMP_STORAGE_MAX_AGE_SECONDS:
                    continue
                os.rmdir(path)
                removed_dirs += 1
            except FileNotFoundError:
                continue
            except Exception as e:
                logging.warning(f"Temp cleanup dir error for {path}: {e}")

    if removed_files or removed_dirs:
        logging.info(f"TEMP CLEANUP | removed_files={removed_files} | removed_dirs={removed_dirs}")

async def temp_storage_cleanup_loop():
    cleanup_temp_storage()
    while True:
        await asyncio.sleep(TEMP_STORAGE_CLEANUP_INTERVAL_SECONDS)
        cleanup_temp_storage()

def load_known_users():
    """Load users who have authenticated before."""
    global known_users
    try:
        if os.path.exists(KNOWN_USERS_FILE):
            with open(KNOWN_USERS_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            known_users = {int(uid) for uid in data}
        else:
            known_users = set()
        logging.info(f"Known users loaded: {len(known_users)}")
    except Exception as e:
        logging.error(f"Load known users error: {e}")
        known_users = set()

def save_known_users():
    """Persist users who have authenticated before."""
    try:
        with open(KNOWN_USERS_FILE, "w", encoding="utf-8") as f:
            json.dump(sorted(known_users), f, ensure_ascii=False, indent=2)
    except Exception as e:
        logging.error(f"Save known users error: {e}")

def load_whitelist():
    """Load users with permanent password-free access."""
    global whitelist_users
    try:
        if os.path.exists(WHITELIST_FILE):
            with open(WHITELIST_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            whitelist_users = {int(uid) for uid in data}
        else:
            whitelist_users = set()
        logging.info(f"Whitelist loaded: {len(whitelist_users)}")
    except Exception as e:
        logging.error(f"Load whitelist error: {e}")
        whitelist_users = set()

def save_whitelist():
    """Persist users with permanent password-free access."""
    try:
        with open(WHITELIST_FILE, "w", encoding="utf-8") as f:
            json.dump(sorted(whitelist_users), f, ensure_ascii=False, indent=2)
    except Exception as e:
        logging.error(f"Save whitelist error: {e}")

def load_now_playing():
    """Load persisted now-playing state for the mini app."""
    global now_playing_state
    try:
        if os.path.exists(NOW_PLAYING_FILE):
            with open(NOW_PLAYING_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            now_playing_state = {str(uid): value for uid, value in data.items()}
        else:
            now_playing_state = {}
        logging.info(f"Now playing state loaded: {len(now_playing_state)} users")
    except Exception as e:
        logging.error(f"Load now playing error: {e}")
        now_playing_state = {}

def save_now_playing():
    """Persist now-playing state for the mini app."""
    try:
        with open(NOW_PLAYING_FILE, "w", encoding="utf-8") as f:
            json.dump(now_playing_state, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logging.error(f"Save now playing error: {e}")

def is_known_to_bot(uid) -> bool:
    """Check whether the user is known enough to access the mini app."""
    if is_account_uid(uid):
        return True
    try:
        tid = int(uid)
        if get_account_by_telegram_id(tid):
            return True
        return tid in known_users or is_whitelisted(tid) or is_authenticated(tid)
    except:
        return False

async def build_playlist_payload(uid: int) -> list:
    """Serialize playlists for the mini app, with cover art resolution."""
    ensure_default_playlists(uid)
    payload = []
    for playlist_name in sorted(list_playlists(uid)):
        tracks = list_tracks(uid, playlist_name)
        meta = load_playlist_meta(uid, playlist_name)

        track_list = []
        for track_name in tracks:
            title = strip_ext(track_name).split(" - ", 1)[1].strip() if " - " in strip_ext(track_name) else strip_ext(track_name)
            artist = strip_ext(track_name).split(" - ", 1)[0].strip() if " - " in strip_ext(track_name) else "Unknown Artist"
            fpath = os.path.join(get_playlist_dir(uid, playlist_name), track_name)
            bitrate = get_local_track_bitrate(fpath)
            duration = probe_local_track_duration(fpath)
            cover = extract_embedded_cover_url(fpath)
            track_list.append({
                "file_name": track_name,
                "title": title,
                "artist": artist,
                "bitrate_kbps": bitrate,
                "cover": cover,
                "duration": int(duration) if duration else None,
                "duration_seconds": int(duration) if duration else None,
            })

        needs_cover = [i for i, t in enumerate(track_list) if not t["cover"]][:10]
        if needs_cover:
            cover_tasks = [resolve_cover_art(track_list[i]) for i in needs_cover]
            covers = await asyncio.gather(*cover_tasks, return_exceptions=True)
            for idx, cover in zip(needs_cover, covers):
                if isinstance(cover, str) and cover:
                    track_list[idx]["cover"] = cover

        payload.append({
            "name": playlist_name,
            "track_count": len(tracks),
            "cover_url": meta.get("cover_url") or "",
            "is_favorites": playlist_name == LIKED_PLAYLIST_NAME,
            "tracks": track_list,
        })
    return payload

def register_webapp_asset(uid: int, path: str) -> str:
    media_id = uuid.uuid4().hex
    token = uuid.uuid4().hex
    webapp_media_cache[media_id] = {
        "uid": uid,
        "token": token,
        "path": path,
        "temporary": bool(path.startswith(WEBAPP_MEDIA_DIR)),
        "created_at": datetime.utcnow().isoformat() + "Z",
    }
    return build_webapp_media_url(media_id, token)

async def add_track_to_playlist(uid: int, playlist_name: str, source_path: str, track: dict) -> str:
    dest_dir = get_playlist_dir(uid, playlist_name)
    dest_path = os.path.join(dest_dir, build_playlist_track_filename(track))
    await asyncio.to_thread(shutil.copy2, source_path, dest_path)
    return dest_path

def release_webapp_media_for_user(uid: int, media_id: str) -> bool:
    media = webapp_media_cache.get(media_id)
    if not media or media.get("uid") != uid:
        return False

    webapp_media_cache.pop(media_id, None)
    webapp_media_recognition_jobs.pop(media_id, None)
    return True

async def resolve_local_track_path(uid: int, track: dict) -> str:
    media_id = (track.get("id") or "").strip()
    media = webapp_media_cache.get(media_id) if media_id else None
    if media and media.get("uid") == uid:
        candidate = media.get("path") or ""
        if candidate and os.path.exists(candidate):
            return candidate

    playlist_name = (track.get("playlist_name") or "").strip()
    file_name = (track.get("file_name") or "").strip()
    if playlist_name and file_name:
        candidate = os.path.join(get_playlist_dir(uid, playlist_name), file_name)
        if os.path.exists(candidate):
            return candidate

    media = await resolve_webapp_stream_track(uid, track)
    local_path = webapp_media_cache.get(media.get("id"), {}).get("path")
    if local_path and os.path.exists(local_path):
        return local_path
    raise RuntimeError("Не удалось подготовить трек.")

def remove_track_from_playlist(uid: int, playlist_name: str, track: dict) -> bool:
    existing = find_playlist_track_path(uid, playlist_name, track)
    if not existing or not os.path.exists(existing):
        return False
    os.remove(existing)
    return True

def remove_playlist(uid: int, playlist_name: str) -> bool:
    if not playlist_name or playlist_name == LIKED_PLAYLIST_NAME:
        return False
    playlist_dir = os.path.join(get_user_dir(uid), playlist_name)
    if not os.path.isdir(playlist_dir):
        return False
    shutil.rmtree(playlist_dir)
    return True

def track_is_favorite(uid: int, track: dict) -> bool:
    return find_playlist_track_path(uid, LIKED_PLAYLIST_NAME, track) is not None

async def toggle_favorite_track(uid: int, track: dict) -> bool:
    ensure_default_playlists(uid)
    try:
        local_path = await resolve_local_track_path(uid, track)
    except RuntimeError as e:
        if str(e) == "TRACK_PREPARING":
            download_key, job = ensure_webapp_download_job(track)
            for _ in range(60):
                await asyncio.sleep(1)
                status = job.get("status")
                if status == "ready" and job.get("path") and os.path.exists(job.get("path")):
                    local_path = job.get("path")
                    break
                if status == "error":
                    raise RuntimeError(job.get("error") or "Не удалось подготовить трек для избранного.")
                if status == "cancelled":
                    raise RuntimeError("Загрузка отменена")
            else:
                if not (job.get("status") == "ready" and job.get("path") and os.path.exists(job.get("path"))):
                    raise RuntimeError("Таймаут подготовки трека")
        else:
            raise

    if not local_path or not os.path.exists(local_path):
        raise RuntimeError("Не удалось подготовить трек для избранного.")

    favorite = not track_is_favorite(uid, track)
    if favorite:
        await add_track_to_playlist(uid, LIKED_PLAYLIST_NAME, local_path, track)
    else:
        remove_track_from_playlist(uid, LIKED_PLAYLIST_NAME, track)

    return favorite

async def add_track_to_user_playlist(uid: int, playlist_name: str, track: dict) -> str:
    ensure_default_playlists(uid)
    normalized_name = normalize_playlist_name(playlist_name)
    if not normalized_name:
        raise ValueError("PLAYLIST_NAME_MISSING")
    if not playlist_exists(uid, normalized_name):
        raise FileNotFoundError("PLAYLIST_NOT_FOUND")

    try:
        local_path = await resolve_local_track_path(uid, track)
    except RuntimeError as e:
        if str(e) == "TRACK_PREPARING":
            download_key, job = ensure_webapp_download_job(track)
            for _ in range(60):
                await asyncio.sleep(1)
                status = job.get("status")
                if status == "ready" and job.get("path") and os.path.exists(job.get("path")):
                    local_path = job.get("path")
                    break
                if status == "error":
                    raise RuntimeError(job.get("error") or "Не удалось подготовить трек")
                if status == "cancelled":
                    raise RuntimeError("Загрузка отменена")
            else:
                if not (job.get("status") == "ready" and job.get("path") and os.path.exists(job.get("path"))):
                    raise RuntimeError("Таймаут подготовки трека")
        else:
            raise

    if not local_path or not os.path.exists(local_path):
        raise RuntimeError("TRACK_NOT_READY")

    await add_track_to_playlist(uid, normalized_name, local_path, track)
    return normalized_name

def serialize_search_track(track: dict) -> dict:
    """Convert internal search result to API payload."""
    duration = track.get("duration") if track.get("duration") is not None else track.get("duration_seconds")
    try:
        duration = int(float(duration)) if duration is not None and str(duration) != "NA" else None
    except Exception:
        duration = None
    return {
        "title": track.get("title") or "",
        "artist": track.get("artist") or "",
        "full_title": track.get("full_title") or f"{track.get('artist', '')} - {track.get('title', '')}".strip(" -"),
        "url": track.get("url") or "",
        "src": track.get("src") or "",
        "cover": track.get("cover") or track.get("cover_url") or "",
        "bitrate_kbps": track.get("bitrate_kbps"),
        "duration": duration,
        "duration_seconds": duration,
    }

def serialize_recognized_track(track: dict) -> dict:
    """Convert Shazam recognition payload to mini app payload."""
    if not track:
        return {}
    return {
        "title": track.get("title") or "",
        "artist": track.get("subtitle") or "",
        "cover": ((track.get("images") or {}).get("coverart") or ""),
        "share_url": ((track.get("share") or {}).get("href") or ""),
    }

def media_kind_from_name(filename: str) -> str:
    ext = os.path.splitext((filename or "").lower())[1]
    if ext in {".mp4", ".mov", ".m4v", ".avi", ".mkv", ".webm"}:
        return "video"
    return "audio"

async def extract_audio_excerpt_for_recognition(source_path: str, duration_seconds: int = 12) -> str:
    """Extract a short audio excerpt for faster recognition."""
    target_path = os.path.join(TMP_DIR, f"{uuid.uuid4().hex}.mp3")
    cmd = [
        FFMPEG,
        "-y",
        "-ss",
        "0",
        "-i",
        source_path,
        "-t",
        str(duration_seconds),
        "-vn",
        "-acodec",
        "mp3",
        "-ar",
        "44100",
        "-ac",
        "2",
        target_path,
    ]
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.DEVNULL,
    )
    await proc.communicate()
    if proc.returncode != 0 or not os.path.exists(target_path):
        raise RuntimeError("РќРµ СѓРґР°Р»РѕСЃСЊ РїРѕРґРіРѕС‚РѕРІРёС‚СЊ Р°СѓРґРёРѕ РґР»СЏ СЂР°СЃРїРѕР·РЅР°РІР°РЅРёСЏ.")
    return target_path

async def extract_audio_for_recognition(source_path: str, target_ext: str = ".mp3") -> str:
    """Normalize uploaded media to an audio file that Shazam can inspect."""
    target_path = os.path.join(TMP_DIR, f"{uuid.uuid4().hex}{target_ext}")
    proc = await asyncio.create_subprocess_exec(
        FFMPEG,
        "-y",
        "-i",
        source_path,
        "-vn",
        "-acodec",
        "mp3",
        "-ar",
        "44100",
        "-ac",
        "2",
        target_path,
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.DEVNULL,
    )
    await proc.communicate()
    if proc.returncode != 0 or not os.path.exists(target_path):
        raise RuntimeError("Не удалось подготовить аудио для распознавания.")
    return target_path

async def recognize_track_from_file(source_path: str, original_name: str = "", fast: bool = False) -> tuple[dict | None, str]:
    """Recognize a track and return mini app search query."""
    normalized_path = source_path
    if fast:
        normalized_path = await extract_audio_excerpt_for_recognition(source_path, duration_seconds=12)
    elif media_kind_from_name(original_name) == "video":
        normalized_path = await extract_audio_for_recognition(source_path)
    try:
        result = await shazam.recognize(normalized_path)
    finally:
        if normalized_path != source_path and os.path.exists(normalized_path):
            os.remove(normalized_path)
    recognized = result.get("track") if isinstance(result, dict) else None
    if not recognized:
        return None, ""
    query = f"{recognized.get('subtitle', '').strip()} {recognized.get('title', '').strip()}".strip()
    return recognized, query

async def get_recommendations_payload(uid: int, force_refresh: bool = False) -> list:
    """Return cached or freshly generated recommendations for API/mini app."""
    uid_key = str(uid)
    if not force_refresh and uid_key in recommendations_cache and not is_cache_expired(uid_key):
        return recommendations_cache[uid_key]

    recs = await generate_recommendations(uid_key)
    cover_tasks = [resolve_cover_art(r) for r in recs[:10]]
    covers = await asyncio.gather(*cover_tasks, return_exceptions=True)
    for i, cover in enumerate(covers):
        if i < len(recs) and isinstance(cover, str) and cover:
            recs[i]["cover"] = cover
    recommendations_cache[uid_key] = recs
    last_recommendation_update[uid_key] = datetime.utcnow().isoformat()
    save_recommendations_cache()
    return recs

def get_track_cookies_path(track: dict) -> str | None:
    src = (track.get("src") or "").strip().upper()
    if src == "SC":
        return SC_COOKIES
    if src == "YM":
        return YT_COOKIES
    return None

async def resolve_cover_art(track: dict) -> str | None:
    """Try to resolve artwork for a track using iTunes search (more reliable for filenames), then Shazam."""
    raw_artist = (track.get("artist") or "").strip()
    raw_title = (track.get("title") or "").strip()
    artist = re.sub(r"([a-z])([A-Z])", r"\1 \2", raw_artist).replace("_", " ").strip()
    title = raw_title
    query = f"{artist} {title}".strip()
    if not query:
        return None

    try:
        lookup_url = f"https://itunes.apple.com/search?term={quote_plus(query)}&entity=song&limit=1"
        async with ClientSession() as session:
            async with session.get(lookup_url, timeout=8) as response:
                if response.status == 200:
                    data = await response.json(content_type=None)
                    results = data.get("results") or []
                    if results:
                        artwork = results[0].get("artworkUrl100") or results[0].get("artworkUrl60")
                        if artwork:
                            return artwork.replace("100x100bb", "600x600bb").replace("60x60bb", "600x600bb")
    except Exception as e:
        logging.debug(f"iTunes cover lookup error for {query}: {e}")

    try:
        result = await shazam.search_track(query=query, limit=1)
        tracks = result.get("tracks") or []
        if tracks:
            images = tracks[0].get("images") or {}
            cover = images.get("coverart") or images.get("background")
            if cover:
                return cover
    except Exception as e:
        logging.debug(f"Shazam cover error for {query}: {e}")
    return None

async def resolve_artist_genre_from_web(artist: str) -> str | None:
    """Resolve an artist genre from web metadata when track-level genre is missing."""
    normalized_artist = normalize_track_text(artist)
    if not normalized_artist:
        return None
    if normalized_artist in artist_genre_cache:
        return artist_genre_cache[normalized_artist]

    urls = [
        f"https://itunes.apple.com/search?term={quote_plus(artist)}&entity=song&attribute=artistTerm&limit=25",
        f"https://itunes.apple.com/search?term={quote_plus(artist)}&entity=musicArtist&limit=10",
    ]
    genre_counts = {}

    try:
        async with ClientSession() as session:
            for lookup_url in urls:
                async with session.get(lookup_url, timeout=10) as response:
                    if response.status != 200:
                        continue
                    data = await response.json(content_type=None)
                for item in data.get("results") or []:
                    candidate_artist = (
                        item.get("artistName")
                        or item.get("collectionArtistName")
                        or item.get("trackArtistName")
                        or ""
                    )
                    if candidate_artist and not artist_matches_target(candidate_artist, artist):
                        continue
                    raw_genre = (
                        item.get("primaryGenreName")
                        or item.get("primaryGenre")
                        or item.get("genre")
                        or ""
                    )
                    resolved_genre = infer_genre_from_text(raw_genre)
                    if not resolved_genre:
                        continue
                    genre_counts[resolved_genre] = genre_counts.get(resolved_genre, 0) + 1
    except Exception as e:
        logging.debug(f"Artist genre web lookup error for {artist}: {e}")

    if not genre_counts:
        artist_genre_cache[normalized_artist] = None
        return None

    resolved = max(genre_counts.items(), key=lambda item: item[1])[0]
    artist_genre_cache[normalized_artist] = resolved
    return resolved

async def ensure_playable_recommendation(track: dict) -> dict | None:
    """Ensure a recommendation has a playable URL/source pair for the web player."""
    prepared = dict(track)
    if not prepared.get("url"):
        query = f"{prepared.get('artist', '')} {prepared.get('title', '')}".strip()
        if query:
            results = await asyncio.to_thread(sc_search, query)
            match = find_best_search_match(prepared, results)
            if match:
                prepared["url"] = match.get("url")
                prepared["src"] = match.get("src")
                prepared["full_title"] = match.get("full_title")
    if not prepared.get("cover"):
        prepared["cover"] = await resolve_cover_art(prepared)
    return prepared if prepared.get("url") and prepared.get("src") else None

async def get_random_discovery_recommendations(limit: int = 8) -> list:
    """Build random playable recommendations from SoundCloud and YouTube Music."""
    queries = [
        "viral hits",
        "fresh music",
        "best songs",
        "top tracks",
        "summer vibes",
        "night drive",
    ]
    random.shuffle(queries)
    collected = []
    seen = set()

    for query in queries[:4]:
        results = await asyncio.to_thread(sc_search, query)
        random.shuffle(results)
        for track in results:
            if track.get("src") not in {"SC", "YM"}:
                continue
            track_key = f"{track.get('artist', '')}-{track.get('title', '')}".lower()
            if track_key in seen:
                continue
            seen.add(track_key)
            collected.append({
                "title": track.get("title"),
                "artist": track.get("artist"),
                "url": track.get("url"),
                "src": track.get("src"),
                "full_title": track.get("full_title"),
                "cover": None,
                "reason": f"🎲 Случайно из {'SoundCloud' if track.get('src') == 'SC' else 'YouTube Music'}",
            })
            if len(collected) >= limit:
                return collected
    return collected

def update_now_playing(uid: int, track: dict, cover_url: str | None = None) -> None:
    """Update in-memory and persisted now-playing state."""
    now_playing_state[str(uid)] = {
        "title": track.get("title") or "Unknown title",
        "artist": track.get("artist") or "Unknown artist",
        "source": track.get("src") or "Unknown",
        "cover_url": cover_url,
        "played_at": datetime.utcnow().isoformat() + "Z",
    }
    save_now_playing()

def build_webapp_media_url(media_id: str, token: str) -> str:
    return f"/api/media/{media_id}?token={token}"

def build_webapp_media_recognition_payload(media: dict) -> dict:
    return {
        "status": media.get("recognition_status") or "idle",
        "recognized": media.get("recognized_track"),
        "query": media.get("recognized_query") or "",
        "error": media.get("recognition_error") or "",
    }

async def recognize_webapp_media_file(media_id: str) -> None:
    media = webapp_media_cache.get(media_id)
    if not media:
        return

    job = webapp_media_recognition_jobs.setdefault(media_id, {})
    path = media.get("path") or ""
    if not path or not os.path.exists(path):
        media["recognition_status"] = "error"
        media["recognition_error"] = "Файл для распознавания не найден."
        job.update({"status": "error", "error": media["recognition_error"], "task": None})
        return

    media["recognition_status"] = "recognizing"
    media["recognition_error"] = ""
    job.update({"status": "recognizing", "error": ""})

    try:
        recognized, query = await recognize_track_from_file(path, os.path.basename(path), fast=True)
        if recognized and query:
            payload = serialize_recognized_track(recognized)
            media["recognized_track"] = payload
            media["recognized_query"] = query
            media["recognition_status"] = "ready"
            media["recognition_error"] = ""
            job.update({"status": "ready", "recognized": payload, "query": query, "error": ""})
        else:
            media["recognized_track"] = None
            media["recognized_query"] = ""
            media["recognition_status"] = "not_found"
            media["recognition_error"] = ""
            job.update({"status": "not_found", "recognized": None, "query": "", "error": ""})
    except Exception as e:
        logging.debug(f"WEBAPP MEDIA RECOGNITION ERROR | {media_id} | {e}")
        media["recognized_track"] = None
        media["recognized_query"] = ""
        media["recognition_status"] = "error"
        media["recognition_error"] = str(e) or "Recognition failed"
        job.update({"status": "error", "error": media["recognition_error"]})
    finally:
        job["task"] = None

def ensure_webapp_media_recognition(media_id: str) -> None:
    media = webapp_media_cache.get(media_id)
    if not media:
        return
    if media.get("recognition_status") in {"recognizing", "ready"}:
        return

    job = webapp_media_recognition_jobs.setdefault(media_id, {})
    task = job.get("task")
    if task is not None and not task.done():
        return

    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return

    task = loop.create_task(recognize_webapp_media_file(media_id))
    job["task"] = task
    media["recognition_status"] = "recognizing"
    media["recognition_error"] = ""
    job["status"] = "recognizing"
    job["error"] = ""

def register_webapp_media(uid: int, path: str, title: str, artist: str, source: str, cover_url: str | None = None) -> dict:
    media_id = uuid.uuid4().hex
    token = uuid.uuid4().hex
    duration_seconds = get_audio_duration_seconds(path)
    webapp_media_cache[media_id] = {
        "uid": uid,
        "token": token,
        "path": path,
        "title": title,
        "artist": artist,
        "source": source,
        "cover_url": cover_url,
        "duration_seconds": duration_seconds,
        "temporary": bool(path.startswith(WEBAPP_MEDIA_DIR)),
        "created_at": datetime.utcnow().isoformat() + "Z",
        "recognition_status": "idle",
        "recognized_track": None,
        "recognized_query": "",
        "recognition_error": "",
    }
    ensure_webapp_media_recognition(media_id)
    return {
        "id": media_id,
        "audio_url": build_webapp_media_url(media_id, token),
        "title": title,
        "artist": artist,
        "src": source,
        "cover_url": cover_url,
        "duration_seconds": duration_seconds,
    }

def build_webapp_download_key(track: dict) -> str:
    src = (track.get("src") or "SC").strip() or "SC"
    url = (track.get("url") or "").strip()
    return hashlib.sha1(f"{src}|{url}".encode("utf-8")).hexdigest()

def build_webapp_cached_path(download_key: str) -> str:
    return os.path.join(WEBAPP_MEDIA_DIR, f"{download_key}.mp3")

def parse_download_progress(line: str) -> float | None:
    match = re.search(r"(\d+(?:\.\d+)?)%", line)
    if not match:
        return None
    try:
        return max(0.0, min(100.0, float(match.group(1))))
    except ValueError:
        return None

def build_webapp_ready_media(uid: int, track: dict, path: str, cover_url: str | None = None) -> dict:
    title = (track.get("title") or "Unknown title").strip()
    artist = (track.get("artist") or "Unknown artist").strip()
    src = (track.get("src") or "SC").strip() or "SC"
    update_now_playing(uid, {"title": title, "artist": artist, "src": src}, cover_url)
    return register_webapp_media(uid, path, title, artist, src, cover_url)

async def _find_alternative_source(title: str, artist: str, exclude_src: str | None = None) -> dict | None:
    """Search for an alternative source when the primary one fails (e.g. SC DRM)."""
    query = f"{artist} {title}".strip()
    if not query:
        return None
    try:
        results = await asyncio.to_thread(sc_search, query)
    except Exception:
        return None
    for r in results:
        if not r.get("url"):
            continue
        if exclude_src and (r.get("src") or "").upper() == exclude_src.upper():
            continue
        logging.info(f"DOWNLOAD FALLBACK | {artist} - {title} | trying {r.get('src')} | {r['url']}")
        return r
    return None

async def run_webapp_download_job(download_key: str, track: dict) -> None:
    job = webapp_download_jobs.setdefault(download_key, {})
    cached_path = build_webapp_cached_path(download_key)
    title = (track.get("title") or "Unknown title").strip()
    artist = (track.get("artist") or "Unknown artist").strip()
    src = (track.get("src") or "SC").strip() or "SC"
    try:
        job.update({
            "download_key": download_key,
            "title": title,
            "artist": artist,
            "src": src,
            "path": cached_path,
            "status": "downloading",
            "progress": 0.0,
            "error": None,
            "updated_at": datetime.utcnow().isoformat() + "Z",
        })
        if not os.path.exists(cached_path):
            base = os.path.join(WEBAPP_MEDIA_DIR, download_key)
            returncode = await download_track_with_fallback(
                job, track, base, title, artist, show_progress=True
            )
            if job.get("cancelled"):
                job.update({"status": "cancelled", "updated_at": datetime.utcnow().isoformat() + "Z"})
                return
            if returncode != 0 or not os.path.exists(cached_path):
                raise RuntimeError("Не удалось подготовить аудио для web-плеера.")

        job.update({
            "status": "finalizing",
            "progress": 100.0,
            "updated_at": datetime.utcnow().isoformat() + "Z",
        })
        cover_url = track.get("cover") or track.get("cover_url")
        if not cover_url:
            cover_url = await resolve_cover_art(track)
        if cover_url and os.path.exists(cached_path):
            try:
                await embed_cover_into_mp3(cached_path, cover_url)
            except Exception as e:
                logging.debug(f"Embed cover post-download error: {e}")
        job.update({
            "status": "ready",
            "progress": 100.0,
            "cover_url": cover_url,
            "updated_at": datetime.utcnow().isoformat() + "Z",
        })
    except Exception as e:
        logging.error(f"WEBAPP DOWNLOAD ERROR | {artist} - {title} | {e}")
        job.update({
            "status": "error",
            "error": str(e) or "Не удалось подготовить трек для web-плеера.",
            "updated_at": datetime.utcnow().isoformat() + "Z",
        })
    finally:
        job["task"] = None

def ensure_webapp_download_job(track: dict) -> tuple[str, dict]:
    download_key = build_webapp_download_key(track)
    cached_path = build_webapp_cached_path(download_key)
    title = (track.get("title") or "Unknown title").strip()
    artist = (track.get("artist") or "Unknown artist").strip()
    src = (track.get("src") or "SC").strip() or "SC"
    job = webapp_download_jobs.get(download_key)
    if job and job.get("status") in ("cancelled", "error"):
        webapp_download_jobs.pop(download_key, None)
        job = None
    if os.path.exists(cached_path):
        if not job:
            job = {}
            webapp_download_jobs[download_key] = job
        job.update({
            "download_key": download_key,
            "title": title,
            "artist": artist,
            "src": src,
            "path": cached_path,
            "status": "ready",
            "progress": 100.0,
            "error": None,
            "updated_at": datetime.utcnow().isoformat() + "Z",
            "task": None,
        })
        return download_key, job

    if not job:
        job = {
            "download_key": download_key,
            "title": title,
            "artist": artist,
            "src": src,
            "path": cached_path,
            "status": "queued",
            "progress": 0.0,
            "error": None,
            "updated_at": datetime.utcnow().isoformat() + "Z",
            "task": None,
        }
        webapp_download_jobs[download_key] = job

    task = job.get("task")
    if task is None or task.done():
        job["task"] = asyncio.create_task(run_webapp_download_job(download_key, dict(track)))
    return download_key, job

async def resolve_webapp_stream_track(uid: int, track: dict) -> dict:
    url = (track.get("url") or "").strip()

    if not url:
        for pl_name in list_playlists(uid):
            local_path = find_playlist_track_path(uid, pl_name, track)
            if local_path and os.path.exists(local_path):
                return build_webapp_ready_media(uid, track, local_path)

    if not url:
        title = (track.get("title") or "").strip()
        artist = (track.get("artist") or "").strip()
        query = f"{artist} {title}".strip()
        if query:
            try:
                results = await asyncio.to_thread(sc_search, query)
                best = find_best_search_match(track, results) if results else None
                if best and best.get("url"):
                    track = dict(track)
                    track["url"] = best["url"]
                    track["src"] = best.get("src", track.get("src", "SC"))
                    url = track["url"]
                elif results:
                    track = dict(track)
                    track["url"] = results[0].get("url", "")
                    track["src"] = results[0].get("src", track.get("src", "SC"))
                    url = track["url"]
            except Exception:
                pass
        if not url:
            raise ValueError("У трека нет ссылки для загрузки.")

    download_key, job = ensure_webapp_download_job(track)
    cached_path = build_webapp_cached_path(download_key)
    if job.get("status") == "ready" and os.path.exists(cached_path):
        return build_webapp_ready_media(uid, track, cached_path, job.get("cover_url"))

    if job.get("status") == "error":
        raise RuntimeError(job.get("error") or "Не удалось подготовить аудио для web-плеера.")

    raise RuntimeError("TRACK_PREPARING")

def validate_webapp_init_data(init_data: str) -> dict | None:
    """Validate Telegram Web App initData and return parsed values."""
    if not init_data:
        return None

    try:
        parsed = dict(parse_qsl(init_data, keep_blank_values=True))
        received_hash = parsed.pop("hash", "")
        if not received_hash:
            return None

        data_check_string = "\n".join(f"{key}={value}" for key, value in sorted(parsed.items()))
        secret_key = hmac.new(b"WebAppData", TOKEN.encode("utf-8"), hashlib.sha256).digest()
        calculated_hash = hmac.new(secret_key, data_check_string.encode("utf-8"), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(calculated_hash, received_hash):
            return None

        auth_date = int(parsed.get("auth_date", "0") or "0")
        if auth_date:
            age = datetime.utcnow().timestamp() - auth_date
            if age > 2592000:
                return None
        return parsed
    except Exception as e:
        logging.warning(f"Mini app initData validation failed: {e}")
        return None

def extract_miniapp_user(request: web.Request) -> tuple[int | None, dict | None]:
    """Extract and validate mini app user from request headers."""
    init_data = request.headers.get("X-Telegram-Init-Data", "")
    validated = validate_webapp_init_data(init_data)
    if not validated:
        return None, None

    try:
        user = json.loads(validated.get("user", "{}"))
        uid = int(user.get("id"))
        return uid, user
    except Exception:
        return None, None


SESSIONS_FILE = os.path.join(os.path.dirname(__file__), "sessions.json")

def load_sessions() -> dict:
    try:
        with open(SESSIONS_FILE, "r") as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}

def save_sessions(sessions: dict) -> None:
    try:
        with open(SESSIONS_FILE, "w") as f:
            json.dump(sessions, f)
    except Exception as e:
        logging.warning(f"Failed to save sessions: {e}")

android_sessions = load_sessions()

def validate_telegram_login_widget(data: dict) -> dict | None:
    """Validate Telegram Login Widget data (not WebApp initData)."""
    received_hash = data.get("hash", "")
    if not received_hash:
        return None

    check_data = {k: v for k, v in data.items() if k != "hash"}
    data_check_string = "\n".join(f"{key}={value}" for key, value in sorted(check_data.items()))

    secret_key = hashlib.sha256(TOKEN.encode("utf-8")).digest()
    calculated_hash = hmac.new(secret_key, data_check_string.encode("utf-8"), hashlib.sha256).hexdigest()

    if not hmac.compare_digest(calculated_hash, received_hash):
        return None

    auth_date = int(data.get("auth_date", "0") or "0")
    if auth_date:
        age = datetime.utcnow().timestamp() - auth_date
        if age > 2592000:
            return None

    return check_data

async def webapp_android_auth(request: web.Request) -> web.Response:
    """Authenticate native Android app via Telegram Login Widget data."""
    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные."}, status=400)

    password = (payload.pop("password", None) or "").strip()

    if BOT_PASSWORD:
        if not password:
            return web.json_response({"ok": False, "error": "PASSWORD_REQUIRED", "message": "Введите пароль."}, status=403)
        if password != BOT_PASSWORD:
            return web.json_response({"ok": False, "error": "WRONG_PASSWORD", "message": "Неверный пароль."}, status=403)

    validated = validate_telegram_login_widget(payload)
    if not validated:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Неверная подпись Telegram."}, status=403)

    try:
        uid = int(validated.get("id"))
        user = {
            "id": uid,
            "first_name": validated.get("first_name", ""),
            "last_name": validated.get("last_name", ""),
            "username": validated.get("username", ""),
            "photo_url": validated.get("photo_url", ""),
        }
    except Exception:
        return web.json_response({"ok": False, "error": "AUTH_PARSE", "message": "Не удалось разобрать данные пользователя."}, status=400)

    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Бот не знает этого пользователя."}, status=403)

    session_token = hashlib.sha256(f"{uid}:{datetime.utcnow().timestamp()}:{TOKEN}".encode()).hexdigest()
    android_sessions[session_token] = {"uid": uid, "user": user, "created_at": datetime.utcnow().timestamp()}
    save_sessions(android_sessions)

    touch_user_activity(uid)
    logging.info(f"WEBAPP ANDROID AUTH | USER {uid} | {user.get('first_name', '')}")
    return web.json_response({"ok": True, "session_token": session_token, "user": user})

async def webapp_password_auth(request: web.Request) -> web.Response:
    """Authenticate Android app via password only."""
    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные."}, status=400)

    password = (payload.get("password") or "").strip()
    if not BOT_PASSWORD:
        return web.json_response({"ok": False, "error": "NO_PASSWORD", "message": "Пароль не настроен."}, status=400)
    if password != BOT_PASSWORD:
        return web.json_response({"ok": False, "error": "WRONG_PASSWORD", "message": "Неверный пароль."}, status=403)

    uid = next(iter(known_users), 0)
    user = {"id": uid, "first_name": "Android User", "last_name": "", "username": "", "photo_url": ""}
    known_users.add(uid)
    session_token = hashlib.sha256(f"password:{datetime.utcnow().timestamp()}:{TOKEN}".encode()).hexdigest()
    android_sessions[session_token] = {"uid": uid, "user": user, "created_at": datetime.utcnow().timestamp()}
    save_sessions(android_sessions)

    touch_user_activity(uid)
    logging.info(f"WEBAPP PASSWORD AUTH | uid={uid}")
    return web.json_response({"ok": True, "session_token": session_token, "user": user})

def create_account_session(acc: dict):
    token = hashlib.sha256(f"{acc['uid']}:{datetime.utcnow().timestamp()}:{TOKEN}:{secrets.token_hex(8)}".encode()).hexdigest()
    user = {
        "id": acc["uid"],
        "first_name": acc.get("display_name") or acc.get("username") or "",
        "last_name": "",
        "username": acc.get("username") or "",
        "photo_url": acc.get("avatar_url") or "",
        "is_account": True,
        "account_username": acc.get("username"),
        "telegram_id": acc.get("telegram_id"),
    }
    android_sessions[token] = {"uid": acc["uid"], "user": user, "created_at": datetime.utcnow().timestamp()}
    save_sessions(android_sessions)
    try:
        touch_user_activity(acc["uid"])
    except:
        pass
    return token, user

async def webapp_account_register(request: web.Request) -> web.Response:
    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные."}, status=400)
    username = (payload.get("username") or "").strip()
    password = (payload.get("password") or "").strip()
    bot_password = (payload.get("bot_password") or "").strip()
    display_name = (payload.get("display_name") or "").strip()
    if not username or not password:
        return web.json_response({"ok": False, "error": "MISSING_FIELDS", "message": "Укажите ник и пароль."}, status=400)
    if BOT_PASSWORD:
        if not bot_password:
            return web.json_response({"ok": False, "error": "BOT_PASSWORD_REQUIRED", "message": "Введите пароль бота из .env"}, status=403)
        if bot_password != BOT_PASSWORD:
            return web.json_response({"ok": False, "error": "WRONG_BOT_PASSWORD", "message": "Неверный пароль бота."}, status=403)
    try:
        acc = create_account(username, password, display_name or username)
    except ValueError as e:
        return web.json_response({"ok": False, "error": "REGISTER_FAILED", "message": str(e)}, status=400)
    except Exception as e:
        logging.error(f"Account register error: {e}")
        return web.json_response({"ok": False, "error": "REGISTER_FAILED", "message": "Не удалось создать аккаунт"}, status=500)
    token, user = create_account_session(acc)
    logging.info(f"ACCOUNT REGISTER | {username} | {acc['uid']}")
    return web.json_response({"ok": True, "session_token": token, "user": user, "account": acc})

async def webapp_account_login(request: web.Request) -> web.Response:
    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные."}, status=400)
    username = (payload.get("username") or "").strip()
    password = (payload.get("password") or "").strip()
    if not username or not password:
        return web.json_response({"ok": False, "error": "MISSING_FIELDS", "message": "Укажите ник и пароль."}, status=400)
    acc = get_account_by_username(username)
    if not acc:
        return web.json_response({"ok": False, "error": "ACCOUNT_NOT_FOUND", "message": "Аккаунт не найден"}, status=404)
    if not verify_password(password, acc.get("password_hash") or "", acc.get("salt") or ""):
        return web.json_response({"ok": False, "error": "WRONG_PASSWORD", "message": "Неверный пароль"}, status=403)
    token, user = create_account_session(acc)
    logging.info(f"ACCOUNT LOGIN | {username} | {acc['uid']}")
    return web.json_response({"ok": True, "session_token": token, "user": user, "account": acc})

async def webapp_account_profile(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался"}, status=401)
    acc = None
    if is_account_uid(uid):
        acc = get_account_by_uid(uid)
    else:
        try:
            tid = int(uid)
            acc = get_account_by_telegram_id(tid)
        except:
            acc = None
    if acc:
        return web.json_response({"ok": True, "account": acc, "user": user, "is_account": True, "telegram_linked": bool(acc.get("telegram_id"))})
    return web.json_response({"ok": True, "account": None, "user": user, "is_account": False, "telegram_linked": False})

async def webapp_account_update_profile(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался"}, status=401)
    acc = None
    acc_key = None
    if is_account_uid(uid):
        acc = get_account_by_uid(uid)
        acc_key = acc.get("username_key") if acc else None
    else:
        try:
            tid = int(uid)
            acc = get_account_by_telegram_id(tid)
            if acc:
                acc_key = acc.get("username_key")
        except:
            pass
    if not acc or not acc_key:
        try:
            payload = await request.json()
        except Exception:
            return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные"}, status=400)
        display_name = payload.get("display_name")
        avatar_url = payload.get("avatar_url")
        try:
            tid = int(uid)
            prof = update_telegram_profile(tid, display_name=display_name, avatar_url=avatar_url)
            token = request.headers.get("X-Session-Token", "") or request.cookies.get("session_token", "")
            if token and token in android_sessions:
                sess = android_sessions[token]
                if display_name is not None:
                    sess["user"]["first_name"] = prof.get("display_name") or sess["user"].get("first_name")
                if avatar_url is not None:
                    sess["user"]["photo_url"] = prof.get("avatar_url") or ""
                save_sessions(android_sessions)
            return web.json_response({"ok": True, "account": None, "telegram_profile": prof})
        except Exception as e:
            logging.error(f"Telegram profile update error: {e}")
            return web.json_response({"ok": False, "error": "ACCOUNT_NOT_FOUND", "message": "Аккаунт не найден. Создайте аккаунт или привяжите Telegram."}, status=404)
    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные"}, status=400)
    display_name = payload.get("display_name")
    avatar_url = payload.get("avatar_url")
    try:
        updated = update_account_profile(acc_key, display_name=display_name, avatar_url=avatar_url)
    except ValueError as e:
        return web.json_response({"ok": False, "error": "UPDATE_FAILED", "message": str(e)}, status=400)
    token = request.headers.get("X-Session-Token", "") or request.cookies.get("session_token", "")
    if token and token in android_sessions:
        sess = android_sessions[token]
        sess["user"]["first_name"] = updated.get("display_name") or updated.get("username")
        sess["user"]["photo_url"] = updated.get("avatar_url") or ""
        sess["user"]["username"] = updated.get("username") or ""
        save_sessions(android_sessions)
    return web.json_response({"ok": True, "account": updated})

async def webapp_account_avatar_upload(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался"}, status=401)
    acc = None
    acc_key = None
    if is_account_uid(uid):
        acc = get_account_by_uid(uid)
        acc_key = acc.get("username_key") if acc else None
    else:
        try:
            tid = int(uid)
            acc = get_account_by_telegram_id(tid)
            if acc:
                acc_key = acc.get("username_key")
        except:
            pass
    is_telegram_only = False
    telegram_id = None
    if not acc or not acc_key:
        try:
            telegram_id = int(uid)
            is_telegram_only = True
        except:
            return web.json_response({"ok": False, "error": "ACCOUNT_NOT_FOUND", "message": "Аккаунт не найден"}, status=404)
    try:
        reader = await request.multipart()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_MULTIPART", "message": "Не удалось прочитать файл"}, status=400)
    field = await reader.next()
    if not field or field.name != "avatar":
        return web.json_response({"ok": False, "error": "FILE_MISSING", "message": "Файл не передан"}, status=400)
    filename = field.filename or "avatar.jpg"
    ext = os.path.splitext(filename)[1].lower() or ".jpg"
    if ext not in {".jpg", ".jpeg", ".png", ".webp"}:
        ext = ".jpg"
    avatars_dir = os.path.join(WEBAPP_MEDIA_DIR, "avatars")
    os.makedirs(avatars_dir, exist_ok=True)
    if is_telegram_only:
        dest = os.path.join(avatars_dir, f"tg_{telegram_id}{ext}")
    else:
        dest = os.path.join(avatars_dir, f"{acc_key}{ext}")
    size = 0
    try:
        with open(dest, "wb") as f:
            while True:
                chunk = await field.read_chunk()
                if not chunk:
                    break
                size += len(chunk)
                if size > 5 * 1024 * 1024:
                    raise ValueError("FILE_TOO_LARGE")
                f.write(chunk)
    except ValueError as e:
        if str(e) == "FILE_TOO_LARGE":
            return web.json_response({"ok": False, "error": "FILE_TOO_LARGE", "message": "Файл слишком большой (макс 5МБ)"}, status=400)
        raise
    except Exception as e:
        logging.error(f"Avatar upload error: {e}")
        return web.json_response({"ok": False, "error": "UPLOAD_FAILED", "message": "Не удалось сохранить аватар"}, status=500)
    if is_telegram_only:
        avatar_url = f"/api/avatar/tg_{telegram_id}{ext}?v={int(datetime.utcnow().timestamp())}"
        update_telegram_profile(telegram_id, avatar_url=avatar_url)
        token = request.headers.get("X-Session-Token", "") or request.cookies.get("session_token", "")
        if token and token in android_sessions:
            android_sessions[token]["user"]["photo_url"] = avatar_url
            save_sessions(android_sessions)
        return web.json_response({"ok": True, "avatar_url": avatar_url, "telegram_profile": get_telegram_profile(telegram_id)})
    else:
        avatar_url = f"/api/avatar/{acc_key}{ext}?v={int(datetime.utcnow().timestamp())}"
        acc["avatar_url"] = avatar_url
        save_accounts()
        token = request.headers.get("X-Session-Token", "") or request.cookies.get("session_token", "")
        if token and token in android_sessions:
            android_sessions[token]["user"]["photo_url"] = avatar_url
            save_sessions(android_sessions)
        return web.json_response({"ok": True, "avatar_url": avatar_url, "account": acc})

async def webapp_serve_avatar(request: web.Request) -> web.Response:
    key = request.match_info.get("key", "")
    avatars_dir = os.path.join(WEBAPP_MEDIA_DIR, "avatars")
    candidate = os.path.join(avatars_dir, key)
    if os.path.exists(candidate):
        return web.FileResponse(candidate)
    base = os.path.splitext(key)[0]
    for ext in [".jpg", ".jpeg", ".png", ".webp"]:
        cand = os.path.join(avatars_dir, base + ext)
        if os.path.exists(cand):
            return web.FileResponse(cand)
    raise web.HTTPNotFound(text="Avatar not found")

async def webapp_serve_cover(request: web.Request) -> web.Response:
    """Serve an extracted playlist track cover by stable filename.

    Covers must have restart-stable URLs; the tokenized /api/media route lives
    only in memory and 404s after a server restart.
    """
    key = os.path.basename(request.match_info.get("key", ""))
    if not key:
        raise web.HTTPNotFound(text="Cover not found")
    covers_dir = os.path.join(WEBAPP_MEDIA_DIR, "covers")
    candidate = os.path.join(covers_dir, key)
    if os.path.exists(candidate) and os.path.isfile(candidate):
        return web.FileResponse(candidate)
    base = os.path.splitext(key)[0]
    for ext in [".jpg", ".jpeg", ".png", ".webp"]:
        cand = os.path.join(covers_dir, base + ext)
        if os.path.exists(cand) and os.path.isfile(cand):
            return web.FileResponse(cand)
    raise web.HTTPNotFound(text="Cover not found")

async def webapp_account_link_telegram(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался"}, status=401)
    if not is_account_uid(uid):
        return web.json_response({"ok": False, "error": "NOT_ACCOUNT", "message": "Только аккаунт может привязать Telegram"}, status=400)
    acc = get_account_by_uid(uid)
    if not acc:
        return web.json_response({"ok": False, "error": "ACCOUNT_NOT_FOUND", "message": "Аккаунт не найден"}, status=404)
    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные"}, status=400)
    init_data = (payload.get("initData") or payload.get("init_data") or "").strip()
    if not init_data:
        init_data = request.headers.get("X-Telegram-Init-Data", "")
    validated = validate_webapp_init_data(init_data)
    if not validated:
        return web.json_response({"ok": False, "error": "TELEGRAM_INVALID", "message": "Неверные данные Telegram"}, status=403)
    try:
        tg_user = json.loads(validated.get("user", "{}"))
        tid = int(tg_user.get("id"))
    except Exception:
        return web.json_response({"ok": False, "error": "TELEGRAM_PARSE", "message": "Не удалось разобрать Telegram пользователя"}, status=400)
    try:
        updated = link_telegram_to_account(acc.get("username_key"), tid, tg_user)
    except ValueError as e:
        return web.json_response({"ok": False, "error": "LINK_FAILED", "message": str(e)}, status=400)
    logging.info(f"ACCOUNT LINK | {acc.get('username')} | tg={tid}")
    return web.json_response({"ok": True, "account": updated})

async def webapp_account_unlink_telegram(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался"}, status=401)
    if not is_account_uid(uid):
        return web.json_response({"ok": False, "error": "NOT_ACCOUNT", "message": "Только аккаунт может отвязать Telegram"}, status=400)
    acc = get_account_by_uid(uid)
    if not acc:
        return web.json_response({"ok": False, "error": "ACCOUNT_NOT_FOUND", "message": "Аккаунт не найден"}, status=404)
    try:
        updated = unlink_telegram_from_account(acc.get("username_key"))
    except ValueError as e:
        return web.json_response({"ok": False, "error": "UNLINK_FAILED", "message": str(e)}, status=400)
    logging.info(f"ACCOUNT UNLINK | {acc.get('username')}")
    return web.json_response({"ok": True, "account": updated})

async def webapp_account_featured_toggle(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался"}, status=401)
    acc = None
    if is_account_uid(uid):
        acc = get_account_by_uid(uid)
    else:
        try:
            acc = get_account_by_telegram_id(int(uid))
        except:
            acc = None
    if not acc:
        return web.json_response({"ok": False, "error": "ACCOUNT_NOT_FOUND", "message": "Нужен аккаунт. Создайте аккаунт."}, status=404)
    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные"}, status=400)
    track = payload.get("track") or {}
    action = (payload.get("action") or "toggle").strip().lower()
    if not track.get("title"):
        return web.json_response({"ok": False, "error": "TRACK_INVALID", "message": "Трек не указан"}, status=400)
    try:
        if action == "remove":
            updated = remove_featured_from_profile(uid, track)
        elif action == "add":
            updated = add_featured_to_profile(uid, track)
        else:
            tkey = trackKey(track)
            exists = any(trackKey(t) == tkey for t in acc.get("profile_featured", []))
            if exists:
                updated = remove_featured_from_profile(uid, track)
            else:
                updated = add_featured_to_profile(uid, track)
    except ValueError as e:
        return web.json_response({"ok": False, "error": "FEATURED_FAILED", "message": str(e)}, status=400)
    return web.json_response({"ok": True, "account": updated, "featured": updated.get("profile_featured", [])})

def extract_android_user(request: web.Request):
    """Extract user from session token (header or cookie) or WebApp initData. Supports Telegram and account sessions."""
    token = request.headers.get("X-Session-Token", "")
    if not token:
        token = request.cookies.get("session_token", "")
    if not token:
        init_data = request.headers.get("X-Telegram-Init-Data", "")
        validated = validate_webapp_init_data(init_data)
        if validated:
            try:
                user = json.loads(validated.get("user", "{}"))
                tid = int(user.get("id"))
                acc = get_account_by_telegram_id(tid)
                if acc:
                    uid = acc["uid"]
                    acc_user = {
                        "id": uid,
                        "first_name": acc.get("display_name") or acc.get("username") or user.get("first_name") or "",
                        "last_name": "",
                        "username": acc.get("username") or user.get("username") or "",
                        "photo_url": acc.get("avatar_url") or user.get("photo_url") or "",
                        "telegram_id": tid,
                        "is_account": True,
                        "account_username": acc.get("username"),
                    }
                    try:
                        touch_user_activity(uid)
                    except:
                        pass
                    return uid, acc_user
                if tid in known_users:
                    touch_user_activity(tid)
                return tid, user
            except Exception:
                pass
        return None, None

    session = android_sessions.get(token)
    if not session:
        return None, None

    age = datetime.utcnow().timestamp() - session["created_at"]
    if age > 2592000:
        android_sessions.pop(token, None)
        save_sessions(android_sessions)
        return None, None
    try:
        session["created_at"] = datetime.utcnow().timestamp()
        save_sessions(android_sessions)
    except Exception:
        pass

    uid = session["uid"]
    if is_account_uid(uid):
        try:
            touch_user_activity(uid)
        except:
            pass
        return uid, session["user"]
    try:
        tid = int(uid)
        acc = get_account_by_telegram_id(tid)
        if acc:
            acc_user = {
                "id": acc["uid"],
                "first_name": acc.get("display_name") or acc.get("username") or session["user"].get("first_name") or "",
                "last_name": "",
                "username": acc.get("username") or session["user"].get("username") or "",
                "photo_url": acc.get("avatar_url") or session["user"].get("photo_url") or "",
                "telegram_id": tid,
                "is_account": True,
                "account_username": acc.get("username"),
            }
            try:
                touch_user_activity(acc["uid"])
            except:
                pass
            return acc["uid"], acc_user
    except:
        pass
    if uid in known_users:
        try:
            touch_user_activity(uid)
        except:
            pass
    return uid, session["user"]

import subprocess
import tempfile

_obfuscated_js_cache = None
_obfuscated_js_mtime = 0

def _build_obfuscated_js():
    """Transpile JSX with esbuild, then obfuscate with javascript-obfuscator."""
    global _obfuscated_js_cache, _obfuscated_js_mtime
    js_path = os.path.join(WEBAPP_DIR, "src", "app.js")
    mtime = os.path.getmtime(js_path)
    if _obfuscated_js_cache and _obfuscated_js_mtime == mtime:
        return _obfuscated_js_cache
    with tempfile.NamedTemporaryFile(suffix=".js", delete=False) as min_f:
        min_path = min_f.name
    with tempfile.NamedTemporaryFile(suffix=".js", delete=False) as obf_f:
        obf_path = obf_f.name
    try:
        subprocess.run([
            "esbuild", js_path,
            "--bundle", "--minify",
            "--jsx=transform",
            "--target=es2020",
            "--format=iife",
            "--external:react", "--external:react-dom",
            "--loader:.js=jsx",
            f"--outfile={min_path}"
        ], check=True, capture_output=True)
        subprocess.run([
            "terser", min_path,
            "--compress", "passes=2",
            "--mangle", "toplevel,reserved=['React','ReactDOM']",
            "--output", obf_path
        ], check=True, capture_output=True)
        with open(obf_path, "r", encoding="utf-8") as f:
            _obfuscated_js_cache = f.read()
        _obfuscated_js_mtime = mtime
    finally:
        os.unlink(min_path)
        os.unlink(obf_path)
    return _obfuscated_js_cache

_minified_css_cache = None
_minified_css_mtime = 0

def _minify_css(css_path):
    global _minified_css_cache, _minified_css_mtime
    mtime = os.path.getmtime(css_path)
    if _minified_css_cache and _minified_css_mtime == mtime:
        return _minified_css_cache
    result = subprocess.run(
        ["cssnano"],
        input=open(css_path, "r", encoding="utf-8").read(),
        capture_output=True, text=True
    )
    if result.returncode == 0 and result.stdout.strip():
        _minified_css_cache = result.stdout
    else:
        with open(css_path, "r", encoding="utf-8") as f:
            _minified_css_cache = f.read()
    _minified_css_mtime = mtime
    return _minified_css_cache

def _read_file(name):
    with open(os.path.join(WEBAPP_DIR, name), "r", encoding="utf-8") as f:
        return f.read()

async def webapp_index(request: web.Request) -> web.Response:
    html = _read_file("index.html")
    css = _minify_css(os.path.join(WEBAPP_DIR, "styles.css"))
    tw = _read_file("tailwind-config.js")
    obf_js = _build_obfuscated_js().replace("</script>", "<\\/script>").replace("</Script>", "<\\/Script>").replace("</SCRIPT>", "<\\/SCRIPT>")
    react_js = _read_file("react.production.min.js")
    reactdom_js = _read_file("react-dom.production.min.js")
    tailwind_js = _read_file("tailwind.js")
    tg_js = _read_file("telegram-web-app.js")
    html = html.replace('<link rel="stylesheet" href="/static/styles.css?v=20260912a">', f"<style>{css}</style>")
    html = html.replace('<script src="https://telegram.org/js/telegram-web-app.js"></script>', f"<script>{tg_js}</script>")
    html = html.replace('<script src="/static/tailwind-config.js?v=20260527a"></script>', f"<script>{tw}</script>")
    html = html.replace('<script src="https://cdn.tailwindcss.com"></script>', f"<script>{tailwind_js}</script>")
    html = html.replace('<script crossorigin src="https://unpkg.com/react@18/umd/react.development.js"></script>', f"<script>{react_js}</script>")
    html = html.replace('<script crossorigin src="https://unpkg.com/react-dom@18/umd/react-dom.development.js"></script>', f"<script>{reactdom_js}</script>")
    html = html.replace('<script type="text/babel" src="/static/app.js?v=20260912a"></script>', f"<script>{obf_js}</script>")
    return web.Response(text=html, content_type="text/html", headers={"Cache-Control": "no-store, no-cache, must-revalidate, max-age=0", "Pragma": "no-cache", "Expires": "0"})

async def webapp_bootstrap(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался. Telegram не подтвердил сессию."}, status=401)

    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    acc = None
    tg_prof = None
    if is_account_uid(uid):
        acc = get_account_by_uid(uid)
    else:
        try:
            tid = int(uid)
            acc = get_account_by_telegram_id(tid)
            if not acc:
                tg_prof = get_telegram_profile(tid)
                if tg_prof:
                    user = {**user, "first_name": tg_prof.get("display_name") or user.get("first_name"), "photo_url": tg_prof.get("avatar_url") or user.get("photo_url")}
        except:
            acc = None
    profile_extra = {}
    if acc:
        profile_extra = {
            "is_account": True,
            "account_username": acc.get("username"),
            "display_name": acc.get("display_name"),
            "telegram_id": acc.get("telegram_id"),
            "profile_featured": acc.get("profile_featured", []),
        }
    elif tg_prof:
        profile_extra = {
            "is_account": False,
            "is_telegram_profile": True,
            "display_name": tg_prof.get("display_name"),
            "telegram_profile": tg_prof,
            "profile_featured": [],
        }
    else:
        profile_extra = {"is_account": False, "profile_featured": []}

    payload = {
        "ok": True,
        "profile": {
            "id": uid,
            "first_name": user.get("first_name") or "",
            "last_name": user.get("last_name") or "",
            "username": user.get("username") or "",
            "photo_url": user.get("photo_url") or "",
            **profile_extra,
        },
        "playlists": await build_playlist_payload(uid),
        "now_playing": now_playing_state.get(str(uid)),
        "authorized": has_access(uid) or is_known_to_bot(uid),
        "account": acc,
    }
    return web.json_response(payload)

async def webapp_search(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    query = (request.query.get("q") or "").strip()
    if len(query) < 2:
        return web.json_response({"ok": True, "query": query, "tracks": []})

    tracks = await asyncio.to_thread(sc_search, query)
    cover_tasks = [resolve_cover_art(t) for t in tracks[:10]]
    covers = await asyncio.gather(*cover_tasks, return_exceptions=True)
    for i, cover in enumerate(covers):
        if i < len(tracks) and isinstance(cover, str) and cover:
            tracks[i]["cover"] = cover
    payload = [serialize_search_track(track) for track in tracks]
    return web.json_response({"ok": True, "query": query, "tracks": payload})

async def webapp_recognize_track(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    try:
        reader = await request.multipart()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_MULTIPART", "message": "Не удалось прочитать загруженный файл."}, status=400)

    field = await reader.next()
    if not field or field.name != "file":
        return web.json_response({"ok": False, "error": "FILE_MISSING", "message": "Файл не был передан."}, status=400)

    filename = field.filename or "recognition-upload"
    ext = os.path.splitext(filename)[1] or ".bin"
    upload_path = os.path.join(TMP_DIR, f"{uuid.uuid4().hex}{ext}")
    size = 0

    try:
        with open(upload_path, "wb") as f:
            while True:
                chunk = await field.read_chunk()
                if not chunk:
                    break
                size += len(chunk)
                if size > 30 * 1024 * 1024:
                    raise ValueError("FILE_TOO_LARGE")
                f.write(chunk)

        recognized, query = await recognize_track_from_file(upload_path, filename)
        if not recognized or not query:
            return web.json_response({"ok": False, "error": "RECOGNITION_FAILED", "message": "Не удалось распознать трек. Попробуй файл подлиннее или чище."}, status=422)

        tracks = await asyncio.to_thread(sc_search, query)
        payload = [serialize_search_track(track) for track in tracks]
        return web.json_response({
            "ok": True,
            "query": query,
            "recognized": serialize_recognized_track(recognized),
            "tracks": payload,
        })
    except ValueError as e:
        if str(e) == "FILE_TOO_LARGE":
            return web.json_response({"ok": False, "error": "FILE_TOO_LARGE", "message": "Файл слишком большой. Лимит 30 МБ."}, status=400)
        raise
    except Exception as e:
        logging.error(f"WEBAPP TRACK RECOGNITION ERROR | USER {uid} | {filename} | {e}")
        return web.json_response({"ok": False, "error": "RECOGNITION_ERROR", "message": "Ошибка распознавания. Проверь формат файла и попробуй снова."}, status=500)
    finally:
        if os.path.exists(upload_path):
            os.remove(upload_path)

async def webapp_recommendations(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    force_refresh = (request.query.get("force") or "").strip().lower() in {"1", "true", "yes", "on"}
    recs = await get_recommendations_payload(uid, force_refresh=force_refresh)
    return web.json_response({"ok": True, "recommendations": recs})

async def webapp_statistics(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Р’С…РѕРґ РЅРµ СѓРґР°Р»СЃСЏ."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Р’С…РѕРґ РЅРµ СѓРґР°Р»СЃСЏ. Р‘РѕС‚ РЅРµ Р·РЅР°РµС‚ СЌС‚РѕРіРѕ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ."}, status=403)

    stats = load_user_stats(uid)
    artist_seconds = stats.get("artist_seconds") or {}
    artist_labels = stats.get("artist_labels") or {}
    top_artists = []
    for artist_key, seconds in sorted(artist_seconds.items(), key=lambda item: int(item[1] or 0), reverse=True)[:6]:
        artist_name = artist_labels.get(artist_key) or artist_key
        top_artists.append({
            "key": artist_key,
            "name": artist_name,
            "seconds": int(seconds or 0),
            "hours": round(int(seconds or 0) / 3600, 2),
        })

    avatars = await asyncio.gather(
        *[fetch_artist_avatar_url(item["name"]) for item in top_artists],
        return_exceptions=True
    )
    for item, avatar in zip(top_artists, avatars):
        if isinstance(avatar, str) and avatar:
            item["avatar_url"] = avatar
        else:
            item["avatar_url"] = ""

    total_active_seconds = int(stats.get("total_active_seconds") or 0)
    playlists = list_playlists(uid)
    track_count = sum(len(list_tracks(uid, pl)) for pl in playlists)

    return web.json_response({
        "ok": True,
        "statistics": {
            "total_active_seconds": total_active_seconds,
            "total_active_hours": round(total_active_seconds / 3600, 2),
            "total_active_days": round(total_active_seconds / 86400, 2),
            "playlist_count": len(playlists),
            "track_count": track_count,
            "top_artists": top_artists,
        },
    })

async def webapp_activity_ping(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Р’С…РѕРґ РЅРµ СѓРґР°Р»СЃСЏ."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Р’С…РѕРґ РЅРµ СѓРґР°Р»СЃСЏ. Р‘РѕС‚ РЅРµ Р·РЅР°РµС‚ СЌС‚РѕРіРѕ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ."}, status=403)

    touch_user_activity(uid)
    stats = load_user_stats(uid)
    return web.json_response({
        "ok": True,
        "total_active_seconds": int(stats.get("total_active_seconds") or 0),
    })

async def webapp_playlists(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    playlists = await build_playlist_payload(uid)
    for pl in playlists:
        tracks = pl.get("tracks") or []
        to_resolve = [(i, t) for i, t in enumerate(tracks[:8]) if not t.get("cover")]
        if to_resolve:
            covers = await asyncio.gather(
                *[resolve_cover_art({"title": t["title"], "artist": t["artist"]}) for _, t in to_resolve],
                return_exceptions=True
            )
            for (i, _), cover in zip(to_resolve, covers):
                if isinstance(cover, str) and cover:
                    tracks[i]["cover"] = cover

    return web.json_response({"ok": True, "playlists": playlists})

async def webapp_update_playlist_cover(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные обложки."}, status=400)

    playlist_name = (payload.get("playlist_name") or "").strip()
    cover_url = (payload.get("cover_url") or "").strip()
    if not playlist_name:
        return web.json_response({"ok": False, "error": "PLAYLIST_NAME_MISSING", "message": "Плейлист не указан."}, status=400)
    if playlist_name not in list_playlists(uid):
        return web.json_response({"ok": False, "error": "PLAYLIST_NOT_FOUND", "message": "Плейлист не найден."}, status=404)
    if cover_url and not cover_url.startswith(("http://", "https://")):
        return web.json_response({"ok": False, "error": "COVER_URL_INVALID", "message": "Нужна ссылка http:// или https://."}, status=400)

    meta = load_playlist_meta(uid, playlist_name)
    if cover_url:
        meta["cover_url"] = cover_url
    else:
        meta.pop("cover_url", None)
    save_playlist_meta(uid, playlist_name, meta)
    return web.json_response({"ok": True, "playlists": await build_playlist_payload(uid)})

async def webapp_create_playlist(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные плейлиста."}, status=400)

    ok, result = create_playlist(uid, payload.get("playlist_name") or "")
    if not ok:
        status = 409 if "существует" in result else 400
        return web.json_response({"ok": False, "error": "PLAYLIST_CREATE_FAILED", "message": result}, status=status)

    return web.json_response({"ok": True, "playlist_name": result, "playlists": await build_playlist_payload(uid)})

async def webapp_play_track(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные трека."}, status=400)

    track = payload.get("track") or {}
    try:
        media = await resolve_webapp_stream_track(uid, track)
    except ValueError as e:
        return web.json_response({"ok": False, "error": "TRACK_URL_MISSING", "message": str(e)}, status=400)
    except RuntimeError as e:
        if str(e) == "TRACK_PREPARING":
            download_key, job = ensure_webapp_download_job(track)
            return web.json_response({
                "ok": True,
                "status": job.get("status") or "queued",
                "download_id": download_key,
                "progress": round(float(job.get("progress") or 0.0), 1),
            })
        return web.json_response({"ok": False, "error": "STREAM_PREPARE_FAILED", "message": str(e)}, status=500)
    except Exception as e:
        logging.error(f"WEBAPP STREAM ERROR | USER_ID: {uid} | {e}")
        return web.json_response({"ok": False, "error": "STREAM_PREPARE_FAILED", "message": "Не удалось подготовить трек для web-плеера."}, status=500)

    logging.info(f"WEBAPP | USER {uid} | STREAM_TRACK | {media['artist']} - {media['title']}")
    record_artist_listen(uid, media.get("artist") or "", media.get("duration_seconds"))
    return web.json_response({"ok": True, "status": "ready", "track": media})

async def webapp_download_status(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    download_key = (request.query.get("id") or "").strip()
    if not download_key:
        return web.json_response({"ok": False, "error": "DOWNLOAD_ID_MISSING", "message": "Не указан идентификатор загрузки."}, status=400)

    job = webapp_download_jobs.get(download_key)
    if not job:
        return web.json_response({"ok": False, "error": "DOWNLOAD_NOT_FOUND", "message": "Загрузка не найдена."}, status=404)

    status = job.get("status") or "queued"
    progress = round(float(job.get("progress") or 0.0), 1)
    if status == "ready":
        path = job.get("path") or ""
        if not path or not os.path.exists(path):
            return web.json_response({"ok": True, "status": "error", "message": "Файл трека не найден после загрузки.", "progress": progress})
        media = build_webapp_ready_media(
            uid,
            {
                "title": job.get("title") or "Unknown title",
                "artist": job.get("artist") or "Unknown artist",
                "src": job.get("src") or "SC",
            },
            path,
            job.get("cover_url"),
        )
        return web.json_response({"ok": True, "status": "ready", "progress": 100.0, "track": media})

    if status == "error":
        return web.json_response({"ok": True, "status": "error", "progress": progress, "message": job.get("error") or "Не удалось подготовить трек."})

    return web.json_response({"ok": True, "status": status, "progress": progress})

async def webapp_login_page(request: web.Request) -> web.Response:
    """Login page with Telegram and account login."""
    bot_username = "MusicFounderBotbot"
    html = f"""<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        * {{ margin: 0; padding: 0; box-sizing: border-box; }}
        body {{
            display: flex; justify-content: center; align-items: center;
            min-height: 100vh;
            background: linear-gradient(135deg, #0a111b 0%, #111827 50%, #0a111b 100%);
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            color: #e2e8f0;
        }}
        .card {{
            text-align: center; padding: 32px 28px;
            background: rgba(255,255,255,0.05);
            border: 1px solid rgba(255,255,255,0.1);
            border-radius: 24px;
            backdrop-filter: blur(20px);
            max-width: 380px; width: 92%;
        }}
        .logo {{ font-size: 44px; margin-bottom: 10px; }}
        h1 {{ font-size: 26px; font-weight: 700; margin-bottom: 6px; }}
        p {{ font-size: 13px; color: #94a3b8; margin-bottom: 18px; line-height: 1.4; }}
        #tg-widget {{ display: flex; justify-content: center; margin-bottom: 6px; }}
        .divider {{ display:flex; align-items:center; gap:12px; margin:18px 0; }}
        .divider::before, .divider::after {{ content:""; flex:1; height:1px; background: rgba(255,255,255,0.1); }}
        .divider span {{ font-size:11px; letter-spacing:0.15em; color:#64748b; text-transform:uppercase; }}
        .step2 {{ display: none; }}
        input[type="text"], input[type="password"] {{
            width: 100%; padding: 12px 14px; margin-bottom: 12px;
            background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.15);
            border-radius: 12px; color: #e2e8f0; font-size: 14px; outline: none;
            box-sizing: border-box;
        }}
        input[type="text"]:focus, input[type="password"]:focus {{ border-color: rgba(16,185,129,0.5); }}
        input::placeholder {{ color: #64748b; }}
        button {{
            width: 100%; padding: 12px; background: #10b981; color: #0f172a;
            border: none; border-radius: 12px; font-size: 14px; font-weight: 600;
            cursor: pointer; transition: opacity 0.2s;
        }}
        button:hover {{ opacity: 0.85; }}
        button:disabled {{ opacity: 0.5; cursor: default; }}
        .btn-secondary {{ background: rgba(255,255,255,0.08); color:#e2e8f0; border:1px solid rgba(255,255,255,0.12); }}
        .user-name {{ color: #10b981; font-weight: 600; }}
        a.link {{ color:#10b981; text-decoration:none; font-weight:600; }}
        a.link:hover {{ text-decoration:underline; }}
    </style>
</head>
<body>
    <div class="card">
        <div id="step1">
            <div class="logo">🎵</div>
            <h1>MusicFind</h1>
            <p>Войди через Telegram или аккаунт</p>
            <div id="tg-widget">
                <script
                    async
                    src="https://telegram.org/js/telegram-widget.js?22"
                    data-telegram-login="{bot_username}"
                    data-size="large"
                    data-onauth="onTelegramAuth(user)"
                    data-request-access="write"
                ></script>
            </div>
            <div class="divider"><span>или</span></div>
            <button class="btn-secondary" onclick="showAccountLogin()" style="margin-bottom:12px;">Войти по логину/паролю</button>
            <p style="font-size:13px; margin-top:8px;">Нет аккаунта? <a href="/createacc" class="link">Создайте</a></p>
            <div id="account-login" style="display:none; margin-top:18px; text-align:left;">
                <input type="text" id="acc-username" placeholder="Логин" autocomplete="username">
                <input type="password" id="acc-password" placeholder="Пароль" autocomplete="current-password">
                <button id="acc-login-btn" onclick="loginWithAccount()">Войти</button>
                <p id="acc-error" style="color:#f43f5e;margin-top:10px;display:none; font-size:13px; text-align:center;"></p>
            </div>
        </div>
        <div id="step2" class="step2">
            <div class="logo">🔐</div>
            <h1>Привет, <span id="user-name" class="user-name"></span>!</h1>
            <p>Введи пароль для завершения авторизации</p>
            <input type="password" id="password" placeholder="Пароль доступа" autocomplete="off">
            <button id="submit-btn" onclick="submitAuth()">Войти</button>
            <p id="error-msg" style="color:#f43f5e;margin-top:12px;display:none;"></p>
        </div>
    </div>
    <script>
        function showAccountLogin() {{
            var el = document.getElementById('account-login');
            el.style.display = el.style.display === 'none' ? 'block' : 'none';
            if (el.style.display === 'block') document.getElementById('acc-username').focus();
        }}
        function accShowError(msg) {{
            var el = document.getElementById('acc-error');
            el.textContent = msg;
            el.style.display = 'block';
        }}
        function loginWithAccount() {{
            var u = document.getElementById('acc-username').value.trim();
            var p = document.getElementById('acc-password').value;
            if (!u || !p) {{ accShowError('Введите логин и пароль'); return; }}
            var btn = document.getElementById('acc-login-btn');
            btn.disabled = true; btn.textContent = 'Вход...';
            fetch('/api/account/login', {{
                method: 'POST',
                headers: {{'Content-Type': 'application/json'}},
                body: JSON.stringify({{username: u, password: p}})
            }})
            .then(function(r){{return r.json();}})
            .then(function(data){{
                if (data.ok) {{
                    document.cookie = "session_token=" + data.session_token + ";path=/;max-age=2592000;SameSite=Lax";
                    document.querySelector('.card').innerHTML = '<div class="logo">✅</div><h1>Готово!</h1><p>Вход выполнен. Переход...</p>';
                    setTimeout(function(){{window.location.href='/'}} , 1000);
                }} else {{
                    accShowError(data.message || 'Ошибка входа');
                    btn.disabled = false; btn.textContent = 'Войти по логину/паролю';
                }}
            }})
            .catch(function(e){{ accShowError('Ошибка: '+e.message); btn.disabled=false; btn.textContent='Войти по логину/паролю'; }});
        }}
        document.getElementById('acc-password')?.addEventListener('keydown', function(e){{ if(e.key==='Enter') loginWithAccount(); }});
        var tgUser = null;
        function onTelegramAuth(user) {{
            tgUser = user;
            if (window.Android) {{
                window.Android.onAuth(
                    String(user.id),
                    user.first_name || '',
                    user.last_name || '',
                    user.username || '',
                    user.photo_url || '',
                    String(user.auth_date || Math.floor(Date.now() / 1000)),
                    user.hash || ''
                );
            }} else {{
                document.getElementById('step1').style.display = 'none';
                document.getElementById('step2').style.display = 'block';
                document.getElementById('user-name').textContent = user.first_name || 'друг';
                document.getElementById('password').focus();
            }}
        }}
        document.getElementById('password').addEventListener('keydown', function(e) {{
            if (e.key === 'Enter') submitAuth();
        }});
        function submitAuth() {{
            var pwd = document.getElementById('password').value;
            if (!pwd) {{
                showError('Введи пароль');
                return;
            }}
            var btn = document.getElementById('submit-btn');
            btn.disabled = true;
            btn.textContent = 'Проверка...';
            var payload = Object.assign({{}}, tgUser, {{password: pwd}});
            fetch('/api/android-auth', {{
                method: 'POST',
                headers: {{'Content-Type': 'application/json'}},
                body: JSON.stringify(payload)
            }})
            .then(function(r) {{ return r.json(); }})
            .then(function(data) {{
                if (data.ok) {{
                    document.cookie = "session_token=" + data.session_token + ";path=/;max-age=2592000;SameSite=Lax";
                    document.querySelector('.card').innerHTML =
                        '<div class="logo">✅</div>' +
                        '<h1>Готово!</h1>' +
                        '<p>Авторизация успешна. Переход на главную...</p>';
                    setTimeout(function() {{ window.location.href = '/'; }}, 1500);
                }} else if (data.error === 'AUTH_UNKNOWN_USER') {{
                    showError('Бот не знает тебя. Напиши @MusicFounderBotbot /start');
                    btn.disabled = false;
                    btn.textContent = 'Войти';
                }} else if (data.error === 'WRONG_PASSWORD') {{
                    showError('Неверный пароль');
                    btn.disabled = false;
                    btn.textContent = 'Войти';
                }} else {{
                    showError(data.message || 'Ошибка авторизации');
                    btn.disabled = false;
                    btn.textContent = 'Войти';
                }}
            }})
            .catch(function(e) {{
                showError('Ошибка сети: ' + e.message);
                btn.disabled = false;
                btn.textContent = 'Войти';
            }});
        }}
        function showError(msg) {{
            var el = document.getElementById('error-msg');
            el.textContent = msg;
            el.style.display = 'block';
        }}
    </script>
</body>
</html>"""
    return web.Response(text=html, content_type="text/html")

async def webapp_createacc_page(request: web.Request) -> web.Response:
    html = f"""<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        * {{ margin:0; padding:0; box-sizing:border-box; }}
        body {{ display:flex; justify-content:center; align-items:center; min-height:100vh; background: linear-gradient(135deg, #0a111b 0%, #111827 50%, #0a111b 100%); font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif; color:#e2e8f0; }}
        .card {{ text-align:center; padding:32px 28px; background: rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1); border-radius:24px; backdrop-filter:blur(20px); max-width:380px; width:92%; }}
        .logo {{ font-size:44px; margin-bottom:10px; }}
        h1 {{ font-size:26px; font-weight:700; margin-bottom:6px; }}
        p {{ font-size:13px; color:#94a3b8; margin-bottom:18px; line-height:1.4; }}
        .step {{ display:none; }} .step.active {{ display:block; }}
        input {{ width:100%; padding:12px 14px; margin-bottom:12px; background: rgba(255,255,255,0.08); border:1px solid rgba(255,255,255,0.15); border-radius:12px; color:#e2e8f0; font-size:14px; outline:none; box-sizing:border-box; }}
        input:focus {{ border-color: rgba(16,185,129,0.5); }} input::placeholder {{ color:#64748b; }}
        button {{ width:100%; padding:12px; background:#10b981; color:#0f172a; border:none; border-radius:12px; font-size:14px; font-weight:600; cursor:pointer; }}
        button:hover {{ opacity:0.85; }} button:disabled {{ opacity:0.5; }}
        .progress {{ display:flex; gap:6px; margin-bottom:16px; }} .progress div {{ height:4px; flex:1; border-radius:99px; background:rgba(255,255,255,0.15); }} .progress div.active {{ background:#fff; }}
        a.link {{ color:#10b981; text-decoration:none; font-weight:600; }} a.link:hover {{ text-decoration:underline; }}
    </style>
</head>
<body>
    <div class="card">
        <div class="logo">🎵</div>
        <h1>Создать аккаунт</h1>
        <p id="desc">Шаг 1 — придумайте логин и пароль</p>
        <div class="progress"><div id="p1" class="active"></div><div id="p2"></div></div>
        <div id="step1" class="step active" style="text-align:left;">
            <input type="text" id="reg-username" placeholder="Логин (a-z, 0-9, _)" autocomplete="username">
            <input type="text" id="reg-display" placeholder="Никнейм (отображаемое имя)">
            <input type="password" id="reg-password" placeholder="Пароль (мин 4)" autocomplete="new-password">
            <button onclick="nextStep()">Далее</button>
            <p id="reg-error1" style="color:#f43f5e; margin-top:10px; display:none; font-size:13px; text-align:center;"></p>
            <p style="margin-top:12px; font-size:13px; text-align:center;">Есть аккаунт? <a href="/login" class="link">Войти</a></p>
        </div>
        <div id="step2" class="step" style="text-align:left;">
            <div style="background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1); border-radius:12px; padding:12px 14px; margin-bottom:12px;">
                <div style="font-size:12px; color:#94a3b8;">Аккаунт</div>
                <div id="acc-preview" style="font-size:14px; font-weight:600; color:#fff;"></div>
            </div>
            <p style="font-size:13px; color:#94a3b8; margin-bottom:10px; text-align:center;">Шаг 2 — введите пароль бота</p>
            <input type="password" id="reg-botpass" placeholder="Пароль бота" autocomplete="off">
            <button id="reg-create-btn" onclick="createAccount()">Создать и войти</button>
            <button onclick="prevStep()" style="background:rgba(255,255,255,0.08); color:#e2e8f0; border:1px solid rgba(255,255,255,0.12); margin-top:8px;">← Назад</button>
            <p id="reg-error2" style="color:#f43f5e; margin-top:10px; display:none; font-size:13px; text-align:center;"></p>
        </div>
    </div>
    <script>
        var regData = {{}};
        function nextStep() {{
            var u = document.getElementById('reg-username').value.trim();
            var d = document.getElementById('reg-display').value.trim();
            var p = document.getElementById('reg-password').value;
            if (!u || u.length < 3) {{ showError1('Логин от 3 символов'); return; }}
            if (!p || p.length < 4) {{ showError1('Пароль от 4 символов'); return; }}
            regData = {{username: u, display_name: d || u, password: p}};
            document.getElementById('acc-preview').textContent = u + (d ? ' • ' + d : '');
            document.getElementById('step1').classList.remove('active');
            document.getElementById('step2').classList.add('active');
            document.getElementById('p1').classList.remove('active');
            document.getElementById('p2').classList.add('active');
            document.getElementById('desc').textContent = 'Шаг 2 — пароль бота';
            document.getElementById('reg-botpass').focus();
        }}
        function prevStep() {{
            document.getElementById('step2').classList.remove('active');
            document.getElementById('step1').classList.add('active');
            document.getElementById('p2').classList.remove('active');
            document.getElementById('p1').classList.add('active');
            document.getElementById('desc').textContent = 'Шаг 1 — придумайте логин и пароль';
        }}
        function showError1(msg) {{ var el=document.getElementById('reg-error1'); el.textContent=msg; el.style.display='block'; }}
        function showError2(msg) {{ var el=document.getElementById('reg-error2'); el.textContent=msg; el.style.display='block'; }}
        function createAccount() {{
            var bot = document.getElementById('reg-botpass').value;
            if (!bot) {{ showError2('Введите пароль бота'); return; }}
            var btn=document.getElementById('reg-create-btn'); btn.disabled=true; btn.textContent='Создание...';
            fetch('/api/account/register', {{ method:'POST', headers:{{'Content-Type':'application/json'}}, body: JSON.stringify({{username: regData.username, password: regData.password, bot_password: bot, display_name: regData.display_name}}) }})
            .then(function(r){{return r.json();}})
            .then(function(data){{
                if (data.ok) {{
                    document.cookie = "session_token=" + data.session_token + ";path=/;max-age=2592000;SameSite=Lax";
                    document.querySelector('.card').innerHTML = '<div class="logo">✅</div><h1>Готово!</h1><p>Аккаунт создан. Вход...</p>';
                    setTimeout(function(){{window.location.href='/'}} , 1000);
                }} else {{
                    showError2(data.message || 'Ошибка');
                    btn.disabled=false; btn.textContent='Создать и войти';
                }}
            }})
            .catch(function(e){{ showError2('Ошибка: '+e.message); btn.disabled=false; btn.textContent='Создать и войти'; }});
        }}
    </script>
</body>
</html>"""
    return web.Response(text=html, content_type="text/html")

async def webapp_cancel_download(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные."}, status=400)

    download_key = (payload.get("download_id") or "").strip()
    if not download_key:
        return web.json_response({"ok": False, "error": "DOWNLOAD_ID_MISSING", "message": "Не указан идентификатор загрузки."}, status=400)

    job = webapp_download_jobs.get(download_key)
    if not job:
        return web.json_response({"ok": True, "status": "not_found"})

    if job.get("status") in ("ready", "error", "cancelled"):
        return web.json_response({"ok": True, "status": job["status"]})

    job["cancelled"] = True
    pid = job.get("pid")
    if pid:
        try:
            import signal
            os.kill(pid, signal.SIGTERM)
        except OSError:
            pass

    logging.info(f"WEBAPP | USER {uid} | CANCEL_DOWNLOAD | {download_key}")
    return web.json_response({"ok": True, "status": "cancelling"})

_download_tokens: dict[str, dict] = {}

async def webapp_prepare_download(request: web.Request) -> web.Response:
    """Generate a one-time download URL for a track. No auth needed for the download itself."""
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER"}, status=403)

    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON"}, status=400)

    track = payload.get("track") or {}
    playlist_name = (payload.get("playlist_name") or "").strip()
    file_name = (track.get("file_name") or "").strip()

    track_path = None
    if playlist_name and file_name:
        candidate = os.path.join(get_playlist_dir(uid, playlist_name), file_name)
        if os.path.exists(candidate):
            track_path = candidate

    if not track_path:
        for pl_name in list_playlists(uid):
            found = find_playlist_track_path(uid, pl_name, track)
            if found and os.path.exists(found):
                track_path = found
                break

    if not track_path:
        media_id = (track.get("id") or "").strip()
        media = webapp_media_cache.get(media_id) if media_id else None
        if media and media.get("uid") == uid:
            candidate = media.get("path") or ""
            if candidate and os.path.exists(candidate):
                track_path = candidate

    if not track_path:
        return web.json_response({"ok": False, "error": "TRACK_NOT_FOUND", "message": "Трек не найден."}, status=404)

    import secrets
    dl_token = secrets.token_urlsafe(32)
    display_name = os.path.basename(track_path)
    _download_tokens[dl_token] = {
        "path": track_path,
        "filename": display_name,
        "expires": datetime.utcnow().timestamp() + 3600,
    }

    dl_url = f"/api/dl/{dl_token}"
    return web.json_response({"ok": True, "download_url": dl_url, "filename": display_name})

def _parse_http_range(range_header: str | None, file_size: int) -> tuple[int, int] | None:
    if not range_header:
        return None
    match = re.match(r"^bytes=(\d*)-(\d*)$", range_header.strip())
    if not match:
        return None
    start_raw, end_raw = match.groups()
    if not start_raw and not end_raw:
        return None
    try:
        if not start_raw:
            suffix_len = int(end_raw)
            if suffix_len <= 0:
                return None
            start = max(0, file_size - suffix_len)
            end = file_size - 1
        else:
            start = int(start_raw)
            end = int(end_raw) if end_raw else file_size - 1
            if start < 0 or start >= file_size:
                return None
            end = min(end, file_size - 1)
            if end < start:
                return None
    except ValueError:
        return None
    return start, end

async def _stream_file_response(request: web.Request, path: str, *, content_type: str, content_disposition: str | None = None) -> web.StreamResponse:
    file_size = os.path.getsize(path)
    byte_range = _parse_http_range(request.headers.get("Range"), file_size)
    start = 0
    end = file_size - 1
    status = 200
    headers = {
        "Content-Type": content_type,
        "Accept-Ranges": "bytes",
        "Cache-Control": "no-store, no-cache, must-revalidate",
        "Access-Control-Allow-Origin": "*",
        "Cross-Origin-Resource-Policy": "cross-origin",
    }
    if content_disposition:
        headers["Content-Disposition"] = content_disposition
    if byte_range:
        start, end = byte_range
        status = 206
        headers["Content-Range"] = f"bytes {start}-{end}/{file_size}"
        headers["Content-Length"] = str(end - start + 1)
    else:
        headers["Content-Length"] = str(file_size)
    response = web.StreamResponse(status=status, headers=headers)
    await response.prepare(request)
    with open(path, "rb") as f:
        f.seek(start)
        remaining = end - start + 1
        while remaining > 0:
            chunk = f.read(min(65536, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
            await response.write(chunk)
    await response.write_eof()
    return response

async def webapp_serve_download(request: web.Request) -> web.StreamResponse:
    """Serve a file via one-time download token. No auth required."""
    dl_token = request.match_info.get("token", "")
    entry = _download_tokens.pop(dl_token, None)
    if not entry:
        raise web.HTTPNotFound(text="Link expired or invalid")
    if datetime.utcnow().timestamp() > entry["expires"]:
        raise web.HTTPGone(text="Link expired")
    path = entry["path"]
    if not path or not os.path.exists(path):
        raise web.HTTPNotFound(text="File not found")
    return await _stream_file_response(
        request,
        path,
        content_type="audio/mpeg",
        content_disposition=f'attachment; filename="{entry["filename"]}"',
    )

async def webapp_download_playlist_track(request: web.Request) -> web.StreamResponse:
    """Serve a playlist track file directly as a download."""
    uid, user = extract_android_user(request)
    if not uid or not user:
        raise web.HTTPUnauthorized(text="Auth required")
    if not is_known_to_bot(uid):
        raise web.HTTPForbidden(text="Unknown user")

    playlist_name = (request.query.get("playlist") or "").strip()
    file_name = (request.query.get("file") or "").strip()
    if not playlist_name or not file_name:
        raise web.HTTPBadRequest(text="Missing playlist or file")

    track_path = os.path.join(get_playlist_dir(uid, playlist_name), file_name)
    if not os.path.exists(track_path):
        raise web.HTTPNotFound(text="File not found")

    file_size = os.path.getsize(track_path)
    response = web.StreamResponse(
        status=200,
        headers={
            "Content-Type": "audio/mpeg",
            "Content-Length": str(file_size),
            "Content-Disposition": f'attachment; filename="{file_name}"',
            "Cache-Control": "no-store, no-cache, must-revalidate",
        },
    )
    await response.prepare(request)
    with open(track_path, "rb") as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            await response.write(chunk)
    await response.write_eof()
    return response

async def webapp_play_playlist_track(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные трека."}, status=400)

    track = payload.get("track") or {}
    playlist_name = (payload.get("playlist_name") or "").strip()
    file_name = (track.get("file_name") or "").strip()
    if not playlist_name or not file_name:
        return web.json_response({"ok": False, "error": "PLAYLIST_TRACK_INVALID", "message": "Не удалось определить трек плейлиста."}, status=400)

    track_path = os.path.join(get_playlist_dir(uid, playlist_name), file_name)
    if not os.path.exists(track_path):
        return web.json_response({"ok": False, "error": "PLAYLIST_TRACK_MISSING", "message": "Файл трека не найден в плейлисте."}, status=404)

    display_name = strip_ext(file_name)
    if " - " in display_name:
        artist, title = display_name.split(" - ", 1)
    else:
        artist, title = "Unknown artist", display_name

    cover_url = await resolve_cover_art({"title": title, "artist": artist})
    update_now_playing(uid, {"title": title, "artist": artist, "src": f"playlist:{playlist_name}"}, cover_url)
    media = register_webapp_media(uid, track_path, title, artist, f"playlist:{playlist_name}", cover_url)
    record_artist_listen(uid, artist, media.get("duration_seconds"))
    logging.info(f"WEBAPP | USER {uid} | STREAM_PLAYLIST_TRACK | {playlist_name} | {artist} - {title}")
    return web.json_response({"ok": True, "status": "ready", "track": media})

async def webapp_remove_playlist_track(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные трека."}, status=400)

    playlist_name = (payload.get("playlist_name") or "").strip()
    track = payload.get("track") or {}
    file_name = (track.get("file_name") or "").strip()
    if not playlist_name or not file_name:
        return web.json_response({"ok": False, "error": "PLAYLIST_TRACK_INVALID", "message": "Не удалось определить трек плейлиста."}, status=400)
    if playlist_name not in list_playlists(uid):
        return web.json_response({"ok": False, "error": "PLAYLIST_NOT_FOUND", "message": "Плейлист не найден."}, status=404)

    removed = remove_track_from_playlist(uid, playlist_name, track)
    if not removed:
        return web.json_response({"ok": False, "error": "PLAYLIST_TRACK_MISSING", "message": "Файл трека не найден в плейлисте."}, status=404)

    return web.json_response({"ok": True, "playlists": await build_playlist_payload(uid)})

async def webapp_remove_playlist(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные плейлиста."}, status=400)

    playlist_name = (payload.get("playlist_name") or "").strip()
    if not playlist_name:
        return web.json_response({"ok": False, "error": "PLAYLIST_NAME_MISSING", "message": "Плейлист не указан."}, status=400)
    if playlist_name == LIKED_PLAYLIST_NAME:
        return web.json_response({"ok": False, "error": "PLAYLIST_DELETE_FORBIDDEN", "message": "Плейлист «Мне нравится» нельзя удалить."}, status=400)

    removed = remove_playlist(uid, playlist_name)
    if not removed:
        return web.json_response({"ok": False, "error": "PLAYLIST_NOT_FOUND", "message": "Плейлист не найден."}, status=404)

    return web.json_response({"ok": True, "playlists": await build_playlist_payload(uid)})

async def webapp_add_track_to_playlist(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные трека."}, status=400)

    playlist_name = (payload.get("playlist_name") or "").strip()
    track = payload.get("track") or {}
    if not (track.get("title") or "").strip():
        return web.json_response({"ok": False, "error": "TRACK_INVALID", "message": "Не удалось определить трек."}, status=400)

    try:
        result = await add_track_to_user_playlist(uid, playlist_name, track)
    except ValueError:
        return web.json_response({"ok": False, "error": "PLAYLIST_NAME_MISSING", "message": "Плейлист не указан."}, status=400)
    except FileNotFoundError:
        return web.json_response({"ok": False, "error": "PLAYLIST_NOT_FOUND", "message": "Плейлист не найден."}, status=404)
    except Exception as e:
        logging.error(f"WEBAPP ADD TRACK ERROR | USER_ID: {uid} | {e}")
        return web.json_response({"ok": False, "error": "PLAYLIST_ADD_FAILED", "message": "Не удалось добавить трек в плейлист."}, status=500)

    return web.json_response({"ok": True, "playlist_name": result, "playlists": await build_playlist_payload(uid)})

async def webapp_toggle_favorite(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные трека."}, status=400)

    track = payload.get("track") or {}
    if not (track.get("title") or "").strip():
        return web.json_response({"ok": False, "error": "TRACK_INVALID", "message": "Не удалось определить трек."}, status=400)

    try:
        favorite = await toggle_favorite_track(uid, track)
    except Exception as e:
        logging.error(f"WEBAPP FAVORITE ERROR | USER_ID: {uid} | {e}")
        return web.json_response({"ok": False, "error": "FAVORITE_TOGGLE_FAILED", "message": "Не удалось обновить избранное."}, status=500)

    return web.json_response({"ok": True, "favorite": favorite, "playlists": await build_playlist_payload(uid)})

async def webapp_release_media(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Вход не удался."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Вход не удался. Бот не знает этого пользователя."}, status=403)

    try:
        payload = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "BAD_JSON", "message": "Некорректные данные трека."}, status=400)

    media_id = (payload.get("media_id") or "").strip()
    if not media_id:
        return web.json_response({"ok": False, "error": "MEDIA_ID_MISSING", "message": "Не указан media_id."}, status=400)

    released = release_webapp_media_for_user(uid, media_id)
    return web.json_response({"ok": True, "released": released})

async def webapp_media_recognition(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID", "message": "Р’С…РѕРґ РЅРµ СѓРґР°Р»СЃСЏ."}, status=401)
    if not is_known_to_bot(uid):
        return web.json_response({"ok": False, "error": "AUTH_UNKNOWN_USER", "message": "Р’С…РѕРґ РЅРµ СѓРґР°Р»СЃСЏ. Р‘РѕС‚ РЅРµ Р·РЅР°РµС‚ СЌС‚РѕРіРѕ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ."}, status=403)

    media_id = request.match_info.get("media_id", "").strip()
    if not media_id:
        return web.json_response({"ok": False, "error": "MEDIA_ID_MISSING", "message": "Не указан media_id."}, status=400)

    media = webapp_media_cache.get(media_id)
    if not media or media.get("uid") != uid:
        return web.json_response({"ok": False, "error": "MEDIA_NOT_FOUND", "message": "Медиафайл не найден."}, status=404)

    ensure_webapp_media_recognition(media_id)
    return web.json_response({"ok": True, **build_webapp_media_recognition_payload(media)})

async def webapp_media(request: web.Request) -> web.StreamResponse:
    media_id = request.match_info.get("media_id", "")
    token = (request.query.get("token") or "").strip()
    media = webapp_media_cache.get(media_id)
    if not media:
        raise web.HTTPNotFound(text="Media not found")
    if token != media.get("token"):
        raise web.HTTPUnauthorized(text="Invalid token")
    path = media.get("path") or ""
    if not path or not os.path.exists(path):
        raise web.HTTPNotFound(text="File not found")
    return await _stream_file_response(
        request,
        path,
        content_type="audio/mpeg",
        content_disposition=f'inline; filename="{os.path.basename(path)}"',
    )

lyrics_cache = {}

async def webapp_lyrics(request: web.Request) -> web.Response:
    """Fetch lyrics for a track. Returns synced (LRC) or plain text."""
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)

    title = (request.query.get("title") or "").strip()
    if not title:
        return web.json_response({"ok": False, "message": "Название трека не указано."}, status=400)

    artist = (request.query.get("artist") or "").strip()
    cache_key = f"{artist.lower()}::{title.lower()}".strip()
    cached = lyrics_cache.get(cache_key)
    if cached and (datetime.now().timestamp() - cached.get("ts", 0)) < 3600:
        return web.json_response({"ok": True, **cached["data"]})

    result = {"synced": None, "plain": None, "source": None}

    try:
        async with ClientSession() as session:
            clean_title = clean_search_query(title) or title

            async def try_lrclib_get(track_name: str, artist_name: str = "") -> bool:
                params = {"track_name": track_name}
                if artist_name:
                    params["artist_name"] = artist_name
                lrclib_url = "https://lrclib.net/api/get?" + "&".join(f"{k}={quote_plus(v)}" for k, v in params.items())
                try:
                    async with session.get(lrclib_url, timeout=ClientTimeout(total=8), headers={"User-Agent": "MusicFind/1.0"}) as resp:
                        if resp.status != 200:
                            return False
                        data = await resp.json()
                        synced_lyrics = data.get("syncedLyrics")
                        plain_lyrics = data.get("plainLyrics")
                        if synced_lyrics:
                            lines = []
                            for line in synced_lyrics.split("\n"):
                                match = re.match(r'\[(\d{2}):(\d{2})\.(\d{2,3})\]\s*(.*)', line)
                                if match:
                                    mins = int(match.group(1))
                                    secs = int(match.group(2))
                                    ms_str = match.group(3)
                                    ms = int(ms_str) if len(ms_str) == 3 else int(ms_str) * 10
                                    time_sec = mins * 60 + secs + ms / 1000.0
                                    text = match.group(4).strip()
                                    lines.append({"time": round(time_sec, 3), "text": text})
                            if lines:
                                result["synced"] = lines
                                result["source"] = "lrclib"
                                return True
                        if plain_lyrics and not result["synced"]:
                            result["plain"] = plain_lyrics
                            result["source"] = "lrclib"
                            return True
                except Exception as e:
                    logging.debug(f"LRCLIB lookup error: {e}")
                return False

            for track_name in (title, clean_title):
                if result["synced"] or result["plain"]:
                    break
                if artist and await try_lrclib_get(track_name, artist):
                    break
            for track_name in (title, clean_title):
                if result["synced"] or result["plain"]:
                    break
                if await try_lrclib_get(track_name):
                    break

            if not result["synced"] and not result["plain"]:
                search_specs = lrclib_search_specs(artist, title)

                def score_lrclib_item(item: dict) -> int:
                    item_title = clean_search_query(item.get("trackName") or "")
                    item_artist = clean_search_query(item.get("artistName") or "")
                    raw_title_norm = normalize_track_text(title)
                    clean_title_norm = normalize_track_text(clean_title)
                    item_title_norm = normalize_track_text(item_title)
                    artist_norm = normalize_track_text(artist)

                    score = 0
                    if raw_title_norm and item_title_norm == raw_title_norm:
                        score += 8
                    if clean_title_norm and item_title_norm == clean_title_norm:
                        score += 7
                    if raw_title_norm and (raw_title_norm in item_title_norm or item_title_norm in raw_title_norm):
                        score += 5
                    if clean_title_norm and (clean_title_norm in item_title_norm or item_title_norm in clean_title_norm):
                        score += 4
                    if artist_norm and artist_norm == normalize_track_text(item_artist):
                        score += 3
                    if artist_norm and artist_norm in normalize_track_text(item_artist):
                        score += 2
                    return score

                for params in search_specs:
                    if result["synced"] or result["plain"]:
                        break
                    search_url = "https://lrclib.net/api/search?" + "&".join(
                        f"{k}={quote_plus(v)}" for k, v in params.items()
                    )
                    try:
                        async with session.get(search_url, timeout=ClientTimeout(total=8), headers={"User-Agent": "MusicFind/1.0"}) as resp:
                            if resp.status == 200:
                                items = await resp.json()
                                if isinstance(items, list) and items:
                                    ranked_items = sorted(items, key=score_lrclib_item, reverse=True)
                                    best = next(
                                        (
                                            item
                                            for item in ranked_items
                                            if item.get("syncedLyrics") or item.get("plainLyrics")
                                        ),
                                        None,
                                    )
                                    if best:
                                        synced_lyrics = best.get("syncedLyrics")
                                        plain_lyrics = best.get("plainLyrics")
                                        if synced_lyrics:
                                            lines = []
                                            for line in synced_lyrics.split("\n"):
                                                match = re.match(r'\[(\d{2}):(\d{2})\.(\d{2,3})\]\s*(.*)', line)
                                                if match:
                                                    mins = int(match.group(1))
                                                    secs = int(match.group(2))
                                                    ms_str = match.group(3)
                                                    ms = int(ms_str) if len(ms_str) == 3 else int(ms_str) * 10
                                                    time_sec = mins * 60 + secs + ms / 1000.0
                                                    text = match.group(4).strip()
                                                    lines.append({"time": round(time_sec, 3), "text": text})
                                            if lines:
                                                result["synced"] = lines
                                                result["source"] = "lrclib-search"
                                        if plain_lyrics and not result["synced"]:
                                            result["plain"] = plain_lyrics
                                            result["source"] = "lrclib-search"
                    except Exception as e:
                        logging.debug(f"LRCLIB search error: {e}")
    except Exception as e:
        logging.error(f"Lyrics fetch error: {e}")

    if not result["synced"] and not result["plain"]:
        return web.json_response({"ok": True, "synced": None, "plain": None, "source": None, "found": False})

    lyrics_cache[cache_key] = {"data": result, "ts": datetime.now().timestamp()}
    return web.json_response({"ok": True, **result, "found": True})

async def webapp_health(request: web.Request) -> web.Response:
    return web.json_response({"ok": True, "status": "running"})

def load_app_version() -> dict:
    """Read the published app version descriptor (app_version.json)."""
    data = {
        "version_code": DEFAULT_APP_VERSION_CODE,
        "version_name": DEFAULT_APP_VERSION_NAME,
        "notes": "",
    }
    try:
        if os.path.exists(APP_VERSION_FILE):
            with open(APP_VERSION_FILE, "r", encoding="utf-8") as f:
                loaded = json.load(f)
            if isinstance(loaded, dict):
                for key in ("version_code", "version_name", "notes"):
                    if key in loaded and loaded[key] is not None:
                        data[key] = loaded[key]
    except Exception as e:
        logging.warning(f"Load app version error: {e}")
    return data

async def webapp_app_version(request: web.Request) -> web.Response:
    """Return the latest published APK version for OTA update checks."""
    info = load_app_version()
    apk_exists = os.path.isfile(APP_APK_PATH)
    size = os.path.getsize(APP_APK_PATH) if apk_exists else 0
    return web.json_response(
        {
            "ok": True,
            "version_code": int(info.get("version_code") or 0),
            "version_name": str(info.get("version_name") or ""),
            "notes": str(info.get("notes") or ""),
            "size": size,
            "download_url": "/api/app/download" if apk_exists else "",
        }
    )

async def webapp_app_download(request: web.Request) -> web.StreamResponse:
    """Serve the latest APK for OTA updates."""
    if not os.path.isfile(APP_APK_PATH):
        raise web.HTTPNotFound(text="APK not found")
    return await _stream_file_response(
        request,
        APP_APK_PATH,
        content_type="application/vnd.android.package-archive",
        content_disposition='attachment; filename="MusicFind.apk"',
    )

async def webapp_statistics_legacy(request: web.Request) -> web.Response:
    uid, user = extract_android_user(request)
    if not uid or not user:
        return web.json_response({"ok": False, "error": "AUTH_INVALID"}, status=401)
    stats = await build_user_statistics(uid)
    return web.json_response({"ok": True, "stats": stats})

async def webapp_static(request: web.Request) -> web.Response:
    """Serve static files only via secret path token."""
    token = request.match_info.get("token", "")
    if token != STATIC_TOKEN:
        return web.Response(status=404)
    filename = request.match_info.get("filename", "")
    filepath = os.path.join(WEBAPP_DIR, filename)
    if not os.path.abspath(filepath).startswith(os.path.abspath(WEBAPP_DIR)):
        return web.Response(status=404)
    if not os.path.isfile(filepath):
        return web.Response(status=404)
    return web.FileResponse(filepath)

async def start_webapp_server():
    """Serve the mini app and API directly from the bot process."""
    app = web.Application()
    app.router.add_get("/", webapp_index)
    app.router.add_get("/index.html", webapp_index)
    app.router.add_get("/static/{filename:.+}", lambda r: web.Response(status=404))
    app.router.add_get("/api/bootstrap", webapp_bootstrap)
    app.router.add_get("/api/search", webapp_search)
    app.router.add_post("/api/recognize-track", webapp_recognize_track)
    app.router.add_get("/api/download-status", webapp_download_status)
    app.router.add_post("/api/cancel-download", webapp_cancel_download)
    app.router.add_post("/api/android-auth", webapp_android_auth)
    app.router.add_post("/api/password-auth", webapp_password_auth)
    app.router.add_post("/api/account/register", webapp_account_register)
    app.router.add_post("/api/account/login", webapp_account_login)
    app.router.add_get("/api/account/profile", webapp_account_profile)
    app.router.add_post("/api/account/profile/update", webapp_account_update_profile)
    app.router.add_post("/api/account/avatar", webapp_account_avatar_upload)
    app.router.add_get("/api/avatar/{key:.+}", webapp_serve_avatar)
    app.router.add_get("/api/cover/{key:.+}", webapp_serve_cover)
    app.router.add_post("/api/account/link-telegram", webapp_account_link_telegram)
    app.router.add_post("/api/account/unlink-telegram", webapp_account_unlink_telegram)
    app.router.add_post("/api/account/featured", webapp_account_featured_toggle)
    app.router.add_get("/login", webapp_login_page)
    app.router.add_get("/createacc", webapp_createacc_page)
    app.router.add_post("/api/activity-ping", webapp_activity_ping)
    app.router.add_get("/api/recommendations", webapp_recommendations)
    app.router.add_get("/api/statistics", webapp_statistics)
    app.router.add_get("/api/playlists", webapp_playlists)
    app.router.add_post("/api/create-playlist", webapp_create_playlist)
    app.router.add_post("/api/playlist-cover", webapp_update_playlist_cover)
    app.router.add_post("/api/play-track", webapp_play_track)
    app.router.add_post("/api/play-playlist-track", webapp_play_playlist_track)
    app.router.add_get("/api/download-playlist-track", webapp_download_playlist_track)
    app.router.add_post("/api/prepare-download", webapp_prepare_download)
    app.router.add_get("/api/dl/{token}", webapp_serve_download)
    app.router.add_post("/api/add-track-to-playlist", webapp_add_track_to_playlist)
    app.router.add_post("/api/remove-playlist-track", webapp_remove_playlist_track)
    app.router.add_post("/api/remove-playlist", webapp_remove_playlist)
    app.router.add_post("/api/toggle-favorite", webapp_toggle_favorite)
    app.router.add_post("/api/release-media", webapp_release_media)
    app.router.add_get("/api/media-recognition/{media_id}", webapp_media_recognition)
    app.router.add_get("/api/media/{media_id}", webapp_media)
    app.router.add_get("/api/lyrics", webapp_lyrics)
    app.router.add_get("/api/health", webapp_health)
    app.router.add_get("/api/app/version", webapp_app_version)
    app.router.add_get("/api/app/download", webapp_app_download)

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, host=WEBAPP_BIND, port=WEBAPP_PORT)
    try:
        await site.start()
    except OSError as e:
        await runner.cleanup()
        if e.errno == 98:
            raise RuntimeError(
                f"Mini app port {WEBAPP_BIND}:{WEBAPP_PORT} is already in use. "
                "Most likely another bot instance is already running."
            ) from e
        raise
    logging.info(f"Mini app server started on http://{WEBAPP_BIND}:{WEBAPP_PORT}")

def build_android_api_application() -> web.Application:
    """Application exposing the API surface used by the Android app only."""
    app = web.Application()
    app.router.add_get("/api/bootstrap", webapp_bootstrap)
    app.router.add_get("/api/search", webapp_search)
    app.router.add_post("/api/recognize-track", webapp_recognize_track)
    app.router.add_get("/api/download-status", webapp_download_status)
    app.router.add_post("/api/cancel-download", webapp_cancel_download)
    app.router.add_post("/api/android-auth", webapp_android_auth)
    app.router.add_post("/api/password-auth", webapp_password_auth)
    app.router.add_post("/api/account/register", webapp_account_register)
    app.router.add_post("/api/account/login", webapp_account_login)
    app.router.add_get("/api/account/profile", webapp_account_profile)
    app.router.add_post("/api/account/profile/update", webapp_account_update_profile)
    app.router.add_post("/api/account/avatar", webapp_account_avatar_upload)
    app.router.add_get("/api/avatar/{key:.+}", webapp_serve_avatar)
    app.router.add_get("/api/cover/{key:.+}", webapp_serve_cover)
    app.router.add_post("/api/account/link-telegram", webapp_account_link_telegram)
    app.router.add_post("/api/account/unlink-telegram", webapp_account_unlink_telegram)
    app.router.add_post("/api/account/featured", webapp_account_featured_toggle)
    app.router.add_get("/login", webapp_login_page)
    app.router.add_get("/createacc", webapp_createacc_page)
    app.router.add_post("/api/activity-ping", webapp_activity_ping)
    app.router.add_get("/api/recommendations", webapp_recommendations)
    app.router.add_get("/api/statistics", webapp_statistics)
    app.router.add_get("/api/playlists", webapp_playlists)
    app.router.add_post("/api/create-playlist", webapp_create_playlist)
    app.router.add_post("/api/playlist-cover", webapp_update_playlist_cover)
    app.router.add_post("/api/play-track", webapp_play_track)
    app.router.add_post("/api/play-playlist-track", webapp_play_playlist_track)
    app.router.add_get("/api/download-playlist-track", webapp_download_playlist_track)
    app.router.add_post("/api/prepare-download", webapp_prepare_download)
    app.router.add_get("/api/dl/{token}", webapp_serve_download)
    app.router.add_post("/api/add-track-to-playlist", webapp_add_track_to_playlist)
    app.router.add_post("/api/remove-playlist-track", webapp_remove_playlist_track)
    app.router.add_post("/api/remove-playlist", webapp_remove_playlist)
    app.router.add_post("/api/toggle-favorite", webapp_toggle_favorite)
    app.router.add_post("/api/release-media", webapp_release_media)
    app.router.add_get("/api/media-recognition/{media_id}", webapp_media_recognition)
    app.router.add_get("/api/media/{media_id}", webapp_media)
    app.router.add_get("/api/lyrics", webapp_lyrics)
    app.router.add_get("/api/health", webapp_health)
    app.router.add_get("/api/app/version", webapp_app_version)
    app.router.add_get("/api/app/download", webapp_app_download)
    return app

async def start_android_api_server():
    """Serve the Android-only API on its own port, sharing this process' data."""
    app = build_android_api_application()
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, host=APP_API_BIND, port=APP_API_PORT)
    try:
        await site.start()
    except OSError as e:
        await runner.cleanup()
        if e.errno == 98:
            raise RuntimeError(
                f"Android API port {APP_API_BIND}:{APP_API_PORT} is already in use."
            ) from e
        raise
    logging.info(f"Android API server started on http://{APP_API_BIND}:{APP_API_PORT}")

def acquire_process_lock():
    """Prevent accidental double-start of the bot on the same machine."""
    global process_lock_handle
    process_lock_handle = open(LOCK_FILE, "w", encoding="utf-8")
    try:
        fcntl.flock(process_lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as e:
        raise RuntimeError("Another Music Bot instance is already running in this directory.") from e
    process_lock_handle.write(str(os.getpid()))
    process_lock_handle.flush()


def load_recommendations_cache():
    """Загрузить кэш рекомендаций из файла"""
    global recommendations_cache, last_recommendation_update
    try:
        if os.path.exists(RECOMMENDATIONS_CACHE):
            with open(RECOMMENDATIONS_CACHE, 'r', encoding='utf-8') as f:
                data = json.load(f)
                recommendations_cache = data.get('recommendations', {})
                last_recommendation_update = data.get('last_update', {})
                logging.info("Recommendations cache loaded")
    except Exception as e:
        logging.error(f"Load recommendations cache error: {e}")

def save_recommendations_cache():
    """Сохранить кэш рекомендаций в файл"""
    try:
        data = {
            'recommendations': recommendations_cache,
            'last_update': last_recommendation_update
        }
        with open(RECOMMENDATIONS_CACHE, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        logging.info("Recommendations cache saved")
    except Exception as e:
        logging.error(f"Save recommendations cache error: {e}")

def is_cache_expired(uid: str) -> bool:
    """Проверить, истёк ли кэш рекомендаций (24 часа)"""
    last_update = last_recommendation_update.get(uid)
    if not last_update:
        return True
    try:
        update_time = datetime.fromisoformat(last_update)
        return datetime.utcnow() - update_time > timedelta(hours=RECOMMENDATIONS_TTL_HOURS)
    except:
        return True

def parse_filename_to_query(filename: str) -> str:
    """Извлечь артиста и название из имени файла для поиска"""
    name = strip_ext(filename)
    if " - " in name:
        parts = name.split(" - ", 1)
        return f"{parts[0].strip()} {parts[1].strip()}"
    return name

async def analyze_user_playlist(uid: str) -> dict:
    """Анализировать плейлист пользователя и собрать статистику"""
    artists = {}
    genres = {}
    all_tracks = []
    sampled_track_artists = []
    
    playlists = list_playlists(uid)
    for pl_name in playlists:
        inferred_playlist_genre = infer_genre_from_text(pl_name)
        if inferred_playlist_genre:
            genres[inferred_playlist_genre] = genres.get(inferred_playlist_genre, 0) + 3
        tracks = list_tracks(uid, pl_name)
        for track_file in tracks:
            query = parse_filename_to_query(track_file)
            all_tracks.append(query)
            if " - " in track_file:
                artist = track_file.split(" - ", 1)[0].strip()
                artists[artist] = artists.get(artist, 0) + 1
                sampled_track_artists.append(artist)
            else:
                sampled_track_artists.append("")
    
    sample_tracks = all_tracks[:12]
    for index, track_query in enumerate(sample_tracks):
        try:
            result = await shazam.search_track(track_query)
            if result.get('tracks'):
                track_data = result['tracks'][0]
                track_info = Serialize.track(data=track_data)
                genre = track_info.genres.get('primary') if track_info.genres else 'Unknown'
                resolved_genre = infer_genre_from_text(genre or "")
                if not resolved_genre:
                    local_artist = sampled_track_artists[index] if index < len(sampled_track_artists) else ""
                    resolved_genre = await resolve_artist_genre_from_web(local_artist or track_info.subtitle or "")
                if resolved_genre:
                    genres[resolved_genre] = genres.get(resolved_genre, 0) + 1
        except Exception as e:
            logging.debug(f"Genre analysis error for {track_query}: {e}")
            continue

    if not genres:
        for artist, count in sorted(artists.items(), key=lambda item: item[1], reverse=True)[:8]:
            resolved_genre = await resolve_artist_genre_from_web(artist)
            if resolved_genre:
                genres[resolved_genre] = genres.get(resolved_genre, 0) + max(1, count)
    
    return {
        'artists': artists,
        'genres': genres,
        'total_tracks': len(all_tracks),
        'top_artists': sorted(artists.items(), key=lambda x: x[1], reverse=True)[:3],
        'top_genres': sorted(genres.items(), key=lambda x: x[1], reverse=True)[:3]
    }

async def get_recommendations_by_artist(artist: str, limit: int = 5) -> list:
    """Получить рекомендации по исполнителю"""
    try:
        result = await shazam.search_artist(artist, limit=max(limit * 4, 10))
        if not result or not result.get('tracks'):
            logging.debug(f"No Shazam results for {artist}, using fallback")
            return get_fallback_recommendations(artist, limit)
        recommendations = []
        seen = set()
        for track in result.get('tracks', []):
            track_info = Serialize.track(data=track)
            if not artist_matches_target(track_info.subtitle, artist):
                continue
            track_key = f"{track_info.subtitle}-{track_info.title}"
            if track_key not in seen:
                seen.add(track_key)
                recommendations.append({
                    'title': track_info.title,
                    'artist': track_info.subtitle,
                    'shazam_id': track.get('key'),
                    'cover': track.get('images', {}).get('coverart') if track.get('images') else None
                })
                if len(recommendations) >= limit:
                    break
        return recommendations
    except Exception as e:
        logging.debug(f"Artist recommendations error (using fallback): {e}")
        return get_fallback_recommendations(artist, limit)

def get_fallback_recommendations(artist: str, limit: int = 5) -> list:
    """Fallback recommendations using YouTube search when Shazam fails"""
    try:
        queries = [artist, f"{artist} songs"]
        recommendations = []
        seen = set()
        for query in queries:
            results = sc_search(query)
            for track in results:
                if not artist_matches_target(track.get('artist', ''), artist):
                    continue
                if looks_like_compilation(track.get('title', ''), track.get('artist', '')):
                    continue
                track_key = f"{track['artist']}-{track['title']}"
                if track_key in seen:
                    continue
                seen.add(track_key)
                recommendations.append({
                    'title': track['title'],
                    'artist': track['artist'],
                    'shazam_id': None,
                    'cover': None,
                    'url': track['url'],
                    'src': track['src']
                })
                if len(recommendations) >= limit:
                    return recommendations
        return recommendations
    except Exception as e:
        logging.error(f"Fallback recommendations error: {e}")
        return []

async def get_recommendations_by_genre(genre: str, limit: int = 5) -> list:
    """Получить рекомендации по жанру"""
    try:
        genre_slug = resolve_genre_slug(genre)
        if not genre_slug:
            logging.debug(f"Unsupported genre label for Shazam: {genre}, using fallback")
            return get_fallback_genre_recommendations(genre, limit)
        top_tracks = await shazam.top_world_genre_tracks(genre=genre_slug, limit=limit)
        if not top_tracks or not top_tracks.get('tracks'):
            logging.debug(f"No Shazam genre results for {genre}, using fallback")
            return get_fallback_genre_recommendations(genre, limit)
        recommendations = []
        for track in top_tracks.get('tracks', []):
            track_info = Serialize.track(data=track)
            recommendations.append({
                'title': track_info.title,
                'artist': track_info.subtitle,
                'shazam_id': track.get('key'),
                'cover': track.get('images', {}).get('coverart') if track.get('images') else None
            })
        return recommendations
    except Exception as e:
        logging.debug(f"Genre recommendations error (using fallback): {e}")
        return get_fallback_genre_recommendations(genre, limit)

def get_fallback_genre_recommendations(genre: str, limit: int = 5) -> list:
    """Fallback genre recommendations using YouTube search"""
    try:
        recommendations = []
        seen = set()
        for query in genre_query_variants(genre):
            results = sc_search(query)
            for track in results:
                if not genre_result_matches(genre, track):
                    continue
                if looks_like_compilation(track.get('title', ''), track.get('artist', '')):
                    continue
                track_key = f"{track['artist']}-{track['title']}"
                if track_key in seen:
                    continue
                seen.add(track_key)
                recommendations.append({
                    'title': track['title'],
                    'artist': track['artist'],
                    'shazam_id': None,
                    'cover': None,
                    'url': track['url'],
                    'src': track['src']
                })
                if len(recommendations) >= limit:
                    return recommendations
        return recommendations
    except Exception as e:
        logging.error(f"Fallback genre recommendations error: {e}")
        return []

async def get_similar_tracks(track_id: str, limit: int = 5) -> list:
    """Получить похожие треки через Shazam related_tracks"""
    try:
        related = await shazam.related_tracks(track_id=track_id, limit=limit)
        recommendations = []
        seen = set()
        for track in related.get('tracks', []):
            track_info = Serialize.track(data=track)
            track_key = f"{track_info.subtitle}-{track_info.title}"
            if track_key not in seen:
                seen.add(track_key)
                recommendations.append({
                    'title': track_info.title,
                    'artist': track_info.subtitle,
                    'shazam_id': track.get('key'),
                    'cover': track.get('images', {}).get('coverart') if track.get('images') else None
                })
                if len(recommendations) >= limit:
                    break
        return recommendations
    except Exception as e:
        logging.error(f"Similar tracks error: {e}")
        return []

async def generate_recommendations(uid: str) -> list:
    """Сгенерировать персональные рекомендации для пользователя"""
    logging.info(f"Generating recommendations for user {uid}")
    
    playlist_analysis = await analyze_user_playlist(uid)
    all_recommendations = []
    seen_tracks = set()
    artist_recommendations = []
    genre_recommendations = []

    async def build_playable_recommendation(rec: dict, reason: str) -> dict | None:
        track_key = f"{rec.get('artist', '')}-{rec.get('title', '')}".lower()
        if track_key in seen_tracks:
            return None
        playable = await ensure_playable_recommendation({**rec, "reason": reason})
        if not playable:
            return None
        seen_tracks.add(track_key)
        return playable

    async def extend_bucket(source_recommendations: list, reason: str, bucket: list, target_size: int) -> None:
        if len(bucket) >= target_size:
            return
        for rec in source_recommendations:
            playable = await build_playable_recommendation(rec, reason)
            if not playable:
                continue
            bucket.append(playable)
            if len(bucket) >= target_size:
                return
    
    top_artists = playlist_analysis['top_artists']
    top_genres = playlist_analysis['top_genres']

    if top_artists:
        per_artist_limit = max(4, ARTIST_RECOMMENDATIONS_TARGET)
        for artist, count in top_artists:
            if len(artist_recommendations) >= ARTIST_RECOMMENDATIONS_TARGET:
                break
            artist_recs = await get_recommendations_by_artist(artist, limit=per_artist_limit)
            await extend_bucket(artist_recs, f"🎤 Исполнитель: {artist}", artist_recommendations, ARTIST_RECOMMENDATIONS_TARGET)

        if len(artist_recommendations) < ARTIST_RECOMMENDATIONS_TARGET:
            for artist, count in top_artists:
                if len(artist_recommendations) >= ARTIST_RECOMMENDATIONS_TARGET:
                    break
                fallback_artist_recs = get_fallback_recommendations(artist, limit=ARTIST_RECOMMENDATIONS_TARGET)
                await extend_bucket(fallback_artist_recs, f"🎤 Исполнитель: {artist}", artist_recommendations, ARTIST_RECOMMENDATIONS_TARGET)

    if top_genres:
        per_genre_limit = max(4, GENRE_RECOMMENDATIONS_TARGET)
        for genre, count in top_genres:
            if len(genre_recommendations) >= GENRE_RECOMMENDATIONS_TARGET:
                break
            genre_recs = await get_recommendations_by_genre(genre, limit=per_genre_limit)
            await extend_bucket(genre_recs, f"🎵 Жанр: {genre}", genre_recommendations, GENRE_RECOMMENDATIONS_TARGET)

        if len(genre_recommendations) < GENRE_RECOMMENDATIONS_TARGET:
            for genre, count in top_genres:
                if len(genre_recommendations) >= GENRE_RECOMMENDATIONS_TARGET:
                    break
                fallback_genre_recs = get_fallback_genre_recommendations(genre, limit=GENRE_RECOMMENDATIONS_TARGET)
                await extend_bucket(fallback_genre_recs, f"🎵 Жанр: {genre}", genre_recommendations, GENRE_RECOMMENDATIONS_TARGET)

    if playlist_analysis['total_tracks'] == 0:
        try:
            random_recs = await get_random_discovery_recommendations(limit=ARTIST_RECOMMENDATIONS_TARGET + GENRE_RECOMMENDATIONS_TARGET)
            await extend_bucket(random_recs, "🎲 Случайный трек", artist_recommendations, ARTIST_RECOMMENDATIONS_TARGET)
            await extend_bucket(random_recs, "🎲 Случайный трек", genre_recommendations, GENRE_RECOMMENDATIONS_TARGET)
        except Exception as e:
            logging.error(f"Random discovery recommendations error: {e}")

    if len(artist_recommendations) < ARTIST_RECOMMENDATIONS_TARGET:
        fallback_artist_pool = get_fallback_genre_recommendations('pop', ARTIST_RECOMMENDATIONS_TARGET * 2)
        await extend_bucket(fallback_artist_pool, "🎤 Исполнитель: Популярное", artist_recommendations, ARTIST_RECOMMENDATIONS_TARGET)

    for genre, count in top_genres:
        if len(genre_recommendations) >= GENRE_RECOMMENDATIONS_TARGET:
            break
        extra_genre_recs = get_fallback_genre_recommendations(genre, limit=GENRE_RECOMMENDATIONS_TARGET * 3)
        await extend_bucket(extra_genre_recs, f"🎵 Жанр: {genre}", genre_recommendations, GENRE_RECOMMENDATIONS_TARGET)

    all_recommendations.extend(artist_recommendations[:ARTIST_RECOMMENDATIONS_TARGET])
    all_recommendations.extend(genre_recommendations[:GENRE_RECOMMENDATIONS_TARGET])
    return all_recommendations

