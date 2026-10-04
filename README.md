<div align="center">

# MusicFind

**Собственный музыкальный стриминг: Android‑клиент и self‑hosted сервер.**

Поиск и воспроизведение треков из SoundCloud и YouTube Music, распознавание музыки
по микрофону, синхронизированные тексты, плейлисты, статистика и обновления по воздуху.

![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Linux-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF)
![Python](https://img.shields.io/badge/Python-3.10%2B-3776AB)
![Server](https://img.shields.io/badge/server-aiohttp-2C5BB4)

</div>

---

## Возможности

| Клиент (Android) | Сервер (self‑hosted) |
|---|---|
| Поиск треков (SoundCloud, YouTube Music) | Поиск и стриминг через `yt-dlp` |
| Распознавание музыки по микрофону (Shazam) | Распознавание через `shazamio` |
| Синхронизированные тексты песен | Тексты из LRCLIB (с кэшем) |
| Плейлисты, «Мне нравится», редактирование треков | Хранение плейлистов по аккаунтам |
| Скачивание треков и плейлистов для офлайна | Серверная загрузка и раздача медиа |
| Статистика прослушивания и топ пользователей | Подсчёт статистики и рейтинг |
| Обновления по воздуху (OTA) | Админ‑панель и раздача APK |
| Тёмная/светлая тема, акцентные цвета | Аккаунты, аватары, Telegram‑профили |
| Русский и английский языки | Автообновление `yt-dlp` и `ffmpeg` |

> **Приватность.** В репозитории нет личных данных: cookie‑файлы, база, плейлисты
> и `.env` не входят в состав и создаются локально при установке.

---

## Как это работает

```
┌──────────────────────────┐        HTTP/JSON         ┌──────────────────────────────┐
│      Android-клиент      │  ───────────────────────▶ │         MusicFind API         │
│  Kotlin · Jetpack Compose│  ◀─────────────────────── │       Python · aiohttp        │
└──────────────────────────┘                           └───────────────┬──────────────┘
                                                                        │
                        ┌───────────────────────────────────────────────┼───────────────────────────────┐
                        ▼                        ▼                       ▼                               ▼
                  yt-dlp (SC/YT)           shazamio                 LRCLIB                        локальное медиа
                  поиск и загрузка        распознавание           тексты песен                (/api/media, /api/cover)
```

Клиент общается только со своим сервером. Сервер сам ходит к внешним источникам,
кэширует медиа и раздаёт его клиенту — поэтому приложение не зависит от чужих API‑ключей.

---

## Быстрый старт

### 1. Сервер

```bash
git clone <repo-url> musicfind
cd musicfind
sudo ./install.sh
```

Установщик поставит зависимости, создаст окружение и зарегистрирует systemd‑сервис:

1. системные пакеты — `python3`, `python3-venv`, `ffmpeg`;
2. `server/.venv` и зависимости из `server/requirements.txt`;
3. `server/data/` и `server/.env` (случайный пароль админа);
4. сервис `musicfind-api` и его запуск.

После установки:

| | |
|---|---|
| API | `http://<server-ip>:8081` |
| Админ‑панель | `http://<server-ip>:8081/admin` |
| Пароль админа | выводится один раз в конце установки (и лежит в `server/.env`) |

Управление:

```bash
systemctl status musicfind-api     # статус
journalctl -u musicfind-api -f     # логи
sudo ./install.sh                  # обновление
sudo ./install.sh --uninstall      # удаление
```

### 2. Android‑приложение

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
При первом запуске приложение попросит адрес сервера — укажите `http://<server-ip>:8081`.

---

## Конфигурация сервера

Скопируйте `server/.env.example` в `server/.env` (установщик делает это сам).

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `HOST` | `0.0.0.0` | адрес прослушивания |
| `PORT` | `8081` | порт API |
| `ADMIN_PASSWORD` | — | пароль админ‑панели |
| `APP_APK_PATH` | `server/data/app/MusicFind.apk` | APK для OTA‑обновлений |
| `AUTO_UPDATE` | `1` | автообновление `yt-dlp`/`ffmpeg` (1/0) |
| `AUTO_UPDATE_INTERVAL_SECONDS` | `86400` | интервал автообновления |

### OTA‑обновления

Чтобы работало автообновление, положите собранный APK по пути `APP_APK_PATH`:

```bash
scp app/build/outputs/apk/debug/app-debug.apk \
    user@server:/opt/musicfind/server/data/app/MusicFind.apk
```

---

## Структура проекта

```
app/                         Android-клиент (Kotlin, Jetpack Compose)
  src/main/java/…/data/      модели, сеть, репозиторий, локальная библиотека
  src/main/java/…/player/    контроллер воспроизведения
  src/main/java/…/service/   foreground-сервис (Media3/ExoPlayer)
  src/main/java/…/ui/        экраны и компоненты
  src/main/res/values/       английские строки (язык по умолчанию)
  src/main/res/values-ru/    русские строки
server/
  main.py                    HTTP API, аккаунты, плейлисты, статистика, админка, OTA
  music_engine.py            поиск, Shazam, yt-dlp, медиа, тексты
  requirements.txt           зависимости Python
  .env.example               шаблон конфигурации
install.sh                   установщик сервера (systemd)
```

---

## Безопасность

- Не коммитьте `server/.env`, `server/data/` и cookie‑файлы — они уже в `.gitignore`.
- **Обязательно смените `ADMIN_PASSWORD`** после установки.
- Для доступа к закрытому контенту положите cookie‑файлы в
  `server/data/yt_cookies.txt` и `server/data/sc_cookies.txt`.
- Наружу лучше выставлять сервер через reverse‑proxy с HTTPS.

---

## FAQ

**Приложение не подключается к серверу.**
Проверьте, что сервис запущен (`systemctl status musicfind-api`) и адрес указан с
портом, например `http://192.168.1.10:8081`.

**Трек не воспроизводится.**
Первый запуск требует загрузки аудио на сервер (через `yt-dlp`) — это занимает время.
Если источник недоступен, сервер попробует альтернативный.

**Как сменить язык приложения?**
Настройки → Язык: `English` или `Русский` (по умолчанию — English).

---

<div align="center">
<sub>MusicFind — личный музыкальный сервер. Используйте ответственно и соблюдайте права правообладателей.</sub>
</div>
