# staticlog-app

Android 应用源码（`com.demo.zt`）。这个仓库只放构建 APK 需要的东西。

## 构建

环境、三步出包、签名和常见错误都在 [BUILD-APK.md](BUILD-APK.md)。最短路径：

```bash
cd ztui
cp local.properties.example local.properties   # 改成你的 SDK 路径
./gradlew :app:assembleRelease
```

Windows 用 `gradlew.bat`。

## 仓库里有什么

```
ztui/            Android 工程（Gradle 9.3.1 wrapper 已带）
server/          使用统计后端：标准库 Python + systemd + nginx，部署见 server/README.md
docs/            需求清单与开发交接记录
BUILD-APK.md     构建说明
README-PACK.md   包内容说明：哪些东西没带、为什么
```

## 仓库里没有什么

- 构建产物（`*.apk`、`build/`、`.gradle/`）
- 签名私钥（`.android/*.jks`）
- 本机配置（`local.properties`）

这些都在 `ztui/.gitignore` 里，并且 release 签名需要自己生成一份，命令见 BUILD-APK.md。
