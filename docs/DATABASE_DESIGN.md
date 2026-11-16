# CineLog 数据结构

## Android 本地库

使用 Android 平台自带的 SQLite，由 `LocalDatabase`（`SQLiteOpenHelper`）管理，数据库文件保存在 App 私有目录，不需要用户安装数据库软件。

```text
entries 1 ─── * entry_tags * ─── 1 tags
```

- `entries`：作品主记录，含类型、状态、评分、短评、观看日期等。
- `tags`：账号可用的标签名称及适用范围。
- `entry_tags`：作品与标签的关联表；复合主键 `(entry_id, tag_id)` 防止重复标注，外键级联删除。
- `app_state`：本地轻量状态元数据；登录凭据 Token 单独放 Android App 私有偏好存储，不进 Git。

这种多对多关系允许一个作品拥有多个标签，同一标签也可用于多部作品；删除标签或作品时只移除关联，不留下孤儿数据。

## 本机服务端 SQLite

FastAPI + SQLAlchemy 在本机运行，默认 SQLite 文件为 `cinelog.db`。服务端以 `users` 为账号主表：

```text
users 1 ─── * entries
users 1 ─── * tags
```

`entries.owner_id` 和 `tags.owner_id` 都指向 `users.id`，用于隔离不同账号的数据。API 兼容层把条目的标签名作为列表传输；客户端收到后会写入规范化的 SQLite 关联表。用户密码以 PBKDF2 哈希保存，访问 Token 只保存在登录端，不随片单内容传输。

## 同步边界

- 注册后，当前设备的本地片单上传到该账号的本机服务端库。
- 登录后，服务端账号数据覆盖本地缓存，随后编辑会自动同步。
- 退出登录只清除认证 Token，不删除服务端或本地 SQLite 数据；重新登录即可恢复片单。
- 手机与电脑需在同一局域网。测试 APK 已内置当前电脑地址，Android 模拟器会自动使用 `10.0.2.2` 访问宿主机。

## 管理员监测

- 第一个注册账号会成为管理员；升级旧数据库时，若尚无管理员则将最早创建的账号提升为管理员。
- 管理员访问 `/admin` 并用正常账号登录，可查看服务状态、用户数、电影/剧集/想看数量、用户账号清单和最近作品活动。
- 管理数据接口 `/api/admin/overview` 只允许管理员调用；普通账号返回 403。
