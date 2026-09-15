from pathlib import Path
import base64, hashlib, json, re, subprocess, sys, time, zipfile, zlib
import xml.etree.ElementTree as ET

ROOT=Path.cwd()
BRANCH='codex/portal-v4-three-methods'
EXPECTED='66bb07bcaa063fb319a62c38c8436ab00533ba73949c598df519c2637974c1cf'
REPORT=Path('/tmp/portal-v4-report.json')

def run(args, capture=False):
    print('$ '+' '.join(args), flush=True)
    p=subprocess.run(args,text=True,stdout=subprocess.PIPE if capture else None,stderr=subprocess.STDOUT if capture else None)
    if p.returncode: raise RuntimeError('Command failed: '+repr(args)+'\n'+(p.stdout or ''))
    return p.stdout if capture else ''

def allowed(name):
    p=Path(name)
    return not p.is_absolute() and '..' not in p.parts and (name.startswith('app/src/') or name.startswith('docs/portal_v4/'))

parts=[ROOT/f'tools/_portal_v4_payload.{i}' for i in range(1,8)]
encoded=''.join(p.read_text().strip() for p in parts)
# Correct a known single-character transport typo, then require the original complete payload hash.
encoded=encoded.replace('9daIpui5nLT','9daIpui1nLT')
raw=zlib.decompress(base64.b64decode(encoded,validate=True))
assert hashlib.sha256(raw).hexdigest()==EXPECTED, 'Payload hash mismatch'
data=json.loads(raw)
patch=data['patch']
modified=re.findall(r'^diff --git a/(\S+) b/',patch,re.M)
assert modified and all(allowed(p) for p in modified)
assert all(allowed(p) and not (ROOT/p).exists() for p in data['new_files'])
print('Verified payload',len(raw),len(modified),len(data['new_files']),flush=True)
Path('/tmp/portal-v4.patch').write_text(patch,encoding='utf-8')
run(['git','apply','--check','/tmp/portal-v4.patch'])
run(['git','apply','/tmp/portal-v4.patch'])
for name,text in data['new_files'].items():
    p=ROOT/name;p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(text.rstrip()+'\n',encoding='utf-8')
run(['git','diff','--check'])

models={
 'yolo26s_w8a32.tflite':'7a598838082251ef8e1d3b8f06f356ad532f8fe3a24c7eeeed68b52d28525036',
 'yolo26s-pose_w8a32.tflite':'8409d9109f1bb374e28a9ff925205580e62cbcf73528b11777b667f8e28611f8',
}
for name,sha in models.items():
    assert hashlib.sha256((ROOT/'app/src/main/assets'/name).read_bytes()).hexdigest()==sha,name

