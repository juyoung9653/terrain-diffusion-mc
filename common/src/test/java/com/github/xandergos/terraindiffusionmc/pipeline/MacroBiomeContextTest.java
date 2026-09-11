package com.github.xandergos.terraindiffusionmc.pipeline;

/** Pure numeric regression checks for halo, coast, and macro-region behaviour. */
public final class MacroBiomeContextTest {
    public static void main(String[] args) {
        checkCoastMask();
        checkOverlapInvariant();
        checkVanillaIdsUnchanged();
        checkFragmentReduction();
        System.out.println("MacroBiomeContextTest: PASS");
    }

    private static void checkCoastMask() {
        Field inland = field(-400, -400, 900, 900, false);
        BiomeClassifier.TerralithContext context = MacroBiomeContext.build(inland.elevation, inland.climate,
                inland.i0, inland.j0, inland.h, inland.w, 1, 16, -16, -16, 32, 32);
        for (boolean coastal : context.coastal()) require(!coastal, "inland terrain became coast");

        Field coast = field(-400, -400, 900, 900, false);
        coast.elevation[(0 - coast.i0) * coast.w + (0 - coast.j0)] = -20;
        BiomeClassifier.TerralithContext edge = MacroBiomeContext.build(coast.elevation, coast.climate,
                coast.i0, coast.j0, coast.h, coast.w, 1, 16, 0, 23, 1, 3);
        require(edge.coastal()[0] && edge.coastal()[1] && !edge.coastal()[2], "coast distance must be exactly 24 blocks");
        short land = TerralithBiomeClassifier.selectSurface(0, 0, (short) 1, 180, .1f, -20, .1f, 16, 700, 0, false);
        require(land != 0 && !TerralithBiomeClassifier.biomeName(land).contains("ocean"), "macro ocean made a local land column ocean");
        short water = TerralithBiomeClassifier.selectSurface(0, 0, (short) 1, -100, .1f, 300, .1f, 26, 700, 0, false);
        require(water != 0 && TerralithBiomeClassifier.biomeName(water).contains("ocean"), "local ocean became macro land");
    }

    private static void checkOverlapInvariant() {
        Field full = field(-500, -500, 1100, 1100, true);
        Field sub = field(-300, -300, 700, 700, true);
        BiomeClassifier.TerralithContext a = MacroBiomeContext.build(full.elevation, full.climate, full.i0, full.j0, full.h, full.w, 2, 8, -32, -32, 64, 64);
        BiomeClassifier.TerralithContext b = MacroBiomeContext.build(sub.elevation, sub.climate, sub.i0, sub.j0, sub.h, sub.w, 2, 8, -32, -32, 64, 64);
        require(equal(a, b), "same global area changed with a different halo tile");
        for (int n = 0; n < a.elevation().length; n++) {
            short x = TerralithBiomeClassifier.selectSurface(-32 + n % 64, -32 + n / 64, (short) 1,
                    a.elevation()[n], a.slope()[n], a.temperature()[n], a.precipitation()[n], a.precipitationCv()[n], a.coastal()[n]);
            short y = TerralithBiomeClassifier.selectSurface(-32 + n % 64, -32 + n / 64, (short) 1,
                    b.elevation()[n], b.slope()[n], b.temperature()[n], b.precipitation()[n], b.precipitationCv()[n], b.coastal()[n]);
            require(x == y, "classification changed across an overlapping tile");
        }
        BiomeClassifier.TerralithContext left = MacroBiomeContext.build(full.elevation, full.climate, full.i0, full.j0, full.h, full.w, 2, 8, -32, -32, 64, 32);
        BiomeClassifier.TerralithContext right = MacroBiomeContext.build(full.elevation, full.climate, full.i0, full.j0, full.h, full.w, 2, 8, -32, 0, 64, 32);
        for (int r = 0; r < 64; r++) for (int c = 0; c < 64; c++) {
            int whole = r * 64 + c, part = r * 32 + c % 32;
            BiomeClassifier.TerralithContext tile = c < 32 ? left : right;
            require(a.elevation()[whole] == tile.elevation()[part] && a.slope()[whole] == tile.slope()[part]
                    && a.temperature()[whole] == tile.temperature()[part] && a.precipitation()[whole] == tile.precipitation()[part]
                    && a.coastal()[whole] == tile.coastal()[part], "whole/subtile context mismatch");
            require(surface(a, whole, -32 + r, -32 + c) == surface(tile, part, -32 + r, -32 + c), "whole/subtile classification mismatch");
        }
        BiomeClassifier.TerralithContext one = MacroBiomeContext.build(full.elevation, full.climate, full.i0, full.j0, full.h, full.w, 1, 16, -32, -32, 64, 64);
        BiomeClassifier.TerralithContext oneLeft = MacroBiomeContext.build(full.elevation, full.climate, full.i0, full.j0, full.h, full.w, 1, 16, -32, -32, 64, 32);
        BiomeClassifier.TerralithContext oneRight = MacroBiomeContext.build(full.elevation, full.climate, full.i0, full.j0, full.h, full.w, 1, 16, -32, 0, 64, 32);
        for (int r = 0; r < 64; r++) for (int c = 0; c < 64; c++) {
            int whole = r * 64 + c, part = r * 32 + c % 32; BiomeClassifier.TerralithContext tile = c < 32 ? oneLeft : oneRight;
            require(one.elevation()[whole] == tile.elevation()[part] && one.coastal()[whole] == tile.coastal()[part]
                    && surface(one, whole, -32 + r, -32 + c) == surface(tile, part, -32 + r, -32 + c), "scale-one whole/subtile mismatch");
        }
    }

