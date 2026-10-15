from pathlib import Path
import os,subprocess,json,datetime
base=Path('/home/u/lanes/util20261014/out')
raw=subprocess.check_output(['ps','-eo','pid,args','-ww'],text=True)
(base/'jschfix6-ps-full.txt').write_text(raw)
parents={}
for line in subprocess.check_output(['ps','-eo','pid,ppid'],text=True).splitlines()[1:]:
 pid,ppid=map(int,line.split());parents[pid]=ppid
ancestors={os.getpid()};pid=os.getpid()
while pid in parents and parents[pid] not in ancestors:
 pid=parents[pid];ancestors.add(pid)
owned=[]
root='/home/u/lanes/util20261014/workspace/jschfix6'
for entry in Path('/proc').iterdir():
 if not entry.name.isdigit() or int(entry.name) in ancestors:continue
 try:
  cwd=str((entry/'cwd').resolve());args=(entry/'cmdline').read_bytes().split(b'\0')
 except OSError:continue
 if cwd==root or cwd.startswith(root+'/'):
  owned.append({'pid':int(entry.name),'program':os.fsdecode(args[0]) if args else '', 'cwd':cwd})
slots=[]
for p in base.glob('build-slot-*/owner'):
 if p.read_text().startswith('jschfix6 '):slots.append(str(p))
result={'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'owned_active_processes':owned,'owned_slots':slots,'ps_command':'ps -eo pid,args -ww'}
(base/'jschfix6-process-check.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
assert not owned and not slots,result
