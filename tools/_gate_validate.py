from pathlib import Path
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
os.chdir(ROOT)
BRANCH='codex/portal-three-engines'
if os.environ.get('GITHUB_REF_NAME') != BRANCH:
    raise RuntimeError('Validation is restricted to the implementation branch')

def run(args, capture=False):
    print('$ '+' '.join(args),flush=True)
    return subprocess.run(args,check=True,text=True,stdout=subprocess.PIPE if capture else None).stdout

def write(path,text):
    path=ROOT/path
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(text.rstrip()+'\n',encoding='utf-8')

print((ROOT/'AGENTS.md').read_text(encoding='utf-8'))
run([sys.executable,'tools/_gate_integrate.py'])
run(['git','diff','--check'])
run(['bash','gradlew',':app:testDebugUnitTest',':app:assembleDebug','--stacktrace'])

suites=[]
for p in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    r=ET.parse(p).getroot()
    suites.append({'name':r.attrib['name'],'tests':int(r.attrib['tests']),'failures':int(r.attrib['failures']),'errors':int(r.attrib['errors']),'skipped':int(r.attrib.get('skipped','0'))})
if not suites or any(r['failures'] or r['errors'] for r in suites):raise RuntimeError('Missing or failed unit tests')
if sum(r['tests'] for r in suites if r['name'].endswith('GateDecisionCoreTest'))<52:raise RuntimeError('New regression suite did not execute')
apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
expected={'yolo26s_w8a32.tflite':'7a598838082251ef8e1d3b8f06f356ad532f8fe3a24c7eeeed68b52d28525036','yolo26s-pose_w8a32.tflite':'8409d9109f1bb374e28a9ff925205580e62cbcf73528b11777b667f8e28611f8'}
with zipfile.ZipFile(apk) as z:
    names=z.namelist()
    abis=sorted({s.split('/')[1] for s in names if s.startswith('lib/') and s.endswith('.so')})
    assert abis==['arm64-v8a'],abis
    for name,sha in expected.items():assert hashlib.sha256(z.read('assets/'+name)).hexdigest()==sha,name
    dex=b''.join(z.read(name) for name in names if name.endswith('.dex'))
    for text in [b'gate_diff',b'gate_flow',b'gate_mog2',b'GateDecisionCore',b'BackgroundSubtractorMOG2']:
        assert text in dex,text

report={'tests':suites,'total_tests':sum(r['tests'] for r in suites),'apk_bytes':apk.stat().st_size,
        'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'baseline_bytes':128171337,
        'delta_bytes':apk.stat().st_size-128171337,'abis':abis,'models_sha256':expected,
        'device_installed':False,'device_fps_measured':False,'user_video_accuracy_measured':False}
