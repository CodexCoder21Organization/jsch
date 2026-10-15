#!/usr/bin/env bash
set -eu
base=/home/u/lanes/util20261014/out
python3 - <<'PY'
from pathlib import Path
import json,datetime,urllib.request,urllib.error
base=Path('/home/u/lanes/util20261014/out')
r=json.loads((base/'jschfix6-release-suite-summary.json').read_text())
assert r['rows']==242 and all(r[k]==0 for k in ['failures','errors','skipped','retry_records'])
a=json.loads((base/'jschfix6-artifact-verification.json').read_text());assert len(a['files'])==4
url='https://kotlin.directory/com/github/mwiede/jsch/0.2.18-reader6/jsch-0.2.18-reader6.pom'
try:
 with urllib.request.urlopen(url,timeout=30) as response:status=response.status
except urllib.error.HTTPError as e:status=e.code
r={'url':url,'http_status':status,'utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(base/'jschfix6-prepublish-probe.json').write_text(json.dumps(r,indent=2)+'\n')
assert status==404,r
print('Immediate POM preprobe:',status)
PY
ls -l "$base/jschfix6-artifacts/"*.pom
coursier launch community.kotlin.maven.artifact.publishing:community-kotlin-maven-artifact-publishing:0.0.6 -r https://kotlin.directory -- --artifact-dir "$base/jschfix6-artifacts" --api-url https://api.kotlin.directory/upload --force > "$base/jschfix6-publish.log" 2>&1
python3 "$base/jschfix6-verify-publication.py"
