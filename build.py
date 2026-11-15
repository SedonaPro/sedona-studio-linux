from pathlib import Path
import os,subprocess,shutil,sys,zipfile,json,hashlib
r=Path(__file__).resolve().parent
# One project build: internal panel API/widgets/demo before compiling the editor.
subprocess.run([sys.executable,'-X','utf8',str(r/'tools/build_panel.py')]+(['--only','panel'] if '--panel-only' in sys.argv else []),check=True)
if '--panel-only' in sys.argv:sys.exit(0)

jre=Path(os.environ.get('STUDIO_JRE',str(r/'dist/SedonaStudio/runtime')));jfx=Path(os.environ.get('JAVAFX_JAR',str(r/('lib/javafx/*' if os.name=='nt' else 'lib/javafx-linux/*'))))
build=r/'build';build.mkdir(exist_ok=True);classes=build/'classes'
resolved=classes.resolve()
if r.resolve() not in resolved.parents or resolved.name!='classes' or classes.is_symlink():raise RuntimeError('Unsafe classes directory')
if classes.exists():shutil.rmtree(classes)
classes.mkdir(exist_ok=True)
jfx_files=sorted(jfx.parent.glob('*.jar')) if jfx.name=='*' else [jfx]
if not jfx_files:raise RuntimeError('JavaFX libraries missing; run link_runtime.py or set JAVAFX_JAR')
# Module boundaries, one direction only: model <- storage <- protocol <- ui. Each layer must compile
# with nothing above it (and the whole core without JavaFX); a leak fails the build here.
layers=[]
for layer in ('model','storage','protocol'):
 layers.append(layer)
 units=[str(p) for d in ['ru/bccontrol/sedonastudio/'+n for n in layers]+(['sedona'] if layer=='protocol' else []) for p in (r/'src/main/java'/d).rglob('*.java')]
 check=subprocess.run(['javac','-J-Dfile.encoding=UTF-8','--release','17','-encoding','UTF-8','-proc:none','-implicit:none','-cp',str(r/'lib/sedona.jar'),'-d',str(build/'layer-check'/layer)]+units,capture_output=True,text=True,encoding='utf-8',errors='replace')
 if check.returncode:print(check.stdout+check.stderr);raise RuntimeError('Layer "'+layer+'" depends on a layer above it')
