# SillyDroid 自定义版 AI 交接说明

这份文档给后续接手的 AI 使用。目标是从零了解当前仓库状态、用户定制点、常用命令和容易踩坑的位置。

## 当前仓库状态

- 工作目录：`/root/workspace/SillyDroid`
- 当前分支：`custom/st-shell`
- 上游仓库：`origin = https://github.com/jialmaster/SillyDroid.git`
- 用户 fork：`fork = https://github.com/xiu14/SillyDroid.git`
- 当前已合并上游版本：`sillydroid-v1.0.1.61-1.18.0-release`
- 最近本地提交：
  - `1fccf0f Fix upstream WebView unit test compatibility`
  - `35a8359 Merge remote-tracking branch 'origin/master' into custom/st-shell`
  - `8e0c6df Add storage settings and refine launcher icon`
  - `bd67617 Add integrated remote backup controls`

推送用户 fork 时优先用 SSH：

```bash
GIT_SSH_COMMAND='ssh -F /config/.ssh/config' git push github-xiu14:xiu14/SillyDroid.git custom/st-shell
```

不要在文档、提交信息或命令输出里暴露用户曾提供过的 token。

## 用户想要的方向

这是用户自用的 SillyDroid 定制版，目标是把原来 ST Android Shell 的实用功能合进本地运行的 SillyDroid。

用户偏好：

- 功能直接内置到设置页，不要额外网页入口。
- UI 尽量干净，少放不常用选项。
- `Tavern` 标签页作为设置默认入口。
- 备份、图床、自动回底、日志球、存储分析等功能要稳定实用。
- APK 名字目前是 `STDROID`，图标使用 ST Shell 的 `logo1` 风格，透明底，不要黑底、白底或明显拉伸。

## 已经做过的主要改动

### ST Shell 功能迁移

已经内置或迁移过：

- ST shell bridge 扩展。
- `xiu14/1` 相关内置扩展资源。
- 流体云/代理图床相关能力。
- 静音提示音处理。
- 进入聊天页自动回底逻辑。
- 悬浮按钮精简：
  - 保留设置、导出日志、刷新、立即备份等实用按钮。
  - 删除“在浏览器打开”。
  - 删除反馈相关功能和按钮。
- Android 宿主扩展面板默认关闭。

自动回底要注意：用户确认过，希望进入聊天页时自动回到底部；如果使用 SillyTavern 原生的 `chat-jump`，不应该被反复拉回底部。

### 备份功能

远程备份服务位于：

```text
android-tavern/app/src/main/assets/bootstrap/sidecars/st-remote-backup/server.js
```

设置页已内置备份控制，不再要求用户打开 `8787` 网页操作。当前逻辑包含：

- 立即备份按钮。
- 自动备份开关。
- R2 配置弹窗。
- 备份日志/备份列表/恢复入口。
- 支持本地和 R2。

用户曾问过“R2 有了是否还需要本地备份”。当前倾向是：R2 配置存在时，主要以 R2 为准，本地只作为必要中转或短期副本，避免长期堆积。

相关 Kotlin 入口主要在：

```text
android-tavern/domain/src/main/kotlin/com/jm/sillydroid/domain/backup/RemoteBackupRepository.kt
android-tavern/data/settings/src/main/kotlin/com/jm/sillydroid/data/settings/RemoteBackupRepositoryImpl.kt
android-tavern/feature/settings/src/main/kotlin/com/jm/sillydroid/feature/settings/ui/backup/BootstrapSettingsBackupCoordinator.kt
```

### 设置页重组

设置页标签顺序已经改为：

```text
Tavern / 备份 / 存储 / 酒馆设置 / 扩展/插件 / 高级 / 终端 / 日志
```

默认打开 `Tavern`，不是原来的 APP 设置页。

相关文件：

```text
android-tavern/feature/settings/src/main/kotlin/com/jm/sillydroid/feature/settings/model/SettingsTab.kt
android-tavern/feature/settings/src/main/kotlin/com/jm/sillydroid/feature/settings/model/SettingsActivityUiState.kt
android-tavern/feature/settings/src/main/kotlin/com/jm/sillydroid/feature/settings/ui/screen/BootstrapSettingsScreenController.kt
android-tavern/feature/settings/src/main/kotlin/com/jm/sillydroid/feature/settings/ui/terminal/TerminalPageController.kt
android-tavern/feature/settings/src/main/res/layout/activity_bootstrap_settings.xml
android-tavern/feature/settings/src/main/res/values/strings.xml
```

`日志球` 开关已从原 APP 设置页移动到 `Tavern` 标签页。旧的 APP 设置页现在是 `高级`，位置靠后，尽量不打扰用户。
上游新增的“启动模式”也放在 `Tavern` 标签页，方便直接切换快速启动相关行为。

### 存储占用分析

新增了 `存储` 标签页，用于显示和清理安全项目。

相关文件：

```text
android-tavern/domain/src/main/kotlin/com/jm/sillydroid/domain/storage/StorageUsageRepository.kt
android-tavern/data/settings/src/main/kotlin/com/jm/sillydroid/data/settings/StorageUsageRepositoryImpl.kt
android-tavern/feature/settings/src/main/kotlin/com/jm/sillydroid/feature/settings/ui/storage/BootstrapSettingsStorageCoordinator.kt
```

