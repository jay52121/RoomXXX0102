import unittest
from strict_portal_acceptance import audit, digest


def recording(outs, gt=None):
    gt = gt or [dict(id=1,timeMs=1000,portalRoomId='A',direction='ENTER')]
    frames=[dict(timeMs=0,input={},outputs=[])]
    for t,room,direction,declared in outs:
        frames.append(dict(timeMs=t,input={},outputs=[dict(direction=direction,
            **{'from':'living_room' if direction=='ENTER' else room,
               'to':room if direction=='ENTER' else 'living_room'},
            gateId=room+'#1',timeMs=t if declared is None else declared)]))
    frames.sort(key=lambda f:f['timeMs'])
    frames.append(dict(timeMs=5000,input={},outputs=[]))
    return dict(algorithm='TEST',groundTruth=gt,frames=frames)


class TestStrictAcceptance(unittest.TestCase):
    def score(self,outs,gt=None):
        return audit(recording(outs,gt),expected_labels=len(gt) if gt else 1)
    def test_exact_boundary(self):
        for t in [900,1000,1500,2000]:
            r=self.score([(t,'A','ENTER',None)])
            self.assertEqual('PASS',r['event_contract_status'])
            self.assertEqual(1 if t<=1500 else 0,r['within500_matches'])
    def test_one_ms_early_fails(self):
        r=self.score([(899,'A','ENTER',None)])
        self.assertEqual('FAIL',r['event_contract_status']);self.assertEqual(1,r['unmatched_outputs'])
    def test_one_ms_late_fails(self):
        self.assertEqual('FAIL',self.score([(2001,'A','ENTER',None)])['event_contract_status'])
    def test_retrospective_stamp_cannot_pass(self):
        r=self.score([(2500,'A','ENTER',1000)])
        self.assertEqual(0,r['hard_matches']);self.assertEqual([0],r['event_time_differs_from_output_frame'])
    def test_correct_actual_time_is_not_confused_with_event_time(self):
        r=self.score([(1400,'A','ENTER',0)])
        self.assertEqual(1,r['hard_matches']);self.assertEqual(400,r['pairs'][0]['latency_ms'])
    def test_wrong_door_never_consumes_truth(self):
        r=self.score([(1000,'B','ENTER',None),(1100,'A','ENTER',None)])
        self.assertEqual(1,r['hard_matches']);self.assertEqual(1,r['unmatched_outputs'])
    def test_wrong_direction(self):
        self.assertEqual(0,self.score([(1000,'A','EXIT',None)])['hard_matches'])
    def test_duplicate_output_fails_even_if_truth_found(self):
        r=self.score([(1000,'A','ENTER',None),(1100,'A','ENTER',None)])
        self.assertEqual(1,r['hard_matches']);self.assertEqual('FAIL',r['event_contract_status'])
    def test_maximum_matching_not_greedy_nearest(self):
        gt=[dict(id=1,timeMs=1000,portalRoomId='A',direction='ENTER'),dict(id=2,timeMs=1150,portalRoomId='A',direction='ENTER')]
        r=self.score([(900,'A','ENTER',None),(1050,'A','ENTER',None)],gt)
        self.assertEqual(2,r['hard_matches']);self.assertEqual(0,r['unmatched_outputs'])
    def test_no_events_fails(self):
        self.assertEqual('FAIL',self.score([])['event_contract_status'])
    def test_incomplete_recording_rejected(self):
        d=recording([]);d['frames']=d['frames'][:-1]
        with self.assertRaises(ValueError):audit(d,expected_labels=1)
    def test_duplicate_frames_rejected(self):
        with self.assertRaises(ValueError):self.score([(1000,'A','ENTER',None),(1000,'A','ENTER',None)])
    def test_contract_label_count_is_not_silently_reduced(self):
        with self.assertRaises(ValueError):audit(recording([]))
    def test_pass_is_not_unmeasured_latency_or_generalization_claim(self):
        r=self.score([(1000,'A','ENTER',None)])
        self.assertEqual('NOT_ESTABLISHED',r['full_system_acceptance'])
        self.assertEqual('NOT_EVALUATED',r['independent_video_generalization_status'])
    def test_frozen_truth_detects_changed_marker(self):
        d=recording([(1200,'A','ENTER',None)])
        pinned=digest([{k: d['groundTruth'][0][k] for k in ('id','timeMs','direction','portalRoomId')}])
        self.assertEqual('PASS',audit(d,expected_labels=1,expected_truth_sha256=pinned)['event_contract_status'])
        d['groundTruth'][0]['timeMs']+=1
        with self.assertRaises(ValueError):audit(d,expected_labels=1,expected_truth_sha256=pinned)
    def test_frame_annotations_do_not_change_marker_identity(self):
        d=recording([(1200,'A','ENTER',None)])
        pinned=digest(d['groundTruth'])
        d['groundTruth'][0]['frame']=30
        self.assertEqual('PASS',audit(d,expected_labels=1,expected_truth_sha256=pinned)['event_contract_status'])
    def test_truth_direction_and_room_are_frozen(self):
        for key,value in [('direction','EXIT'),('portalRoomId','B')]:
            d=recording([(1200,'A','ENTER',None)]);pinned=digest(d['groundTruth'])
            d['groundTruth'][0][key]=value
            with self.assertRaises(ValueError):audit(d,expected_labels=1,expected_truth_sha256=pinned)
    def test_nonzero_start_rejects_trimmed_video(self):
        d=recording([(1200,'A','ENTER',None)]);d['frames'][0]['timeMs']=10
        with self.assertRaises(ValueError):audit(d,expected_labels=1)
    def test_boolean_timestamp_rejected(self):
        d=recording([(1200,'A','ENTER',None)]);d['groundTruth'][0]['timeMs']=True
        with self.assertRaises(ValueError):audit(d,expected_labels=1)
    def test_measurement_timestamp_is_not_current_frame(self):
        d=recording([(1200,'A','ENTER',None)])
        d['frames'][1]['input']={'signals':[{'timeMs':1100}]}
        self.assertEqual('FAIL',audit(d,expected_labels=1)['event_contract_status'])
    def test_room_gate_conflict_fails(self):
        d=recording([(1200,'A','ENTER',None)])
        d['frames'][1]['outputs'][0]['gateId']='B#1'
        self.assertEqual('FAIL',audit(d,expected_labels=1)['event_contract_status'])
    def test_within1000_does_not_claim_within500(self):
        r=self.score([(1600,'A','ENTER',None)])
        self.assertEqual('PASS',r['event_contract_status'])
        self.assertEqual('FAIL',r['preferred_latency_target_status'])
    def test_explicit_empty_output_list_is_required(self):
        d=recording([]);d['frames'][0].pop('outputs')
        with self.assertRaises(ValueError):audit(d,expected_labels=1)
    def test_invalid_json_structure_is_rejected(self):
        with self.assertRaises(ValueError):audit([],expected_labels=1)


if __name__=='__main__':unittest.main()
