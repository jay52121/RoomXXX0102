#!/usr/bin/env python3
"""仅用于本次隔离验证，成功提交前删除自身及传输文件。"""
from pathlib import Path, PurePosixPath
import base64, hashlib, json, lzma, os, re, subprocess, tempfile, zipfile
import xml.etree.ElementTree as ET
from datetime import datetime
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
BRANCH = 'codex/portal-v3-flow'
EXPECTED = '68460a47a07b56471e8e12fdddc0059cd6a8c6fb45a30a529fa244119acda02b'

def run(args, capture=False):
    print('$', ' '.join(args), flush=True)
    return subprocess.run(args,cwd=ROOT,check=True,text=True,stdout=subprocess.PIPE if capture else None)

def write(path, text):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(text,encoding='utf-8',newline='\n')

print((ROOT/'AGENTS.md').read_text(encoding='utf-8'))
parts = [ROOT/f'tools/portal_v3_payload.{i}' for i in range(1,6)]
raw = lzma.decompress(base64.b64decode(''.join(p.read_text().strip() for p in parts), validate=True))
assert hashlib.sha256(raw).hexdigest() == EXPECTED, '传输内容校验失败，拒绝修改'
payload = json.loads(raw)
for name in payload['changed']:
    assert not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts
    assert name.startswith(('app/src/','docs/portal_v3/'))
patch = Path('/tmp/portal-v3-existing.patch')
write(patch,payload['patch'])
run(['git','apply','--check',str(patch)])
run(['git','apply',str(patch)])
for name, content in payload['files'].items():
    assert name in payload['changed']
    write(ROOT/name, content)

# 遮挡后旧脚点可留作历史证据，但不能无限期画成当前可靠观测。
core = ROOT/'app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/flow/PortalV3Core.kt'
s = core.read_text(encoding='utf-8')
a = '            if (!p.accepted && timeMs - p.lastDetection > 2000) people.remove(p.number)'
b = '            if (timeMs - p.lastStrong > 450) p.ground = null\n' + a
assert s.count(a)==1
write(core,s.replace(a,b))

