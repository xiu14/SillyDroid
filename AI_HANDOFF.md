# STDROID（SillyDroid 自用定制版）AI 交接说明

给接手这个仓库的 AI 看：项目是什么、用户要什么、怎么构建发布、关键机制在哪、哪些坑已经踩过。会随时间变化的状态（当前分支、最近提交、和上游差多少）不写在这里，用下面的命令现查。

## 项目概况

- 仓库：`/root/workspace/SillyDroid`，日常分支 `custom/st-shell`。
- 远端：
  - `origin`：上游 `https://github.com/jialmaster/SillyDroid.git`。
  - `fork`：用户的公开 fork `https://github.com/xiu14/SillyDroid.git`。构建和发布都在 fork 上跑。
- 这是用户自用的 SillyDroid 定制版，目标是把原来 ST Android Shell 的实用功能并进本地运行的 SillyDroid。
- 名字：对用户叫 **STDROID**（App 名、Release 名 `stdroid-v<版本>`）；代码、包名和上游脚本里仍叫 SillyDroid。包名是 `com.jm.sillydroid.debug`，只出 debug 包。
- 手机：用户主力机是 OPPO Find X8（adb 和规矩见 `/root/workspace/连x8.md`）。
- 查当前状态和上游差距：

```bash
git status --short
git log --oneline --decorate -8
git fetch origin --prune --tags
git rev-list --left-right --count custom/st-shell...origin/master   # 左：我们独有，右：上游没合进来的
git log --oneline custom/st-shell..origin/master
```

## 用户偏好

- 功能直接内置到设置页，不要额外网页入口。
- UI 干净，少放不常用选项。设置页默认打开 `Tavern` 标签页。
- 备份、图床、自动回底、日志球、存储分析等功能要稳定实用。
- 进入聊天页要自动回到底部；但用 ST 原生 `/chat-jump` 跳转后，不能被反复拉回底部。
- 图标用 ST Shell 的 `logo1` 风格，透明底，不要黑底、白底、白边框，也不要拉伸。
- 提交信息用中文，格式 `type(scope): 说明`。每个验证通过的逻辑改动单独提交。
- `custom/st-shell` 只在用户要求时才推。本地提交会随构建快照带上 GitHub（见下文），不用为了备份去推，也不要反复提醒它没推。

## 常用构建和验证命令

**构建只走 GitHub Actions。**本机不跑 `./gradlew`。rbuild 已经退役，构建机也没了。入口脚本是 `scripts/stdroid-gha-build.sh`，在仓库根目录执行：

```bash
cd /root/workspace/SillyDroid

# 完整构建 debug APK（stage 1→4），改宿主代码后打包用这个
bash scripts/stdroid-gha-build.sh
bash scripts/stdroid-gha-build.sh --refresh             # 不复用缓存里的 stage 1/3 产物，强制重建 rootfs / server
bash scripts/stdroid-gha-build.sh --tavern-tag latest   # 额外参数原样传给 scripts/build-stdroid-full.sh

# 只跑 Gradle 任务（单测、编译检查），约 1 分钟
bash scripts/stdroid-gha-build.sh gradle \
  :feature:settings:compileDebugKotlin \
  :feature:settings:testDebugUnitTest \
  :feature:main:testDebugUnitTest \
  :data:runtime:testDebugUnitTest \
  :data:settings:testDebugUnitTest
```

- 完整构建的 APK 下载到 `artifacts/releases/android-apk/app-debug.apk`，脚本最后会打印路径、SHA-256 和大小。
- gradle 模式的测试报告（HTML）下载到 `artifacts/ci-reports/<run id>/`。失败时脚本会打印失败步骤日志的末尾。
- 只能用这个入口。不要用上游的 `scripts/build-tavern-android-local.sh`，也不要用 fork 上的 `tavern-upstream-apk`（`.github/workflows/sillydroid-upstream-apk.yml`）。它们按上游 release 计算 versionCode（如 1810001069），装到手机上会挡住之后所有 stdroid 版本的覆盖安装；后者还会在 fork 上建 Release。

### 工作方式

workflow 是 `.github/workflows/stdroid-build.yml`。

