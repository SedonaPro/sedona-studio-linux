from pathlib import Path
import subprocess,shutil,sys,os,socket,xml.etree.ElementTree as E,datetime,json
r=Path(__file__).resolve().parent;build=r/'build/tests';build.mkdir(parents=True,exist_ok=True)
core_cp=str(r/'dist/SedonaStudio/sedona-studio.jar')+os.pathsep+str(r/'lib/sedona.jar')+os.pathsep+str(r/'lib/studio-panel-api.jar')
# The full edition: the editor with its SCADA module.
cp=core_cp+os.pathsep+str(r/'dist/SedonaStudio/lib/sedona-scada.jar')
# The engineering edition: the editor jar in a folder of its own, where its manifest finds no lib/sedona-scada.jar.
(build/'engineering').mkdir(exist_ok=True);shutil.copy2(r/'dist/SedonaStudio/sedona-studio.jar',build/'engineering/sedona-studio.jar');shutil.copytree(r/'dist/SedonaStudio/modules',build/'engineering/modules',dirs_exist_ok=True)
core_cp=str(build/'engineering/sedona-studio.jar')+os.pathsep+str(r/'lib/sedona.jar')+os.pathsep+str(r/'lib/studio-panel-api.jar')
def run(cmd,name,timeout=90):
 if cmd[0]=='java':
  runtime=r/'dist/SedonaStudio/runtime/bin'/('java.exe' if os.name=='nt' else 'java')
  if runtime.exists():cmd[0]=str(runtime)
  elif '--add-modules' in cmd and os.name!='nt':cmd[1:1]=['--module-path',str(r/'lib/javafx-linux')]
  # No display (CI, SSH): render UI steps offscreen with the Monocle headless toolkit instead of failing.
  monocle=sorted((r/'lib/test').glob('openjfx-monocle-*.jar'))
  if 'javafx.controls,javafx.swing' in cmd and os.name!='nt' and monocle and (not os.environ.get('DISPLAY') or os.environ.get('STUDIO_HEADLESS')):cmd[1:1]=['--patch-module','javafx.graphics='+str(monocle[-1]),'-Dglass.platform=Monocle','-Dmonocle.platform=Headless','-Dprism.order=sw','-Dheadless.geometry=1920x1200-32','-Djava.awt.headless=true']
  cmd.insert(1,'-Dfile.encoding=UTF-8')
  # Tests run in one fixed interface language (STUDIO_TEST_LANG, English by default).
  cmd.insert(1,'-Dstudio.lang='+os.environ.get('STUDIO_TEST_LANG','en'))
 p=subprocess.run(cmd,timeout=timeout,capture_output=True,text=True,encoding='utf-8',errors='replace');(build/(name+'.log')).write_text(p.stdout+p.stderr,encoding='utf-8');print(p.stdout+p.stderr);history=build/'history';history.mkdir(exist_ok=True);(history/(datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+name+'.log')).write_text(p.stdout+p.stderr,encoding='utf-8');p.check_returncode();assert "Exception in thread" not in p.stdout+p.stderr and "AssertionError" not in p.stdout+p.stderr, name+": unexpected uncaught exception"
