# 自在跑

在 PICO 共享空间放一座越野沙盘。林溪和陆野会自动巡跑。看向地面捏合可以插旗带路，看向金色五角星插旗可以收集它。一天内首次集齐三颗星记一次打卡，累计 1、3、7 天分别解锁晨光、溪蓝和暮紫。已解锁的彩色徽章支持捏住左右旋转。

## 运行

工程由 PICO CLI 的原生 Volume 模板起步。包名为 `com.example.terrainrunner`，版本为 0.3.1（9）。测试设备为 PICO B3110。构建环境为 Android SDK 35、PICO Spatial BOM 6.2.8、NDK 27.2.12479018、CMake 3.22.1 和 arm64-v8a。需能访问工程声明的 Maven 仓库或具备对应的依赖缓存。通过 ANDROID_HOME 或 local.properties 配置 SDK 路径。

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug assembleRelease
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --replace
pico-cli app launch com.example.terrainrunner
```

使用 `./gradlew testDebugUnitTest connectedDebugAndroidTest` 运行本地与设备测试。Release 默认输出未签名 APK，签名由发布主体提供。

## 项目结构

- `app/src/main/assets/ridge` 放地形、独木桥等通行参数、林溪的蒙皮模型与接触网格。`stride` 放陆野的模型及专用接触数据。`badges` 放三枚金属徽章。
- `app/src/main/assets/pfnn` 包含十组线性插值相位权重、标准化参数、骨架父子表与原始许可。模型以 60 Hz 运行。共享空间的显示时钟与模拟时钟独立推进。
- `app/src/main/java/com/example/terrainrunner` 包含地形、PFNN、巡跑、插旗和寻星。`content` 包含前沿玩法面板与上方数据板。`app/src/main/cpp` 包含原生推理实现。
- `art/identity` 保存本版图标的两张 ImageGen 原图。`tools/pocket_identity.py` 用 Pillow、NumPy、SciPy 导出 PICO 双层图、配套距离场和 Android 合成图。`tools/import_weights.py` 可从作者原始包重建 PFNN 数据。

## 资源来源

林溪与陆野由 PICO SGS 生成并针对 PFNN 绑定，分别为 45,606 和 33,628 个三角面。山体与三枚金属徽章同样来自 SGS。任务编号如下。

| 资源 | SGS 任务 |
| --- | --- |
| 林溪 | 6cc999af-7847-4623-9846-e27569eb1884 |
| 陆野 | 950dae0a-7037-4197-91ee-c133d1edf2af |
| 山体 | 1827b184-efa2-40a1-b39a-0f511f6a4d79 |
| 晨光 | 8f6a11ef-25de-49fe-8b50-7c556fa9cc18 |
| 溪蓝 | fe732784-4909-4c9d-a848-f9c6daaea260 |
| 暮紫 | 3e63c463-5fbf-4623-a5eb-2b3db3870a57 |

地表与树皮使用 Poly Haven 的 CC0 纹理。[草地](https://polyhaven.com/a/aerial_grass_rock) · [林地](https://polyhaven.com/a/forest_ground_04) · [岩石](https://polyhaven.com/a/rock_boulder_dry) · [树皮](https://polyhaven.com/a/bark_brown_02)

PFNN 来自 Holden、Komura、Saito 的 SIGGRAPH 2017 作者实现。作者许可覆盖学术及非商业用途。本工程按已确认的非商业研究用途制作，公开分发前需核对实际分发方式与授权范围。[论文](https://doi.org/10.1145/3072959.3073663) · [作者项目](https://theorangeduck.com/page/phase-functioned-neural-networks-character-control) · [随包许可](app/src/main/assets/pfnn/LICENSE.txt)


## PPS 每日跑量榜

数据板里的「排行榜」展示当日前 10 名，按虚拟跑者今日累计整米数从高到低排列，用户名来自 PPS 条目的 `user.displayName`。每分钟同步一次当前今日总量，打开榜单时也先同步，页面每 30 秒刷新。写入采用最佳成绩模式，不会因重复上传重复加分。

使用 PPS Leaderboard 与 Auth 1.1.2。`gradle.properties` 已配置公开 App ID `c6148f5f7742de464efa1b3188721b67`、前缀 `run_daily`，包名仍是 `com.example.terrainrunner`。开发者平台内部应用 ID 是 `7711696452895916074`，不要拿它替换 API 页的 PPS App ID。App Secret 和账号令牌不写入工程或日志。

首次授权只在打开排行榜时触发；后台通过 `getAuthorizedScopes` 恢复已有授权，未授权时不弹窗。平台初始化和账号授权完成后直接调用 `LeaderboardClient`。不要额外调用或要求 `GameApi.gameInitializeWithTokenAccountTypeOne`，它曾返回 `-98: app not found` 并错误挡住了排行榜；移除后真实写入和读取均成功，无需开通额外匹配服务。

每个日期使用独立 API Name「前缀_yyyyMMdd」，日期与本地今日跑量一致。中国大陆后台已预置 2026-11-24 至 2026-12-31 共 38 个日期榜，均通过，配置为「越高越好」「距离（米）」「客户端可写」。之后的日期需由后台继续预置；客户端不持有创建榜单所需的服务端密钥。

普通请求最多等待 15 秒，首次授权最多 180 秒。错误后可刷新重试，跨日刷新会切换到当天。JNI 的 extraData 使用 `byteArrayOf()`，不能传 null，否则已观察到真机原生崩溃。

### 已验证版本 0.3.2（versionCode 10）

- 已安装到 PB321XJGL2030019G，显示名称为「自在跑」，包名及用户存档不变。
- `assembleDebug` 和 `assembleDebugAndroidTest` 成功；SpatialUI 风格检查零错误零警告。
- `PpsDailyRankingStudy`：在已授权账号上从冷启动静默恢复授权，上传真实今日跑量，确认榜单返回有效用户名、成绩、排名及降序前十。
- `DailyRankingPresentation`：打开、返回排行榜时巡跑继续；两项真机测试均通过。
- 2026-11-25 后台已出现 XRCoder 的真实跑量条目；无假名或填充成绩。重启正常应用后也确认自动上传成功。
- 验证命令：`adb -s PB321XJGL2030019G shell am instrument -w -e class com.example.terrainrunner.PpsDailyRankingStudy,com.example.terrainrunner.DailyRankingPresentation com.example.terrainrunner.test/androidx.test.runner.AndroidJUnitRunner`。

开发者后台已保存「自在跑」0.3.2 修复版草稿（versionCode 10），草稿 ID `7711937226720002102`，渠道为消费者商店 / 中国大陆 / PICO Space Pro。后台 0.3.2 包体 MD5 为 `07ea952afd0358025274b74ae0c95988`，上传时已与本地修复包核对一致，包名及签名测试通过。版本性质为 Bugfix，未提交审核。

草稿编辑页：https://developer-cn.picoxr.com/newconsole/organization/7711206045355556883/overview/7711696452895916074/7711937226720002102 。安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。除应用名称和包体外，其余宣传文案留待用户编辑。

### UI 文案与字阶 0.3.3（versionCode 11）

- 数据板使用「今日跑动」及「每日寻星三颗完成打卡」
- 应用自有 UI 文案不使用中文句号，数字小数点保留
- 寻星主标题使用原生 `titleLarge`，操作说明和星名使用 `bodySmall`，反馈及状态使用 `labelMedium`；保持现有 PicoTheme 颜色和玻璃面板
- 风格校验 0 错误，1 条大标题软上限提示已按用户要求扩大标题的意图审查保留；工程与运行可访问性审计均无错误
- 已安装到 PB321XJGL2030019G，真实界面已核对两处文案并打开今日寻星
- 后台草稿仍是上次确认的 0.3.2，本次仅更新本地和头显 UI，不自动提交审核


### 热量与寻星简化 0.3.4（versionCode 12）

- 热量按 70 kg 虚拟跑者估算：`70 × (今日公里数 + 0.0045 × 今日爬升米数)`，平地 1 km 为 70 kcal，1 km 加 100 m 爬升为 101.5 kcal
- 依据 ACSM 跑步氧耗的水平项 0.2、垂直项 0.9，以 5 kcal/L 换算活动消耗；旧版爬升系数 0.009 偏高，改为 0.0045，不计静息消耗
- 这是基于角色游戏里程的近似展示，非佩戴者实际热量测量，陡坡与乱石不作精确生理建模；保留现有里程、爬升、排行榜和打卡存档
- 参考研究：https://pmc.ncbi.nlm.nih.gov/articles/PMC10707652/
- 寻星三颗星图标由 17 dp 改为 26 dp，星名由 bodySmall 改为 titleMedium，移除「等你带路 / 已插旗 / 已收集」整行，右上角收集数量继续显示
- 后台草稿仍为已保存的 0.3.2，本次不上传或提交审核
- 验证：assembleDebug / assembleDebugAndroidTest 构建成功，0019G 上 RunJournalTest 四项通过（含平地 70 kcal、爬升 101.5 kcal、存档、跨日和暂停）
- 已安装并打开今日寻星，真实 UI 语义确认三颗星名称存在、等待状态整行不存在；当前进程崩溃记录为 0，工程与真机可访问性审计零错误，风格检查保留原有 1 条大标题软提示
- 截图只捕获到当前虚拟山景，面板未在头显相机视野内，字号实际观感仍待佩戴查看；证据在 /private/tmp/twr-pps/ui-034-*


### 0.3.4 热量口径微调

- 按最新要求使用 73 kg 虚拟跑者，公式为 `73 × 今日公里数 + 0.3285 × 今日爬升米数`
- 例如同为 1 km，平地为 73 kcal，爬升 100 m 为 105.85 kcal；热量由已有距离及爬升确定，不添加随机波动
- 界面继续使用「今日消耗 · 估算」，保留寻星字号和所有存档，不上传后台草稿
- 构建成功，0019G 的 RunJournalTest 四项通过，已覆盖安装并重新启动，当前进程未见崩溃记录