1. 脚本用临时 index 把工作区快照成一个 commit，父提交是 HEAD。未提交的改动和没被 gitignore 的新文件都会带上。当前分支、暂存区和工作区都不动。
2. 把快照强推到 fork 的 `ci/stdroid` 分支，触发 workflow。模式和参数写在 commit message 的 trailer 里：
   - `Stdroid-CI-Mode`：`full`、`gradle`、`publish` 或 `keepalive`。
   - `Stdroid-CI-Args`：`full` / `publish` 模式下是 `build-stdroid-full.sh` 的额外参数，`gradle` 模式下是 Gradle 任务。
   - `Stdroid-CI-Changelog-B64`：`publish` 模式的更新说明（base64）。
3. 脚本等构建结束，下载产物。

各模式做什么：

| 模式 | 做什么 | 产物 |
| --- | --- | --- |
| `full` | `scripts/build-stdroid-full.sh --build-type debug` | artifact `stdroid-apk-debug`，保留 3 天，脚本自动下载 |
| `gradle` | `./gradlew -p android-tavern <任务>` | artifact `stdroid-gradle-reports`，保留 3 天 |
| `publish` | 同 `full`，再建 Release（见下一节） | Release `stdroid-v<versionName>` |
| `keepalive` | 只拉一次缓存刷新使用时间，不编译 | 无 |

要注意的事：

- **fork 是公开仓库**，未提交的改动也会推上去，密钥不能放在没被 gitignore 的文件里。`ci/stdroid` 的快照以 HEAD 为父提交，所以本地提交会跟着上 GitHub。
- **同时只跑一个构建**：新推一个构建会取消正在跑的那个（`concurrency` + `cancel-in-progress`）。`keepalive` 单独一组，不会取消构建，也不会被构建取消。
- **被 gitignore 的 assets 不会上传**，包括 `android-tavern/app/src/main/assets` 里的 rootfs、`server-source.zip`、`server-source-manifest.json`。所以 CI 上不能单跑 `:app:assembleDebug`，打 APK 只能走完整流程，rootfs 和 server 由 stage 1/3 现做（有缓存直接复用）。
- **Tavern 版本固定**：CI 上读不到本机 assets 的 manifest，所以 workflow 用 `STDROID_TAVERN_TAG`（当前 `1.18.0`）固定版本，免得悄悄升级。升级 Tavern 时要改这个值。
- **版本号**取 `android-tavern/gradle.properties` 的 `stdroidVersionCode` / `stdroidVersionName`，不按上游规则改写。
- **签名**用仓库里的 `android-tavern/app/signing/debug.keystore`，不需要配 Secret。release 构建不支持。
- **JDK 固定 17**：`ubuntu-latest` 默认是 JDK 25，项目的 Mockito / Robolectric（Byte Buddy）只支持到 Java 22，用 25 的话 `:feature:main:testDebugUnitTest` 会有约 30 个测试失败。
- **耗时**（2026-11 实测，公开仓库的 runner 是 4 核 16G）：触发后几秒内开跑。有缓存时，gradle 模式约 1 分钟，完整构建或发布约 3 分钟；没缓存的完整构建要 11 分钟以上。337MB 的 APK 下载回本机约 2 分钟。
- **缓存**（`actions/cache`）：full 和 gradle 两种模式分开存。full 一份约 1.4GB，存 rootfs、server source、Android 工具链和 Gradle；gradle 一份约 1.1GB，只存 Gradle。构建脚本或 gradle 配置一改就会换 key，之后第一次构建先退回旧缓存，构建完再另存一份。key 里**不要**加 `android-tavern/gradle.properties`：版本号在这个文件里，一加进去，每次发版都会多存两份缓存。
- **缓存上限**：每个仓库 10GB，公开仓库不收费，但超了也不能多存。超出时 GitHub 会删最久没用的那几份，构建不会坏，但空间会被旧缓存占着。
- **缓存保活和清理**：GitHub 会删掉 7 天没用过的缓存。VPS 的 crontab 每天 05:23 跑 `scripts/stdroid-gha-keepalive.sh`，日志在 `artifacts/gha-keepalive.log`。它做两件事：
  - 先清理：full、gradle 两种各留最近用过的一份，其余删掉。有构建在跑时不动。
  - 再保活：缓存 4 天没用时，在 `ci/stdroid` 上追加一个 `Stdroid-CI-Mode: keepalive` 空提交，约 1 分钟跑完。它用的是 `ci/stdroid` 当前提交的 tree，所以最后推上去的快照里必须有带 keepalive 模式的 workflow。

  `--force` 可以手动触发。
