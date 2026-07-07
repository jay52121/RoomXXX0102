# SISP 赛事技术提交材料

本目录包含 SISP 项目的核心技术源码、Android 演示安装包及 0629 路演材料。

## 文件结构

```text
01_Source/
  SISP_Core_Technology_Source_Disclosure_2026Q2.zip
  SISP_Core_Technology_Source_Disclosure_2026Q2.zip.sha256

02_Demo_APK/
  SISP_Demo_Android_arm64-v8a.apk

03_Presentation/
  基于本地视觉AI的室内空间理解与智能调度平台Sisp0629.pptx

04_Verification/
  APK_INFO.txt
  SHA256SUMS.txt
```

## 材料说明

- **核心技术源码**：展示 SISP Core-first / Edge Continuity 架构、Core 接口契约、能力协商、故障降级、离线日志与恢复对账，以及关键词识别、设备指向和空间状态的边缘参考实现。
- **Android 演示安装包**：集成人体/姿态识别、手部理解、关键词识别与空间交互链路，用于真机功能演示。
- **项目路演材料**：介绍项目背景、平台架构、核心技术、应用场景与产品规划。

## APK 安装条件

- 设备架构：arm64-v8a
- 最低系统：Android 8.0（API 26）
- 安装方式：`adb install -r SISP_Demo_Android_arm64-v8a.apk`
- 本安装包为赛事演示构建，不是应用商店发布包。

## 完整性

所有交付文件均在 `04_Verification/SHA256SUMS.txt` 中提供 SHA-256。源码压缩包内部另包含逐文件校验清单。

## 披露范围

源码材料基于早期技术验证版本整理。现行 SISP Core 服务端、模型资产、生产传输适配与核心策略因软件著作权办理及公司知识产权保护未完整披露，具体范围见源码包内 `docs/DISCLOSURE_SCOPE.md`。
