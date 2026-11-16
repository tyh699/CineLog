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

如需启用 TMDB 在线搜索，在本机后端启动前设置 TMDB API Read Access Token（不要写入 APK、Git 或聊天）：

```powershell
$env:TMDB_API_READ_TOKEN = "你的 TMDB Read Access Token"
```

未设置 Token 时仍使用内置本地目录；设置后，作品搜索会合并 TMDB 的电影/剧集结果。TMDB 结果经后端代理返回，手机端不保存 Token。

打开 http://127.0.0.1:8000/ 。首次打开直接注册/登录本地账号，密码至少 6 位。第一个注册账号会自动成为管理员；管理员监测页在 http://127.0.0.1:8000/admin 。数据默认写入项目目录下的 `cinelog.db`，只保存在当前电脑；卸载手机 App 或退出登录都不会删除服务端数据。API 文档在 http://127.0.0.1:8000/docs 。

## 已实现功能

- 电影/剧集日志、想看清单、评分、短评、日期和标签；
- 本地注册/登录与用户数据隔离（PBKDF2 密码哈希、HMAC token）；
- SQLite 默认存储，也保留 Docker + PostgreSQL 配置；
- 作品和标签完整 CRUD，按类型、状态、标题/短评/标签搜索；
- `/api/stats` 年度统计基础接口和 `/api/recommendations` 本地标签推荐接口；
- `/api/catalog/search` 本地标准作品目录搜索；新增/编辑作品必须选择目录结果，不能保存任意的 `1` 等文本；
- `/api/catalog/search` 在配置 `TMDB_API_READ_TOKEN` 后会优先合并 TMDB 在线搜索，并保留本地目录兜底；
- `/api/admin/overview` 管理员监测接口与 `/admin` 管理后台；
- 响应式网页端与 Android 客户端工程，共用同一套数据模型；
- 后端自动托管网页，无需额外前端服务器。

## 测试

```powershell
$env:PYTHONPATH = "backend"
python -m pytest backend\tests -q
```

## Android

`app/` 是账号优先的正常 App：首次打开直接注册/登录，密码至少 6 位，登录后进入首页；本地使用 SQLite 缓存作品和标签，并自动同步到电脑上的 FastAPI。手机安装包已默认配置当前电脑地址 `http://10.29.63.116:8000`，登录页不需要填写服务器地址。手机与电脑连接同一 Wi-Fi，电脑上需运行本地服务。Android 模拟器会自动切换到 `http://10.0.2.2:8000`。若这台电脑的局域网地址变化，可以在构建时覆盖：

```powershell
.\gradlew.bat clean assembleDebug -PAPI_BASE_URL=http://192.168.1.100:8000
```

把示例地址换成电脑实际局域网 IP，并允许 Windows 防火墙放行 8000 端口。当前 Android 版本为 `1.5`，APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，仓库外的同步交付副本位于 `F:\桌面\lab2\CineLog-debug.apk`。`docker-compose.yml` 提供可选的 PostgreSQL 本地部署方式。