- **artifact**：APK 和测试报告只保留 3 天。公开仓库的 artifact 存储不收费，不用管。
- **免费额度**：公开仓库的 Actions 分钟数、Release 存储和下载流量都免费。Release 单个文件要小于 2 GiB。
- **其他 workflow** 都是上游的：`sillydroid-*.yml` 和 `tavern-server-source-release.yml`。它们只在推 master/main 或手动触发时才跑，留着是为了合并上游时不冲突，别去用。

## 发布新版本（应用内更新）

App 从 fork 的 GitHub Release 检查更新：`https://github.com/xiu14/SillyDroid/releases/latest/download/version.json`（`android-tavern/app/build.gradle.kts` 的 `SILLYDROID_LATEST_RELEASE_METADATA_URL`）。filesite（`files.biatch.party`）已经删掉了，不要再往那里发。

发布前先向用户确认 `versionCode`、`versionName` 和更新说明，缺哪个问哪个，不要猜。然后：

```bash
# 1. 递增版本号并提交（versionCode 必须大于线上值，否则 App 不会提示更新）
#    android-tavern/gradle.properties：stdroidVersionCode=<code>、stdroidVersionName=<name>
git commit -am "chore: 版本号升到 <name>"

# 2. 发布：GHA 完整构建 → 建 Release stdroid-v<name>（APK + version.json）→ 设为 latest
bash scripts/stdroid-gha-build.sh publish --changelog '修复 xxx；新增 yyy'
bash scripts/stdroid-gha-build.sh publish --changelog-file notes.md   # 多行说明
```

- 构建前会检查三项：工作区必须干净；`versionCode` 必须大于线上 `version.json` 的值；同名 tag 不能已存在。脚本和 workflow 各查一遍，任何一项不满足都会在构建前停下。
- Release 先建成草稿，APK 和 `version.json` 传完才正式发布并设为 latest。所以 App 不会拿到一个指向还没传完的 APK 的版本。
- 发布后脚本会等 `releases/latest` 的跳转切到新版本（最多 60 秒），再打印线上的 `version.json`。
- `version.json` 的字段：`versionCode`、`versionName`、`apkKey`（Release 资产名）、`apkUrl`（固定 tag 的下载地址）、`size`、`sha256`、`changelog`。它沿用以前 filesite 的格式，由 `FilesiteVersionParser` 解析，`sha256` 由 workflow 计算。
- 撤回某个版本：`gh release delete stdroid-v<name> -R xiu14/SillyDroid --cleanup-tag`，latest 会回落到上一个 Release。也可以用 `gh release edit <上一个 tag> -R xiu14/SillyDroid --latest` 把上一个版本重新设为 latest。
- Release 的 tag 指向 `ci/stdroid` 上的快照提交，内容和当时的 HEAD 一致。
- 不发版、只想装到手机上测时，用完整构建拿到 APK，再按 `/root/workspace/连x8.md` 用 adb 装。装之前要先问用户。

### 应用内更新的安装方式：本机 adb 自安装

ColorOS 用系统安装器装未认证开发者的 APK 时，每次都要确认风险、输入密码；用 adb（shell 身份）装不走这套检测。所以下载完、SHA-256 校验通过后，App 自己当 adb 客户端去连本机 adbd 安装。代码在 `data/update` 的 `LocalAdbClient` / `AdbSelfInstaller.kt`，调用方是 `AppUpdateDownloadService` 和关于页的「安装」按钮。

- 连 `127.0.0.1:5555`。X8 的 adbd 常开 TCP 5555，手机重启后要先 `x8up` 才有（见 `/root/workspace/连x8.md`）。连不上、授权被拒或超时，就退回系统安装器和「可安装」通知。
- 第一次连会弹「允许 USB 调试」，勾选「一律允许」后不再弹。认证私钥放在 Android Keystore（alias `stdroid-self-update-adb`，不可导出），因为酒馆服务端插件和 App 是同一个 uid，私钥要是落成文件，插件就能读走它拿到 adb shell。在开发者选项里「撤销 USB 调试授权」，会把它和 VPS、Termux 的授权一起清掉。
- 必须走 **shell_v2**（`shell,v2,raw:`）。老式 `shell:` 原始通道在连接断开时，adbd 会把这次会话起的后台进程全杀掉，setsid / nohup 都拦不住。
- 必须**经管道**交给 `pm install -S <大小>`。`pm install <路径>` 是 system_server 自己按路径打开文件，SELinux 不让它读 `/storage/emulated`（fuse）。
- 命令先在 shell 端重算 SHA-256，对上了才用 `setsid nohup` 在后台执行 `pm install`，装完用 `am start` 重新打开 App。
- 日志：手机上的安装日志在 `/data/local/tmp/stdroid-adb-install.log`。App 侧的事件记在 host 诊断日志的 `category=app_update` 下，有 `adb_install_started`、`adb_install_unavailable`、`adb_install_not_finished` 三种。
- 交给 adb 后服务等 120 秒，进程还活着就说明没装上，退回「可安装」通知。X8 上实测从交给 adb 到装完约 2 秒。
- 安装逻辑只在**执行安装的那个版本**里生效。改了这块代码后，要先用 VPS 的 adb 把新版本直接装到手机上（先征得用户同意），再发一个更高的版本，才能在 App 里测到改动。