# Run the actual Android toolchain. Keep its output available even if compilation fails.
p=subprocess.run(['bash','gradlew',':app:testDebugUnitTest',':app:assembleDebug','--stacktrace'],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
Path('/tmp/portal-v4-build.log').write_text(p.stdout,encoding='utf-8')
print(p.stdout,flush=True)
if p.returncode: raise RuntimeError('Android tests/build failed; no implementation commit produced')

# Validate the actual official Android AAR, not just online JavaDoc.
aars=list((Path.home()/'.gradle/caches/modules-2/files-2.1/org.opencv/opencv/4.13.0').rglob('*.aar'))
assert aars,'OpenCV AAR not found'
import io
with zipfile.ZipFile(aars[0]) as a:
    with zipfile.ZipFile(io.BytesIO(a.read('classes.jar'))) as classes:
        for cls in ['org/opencv/video/BackgroundSubtractorMOG2.class','org/opencv/video/Video.class']:
            assert cls in classes.namelist(),cls
    assert any(n.startswith('jni/arm64-v8a/') and n.endswith('.so') for n in a.namelist())

tests=failures=errors=skipped=0
suites={}
for path in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    root=ET.parse(path).getroot()
    n=int(root.attrib.get('tests',0));tests+=n
    failures+=int(root.attrib.get('failures',0));errors+=int(root.attrib.get('errors',0));skipped+=int(root.attrib.get('skipped',0))
    suites[root.attrib.get('name',path.name)]=n
assert tests>=80 and failures==0 and errors==0,(tests,failures,errors)
apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
with zipfile.ZipFile(apk) as z:
    for name,sha in models.items():
        assert hashlib.sha256(z.read('assets/'+name)).hexdigest()==sha,name
    assert not any('yolo11' in n or ('yolo26' in n and 'float16' in n) for n in z.namelist())
    abis=sorted({n.split('/')[1] for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')})
    assert abis==['arm64-v8a'],abis
report=dict(tests=tests,failures=failures,errors=errors,skipped=skipped,suites=suites,
 apk_size=apk.stat().st_size,apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
 baseline_apk_size=128171337,abis=abis,model_sha256=models,
 opencv_aar_verified=str(aars[0].name),device_installed=False,phone_fps_measured=False)
REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('FINAL_REPORT='+json.dumps(report,ensure_ascii=False),flush=True)

history=ROOT/'codexHistory.md'
old=history.read_text(encoding='utf-8')
number=max(map(int,re.findall(r'^## \[(\d+)\]',old,re.M)))+1
now=run(['date','-u','+%Y-%m-%d %H:%M:%S'],True).strip()+' UTC'
entry=f'''## [{number}] {now} - 新增三种可切换 V4 门口算法及参数设置

**用户指令**：
> 实现差分高帧率、含光流精度候选、成熟开源第三方案，在 APK 设置中选择测试，并说明参数调整和全部失败后的下一步。

**实现方案**：

* **变更摘要**
  * 任务目的：新增 V4-A 差分与固定背景、V4-B 门口双向 LK、V4-C OpenCV MOG2 三种真实视觉后端，共用有限门底边和独立人数账本。
  * 修改文件：GateConfig/Vision/Lk/RoomAlgorithm/Runtime/Overlay/Settings、新增测试及 docs/portal_v4 三份说明；精确接入 Registry、VideoFeeder、YoloPoseAnalyzer、MainActivity、PoseDrawer 和设置页；V3 核心新增默认关闭的策略扩展。
  * 涉及方法：截图前背压、采样周期、同帧元数据、无积压单帧对处理、人体归属前景、背景恢复、有限跨门、局部双向 LK、MOG2 阴影排除、参数持久化、初始人数复用和事件去重。
  * 语义边界：门框图像坐标不当作真实深度；差分归零、点丢失和旧位置空出不独立触发进门；多人及相邻门歧义保持待定。
  * 性能边界：新 V4 不执行 V3 灰度队列及逐帧补算；截图前预留单线程推理名额；显示实际总耗时、输出帧率、跳过采样次数；未声称真机达到 10/20fps。原 V3 可选择但仍保留原工作方式。
  * 参数：每种方法单独保存采样/截图/视觉分辨率、差分、背景、门槛、消失确认和准入参数；B 有点数、金字塔、双向误差、预算；C 有 MOG2 方差和学习率。窗口坐标仍是二维图像，未实现虚假的深度分层。
  * 兼容：两套 YOLO26 模型 SHA256 不变，既有 Tracker 和阈值不变；截图缩小会影响二次裁切与手部细节，说明中提供 2560 长边恢复原图细节的选项。
  * 验证：Android 单元测试 {tests} 项通过，失败 {failures}、错误 {errors}、跳过 {skipped}；:app:assembleDebug 通过；实际 OpenCV 4.13 AAR 含 MOG2/Video 类与 arm64 原生库。
  * 安装包：{report['apk_size']} 字节；相比 V3 {report['apk_size']-128171337:+d} 字节；SHA256 {report['apk_sha256']}；ABI 仅 arm64-v8a。
  * 限制：没有真机或用户视频验证；差分/C 不伪造出门早期人体轨迹，B 有界逆向回看仅为来源辅助；隐藏初始人数须由已有初值设置说明；没有足够可见证据时不能保证每次唯一判定。

---

'''
idx=old.index('## [')
history.write_text(old[:idx]+entry+old[idx:],encoding='utf-8')
user='充分思考刚才的讨论结果，使用最大算力（接受任意一长的思考时间）去实现如下几个方案，让我可以在 APK 里面去选择测试：1.差分法（或者帧数高的其他方案） 2.含有光流法（可以接受一定程度的性能下降，追求最高准确率）3.其他成熟开源的方案或者思路方案做一版。4.如果这三个方法都不行，分别有哪些可以调整的参数？如果都还是不行下一步做什么？'
assistant='我会先核对当前分支和性能瓶颈，再实现三种可切换方案，补上参数说明，并完成编译和回归测试。'
userfile=ROOT/'tools/_dialogue_user_current.txt';assistantfile=ROOT/'tools/_dialogue_assistant_current.txt'
originals={p:p.read_bytes() if p.exists() else None for p in (userfile,assistantfile)}
userfile.write_text(user,encoding='utf-8');assistantfile.write_text(assistant,encoding='utf-8')
run(['python','tools/dialogue_archive.py','append-turn','--user-file',str(userfile),'--assistant-file',str(assistantfile),'--title','三种 V4 门口视觉方案与参数设置'])
for p,content in originals.items():
    if content is None: p.unlink(missing_ok=True)
    else: p.write_bytes(content)

cleanup=['.github/workflows/portal-v4-verify.yml','tools/_portal_v4_apply.py']+[str(p.relative_to(ROOT)) for p in parts]
for name in cleanup: (ROOT/name).unlink(missing_ok=True)
paths=sorted(set(modified+list(data['new_files'])+['codexHistory.md','dialogueHistory.md']+cleanup))
run(['git','add','--']+paths)
run(['git','diff','--cached','--check'])
staged=set(run(['git','diff','--cached','--name-only'],True).splitlines())
assert staged.issubset(paths),staged-set(paths)
assert not any(Path(p).name in ('local.properties','.DS_Store') for p in staged)
run(['git','config','user.name','github-actions[bot]'])
run(['git','config','user.email','41898282+github-actions[bot]@users.noreply.github.com'])
run(['git','commit','-m','feat: add selectable difference, local LK and MOG2 portal algorithms'])
run(['git','push','origin','HEAD:'+BRANCH])
print('IMPLEMENTATION_COMMIT='+run(['git','rev-parse','HEAD'],True).strip(),flush=True)
