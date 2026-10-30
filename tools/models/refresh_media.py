#!/usr/bin/env python3
"""Match the original media cabinets to the core 3.0.68 equipment finishes.

Regeneration uses the sibling ELN checkout's original mesh authoring tool. Building the addon
does not run this script and needs only the compiled core jar, as before.
"""
from pathlib import Path
import importlib.util
import json
import subprocess

ROOT=Path(__file__).resolve().parents[2]
tool=ROOT.parent/'ELN1.21.1ForkPatch/tools/models/refresh_equipment.py'
spec=importlib.util.spec_from_file_location('equipment_refresh',tool)
refresh=importlib.util.module_from_spec(spec);spec.loader.exec_module(refresh)
refresh.ROOT=ROOT;refresh.ASSETS=ROOT/'src/main/resources/assets/eln'
results=[]
for name in ['industrial_jukebox','industrial_tv','wall_browser_tv']:
    path=f'{name}/{name}.obj'
    original=subprocess.check_output(['git','show','v1.0.9:src/main/resources/assets/eln/model/'+path],cwd=ROOT,text=True)
    m=refresh.Model(original)
    beveled=m.bevel_boxes(['Case','Base'])
    if name=='industrial_jukebox':
        refresh.cabinet_details(m,name)
        # Twin speaker grilles remain below the existing display and media buttons.
        for x in [-.19,.19]:
            for row in range(8):
                y=-.215+row*.028
                m.box('Trim',(x-.10,y,.341),(x+.10,y+.008,.345),'rubber')
        m.plate('Trim',(.23,-.33,.304),.07,.045,'label')
    else:
        lo,hi=m.bounds('Case');x,y,z=lo;X,Y,Z=hi
        # Rear ventilation, service cover and stand/bracket fasteners; never cover the browser.
        for i in range(12):
            px=-.50+i*.085
            m.box('Trim',(px,Y-.32,z-.003),(px+.035,Y-.10,z+.001),'rubber')
        m.box('Trim',(-.28,y+.12,z-.009),(.28,y+.36,z-.002),'rubber')
        for px in [-.235,.235]:
            for py in [y+.15,y+.33]:m.cyl('Trim',(px,py,z-.012),(px,py,z-.009),.009,'metal',8)
    results.append(refresh.save(m,path)|{'beveledBoxes':beveled})
out=ROOT/'docs/verification/release-1.0.10';out.mkdir(parents=True,exist_ok=True)
(out/'models.json').write_text(json.dumps(results,indent=2)+'\n')
print('Refreshed all three media cabinets; browser picture planes and cable terminals retained.')