## 关键机制

### ST Shell 功能迁移

已经内置：ST shell bridge 扩展、`xiu14/1` 相关内置扩展资源、流体云 / 虚拟流体云、代理图床、静音提示音处理、进入聊天页自动回底、日志球、字体拦截、站点登录信息。悬浮按钮只保留设置、导出日志、刷新、立即备份，去掉了「在浏览器打开」和反馈。Android 宿主扩展面板默认关闭。

流体云和虚拟流体云的生成状态来自内置扩展 `android-tavern/extensions/tavern-native-bridge`，它监听 ST 的生成事件，再转给 Android（事件时序见它的 README）：

- 「准备中」：发送按钮被按下，或收到 `GENERATION_AFTER_COMMANDS`。
- 「在想了」：`/api/.../generate` 的响应头到达，也就是首包。
- 「在写了」：流式文本里出现 `<content>`。
- 结束：`GENERATION_ENDED` 或 `GENERATION_STOPPED`。

酒馆助手 `generateRaw({ should_silence: true })` 这类静默后台生成（比如自动范围总结），会发 `GENERATION_AFTER_COMMANDS`，但永远不会发 `GENERATION_ENDED`，所以桥接靠 `js_generation_requested` 把它们整轮忽略掉。以后改桥接，别把这一点改坏了，否则流体云会一直挂着下不去。

### 设置页

标签顺序：`Tavern / 备份 / 存储 / 酒馆设置 / 扩展/插件 / 高级 / 终端 / 日志`。默认打开 `Tavern`。日志球开关和上游的「启动模式」都放在 `Tavern`。旧的 APP 设置页现在叫 `高级`，放在靠后的位置。改设置页要同步更新 `SettingsTabTest`。

相关文件在 `feature/settings` 下：`model/SettingsTab.kt`、`model/SettingsActivityUiState.kt`、`ui/screen/BootstrapSettingsScreenController.kt`、`res/layout/activity_bootstrap_settings.xml`、`res/values/strings.xml`。

### 备份

- 远程备份服务是 `android-tavern/app/src/main/assets/bootstrap/sidecars/st-remote-backup/server.js`。设置页内置了全部操作：立即备份、自动备份开关、R2 配置、备份日志 / 列表 / 恢复，不需要打开 `8787` 网页。
- 支持本地和 R2 两种存储。配了 R2 时以 R2 为准，本地只做必要的中转或短期副本，避免长期堆积。
- Kotlin 入口：`domain/backup/RemoteBackupRepository.kt`、`data/settings/RemoteBackupRepositoryImpl.kt`、`feature/settings/ui/backup/BootstrapSettingsBackupCoordinator.kt`。
- 备份、聊天记录、角色、世界书相关的删除功能要非常谨慎，默认只做安全清理。

### 存储占用分析

`存储` 标签页显示各部分的占用：APP 私有数据、ST 用户数据、聊天记录、角色、素材、世界书、扩展、程序目录、离线 runtime、日志、WebView、图床缓存、临时文件、本地备份。

只有安全项目给清理按钮：日志、代理图床缓存、临时文件、WebView 数据、本地备份副本。聊天记录、角色、世界书、程序目录、离线 runtime 这些只显示，不提供清理。

代码：`domain/storage/StorageUsageRepository.kt`、`data/settings/StorageUsageRepositoryImpl.kt`、`feature/settings/ui/storage/BootstrapSettingsStorageCoordinator.kt`。

### 冷启动加速

目标是缩短「点开图标 → 酒馆可用」的时间。

