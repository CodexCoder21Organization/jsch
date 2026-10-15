from pathlib import Path
from zipfile import ZipFile
import hashlib,shutil,subprocess,json,urllib.request,xml.etree.ElementTree as E
version='0.2.18-reader6';base=Path('/home/u/lanes/util20261014/out');dest=base/'jschfix6-artifacts';dest.mkdir(exist_ok=True)
for suffix in ['.jar','-sources.jar','-javadoc.jar']:
 source=Path('target')/('jsch-'+version+suffix)
 assert source.is_file(),source
 shutil.copy2(source,dest/source.name)
shutil.copy2('.flattened-pom.xml',dest/('jsch-'+version+'.pom'))
ns={'m':'http://maven.apache.org/POM/4.0.0'}
r=E.parse(dest/('jsch-'+version+'.pom')).getroot()
assert [r.findtext('m:'+v,namespaces=ns) for v in ['groupId','artifactId','version']]==['com.github.mwiede','jsch',version]
old=urllib.request.urlopen('https://kotlin.directory/com/github/mwiede/jsch/0.2.18-reader5/jsch-0.2.18-reader5.pom',timeout=30).read()
(base/'jschfix6-reader5-public.pom').write_bytes(old)
def deps(x):
 return sorted(E.tostring(d,encoding='unicode').strip() for d in x.findall('m:dependencies/m:dependency',ns))
def canonical_deps(x):
 return sorted(tuple((child.tag.split('}')[-1],''.join(child.itertext()).strip()) for child in d) for d in x.findall('m:dependencies/m:dependency',ns))
assert canonical_deps(r)==canonical_deps(E.fromstring(old)), 'Release dependencies differ from reader5'
matched=0
with ZipFile(dest/('jsch-'+version+'-sources.jar')) as z:
 for d in Path('src/main').iterdir():
  if not d.is_dir() or not (d.name=='java' or d.name.startswith('java') and d.name[4:].isdigit()):continue
  prefix='' if d.name=='java' else 'META-INF/versions/'+d.name[4:]+'/'
  for p in d.rglob('*.java'):
   name=prefix+p.relative_to(d).as_posix()
   assert z.read(name).replace(b'\r\n',b'\n')==p.read_bytes().replace(b'\r\n',b'\n'),p
   matched+=1
 assert b'0.2.18_reader6' in z.read('com/jcraft/jsch/Version.java'), 'Generated version does not identify reader6'
with ZipFile(dest/('jsch-'+version+'.jar')) as z:
 names=z.namelist()
 assert not any(n.startswith(('org/apache/','org/bouncycastle/')) or any(test in n for test in ['ChannelLateOpen', 'FailedOpenReplyTest', 'PendingCloseBoundTest']) for n in names), 'Library contains peer/test/dependency classes'
 c=z.read('com/jcraft/jsch/Channel.class');major=int.from_bytes(c[6:8],'big');assert major==52,major
 assert b'0.2.18-reader6' in z.read('META-INF/MANIFEST.MF'), 'Manifest does not identify reader6'
result={'coordinates':'com.github.mwiede:jsch:'+version,'release_source_head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'pr_source_head':subprocess.check_output(['git','rev-parse','wip/jschfix2-master'],text=True).strip(),'checked_in_sources_matched':matched,'base_class_major':major,'dependencies_identical_to_reader5':True,'files':{p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(dest.iterdir()) if p.is_file()}}
(base/'jschfix6-artifact-verification.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
