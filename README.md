# 替我跑

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
