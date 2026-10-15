from pathlib import Path
import json,subprocess
base=Path('/home/u/lanes/util20261014/out')
for kind,total in [('master-suite',525),('release-suite',233),('final-green',6),('ordering-corrected100',100)]:
 r=json.loads((base/('jschfix5-'+kind+'-summary.json')).read_text());assert r['rows']==total and all(r[k]==0 for k in ['failures','errors','skipped','retry_records']),r
publication=json.loads((base/'jschfix5-publication-verification.json').read_text());artifact=json.loads((base/'jschfix5-artifact-verification.json').read_text())
assert len(publication['files'])==4 and all(v['matches_local'] and v['http_status']==200 for v in publication['files'].values())
head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip();assert head=='48ad529432ac01a78c9c3a712be072a4e46b1c8c',head
s=(base/'jschfix5-pr-draft.md').read_text();s=s.replace('RELEASE_GATE_VALIDATION','Final reader5 full unit suite and package once: **233/233 first attempts**, zero failures/errors/skips/retry records. This retains all227 reader4 rows and adds the same6 reply-consumption rows.')
release=publication['release_source_head'];pub='`com.github.mwiede:jsch:0.2.18-reader5`, published with publisher0.0.6 after an immediate404 POM preprobe. The [POM](https://kotlin.directory/com/github/mwiede/jsch/0.2.18-reader5/jsch-0.2.18-reader5.pom), [library jar](https://kotlin.directory/com/github/mwiede/jsch/0.2.18-reader5/jsch-0.2.18-reader5.jar), [sources jar](https://kotlin.directory/com/github/mwiede/jsch/0.2.18-reader5/jsch-0.2.18-reader5-sources.jar) and [Javadoc jar](https://kotlin.directory/com/github/mwiede/jsch/0.2.18-reader5/jsch-0.2.18-reader5-javadoc.jar) each return200 and match local SHA-256. All285 checked-in Java sources match the sources jar; the base class format remains Java8, no test/peer/dependency classes are bundled, and dependency declarations match reader4. The [release source](https://github.com/CodexCoder21Organization/jsch/commit/'+release+') preserves reader4 lineage and receive behavior. [Retrieval/checksum evidence](https://github.com/CodexCoder21Organization/jsch/blob/wip/jschfix5-evidence/evidence/jschfix5-publication-verification.json).'
s=s.replace('PUBLICATION_VALIDATION',pub)
for a,b in [('all227','all 227'),('same6','same 6'),('publisher0.0.6','publisher 0.0.6'),('immediate404','immediate 404'),('return200','return 200'),('All285','All 285'),('Java8','Java 8')]:s=s.replace(a,b)
assert 'eb523698b133d41a85650bab5650a7da18e282ce3c5d6b0720199f98689835d5' in s
assert not any(x in s for x in ['READER5_VALIDATION','RELEASE_GATE_VALIDATION','FIXTURE_VALIDATION','PUBLICATION_VALIDATION'])
(base/'jschfix5-pr-body.md').write_text(s);print('Prepared verified PR body for '+head+' and release '+release)
