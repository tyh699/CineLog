# CineLog

一个受豆瓣启发的本地观影记录 App：记录电影/剧集、评分、短评、标签和想看清单，并支持本机账号隔离、统计和可解释推荐。

## 一键启动（本地全栈）

需要 Python 3.11+。在 `cine-log-android` 目录执行：

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r backend\requirements.txt
$env:PYTHONPATH = "backend"
python -m uvicorn app.main:app --reload
```

打开 http://127.0.0.1:8000/ 。首次点击右下角设置图标，注册一个本地账号。数据默认写入 `backend/cinelog.db`，只保存在当前电脑；API 文档在 http://127.0.0.1:8000/docs 。

## 已实现功能

- 电影/剧集日志、想看清单、评分、短评、日期和标签；
- 本地注册/登录与用户数据隔离（PBKDF2 密码哈希、HMAC token）；
- SQLite 默认存储，也保留 Docker + PostgreSQL 配置；
- 作品和标签完整 CRUD，按类型、状态、标题/短评/标签搜索；
- `/api/stats` 年度统计基础接口和 `/api/recommendations` 本地标签推荐接口；
- 响应式网页端与 Android 客户端工程，共用同一套数据模型；
- 后端自动托管网页，无需额外前端服务器。

## 测试

```powershell
$env:PYTHONPATH = "backend"
python -m pytest backend\tests -q
```

## Android

`app/` 现在支持本地数据库连接：打开 App 顶部“连接”，登录/注册后会把片单同步到 FastAPI。默认 Android 模拟器地址是 `http://10.0.2.2:8000`；真机和电脑在同一 Wi-Fi 时，用电脑局域网 IP 构建：

```powershell
.\gradlew.bat clean assembleDebug -PAPI_BASE_URL=http://192.168.1.100:8000
```

把 `192.168.1.100` 换成电脑实际局域网 IP，并允许 Windows 防火墙放行 8000 端口。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，仓库外的同步交付副本位于 `F:\桌面\lab2\CineLog-debug.apk`。`docker-compose.yml` 提供可选的 PostgreSQL 本地部署方式。
