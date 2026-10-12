import unittest,json,hashlib,tempfile
from pathlib import Path
from quality_gate import score,publications,audit_snapshot,score_predictions

def truth(t=1000,i=1):return dict(id=i,timeMs=t,portalRoomId='r',direction='ENTER')
def event(t=1000):return dict(responseMs=t,eventMs=t,roomId='r',direction='ENTER')

class GateTests(unittest.TestCase):
    def test_hard_bounds(self):
        for t in (900,1000,1500,2000):self.assertEqual('PASS',score([truth()],[event(t)])['status'])
        for t in (899,2001):self.assertEqual('FAIL',score([truth()],[event(t)])['status'])
    def test_duplicates_fail(self):
        r=score([truth()],[event(),event()]);self.assertEqual(1,r['matched']);self.assertEqual(1,r['extras']);self.assertEqual('FAIL',r['status'])
    def test_max_matching_avoids_greedy_stealing(self):
        r=score([truth(),truth(1150,2)],[event(900),event(1050)]);self.assertEqual('PASS',r['status'])
    def test_backdating_does_not_hide_lag(self):
        d=dict(frames=[dict(timeMs=3000,outputs=[dict(timeMs=1000,direction='ENTER',gateId='g',to='r',track=1)])])
        self.assertEqual('FAIL',score([truth()],publications(d))['status'])
    def test_wrong_direction_and_room_are_extra(self):
        e=event();e['direction']='EXIT';r=score([truth()],[e]);self.assertEqual(0,r['matched']);self.assertEqual(1,r['extras'])
        e=event();e['roomId']='other';self.assertEqual(0,score([truth()],[e])['matched'])
    def test_missing_truth_is_not_zero_out_of_zero_pass(self):
        with self.assertRaises(ValueError):score([],[])
    def test_reversed_frames_are_rejected(self):
        with self.assertRaises(ValueError):publications(dict(frames=[dict(timeMs=100),dict(timeMs=90)]))
    def test_recorded_failures_remain_failures(self):
        r=audit_snapshot(Path(__file__).with_name('recorded_outcomes.json'))
        self.assertEqual([27,16,24,4,0,12],[a['quality']['matched'] for a in r])
        self.assertTrue(all(a['quality']['status']=='FAIL' for a in r))
        self.assertEqual([1,8,3,3,27,3],[a['quality']['extras'] for a in r])

class PredictionContractTests(unittest.TestCase):
    def trial(self, mutator=lambda data: None):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'input.json';q=Path(d)/'predictions.json'
            raw=dict(groundTruth=[truth()],frames=[dict(timeMs=1100,frameSeq=10,
                input=dict(context=dict(gates=[dict(id='g',room='r')]))),
                dict(timeMs=1200,frameSeq=11,input=dict(context=None))])
            p.write_text(json.dumps(raw))
            run=dict(sourceSha256=hashlib.sha256(p.read_bytes()).hexdigest(),
                status='COMPLETE',consumedFrames=2,
                events=[dict(responseFrameSeq=10,roomId='r',gateId='g',direction='ENTER')])
            pred=dict(codeSha='a'*40,runs=[run]);mutator(pred)
            q.write_text(json.dumps(pred))
            return score_predictions([p],q)
    def test_new_prediction_can_pass_without_rewriting_original_recording(self):
        r=self.trial();self.assertEqual('PASS',r[0]['quality']['status'])
        self.assertEqual(100,r[0]['quality']['pairs'][0]['deltaMs'])
    def test_backdated_prediction_rejected(self):
        with self.assertRaises(ValueError):
            self.trial(lambda x:x['runs'][0]['events'][0].update(responseMs=1000))
    def test_invented_frame_rejected(self):
        with self.assertRaises(ValueError):
            self.trial(lambda x:x['runs'][0]['events'][0].update(responseFrameSeq=99))
    def test_missing_measurement_support_is_blocked_not_pass(self):
        with self.assertRaises(ValueError):
            self.trial(lambda x:x['runs'][0].update(status='BLOCKED'))
    def test_shortened_recording_cannot_pass(self):
        with self.assertRaises(ValueError):
            self.trial(lambda x:x['runs'][0].update(consumedFrames=1))
    def test_false_room_identity_rejected(self):
        with self.assertRaises(ValueError):
            self.trial(lambda x:x['runs'][0]['events'][0].update(roomId='another'))
    def test_score_refuses_nonfinite_or_float_time(self):
        with self.assertRaises(ValueError):score([truth()],[event(float('nan'))])

if __name__=='__main__':unittest.main()
