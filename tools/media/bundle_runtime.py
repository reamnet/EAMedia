#!/usr/bin/env python3
"""Refresh pinned in-jar media tools from publisher releases; verifies every upstream SHA-256."""
import concurrent.futures, gzip, hashlib, json, pathlib, shutil, urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[2]
OUT=ROOT/'src/main/resources/assets/eln/media-runtime'
VERSIONS={'yt-dlp':('yt-dlp/yt-dlp','2026.10.26'),'ffmpeg-static':('eugeneware/ffmpeg-static','b6.1.1'),'quickjs':('quickjs-ng/quickjs','v0.17.0')}
OUT.mkdir(parents=True,exist_ok=True)
def release(repo,tag):
 req=urllib.request.Request('https://api.github.com/repos/'+repo+'/releases/tags/'+tag,headers={'User-Agent':'ELN-ForkPatch-media-bundler'})
 with urllib.request.urlopen(req,timeout=60) as r:return json.load(r)
releases={k:release(*v) for k,v in VERSIONS.items()}
platforms={'windows-x64':('yt-dlp.exe','win32-x64','windows-x86_64.exe'),
 'linux-x64':('yt-dlp_linux','linux-x64','linux-x86_64'),
 'linux-arm64':('yt-dlp_linux_aarch64','linux-arm64','linux-aarch64'),
 'macos-x64':('yt-dlp_macos','darwin-x64','darwin-x86_64'),
 'macos-arm64':('yt-dlp_macos','darwin-arm64','darwin-arm64')}
def fetch(url,path):
 req=urllib.request.Request(url,headers={'User-Agent':'ELN-ForkPatch-media-bundler'})
 with urllib.request.urlopen(req,timeout=120) as src,path.open('wb') as dst:shutil.copyfileobj(src,dst)
def digest(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def get_asset(tool,name):return next(a for a in releases[tool]['assets'] if a['name']==name)
previous=json.loads((OUT/'manifest.json').read_text()) if (OUT/'manifest.json').exists() else {'platforms':{}}
known={a['sourceUrl']:a['sha256'] for tools in previous['platforms'].values() for a in tools.values()}
jobs={}
for platform,(yt,ff,js) in platforms.items():
 for tool,asset in [('yt-dlp',yt),('ffmpeg-static','ffmpeg-'+ff+'.gz'),('quickjs','qjs-'+js)]:jobs[(tool,asset)]=get_asset(tool,asset)
def download(entry):
 (tool,name),asset=entry
 filename=tool+'-'+name
 path=OUT/filename
 expected=asset['digest'].removeprefix('sha256:');assert len(expected)==64
 if asset['browser_download_url'] in known:assert known[asset['browser_download_url']]==expected,'Previously pinned upstream asset changed'
 if not path.exists() or digest(path)!=expected:
  temp=path.with_suffix(path.suffix+'.part');fetch(asset['browser_download_url'],temp)
  assert digest(temp)==expected,name;temp.replace(path)
 if name.endswith('.gz'):
  with gzip.open(path,'rb') as src:
   h=hashlib.sha256();size=0
   while b:=src.read(1024*1024):h.update(b);size+=len(b)
  binary_digest=h.hexdigest();binary_size=size;fmt='gzip';member=None
 else:binary_digest=expected;binary_size=path.stat().st_size;fmt='raw';member=None
 print(tool,name,path.stat().st_size,flush=True)
 return (tool,name),dict(resource=filename,sha256=expected,format=fmt,member=member,binarySha256=binary_digest,binaryBytes=binary_size,sourceUrl=asset['browser_download_url'],version=releases[tool]['tag_name'])
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:assets=dict(pool.map(download,jobs.items()))
manifest={'schema':1,'platforms':{}}
for platform,(yt,ff,js) in platforms.items():
 manifest['platforms'][platform]={name:assets[(tool,asset)] for name,tool,asset in [('ytdlp','yt-dlp',yt),('ffmpeg','ffmpeg-static','ffmpeg-'+ff+'.gz'),('quickjs','quickjs','qjs-'+js)]}
(OUT/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('BUNDLED',sum(p.stat().st_size for p in OUT.iterdir() if p.is_file()),flush=True)
