from pathlib import Path
import hashlib,json,subprocess,re,zipfile
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]

def edit(path,old,new):
    s=path.read_text(encoding='utf-8')
    assert s.count(old)==1,(path,old)
    path.write_text(s.replace(old,new),encoding='utf-8',newline='\n')

def run(args,capture=False):
    print('$',' '.join(args),flush=True)
    return subprocess.run(args,cwd=ROOT,check=True,text=True,stdout=subprocess.PIPE if capture else None)

base=ROOT/'app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/flow'
edit(base/'PortalV3Core.kt','    fun profileSamples():', '''    fun detachIdentitySource(): FlowDecision {
        people.entries.removeAll { !it.value.accepted }
        for (p in people.values) {
            p.track = -p.number; p.bootstrap = true; p.lastDetection = -100000L
            p.ground = null; p.lastStrong = -1L; p.history.clear(); p.attempt = null
            p.terminal.clear(); p.terminalFrames.clear(); p.detectorMissingSince = -1L
            p.motionTrace.clear(); p.motion = 0.0; p.conflict = false
            p.status = "IDENTITY_SOURCE_CHANGED_RETAIN_COUNTS"
        }
        return snapshot(listOf("IDENTITY_SOURCE_CHANGED_RETAIN_COUNTS"))
    }

    fun profileSamples():''')
edit(base/'PortalV3Core.kt','        val firstRoom = origin?.room ?: initialRoom','''        val firstRoom = origin?.room ?: initialRoom
        if (people.values.any { it.number != p.number && it.accepted && it.bootstrap && it.room == null }) {
            p.status = "UNRESOLVED_IDENTITY"; return
        }''')
a=base/'PortalV3RoomAlgorithm.kt'
edit(a,'        val idSource = input.poses.firstOrNull()?.idSource?.name ?: identitySource','''        val idSource = input.poses.firstOrNull()?.idSource?.name ?: identitySource
        val applyStartCounts = baseline.known && (!input.sceneInfo.isVideoPlayback || (meta?.stamp?.timestampMs ?: input.timestampMs) <= 500L)''')
edit(a,' || (identitySource.isNotEmpty() && idSource.isNotEmpty() && identitySource != idSource)) {',') {')
edit(a,'aspect,baseline.counts)','aspect,if(applyStartCounts) baseline.counts else emptyMap())')
edit(a,'initialKnown=baseline.known','initialKnown=applyStartCounts')
edit(a,'        identitySource=idSource','''        if (identitySource.isNotEmpty() && idSource.isNotEmpty() && identitySource != idSource) {
            lastDecision = core!!.detachIdentitySource()
            vision?.close(); vision = PortalV3Vision(core!!.gates); originTried.clear()
        }
        identitySource=idSource''')
t=ROOT/'app/src/test/java/com/example/roomxxx0102/logic/roomalgorithm/flow/PortalV3CoreTest.kt'
s=t.read_text(encoding='utf-8');i=s.rfind('}');assert i>0
s=s[:i]+'''
    @Test fun identityBackendChangePreservesOccupiedRoomSlots() {
        val e=engine(); enter(e)
        val detached=e.detachIdentitySource()
        assertEquals(1,detached.counts["A"])
        assertEquals(1,detached.counts.values.sum())
        e.step(900,listOf(d(.43,id=99)))
        e.step(1050,listOf(d(.45,id=99)))
        e.step(1200,listOf(d(.47,id=99)))
        e.step(1300,listOf(d(.53,id=99)))
        val r=e.step(1400,listOf(d(.56,id=99)))
        assertEquals(1,r.counts["L"]);assertEquals(1,r.counts.values.sum())
    }
'''+s[i:]
t.write_text(s,encoding='utf-8',newline='\n')
docs=ROOT/'docs/portal_v3'
edit(docs/'02_exit_and_house_counts.md','## 五、人数不是每帧检测框数量', '''当身份关联在本地与远端之间切换时，不清空已经确认的房间人数。旧检测 ID 解除绑定，保留匿名占用名额，再由新的可靠观测重新接续；不能因为服务器回退，把整个住宅突然归零。已有名额自身位置不明时，不随便新增一个可能重复的人。

## 五、人数不是每帧检测框数量''')
edit(docs/'02_exit_and_house_counts.md','起始人数被建立为匿名占用名额。', '视频仅在开头 500ms 内启动新算法会话时应用已保存初值。直接从中途开始或倒退到中途，会显示初值未知，不把时间零点的人数冒充此刻人数。\n\n起始人数被建立为匿名占用名额。')
edit(docs/'04_human_admission_and_delivery.md','## ', '## ') if False else None

