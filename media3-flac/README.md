# media3-flac（16 KB 页对齐自维护副本）

本模块是 media3 `1.10.1` 中 `decoder_flac` 扩展的本地副本：

- `src/main/java/androidx/media3/decoder/flac/` 的 8 个 Java 文件与上游逐字节一致，未作任何修改；
- `src/main/jniLibs/` 下四个 ABI 的 `libflacJNI.so` 为**预编译入库**二进制，由本仓库自行构建维护；
- `src/main/jni/` 存放 JNI 包装层源码与 CMake 配置（同样来自上游，逐字节一致）；
- `proguard-rules.pro` 为本模块的 consumer 规则，会合并进 App 的 R8 配置。

模块通过 `src/main/jniLibs/` 交付 `.so`，`build.gradle.kts` 中**没有**配置 `externalNativeBuild`，因此 Gradle 构建不会重新编译原生代码，四个 `.so` 必须由 `tools/build-flac.sh` 显式重新生成。

## 为什么需要重新构建

原入库的四个 `libflacJNI.so` 的 ELF `PT_LOAD` 段 `p_align` 为 `0x1000`（4 KB），而同一 APK 内其他原生库为 `0x4000`。本项目 `targetSdk = 36`，Google Play 已强制要求 16 KB 页支持，Android 15+ 的 16 KB 页设备上 4 KB 对齐的 `.so` 会 `dlopen` 失败，`LibflacAudioRenderer` 随即**静默**退化为平台 FLAC 解码器——这正是本模块要修复的 bug。

NDK r26 默认仍以 4 KB 页链接，因此构建时必须显式传入 `-Wl,-z,max-page-size=16384`。

## 源码版本与来源

| 组成 | 版本 / 提交 | 许可 | 位置 |
| --- | --- | --- | --- |
| JNI 包装层 | androidx.media3 `1.10.1`，`libraries/decoder_flac/src/main/jni/` | Apache-2.0 | `src/main/jni/`（已入库） |
| libFLAC | xiph/flac `e94ff9f68b8e7dbd3e9f8b1ac18a8eca1914f181`（2026-07-19，即 libFLAC 1.5.0 之后的 git 版本） | BSD-3-Clause | `src/main/jni/libflac/`（由脚本拉取，不入库） |
| 工具链 | Android NDK r26b（clang 17.0.2）、CMake 3.31.6 + Ninja、`ANDROID_PLATFORM=android-27` | — | 由 `tools/build-flac.sh` 自动探测 |

libFLAC 由 HEAD 拉取、不带版本标签，其版本仅体现在二进制内嵌的 `FLAC__VENDOR_STRING`。已入库的四个 `.so` 均内嵌：

```text
reference libFLAC git-e94ff9f 20260719
```

该字符串由 libFLAC 的 CMake 在配置期从 git 元数据生成，因此 `src/main/jni/libflac/` 必须保留 `.git` 目录，不能用源码压缩包替代。

已入库 JNI 源码的 SHA-256（用于确认与上游 media3 1.10.1 一致）：

```text
62e7170dcf0f71d4dabe6cf397e9e199d51d43e7f885f6b730b89935568f3ae9  CMakeLists.txt
ee4892c707fde0152ac36160580364014227aeff1f17e33d8db3c7ca850aea86  flac_jni.cc
0914a0b08866ddc95e4cf114a47a46f3924e2897533cc85f6048748e4e282c0b  flac_parser.cc
ec1561e097797a13db9d448f09175b364f71af7462600ee54dd09b854786d085  include/data_source.h
efbcf80e09b94ef7bd927f9842c9548dedb482e9f78b8dfee6d749a76068ac7f  include/flac_parser.h
```

`tools/build-flac.sh` 每次构建都会比对上述校验和，本地改动会被显式警告而不是静默使用。

## 重新构建

```shell
# 全部四个 ABI：构建 → 校验 → 覆盖 src/main/jniLibs/
media3-flac/tools/build-flac.sh

# 只构建指定 ABI，构建与校验但不覆盖已入库产物
media3-flac/tools/build-flac.sh --abis "arm64-v8a" --no-install

# 丢弃上次构建缓存 / 重新拉取 libFLAC
media3-flac/tools/build-flac.sh --clean --refetch
```

首次运行需要网络：脚本会以 `git fetch --depth=1` 拉取 libFLAC 的指定提交到 `src/main/jni/libflac/`。

关键的链接参数由脚本通过 CMake 传入，未修改上游 `CMakeLists.txt`：

```text
-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384
```

构建产物先经 `llvm-strip --strip-unneeded` 去符号（与入库产物的 section 组成一致），再通过校验才会覆盖 `src/main/jniLibs/`。

## 校验

```shell
# 校验 src/main/jniLibs/ 下四个 .so
media3-flac/tools/check-flac-jni.sh

# 校验 APK 内实际打包的 .so（AGP 打包时会再次 strip）
media3-flac/tools/check-flac-jni.sh --apk app/build/outputs/apk/debug/app-debug.apk
```

校验项：

1. `PT_LOAD` 段 `p_align >= 0x4000`（16 KB）；
2. `FlacDecoderJni.java` 中声明的 14 个 `Java_androidx_media3_decoder_flac_FlacDecoderJni_*` 入口点全部导出；
3. ELF 架构与 ABI 目录匹配。

校验脚本从 `$ANDROID_NDK_HOME`、`$ANDROID_HOME/ndk/*`、`$ANDROID_HOME/android-ndk-*` 或 `local.properties` 的 `sdk.dir` 中定位 `llvm-readelf` / `llvm-nm`，因此需要本机存在 NDK。

当前入库产物的 SHA-256：

```text
568a65afc71f91048fdf453e1e608a994fbb743f0fef26fe0fcd60943e9602da  arm64-v8a/libflacJNI.so    (456856 bytes)
1cdbd8a20ade8fb4421a476c6a501440e25327889e8c400dbd73dc51429ea63d  armeabi-v7a/libflacJNI.so  (339432 bytes)
375358475380f350c2fcce4a9c793756c8f3d713dfcb2f39a03fcb21991f5391  x86/libflacJNI.so          (460616 bytes)
4c9500405246f7400968eb2f5d829c34d93f4abf8b8a17a75ccd3aae4922d70f  x86_64/libflacJNI.so       (460720 bytes)
```

相关许可证文本与出处记录见仓库根目录 `THIRD_PARTY_NOTICES.md`。
