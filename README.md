# Keer Android

致谢：本项目在产品思路、交互设计与移动端实现上受到了 `memos` 和 `MoeMemosAndroid` 的启发与帮助。

`Keer Android` 是 `Keer` 的 Android 客户端，面向自托管记录、移动端同步、离线浏览和日常输入场景。应用当前围绕远程 `Keer` 账户工作，适合连接你自己的 `Keer` 服务端，在手机上完成记录、查看、编辑、同步、附件上传和群组消息等操作。

## 适用场景

- 需要一个连接自有 `Keer` 服务端的 Android 客户端
- 需要在手机上完成记录输入、附件管理和消息查看
- 希望断网时仍可浏览和编辑，联网后自动同步
- 希望使用分享入口、小组件和系统级移动端能力

## 主要能力

- 远程账户登录与注册
- 离线优先的数据访问与后台同步
- 记录创建、编辑、归档、置顶、标签整理
- 图片、文件、缩略图和断点续传上传
- 群组、群消息、已读状态与成员协作
- 账户设置、加密设置、密码变更
- 系统分享接收、快捷入口、小组件
- 基于 Jetpack Compose 的 Material 3 界面

## 个人 Memo 导入导出

当前已支持远程账户下“个人 memo”的完整导入与导出（包含附件文件）。

入口位置（远程账户）：

- 设置页 -> `个人 memo 导入导出` -> 选择 `导出个人 memo` 或 `导入个人 memo`

导出与导入说明：

- 导出为单个 `zip` 文件（包含 `manifest.json` + 附件文件）
- 导出时会优先使用本地已缓存的附件文件；本地不存在时再回退到远端下载
- 导出时如果某条 memo 无法解密，会自动跳过该条并在导出结果中显示失败数量
- 导入 `zip` 时会先写入本地（memo + 附件），再通过常规后台同步上传到服务端
- 兼容旧的纯 JSON 导入（无附件场景）
- 选择导入文件后会直接开始导入，不再需要预检确认
- 导入开始前会做在线前置校验（当前账号、鉴权、服务端可用性）；失败则不写入本地
- 导入/导出过程中会显示不可关闭进度弹层，任务完成后自动关闭
- 导入“完成”表示本地入库完成；服务端最终完成取决于后台同步进度
- 导入具备幂等去重能力：同一份数据包在“部分成功”后再次导入时，已成功导入的 memo 会自动跳过，避免重复写入
- 导入前会基于当前账号已存在的个人 memo 建立去重集合，因此“先导出再导回同一账号”会自动跳过重复项
- 去重优先使用 `importId`，缺失时回退到内容指纹（包含正文、时间、可见性、标签、坐标、状态、附件名与类型）
- 去重记录按账号持久化在本地并自动清理：默认保留 7 天，最多 20000 条，超出后会优先淘汰更旧记录

JSON 字段说明（`manifest.json` 或旧 JSON 导入）：

- 支持标准对象格式：`{ "format": "keer.memo.transfer.v2", "memos": [...] }`
- 也兼容直接数组格式：`[ {...}, {...} ]`
- 每条 memo 至少要有正文字段之一：`content` / `text` / `body`
- `createdAt` / `createTime` 可选，支持 RFC3339 或时间戳；如果提供，会写入本地创建时间并在同步时透传到服务端
- `format` 是导入导出文档结构版本；当前为 `keer.memo.transfer.v2`
- `importId` 是单条 memo 的去重标识版本；当前导出默认格式为 `keer:v1:<hash>`
- `importId` 可选，建议外部导入时提供稳定唯一值（可显著提升去重可靠性）
- `visibility` 可选，缺失时默认 `PRIVATE`
- `latitude` / `longitude`（或 `lat` / `lng`）可选，缺失不会影响导入
- `tags` 可选，可用数组或逗号分隔字符串
- `pinned` / `archived` 可选
- `attachments` 可选（`zip` 导入时生效）

最小可用 JSON 示例（无附件）：

```json
[
  {
    "content": "今天完成了导入功能联调",
    "createdAt": "2026-03-19T10:30:00Z"
  }
]
```

`manifest.json` 示例（位于导出 zip 内）：

