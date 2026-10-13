#!/usr/bin/env python3
"""Run the native detector and the UNCHANGED strict project acceptance checker.
This proves known-recording replay, not independent-action/video acceptance.
"""
from __future__ import annotations
import argparse,hashlib,importlib.util,json,shutil,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def load_auditor():
    p=ROOT/'tools/portal_acceptance/strict_portal_acceptance.py'
    spec=importlib.util.spec_from_file_location('strict_portal_acceptance',p)
    mod=importlib.util.module_from_spec(spec)
    import sys
    sys.modules[spec.name]=mod;spec.loader.exec_module(mod)
    return mod

def main():
    pa=argparse.ArgumentParser();pa.add_argument('--output-dir',type=Path,default=ROOT/'build/portal_temporal');args=pa.parse_args()
    if not shutil.which('kotlinc') or not shutil.which('java'):raise SystemExit('JDK and kotlinc are required; alternatively use the Android JUnit tests.')
    out=args.output_dir.resolve();out.mkdir(parents=True,exist_ok=True)
    core=ROOT/'app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/temporal/TemporalPortalCore.kt'
    harness=ROOT/'tools/portal_temporal/NativeReplayMain.kt';jar=out/'native.jar'
    subprocess.run(['kotlinc',str(core),str(harness),'-include-runtime','-d',str(jar)],check=True)
    meta=json.loads((ROOT/'tools/portal_temporal/recordings_manifest.json').read_text(encoding='utf-8'))
    auditor=load_auditor();reports=[]
    for rec in meta:
        fixture=ROOT/f"tools/portal_temporal/fixtures/recording_{rec['id']}.replay.gz"
        assert hashlib.sha256(fixture.read_bytes()).hexdigest()==rec['fixture_sha256']
        pred=out/f"predictions_{rec['id']}.json"
        subprocess.run(['java','-Xmx128m','-jar',str(jar),str(ROOT/'app/src/main/assets/portal_temporal/scene_model.bin.gz'),str(fixture),str(pred)],check=True)
        data=json.loads(pred.read_text(encoding='utf-8'))
        # Preserve the original extra metadata such as the marked source-frame
        # index. These annotations are used by the evaluator, NEVER the detector.
        narrow=lambda gs:[(g['id'],g['timeMs'],g['portalRoomId'],g['direction']) for g in gs]
        assert narrow(data['groundTruth'])==narrow(rec['truth'])
        data['groundTruth']=rec['truth'];pred.write_text(json.dumps(data,ensure_ascii=False))
        rep=auditor.audit(data,expected_truth_sha256=auditor.EXPECTED_TRUTH_SHA256);rep['recording']=rec['name']
        rep['scope']='known-scene supervised replay; NOT independent new-action validation'
        reports.append(rep)
        print(rec['id'],rep['event_contract_status'],rep['hard_matches'],rep['within500_matches'],rep['unmatched_outputs'])
    (out/'strict_reports.json').write_text(json.dumps(reports,ensure_ascii=False,indent=2))
    return 0 if all(r['event_contract_status']=='PASS' and r['preferred_latency_target_status']=='PASS' for r in reports) else 1
if __name__=='__main__':raise SystemExit(main())
