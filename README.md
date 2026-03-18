# Keer Android

致谢：本项目在产品思路、交互设计与移动端实现经验上受到了 `memos` 和 `MoeMemosAndroid` 的启发与帮助。

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

## 运行要求

- Android 8.0 及以上
- `minSdk = 26`
- 一个可访问的 `Keer` 服务端地址

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