宿主侧（`data/runtime` 的 `HostRuntime.kt`、`BootstrapSessionManager.kt`）：

- `HealthProbe.awaitReady` 每 1 秒一轮，轮内每 100ms 探测一次，Node 一开始监听就能发现。
- `AssetExtractor.prepareHostExtensionAssets` 用 `bootstrap/.host-asset-sync-stamp` 记录「APK 安装标识 + 脚本与 host 扩展的文件清单」。都没变就跳过那近千个文件的重写。
- `RootfsRuntimeProvisioner.ensure` 用 `rootfs/.rootfs-runtime-verified-stamp` 记录上次校验通过时的状态，没变就不再跑 `ensure-rootfs-runtime.sh`。stamp 是 v2 格式：文件身份一节，加上内容 SHA-256 一节。App 升级后安装路径变了、文件身份对不上时，只要内容哈希一致，也会跳过校验。手动重试、崩溃自动重启、启动失败后都会重新完整校验。
- 启动会话不等前台服务：`MainActivity.onCreate` 一开头就调用 `processManager.start(false)`。`DefaultHostProcessManager` 在前台服务启动请求成功后，直接从 `BootstrapSessionHost`（进程内唯一的 `BootstrapSessionManager`）开始会话；`StartupCoordinatorService` 拿同一个实例，只负责前台通知和保活。以前会话管理器建在服务的 `onCreate` 里，要等 Activity 创建完、第一帧（含 WebView 初始化）画出来才轮到，Node 因此晚了约 0.4 秒。重试仍只走服务。
- server 覆盖层用 `bootstrap/server/.sillydroid-server-overlay-stamp` 记两边文件的大小和修改时间，两边都没变就不读文件比对。
- 前端启动时间线：内置扩展 `tavern-native-bridge` 在 `APP_READY` 时往宿主诊断日志写 `event=tavern_startup_timeline`，外加资源明细（格式见它的 README）。X8 热启动实测：点开到能用约 6 秒，其中酒馆前端初始化约 4.4 秒，Node 段约 1.2 秒。所以继续优化 Node（端点懒加载、打包单文件）的收益有限。
- 启动脚本链（`start-server.sh` → `termux-host-runtime.sh` → `tavern-entrypoint.sh`）每次冷启动都要跑。Android 上 fork/exec 一个小命令约 15～20ms，所以热路径只用 shell 内置命令：用 `test -ef` 检查软链接、用 `read` 读文件、目录已存在就不 `mkdir`、没变化的步骤直接跳过。备份 sidecar 在酒馆 Node 拉起之后才启动。改这几个脚本时别把 `$(readlink)`、`$(cat)` 或无条件的 `rm`/`ln` 加回热路径。
- 升级后第一次启动以前要 8～9 秒，不是 compile cache 的问题（实测命中）。原因是 APK 升级后 nativeLibraryDir 换了路径，`install_git_core_links` 的签名对不上，要重建 150 多个 git 命令链接。shell 每建一个要起 3 个进程，合计约 6 秒。现在改由 `scripts/sync-git-core-links.cjs` 在一个 Node 进程里批量重建（X8 上约 0.2 秒），失败时退回原来的 shell 循环。剩下的约 3 秒是 Node 从闪存现读新 APK 里的二进制和模块文件。

Node 侧（`app/src/main/assets/bootstrap/scripts/tavern-startup-preload.cjs`，由 `tavern-entrypoint.sh` 用 `node --require` 挂到 server 进程）：

- 打开 compile cache，并在启动后主动 flush。
- 把 `vectra`、`sillytavern-transformers`、`archiver` 换成惰性桩，第一次用到时才加载。升级内置 Tavern 后惰性桩默认不生效：`LAZY_SUPPORTED_TAVERN_VERSIONS` 里只列了验证过的版本。要先核对上游 `src/endpoints/vectors.js`、`src/users.js`、`src/transformers.js` 对这三个包的用法没变，再把新版本号加进去。
- 前端库产物已存在时跳过 webpack 编译。

- 首页先按用户主题上色：ST 默认的 `--SmartThemeBlurTintColor` 近乎黑色，加载画面和 `body` 都用它，要等前端 JS 读完设置才换成用户主题，浅色主题会先黑一下。所以给 `src/server-main.js` 返回 `index.html` 的那行加了钩子：按当前用户 `settings.json` 的 `power_user` 颜色生成 `:root` 变量，插在 `style.css` 之后、`user.css` 之前，不压过 `user.css` 和主题自定义 CSS。锚点对不上、读不到设置或出错时，交还上游原样返回。启动日志的 `event=ready` 一行里有 `boot_theme=on`，第一次注入成功时还会打一行 `event=boot_theme_injected`。

