# roomxxxmp4 空间标定数据与语义说明

## 1. 文档用途

本文档用于向其他 AI 或开发者完整传递 `roomxxxmp4` 的空间标定数据及其真实语义。

这不是标准俯视户型图，也不是所有房间的完整平面边界。它是建立在固定摄像机视频画面上的二维透视空间抽象，主要描述：

- 当前摄像机可见的主空间；
- 主空间边界上的门底边；
- 从门口向门后延伸的 Portal 过渡区；
- 每个 Portal 所连接的门后空间语义；
- 特殊入口和不可见盲区。

所有 `x`、`y` 坐标均为相对视频画面的归一化坐标，范围通常为 `0.0～1.0`。它们不是现实世界米制坐标。

## 2. 必须遵守的语义

### 2.1 主空间

`id=living_room`、`name=客厅`、`isSovereign=true` 的记录是当前视频中被完整标定的主可见空间。

其 `boundaryVertices` 构成客厅在视频画面中的透视多边形边界。只有这个大多边形可以理解为被完整标定的空间范围。

### 2.2 门后空间记录

主卧、儿童房、书房、次卧、卫生间、厨房和入户等记录，不表示这些房间的完整边界。

这些名称表示门后空间的语义归属，例如“这扇门通往主卧”。程序没有记录门后真实房间的完整大小、形状或墙体边界。

### 2.3 Portal 过渡区

非主空间记录中的 `boundaryVertices` 表示门口及门后可见延伸形成的 Portal 过渡区。

通常：

- 靠近客厅的一条边是门底边；
- 另外两个点描述门后通道在视频画面中的延伸；
- 延伸可以正对摄像机、侧对摄像机或斜向摄像机；
- 该四边形只用于表达人物穿门过程中的空间连续性；
- 不得把该四边形解释成整个主卧、厨房等房间的轮廓。

### 2.4 门底边与 `occupiedWallIds`

配置没有单独保存 `doors` 数组。

每个门后空间通过 `occupiedWallIds` 关联客厅边界。客厅的每个 `BoundaryVertex` 都有 `edgeIdToNext`，表示“当前顶点到下一个顶点”的边 ID。

解析关系如下：

1. 读取门后空间的 `occupiedWallIds`；
2. 在客厅 `boundaryVertices` 中寻找对应的 `edgeIdToNext`；
3. 该顶点与下一个顶点组成的边，就是这个 Portal 在客厅侧的门底边；
4. 门底边与门后空间的 Portal 过渡区共同描述人物进入或离开该门的路径。

### 2.5 入户门

`isEntranceDoor=true` 表示该 Portal 是住宅与外部世界之间的入户入口，而不是普通室内房间门。

当前配置中“入户”记录具有该标记。

### 2.6 盲区

`isLivingBlindZone=true` 表示客厅边界上的不可见或特殊盲区。

盲区可以没有 `boundaryVertices`，只通过 `occupiedWallIds` 关联一条或多条客厅边。它不代表一个已绘制的房间。

## 3. 数据结构速查

| 字段 | 实际含义 |
| --- | --- |
| `rooms` | 历史命名；实际同时承载主空间、Portal 语义记录和盲区 |
| `id` | 稳定标识 |
| `name` | 主空间名称，或 Portal 所连接的门后空间名称 |
| `isSovereign` | 是否为完整标定的主空间 |
| `boundaryVertices` | 主空间中表示完整边界；非主空间中表示 Portal 过渡区 |
| `edgeIdToNext` | 当前顶点到下一个顶点所形成边的 ID |
| `occupiedWallIds` | Portal 或盲区关联的客厅边 ID；Portal 场景下对应门底边 |
| `isEntranceDoor` | 是否为连接室外的入户门 |
| `isLivingBlindZone` | 是否为客厅不可见盲区 |
| `anchor` | 编辑/显示使用的语义锚点 |
| `label` | 标签显示位置 |
| `themeColor` | UI 显示颜色 |
| `devices` | 空间内设备配置；当前文件为空 |

## 4. 给其他 AI 的正确抽象

应将该数据描述为：

