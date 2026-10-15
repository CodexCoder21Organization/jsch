from pathlib import Path
import sys,shutil,xml.etree.ElementTree as E,json,datetime
base=Path('/home/u/lanes/util20261014/out');kind=sys.argv[1];expected=int(sys.argv[2]);mode=sys.argv[3]
src=Path(sys.argv[4]) if len(sys.argv)>4 else Path('target/surefire-reports')
dest=base/('jschfix5-'+kind+'-results');dest.mkdir(exist_ok=True)
if src!=dest:
 for p in src.glob('TEST-*.xml'):shutil.copy2(p,dest/p.name)
rows=[];history=[]
for p in dest.glob('TEST-*.xml'):
 root=E.parse(p).getroot();rows+=root.findall('testcase');history += [e.tag for e in root.iter() if any(t in e.tag.lower() for t in ['retry','rerun','flaky','attempt-history'])]
fails=sum(r.find('failure') is not None for r in rows);errors=sum(r.find('error') is not None for r in rows);skips=sum(r.find('skipped') is not None for r in rows)
s={'kind':kind,'rows':len(rows),'failures':fails,'errors':errors,'skipped':skips,'retry_records':len(history)}
assert len(rows)==expected and errors==0 and skips==0 and not history,s
assert fails==(expected if mode=='red' else 0),s
if mode=='red':
 for r in rows:
  m=r.find('failure').get('message','');assert ('expected: <1> but was: <513>' in m or 'expected: <0> but was: <1>' in m),m
text=json.dumps(s,sort_keys=True);print(text);(base/('jschfix5-'+kind+'-summary.json')).write_text(text+'\n')
with (base/'jschfix5-findings.md').open('a') as f:f.write('\n'+datetime.datetime.now(datetime.timezone.utc).isoformat()+' — OBSERVED: '+text+'; native XML and full stacks preserved in '+str(dest)+'.\n')
