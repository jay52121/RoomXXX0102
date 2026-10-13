# 可执行验收基础

完整方案：[docs/portal_boundary/README.md](../../docs/portal_boundary/README.md)。这些脚本不负责检测穿门，不是另一个V5.x版本。

## 本地自测

依赖：Python 3.10或以上；运动契约另外需要JDK及PATH中的`kotlinc`。Python部分仅使用标准库，没有pip依赖。

```sh
python3 tools/portal_acceptance/run_checks.py
```

执行24项验收器自测及8类Kotlin图像运动契约。返回0仅表示这些组件测试通过；缺少Kotlin工具返回2，不静默跳过。只运行验收器自测：

```sh
python3 -m unittest discover -s tools/portal_acceptance -p 'test_*.py' -v
```

## 对完整记录验收

```sh
python3 tools/portal_acceptance/strict_portal_acceptance.py /path/to/full-recording.json --json-out /path/to/report.json
```

可以一次列出全部六个文件。退出码0=提供的全部文件满足硬事件契约；1=至少一个不满足；2=结构不完整、标签被改或输入无效。需要检查所有事件500ms目标时追加`--require-target500`，它只能收紧，不能放宽标准。

命令行固定32条标签，并校验按`id,timeMs,direction,portalRoomId`规范化后的SHA-256。基线摘要为`c5ccf03445ab2f11b4043a15c712509d8cdcc5863871df40bd4e5ec151987988`。禁止通过修改该常量、减少标注数或只输入成功片段让测试变绿。内部Python函数允许小型单测构造标签，生产CLI不提供较低正确数门槛。

匹配使用同房门、同方向、[-100,+1000]ms的最大一对一匹配，错门不能抢占正确配对。计分时间取`frames[].timeMs`，不取可能回填的`outputs[].timeMs`；差异另外报告。500ms结果独立匹配并单列；未测墙钟延迟和新视频泛化始终明确标为未评估。

文件必须从视频0时刻记录，最后覆盖最晚标注+1000ms，且每帧明确含`outputs`数组。这个检查不能证明视频后面绝无未记录内容，所以全视频哈希、解码PTS和采集来源仍属于端到端验收。

## 历史结果

`baseline_manifest.json`保留六份文件SHA-256和实际重算结果，全部FAIL。原始JSON和视频不随工具重复上传；在本地使用已有文件执行以上命令即可。工作分支对V5.4旧输入的11/32回放不混入原始真机4/32结果。

## 运动组件的边界

`motion/PortalImageMotion.kt`只计算同一人、同名上身关节点的图像对应位移与速度。没有脚/膝/框底部输入，不读取标注或人数；不能单独判定穿过三维门槛。残差散布不等于标定过的传感器误差，使用前还需添加量化/定位噪声下限。它目前是独立参考组件，未修改Android运行时。

## CI边界

`portal-contracts.yml`检查验收器及工具语法；现有Android构建仍执行。CI绿色不能覆盖`baseline_manifest.json`中的失败，更不代表产品验收已通过。产品放行必须同时附完整事件、全像素链路、真实处理耗时和独立负样本证据。
