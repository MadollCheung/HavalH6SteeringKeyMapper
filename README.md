# 哈弗H6三代 方向盘按键自定义工具

## 项目概述

这是一款运行在哈弗第三代H6车机上的 Android APK，利用 `AccessibilityService` 监听方向盘硬按键，支持通过「★键 + 功能键」组合快捷控制空调温度、风量、内外循环等车机功能。

## 完整文件清单

```
HavalH6SteeringKeyMapper/
├── build.gradle.kts             # 项目根目录构建脚本 (AGP 8.2.2, Kotlin 1.9.22)
├── settings.gradle.kts          # 项目模块设置
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties  # Gradle 8.2 wrapper 配置
├── app/
│   ├── build.gradle.kts         # 模块构建脚本
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml  # 权限、组件注册
│       ├── java/com/haval/h6/steeringmapper/
│       │   ├── MainActivity.kt        # 主界面（按键映射配置列表）
│       │   ├── SteeringKeyService.kt  # AccessibilityService 核心服务 ★
│       │   ├── HvacController.kt      # 空调控制器（广播 + 模拟点击双路）
│       │   ├── KeyMappingManager.kt   # 按键映射管理器（增删改查，SharedPreferences持久化）
│       │   ├── KeyDetectActivity.kt   # 按键检测界面（确认车机按键 keycode）
│       │   ├── BootReceiver.kt        # 开机自启动接收器
│       │   ├── HvacAction.kt          # 空调动作枚举（13种功能）
│       │   └── KeyMapping（数据模型，路径报存在 KeyMappingManager.kt 中）
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml
│           │   ├── activity_key_detect.xml
│           │   └── item_key_mapping.xml  # RecyclerView 条目布局
│           ├── values/
│           │   └── strings.xml
│           └── xml/
│               └── accessibility_service_config.xml
└── README.md
```

## 技术方案说明

### 方向盘按键如何被监听

哈弗H6三代车机（华阳100A/安波福403A）的方向盘按键通过 CAN 总线上报后，由底层 BSP 转为 Android `KeyEvent` 注入系统。`AccessibilityService` 可以在**所有窗口**拦截到这些 `KeyEvent`，包括在其他 App 运行时。

### 空调控制实现方式

由于车机不是 Android Automotive OS，无法直接使用 `CarHvacManager`。本工具采用双路策略：

**方式一（主要）：发送广播 Intent**
- 向车机系统发送 `com.greatwall.hvac.action.CONTROL` 广播
- 部分长城车机版本暴露了内部广播接口，可直接控制空调

**方式二（兜底）：AccessibilityService 模拟触控点击**
- 利用 `findAccessibilityNodeInfosByViewId` 找到空调界面控件，触发 click
- 广播方案失败时自动降级到此方式

### 按键编码参考（哈弗H6三代）

| 方向盘按键 | Android KeyCode |
|------------|----------------|
| ★ (星号键) | `KEYCODE_STAR` (17) |
| ▲ 上键 | `KEYCODE_DPAD_UP` (19) |
| ▼ 下键 | `KEYCODE_DPAD_DOWN` (20) |
| ◄ 左键 | `KEYCODE_DPAD_LEFT` (21) |
| ► 右键 | `KEYCODE_DPAD_RIGHT` (22) |
| OK/确认 | `KEYCODE_DPAD_CENTER` (23) |
| 音量+ | `KEYCODE_VOLUME_UP` (24) |
| 音量- | `KEYCODE_VOLUME_DOWN` (25) |
| 接听电话 | `KEYCODE_CALL` (5) |
| 挂断电话 | `KEYCODE_ENDCALL` (6) |

> ⚠️ 不同批次车机 keycode 可能略有差异，App 内置「按键检测」功能可实际检测你的车机 keycode

### 默认按键映射表

| 组合 | 功能 |
|------|------|
| ★ + ▲ | 温度 +0.5°C |
| ★ + ▼ | 温度 -0.5°C |
| ★ + ► | 风量 + |
| ★ + ◄ | 风量 - |
| ★ + OK | 内外循环切换 |
| ★ + 音量+ | A/C 开关 |
| ★ + 音量- | AUTO 自动模式 |
| ★ + 接听 | 前挡风除雾 |
| ★ + 挂断 | 后挡风加热 |

## 编译安装步骤

### 环境准备

- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 17
- AGP 8.2.2 / Gradle 8.2

### 导入项目

1. 将 `HavalH6SteeringKeyMapper/` 文件夹复制到工作目录
2. 用 Android Studio 打开项目 (`File → Open`)
3. 等待 Gradle Sync 完成
4. 点击 `Build → Generate Signed APK` 或直接 `Build → Build APK(s)`

### 安装到车机

```bash
# ADB 无线/有线安装
adb install app-debug.apk

# 或将 APK 拷贝到 U 盘，车机内置文件管理器安装
```

### 首次使用

1. 安装后打开「方向盘按键映射」App
2. 点击「开启无障碍服务」，跳转到系统无障碍设置
3. 找到「方向盘按键映射工具」并开启
4. 返回 App，推荐先进入「按键检测」界面确认车机实际 keycode
5. 按需配置或使用默认映射表

## 调试技巧

```bash
# 查看空调 App 包名
adb shell dumpsys window windows | grep mCurrentFocus

# 实时查看服务日志
adb logcat -s SteeringKeyService HvacController KeyMappingManager

# 查看所有按键事件（检测模式等效）
adb shell getevent -l
```

## 常见问题

**Q: 按键没有响应**
A: 确认无障碍服务已开启；用按键检测界面确认实际 keycode，并更新映射表

**Q: 空调操作没生效**
A: 车机可能不支持广播方式；需将空调 App 切到前台再操作（模拟点击方案需要界面可见）

**Q: 车机 OTA 后失效**
A: 空调 App 包名可能变化，用 ADB 重新检查包名并在 `HvacController.kt` 的 `HVAC_PACKAGE_CANDIDATES` 中更新

## 注意事项

- 车机改装可能影响质保，风险自担
- 建议在停车状态下完成配置
- 本工具不读取任何敏感信息

---

> 适用车型：哈弗H6第三代（华阳100A / 安波福403A 车机）  
> Android 版本：API 29+（Android 10）
