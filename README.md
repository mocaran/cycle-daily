# Cycle Daily · 每日赛区

一款 Android 运动记录应用。用手机定位记录步行和跑步的距离、用时、配速和当天轨迹。支持锁屏记录、自定义每日目标和跑步折算倍数，也可扣除误计里程。运动数据只保存在当前设备，跨天清理。地图底图通过高德地图联网加载，在中国内地使用无需 VPN；无网络时运动记录仍可继续，但地图底图无法显示。

## 安装和使用

从 [GitHub Releases](https://github.com/mocaran/cycle-daily/releases/latest) 下载 APK，支持 Android 8.0 及以上。安装后开启手机定位并授予精确位置权限，在户外点按「开始记录」。默认目标为步行距离加两倍跑步距离，合计 6 公里。

首页可点按轨迹进入全屏，也可拖动或双指缩放。轨迹视图使用高德地图底图，蓝点表示当前位置，绿色和橙色分别表示步行和跑步。锁屏时由 Android 前台服务继续记录。

此前安装过调试版的用户需先卸载旧包再安装发布版。卸载会清除已保存的运动数据与设置。

## 从源码构建

需要 JDK 17 或以上和 Android SDK 36.1。Windows 上运行：

```powershell
.\gradlew.bat assembleDebug
```

生成文件位于 `app/build/outputs/apk/debug/app-debug.apk`。

构建前在本机 `local.properties` 中填写高德 Web 端 JS API 的 `AMAP_WEB_KEY` 和 `AMAP_SECURITY_CODE`，或设置同名环境变量。该文件不会上传到 GitHub。