`server-source.zip` 仍是原样的上游源码，这些都是进程内处理。排查问题时，设置环境变量 `SILLYDROID_STARTUP_PRELOAD=off` 可以整体关闭预加载。

### 启动画面配色跟随酒馆主题

App 主题继承 `DayNight`，跟随系统深浅色；没有写死深色模式。系统开深色时，启动遮罩和浏览器占位底色是 `#111827`，用户用浅色主题时，启动会先暗一下。所以：

- `tavern-native-bridge` 在 `APP_READY`、`SETTINGS_LOADED_AFTER`、`SETTINGS_UPDATED` 之后读出 `--SmartThemeBlurTintColor`，换算成不透明的 `#RRGGBB`，颜色变了才上报 `page_theme_background`。
- `MainActivity` 收到后存进 SharedPreferences `sillydroid_startup_surface`，代码在 `feature/main/.../ui/home/startup/StartupSurfacePalette.kt`。
- 下次启动时，启动遮罩、浏览器占位底色和系统栏都用这个颜色。文字按底色亮度取日间或夜间那套 `bootstrap_overlay_*` 颜色；图标按钮的底色和描边从底色混出来。
- 还没上报过时，保持按系统深浅色的默认配色。
- 系统启动窗口（`windowBackground`）是主题里静态定义的，没法跟着变，不过只闪一瞬间。
- 配合上文冷启动加速里的「首页先按用户主题上色」，整个启动过程是同一个底色。

### server 覆盖层（升级后保留对内置酒馆程序文件的改动）

App 自带的酒馆程序在 `files/android-tavern/bootstrap/server`（`paths.serverDir`）。升级包里的酒馆源码一变（`server-source-manifest.json` 不同），`prepareServerArchives()` 就会把源码包覆盖解压进去，同名文件直接被换回原版。解压时唯一跳过的是根目录的 `config.yaml`。所以**不要直接改 `bootstrap/server` 里的文件**，改动放进覆盖层：

- **位置**：`files/android-tavern/data/server/overrides/`，也就是 `paths.serverDataDir/overrides`、`stdroid` 远程编辑根目录下的 `overrides/`。结构跟 `bootstrap/server` 一一对应，例如 `overrides/public/img/logo.png` 对应 `bootstrap/server/public/img/logo.png`。
- **时机**：每次启动，server 资产准备完之后、启动 Node 之前铺一遍，不管这次有没有重新解压。内容相同的文件跳过，不会重写。目标不存在时会连目录一起新建。
- **限制**：
  - 只接受 `public/` 下的普通文件。
  - 不跟随符号链接。覆盖层里的链接、`bootstrap/server` 里作为上级目录的链接（如 `public/scripts/extensions/third-party`）、本身是链接的目标文件，都会被拒绝。
  - 拒绝 `..`、绝对路径、空段这类越界路径。
  - 不碰 `config.yaml`、`node_modules`、`third-party`。
  - 单个文件出错只记原因，整体出错只记日志，都不影响启动。
- **日志**：每次启动，启动日志 `files/android-tavern/logs/startup-*.log` 里都会写一行 `server 覆盖层：…`，列出覆盖了哪些、内容相同跳过哪些、拒绝了哪些及原因。没有 `overrides/` 目录时写「不存在，跳过」。
- **用法**：`stdroid put <本地文件> overrides/public/<路径> --no-reload`，然后重启 App。只刷新页面不会生效，覆盖层只在启动时铺。
- **撤销**：删掉 `overrides/` 里的文件，只是以后不再覆盖；`bootstrap/server` 里已经铺过去的那份不会自动还原。要么手动放回原版，要么等下次酒馆源码变化时被解压换回。
- **实现**：`data/runtime/.../ServerOverlay.kt` 里的 `applyServerOverlay`、`resolveServerOverlayTarget`，由 `BootstrapSessionManager.runBootstrapAttempt` 调用。测试在 `ServerOverlayTest`。

### 图标和应用名

