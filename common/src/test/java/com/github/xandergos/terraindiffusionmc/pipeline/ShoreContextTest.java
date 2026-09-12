package com.github.xandergos.terraindiffusionmc.pipeline;

/** Numeric contract for finite, globally addressed shore support. */
public final class ShoreContextTest {
    public static void main(String[] args) {
        int h=500,w=500,n=h*w; float[] e=new float[n], c=new float[4*n]; java.util.Arrays.fill(e,100);
        // A straight sea at x<0: its 96-block outer ring is half open.
        for(int r=0;r<h;r++)for(int x=0;x<w;x++)if(x-250<0)e[r*w+x]=-20;
        var a=MacroBiomeContext.build(e,c,-250,-250,h,w,1,16,0,10,1,1).shore();
        require(a.waterDistanceBlocks(0)<=24 && a.openWaterFraction(0)>.35f && a.openWaterFraction(0)<.65f,"straight shore");
        java.util.Arrays.fill(e,100); for(int r=240;r<=260;r++)for(int x=240;x<=260;x++)e[r*w+x]=-20;
        var b=MacroBiomeContext.build(e,c,-250,-250,h,w,1,16,0,23,1,1).shore();
        require(b.waterDistanceBlocks(0)<=24 && b.openWaterFraction(0)==0,"small lake support");
        java.util.Arrays.fill(e,100);
        var inland=MacroBiomeContext.build(e,c,-250,-250,h,w,2,8,0,0,1,1).shore();
        require(inland.waterDistanceBlocks(0)>24 && inland.openWaterFraction(0)==0 && inland.reliefSlope(0)==0,"inland");
        for(int r=0;r<h;r++)for(int x=0;x<w;x++) e[r*w+x]=x<250?-20:(x-250)*4;
        var steep=MacroBiomeContext.build(e,c,-250,-250,h,w,1,16,0,1,1,1).shore();
        require(steep.reliefSlope(0)>0,"relief");
        for (int scale : new int[]{1, 2}) checkSplit(scale);
        float[] owned = {3};
        ShoreContext immutable = new ShoreContext(owned, new float[]{.5f}, new float[]{.1f});
        owned[0] = 90;
        require(immutable.waterDistanceBlocks(0) == 3, "defensive copy");
        System.out.println("ShoreContextTest: PASS (pond, exposure, relief, immutable, negative-coordinate split at scales 1/2)");
    }
    private static void checkSplit(int scale) {
        int n = 700, origin = -350;
        float[] e = new float[n * n], climate = new float[4 * n * n];
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
            float worldX = (origin + x + .5f) * scale - .5f;
            float worldZ = (origin + z + .5f) * scale - .5f;
            e[z * n + x] = (worldX + 32 + worldZ * .125f) * 2;
        }
        ShoreContext whole = MacroBiomeContext.build(e, climate, origin, origin, n, n,
                scale, 16f / scale, -64, -64, 64, 64).shore();
        for (int dz : new int[]{0, 32}) for (int dx : new int[]{0, 32}) {
            ShoreContext part = MacroBiomeContext.build(e, climate, origin, origin, n, n,
                    scale, 16f / scale, -64 + dz, -64 + dx, 32, 32).shore();
            for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                int a = (z + dz) * 64 + x + dx, b = z * 32 + x;
                require(whole.waterDistanceBlocks(a) == part.waterDistanceBlocks(b), "split distance");
                require(whole.openWaterFraction(a) == part.openWaterFraction(b), "split exposure");
                require(whole.reliefSlope(a) == part.reliefSlope(b), "split relief");
            }
        }
    }
    private static void require(boolean ok,String s){if(!ok)throw new AssertionError(s);}
}
