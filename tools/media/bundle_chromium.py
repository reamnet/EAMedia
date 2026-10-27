#!/usr/bin/env python3
"""Vendor pinned MCEF Chromium archives as GitHub-sized chunks; ordinary builds never download them."""
import argparse,copy,concurrent.futures,gzip,hashlib,json,pathlib,re,shutil,struct,tarfile,urllib.request
COMMIT='a78e832f9f13c2c688caea3d04d8b84fcd238d94'
BASE='https://mcef-download.cinemamod.com/java-cef-builds/'+COMMIT+'/'
ROOT=pathlib.Path(__file__).resolve().parents[2]
OUT=ROOT/'src/main/resources/assets/eamedia/chromium'
PLATFORMS=['linux_amd64','linux_arm64','windows_amd64','windows_arm64','macos_amd64','macos_arm64']
parser=argparse.ArgumentParser();parser.add_argument('--cache',type=pathlib.Path,default=ROOT/'.chromium-downloads');args=parser.parse_args()
args.cache.mkdir(parents=True,exist_ok=True);OUT.mkdir(parents=True,exist_ok=True)
def request(url):return urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'EAMedia-native-bundler'}),timeout=180)
def sha(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def architectures(head):
 if head[:4]==b'\x7fELF':return {62:'amd64',183:'arm64'}.get(int.from_bytes(head[18:20],'little' if head[5]==1 else 'big'),'other')
 if head[:2]==b'MZ':
  at=int.from_bytes(head[60:64],'little')
  if at+6<len(head):return {0x8664:'amd64',0xaa64:'arm64'}.get(int.from_bytes(head[at+4:at+6],'little'),'other')
 if head[:4] in [bytes.fromhex('cffaedfe'),bytes.fromhex('feedfacf')]:
  cpu=int.from_bytes(head[4:8],'little' if head[:4]==bytes.fromhex('cffaedfe') else 'big');return {0x1000007:'amd64',0x100000c:'arm64'}.get(cpu,'other')
 if head[:4] in [bytes.fromhex('cafebabe'),bytes.fromhex('bebafeca')]:
  endian='big' if head[:4]==bytes.fromhex('cafebabe') else 'little'
  count=int.from_bytes(head[4:8],endian)
  # Java .class files share CAFEBABE; their version is not a fat Mach-O count.
  if not 1<=count<=16 or len(head)<8+count*20:return None
  cpus={int.from_bytes(head[8+i*20:12+i*20],endian) for i in range(count)}
  if cpus=={0x1000007,0x100000c}:return 'universal'
  if cpus=={0x1000007}:return 'amd64'
  if cpus=={0x100000c}:return 'arm64'
  return 'other'
 return None
def bundle(platform):
 url=BASE+platform+'.tar.gz';path=args.cache/(platform+'.tar.gz')
 with request(url+'.sha256') as r:raw=r.read().decode()
 matches=re.findall(r'\b[a-fA-F0-9]{64}\b',raw);assert len(matches)==1,(platform,raw)
 expected=matches[0].lower()
 if not path.exists() or sha(path)!=expected:
  temp=path.with_suffix('.part')
  with request(url) as src,temp.open('wb') as dst:shutil.copyfileobj(src,dst,1024*1024)
  assert sha(temp)==expected,platform;temp.replace(path)
 files=[];links=[];machines={};unpacked=0
 with tarfile.open(path) as t:
  for member in t:
   name=member.name.removeprefix('./');parts=pathlib.PurePosixPath(name).parts
   assert parts and parts[0]==platform and '..' not in parts,(platform,name)
   relative='/'.join(parts[1:])
   if member.isdir():continue
   if member.issym() or member.islnk():links.append(dict(path=relative,target=member.linkname,hard=member.islnk()));continue
   assert member.isfile(),name
   h=hashlib.sha256();head=b''
   with t.extractfile(member) as f:
    while b:=f.read(1024*1024):
     if len(head)<4096:head+=(b[:4096-len(head)])
     h.update(b)
   machine=architectures(head)
   if machine:machines[relative]=machine
   files.append(dict(path=relative,bytes=member.size,sha256=h.hexdigest(),executable=bool(member.mode&0o111)))
   unpacked+=member.size
 target=platform.rsplit('_',1)[1]
 wrong={p:m for p,m in machines.items() if m not in [target,'universal']}
 if wrong:
  print('UNSUPPORTED upstream ABI',platform,wrong,flush=True)
  return platform,dict(unsupportedReason='Publisher archive contains a different native architecture',nativeArchitectures=wrong,sourceUrl=url,archiveSha256=expected)
 # Minecraft's Commons Compress 1.26 cannot reliably read the publisher's macOS
 # GNU sparse records. Expand holes while packaging; every extracted byte/mode stays identical.
 # Retain both the publisher checksum and the deterministic repacked archive checksum.
 source_bytes=path.stat().st_size;source_sha=expected
 if platform.startswith('macos_'):
  normalized=args.cache/(platform+'.normalized.tar.gz')
  with tarfile.open(path) as original,normalized.open('wb') as raw:
   with gzip.GzipFile(filename='',mode='wb',fileobj=raw,mtime=0,compresslevel=6) as gz:
    with tarfile.open(fileobj=gz,mode='w|',format=tarfile.PAX_FORMAT) as dest:
     for member in original:
      entry=copy.copy(member);entry.sparse=None
      entry.pax_headers={k:v for k,v in member.pax_headers.items() if not k.startswith('GNU.sparse.')}
      if member.isfile():entry.type=tarfile.REGTYPE
      stream=original.extractfile(member) if member.isfile() else None
      try:dest.addfile(entry,stream)
      finally:
       if stream:stream.close()
  path=normalized
 archive_sha=sha(path)
 chunks=[]
 with path.open('rb') as f:
  n=0
  while b:=f.read(45*1024*1024):
   name=platform+'-'+str(n).zfill(3)+'.bin';(OUT/name).write_bytes(b);chunks.append(dict(resource=name,bytes=len(b),sha256=hashlib.sha256(b).hexdigest()));n+=1
 print('BUNDLED',platform,path.stat().st_size,'bytes',len(files),'files',set(machines.values()),flush=True)
 return platform,dict(sourceUrl=url,sourceArchiveBytes=source_bytes,sourceArchiveSha256=source_sha,normalizedSparseFiles=platform.startswith('macos_'),archiveBytes=path.stat().st_size,archiveSha256=archive_sha,unpackedBytes=unpacked,parts=chunks,files=files,links=links,nativeArchitectures=machines)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:results=dict(pool.map(bundle,PLATFORMS))
manifest=dict(schema=1,jcefCommit=COMMIT,platforms={k:v for k,v in results.items() if 'parts' in v},unsupportedPlatforms={k:v for k,v in results.items() if 'parts' not in v})
(OUT/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('ARCHIVES:',sum(v['archiveBytes'] for v in manifest['platforms'].values()),'bytes',flush=True)