显示项包括：

- APP 私有数据
- ST 用户数据
- 聊天记录
- 角色
- 头像/背景/素材
- 世界书
- 扩展/插件
- ST 程序目录
- 离线 Linux runtime
- 日志
- WebView 数据
- 代理图床缓存
- 临时文件
- 本地备份

清理按钮只给安全项目：

- 日志
- 代理图床缓存
- 临时文件
- WebView 数据
- 本地备份副本

聊天记录、角色、世界书、程序目录、离线 runtime 等高风险项目只显示，不提供直接清理。

### 冷启动加速

目标是缩短「点开图标 → 酒馆可用」的时间，改动分两头：

宿主侧（`android-tavern/data/runtime/.../HostRuntime.kt`、`BootstrapSessionManager.kt`）：

- `HealthProbe.awaitReady` 在每 1 秒一轮内以 100ms 间隔探测，Node 一开始监听就能发现。
- `AssetExtractor.prepareHostExtensionAssets` 用 `bootstrap/.host-asset-sync-stamp` 记录「APK 安装标识 + 脚本与 host 扩展的文件清单」，都没变就跳过那近千个文件的重写。
- `RootfsRuntimeProvisioner.ensure` 用 `rootfs/.rootfs-runtime-verified-stamp` 记录上次校验通过时的二进制和系统版本，没变就不再跑 `ensure-rootfs-runtime.sh`；手动重试、崩溃自动重启、启动失败后都会重新完整校验。

Node 侧（`android-tavern/app/src/main/assets/bootstrap/scripts/tavern-startup-preload.cjs`，由 `tavern-entrypoint.sh` 用 `node --require` 挂到 server 进程）：

- 打开 compile cache 并在启动后主动 flush。
- 把 `vectra`、`sillytavern-transformers`、`archiver` 换成惰性桩，第一次用到才加载。
- 前端库产物已存在时跳过 webpack 编译。

`server-source.zip` 仍是原样的上游源码，这些都是进程内处理。排查问题时设置环境变量 `SILLYDROID_STARTUP_PRELOAD=off` 可整体关闭预加载。

### 图标和应用名

用户要求：

- APP 名称：`STDROID`
- 图标使用 ST Shell 的 `logo1` 感觉。
- 不要拉伸。
- 不要黑底、白底、白边框。
- 要透明底。

相关资源：

```text
android-tavern/app/src/main/res/drawable/ic_launcher.png
android-tavern/app/src/main/res/drawable/ic_launcher_foreground.png
android-tavern/app/src/main/res/drawable/ic_launcher_background.xml
android-tavern/app/src/main/res/drawable-anydpi-v26/ic_launcher.xml
```

当前 `ic_launcher_background.xml` 应保持透明：

```xml
<solid android:color="@android:color/transparent" />
```

注意：部分安卓桌面会给透明 adaptive icon 自动加底色或阴影，这不是 APK 资源里的黑底。

## 上游合并记录

已经合并官方上游两个提交：

- `84714c0 Harden bootstrap startup and WebView compatibility dialogs`
- `3ecba38 fix(android): 修复子进程环境继承兼容性问题`

合并后补过一个测试兼容提交：

- `1fccf0f Fix upstream WebView unit test compatibility`

这个提交做了：

- `feature/main` 增加 `androidx.test:core:1.6.1` 测试依赖。
- Robolectric 测试 SDK 从 35 调到 34，适配当前 `robolectric:4.13`。
- 调整 `WebViewCompatibilityHintDialogMessagePolicy` 中禁用长按的顺序，避免设置 listener 后 `isLongClickable` 又被置回 true。

## 常用构建和验证命令

构建一律走远程构建机（`rbuild`，说明见 `/root/workspace/android-remote-build/README.md`），不要在本机跑 `./gradlew`。在仓库根目录执行：

```bash
cd /root/workspace/SillyDroid
```

设置页和相关单测：

```bash
rbuild -p android-tavern \
:feature:settings:compileDebugKotlin \
:feature:settings:testDebugUnitTest \
:feature:main:testDebugUnitTest \
:data:runtime:testDebugUnitTest \
:data:settings:testDebugUnitTest
```

日常打 debug APK（直接用工作区 assets 里现有的 rootfs / server / jniLibs，只改宿主代码时用这个）：

```bash
rbuild -p android-tavern :app:assembleDebug
```

产物会拉回 Gradle 原始路径，直接使用，不再额外复制或改名：

```text
/root/workspace/SillyDroid/android-tavern/app/build/outputs/apk/debug/app-debug.apk
```

完整流程（重建 rootfs、server source 等 stage 1→4，比如要换 SillyTavern 版本时）：

```bash
rbuild exec bash scripts/build-stdroid-full.sh --build-type debug                # Tavern 版本沿用当前 assets
rbuild exec bash scripts/build-stdroid-full.sh --build-type debug --tavern-tag latest
```

