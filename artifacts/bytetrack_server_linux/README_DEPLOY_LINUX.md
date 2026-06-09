# ByteTrack Server Linux 部署说明

## 包内容

- `server.py`：FastAPI 服务端，提供 `/health`、`/track`、`/reset`
- `requirements-linux.txt`：Linux 目标机安装依赖
- `start.sh`：启动脚本

这个服务只接收 Android 端已经检测好的框，然后用 `supervision.ByteTrack` 做 ID 跟踪；它不在服务端跑 YOLO 检测模型。

## 目标机要求

- Linux x86_64
- Python 3.10 或 3.11，推荐 3.11
- 手机和 Linux 机器在同一局域网
- 防火墙放行 TCP `8000`

## 安装

```bash
unzip bytetrack_server_linux.zip
cd bytetrack_server_linux

python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements-linux.txt
```

如果机器上没有 `python3.11`，可以先用：

```bash
python3 --version
python3 -m venv .venv
```

## 启动

```bash
source .venv/bin/activate
chmod +x start.sh
./start.sh
```

等价命令：

```bash
python -m uvicorn server:app --host 0.0.0.0 --port 8000
```

## 验证

在 Linux 目标机本机执行：

```bash
curl http://127.0.0.1:8000/health
```

正常会返回类似：

```json
{"ok":true,"supervision":"0.27.0","trackers_alive":[]}
```

在 Android 手机所在网络里，确认能访问：

```text
http://<Linux机器IP>:8000/health
```

## Android 端注意

当前 Android 端 ByteTrack 地址写死为：

```text
http://192.168.50.161:8000
```

如果 Linux 目标机 IP 不是这个地址，需要修改 Android 端配置或代码里的 ByteTrack baseUrl，否则 App 会连旧地址。

相关 Android 文件：

- `RemoteByteTrackEngine.kt`
- `SettingsHomeFragment.kt`

## 体积说明

这个 Linux 部署包不包含 Windows `.venv`，所以压缩包很小。首次部署时，Linux 会从 pip 下载依赖；安装后的虚拟环境大小取决于平台 wheel，通常会明显小于把 Windows `.venv` 直接拷过去，而且不会有跨平台不可用的问题。
