#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 - "$REPO_ROOT" <<'CHECK'
import json, os, pathlib, subprocess, sys, tempfile, urllib.parse
root=pathlib.Path(sys.argv[1])
# Fixed non-production fixture values; no service is started by compose config.
env={**os.environ,
    'SKILLHUB_OBJECT_STORAGE_ROOT_USER':'fixture-storage-admin',
    'SKILLHUB_OBJECT_STORAGE_ROOT_PASSWORD':'fixture-storage-admin-secret',
    'SKILLHUB_STORAGE_PROVIDER':'s3',
    'SKILLHUB_STORAGE_S3_ACCESS_KEY':'fixture-business-user',
    'SKILLHUB_STORAGE_S3_SECRET_KEY':'fixture-business-secret',
    'SKILLHUB_STORAGE_S3_PUBLIC_ENDPOINT':'https://storage.example.test',
    'SKILLHUB_GITHUB_SYNC_ALLOWED_S3_HOSTS':'object-storage:9000',
    'SKILLHUB_DOWNLOAD_ANON_COOKIE_SECRET':'fixture-download-secret-at-least-32-characters'}
for key in ['RUSTFS_IMAGE','SILO_IMAGE']:
    env.pop(key,None)
with tempfile.TemporaryDirectory() as work:
    config=pathlib.Path(work)/'fixture.env'
    config.touch()
    for product in ['rustfs','silo','external']:
        command=[str(root/'scripts/storage-compose.sh'),'--storage',product,'--env-file',str(config),
                 '--project-name','storage-render-contract','--','--profile','github-sync','config','--format','json']
        data=json.loads(subprocess.check_output(command,cwd=root,env=env))
        services=data['services']
        server=services['server']['environment']
        assert server['SKILLHUB_STORAGE_S3_ACCESS_KEY']=='fixture-business-user'
        assert server['SKILLHUB_STORAGE_S3_SECRET_KEY']=='fixture-business-secret'
        assert 'fixture-storage-admin-secret' not in json.dumps(server)
        assert not any('ROOT_PASSWORD' in key or 'ROOT_USER' in key for key in server)
        if product=='external':
            assert 'object-storage' not in services and 'object-storage-permissions' not in services
            continue
        storage=services['object-storage']
        assert '@sha256:' in storage['image'] and len(storage['image'].rsplit(':',1)[1])==64
        assert server['SKILLHUB_STORAGE_PROVIDER']=='s3'
        assert server['SKILLHUB_STORAGE_S3_ENDPOINT']=='http://object-storage:9000'
        assert server['SKILLHUB_STORAGE_S3_FORCE_PATH_STYLE']=='true'
        assert server['SKILLHUB_STORAGE_S3_AUTO_CREATE_BUCKET']=='false'
        assert storage['volumes'][0]['source']==product+'_data'
        other='silo_data' if product=='rustfs' else 'rustfs_data'
        assert other not in data['volumes']
        assert services['github-sync']['environment']['SKILLHUB_GITHUB_SYNC_ALLOWED_S3_HOSTS']==urllib.parse.urlparse(server['SKILLHUB_STORAGE_S3_ENDPOINT']).netloc
        assert services['server']['depends_on']['object-storage']['condition']=='service_healthy'
        if product=='rustfs':
            initializer=services['object-storage-permissions']
            assert initializer['network_mode']=='none' and initializer['read_only']
            assert initializer['entrypoint']==['chown','10001:10001','/data']
            assert 'environment' not in initializer or not initializer['environment']
print('Storage Compose rendering, isolated volumes, business credentials and Worker endpoint tests passed')
CHECK
