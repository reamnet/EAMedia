#!/usr/bin/env python3
"""Original floor cabinet and flush ceiling grille for the jukebox audio channel."""
from pathlib import Path
from collections import defaultdict
from math import sin,cos,pi
ROOT=Path(__file__).resolve().parents[2]/'src/main/resources/assets/eln/model'
class Mesh:
 def __init__(self):self.parts=defaultdict(list)
 def box(self,n,x,y,z,w,h,d):
  v=[(x+i*w,y+j*h,z+k*d) for i,j,k in [(0,0,0),(1,0,0),(1,1,0),(0,1,0),(0,0,1),(1,0,1),(1,1,1),(0,1,1)]]
  for ids in [(0,3,2,1),(4,5,6,7),(0,1,5,4),(3,7,6,2),(0,4,7,3),(1,2,6,5)]:self.parts[n].append([v[i] for i in ids])
 def cone(self,n,x,y,z,r,ceiling=False):
  for i in range(32):
   a=i*pi/16;b=(i+1)*pi/16
   face=[(x,y,z),(x+r*cos(b),y if ceiling else y+r*sin(b),z+r*sin(b) if ceiling else z),(x+r*cos(a),y if ceiling else y+r*sin(a),z+r*sin(a) if ceiling else z)]
   self.parts[n].append(face[::-1] if ceiling else face)
 def write(self,name):
  d=ROOT/name;d.mkdir(exist_ok=True);lines=['# Original EA Media speaker','mtllib fixture.mtl'];idx=1
  for n,faces in self.parts.items():
   lines+=['o '+n,'usemtl fixture']
   for vs in faces:
    lines+=['v %.6f %.6f %.6f'%v for v in vs]+[f'f {idx} {idx+1} {idx+2}']
    if len(vs)==4:lines+=[f'f {idx} {idx+2} {idx+3}']
    idx+=len(vs)
  (d/(name+'.obj')).write_text('\n'.join(lines)+'\n');(d/'fixture.mtl').write_text('newmtl fixture\nKd 1 1 1\nKa 1 1 1\n')
m=Mesh();m.box('Case',-.26,-.49,-.25,.52,.98,.50);m.box('Trim',-.28,-.5,-.27,.56,.045,.54)
for y,r in [(-.23,.16),(.14,.105),(.36,.04)]:m.cone('Cone',0,y,-.257,r)
for i in range(15):m.box('Grille',-.235,-.43+i*.058,-.268,.47,.009,.012)
m.box('Indicator',.18,.42,-.27,.025,.025,.012);m.write('jukebox_standing_speaker')
m=Mesh();m.box('Case',-.35,.36,-.35,.70,.14,.70);m.cone('Grille',0,.352,0,.29,True);m.cone('Cone',0,.35,0,.22,True)
for i in range(17):m.box('Grille',-.29,.341,-.27+i*.034,.58,.007,.009)
m.box('Indicator',.28,.34,.28,.02,.012,.02);m.write('jukebox_ceiling_speaker')
