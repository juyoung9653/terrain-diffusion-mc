import ai.onnxruntime.*;
import ai.onnxruntime.providers.OrtCUDAProviderOptions;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** CUDA-only N=1 decoder benchmark; input/output tensors remain float32. */
public final class OnnxDecoderBenchmark {
    static final int SIDE=256, CHANNELS=5, COUNT=CHANNELS*SIDE*SIDE;
    record Case(String id,Path x,Path labels){} record Sample(float[] out,long ns){}
    static final class Stability {
        float[] first;
        int changedRepeats;
        long nonfinitePairs;
        double maxAbs;
        void observe(float[] values) {
            if (first == null) { first = values; return; }
            if (values.length != first.length) throw new IllegalStateException("output shape changed");
            boolean changed = false;
            for (int i=0; i<values.length; i++) {
                changed |= Float.floatToRawIntBits(first[i]) != Float.floatToRawIntBits(values[i]);
                if (Float.isFinite(first[i]) && Float.isFinite(values[i]))
                    maxAbs = Math.max(maxAbs, Math.abs((double)first[i] - values[i]));
                else nonfinitePairs++;
            }
            if (changed) changedRepeats++;
        }
    }
    public static void main(String[] a) throws Exception {
        Map<String,String> p=new LinkedHashMap<>(); List<Case> cases=new ArrayList<>();
        for(int i=0;i<a.length;i++) {
            if(a[i].equals("--case")) { String[] v=a[++i].split("\\|",3); cases.add(new Case(v[0],Path.of(v[1]),Path.of(v[2]))); }
            else if(a[i].startsWith("--")) p.put(a[i],a[++i]); else throw new IllegalArgumentException("bad arg "+a[i]);
        }
        if(cases.isEmpty()) throw new IllegalArgumentException("at least one --case required");
        int warmup=Integer.parseInt(p.getOrDefault("--warmup","4")), runs=Integer.parseInt(p.getOrDefault("--runs","12"));
        String algorithm=p.getOrDefault("--algorithm","HEURISTIC"), variant=p.getOrDefault("--variant","model");
        if(!Set.of("HEURISTIC","EXHAUSTIVE").contains(algorithm)||warmup<0||runs<1) throw new IllegalArgumentException("invalid settings");
        OrtEnvironment env=OrtEnvironment.getEnvironment();
        if(!env.getAvailableProviders().toString().contains("CUDA")) throw new IllegalStateException("CUDA unavailable: "+env.getAvailableProviders());
        Path out=Path.of(p.get("--out")); Files.createDirectories(out);
        Map<String,List<Long>> timings=new LinkedHashMap<>(); Map<String,float[]> saved=new LinkedHashMap<>();
        Map<String,Stability> stability=new LinkedHashMap<>();
        try(OrtSession.SessionOptions opt=options(algorithm)) {
            long sessionStarted=System.nanoTime();
            try(OrtSession s=env.createSession(p.get("--model"),opt)) {
            long sessionNanos=System.nanoTime()-sessionStarted;
            if(!s.getInputNames().containsAll(Set.of("x","noise_labels"))) throw new IllegalStateException("decoder inputs: "+s.getInputNames());
            for(Case c:cases) { float[] x=read(c.x,COUNT), n=read(c.labels,1); for(int i=0;i<warmup;i++) run(env,s,x,n); }
            for(int r=0;r<runs;r++) for(Case c:cases) { Sample q=run(env,s,read(c.x,COUNT),read(c.labels,1)); timings.computeIfAbsent(c.id,k->new ArrayList<>()).add(q.ns); saved.put(c.id,q.out); stability.computeIfAbsent(c.id,k->new Stability()).observe(q.out); }
            for(Case c:cases) write(out.resolve(c.id+".fp32"),saved.get(c.id));
            json(Path.of(p.get("--result")),variant,algorithm,env.getVersion(),sessionNanos,cases,timings,stability,s.getInputInfo(),s.getOutputInfo());
            }
        }
    }
    static OrtSession.SessionOptions options(String algorithm) throws OrtException {
        OrtSession.SessionOptions o=new OrtSession.SessionOptions(); o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        try(OrtCUDAProviderOptions c=new OrtCUDAProviderOptions(0)) { c.add("arena_extend_strategy","kSameAsRequested"); c.add("cudnn_conv_algo_search",algorithm); c.add("do_copy_in_default_stream","1"); o.addCUDA(c); }
        return o;
    }
    static Sample run(OrtEnvironment e,OrtSession s,float[] x,float[] n) throws OrtException {
        long start=System.nanoTime(); Map<String,OnnxTensor> in=new LinkedHashMap<>();
        try { in.put("x",OnnxTensor.createTensor(e,FloatBuffer.wrap(x),new long[]{1,5,256,256})); in.put("noise_labels",OnnxTensor.createTensor(e,FloatBuffer.wrap(n),new long[]{1}));
            try(OrtSession.Result r=s.run(in)) { FloatBuffer b=((OnnxTensor)r.get(0)).getFloatBuffer(); float[] v=new float[b.remaining()]; b.get(v); return new Sample(v,System.nanoTime()-start); }
        } finally { for(OnnxTensor t:in.values()) t.close(); }
    }
    static float[] read(Path p,int count) throws IOException { byte[] b=Files.readAllBytes(p); if(b.length!=count*4) throw new IOException("bad fp32 length "+p+": "+b.length); float[] v=new float[count]; ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v); return v; }
    static void write(Path p,float[] v) throws IOException { ByteBuffer b=ByteBuffer.allocate(v.length*4).order(ByteOrder.LITTLE_ENDIAN); b.asFloatBuffer().put(v); Files.write(p,b.array()); }
    static double percentile(List<Long> v,double p){ long[] a=v.stream().mapToLong(Long::longValue).sorted().toArray(); return a[(int)Math.ceil(p*a.length)-1]/1e6; }
    static String q(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    static void json(Path p,String variant,String algorithm,String ort,long sessionNanos,List<Case> cs,Map<String,List<Long>> ts,Map<String,Stability> stability,Object in,Object out)throws IOException{
        StringBuilder b=new StringBuilder("{\"variant\":").append(q(variant)).append(",\"algorithm\":").append(q(algorithm)).append(",\"ort\":").append(q(ort)).append(",\"sessionConstructionMs\":").append(sessionNanos/1e6).append(",\"inputInfo\":").append(q(String.valueOf(in))).append(",\"outputInfo\":").append(q(String.valueOf(out))).append(",\"cases\":[");
        for(int i=0;i<cs.size();i++){
            if(i>0)b.append(',');
            String id=cs.get(i).id; List<Long> t=ts.get(id); Stability s=stability.get(id);
            b.append("{\"id\":").append(q(id)).append(",\"runs\":").append(t.size()).append(",\"medianMs\":").append(percentile(t,.5)).append(",\"p90Ms\":").append(percentile(t,.9)).append(",\"nanos\":").append(t)
                .append(",\"repeatStability\":{\"changedRepeats\":").append(s.changedRepeats)
                .append(",\"nonfinitePairs\":").append(s.nonfinitePairs)
                .append(",\"maxAbs\":").append(s.maxAbs).append("}}");
        }
        b.append("]}"); Files.writeString(p,b.toString());
    }
}