    private static void checkFragmentReduction() {
        int h = 128, w = 128; Field noisy = field(-256, -256, 700, 700, false);
        for (int r = 0; r < noisy.h; r++) for (int c = 0; c < noisy.w; c++) {
            int n = r * noisy.w + c; boolean hot = ((r ^ c) & 1) == 0;
            noisy.elevation[n] = hot ? 80 : 650;
            noisy.climate[n] = hot ? 28 : 1;
            noisy.climate[2 * noisy.h * noisy.w + n] = hot ? 250 : 900;
        }
        BiomeClassifier.TerralithContext macro = MacroBiomeContext.build(noisy.elevation, noisy.climate, noisy.i0, noisy.j0, noisy.h, noisy.w, 1, 16, 0, 0, h, w);
        short[] legacy = new short[h*w], coherent = new short[h*w];
        for (int r=0; r<h; r++) for (int c=0; c<w; c++) { int n=r*w+c, k=(r + 256)*noisy.w + c + 256;
            float e=noisy.elevation[k], dx=noisy.elevation[k+1]-noisy.elevation[k-1], dy=noisy.elevation[k+noisy.w]-noisy.elevation[k-noisy.w];
            float slope=(float)Math.sqrt(dx*dx+dy*dy)/32f;
            float t=BiomeClassifier.terralithTemperature(noisy.climate[k], r, c);
            float p=BiomeClassifier.terralithPrecipitation(noisy.climate[2*noisy.h*noisy.w+k], r, c);
            legacy[n]=TerralithBiomeClassifier.selectSurface(c,r,(short)1,e,slope,t,p,0,false);
            coherent[n]=TerralithBiomeClassifier.selectSurface(c,r,(short)1,macro.elevation()[n],macro.slope()[n],macro.temperature()[n],macro.precipitation()[n],macro.precipitationCv()[n],macro.coastal()[n]);
        }
        int oldComponents=components(legacy,h,w), newComponents=components(coherent,h,w);
        System.out.println("components: legacy=" + oldComponents + ", macro=" + newComponents);
        require(newComponents < oldComponents, "macro context did not reduce fragments: "+newComponents+" >= "+oldComponents);
    }

    private static void checkVanillaIdsUnchanged() {
        int h = 3, w = 3, n = h * w; float[] elevation = new float[n], padded = new float[(h + 2) * (w + 2)], climate = new float[4 * n];
        java.util.Arrays.fill(elevation, 300); java.util.Arrays.fill(padded, 300);
        for (int i = 0; i < n; i++) { climate[i] = 16; climate[2*n+i] = 700; }
        short[] baseline = BiomeClassifier.classify(elevation, climate, -2, -2, padded, h, w, 16);
        short[] alternatives = new short[n]; boolean[] coast = new boolean[n];
        BiomeClassifier.TerralithContext context = new BiomeClassifier.TerralithContext(elevation.clone(), new float[n], elevation.clone(), new float[n], new float[n], coast);
        short[] withContext = BiomeClassifier.classify(elevation, climate, -2, -2, padded, h, w, 16, alternatives, context);
        require(java.util.Arrays.equals(baseline, withContext), "macro context changed vanilla biome IDs");
    }

    private static short surface(BiomeClassifier.TerralithContext context, int n, int row, int column) {
        return TerralithBiomeClassifier.selectSurface(column, row, (short) 1, context.elevation()[n], context.slope()[n],
                context.elevation()[n], context.slope()[n], context.temperature()[n], context.precipitation()[n], context.precipitationCv()[n], context.coastal()[n]);
    }

    private static boolean equal(BiomeClassifier.TerralithContext a, BiomeClassifier.TerralithContext b) {
        return java.util.Arrays.equals(a.elevation(),b.elevation()) && java.util.Arrays.equals(a.slope(),b.slope())
                && java.util.Arrays.equals(a.temperature(),b.temperature()) && java.util.Arrays.equals(a.precipitation(),b.precipitation())
                && java.util.Arrays.equals(a.precipitationCv(),b.precipitationCv()) && java.util.Arrays.equals(a.coastal(),b.coastal());
    }
    private static int components(short[] values,int h,int w) { boolean[] seen=new boolean[values.length]; int count=0; int[] queue=new int[values.length]; for(int s=0;s<values.length;s++) if(!seen[s]) { count++; short id=values[s]; int head=0,tail=0;seen[s]=true;queue[tail++]=s;while(head<tail){int n=queue[head++],r=n/w,c=n%w;int[] next={n-w,n+w,n-1,n+1};for(int q:next)if(q>=0&&q<values.length&&((q/w==r)||q==n-w||q==n+w)&&!seen[q]&&values[q]==id){seen[q]=true;queue[tail++]=q;}}}return count; }
    private static Field field(int i0,int j0,int h,int w,boolean ocean) { float[] e=new float[h*w],c=new float[4*h*w]; for(int r=0;r<h;r++)for(int x=0;x<w;x++){int n=r*w+x;int gi=i0+r,gj=j0+x; e[n]=ocean&&Math.abs(gi)<100&&Math.abs(gj)<100?-20:180+((gi*31+gj*17)&31)*9; c[n]=12+((gi+gj)&7);c[2*h*w+n]=500+((gi*19-gj*7)&127)*5;}return new Field(i0,j0,h,w,e,c); }
    private record Field(int i0,int j0,int h,int w,float[] elevation,float[] climate) { }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
