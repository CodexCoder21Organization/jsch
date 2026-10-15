from pathlib import Path
import hashlib,json,urllib.request,xml.etree.ElementTree as E,datetime
base=Path('/home/u/lanes/util20261014/out');record=json.loads((base/'jschfix5-artifact-verification.json').read_text());result={"checked_at":datetime.datetime.now(datetime.timezone.utc).isoformat(),"coordinates":record["coordinates"],"release_source_head":record["release_source_head"],"files":{}}
for name,meta in record['files'].items():
 url='https://kotlin.directory/com/github/mwiede/jsch/0.2.18-reader5/'+name
 with urllib.request.urlopen(url,timeout=60) as response:
  data=response.read();status=response.status
 digest=hashlib.sha256(data).hexdigest();assert status==200 and digest==meta['sha256'],(name,status,digest,meta['sha256'])
 result['files'][name]={'url':url,'http_status':status,'bytes':len(data),'sha256':digest,'matches_local':True}
 if name.endswith('.pom'):
  r=E.fromstring(data);ns={'m':'http://maven.apache.org/POM/4.0.0'}
  assert [r.findtext('m:'+v,namespaces=ns) for v in ['groupId','artifactId','version']]==['com.github.mwiede','jsch','0.2.18-reader5']
(base/'jschfix5-publication-verification.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