run(['git','diff','--check'])
run(['bash','gradlew',':app:testDebugUnitTest',':app:assembleDebug','--stacktrace'])
apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
assert apk.is_file()
models={
 'assets/yolo26s_w8a32.tflite':'7a598838082251ef8e1d3b8f06f356ad532f8fe3a24c7eeeed68b52d28525036',
 'assets/yolo26s-pose_w8a32.tflite':'8409d9109f1bb374e28a9ff925205580e62cbcf73528b11777b667f8e28611f8',
}
with zipfile.ZipFile(apk) as z:
    names=z.namelist()
    abis=sorted({n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so')})
    assert abis==['arm64-v8a'],abis
    assert {n for n in names if n.startswith('assets/yolo') and n.endswith('.tflite')}==set(models)
    for n,h in models.items():
        assert hashlib.sha256(z.read(n)).hexdigest()==h,n
    assert any('opencv' in n.lower() and n.endswith('.so') for n in names)
suites=[]
for p in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    x=ET.parse(p).getroot()
    suites.append({k:x.attrib.get(k,'0') for k in ['name','tests','failures','errors','skipped']})
assert suites
assert sum(int(s['failures'])+int(s['errors']) for s in suites)==0
v3=[s for s in suites if 'PortalV3CoreTest' in s['name']]
assert v3 and int(v3[0]['tests'])>=29,v3
report={'algorithm':'portal_v3_flow','runtimeTag':'PortalV3-FlowGate-1.0','tests':suites,'test_count':sum(int(s['tests']) for s in suites),'apk_bytes':apk.stat().st_size,'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'previous_apk_bytes':128056649,'apk_delta_bytes':apk.stat().st_size-128056649,'abis':abis,'model_sha256':models,'device_install':'未连接真机，未进行安装和手机性能验证','behavior_validation':'通过合成序列回归；未获得用户实拍视频，不能声称真实门口准确率已达标'}
write(Path('/tmp/portal-v3-report.json'),json.dumps(report,ensure_ascii=False,indent=2)+'\n')

user_text='结合刚才的一切东西和我们项目本身，用最大的算力思考出一套视觉跟踪机制和最后的判定算法，来确定到底人是从哪儿到哪儿以及。最终确定整个房屋每个房间的人数。要注意房间中本身有一些遮挡。有时 yolo 判定 会突然消失，但并不意味着真正的进入了门。目前我们工程经验里面比较靠谱的还是通过。人和底边的位置来去判断的。现在我们需要一套全新的跟踪机制和门的跨越算法，最终确定。，输出包括多个 MD文件，第一个是。怎么判断人进入一个子房间（各种情况都要考虑到）？第二个是判断人怎么从子房间出来。第三个是怎么避免在客厅中游走的时候，被家具挡住一部分然后误判断进入了某房间。第四个是怎么避免被 yolo 误判断的人体所。影响比如客厅其实根本没有人，他突然把沙发的某个结构认为是人，这种情况下，我们在工程角度已经做了一些规避，需要有一些判断之后，才会把它锁定为一个人，所以这一部分需不需要加强和考虑进去，你也需要通读代码之后再决定。输出这 4 个文件之后，然后再完成一次完整的编码，不要问我，然后直接把新的算法放到 GitHub 里面可以直接运行的，然后我会根据测试视频来实际跑一下，来做一个验证。'
# 原文中的编号表述保持用户实际输入。
user_text=user_text.replace('第四个是怎么避免','第四是怎么避免')
assistant_text='我会先通读当前分支的跟踪、门线、人数与误检过滤代码，整理四份方案，再实现可在设置中切换的新算法并完成构建验证。'
u=ROOT/'tools/_dialogue_user_current.txt'; a=ROOT/'tools/_dialogue_assistant_current.txt'
previous={p:p.read_bytes() if p.exists() else None for p in (u,a)}
write(u,user_text);write(a,assistant_text)
run(['python','tools/dialogue_archive.py','append-turn','--user-file',str(u),'--assistant-file',str(a),'--title','四份方案及 Portal V3 人体光流门槛算法'])
for p,data in previous.items():
    if data is not None: p.write_bytes(data)
    else: p.unlink(missing_ok=True)
history=ROOT/'codexHistory.md'
old=history.read_text(encoding='utf-8')
number=max(map(int,re.findall(r'^## \[(\d+)\]',old,re.M)))+1
time=datetime.now(ZoneInfo('Asia/Taipei')).strftime('%Y-%m-%d %H:%M:%S')
entry=f'''## [{number}] {time} - 新增可切换 Portal V3 人体光流门槛算法

**用户指令**：
> 通读当前工程，分别输出进入、退出、家具遮挡和误检准入四份中文方案，并直接完成新视觉跟踪、门跨越和全屋人数算法，编译验证后提交到当前分支。

**实现方案**：

* **变更摘要**
  * 任务目的：以可靠人体地面支撑与有限门底边为主证据，补充人体归属光流、背景复现和出门逆向回溯；独立维护人数账本，保留旧算法可切换。
  * 修改文件：新增 PortalV3Types/Core/Vision/RoomAlgorithm/Overlay/Settings、PortalFrameHub、四份 docs/portal_v3 文档及回归测试；精确调整 Registry、帧元数据、VideoFeeder、YoloPoseAnalyzer、MainActivity、PoseDrawer 和设置页。
  * 涉及方法：正反向 LK 光流、身体约束分格补点、有限门线跨越、相邻门竞争、遮挡待定与终末证据、短时逆向回溯、独立人数槽位、初始人数配置、严格入账和事件去重。
  * 语义纠正：门框四边形不作为物理房间地板或深度；光流点死亡不等于人体消失；几百点按空间格统计而不是独立投票。
  * 保持现有锁定/远程跟踪/YOLO26 模型与阈值，不新增模型切换；V3 独立强化可信骨架、连续观测及像素运动准入。
  * 初始人数：设置新增当前视频/摄像机的起始人数编辑；未配置时明确提示隐藏初值未知，不把未知伪装成全屋为零。
  * 学习边界：仅从明确测得的跨门轨迹保存最近十二次方向弱先验；未声称实现完整遮挡深度学习或少样本必然收敛。
  * 验证：现有及新增单元测试合计 {report['test_count']} 项通过，其中 PortalV3CoreTest {v3[0]['tests']} 项；:app:assembleDebug 通过；仅 arm64-v8a；两套 w8a32 资产 SHA256 与改造前一致。
  * 安装包：{report['apk_bytes']} 字节，较前版变化 {report['apk_delta_bytes']:+d} 字节；SHA256 {report['apk_sha256']}。
  * 限制：未连接真机，未使用用户实际门口视频验证精度/耗时；玻璃门本轮未专门支持；没有足够阈值观测的极近侧门/盲区保持待定而非强行报进出。

---

'''
header='# Codex History\n\n'
assert old.startswith(header)
write(history,header+entry+old[len(header):])
for p in parts+[Path(__file__),ROOT/'.github/workflows/portal-v3-build.yml']:
    p.unlink(missing_ok=True)
allowed=set(payload['changed'])|{'codexHistory.md','dialogueHistory.md','tools/_portal_v3_apply.py','.github/workflows/portal-v3-build.yml'}|{f'tools/portal_v3_payload.{i}' for i in range(1,6)}
run(['git','add','--']+sorted(allowed))
staged=run(['git','diff','--cached','--name-only'],True).stdout.splitlines()
assert set(staged)<=allowed,staged
run(['git','diff','--cached','--check'])
run(['git','config','user.name','github-actions[bot]'])
run(['git','config','user.email','41898282+github-actions[bot]@users.noreply.github.com'])
run(['git','commit','-m','feat: add selectable Portal V3 optical-flow gate and occupancy engine'])
report['implementation_commit']=run(['git','rev-parse','HEAD'],True).stdout.strip()
write(Path('/tmp/portal-v3-report.json'),json.dumps(report,ensure_ascii=False,indent=2)+'\n')
run(['git','push','origin',f'HEAD:{BRANCH}'])
print('PORTAL_V3_REPORT='+json.dumps(report,ensure_ascii=False),flush=True)