run(['git','diff','--check'])
run(['bash','gradlew',':app:testDebugUnitTest',':app:assembleDebug','--stacktrace'])
apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
assert apk.is_file()
models={
'assets/yolo26s_w8a32.tflite':'7a598838082251ef8e1d3b8f06f356ad532f8fe3a24c7eeeed68b52d28525036',
'assets/yolo26s-pose_w8a32.tflite':'8409d9109f1bb374e28a9ff925205580e62cbcf73528b11777b667f8e28611f8'}
with zipfile.ZipFile(apk) as z:
    names=z.namelist()
    abis=sorted({n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so')})
    assert abis==['arm64-v8a']
    assert {n for n in names if n.startswith('assets/yolo') and n.endswith('.tflite')}==set(models)
    for name,h in models.items(): assert hashlib.sha256(z.read(name)).hexdigest()==h
    assert any('opencv' in n.lower() and n.endswith('.so') for n in names)
suites=[]
for p in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    x=ET.parse(p).getroot()
    suites.append({k:x.attrib.get(k,'0') for k in ('name','tests','failures','errors','skipped')})
assert suites and sum(int(s['failures'])+int(s['errors']) for s in suites)==0
v3=next(s for s in suites if 'PortalV3CoreTest' in s['name']);assert int(v3['tests'])>=32
report={'algorithm':'portal_v3_flow','runtimeTag':'PortalV3-FlowGate-1.0','test_count':sum(int(s['tests']) for s in suites),'tests':suites,'apk_bytes':apk.stat().st_size,'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'previous_apk_bytes':128056649,'apk_delta_bytes':apk.stat().st_size-128056649,'abis':abis,'model_sha256':models,'device_install':'未连接真机，未执行安装','behavior_validation':'合成序列回归通过；用户实拍视频与手机性能仍待实测'}
h=ROOT/'codexHistory.md';text=h.read_text(encoding='utf-8')
start=text.index('## [395]');end=text.index('## [394]',start)
entry=text[start:end]
entry,n=re.subn(r'现有及新增单元测试合计 \d+ 项通过，其中 PortalV3CoreTest \d+ 项',f"现有及新增单元测试合计 {report['test_count']} 项通过，其中 PortalV3CoreTest {v3['tests']} 项",entry);assert n==1
entry,n=re.subn(r'安装包：\d+ 字节，较前版变化 [+-]?\d+ 字节；SHA256 [a-f0-9]+',f"安装包：{report['apk_bytes']} 字节，较前版变化 {report['apk_delta_bytes']:+d} 字节；SHA256 {report['apk_sha256']}",entry);assert n==1
entry=entry.replace('  * 学习边界：','  * 补充安全检查：本地/远端身份切换保留匿名人数名额而非清空账本；中途启动不误用视频零点初值；新增相应回归测试。\n  * 学习边界：')
h.write_text(text[:start]+entry+text[end:],encoding='utf-8',newline='\n')
Path('/tmp/portal-v3-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
for p in (ROOT/'tools/_portal_v3_safety.py',ROOT/'.github/workflows/portal-v3-build.yml'): p.unlink()
allowed=['app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/flow/PortalV3Core.kt','app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/flow/PortalV3RoomAlgorithm.kt','app/src/test/java/com/example/roomxxx0102/logic/roomalgorithm/flow/PortalV3CoreTest.kt','docs/portal_v3/02_exit_and_house_counts.md','codexHistory.md','tools/_portal_v3_safety.py','.github/workflows/portal-v3-build.yml']
run(['git','add','--']+allowed)
assert set(run(['git','diff','--cached','--name-only'],True).stdout.splitlines())<=set(allowed)
run(['git','diff','--cached','--check'])
run(['git','config','user.name','github-actions[bot]']);run(['git','config','user.email','41898282+github-actions[bot]@users.noreply.github.com'])
run(['git','commit','-m','fix: retain room ledger across identity backend changes'])
report['implementation_commit']=run(['git','rev-parse','HEAD'],True).stdout.strip()
Path('/tmp/portal-v3-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
run(['git','push','origin','HEAD:codex/portal-v3-flow'])
print('PORTAL_V3_REPORT='+json.dumps(report,ensure_ascii=False),flush=True)
