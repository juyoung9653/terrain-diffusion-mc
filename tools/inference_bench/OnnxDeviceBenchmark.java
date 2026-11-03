import ai.onnxruntime.*;
import ai.onnxruntime.providers.OrtCUDAProviderOptions;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Small, standalone FP32 decoder benchmark for comparing ONNX CPU and CUDA. */
public final class OnnxDeviceBenchmark {
    static final int SIDE=256, CHANNELS=5, COUNT=SIDE*SIDE*CHANNELS;
    record Case(String id, Path x, Path labels) {}
    static final class Row { String id, hash; long[] ns; Row(String i,String h,long[] n){id=i;hash=h;ns=n;} }
    public static void main(String[] args) throws Exception {
        Map<String,String> p=new LinkedHashMap<>(); List<Case> cases=new ArrayList<>();
        for(int i=0;i<args.length;i++) {
            if("--case".equals(args[i])) { String[] v=args[++i].split("\\|",3); cases.add(new Case(v[0],Path.of(v[1]),Path.of(v[2]))); }
            else if(args[i].startsWith("--")) p.put(args[i],args[++i]); else throw new IllegalArgumentException("bad argument "+args[i]);
        }
        String provider=req(p,"--provider"), model=req(p,"--model"), out=req(p,"--result");
        int warmup=Integer.parseInt(p.getOrDefault("--warmup","2")), runs=Integer.parseInt(p.getOrDefault("--runs","6"));
        int intra=Integer.parseInt(p.getOrDefault("--intra","0"));
        if(!Set.of("cpu","cuda").contains(provider)||cases.isEmpty()||runs<1||warmup<0||intra<0) throw new IllegalArgumentException("invalid settings");
        OrtEnvironment env=OrtEnvironment.getEnvironment();
        if("cuda".equals(provider)&&!env.getAvailableProviders().toString().contains("CUDA")) throw new IllegalStateException("CUDA unavailable: "+env.getAvailableProviders());
        long processStart=ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toNanos();
        long wallStart=System.nanoTime(); long constructStart=System.nanoTime();
        try(OrtSession.SessionOptions opt=options(provider,intra); OrtSession session=env.createSession(model,opt)) {
            long construction=System.nanoTime()-constructStart;
            for(Case c:cases) { float[] x=read(c.x,COUNT), n=read(c.labels,1); for(int j=0;j<warmup;j++) run(env,session,x,n); }
            Map<String,List<Long>> times=new LinkedHashMap<>(); Map<String,String> hashes=new LinkedHashMap<>();
            for(int r=0;r<runs;r++) for(Case c:cases) { Sample s=run(env,session,read(c.x,COUNT),read(c.labels,1)); for(float value:s.values) if(!Float.isFinite(value)) throw new IllegalStateException("non-finite decoder output: "+c.id); times.computeIfAbsent(c.id,k->new ArrayList<>()).add(s.ns); hashes.put(c.id,shaFloats(s.values)); }
            long wall=System.nanoTime()-wallStart;
            long cpu=ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toNanos()-processStart;
            write(Path.of(out),provider,intra,env.getVersion(),env.getAvailableProviders().toString(),construction,wall,cpu,cases,times,hashes);
        }
    }
    static String req(Map<String,String> p,String key){String v=p.get(key);if(v==null)throw new IllegalArgumentException("missing "+key);return v;}
    static OrtSession.SessionOptions options(String provider,int intra) throws OrtException {
        OrtSession.SessionOptions o=new OrtSession.SessionOptions(); o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if("cpu".equals(provider)) { if(intra>0) o.setIntraOpNumThreads(intra); }
        else try(OrtCUDAProviderOptions c=new OrtCUDAProviderOptions(0)) { c.add("arena_extend_strategy","kSameAsRequested"); c.add("cudnn_conv_algo_search","HEURISTIC"); c.add("do_copy_in_default_stream","1"); o.addCUDA(c); }
        return o;
    }
    record Sample(float[] values,long ns){}
    static Sample run(OrtEnvironment e,OrtSession s,float[] x,float[] n)throws OrtException { long at=System.nanoTime(); Map<String,OnnxTensor> in=new LinkedHashMap<>(); try { in.put("x",OnnxTensor.createTensor(e,FloatBuffer.wrap(x),new long[]{1,5,256,256})); in.put("noise_labels",OnnxTensor.createTensor(e,FloatBuffer.wrap(n),new long[]{1})); try(OrtSession.Result r=s.run(in)){FloatBuffer b=((OnnxTensor)r.get(0)).getFloatBuffer();float[] v=new float[b.remaining()];b.get(v);return new Sample(v,System.nanoTime()-at);} } finally {for(OnnxTensor t:in.values())t.close();}}
    static float[] read(Path p,int n)throws IOException{byte[] b=Files.readAllBytes(p);if(b.length!=n*4)throw new IOException("bad FP32 input "+p);float[] v=new float[n];ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v);return v;}
    static String shaFloats(float[] v)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");ByteBuffer b=ByteBuffer.allocate(v.length*4).order(ByteOrder.LITTLE_ENDIAN);b.asFloatBuffer().put(v);d.update(b.array());StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format("%02x",x));return s.toString();}
    static double median(List<Long> v){long[] a=v.stream().mapToLong(Long::longValue).sorted().toArray();return a[(a.length-1)/2]/1e6;}
    static String q(String x){return "\""+x.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    static void write(Path p,String provider,int intra,String ort,String providers,long construction,long wall,long cpu,List<Case> cs,Map<String,List<Long>> ts,Map<String,String> hashes)throws IOException{StringBuilder b=new StringBuilder("{\"provider\":").append(q(provider)).append(",\"intraOpThreads\":").append(intra).append(",\"ort\":").append(q(ort)).append(",\"availableProviders\":").append(q(providers)).append(",\"sessionConstructionMs\":").append(construction/1e6).append(",\"wallMs\":").append(wall/1e6).append(",\"processCpuMs\":").append(cpu/1e6).append(",\"averageUtilizedLogicalCores\":").append(wall==0?0:(double)cpu/wall).append(",\"cases\":[");for(int i=0;i<cs.size();i++){if(i>0)b.append(',');String id=cs.get(i).id;List<Long> v=ts.get(id);b.append("{\"id\":").append(q(id)).append(",\"runs\":").append(v.size()).append(",\"medianMs\":").append(median(v)).append(",\"nanos\":").append(v).append(",\"outputSha256\":").append(q(hashes.get(id))).append('}');}b.append("]}");Files.writeString(p,b.toString());}
}