compile_cp=cp+os.pathsep+os.pathsep.join(str(p) for p in (r/('lib/javafx' if os.name=='nt' else 'lib/javafx-linux')).glob('*.jar'))
run(['javac','--release','17','-encoding','UTF-8','-cp',compile_cp,'-d',str(build),*map(str,(r/'tests').glob('*.java'))],'compile')
cp=str(build)+os.pathsep+cp
run(['java','-cp',cp,'CoreTests',str(build/'files')],'core')
run(['java','-cp',cp,'EditorTests'],'editor')
run(['java','-cp',cp,'LanguageTests'],'languages')
run(['java','-cp',cp,'ScreenTests',str(r/'tests/screens'),str(build/'screens'),str(r/'dist/SedonaStudio/libraries/sedona-hvac.jar')],'screens')
web_ui=Path(os.environ.get('SEDONAPRO_HOME',str(r.parent/'sedonapro')))/'editor/web/src/uiDocument.js'
if shutil.which('node') and web_ui.exists(): run(['node',str(r/'tests/screens/web_check.mjs'),str(web_ui),str(r/'tests/screens'),str(build/'screens')],'screens-web')
else: print('SKIP screens against the web editor: node or the web editor sources are not here')
run(['java','-cp',cp,'AlarmTests',str(build/'alarms')],'alarms')
run(['java','-cp',cp,'ArchiveTests',str(build/'archive')],'archive')
run(['java','-cp',str(build)+os.pathsep+core_cp,'EngineeringTests',str(r/'demo/hvac-demo.sedona.json')],'engineering')
run(['java','-cp',cp,'ProjectDiffTests'],'project-diff')
run(['java','-cp',cp,'LibraryRecoveryTests',str(build/('library-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')))],'library-recovery')
run(['java','-cp',cp,'WorkspaceTests'],'workspace')
run(['java','-cp',cp,'ViewStateTests'],'view-state')
run(['java','-cp',cp,'SamplingTests'],'sampling')
run(['java','-cp',cp,'PanelAdapterTests',str(build/('panel-adapter-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')))],'panel-adapter')
run([sys.executable,'-X','utf8',str(r/'tools/test_panel.py')],'panel-modules')
run(['java','-cp',cp,'PollingIntervalTests'],'polling-interval')
run(['java','-cp',cp,'TemplatePortabilityTests',str(build/('template-portability-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')))],'template-portability')
run(['java','--add-modules','javafx.graphics','-cp',cp,'WireRouterTests'],'routing')
run(['java','-cp',cp,'LocalCatalogTests',str(build/'catalog-cache')],'catalog')
run(['java','-cp',cp,'SessionTests',str(build/'session-profiles')],'sessions')
for session_id in ['a','b']:
 run(['java','-cp',cp,'SessionManifestProbe',str(build/('session-cache-'+session_id+'-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')))],'session-cache-'+session_id)
