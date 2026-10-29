#!/usr/bin/env python3
"""Regenerate original baked TV and jukebox models, moved from Electrical Age."""
from pathlib import Path
from math import sin,cos,pi
from collections import defaultdict
root=Path(__file__).resolve().parents[2]/"src/main/resources/assets/eln/model"
class Mesh:
 def __init__(self):self.p=defaultdict(list)
 def poly(self,p,v):self.p[p].append(v)
 def box(self,p,x,y,z,a,b,c):
  v=[(x+i*a,y+j*b,z+k*c) for i,j,k in [(0,0,0),(1,0,0),(1,1,0),(0,1,0),(0,0,1),(1,0,1),(1,1,1),(0,1,1)]]
  for ids in [(0,3,2,1),(4,5,6,7),(0,1,5,4),(3,7,6,2),(0,4,7,3),(1,2,6,5)]:self.poly(p,[v[i] for i in ids])
 def cyl(self,p,axis,center,length,r0,r1=None,n=24):
  r1=r0 if r1 is None else r1
  def v(t,r,a):
   q=[0.,0.,0.];q[axis]=t;q[(axis+1)%3]=r*cos(a);q[(axis+2)%3]=r*sin(a)
   return tuple(q[i]+center[i] for i in range(3))
  for i in range(n):
   a=i*2*pi/n;b=(i+1)*2*pi/n
   self.poly(p,[v(0,0,0),v(0,r0,b),v(0,r0,a)])
   self.poly(p,[v(length,0,0),v(length,r1,a),v(length,r1,b)])
   self.poly(p,[v(0,r0,a),v(0,r0,b),v(length,r1,b),v(length,r1,a)])
 def save(self,name):
  d=root/name;d.mkdir(exist_ok=True)
  (d/'fixture.mtl').write_text('newmtl fixture\nKd 1 1 1\nKa 1 1 1\nd 1\n')
  lines=['# Electrical Age fixture; metres in local coordinates','mtllib fixture.mtl'];idx=1
  for p,faces in self.p.items():
   lines+=['o '+p,'usemtl fixture']
   for polygon in faces:
    for j in range(1,len(polygon)-1):
     face=[polygon[0],polygon[j],polygon[j+1]]
     lines += ['v %.6f %.6f %.6f'%v for v in face]
     lines += ['f '+' '.join(str(idx+i) for i in range(3))];idx+=3
  (d/(name+'.obj')).write_text('\n'.join(lines)+'\n')
for kind in ["tv","jukebox"]:
 m=Mesh()
 if kind=='tv':
  m.box('Base',-.52,-.49,-.28,1.04,.05,.56)
  m.box('Trim',-.075,-.44,-.055,.15,.28,.11)
  m.box('Case',-1.36,-.20,-.10,2.72,1.59,.16)
  m.box('Trim',-1.32,-.16,-.115,2.64,1.51,.04)
  m.box('Screen',-1.28,-.12,-.14,2.56,1.44,.015)
 elif kind=='jukebox':
  m.box('Base',-.42,-.49,-.32,.84,.07,.64)
  m.box('Case',-.39,-.42,-.28,.78,.84,.56)
  m.box('Trim',-.33,-.34,-.297,.66,.48,.016)
  for x in [-.19,.19]:
   m.cyl('Trim',2,(x,-.11,-.32),.015,.13,n=24)
   m.cyl('Terminal',2,(x,-.11,-.338),.012,.038,n=16)
  m.box('Screen',-.28,.21,-.30,.42,.12,.025)
  m.cyl('Terminal',2,(.24,.27,-.317),.035,.05,n=20)
  for i in range(7):m.box('Red',-.28+i*.085,.12,-.32,.04,.035,.02)

 m.box('Input',-.47,-.39,-.08,.05,.15,.16);m.box('Output',.42,-.39,-.08,.05,.15,.16)
 # The TV LED belongs on the lower bezel, below the browser picture, and
 # only 3 mm proud of its case. The jukebox keeps its original top indicator.
 if kind=='tv':m.box('Indicator',-.015,-.181,-.103,.03,.008,.003)
 else:m.box('Indicator',-.02,.36,-.25,.04,.035,.025)
 # Electrical front uses local +Z. Reflect only front/back so the blue input
 # stays on the existing left terminal; reverse winding to preserve outward normals.
 m.p={part:[[(x,y,-z) for x,y,z in reversed(face)] for face in faces] for part,faces in m.p.items()}
 m.save('industrial_'+kind)

# Wall TV: same picture area and electrical front; brackets replace the tabletop foot.
m=Mesh()
m.box('Base',-.46,-.28,-.50,.92,.62,.03)
m.box('Trim',-.34,-.19,-.47,.68,.44,.06)
m.box('Case',-1.36,-.44,-.40,2.72,1.59,.12)
m.box('Trim',-1.32,-.40,-.277,2.64,1.51,.018)
m.box('Screen',-1.28,-.36,-.257,2.56,1.44,.001)
m.box('Indicator',-.015,-.421,-.257,.03,.008,.003)
for x in [-.75,.75]:
 for y in [-.20,.65]:m.cyl('Trim',2,(x,y,-.405),.01,.02,n=12)
m.box('Input',-1.37,-.34,-.38,.04,.10,.07)
m.save('wall_browser_tv')