Path('/tmp/gate-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('VALIDATION_REPORT='+json.dumps(report,ensure_ascii=False))
write('docs/gate_engines/04_build_report.md',f'''# 构建验证记录

- Android 单元测试：{report['total_tests']} 项；失败 0；错误 0。
- `:app:assembleDebug`：成功。
- APK：{report['apk_bytes']} 字节，较上一版变化 {report['delta_bytes']:+d} 字节。
- APK SHA256：`{report['apk_sha256']}`。
- 原生 ABI：仅 `arm64-v8a`。
- 两个 YOLO26 w8a32 模型的打包 SHA256 与改造前一致。
- 已检查三种新算法标识与 MOG2 类实际进入 APK。
- 未连接 Android 设备，未测真实手机帧率，未跑用户门口视频准确率验证。

该结果证明本次源码可构建、逻辑回归通过，不等于已经证明真实场景识别率或 20fps 性能。
''')

history=ROOT/'codexHistory.md'
s=history.read_text(encoding='utf-8')
number=max(map(int,re.findall(r'^## \[(\d+)\]',s,re.M)),default=0)+1
time=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S')
entry=f'''## [{number}] {time} - 新增三套可切换门口算法与有界回放调度

**用户指令**：
> 实现差分、含光流及成熟开源思路的三套 APK 可选算法，并提供分别可调的参数和全部失败后的处理路线。

**实现方案**：

* 变更摘要：新增门口差分、局部双向 LK、OpenCV MOG2 三套视觉实现，共用可靠地面有限门底边及独立人数账本；保留 Legacy/V2/V3。
* 性能修正：新三版在截图前准入，工作忙则不截图；不启用 V3 灰度队列，不逐帧补算积压；只在工作线程处理门口图像；默认请求上限 20Hz，不假称实测 20fps。
* 安全规则：门框不冒充地板深度；人体误检不能靠时间自动转正；成功空检测、ROI 未覆盖、帧间断和背景恢复分别处理；多人及相邻门歧义不强制转移。
* 设置与诊断：三版参数独立持久化，增加默认/保守/性能预设、可编辑参数、保留人数的背景重建和最近诊断导出。
* 修改文件：新增 gate 目录七个源码文件与两个测试；精确调整 Registry、MainActivity、PoseDrawer、SettingsHomeFragment、VideoFeeder、VideoPlayerFacade、ExoVideoPlayer；新增四份说明并维护历史。
* 关键方法：`GateDecisionCore.step`、`LocalGateSensor.measure`、`analyzeGateFrame`、`GateSettings.show`、`GateOverlay.draw`。
* 验证：单测 {report['total_tests']} 项通过，assembleDebug 通过；APK {report['apk_bytes']} 字节，仅 arm64-v8a；模型 SHA256 未变。
* 限制：没有真机安装、用户视频准确率或实际 FPS 验证；玻璃反射及完全不可观测的穿门仍不声称解决。

---

'''
if not s.startswith('# Codex History'):raise RuntimeError('Unexpected history header')
s=s.replace('# Codex History\n\n','# Codex History\n\n'+entry,1)
history.write_text(s,encoding='utf-8')
user='充分思考刚才的讨论结果，使用最大算力（接受任意一长的思考时间）去实现如下几个方案，让我可以在 APK 里面去选择测试：1.差分法（或者帧数高的其他方案） 2.含有光流法（可以接受一定程度的性能下降，追求最高准确率）3.其他成熟开源的方案或者思路方案做一版。4.如果这三个方法都不行，分别有哪些可以调整的参数？如果都还是不行下一步做什么？'
assistant='我会先核对当前分支和帧处理链，再实现三套可切换方案，并把参数、性能诊断和失败后的处理办法一起交付。'
write('tools/_dialogue_user_current.txt',user)
write('tools/_dialogue_assistant_current.txt',assistant)
run([sys.executable,'tools/dialogue_archive.py','append-turn','--user-file','tools/_dialogue_user_current.txt','--assistant-file','tools/_dialogue_assistant_current.txt','--title','三套可切换门口视觉算法','--time',time])
# Preserve the project's fixed temporary-file convention, without exporting unrelated changes.
run(['git','checkout','--','tools/_dialogue_user_current.txt','tools/_dialogue_assistant_current.txt'])

for name in ['tools/_gate_integrate.py','tools/_gate_validate.py','.github/workflows/gate-engines-build.yml']:
    (ROOT/name).unlink(missing_ok=True)
paths=[b for b in run(['git','status','--porcelain','-z'],True).split('\0') if b]
allowed={
 'app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/RoomAlgorithmRegistry.kt',
 'app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt',
 'app/src/main/java/com/example/roomxxx0102/ui/drawers/PoseDrawer.kt',
 'app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt',
 'app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt',
 'app/src/main/java/com/example/roomxxx0102/logic/video/VideoPlayerFacade.kt',
 'app/src/main/java/com/example/roomxxx0102/logic/video/ExoVideoPlayer.kt',
 'codexHistory.md','dialogueHistory.md','docs/gate_engines/04_build_report.md',
 'tools/_gate_integrate.py','tools/_gate_validate.py','.github/workflows/gate-engines-build.yml'}
changed=[line[3:] for line in paths]
if set(changed)-allowed:raise RuntimeError('Unrelated file changes: '+str(set(changed)-allowed))
run(['git','add','--']+changed)
run(['git','diff','--cached','--check'])
run(['git','config','user.name','github-actions[bot]'])
run(['git','config','user.email','41898282+github-actions[bot]@users.noreply.github.com'])
run(['git','commit','-m','feat: integrate tested gate engines, parameters and bounded frame sampling'])
run(['git','push','origin','HEAD:'+BRANCH])
