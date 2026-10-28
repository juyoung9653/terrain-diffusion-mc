"""Prepare (and, only with --capture, run) a real WorldPipeline decoder-input capture.

The copied WorldPipeline has one hook immediately before its decoder invocation.
It preserves all production math and invokes the original decoder afterwards.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/"common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/WorldPipeline.java"
RUNNER=Path(__file__).with_name("DecoderInputCapture.java")
STUBS=Path(__file__).with_name("capture_stubs")
DEFAULT_RELEASE=ROOT/"build/chunk-profile/optimization-next/release/NeoForge-terrain-diffusion-mc-2.3.0-cuda+1.21.1.jar"
DEFAULT_MODELS=ROOT/"build/chunk-profile/td-decoder-cache-256-b/terrain-diffusion-models"
DEFAULT_OUTPUT=ROOT/"build/inference-bench/decoder-precision/capture-final"


def sha(path:Path)->str:
    digest=hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda:stream.read(1<<20),b""): digest.update(data)
    return digest.hexdigest()


def cached(pattern:str)->Path:
    found=sorted((ROOT/".gradle/caches/modules-2/files-2.1").glob(pattern))
    if not found: raise FileNotFoundError(f"cached dependency not found: {pattern}")
    return found[-1]


def require(path:Path,label:str)->None:
    if not path.is_file(): raise FileNotFoundError(f"missing {label}: {path}")


def copy_models(source:Path,target:Path,decoder_override:Path|None)->tuple[dict[str,str],str]:
    target.mkdir(parents=True)
    hashes={}
    for name in ("coarse_model.onnx","base_model.onnx","decoder_model.onnx",
                 "world_pipeline_config.json","pipeline_data.json"):
        origin=decoder_override if name=="decoder_model.onnx" and decoder_override is not None else source/name
        require(origin,"pinned capture asset")
        destination=target/name
        shutil.copy2(origin,destination)
        if sha(origin)!=sha(destination): raise RuntimeError(f"hash mismatch while copying {name}")
        hashes[name]=sha(destination)
    return hashes, sha(decoder_override) if decoder_override is not None else hashes["decoder_model.onnx"]


def inject_pipeline(target:Path)->str:
    text=SOURCE.read_text(encoding="utf-8")
    needle="        float[] rawPred = decoderModel.runModel(modelIn, new long[]{1, 5, S, S}, new float[]{t}, null, null);"
    hook="        DecoderInputCapture.capture(wi[1], wi[2], i1, j1, modelIn, t);\n"
    if text.count(needle)!=1: raise RuntimeError("WorldPipeline decoder call injection anchor changed")
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(text.replace(needle,hook+needle),encoding="utf-8")
    return sha(SOURCE)


def main()->int:
    parser=argparse.ArgumentParser()
    parser.add_argument("--output",type=Path,default=DEFAULT_OUTPUT)
    parser.add_argument("--models",type=Path,default=DEFAULT_MODELS)
    parser.add_argument("--release-jar",type=Path,default=DEFAULT_RELEASE)
    parser.add_argument("--decoder-model",type=Path,
                        help="optional candidate decoder copied into the isolated model directory before capture")
    parser.add_argument("--preoptimized-decoder",action="store_true",
                        help="skip CPU optimization of an already optimized FP16 decoder; recorded at preparation")
    parser.add_argument("--java-home",type=Path,default=Path(r"C:\Program Files\Java\jdk-21"))
    parser.add_argument("--capture",action="store_true",help="run GPU capture after compiling; omitted by default")
    parser.add_argument("--max-per-region",type=int,default=4)
    parser.add_argument("--timeout",type=int,default=600,help="GPU capture timeout in seconds")
    args=parser.parse_args()
    if not 1<=args.max_per_region<=8: raise SystemExit("--max-per-region must be 1..8")
    if args.timeout<1: raise SystemExit("--timeout must be positive")
    javac=args.java_home/"bin/javac.exe"; java=args.java_home/"bin/java.exe"
    for path,label in ((SOURCE,"production WorldPipeline source"),(RUNNER,"capture hook"),(args.release_jar,"release JAR"),(javac,"JDK 21 javac"),(java,"JDK 21 java")):
        require(path,label)
    ort=cached("com.microsoft.onnxruntime/onnxruntime_gpu/*/*/onnxruntime_gpu-*.jar")
    gson=cached("com.google.code.gson/gson/*/*/gson-*.jar")
    slf4j=cached("org.slf4j/slf4j-api/*/*/slf4j-api-*.jar")
    output=args.output.resolve()
    if args.capture:
        if not (output/"capture-preparation.json").is_file(): raise SystemExit("capture requires a prepared output")
        if (output/"manifest.json").exists() or (output/"capture-run-receipt.json").exists():
            raise SystemExit("refusing to overwrite a completed capture")
    else:
        if output.exists(): raise SystemExit(f"refusing existing capture output: {output}")
        output.mkdir(parents=True)
        model_hashes,candidate_sha=copy_models(args.models.resolve(),output/"models",
                                               args.decoder_model.resolve() if args.decoder_model else None)
        generated=output/"src/com/github/xandergos/terraindiffusionmc/pipeline/WorldPipeline.java"
        pipeline_sha=inject_pipeline(generated)
        shutil.copy2(RUNNER,output/"src/com/github/xandergos/terraindiffusionmc/pipeline/DecoderInputCapture.java")
        for stub in STUBS.rglob("*.java"):
            copied=output/"src"/stub.relative_to(STUBS); copied.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(stub,copied)
        sources=sorted((output/"src").rglob("*.java"))
        classes=output/"classes"; classes.mkdir()
        cp=os.pathsep.join(map(str,(args.release_jar.resolve(),ort,gson,slf4j)))
        sys.path.insert(0,str(ROOT/"tools")); from probe_suite import exclusive_lock
        with exclusive_lock(ROOT):
            subprocess.run([str(javac),"--release","21","-encoding","UTF-8","-cp",cp,"-d",str(classes),*map(str,sources)],
                           cwd=ROOT,check=True,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
        receipt={"kind":"decoder_input_capture_preparation","pipelineSource":str(SOURCE),"pipelineSourceSha256":pipeline_sha,
                 "releaseJar":str(args.release_jar.resolve()),"releaseJarSha256":sha(args.release_jar),"models":model_hashes,
                 "dependencies":{"onnxruntimeGpu":str(ort),"onnxruntimeGpuSha256":sha(ort),"gson":str(gson),"slf4j":str(slf4j)},
                 "shape":[1,5,256,256],"floatEncoding":"little-endian float32","seed":1018612869571620377,
                 "decoderCandidateSource":str(args.decoder_model.resolve()) if args.decoder_model else None,
                 "decoderCandidateSha256":candidate_sha,
                 "preoptimizedDecoder":args.preoptimized_decoder,
                 "regions":{"woodland":{"i":-1024,"j":-240},"ocean":{"i":16384,"j":16384},"third-region":{"i":-8192,"j":4096}},
                 "cropContract":"getElevationCrop(i-218,j-218,i+256+218,j+256+218,i,j,i+256,j+256)","maxPerRegion":args.max_per_region,
                 "captureRequiresExplicitFlag":True}
        receipt["captureSourcesSha256"]={str(path.relative_to(output/"src")):sha(path) for path in sources}
        (output/"capture-preparation.json").write_text(json.dumps(receipt,indent=2)+"\n",encoding="utf-8")
        print(output/"capture-preparation.json")
        return 0
    receipt=json.loads((output/"capture-preparation.json").read_text(encoding="utf-8"))
    if sha(args.release_jar)!=receipt["releaseJarSha256"]: raise SystemExit("release JAR changed since capture preparation")
    for name,digest in receipt["models"].items():
        if sha(output/"models"/name)!=digest: raise SystemExit(f"copied asset changed since capture preparation: {name}")
    for name,digest in receipt.get("captureSourcesSha256",{}).items():
        if sha(output/"src"/name)!=digest: raise SystemExit(f"capture source changed since preparation: {name}")
    cp=os.pathsep.join(map(str,(output/"classes",args.release_jar.resolve(),ort,gson,slf4j)))
    command=[str(java),"-Xmx10G",f"-Dtd.capture.output={output}",f"-Dtd.capture.models={output/'models'}",
             f"-Dtd.capture.preoptimizedDecoder={str(receipt.get('preoptimizedDecoder',False)).lower()}",
             "-Dtd.capture.seed=1018612869571620377",f"-Dtd.capture.maxPerRegion={args.max_per_region}",
             f"-Dtd.capture.pipelineSourceSha256={receipt['pipelineSourceSha256']}",f"-Dtd.capture.releaseJarSha256={receipt['releaseJarSha256']}",
             f"-Dtd.capture.decoderSha256={receipt['models']['decoder_model.onnx']}",f"-Dtd.capture.decoderCandidateSha256={receipt['decoderCandidateSha256']}","-cp",cp,
             "com.github.xandergos.terraindiffusionmc.pipeline.DecoderInputCapture"]
    for name,digest in receipt["models"].items(): command.insert(-3,f"-Dtd.capture.sha.{name}={digest}")
    sys.path.insert(0,str(ROOT/"tools")); from probe_suite import exclusive_lock
    started=time.monotonic()
    try:
        with exclusive_lock(ROOT):
            completed=subprocess.run(command,cwd=ROOT,text=True,capture_output=True,timeout=args.timeout,
                                     creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
        stdout=completed.stdout+completed.stderr; exit_code=completed.returncode; timed_out=False
    except subprocess.TimeoutExpired as failure:
        stdout="".join(value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value or ""
                       for value in (failure.stdout, failure.stderr))
        exit_code=None; timed_out=True
    log=output/"capture-stdout.log"; log.write_text(stdout,encoding="utf-8")
    run_receipt={"kind":"decoder_input_capture_run","command":command,"timeoutSeconds":args.timeout,
                 "elapsedSeconds":round(time.monotonic()-started,3),"exitCode":exit_code,"timedOut":timed_out,
                 "stdoutSha256":sha(log)}
    (output/"capture-run-receipt.json").write_text(json.dumps(run_receipt,indent=2)+"\n",encoding="utf-8")
    if timed_out: raise SystemExit(f"capture timed out; see {log}")
    if completed.returncode: raise SystemExit(f"capture failed; see {output/'capture-stdout.log'}")
    manifest=output/"manifest.json"
    if not manifest.is_file(): raise SystemExit("capture exited without manifest")
    print(manifest); return 0


if __name__=="__main__": raise SystemExit(main())
