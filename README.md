# MusicFind

Музыкальное приложение: Android-клиент + собственный сервер.

- **Клиент** (Kotlin / Jetpack Compose): поиск треков, распознавание музыки через
  микрофон (Shazam), плейлисты, текст песни (синхронизированный), статистика
  прослушивания, скачивание для офлайна, обновление по воздуху (OTA).
- **Сервер** (Python / aiohttp): поиск и стриминг (yt-dlp), распознавание
  (shazamio), тексты (LRCLIB), плейлисты, аккаунты, статистика, админ-панель и
  раздача APK для автообновления.

> В репозитории **нет** личных данных: cookie-файлы, база, плейлисты и `.env`
> не входят в состав и создаются при установке.

---

## Требования

- **Сервер**: Linux с `systemd`, Python 3.10+, `ffmpeg`.
- **Клиент**: Android Studio / Android SDK 35, JDK 17.

---

## Установка сервера

```bash
git clone <repo-url> musicfind
cd musicfind
sudo ./install.sh
```

Скрипт установки:

1. Ставит системные пакеты (`python3`, `python3-venv`, `ffmpeg`).
2. Создаёт `server/.venv` и ставит зависимости из `server/requirements.txt`.
3. Создаёт `server/data/` и `server/.env` (с случайным паролем админа).
4. Регистрирует и запускает сервис `musicfind-api`.

После установки:

- API: `http://<server-ip>:8081`
- Админ-панель: `http://<server-ip>:8081/admin`
- Пароль админа выводится один раз в конце установки (и лежит в `server/.env`).

Полезные команды:

```bash
systemctl status musicfind-api
journalctl -u musicfind-api -f
sudo ./install.sh            # обновление (повторный запуск)
sudo ./install.sh --uninstall
```

### Настройка (`server/.env`)

Смотрите `server/.env.example`. Основные параметры:

| Переменная | Назначение |
|---|---|
| `HOST`, `PORT` | адрес и порт сервера |
| `ADMIN_PASSWORD` | пароль админ-панели |
| `APP_APK_PATH` | путь к APK для OTA-обновлений |
| `AUTO_UPDATE` | автообновление yt-dlp/ffmpeg (1/0) |

---

## Сборка Android-приложения

```bash
# путь к Android SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`.
Скопируйте его в путь `APP_APK_PATH` на сервере, чтобы работало автообновление:

```bash
scp app/build/outputs/apk/debug/app-debug.apk user@server:/opt/musicfind/server/data/app/MusicFind.apk
```

При первом запуске приложение попросит адрес сервера (экран настройки) — укажите
`http://<server-ip>:8081`.

---

## Структура

```
app/                    Android-клиент
server/
  main.py               HTTP API, аккаунты, плейлисты, статистика, админка, OTA
  music_engine.py       поиск, Shazam, yt-dlp, медиа, тексты
  requirements.txt      зависимости Python
  .env.example          шаблон конфигурации
install.sh              установщик сервера (systemd)
```

---

## Безопасность

- Не коммитьте `server/.env`, `server/data/` и cookie-файлы — они в `.gitignore`.
- Обязательно смените `ADMIN_PASSWORD`.
- Для доступа к закрытому контенту можно положить cookie-файлы в
  `server/data/yt_cookies.txt` и `server/data/sc_cookies.txt`.
