from pathlib import Path
import json,subprocess
base=Path('/home/u/lanes/util20261014/out')
for kind,total,failures in [('red',9,9),('reversal',9,9),('green',36,0),('master-suite',534,0),('release-suite',242,0)]:
 r=json.loads((base/('jschfix6-'+kind+'-summary.json')).read_text())
 assert r['rows']==total and r['failures']==failures and all(r[k]==0 for k in ['errors','skipped','retry_records']),r
publication=json.loads((base/'jschfix6-publication-verification.json').read_text())
assert len(publication['files'])==4 and all(v['matches_local'] and v['http_status']==200 for v in publication['files'].values())
head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
assert head==publication['pr_source_head'],(head,publication['pr_source_head'])
checks=json.loads((base/'jschfix6-final-checks.json').read_text())
required=[c for c in checks['check_runs'] if c['name']=='kotlin.build (remote)' and c['head_sha']==head]
assert len(required)==1 and required[0]['status']=='completed' and required[0]['conclusion']=='success',required
s=(base/'jschfix6-pr-draft.md').read_text().replace('SOURCE_HEAD',head).replace('RELEASE_HEAD',publication['release_source_head'])
assert 'eb523698b133d41a85650bab5650a7da18e282ce3c5d6b0720199f98689835d5' in s
assert 'SOURCE_HEAD' not in s and 'RELEASE_HEAD' not in s
(base/'jschfix6-pr-body.md').write_text(s)
print('Prepared verified body for '+head)
