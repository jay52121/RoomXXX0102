# SISP 结构化网页调试与标注台 V1（实施设计）

## 边界
- Android 是唯一视频播放、YOLO Pose、ByteTrack、房间判定及人工标注真值来源；浏览器不接收图片、截图或视频，也不重复执行识别。
- 首版只针对本地视频回放，不改 V4 识别算法；房间几何使用当前标定的归一化坐标。
- 数据通过局域网 WebSocket 传输 JSON；服务默认关闭，手动开启并显示连接地址及临时配对码；只允许一个拥有控制权的浏览器，其余连接只读。

## 界面与工具
1. **结构化画布**：原始视频宽高比的空白坐标系，绘制房间多边形、Portal、多边形名称、人体 bbox、17 个关键点、Track ID、轨迹、置信度；各层独立开关。右侧小地图显示每个人所在房间和房间人数。没有视频像素，画布必须明确标注“结构化视图”。
2. **进度条**：视频总时长、当前视频时间、分析帧时间、播放状态；可拖动跳转。上轨人工事件、下轨 V4 事件；颜色/图标区分 ENTER、EXIT、MATCH、MISS、WRONG_PORTAL、WRONG_DIRECTION、FALSE_POSITIVE。点击事件定位，支持上一事件/下一事件/下一未绑定。
3. **播放器控制**：播放/暂停按钮；空格播放/暂停；左/右箭头后退/前进 5 秒；附逐分析帧按钮及快捷键逗号/句号。输入框聚焦、快捷键说明弹窗打开时，不截获键盘输入。指令由 Android 执行，浏览器等待 ack 后更新播放状态，不做乐观假显示。
4. **人工标注**：当前事件显示序号、时间、进入/离开、Track（可选）、房门和绑定状态。新建进入/离开事件；点击 Portal 或房间名绑定，点另一门覆盖；清除房门、删除事件、撤销/重做。修改前后回传事件 ID、修订号，防止重复提交或覆盖别人的改动。所有更改立即由 Android 持久化。
5. **匹配检查**：展示人工事件 ↔ 算法事件对应关系、时间差、候选门和拒绝原因；未绑定房门时不得标成“门匹配正确”。**无匹配则弹跳回事件记录位置**开关默认关闭：当用户主动请求“下一个事件/下一未匹配事件”且该事件未匹配时，先短暂展示算法位置（若存在），再定位到人工标注时间；若播放到未匹配事件则暂停并跳回该事件时间。必须防止同一事件反复触发造成循环；用户手动 seek 后直到离开该事件窗口不重复触发。开关状态本次会话记忆。
6. **诊断**：选中事件时列出附近关键分析帧的 bbox/关键点覆盖、Portal 候选、Track ID、FSM、Ledger、V4 输出、匹配结果和拒绝原因；不凭空计算没有提供的字段，缺失时显示“未采集”。

## 数据契约
- 静态 snapshot：sessionId、videoKey、durationMs、frameWidth/Height、rooms、portals、算法配置摘要、已保存人工事件及版本号。
- 动态 frame：sessionId、seq、videoTimestampMs、analysisTimestampMs、frameIndex、poses[{poseId,trackId,bbox,keypoints[17],confidence}]、roomStates、tracks、algorithmEvents、debugEvidence（可选）。
- 事件增量：eventId、timestampMs、frameIndex、type、trackId?、portalRoomId?、revision；匹配结果含 GT/算法事件 ID、状态、lagMs、依据。
- 控制：play、pause、seekMs、stepFrame、selectEvent、createEvent、updateEvent、deleteEvent、undo、redo；每条 command 带 requestId，回传 ack/error 与 Android 权威状态。
- 全部帧和事件使用统一视频时间轴；断线重连发 snapshot + 最近一帧，不能把旧帧 Pose 混到新时间。缓冲队列有上限，只丢过时的动态显示帧，不丢事件与操作确认。

## 交付顺序
P0 Android 侧采集统一结构化快照/帧与事件契约；P1 局域网只读 WebSocket、网页地图/Pose/时间轴；P2 远程播放、键盘快捷键与状态确认；P3 标注 CRUD、绑定/撤销、匹配诊断及未匹配回跳开关；P4 断线重连、并发控制、真机测试与性能基准。

## 验收
- 电脑无需看到视频图像，即可检查 33 个事件的 Pose、门、轨迹、匹配关系并完成绑定。
- 空格切换播放/暂停；左右键 ±5s；所有控制按 Android 实际回执同步。
- 无匹配回跳开启后仅触发一次，不陷入回跳循环；关闭后绝不自动跳。
- 标注保存后重启 App 不丢；手机端与网页同一真值；断线后恢复；网页服务关闭时不增加持续计算负担。