```json
{
  "format": "keer.memo.transfer.v2",
  "memos": [
    {
      "importId": "keer:v1:8f1d8b4d...",
      "content": "示例 memo",
      "createdAt": "2026-03-19T10:30:00Z",
      "visibility": "PUBLIC",
      "tags": ["work", "android"],
      "latitude": 31.2304,
      "longitude": 121.4737,
      "attachments": [
        {
          "path": "attachments/memo-0001/001-image.png",
          "filename": "image.png",
          "mimeType": "image/png"
        }
      ]
    }
  ]
}
```

## 运行要求

- Android 8.0 及以上
- `minSdk = 26`
- 一个可访问的 `Keer` 服务端地址

## 快速上手

1. 从 [GitHub Releases](https://github.com/shinyes/KeerAndroid/releases) 下载最新 APK 并安装
2. 打开应用，在登录页选择「连接远程服务」
3. 输入你的 Keer 服务端地址（如 `https://keer.example.com`）
4. 登录或注册账号，应用会自动完成首次全量同步（**最新内容优先展示**）
5. 开始记录、浏览、编辑；离线也能查看和编辑，联网后自动同步

## 与后端版本对应

- 应用当前面向 `v2` 同步协议（`/api/v2/sync/stream`）
- 建议搭配 **Backend v4.1.3 及以上**，以获得初次登录"新→旧"拉取体验
- 更早版本的后端仍可连接，但首次同步按旧→新顺序，体验略差
- 需要管理员功能（设置页"管理员"分组）时，请保持前后端版本接近，并以后端 `ADMIN_USERS` 配置为准

## 安装

### 直接安装

优先使用本仓库 GitHub Releases 中提供的 APK。

### 本地构建

需要环境：

- Android Studio 最新稳定版
- JDK 17
- Android SDK 36

常用命令：

```bash
./gradlew installDebug
./gradlew test
./gradlew lint
./gradlew assembleRelease
```

如果安装了 `just`，也可以使用这些快捷命令：

```bash
just build
just dev
just test
just lint
just release
```

## 开发说明

项目当前主要使用：

- Kotlin
- Jetpack Compose
- Hilt
- Room
- Retrofit + OkHttp
- DataStore

仓库结构：

- `app/`: Android 应用主体代码
- `app/src/main/`: 业务实现、资源文件、Manifest
- `.github/workflows/`: CI 与发布流程
- `gradle/`: Gradle Wrapper 相关文件

## 联调说明

当前应用面向远程 `Keer` 账户，首次进入会要求登录或注册远程账户。

联调前建议确认：

- 服务端地址可被设备访问
- 服务端已经正确配置 `JWT_SECRET`
- 登录、刷新会话、附件上传等接口工作正常

如果你正在和同目录下的服务端仓库一起开发，建议先启动服务端，再通过应用添加远程账户进行联调。

## 管理员功能显示说明

- 设置页“管理员”分组只对 `ADMIN/HOST` 角色账号显示
- 如果后端通过 `ADMIN_USERS` 提升角色，需要重启后端并在 App 内重新登录账号
- 建议使用最新发布版本，以确保角色状态能正确同步到设置页

## 权限说明

应用当前会使用这些权限：

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`

其中网络权限用于远程访问与同步，位置权限用于记录相关的地理位置信息能力。

## 发布

发布工作流位于：

- [`.github/workflows/build-signed-release-apk.yml`](./.github/workflows/build-signed-release-apk.yml)

支持的标签格式：

- `vX.Y.Z`
- `vX.Y.Z-alpha.N`
- `vX.Y.Z-beta.N`

发布前请检查：

1. 更新 [`app/build.gradle`](./app/build.gradle) 中的 `versionName`
2. 同步更新 `versionCode`
3. 确保 Git tag 去掉前缀 `v` 后与 `versionName` 一致

Release 构建依赖以下签名环境变量：

- `ANDROID_SIGNING_STORE_FILE`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

GitHub Actions 会校验版本号、构建签名 APK，并把产物上传到对应的 Release。

## 贡献

欢迎提交 Issue 和 Pull Request。

如果改动涉及登录态、同步、上传链路或数据库结构，建议在提交前至少完成：

- `./gradlew test`
- `./gradlew lint`

另外最好补一轮真机或模拟器联调。

## 许可证

本项目采用 [GPLv3](./LICENSE)。
