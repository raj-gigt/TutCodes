"""Recheck recorded Q5 allocations, chunk totals and bandwidth calculations."""
import argparse
import csv,json,math
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--batch', required=True)
args=parser.parse_args()
base=Path(__file__).resolve().parents[2] / 'results/q5' / args.batch / 'server'
for folder in sorted(base.iterdir()):
 meta=json.loads((folder/'run.json').read_text())
 if meta.get('server_exit_code') != 0: continue
 rows=list(csv.DictReader((folder/'Scheduler.csv').open()))
 counts=[sum(int(r[f'path{i}_chunks']) for r in rows) for i in (0,1)]
 assert sum(counts)==meta['total_chunks']
 assert counts==[meta['path0_chunks'],meta['path1_chunks']]
 max_error=0
 for r in rows:
  N=int(r['chunks']);n=int(r['path0_chunks']);mode=r['mode']
  assert 0<=n<=N and n+int(r['path1_chunks'])==N
  rt0=float(r['rtt0_ms']);rt1=float(r['rtt1_ms'])
  if mode in ['minrtt','minrtt-fallback']:
   assert n==(N if rt0<=rt1 else 0)
  elif mode=='bwratio':
   a=float(r['bw0_Bps']);b=float(r['bw1_Bps'])
   assert n==math.floor(N*a/(a+b)+.5)
  elif mode=='balanced':
   a=float(r['bw0_Bps']);b=float(r['bw1_Bps'])
   def gap(k): return abs(rt0/2000+k*1435/a-rt1/2000-(N-k)*1435/b)
   assert gap(n)<=min(gap(k) for k in range(N+1))+1e-7
   max_error=max(max_error,gap(n))
  else: raise AssertionError(mode)
 audit={'all_windows_valid':True,'windows':len(rows),'chunks':sum(counts),'fallbacks':sum(r['mode']=='minrtt-fallback' for r in rows),'path0_chunks':counts[0],'path1_chunks':counts[1]}
 for i in (0,1):
  probes=list(csv.DictReader((folder/f'Bandwidth-Path{i}.csv').open()))
  old=None
  for r in probes:
   sample=int(r['payload_bytes'])*1e9/int(r['elapsed_ns'])
   assert math.isclose(sample,float(r['sample_bytes_per_second']),abs_tol=1e-3)
   estimate=sample if old is None else .875*old+.125*sample
   assert math.isclose(estimate,float(r['ewma_bytes_per_second']),abs_tol=1e-3)
   old=estimate
  audit[f'path{i}_probe_samples']=len(probes)
 if meta['scheduler']=='balanced':audit['largest_predicted_completion_gap_seconds']=max_error
 (folder/'audit.json').write_text(json.dumps(audit,indent=2)+'\n')
 print(folder.name, 'PASS',len(rows),'windows;',audit['fallbacks'],'fallbacks')
