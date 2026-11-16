# CineLog Android

这是 CineLog 的 Android 客户端与本地可部署后端工程。客户端仍保留离线模式，当前数据保存在应用私有 `SharedPreferences`；`backend/` 提供带认证、SQLite/PostgreSQL 数据库、用户隔离和 CRUD API 的服务端，方便先在本地运行，再部署到云主机。

## 构建

需要 Android Studio、Android SDK 35 和 Gradle 8.7+。SDK 路径固定为 `F:\Android\sdk`（`local.properties`）。在本目录执行 `gradlew assembleDebug`（或用 Android Studio 打开项目），APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

当前 debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，并同步到工作区固定交付路径 `F:\桌面\lab2\CineLog-debug.apk`。每次版本更新后覆盖这两个位置。APK 包名为 `com.cinelog.app`；当前版本 `1.1`（versionCode 2），保留既有包名和调试签名，可覆盖安装并保留应用本地数据。界面适配系统状态栏/导航栏安全区，顶部添加按钮改为更宽的“＋ 添加”触控目标。当前客户端尚未接入后端 API，因此此版本的手机数据仍只存本地。

## 本地后端

后端代码在 `backend/`，默认生产容器使用 PostgreSQL。复制 `.env.example` 为 `.env`，设置长随机 `POSTGRES_PASSWORD` 和 `JWT_SECRET`，然后在工程根目录执行：

```text
docker compose up --build
```

健康检查：`http://localhost:8000/health`。API 文档：`http://localhost:8000/docs`。开发时也可以直接使用 SQLite：`python -m uvicorn app.main:app --app-dir backend --reload`。

后端已提供注册、登录、当前用户、作品增删改查、按类型/状态/关键词筛选、标签增删和健康检查。密码使用 PBKDF2，token 使用 HMAC 签名，并且所有作品/标签查询都按登录用户隔离。
