package com.github.xandergos.terraindiffusionmc.pipeline;
/**
 * Bit-for-bit differential check against the pre-anchor-table implementation.
 * This is intentionally self-contained so a change to MacroBiomeContext cannot
 * accidentally update its reference at the same time.
 */
public final class MacroBiomeContextParityTest {
    private static final int PAD = 192, COAST = 24;
    private static final int[] R64I = new int[16], R64J = new int[16], R96I = new int[16], R96J = new int[16];
    static {
        for (int k=0;k<16;k++) {
            double a=Math.PI*2d*k/16d;
            R64I[k]=(int)Math.round(Math.sin(a)*64d);
            R64J[k]=(int)Math.round(Math.cos(a)*64d);
            R96I[k]=(int)Math.round(Math.sin(a)*96d);
            R96J[k]=(int)Math.round(Math.cos(a)*96d);
        }
    }
    public static void main(String[] args) {
        boolean benchmark = java.util.Arrays.asList(args).contains("--benchmark");
        int cases = 0;
        for (int scale=1; scale<=6; scale++) {
            Field field = field(-1200, -1300, 2800, 2900);
            // (-899, 528) is an exact equidistant two-anchor tie; legacy keeps
            // the first di/dj candidate because its comparison is strict.
            int[][] boxes = {
                    {-899, 528, 1, 1}, {-257, -259, 1, 1}, {-129, 127, 7, 13},
                    {-1, -1, 65, 67}, {0, 0, 128, 129}, {127, 129, 193, 71},
                    {-384, 253, 257, 191}
            };
            for (int[] box : boxes) {
                BiomeClassifier.TerralithContext expected = legacy(field.elevation, field.climate, field.i0, field.j0, field.h, field.w, scale, 16f / scale, box[0], box[1], box[2], box[3]);
                BiomeClassifier.TerralithContext actual = MacroBiomeContext.build(field.elevation, field.climate, field.i0, field.j0, field.h, field.w, scale, 16f / scale, box[0], box[1], box[2], box[3]);
                compare(expected, actual, "scale=" + scale + " box=" + java.util.Arrays.toString(box));
                cases++;
            }
        }
        System.out.println("MacroBiomeContextParityTest: PASS cases=" + cases);
        if (benchmark) benchmark();
    }
    /**
     * Warming avoids measuring class initialization and reports, rather than
     * asserts, the machine-dependent representative build timing.
     */
    private static void benchmark() {
        Field f = field(-700, -700, 1500, 1500);
        for (int n = 0; n < 3; n++) {
            legacy(f.elevation, f.climate, f.i0, f.j0, f.h, f.w, 1, 16f, -128, -128, 256, 256);
            MacroBiomeContext.build(f.elevation, f.climate, f.i0, f.j0, f.h, f.w, 1, 16f, -128, -128, 256, 256);
        }
        long[] oldTimes = new long[9], newTimes = new long[9];
        long oldChecksum = 0, newChecksum = 0;
        for (int n = 0; n < oldTimes.length; n++) {
            long start = System.nanoTime();
            oldChecksum ^= checksum(legacy(f.elevation, f.climate, f.i0, f.j0, f.h, f.w, 1, 16f, -128, -128, 256, 256));
            oldTimes[n] = System.nanoTime() - start;
            start = System.nanoTime();
            newChecksum ^= checksum(MacroBiomeContext.build(f.elevation, f.climate, f.i0, f.j0, f.h, f.w, 1, 16f, -128, -128, 256, 256));
            newTimes[n] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(oldTimes);
        java.util.Arrays.sort(newTimes);
        if (oldChecksum != newChecksum) throw new AssertionError("benchmark checksum differs");
        System.out.printf(java.util.Locale.ROOT, "benchmark 256x256: old median=%.3f ms, new median=%.3f ms, checksum=%016x%n",                 oldTimes[oldTimes.length / 2] / 1_000_000d, newTimes[newTimes.length / 2] / 1_000_000d, oldChecksum);
    }
    private static long checksum(BiomeClassifier.TerralithContext c) {
        long h=0xcbf29ce484222325L;
        for (float x:c.elevation()) h=(h^Float.floatToRawIntBits(x))*0x100000001b3L;
        for (int i=0;i<c.elevation().length;i++) h=(h^(c.coastal()[i]?1:0))*0x100000001b3L;
        return h;
    }
    private static BiomeClassifier.TerralithContext legacy(float[] elevation,float[] climate,int ni,int nj,int nh,int nw,int scale,float pixel,int i0,int j0,int h,int w) {
        int fi=i0-PAD,fj=j0-PAD,fh=h+2*PAD,fw=w+2*PAD,n=h*w;
        float[] smooth=new float[fh*fw];
        for(int r=0;r<fh;r++)for(int c=0;c<fw;c++) smooth[r*fw+c]=sample(elevation,0,ni,nj,nh,nw,scale,fi+r,fj+c);
        int[] ocean=coastDistance(smooth,fh,fw,h,w);
        float[] e=new float[n],s=new float[n],t=new float[n],p=new float[n],cv=new float[n],d=new float[n],o=new float[n],relief=new float[n];
        boolean[] coast=new boolean[n];
        for(int r=0;r<h;r++)for(int c=0;c<w;c++) {
            int wi=i0+r,wj=j0+c,ai=anchor(wi,wj,true),aj=anchor(wi,wj,false),fr=ai-fi,fc=aj-fj,out=r*w+c;
            e[out]=smooth[fr*fw+fc];
            float dx=smooth[fr*fw+fc+1]-smooth[fr*fw+fc-1],dy=smooth[(fr+1)*fw+fc]-smooth[(fr-1)*fw+fc];
            s[out]=(float)Math.sqrt(dx*dx+dy*dy)/(2f*pixel);
            t[out]=BiomeClassifier.terralithTemperature(sample(climate,0,ni,nj,nh,nw,scale,ai,aj),ai,aj);
            p[out]=BiomeClassifier.terralithPrecipitation(sample(climate,2,ni,nj,nh,nw,scale,ai,aj),ai,aj);
            cv[out]=sample(climate,3,ni,nj,nh,nw,scale,ai,aj);
            coast[out]=ocean[out]<=COAST*COAST;
            d[out]=coast[out]?(float)Math.sqrt(ocean[out]):Float.POSITIVE_INFINITY;
            if(!coast[out])continue;
            int wet=0;
            float lo=Float.POSITIVE_INFINITY,hi=Float.NEGATIVE_INFINITY;
            for(int k=0;k<16;k++){
                int rr=wi+R96I[k]-fi,cc=wj+R96J[k]-fj;
                float v=smooth[rr*fw+cc];
                if(v<0)wet++;
                lo=Math.min(lo,v);
                hi=Math.max(hi,v);
                rr=wi+R64I[k]-fi;
                cc=wj+R64J[k]-fj;
                v=smooth[rr*fw+cc];
                lo=Math.min(lo,v);
                hi=Math.max(hi,v);
            }
            o[out]=wet/16f;
            relief[out]=(hi-lo)/(192f*pixel);
        }
        return new BiomeClassifier.TerralithContext(e,s,t,p,cv,coast,new ShoreContext(d,o,relief));
    }
    private static int anchor(int i,int j,boolean row){
        int ci=Math.floorDiv(i,128),cj=Math.floorDiv(j,128);
        long best=Long.MAX_VALUE;
        int selected=0;
        for(int di=-1;di<=1;di++)for(int dj=-1;dj<=1;dj++){
            int ri=ci+di,rj=cj+dj,hash=hash(ri,rj),ai=ri*128+64+Math.floorMod(hash,65)-32,aj=rj*128+64+Math.floorMod(Integer.rotateLeft(hash,15),65)-32;
            long x=(long)i-ai,y=(long)j-aj,q=x*x+y*y;
            if(q<best){
                best=q;
                selected=row?ai:aj;
            }
        }
        return selected;
    }
    private static int hash(int i,int j){
        int h=0x6d2b79f5;
        h=Integer.rotateLeft(h*0x9e3779b9+i,13);
        h=Integer.rotateLeft(h*0x85ebca6b+j,11);
        h^=h>>>16;
        h*=0x85ebca6b;
        h^=h>>>13;
        return h^h>>>16;
    }
    private static float sample(float[] x,int ch,int i0,int j0,int h,int w,int scale,int wi,int wj){
        double i=((double)wi+.5d)/scale-.5d,j=((double)wj+.5d)/scale-.5d,y=i-i0,z=j-j0;
        int y0=(int)Math.floor(y),x0=(int)Math.floor(z);
        float fy=(float)(y-y0),fx=(float)(z-x0);
        if(y0<0||y0+1>=h||x0<0||x0+1>=w)throw new IllegalArgumentException();
        int off=ch*h*w;
        float a=x[off+y0*w+x0],b=x[off+y0*w+x0+1],c=x[off+(y0+1)*w+x0],d=x[off+(y0+1)*w+x0+1];
        return(a+(b-a)*fx)+((c+(d-c)*fx)-(a+(b-a)*fx))*fy;
    }
    private static int[] coastDistance(float[] x,int h,int w,int oh,int ow){
        int[] horizontal=new int[h*w],result=new int[oh*ow];
        final int inf=1_000_000;
        for(int r=0;r<h;r++){
            int left=inf;
            for(int c=0;c<w;c++){
                if(x[r*w+c]<0)left=0;
                else if(left<inf)left++;
                horizontal[r*w+c]=left;
            }
            int right=inf;
            for(int c=w-1;c>=0;c--){
                if(x[r*w+c]<0)right=0;
                else if(right<inf)right++;
                horizontal[r*w+c]=Math.min(horizontal[r*w+c],right);
            }
        }
        for(int r=0;r<oh;r++)for(int c=0;c<ow;c++){
            int best=inf;
            for(int dy=-COAST;dy<=COAST;dy++){
                int rr=r+PAD+dy;
                if(rr>=0&&rr<h){
                    int dx=horizontal[rr*w+c+PAD];
                    if(dx<inf)best=Math.min(best,dx*dx+dy*dy);
                }
            }
            result[r*ow+c]=best;
        }
        return result;
    }
    private static void compare(BiomeClassifier.TerralithContext a,BiomeClassifier.TerralithContext b,String where){
        floats(a.elevation(),b.elevation(),where+" elevation");
        floats(a.slope(),b.slope(),where+" slope");
        floats(a.temperature(),b.temperature(),where+" temperature");
        floats(a.precipitation(),b.precipitation(),where+" precipitation");
        floats(a.precipitationCv(),b.precipitationCv(),where+" cv");
        if(!java.util.Arrays.equals(a.coastal(),b.coastal()))throw new AssertionError(where+" coast");
        for(int i=0;i<a.elevation().length;i++){
            bits(a.shore().waterDistanceBlocks(i),b.shore().waterDistanceBlocks(i),where+" distance "+i);
            bits(a.shore().openWaterFraction(i),b.shore().openWaterFraction(i),where+" open "+i);
            bits(a.shore().reliefSlope(i),b.shore().reliefSlope(i),where+" relief "+i);
        }
    }
    private static void floats(float[] a,float[] b,String w){
        if(a.length!=b.length)throw new AssertionError(w);
        for(int i=0;i<a.length;i++)bits(a[i],b[i],w+" "+i);
    }
    private static void bits(float a,float b,String w){
        if(Float.floatToRawIntBits(a)!=Float.floatToRawIntBits(b))throw new AssertionError(w+": "+a+" != "+b);
    }
    private static Field field(int i0,int j0,int h,int w){
        float[] e=new float[h*w],c=new float[4*h*w];
        for(int r=0;r<h;r++)for(int x=0;x<w;x++){
            int n=r*w+x,gi=i0+r,gj=j0+x;
            e[n]=(float)(Math.sin(gi*.029)*260+Math.cos(gj*.041)*220+(((gi*17)^(gj*31))&127)-80);
            if((gi+gj)%211==0)e[n]=-90;
            c[n]=12+((gi*7+gj*11)&63)*.19f;
            c[h*w+n]=4;
            c[2*h*w+n]=300+((gi*13-gj*5)&255)*3;
            c[3*h*w+n]=((gi^gj)&127)/127f;
        }
        return new Field(i0,j0,h,w,e,c);
    }
    private record Field(int i0,int j0,int h,int w,float[] elevation,float[] climate){
    }
}
