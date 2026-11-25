# 澎湃 SIM 卡余量查询

在澎湃控制中心的「移动数据」卡片显示剩余通用流量和话费余额，支持中国移动、中国电信和中国联通。

[下载模块 ZIP](https://github.com/Lw5959/hyper-china-mobile-usage/releases/latest)

## 安装

需要对应的运营商 App、KernelSU，以及 Vector 或 LSPosed。中国移动路径已在 REDMI K90 Pro Max（`OS4.0.6.0.XPMCNXM`，KernelSU + Vector）和 Xiaomi 15S Pro（`OS4.0.5.0.XODCNXM`，KernelSU + LSPosed）验证。

1. 在 KernelSU 刷入模块 ZIP。确认 Vector 或 LSPosed 已启用「澎湃 SIM 卡余量查询」，作用域勾选「手机管家」。
2. 确认无障碍服务「澎湃 SIM 卡余量查询」已连接。登录运营商 App，从首页打开流量和话费查询页。
3. 下拉控制中心，打开「移动数据」。手机管家设为「每月固定流量」时，会用查询结果校正套餐，再按这张卡的系统用量扣减。

读取服务可在「辅助功能 → 已下载的应用 → 澎湃 SIM 卡余量查询」手动开关。查询结果保存在本机。隐藏图标后，可通过 KernelSU 模块的「操作」打开配套应用。

## 构建

JDK 17 或更高版本、Android SDK Platform 36、Build Tools 37.0.0。

```sh
export ANDROID_SDK_ROOT=/path/to/android-sdk
./scripts/test.sh
./scripts/build.sh
```

模块 ZIP 在 `dist/`。已有 Xposed API 82 JAR 时，可用 `XPOSED_API_JAR` 指定路径。

## 许可证

[MIT](LICENSE)
