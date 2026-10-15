from pathlib import Path
import json,hashlib,urllib.request,datetime
base=Path('/home/u/lanes/util20261014/out')
artifact=json.loads((base/'jschfix6-artifact-verification.json').read_text())
result={'coordinates':artifact['coordinates'],'release_source_head':artifact['release_source_head'],'pr_source_head':artifact['pr_source_head'],'verified_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'files':{}}
for name,local in artifact['files'].items():
 url='https://kotlin.directory/com/github/mwiede/jsch/0.2.18-reader6/'+name
 with urllib.request.urlopen(url,timeout=60) as response:
  data=response.read();status=response.status
 value={'url':url,'http_status':status,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
 value['matches_local']=value['sha256']==local['sha256'] and len(data)==local['bytes']
 result['files'][name]=value
 assert status==200 and value['matches_local'],value
assert len(result['files'])==4
(base/'jschfix6-publication-verification.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
