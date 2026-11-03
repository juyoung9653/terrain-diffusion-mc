"""Run an isolated A/B device measurement on frozen real decoder inputs."""
from __future__ import annotations
import argparse, hashlib, json, shutil, subprocess, sys, time, winreg
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]; SOURCE=Path(__file__).with_name("OnnxDeviceBenchmark.java")
sys.path.insert(0,str(ROOT/"tools")); from probe_suite import exclusive_lock
def sha(p:Path)->str:
 h=hashlib.sha256();
 with p.open("rb") as f:
  for x in iter(lambda:f.read(1<<20),b""):h.update(x)
 return h.hexdigest()
def hardware()->dict:
 try:
  with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as key: cpu=winreg.QueryValueEx(key,"ProcessorNameString")[0]
 except OSError: cpu=None
 try:
  q=subprocess.run(["nvidia-smi","--query-gpu=name","--format=csv,noheader"],text=True,capture_output=True,check=True,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0)); gpu=q.stdout.strip() or None
 except (OSError,subprocess.CalledProcessError): gpu=None
 return {"cpu":cpu,"gpu":gpu}
def main():
 a=argparse.ArgumentParser();a.add_argument("--capture",type=Path,required=True);a.add_argument("--output",type=Path,required=True);a.add_argument("--java-home",type=Path,default=Path(r"C:\Program Files\Java\jdk-21"));a.add_argument("--warmup",type=int,default=2);a.add_argument("--runs",type=int,default=6);a.add_argument("--timeout",type=int,default=900);a.add_argument("--intra",type=int,default=30);a.add_argument("--validate-receipt",type=Path,help="one-pass finite/output-hash validation against a prior receipt, not timing evidence");x=a.parse_args()
 x.capture=x.capture.resolve();x.output=x.output.resolve();x.validate_receipt=x.validate_receipt.resolve() if x.validate_receipt else None
 if x.output.exists():raise SystemExit(f"refusing existing output: {x.output}")
 manifest=json.loads((x.capture/"manifest.json").read_text());model=x.capture/"models/decoder_model.onnx";ort=next((ROOT/".gradle").glob("caches/modules-2/files-2.1/com.microsoft.onnxruntime/onnxruntime_gpu/*/*/onnxruntime_gpu-*.jar"),None)
 if not ort or not model.is_file():raise SystemExit("missing decoder or ONNX Runtime GPU jar")
 x.output.mkdir(parents=True);classes=x.output/"classes";classes.mkdir();frozen=x.output/SOURCE.name;shutil.copy2(SOURCE,frozen)
 command_base=[str(x.java_home/"bin/java.exe"),"-cp",f"{classes};{ort}","OnnxDeviceBenchmark","--model",str(model),"--warmup",str(x.warmup),"--runs",str(x.runs)]
 inputs=[];input_contract=[]
 for c in manifest["cases"]:
  xp=(x.capture/c['x']).resolve();np=(x.capture/c['noiseLabels']).resolve()
  if not xp.is_file() or not np.is_file():raise SystemExit(f"missing captured input for {c['id']}")
  input_contract.append({"id":c['id'],"x":str(xp),"xSha256":sha(xp),"noiseLabels":str(np),"noiseLabelsSha256":sha(np)})
  inputs += ["--case",f"{c['id']}|{xp}|{np}"]
 if x.validate_receipt and (x.warmup!=0 or x.runs!=1): raise SystemExit("--validate-receipt requires --warmup 0 --runs 1")
 if x.validate_receipt:
  prior=json.loads(x.validate_receipt.read_text(encoding="utf-8"))
  for key,actual in (("modelSha256",sha(model)),("ortJarSha256",sha(ort)),("captureManifestSha256",sha(x.capture/"manifest.json"))):
   if prior.get(key)!=actual:raise SystemExit(f"validation contract mismatch: {key}")
  posthoc=x.validate_receipt.with_name("posthoc-metadata.json")
  if posthoc.is_file():
   expected={(q['id'],q['xSha256'],q['noiseLabelsSha256']) for q in json.loads(posthoc.read_text())["inputs"]}
   actual={(q['id'],q['xSha256'],q['noiseLabelsSha256']) for q in input_contract}
   if actual!=expected:raise SystemExit("validation contract mismatch: captured input hashes")
 variants=([("cuda",0),("cpu-default",0),(f"cpu-intra-{x.intra}",x.intra)] if x.validate_receipt else [("cuda",0),("cpu-default",0),(f"cpu-intra-{x.intra}",x.intra),(f"cpu-intra-{x.intra}",x.intra),("cpu-default",0),("cuda",0)])
 with exclusive_lock(ROOT):
  q=subprocess.run([str(x.java_home/"bin/javac.exe"),"--release","21","-cp",str(ort),"-d",str(classes),str(frozen)],cwd=ROOT,text=True,capture_output=True,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0));(x.output/"compile.log").write_text(q.stdout+q.stderr);q.check_returncode()
  receipts=[]
  for seq,(name,intra) in enumerate(variants):
   result=x.output/f"{seq:02d}-{name}.json";cmd=command_base+["--provider","cuda" if name=="cuda" else "cpu","--intra",str(intra),"--result",str(result),*inputs];started=time.monotonic();q=subprocess.run(cmd,cwd=ROOT,text=True,capture_output=True,timeout=x.timeout,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0));(x.output/f"{seq:02d}-{name}.log").write_text(q.stdout+q.stderr);q.check_returncode();receipts.append({"sequence":seq,"name":name,"result":result.name,"elapsedSeconds":round(time.monotonic()-started,3),"resultSha256":sha(result)})
 rows=[json.loads((x.output/r["result"]).read_text()) for r in receipts]
 if any(sha(Path(q["x"]))!=q["xSha256"] or sha(Path(q["noiseLabels"]))!=q["noiseLabelsSha256"] for q in input_contract):raise SystemExit("captured input changed during run")
 validation=None
 if x.validate_receipt:
  expected={}
  for row in prior["results"]:
   key="cuda" if row["provider"]=="cuda" else ("cpu-default" if row["intraOpThreads"]==0 else f"cpu-intra-{row['intraOpThreads']}")
   expected[key]={c["id"]:c["outputSha256"] for c in row["cases"]}
  validation={}
  for (name,_),row in zip(variants,rows):
   actual={c["id"]:c["outputSha256"] for c in row["cases"]}; validation[name]={"sameFinalOutputHashes":sum(actual[k]==expected[name][k] for k in actual),"totalCases":len(actual)}
   if validation[name]["sameFinalOutputHashes"]!=len(actual): raise SystemExit(f"output hash mismatch: {name}")
 summary={"kind":"cpu_cuda_decoder_device_benchmark","status":"success","hardware":hardware(),"sourceSha256":sha(frozen),"modelSha256":sha(model),"ortJar":str(ort),"ortJarSha256":sha(ort),"captureManifestSha256":sha(x.capture/"manifest.json"),"capture":str(x.capture),"inputContract":input_contract,"warmup":x.warmup,"runs":x.runs,"finiteOutputValidation":"Every timed FP32 output value was checked with Float.isFinite; any nonfinite output fails the run.","validationAgainstReceipt":str(x.validate_receipt) if x.validate_receipt else None,"validation":validation,"variants":receipts,"results":rows,"limitations":["Isolated decoder calls exclude the coarse/base models and Minecraft chunk/block generation.","CPU default permits ONNX Runtime's default intra-op choice; CPU intra is an explicit 30-thread request (default graph execution remains sequential).","Each JVM includes session construction in wall/CPU counters; per-call medians exclude it."]}
 (x.output/"receipt.json").write_text(json.dumps(summary,indent=2)+"\n");print(x.output/"receipt.json")
if __name__=="__main__":main()