- 不要直接用上游的 `scripts/build-tavern-android-local.sh`：它会按上游 release 计算 versionCode（如 1810001069），覆盖 `gradle.properties` 的 `stdroidVersionCode`，装到手机后会挡住后续版本的覆盖安装。
- 产物：`artifacts/releases/android-apk/app-debug.apk`。stage 1/3 产物留在构建机的 `artifacts/` 里复用，`--refresh` 强制重建。
- 完整流程打出的 APK 里的 rootfs/server 来自构建机，不会回写本机 `android-tavern/app/src/main/assets`。如果用完整流程换了 Tavern 版本，之后的日常 `:app:assembleDebug` 仍会打进本机 assets 里的旧版本。
- rbuild 同步规则在仓库根目录 `.rbuild.env`（同步 `.git`、保留远程 `artifacts/`、拉回 APK、自动安装 binutils 等系统包）。

## Stdroid JS-Slash-Runner 导入 API

当前 8788 本机导入 API 只支持全局脚本：

```bash
curl -f -X PUT \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/javascript" \
  --data-binary "@model-direct-chatbox.js" \
  "http://127.0.0.1:8788/api/import/js-slash-runner/global/model-direct-chatbox.js?displayName=%E6%A8%A1%E5%9E%8B%E7%9B%B4%E8%BF%9EChatbox"
```

- URL 末尾的 `filename` 必须是稳定英文 key，只允许字母、数字、点、下划线、短横线，并要求 `.js` 结尾。
- `displayName` 是 JS-Slash-Runner 里首次创建脚本时显示的中文名，需要 URL encode。
- 重复导入同一个 `filename` 会按 `data.stdroidImportKey` 替换同一脚本，并保留旧脚本 `id`、启用状态和用户手动改过的 `name`。
- 如果旧脚本没有 `stdroidImportKey`，导入时会尝试按英文名或 `displayName` 接管一次。
- 返回 `reloadRequired: true` 时，刷新 Tavern 页面让 JS-Slash-Runner 重新读取脚本。

## 常见问题和排查方向

### rootfs 或 runtime 资产缺失

用户之前遇到过：

```text
FileNotFoundException: bootstrap/rootfs/rootfs-fs.zip
```

这通常说明 APK assets 里 rootfs 没打进去，或同步 runtime image 过程漏了资源。

### native 依赖缺失

用户之前遇到过：

```text
CANNOT LINK EXECUTABLE ".../libtermux-node.so": library "libcares.so" not found
```

这说明 Android nativeLibraryDir 中缺少 node 依赖 so，优先检查 `android-tavern/app/src/main/jniLibs` 和构建打包产物。

### 透明图标仍显示底色

先确认：

- `ic_launcher_background.xml` 是透明。
- `ic_launcher.png` 和 `ic_launcher_foreground.png` 四角 alpha 为 0。

如果 APK 资源正常但桌面仍有底色，通常是系统 launcher 自动处理 adaptive icon。

### 修改 SillyTavern 根目录 public/img/logo.png 不生效

可能原因：

- WebView 缓存。
- 文件路径写错，用户曾把 `public` 写成 `pubic`。
- SillyTavern 前端资源有构建产物或缓存版本。
- APP 内外目录不是同一份。

优先让用户清 WebView 缓存、确认路径、确认实际加载 URL。

## 操作注意事项

- 不要使用 `git reset --hard` 或 `git checkout --` 回滚用户改动，除非用户明确要求。
- 推送前先 `git status --short`。
- 合并上游前先 `git fetch origin --prune --tags`。
- 用户 fork 推送优先 SSH，不要使用明文 token。
- 构建成功后直接使用 `android-tavern/app/build/outputs/apk/debug/app-debug.apk`。
- 修改设置页时要同步更新 `SettingsTabTest`。
- 修改图标后务必检查 adaptive background。
- 备份、聊天记录、角色、世界书相关删除功能要非常谨慎，默认只做安全清理。
- 升级内置 SillyTavern 版本后，惰性桩默认不生效（`tavern-startup-preload.cjs` 里的 `LAZY_SUPPORTED_TAVERN_VERSIONS` 只列了验证过的版本）。要先核对上游 `src/endpoints/vectors.js`、`src/users.js`、`src/transformers.js` 对这三个包的用法没变，再把新版本号加进去。

## 快速接手流程

1. 进入仓库：

```bash
cd /root/workspace/SillyDroid
```

2. 看当前状态：

```bash
git status --short
git branch --show-current
git log --oneline --decorate -8
```

3. 如需检查上游：

```bash
git fetch origin --prune --tags
git rev-list --left-right --count custom/st-shell...origin/master
git log --oneline custom/st-shell..origin/master
```

4. 改完后至少跑：

```bash
rbuild -p android-tavern :app:assembleDebug
```

5. APK 产物路径：

```text
/root/workspace/SillyDroid/android-tavern/app/build/outputs/apk/debug/app-debug.apk
```

6. 用户要求推送时：

```bash
GIT_SSH_COMMAND='ssh -F /config/.ssh/config' git push github-xiu14:xiu14/SillyDroid.git custom/st-shell
```