> 一个固定摄像机画面中的主可见空间多边形，以及主空间边界上的若干 Portal。每个 Portal 由客厅侧门底边、门后透视过渡区和目标空间语义标签组成。目标房间的完整物理边界并未标定。

不能描述为：

> 客厅和其他房间分别由多个完整房间多边形组成。

仅凭该配置不能确定：

- 房屋真实物理尺寸；
- 门后房间完整形状；
- 门后空间真实深度；
- 精确三维朝向；
- 摄像机内参、外参；
- 不可见区域中的实际运动轨迹。

## 5. roomxxxmp4.Room 原始数据

以下内容从当前设备应用数据目录中的 `files/room_configs/roomxxxmp4/roomxxxmp4.Room` 原样读取。

```json
{
  "rooms": [
    {
      "id": "living_room",
      "name": "客厅",
      "isSovereign": true,
      "isRecorded": true,
      "isEntranceDoor": false,
      "isLivingBlindZone": false,
      "boundaryVertices": [
        {
          "id": 1,
          "x": 0.09612534195184708,
          "y": 0.8370400071144104,
          "edgeIdToNext": 31
        },
        {
          "id": 18,
          "x": 0.11642619967460632,
          "y": 0.8185304403305054,
          "edgeIdToNext": 32
        },
        {
          "id": 6,
          "x": 0.15565583109855652,
          "y": 0.7484200596809387,
          "edgeIdToNext": 9
        },
        {
          "id": 7,
          "x": 0.25262385606765747,
          "y": 0.7420016527175903,
          "edgeIdToNext": 11
        },
        {
          "id": 8,
          "x": 0.32659101486206055,
          "y": 0.7451436519622803,
          "edgeIdToNext": 13
        },
        {
          "id": 9,
          "x": 0.41995862126350403,
          "y": 0.7712596654891968,
          "edgeIdToNext": 15
        },
        {
          "id": 10,
          "x": 0.4948848485946655,
          "y": 0.7490172386169434,
          "edgeIdToNext": 17
        },
        {
          "id": 11,
          "x": 0.631031334400177,
          "y": 0.7170721292495728,
          "edgeIdToNext": 19
        },
        {
          "id": 12,
          "x": 0.7162390351295471,
          "y": 0.6900533437728882,
          "edgeIdToNext": 21
        },
        {
          "id": 13,
          "x": 0.7715132236480713,
          "y": 0.6799028515815735,
          "edgeIdToNext": 22
        },
        {
          "id": 2,
          "x": 0.8264514803886414,
          "y": 0.6469625234603882,
          "edgeIdToNext": 23
        },
        {
          "id": 14,
          "x": 0.8552088737487793,
          "y": 0.6713936924934387,
          "edgeIdToNext": 27
        },
        {
          "id": 16,
          "x": 0.8854499459266663,
          "y": 0.7078919410705566,
          "edgeIdToNext": 28
        },
        {
          "id": 15,
          "x": 0.9624810814857483,
          "y": 0.7432705760002136,
          "edgeIdToNext": 26
        },
        {
          "id": 5,
          "x": 1,
          "y": 0.7490172386169434,
          "edgeIdToNext": 6
        },
        {
          "id": 3,
          "x": 1,
          "y": 1,
          "edgeIdToNext": 3
        },
        {
          "id": 4,
          "x": 0,
          "y": 1,
          "edgeIdToNext": 29
        },
        {
          "id": 17,
          "x": 0.05258342623710632,
          "y": 0.9358609914779663,
          "edgeIdToNext": 30
        }
      ],
      "occupiedWallIds": []
    },
    {
      "id": "c8ef1ff5-2541-42c8-9a2d-a6852087d717",
      "name": "主卧",
      "isSovereign": false,
      "isRecorded": false,
      "isEntranceDoor": false,
      "isLivingBlindZone": false,
      "boundaryVertices": [
        {
          "id": 1,
          "x": 0.05258342623710632,
          "y": 0.9358609914779663,
          "edgeIdToNext": 1
        },
        {
          "id": 2,
          "x": 0.09612534195184708,
          "y": 0.8370400071144104,
          "edgeIdToNext": 2
        },
        {
          "id": 3,
          "x": 0.07722456753253937,
          "y": 0.31089210510253906,
          "edgeIdToNext": 3
        },
        {
          "id": 4,
          "x": 0.019710198044776917,
          "y": 0.31621652841567993,
          "edgeIdToNext": 4
        }
      ],
      "occupiedWallIds": [
        17
      ],
      "anchor": {
        "x": 0.04096312075853348,
        "y": 0.37010475993156433
      },
      "label": {
        "x": 0.03312743082642555,
        "y": 0.3310282528400421
      },
      "themeColor": -16745729
    },
    {
      "id": "7163ae79-e0e5-4faf-9c58-c4c3b7c340fb",
      "name": "儿童房",
      "isSovereign": false,
      "isRecorded": false,
      "isEntranceDoor": false,
      "isLivingBlindZone": false,
      "boundaryVertices": [
        {
          "id": 1,
          "x": 0.8854499459266663,
          "y": 0.7078919410705566,
          "edgeIdToNext": 1
        },
        {
          "id": 2,
          "x": 0.9624810814857483,
          "y": 0.7432705760002136,
          "edgeIdToNext": 2
        },
        {
          "id": 3,
          "x": 0.9868142008781433,
          "y": 0.35413220524787903,
          "edgeIdToNext": 3
        },
        {
          "id": 4,
          "x": 0.9068708419799805,
          "y": 0.35278943181037903,
          "edgeIdToNext": 4
        }
      ],
      "occupiedWallIds": [
        16
      ],
      "anchor": {
        "x": 0.9653652906417847,
        "y": 0.534756600856781
      },
      "label": {
        "x": 0.9461004734039307,
        "y": 0.27867329120635986
      },
      "themeColor": -13318311
    },
    {
      "id": "af80da94-d18b-4684-8121-171ad528bd13",
      "name": "书房",
      "isSovereign": false,
      "isRecorded": false,
      "isEntranceDoor": false,
      "isLivingBlindZone": false,
      "boundaryVertices": [
        {
          "id": 1,
          "x": 0.41995862126350403,
          "y": 0.7712596654891968,
          "edgeIdToNext": 1
        },
        {
          "id": 2,
          "x": 0.4948848485946655,
          "y": 0.7490172386169434,
          "edgeIdToNext": 2
        },
        {
          "id": 3,
          "x": 0.4934900104999542,
          "y": 0.31621652841567993,
          "edgeIdToNext": 3
        },
        {
          "id": 4,
          "x": 0.4190905690193176,
          "y": 0.3086528480052948,
          "edgeIdToNext": 4
        }
      ],
      "occupiedWallIds": [
        9
      ],
      "anchor": {
        "x": 0.45358791947364807,
        "y": 0.3829928934574127
      },
      "label": {
        "x": 0.4505383372306824,
        "y": 0.27894699573516846
      },
      "themeColor": -16726082
    },
    {
      "id": "60d4045e-7a03-4caf-930b-fb3680f83177",
      "name": "次卧",
      "isSovereign": false,
      "isRecorded": false,
      "isEntranceDoor": false,
      "isLivingBlindZone": false,
      "boundaryVertices": [
        {
          "id": 1,
          "x": 0.11642619967460632,
          "y": 0.8185304403305054,
          "edgeIdToNext": 1
        },
        {
          "id": 2,
          "x": 0.15565583109855652,
          "y": 0.7484200596809387,
          "edgeIdToNext": 2
        },
        {
          "id": 3,
          "x": 0.13244278728961945,
          "y": 0.3244263529777527,
          "edgeIdToNext": 3
        },
        {
          "id": 4,
          "x": 0.11152588576078415,
          "y": 0.26377061009407043,
          "edgeIdToNext": 4
        }
      ],
      "occupiedWallIds": [
        18
      ],
      "anchor": {
        "x": 0.11152588576078415,
        "y": 0.3415931761264801
      },
      "label": {
        "x": 0.11152588576078415,
        "y": 0.26377061009407043
      },
      "themeColor": -50384
    },
    {
      "id": "a41c1aac-efee-4a12-8a84-cd976e9b4ca3",
      "name": "卫生间",
      "isSovereign": false,
      "isRecorded": false,
      "isEntranceDoor": false,
      "isLivingBlindZone": false,
      "boundaryVertices": [
        {
          "id": 1,
          "x": 0.15565583109855652,
          "y": 0.7484200596809387,
          "edgeIdToNext": 1
        },
        {
          "id": 2,
          "x": 0.25262385606765747,
          "y": 0.7420016527175903,
          "edgeIdToNext": 2
        },
        {
          "id": 3,
          "x": 0.24618344008922577,
          "y": 0.34044864773750305,
          "edgeIdToNext": 3
        },
        {
          "id": 4,
          "x": 0.14502915740013123,
          "y": 0.3373883366584778,
          "edgeIdToNext": 4
        }
      ],
      "occupiedWallIds": [
        6
      ],
      "anchor": {
        "x": 0.18810904026031494,
        "y": 0.41249969601631165
      },
      "label": {
        "x": 0.19390541315078735,
        "y": 0.254416286945343
      },
      "themeColor": -27392
    },
    {
      "id": "c018e0d6-d54c-4201-9646-13a83b27c898",
      "name": "厨房",
      "isSovereign": false,
      "isRecorded": false,
      "isEntranceDoor": false,
      "isLivingBlindZone": false,
      "boundaryVertices": [
        {
          "id": 1,
          "x": 0.631031334400177,
          "y": 0.7170721292495728,
          "edgeIdToNext": 1
        },
        {
          "id": 2,
          "x": 0.7162390351295471,
          "y": 0.6900533437728882,
          "edgeIdToNext": 2
        },
        {
          "id": 3,
          "x": 0.7234350442886353,
          "y": 0.3372141718864441,
          "edgeIdToNext": 3
        },
        {
          "id": 4,
          "x": 0.6344755291938782,
          "y": 0.3303479254245758,
          "edgeIdToNext": 4
        }
      ],
      "occupiedWallIds": [
        11
      ],
      "anchor": {
        "x": 0.6906177997589111,
        "y": 0.5104241967201233
      },
      "label": {
        "x": 0.6959652900695801,
        "y": 0.28437018394470215
      },
      "themeColor": -13312
    },
    {
      "id": "e07d03fb-98e7-42ac-af88-38eda2e58d01",
      "name": "入户",
      "isSovereign": false,
      "isRecorded": false,
      "isEntranceDoor": true,
      "isLivingBlindZone": false,
      "boundaryVertices": [
        {
          "id": 1,
          "x": 0.7715132236480713,
          "y": 0.6799028515815735,
          "edgeIdToNext": 1
        },
        {
          "id": 2,
          "x": 0.8264514803886414,
          "y": 0.6469625234603882,
          "edgeIdToNext": 2
        },
        {
          "id": 3,
          "x": 0.8316899538040161,
          "y": 0.3702773451805115,
          "edgeIdToNext": 3
        },
        {
          "id": 4,
          "x": 0.7845778465270996,
          "y": 0.3661743998527527,
          "edgeIdToNext": 4
        }
      ],
      "occupiedWallIds": [
        13
      ],
      "anchor": {
        "x": 0.8062065839767456,
        "y": 0.4524555504322052
      },
      "label": {
        "x": 0.8231332302093506,
        "y": 0.2989501953125
      },
      "themeColor": -10987818
    },
    {
      "id": "ebab322a-430b-4cc6-a43d-fab7e33bab20",
      "name": "盲区",
      "isSovereign": false,
      "isRecorded": false,
      "isEntranceDoor": false,
      "isLivingBlindZone": true,
      "boundaryVertices": [],
      "occupiedWallIds": [
        3,
        5
      ],
      "anchor": {
        "x": 0.008229464292526245,
        "y": 0.8961539268493652
      },
      "label": {
        "x": 0.008229464292526245,
        "y": 0.8961539268493652
      },
      "themeColor": -5287202
    }
  ],
  "devices": []
}
```
