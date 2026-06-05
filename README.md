# VoIP Project — Сборка APK через GitHub Actions

## Как собрать APK

### Автоматически (GitHub Actions)

1. Создай новый репозиторий на [github.com](https://github.com)
2. Загрузи **все файлы из этого архива** в репозиторий (структура должна сохраниться как есть)
3. GitHub Actions запустится автоматически при пуше в `main` или `master`
4. Либо запусти вручную: **Actions → Build APK → Run workflow**
5. После завершения (~3-5 минут) скачай APK:
   - Зайди в **Actions → последний запуск → Artifacts → VoIP-debug-apk**

### Структура репозитория

```
(корень репо)/
├── .github/
│   └── workflows/
│       └── build.yml       ← GitHub Actions workflow
├── android/                ← Android проект
│   ├── gradlew
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradle/wrapper/
│   └── app/src/...
└── server/                 ← Python сервер (отдельно)
    ├── server.py
    └── requirements.txt
```

## Запуск Python сервера

```bash
cd server
pip install -r requirements.txt
python server.py
```
