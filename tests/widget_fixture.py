"""A loopback SedonaPro VM with an air handling unit configurator (sproAhu::Ahu) for the kit widget
tests: the SDK's own Linux image (kits.scode, manifests with ui.zip, svm), a small app with one Ahu
that is not built yet, SOX on 127.0.0.1 only. Never touches a running device."""
from pathlib import Path
import base64, hashlib, os, shutil, subprocess, tomllib, xml.etree.ElementTree as E

USER, PASSWORD = 'admin', 'localJarTest'


def image(tree):
    """The SDK tree's default Linux build folder (project.toml decides which one)."""
    linux = tomllib.loads((tree / 'project.toml').read_text())['targets']['linux']
    out = tree / 'build' / ('linux-amd64-%s-%s' % (linux.get('default_profile', 'full'), linux.get('default_board', 'sim')))
    return out if (out / 'run/manifests/ui.zip').is_file() else None


def prepare(tree, work, port):
    out = image(tree)
    if out is None:
        return None
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    for name in ('svm', 'kits.scode'):
        shutil.copy2(out / 'run' / name, work / name)
    shutil.copytree(out / 'run/manifests', work / 'manifests')
    kits = [d.get('on').split()[0] for d in E.parse(out / 'kits.xml').getroot().iter('depend')]
    if 'sproAhu' not in kits:
        return None
    app = E.Element('sedonaApp')
    schema = E.SubElement(app, 'schema')
    for k in kits:
        E.SubElement(schema, 'kit', name=k)
    root = E.SubElement(app, 'app')
    E.SubElement(root, 'prop', name='appName', val='WidgetTest')
    svc = E.SubElement(root, 'comp', name='service', type='sys::Folder')
    E.SubElement(svc, 'comp', name='plat', type='sproPlatformLinux::UnixPlatformService')
    users = E.SubElement(svc, 'comp', name='users', type='sys::UserService')
    user = E.SubElement(users, 'comp', name=USER, type='sys::User')
    cred = base64.b64encode(hashlib.sha1((USER + ':' + PASSWORD).encode()).digest()).decode()
    for n, v in (('cred', cred), ('perm', '2147483647'), ('prov', '255')):
        E.SubElement(user, 'prop', name=n, val=v)
    sox = E.SubElement(svc, 'comp', name='sox', type='sox::SoxService')
    E.SubElement(sox, 'prop', name='port', val=str(port))
    unit = E.SubElement(root, 'comp', name='unit', type='sproAhu::Ahu')
    for n, v in (('buildOnStart', 'false'), ('autoChannels', 'false')):
        E.SubElement(unit, 'prop', name=n, val=v)
    E.SubElement(app, 'links')
    E.ElementTree(app).write(work / 'app.sax', encoding='utf-8')
    home = tree / 'build/home'
    cp = os.pathsep.join(str(home / 'lib' / j) for j in ('sedona.jar', 'sedonac.jar'))
    r = subprocess.run(['java', '-Dsedona.home=' + str(home), '-cp', cp, 'sedonac.Main', str(work / 'app.sax')],
                       capture_output=True, text=True)
    (work / 'app.sax.log').write_text(r.stdout + r.stderr)
    if r.returncode:
        raise RuntimeError('widget fixture app does not compile, see ' + str(work / 'app.sax.log'))
    return work


def start(work, log):
    env = dict(os.environ, SVM_INET_BIND='127.0.0.1')
    return subprocess.Popen(['./svm', 'kits.scode', 'app.sab'], cwd=work, env=env, stdout=log, stderr=subprocess.STDOUT)
