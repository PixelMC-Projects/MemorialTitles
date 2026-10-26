"""Package the built mod and matching static resources without runtime data."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parent.parent
version = re.search(r"^version = '([^']+)'", (root / 'build.gradle').read_text(), re.M)[1]
out = root / 'releases' / f'{version}-20261026'
out.mkdir(parents=True, exist_ok=True)
jar = out / f'memorialtitles-forge-1.20.1-{version}.jar'
shutil.copy2(root / 'build/libs' / jar.name, jar)
client = root / 'web-console/dist/client'
web = out / f'memorialtitles-web-console-{version}.zip'
with zipfile.ZipFile(web, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(client.rglob('*')):
        if path.is_file():
            archive.write(path, path.relative_to(client).as_posix())

with zipfile.ZipFile(jar) as archive:
    assert archive.testzip() is None
    names = archive.namelist()
    assert 'web/index.html' in names
    assert 'org/mariadb/jdbc/Driver.class' in names
    assert 'META-INF/pixelmc/LICENSE' in names
    assert version in archive.read('META-INF/mods.toml').decode()
    assert not any(re.search(r'(?i)(web-accounts|test-secrets|\.env$|\.sqlite|\.db$|\.pem$)', n) for n in names)
    embedded = {n[4:] for n in names if n.startswith('web/') and not n.endswith('/')}
    expected = {p.relative_to(client).as_posix() for p in client.rglob('*') if p.is_file()}
    assert embedded == expected, 'Embedded UI contains stale or missing assets'
    for name in expected:
        assert archive.read('web/' + name) == (client / name).read_bytes(), name

# Optional local credentials are only read to reject leaks; their values never
# enter console output, metadata, archives, or source control.
secrets = []
for name in ['tools/test-secrets.json', 'tools/web-acceptance.local.json']:
    path = root / name
    if path.exists():
        value = json.loads(path.read_text(encoding='utf-8'))
        secrets.extend(str(v).encode() for k, v in value.items() if 'password' in k.lower() and len(str(v)) >= 8)
token_pattern = re.compile(rb'(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[A-Z0-9]{16})')
staged = subprocess.check_output(['git','diff','--cached','--binary'], cwd=root)
assert not any(secret in staged for secret in secrets) and not token_pattern.search(staged), 'Staged credential leak rejected'
for path in [jar, web]:
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            content = archive.read(name)
            assert not any(secret in content for secret in secrets), 'Credential leak rejected'
            assert not token_pattern.search(content), 'Credential token rejected'

sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
(out / 'SHA256SUMS').write_text(f'{sha(jar)}  {jar.name}\n', encoding='ascii')
verification = out / 'verification'
verification.mkdir(exist_ok=True)
shutil.copy2(root / 'build/reports/web-integration.json', verification / 'web-integration.json')
report = dict(version=version, source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
              result='PASS', embeddedMatchesStatic=True, noStaleEmbeddedAssets=True, noLocalCredentialLeaks=True,
              artifacts=[dict(file=p.name, bytes=p.stat().st_size, sha256=sha(p)) for p in [jar,web]],
              publicAssets=[jar.name,'SHA256SUMS'])
(verification / 'artifact-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))