if '--ui-smoke' in sys.argv:
 smoke_root=build/('ui-smoke-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
 run(['java','--add-modules','javafx.controls,javafx.swing','-Dstudio.root='+str(smoke_root),'-Dstudio.data='+str(smoke_root),'-Dsedona.home='+str(smoke_root/'cache'),'-Dstudio.smokeOutput='+str(build/'ui-smoke.png'),'-cp',cp,'ru.bccontrol.sedonastudio.Launcher','--smoke'],'ui-smoke')
 # The same window without the SCADA module: the engineering edition.
 run(['java','--add-modules','javafx.controls,javafx.swing','-Dstudio.root='+str(smoke_root)+'-eng','-Dstudio.data='+str(smoke_root)+'-eng','-Dsedona.home='+str(smoke_root)+'-eng/cache','-Dstudio.smokeOutput='+str(build/'ui-smoke-engineering.png'),'-cp',str(build)+os.pathsep+core_cp,'ru.bccontrol.sedonastudio.Launcher','--smoke'],'ui-smoke-engineering')
if '--local-sox' in sys.argv:
 check=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
 try:check.bind(('127.0.0.1',13987))
 finally:check.close()
 sys.path.insert(0,str(r/'tests'));import fixture as local_fixture
 old,work=local_fixture.prepare(r/'build/fixture');tree=E.parse(work/'app.sax');tree.getroot().find(".//comp[@name='sox']/prop[@name='port']").set('val','13987');tree.write(work/'app.sax')
 run(['java','-Dsedona.home='+str(old),'-cp',str(old/'lib/sedona.jar')+os.pathsep+str(old/'lib/sedonac.jar'),'sedonac.Main',str(work/'app.sax')],'fixture')
 run(['java','-Dsedona.home='+str(old),'-cp',cp,'PrepareArchive',str(work)],'archive')
 # Offline app files and the external toolchain use the same SDK and loopback port, before the fixture VM starts.
 shutil.rmtree(build/'appfiles',ignore_errors=True);shutil.rmtree(build/'toolchain',ignore_errors=True)
 run(['java','-Dsedona.home='+str(old),'-cp',cp,'AppFileTests',str(build/'appfiles'),str(work/'app.sax')],'app-files')
 run(['java','-Dsedona.home='+str(old),'-cp',cp,'ToolchainTests',str(build/'toolchain'),str(work/'app.sax'),str(old.parent.parent if old.name=='home' else old)],'toolchain')
 if os.name!='nt':
  shutil.rmtree(build/'deploy',ignore_errors=True)
  run(['java','-Dsedona.home='+str(old),'-cp',cp,'DeployTests',str(build/'deploy'),str(old.parent.parent if old.name=='home' else old)],'deploy')
  shutil.rmtree(build/'kit-workshop',ignore_errors=True)
  run(['java','-cp',cp,'KitWorkshopTests',str(build/'kit-workshop'),str(old.parent.parent if old.name=='home' else old)],'kit-workshop')
  # A kit with C natives rebuilds the VM through the SDK's own tool: do that on a copy of the SDK tree.
  sdk_tree=old.parent.parent
  if old.name=='home' and (sdk_tree/'spro').is_file() and shutil.which('gcc'):
   native_root=build/'kit-native';shutil.rmtree(native_root,ignore_errors=True)
   shutil.copytree(sdk_tree,native_root/'tree',symlinks=True,ignore=lambda d,names:[n for n in names if n in ('.git','__pycache__') or (os.path.basename(d)=='build' and n in ('esp32','packages','sim'))])
   link=native_root/'tree/build/home/repo'
   if link.is_symlink():link.unlink()
   link.symlink_to(native_root/'tree')
   run(['java','-cp',cp,'KitNativeTests',str(native_root/'tree'),str(native_root/'work')],'kit-native',timeout=240)
  # The workshop window on the same throw-away SDK: create, build, palette, error, tests, simulator.
  kit_root=build/('kit-ui-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));kit_root.mkdir(parents=True)
  run(['java','--add-modules','javafx.controls,javafx.swing','-Dstudio.sdk='+str(build/'kit-workshop/sdk'),'-Dstudio.sdk.refSize='+os.environ.get('SEDONA_TEST_REFSIZE','8'),'-Dstudio.root='+str(kit_root),'-Dstudio.data='+str(kit_root),'-Dsedona.home='+str(kit_root/'cache'),'-Dstudio.smokeOutput='+str(build/'kit-workshop.png'),'-cp',cp,'ru.bccontrol.sedonastudio.Launcher','--kit-smoke='+str(kit_root/'kits')],'kit-workshop-ui')
 # The Simulator button end to end: a saved diagram is built, run in the VM, read back live and stopped.
 sim_root=build/('sim-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));sim_root.mkdir(parents=True)
 run(['java','-Dsedona.home='+str(old),'-cp',cp,'PrepareSimProject',str(work/'app.sax'),str(sim_root/'sim.sedona.json')],'sim-project')
 run(['java','--add-modules','javafx.controls,javafx.swing','-Dstudio.sdk='+str(old.parent.parent if old.name=='home' else old),'-Dstudio.root='+str(sim_root),'-Dstudio.data='+str(sim_root),'-Dsedona.home='+str(sim_root/'cache'),'-Dstudio.smokeOutput='+str(build/'simulator.png'),'-cp',cp,'ru.bccontrol.sedonastudio.Launcher','--sim-smoke='+str(sim_root/'sim.sedona.json')],'simulator-ui')
 # The demo plant (demo/hvac-demo.sedona.json is a saved copy of what tests/PrepareDemo.java writes): three screens live in the simulator.
 demo_root=build/('demo-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));demo_root.mkdir(parents=True)
 run(['java','-Dsedona.home='+str(old),'-cp',cp,'PrepareDemo',str(old/'manifests'),str(r/'dist/SedonaStudio/libraries/sedona-hvac.jar'),str(demo_root/'hvac-demo.sedona.json')],'demo-project')
 run(['java','--add-modules','javafx.controls,javafx.swing','-Dstudio.sdk='+str(old.parent.parent if old.name=='home' else old),'-Dstudio.root='+str(demo_root),'-Dstudio.data='+str(demo_root),'-Dsedona.home='+str(demo_root/'cache'),'-Dstudio.smokeOutput='+str(build/'demo'),'-cp',cp,'ru.bccontrol.sedonastudio.Launcher','--demo-smoke='+str(demo_root/'hvac-demo.sedona.json')],'demo-ui',timeout=150)
 # Kit widgets: read and checked without a device, then against a loopback VM with an Ahu, then in the editor with the simulator.
 sdk_tree=old.parent.parent if old.name=='home' else old
 widget_cache=build/'widget-cache';shutil.rmtree(widget_cache,ignore_errors=True);(widget_cache/'lib').mkdir(parents=True);(widget_cache/'lib/sedona.properties').touch()
 run(['java','-Dsedona.home='+str(widget_cache),'-cp',cp,'KitWidgetTests',str(sdk_tree)],'kit-widgets')
 if os.name!='nt':
  import widget_fixture
  widget_work=widget_fixture.prepare(sdk_tree,build/'widget-vm',13988)
  if widget_work is None:print('SKIP widget device test: the SDK image has no sproAhu or no ui.zip')
  else:
   with (widget_work/'svm.log').open('wb') as widget_log:
    widget_vm=widget_fixture.start(widget_work,widget_log)
    try:
     shutil.rmtree(build/'widget-journal',ignore_errors=True);shutil.rmtree(widget_cache,ignore_errors=True);(widget_cache/'lib').mkdir(parents=True);(widget_cache/'lib/sedona.properties').touch()
     run(['java','-Dsedona.home='+str(widget_cache),'-cp',cp,'WidgetDeviceTests','13988',str(build/'widget-journal')],'widget-device',timeout=180)
    finally:
     widget_vm.terminate();widget_vm.wait(10)
   widget_root=build/('widget-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));widget_root.mkdir(parents=True)
   run(['java','-Dsedona.home='+str(widget_root/'cache'),'-cp',cp,'PrepareWidgetProject',str(sdk_tree),str(widget_root/'widget.sedona.json')],'widget-project')
   run(['java','--add-modules','javafx.controls,javafx.swing','-Dstudio.sdk='+str(sdk_tree),'-Dstudio.root='+str(widget_root),'-Dstudio.data='+str(widget_root),'-Dsedona.home='+str(widget_root/'cache'),'-Dstudio.smokeOutput='+str(build/'widget'),'-cp',cp,'ru.bccontrol.sedonastudio.Launcher','--widget-smoke='+str(widget_root/'widget.sedona.json')],'widget-ui',timeout=240)
 cache=build/('empty-cache-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));(cache/'lib').mkdir(parents=True);(cache/'lib/sedona.properties').touch()
 with (build/'svm.log').open('wb') as log:
  process=local_fixture.launch(old,work,log)
  try:
   run(['java','-Dsedona.home='+str(cache),'-cp',cp,'LocalSoxTest',str(build/'operations')],'local-sox')
   session_root=build/('handoff-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));descriptor=build/'handoff-request.json'
   run(['java','-cp',cp,'PrepareSessionHandoff',str(session_root),str(descriptor)],'handoff-prepare')
   request=json.loads(descriptor.read_text(encoding='utf-8'))
   run(['java','--add-modules','javafx.controls,javafx.swing','-Dstudio.root='+str(session_root),'-Dstudio.session='+request['id'],'-Dstudio.smokeOutput='+str(build/'handoff-preview.png'),'-cp',cp,'ru.bccontrol.sedonastudio.Launcher','--connect-request='+request['token'],'--connect-smoke'],'handoff-ui')
  finally:process.terminate();process.wait(timeout=10)

if '--live-ui' in sys.argv:
 check=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
 try:check.bind(('127.0.0.1',13987))
 finally:check.close()
 sys.path.insert(0,str(r/'tests'));import fixture as live_fixture
 old,work=live_fixture.prepare(r/'build/live-fixture',live=True)
 tree=E.parse(work/'app.sax');tree.getroot().find(".//comp[@name='sox']/prop[@name='port']").set('val','13987');tree.write(work/'app.sax')
 run(['java','-Dsedona.home='+str(old),'-cp',str(old/'lib/sedona.jar')+os.pathsep+str(old/'lib/sedonac.jar'),'sedonac.Main',str(work/'app.sax')],'live-fixture')
 run(['java','-Dsedona.home='+str(old),'-cp',cp,'PrepareArchive',str(work)],'live-archive')
 with (build/'live-svm.log').open('wb') as log:
  process=live_fixture.launch(old,work,log)
  try:
   session_root=build/('live-session-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));descriptor=build/'live-request.json'
   run(['java','-cp',cp,'PrepareSessionHandoff',str(session_root),str(descriptor)],'live-prepare')
   request=json.loads(descriptor.read_text(encoding='utf-8'))
   run(['java','--add-modules','javafx.controls,javafx.swing','-Dstudio.root='+str(session_root),'-Dstudio.session='+request['id'],'-Dstudio.smokeOutput='+str(build/'live-preview.png'),'-cp',cp,'ru.bccontrol.sedonastudio.Launcher','--connect-request='+request['token'],'--connect-smoke','--live-smoke'],'live-ui')
  finally:process.terminate();process.wait(timeout=10)