# The toolchain (external compiler and VM) sits beside protocol: it may use model and storage only.
units=[str(p) for d in ('model','storage','toolchain') for p in (r/'src/main/java/ru/bccontrol/sedonastudio'/d).rglob('*.java')]
check=subprocess.run(['javac','-J-Dfile.encoding=UTF-8','--release','17','-encoding','UTF-8','-proc:none','-implicit:none','-cp',str(r/'lib/sedona.jar'),'-d',str(build/'layer-check'/'toolchain')]+units,capture_output=True,text=True,encoding='utf-8',errors='replace')
if check.returncode:print(check.stdout+check.stderr);raise RuntimeError('Layer "toolchain" may depend only on model and storage')
print('PASS layer boundaries: model <- storage <- protocol | toolchain, no JavaFX or UI classes in the core')
# UI rule: only the composition root and the built-in self-tests may name StudioApp; features take
# building blocks and EditorShell in their constructors.
import re
leaks=[p.name for p in (r/'src/main/java/ru/bccontrol/sedonastudio/ui').glob('*.java') if p.stem!='StudioApp' and not p.stem.startswith('SelfTest') and re.search(r'\bStudioApp\b',re.sub(r'/\*.*?\*/|//[^\n]*','',p.read_text(encoding='utf-8'),flags=re.S))]
if leaks:raise RuntimeError('UI classes must not depend on StudioApp (use EditorShell): '+', '.join(leaks))
print('PASS UI wiring: no feature class depends on StudioApp')
cp=os.pathsep.join([str(r/'lib/sedona.jar'),str(r/'lib/studio-panel-api.jar')]+[str(p) for p in jfx_files])
# The SCADA module (operator screens, alarms, history of values) is built from this tree but ships as
# its own jar, lib/sedona-scada.jar: an engineering edition is the same editor without that file.
# The rest must compile without these sources; a dependency of the core on the module fails the build here.
SCADA={'model':['Screens','ScreenBindings','ScreenEdits','Alarms','Archive'],'storage':['ScreenStore','ScreenLibraries','AlarmStore','ArchiveStore','ScadaParts'],'ui':['ScreenData','ScreenView','ScreenEditor','ScreensFeature','AlarmsFeature','ArchiveFeature','ScadaModule','SelfTestScada']}
scada_sources={r/'src/main/java/ru/bccontrol/sedonastudio'/layer/(name+'.java') for layer,names in SCADA.items() for name in names}
missing=[str(p) for p in scada_sources if not p.exists()]
if missing:raise RuntimeError('SCADA module sources not found: '+', '.join(missing))
core_args=['--release','17','-encoding','UTF-8','-proc:none','-implicit:none','-cp',cp,'-d',str(build/'layer-check'/'core')]+[str(p) for p in (r/'src/main/java').rglob('*.java') if p not in scada_sources]
core_file=build/'javac-core.args';core_file.write_text('\n'.join('"'+v.replace('\\','/')+'"' for v in core_args),encoding='utf-8')
check=subprocess.run(['javac','-J-Dfile.encoding=UTF-8','@'+str(core_file)],capture_output=True,text=True,encoding='utf-8',errors='replace')
if check.returncode:print(check.stdout+check.stderr);raise RuntimeError('The editor core depends on the SCADA module')
print('PASS edition boundary: the editor compiles without the SCADA module ('+str(len(scada_sources))+' sources)')
args=['--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes)]+[str(p) for p in (r/'src/main/java').rglob('*.java')]
argfile=build/'javac.args';argfile.write_text('\n'.join('"'+v.replace('\\','/')+'"' for v in args),encoding='utf-8')
p=subprocess.run(['javac','-J-Dfile.encoding=UTF-8','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace');(build/'compile.log').write_text(p.stdout+p.stderr,encoding='utf-8');print(p.stdout+p.stderr);p.check_returncode()
shutil.copytree(r/'src/main/resources',classes,dirs_exist_ok=True)
# Interface texts: English goes into the jar (always available), every language file goes to dist/lang.
def _texts(path):
 keys={}
 for line in path.read_text(encoding='utf-8').splitlines():
  if line.strip() and not line.lstrip().startswith('#') and '=' in line:k,v=line.split('=',1);keys[k.strip()]=v
 return keys
english=_texts(r/'lang/en.properties')
used=set()
for p in (r/'src/main/java').rglob('*.java'):used|=set(re.findall(r'Tr\.of\(\s*"([^"]+)"',p.read_text(encoding='utf-8')))
unknown=sorted(used-set(english))
if unknown:raise RuntimeError('Tr.of keys missing from lang/en.properties: '+', '.join(unknown))
holes=lambda s:sorted(set(re.findall(r'\{\d+\}',s)))
for other in sorted((r/'lang').glob('*.properties')):
 if other.stem=='en':continue
 texts=_texts(other);absent=sorted(set(english)-set(texts));wrong=sorted(k for k in texts if k in english and holes(texts[k])!=holes(english[k]))
 if wrong:raise RuntimeError(other.name+': placeholders differ from English in '+', '.join(wrong))
 print('lang',other.stem+':',len(texts),'texts'+(', falls back to English for '+str(len(absent))+': '+', '.join(absent[:8]) if absent else ''))
(classes/'lang').mkdir(exist_ok=True);shutil.copy2(r/'lang/en.properties',classes/'lang/en.properties')
# Panel modules write their English text in the code (PanelText.of(key, english)); en.properties must say the same.
module_texts={}
for p in (r/'modules').rglob('*.java'):module_texts.update(re.findall(r'PanelText\.of\(\s*"([^"]+)",\s*"((?:[^"\\\\]|\\\\.)*)"',p.read_text(encoding='utf-8')))
differ=sorted(k for k,v in module_texts.items() if english.get(k)!=v)
if differ:raise RuntimeError('Panel module texts differ from lang/en.properties: '+', '.join(differ))
used|=set(module_texts)
unused=sorted(k for k in set(english)-used if not k.startswith(('language.','selftest.')))
if unused:print('note: texts no longer used in the code:',', '.join(unused))
print('PASS interface texts:',len(used),'keys used, all present in English')
out=r/'dist/SedonaStudio';(out/'lib').mkdir(parents=True,exist_ok=True)
# The editor may be running from out: a jar is written beside its old copy and renamed over it, so a
# running editor keeps reading the old file (rewriting a jar in place breaks its class loading).
def _new(path):return path.with_name(path.name+'.new')
def _swap(path):os.replace(_new(path),path)
def _copy(src,dst):shutil.copy2(src,_new(dst));_swap(dst)
_copy(r/'lib/sedona.jar',out/'lib/sedona.jar');_copy(r/'lib/studio-panel-api.jar',out/'lib/studio-panel-api.jar')
# Screen libraries are plugged in as jars: data only (screen-library.json), drawn by screen-libraries/<name>/build_library.py.
import zipfile as _zip
(out/'libraries').mkdir(exist_ok=True)
for _lib in sorted((r/'screen-libraries').glob('*/build_library.py')):
 subprocess.run([sys.executable,'-X','utf8',str(_lib)],check=True)
 _libjar=out/'libraries'/('sedona-'+_lib.parent.name+'.jar')
 with _zip.ZipFile(_new(_libjar),'w',_zip.ZIP_DEFLATED) as _z:
  _z.writestr(_zip.ZipInfo('META-INF/MANIFEST.MF',(2026,1,1,0,0,0)),'Manifest-Version: 1.0\nSedona-Screen-Library: '+_lib.parent.name+'\n\n')
  _z.writestr(_zip.ZipInfo('screen-library.json',(2026,1,1,0,0,0)),(_lib.parent/'screen-library.json').read_bytes(),_zip.ZIP_DEFLATED)
 _swap(_libjar)
# Portable Python for the SDK's build tool (tools/fetch_linux_deps.py); copied once per version.
python_src=r/'lib/python-linux/python'
if python_src.is_dir() and os.name!='nt':
 marker=(python_src/'.source').read_text() if (python_src/'.source').exists() else ''
 python_out=out/'python'
 if not (python_out/'.source').exists() or (python_out/'.source').read_text()!=marker:
  shutil.rmtree(python_out,ignore_errors=True);shutil.copytree(python_src,python_out,symlinks=True)
 print('portable Python included:',python_out)
lang_out=out/'lang';lang_out.mkdir(exist_ok=True)
for old in lang_out.glob('*.properties'):old.unlink()
for f in (r/'lang').glob('*.properties'):shutil.copy2(f,lang_out/f.name)
module_out=out/'modules'
module_out.mkdir(exist_ok=True)
for old in module_out.glob('*.jar'):old.unlink()
for module in (r/'modules').glob('*.jar'):shutil.copy2(module,module_out/module.name)
def _scada(path):
 parts=path.relative_to(classes).parts
 if len(parts)!=5 or parts[:3]!=('ru','bccontrol','sedonastudio') or parts[3] not in SCADA:return False
 return parts[4].split('.')[0].split('$')[0] in SCADA[parts[3]]
with zipfile.ZipFile(_new(out/'sedona-studio.jar'),'w',zipfile.ZIP_DEFLATED) as z:
 # A Class-Path entry that is not there is skipped by the JVM: without lib/sedona-scada.jar the editor runs as the engineering edition.
 z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nMain-Class: ru.bccontrol.sedonastudio.Launcher\nClass-Path: lib/sedona.jar lib/studio-panel-api.jar lib/sedona-scada.jar\nImplementation-Version: 0.4.2\n\n')
 for f in sorted(classes.rglob('*')):
  if f.is_file() and not _scada(f):z.write(f,f.relative_to(classes))
_swap(out/'sedona-studio.jar')
scada_jar=out/'lib/sedona-scada.jar'
if '--engineering' in sys.argv:
 # Engineering edition: no SCADA module and none of its screen libraries.
 scada_jar.unlink(missing_ok=True);shutil.rmtree(out/'libraries',ignore_errors=True)
 print('engineering edition: no lib/sedona-scada.jar, no libraries/')
else:
 with zipfile.ZipFile(_new(scada_jar),'w',zipfile.ZIP_DEFLATED) as z:
  z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nImplementation-Title: Sedona Studio SCADA module\nImplementation-Version: 0.4.2\n\n')
  z.writestr('META-INF/services/ru.bccontrol.sedonastudio.ui.StudioModule','ru.bccontrol.sedonastudio.ui.ScadaModule\n')
  z.writestr('META-INF/services/ru.bccontrol.sedonastudio.model.ProjectExtension',''.join('ru.bccontrol.sedonastudio.storage.ScadaParts$'+n+'\n' for n in ('ScreensPart','AlarmsPart','ArchivePart')))
  z.write(r/'screens/base-library.json','screens/base-library.json')
  count=0
  for f in sorted(classes.rglob('*')):
   if f.is_file() and _scada(f):z.write(f,f.relative_to(classes));count+=1
 _swap(scada_jar)
 print('SCADA module:',scada_jar.name,count,'classes')
if '--runtime' in sys.argv and not (out/'runtime').exists():shutil.copytree(jre,out/'runtime')
(out/'Start.cmd').write_text('@echo off\nsetlocal\ncd /d "%~dp0"\nif exist "runtime\\bin\\javaw.exe" (set "JAVA=runtime\\bin\\javaw.exe") else (set "JAVA=javaw.exe")\nstart "" "%JAVA%" "-Dsedona.home=%LOCALAPPDATA%\\BCControl\\SedonaStudio\\cache" "-Dstudio.data=%LOCALAPPDATA%\\BCControl\\SedonaStudio" --add-modules javafx.controls,javafx.swing -jar sedona-studio.jar\n',encoding='ascii')
shutil.copy2(r/'start.sh',out/'start.sh')
print('BUILT',out)

for file in [out/'sedona-studio.jar',out/'lib/sedona.jar']:
 with zipfile.ZipFile(file) as z:
  forbidden=[n for n in z.namelist() if n.startswith(('javax/baja/','com/tridium/','ru/bccontrol/ontrol/','ru/bccontrol/wiresheet/'))]
  if forbidden:raise RuntimeError('Unexpected MCS/Niagara dependency: '+str(forbidden[:3]))
print('PASS standalone dependency boundary: no MCS/Baja/WireSheet classes')
