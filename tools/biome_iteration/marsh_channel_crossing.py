"""Read-only saved-block report for a low Streams channel through marsh water."""
from __future__ import annotations
import argparse
from collections import Counter, deque
import json
from pathlib import Path
import sys
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from measure import AIR, SavedWorld
STREAM = "streamsreflowing:stream"
VANILLA = {"minecraft:water", "minecraft:flowing_water"}
PLANTS = ("grass", "fern", "flower", "vine", "bush", "sapling", "lily", "sugar_cane", "seagrass", "kelp", "moss", "propagule")
def wet(s): return s is not None and (s[1] or s[0] in VANILLA or s[0] == STREAM)
def passable(s): return s is not None and (s[0] in AIR or any(p in s[0].removeprefix("minecraft:") for p in PLANTS))
def top(w,x,z,predicate):
    values=[y for y in range(45,97) if predicate(w.state(x,y,z))]
    return max(values) if values else None
def dry_top(w,x,z): return top(w,x,z,lambda s:s is not None and not wet(s) and not passable(s))
def components(points):
    result=[]
    while points:
        start=points.pop(); q=deque([start]); group=[start]
        while q:
            x,z=q.popleft()
            for n in ((x-1,z),(x+1,z),(x,z-1),(x,z+1)):
                if n in points: points.remove(n);q.append(n);group.append(n)
        result.append({"size":len(group),"bounds":[min(x for x,_ in group),min(z for _,z in group),max(x for x,_ in group),max(z for _,z in group)]})
    return sorted(result,key=lambda x:x["size"],reverse=True)
def profile(w, x, z):
    stack=[]
    for y in range(55,81):
        state=w.state(x,y,z)
        if state is None: raise RuntimeError(f"missing or non-FULL block at {x},{y},{z}")
        stack.append({"y":y,"block":state[0],"waterlogged":state[1]})
    return {"x":x,"z":z,"stack":stack}
def report(path,bounds,sites):
    w=SavedWorld(path);x0,z0,x1,z1=bounds;stream=set();standing=set();tops=Counter();cross=[];below=[];incised=[]
    for z in range(z0,z1+1):
      for x in range(x0,x1+1):
        st=top(w,x,z,lambda s:s is not None and s[0]==STREAM); vt=top(w,x,z,lambda s:s is not None and s[0] in VANILLA)
        if vt is not None: standing.add((x,z));tops[vt]+=1
        if st is None: continue
        stream.add((x,z)); ns=[(x-1,z),(x+1,z),(x,z-1),(x,z+1)]
        waters=[top(w,a,b,lambda s:s is not None and s[0] in VANILLA) for a,b in ns if x0<=a<=x1 and z0<=b<=z1]; waters=[a for a in waters if a is not None]
        if waters:
          item={"x":x,"z":z,"streamTop":st,"neighborWaterTop":max(waters)};cross.append(item)
          if st<=item["neighborWaterTop"]-2:below.append(item)
        dry=[dry_top(w,a,b) for a,b in ns if x0<=a<=x1 and z0<=b<=z1];dry=[a for a in dry if a is not None]
        if dry and max(dry)>=st+3:incised.append({"x":x,"z":z,"streamTop":st,"neighborDryTop":max(dry)})
    if w.missing_chunks or w.non_full_chunks or not w.stable():raise RuntimeError("report requires stable, saved FULL chunks")
    return {"schema":1,"kind":"marsh-channel-crossing-physical","world":str(path.resolve()),"bounds":list(bounds),"fixedProfiles":[profile(w,*site) for site in sites],"streamColumns":len(stream),"standingWaterColumns":len(standing),"standingWaterTops":dict(sorted(tops.items())),"streamComponents":components(stream),"standingWaterComponents":components(standing),"streamTouchesStandingWater":cross,"streamBelowStandingWaterBy2":below,"streamIncisedAgainstDryGroundBy3":incised,"summary":{"standingWaterCrossings":len(cross),"belowStandingWaterBy2":len(below),"dryIncisionsBy3":len(incised)}}
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument("--world",type=Path,required=True);p.add_argument("--bounds",default="523,195,587,259");p.add_argument("--sites",default="555,227;572,230;577,200;582,254;563,271;567,286;554,250;529,205");p.add_argument("--output",type=Path,required=True);a=p.parse_args();b=tuple(map(int,a.bounds.split(",")));sites=[tuple(map(int,pair.split(","))) for pair in a.sites.split(";")]
 if len(b)!=4 or b[0]>b[2] or b[1]>b[3]:p.error("--bounds must be x0,z0,x1,z1")
 a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report(a.world,b,sites),ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
if __name__=="__main__":main()
