from pathlib import Path
import os,subprocess
base=Path('/home/u/lanes/util20261014/out');env=os.environ.copy();env['GIT_INDEX_FILE']=str(base/'jschfix5-evidence.index')
def git(*args):return subprocess.check_output(['git',*args],env=env,text=True).strip()
git('read-tree','--empty')
files=[]
for pattern in ['jschfix5-*.java','jschfix5-findings.md','jschfix5-review.md','jschfix5-*.json','jschfix5-*.txt','jschfix5-*.log','jschfix5-*.patch','jschfix5-*.sh','jschfix5-*.py']:
 files+=list(base.glob(pattern))
for d in base.glob('jschfix5-*-results'):files+=list(d.glob('TEST-*.xml'))
files+=list((base/'jschfix5-probe-src').rglob('*.java'))
for p in sorted(set(files)):
 if not p.is_file():continue
 blob=git('hash-object','-w','--',str(p));git('update-index','--add','--cacheinfo','100644',blob,'evidence/'+str(p.relative_to(base)))
tree=git('write-tree');ref='refs/heads/wip/jschfix5-evidence';parent=subprocess.run(['git','rev-parse','--verify',ref],capture_output=True,text=True)
args=['commit-tree',tree]
if parent.returncode==0:args+=['-p',parent.stdout.strip()]
args+=['-m','Record close notice regression and publication evidence','-m','Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>']
commit=git(*args);git('update-ref',ref,commit);print(commit)