- App 名 `STDROID`。
- 资源：`app/src/main/res/drawable/ic_launcher.png`、`ic_launcher_foreground.png`、`ic_launcher_background.xml`、`drawable-anydpi-v26/ic_launcher.xml`。
- `ic_launcher_background.xml` 必须保持透明：`<solid android:color="@android:color/transparent" />`。改了图标后，记得检查 adaptive background。
- 部分桌面会给透明的 adaptive icon 自动加底色或阴影，这不是 APK 资源的问题。

### Stdroid JS-Slash-Runner 导入 API

手机上的 8788 本机导入 API 只支持全局脚本：

```bash
curl -f -X PUT \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/javascript" \
  --data-binary "@model-direct-chatbox.js" \
  "http://127.0.0.1:8788/api/import/js-slash-runner/global/model-direct-chatbox.js?displayName=%E6%A8%A1%E5%9E%8B%E7%9B%B4%E8%BF%9EChatbox"
```

- URL 末尾的文件名必须是稳定的英文 key，只能用字母、数字、点、下划线、短横线，并且以 `.js` 结尾。
- `displayName` 是第一次创建脚本时在 JS-Slash-Runner 里显示的中文名，需要 URL encode。
- 重复导入同一个文件名，会按 `data.stdroidImportKey` 替换同一个脚本，保留旧脚本的 `id`、启用状态和用户手动改过的 `name`。旧脚本没有 `stdroidImportKey` 时，会尝试按英文名或 `displayName` 接管一次。
- 返回 `reloadRequired: true` 时，要刷新 Tavern 页面，让 JS-Slash-Runner 重新读取脚本。
- 远程读写手机上 Stdroid 的内容（脚本、主题、角色卡等）用 `stdroid` CLI，见 `/root/workspace/stdroid-remote/README.md`。

## 常见问题和排查方向

### rootfs 或 runtime 资产缺失

```text
FileNotFoundException: bootstrap/rootfs/rootfs-fs.zip
```

通常是 APK assets 里没打进 rootfs，或者同步 runtime image 时漏了资源。

### native 依赖缺失

```text
CANNOT LINK EXECUTABLE ".../libtermux-node.so": library "libcares.so" not found
```

说明 nativeLibraryDir 里缺了 node 依赖的 so，先查 `android-tavern/app/src/main/jniLibs` 和打包产物。

### 透明图标仍显示底色

先确认 `ic_launcher_background.xml` 是透明的，`ic_launcher.png` 和 `ic_launcher_foreground.png` 的四角 alpha 为 0。APK 资源都正常、桌面上仍有底色的话，通常是系统 launcher 在处理 adaptive icon。

### 修改 SillyTavern 根目录 public/img/logo.png 不生效

现在的做法：把图放进 server 覆盖层（见上文「server 覆盖层」），然后重启 App，不要直接改 `bootstrap/server/public/img/logo.png`。直接改的话，下次升级只要酒馆源码有变化，就会被换回原版。

用户的开屏 logo 是一只会动的透明底小象，格式是 APNG，扩展名仍是 `.png`。源文件在 VPS 的 `/root/workspace/st开发区/小象素材/elephant-idle-transparent.apng.png`：

```bash
stdroid put "/root/workspace/st开发区/小象素材/elephant-idle-transparent.apng.png" overrides/public/img/logo.png --no-reload
# 重启 App 后，启动日志 startup-*.log 里应有一行 server 覆盖层：…，logo 出现在「已覆盖」或「内容相同跳过」里
```

改了还是不生效时，按顺序查：

- 启动日志里 `server 覆盖层：` 那一行：是被拒了（原因写在括号里），还是覆盖层路径写错了。用户曾把 `public` 写成 `pubic`。
- 用 `run-as` 比对 `bootstrap/server/public/img/logo.png` 和 `data/server/overrides/public/img/logo.png` 的 md5。
- WebView 缓存。
- 实际加载的 URL 是不是这张图。

## 操作注意事项

- 不要用 `git reset --hard` 或 `git checkout --` 回滚用户的改动，除非用户明确要求。
- 合并上游前先 `git fetch origin --prune --tags`。合并后跑一遍 gradle 单测，上游改动常让定制部分的测试挂掉。
- 用户要求推送 `custom/st-shell` 时，用 SSH，不要用明文 token：

```bash
GIT_SSH_COMMAND='ssh -F /config/.ssh/config' git push github-xiu14:xiu14/SillyDroid.git custom/st-shell
```

- 不要在文档、提交信息或命令输出里暴露用户提供过的 token。
