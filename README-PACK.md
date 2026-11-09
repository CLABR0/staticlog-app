# StaticLog 构造源码包

这是 Android 应用 `com.demo.zt` 的**源码树**，按可直接上传 GitHub 的形式组织：只有读代码和构建 APK 需要的东西，没有构建产物、没有私钥、没有本机路径。

## 包里有什么

```
ztui/                     Android 工程根目录（见 BUILD-APK.md）
BUILD-APK.md              构建说明：环境、三步出包、签名、常见错误
README-PACK.md            本文件
```

工程内部的目录结构写在 [BUILD-APK.md](BUILD-APK.md) 末尾的树里。

## 包里没有什么

| 没带 | 原因 |
|---|---|
| `*.apk` 构建产物 | 上源码仓库不放二进制，需要就自己构建 |
| `.android/sl-release.jks` | 签名私钥（`.gitignore` 已排除） |
| `local.properties` | 里面是本机 SDK 路径，换机器就失效；包内给的是 `local.properties.example` |
| `build/`、`.gradle/`、`.gradle-user-home/`、`.deps/` | 缓存与中间产物，几百 MB，构建时会自己生成 |
| 设计截图、预览 HTML、一次性修脚本 | 与构建无关 |

## 快速开始

```bash
cd ztui
cp local.properties.example local.properties   # 改 sdk.dir 成本机路径
./gradlew :app:assembleRelease
```

Windows 用 `gradlew.bat`。产物在 `ztui/app/build/outputs/apk/release/`。

签名文件要自己先生成一份，否则 release 构建会失败——命令在 BUILD-APK.md 的「签名说明」里。

## 上传到 GitHub

```bash
cd ztui
git init -b main
git add -A
git commit -m "init: StaticLog APK 构建源码"
git remote add origin https://github.com/USER/REPO.git
git push -u origin main
```

`.gitignore` 已经排掉了 `.gradle/`、`.gradle-user-home/`、`.android/`、`.kotlin/`、`.toolchains/`、`**/build/`、`local.properties`，直接 `git add -A` 不会把缓存和私钥带上去。

推之前建议核一遍实际会提交哪些文件：

```bash
git status --short | Measure-Object -Line
git ls-files | Select-String '\.jks$|local\.properties$'
```

第二条**应该没有任何输出**。有输出说明 `.gitignore` 被改过，先修好再推。

## 体积

源码约 23 MB，其中 18 MB 是 `app/src/main/assets/variants/` 下的载荷文件（`6-12g`、`6-16g`、`7-12g`、`7-16g`、`probe` 五组）。这些是应用运行时要用到的资源，不能删，删了构建出来的 APK 功能不完整。

单个文件最大 6.2 MB，低于 GitHub 的 100 MB 硬限制，可以直接推。
