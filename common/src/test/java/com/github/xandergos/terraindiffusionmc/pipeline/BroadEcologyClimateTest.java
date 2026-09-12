package com.github.xandergos.terraindiffusionmc.pipeline;

/** Pure numeric checks; no models or pipeline inference are required. */
public final class BroadEcologyClimateTest {
    private static final int STRIDE = 256;
    public static void main(String[] args) {
        constantsStayConstant();
        affineAndTileInvariant();
        seamsHaveBoundedDerivative();
        haloIsRequired();
        System.out.println("BroadEcologyClimateTest: PASS");
    }
    private static void constantsStayConstant() {
        BroadEcologyClimate field = make(-700, -900, 800, 600, 1, false, true);
        for (int z = -700; z < 800; z += 113) for (int x = -900; x < 600; x += 127) {
            BroadEcologyClimate.Values v = field.sample(x, z);
            close(v.temperature(), 17); close(v.precipitation(), 800); close(v.precipitationCv(), 45); close(v.elevation(), 144);
        }
    }
    private static void affineAndTileInvariant() {
        for (int scale : new int[]{1, 2}) {
            BroadEcologyClimate whole = make(-1300, -900, 1400, 1100, scale, false, false);
            BroadEcologyClimate left = make(-1300, -900, 1400, 100, scale, false, false);
            BroadEcologyClimate right = make(-1300, 100, 1400, 1100, scale, false, false);
            for (int z = -1277; z < 1400; z += 191) for (int x = -877; x < 1100; x += 173) {
                BroadEcologyClimate.Values a = whole.sample(x,z), b = x < 100 ? left.sample(x,z) : right.sample(x,z);
                same(a.temperature(), b.temperature()); same(a.precipitation(), b.precipitation()); same(a.precipitationCv(), b.precipitationCv()); same(a.elevation(), b.elevation());
                double gy = BroadEcologyClimate.coarseCoordinate(z, scale, STRIDE), gx = BroadEcologyClimate.coarseCoordinate(x, scale, STRIDE);
                close(a.temperature(), (float)(10 + 2 * gy + 3 * gx));
                close(a.precipitation(), (float)(700 - gy + 4 * gx));
            }
        }
    }
    private static void seamsHaveBoundedDerivative() {
        BroadEcologyClimate field = make(-1024, -1024, 1024, 1024, 1, true, false);
        float max = 0, prior = field.sample(-1024, 0).temperature();
        for (int x = -1023; x < 1024; x++) { float next = field.sample(x, 0).temperature(); max = Math.max(max, Math.abs(next-prior)); prior=next; }
        require(max < .02f, "coarse seam derivative is too sharp: " + max);
    }
    private static void haloIsRequired() {
        boolean failed = false;
        try { new BroadEcologyClimate(new float[4],new float[4],new float[4],new float[4],0,0,2,2,0,0,1,1,1,STRIDE); }
        catch (IllegalArgumentException expected) { failed = true; }
        require(failed, "source halo must not clamp");
    }
    private static BroadEcologyClimate make(int i0,int j0,int i1,int j1,int scale,boolean step,boolean constant) {
        int radius=BroadEcologyClimate.smoothingRadius(scale,STRIDE); int ri0=BroadEcologyClimate.floorCoarse(i0,scale,STRIDE)-radius, rj0=BroadEcologyClimate.floorCoarse(j0,scale,STRIDE)-radius;
        int ri1=BroadEcologyClimate.floorCoarse(i1-1,scale,STRIDE)+radius+2, rj1=BroadEcologyClimate.floorCoarse(j1-1,scale,STRIDE)+radius+2;
        int h=ri1-ri0,w=rj1-rj0,n=h*w; float[] e=new float[n],t=new float[n],p=new float[n],cv=new float[n];
        for(int r=0;r<h;r++)for(int c=0;c<w;c++){int k=r*w+c, y=ri0+r,x=rj0+c; e[k]=constant?12:3; t[k]=constant?17:step?(x<0?0:1):10+2*y+3*x; p[k]=constant?800:700-y+4*x; cv[k]=constant?45:30+y-x;}
        return new BroadEcologyClimate(e,t,p,cv,ri0,rj0,h,w,i0,j0,i1,j1,scale,STRIDE);
    }
    private static void close(float actual,float expected){if(Math.abs(actual-expected)>2e-3f)throw new AssertionError(actual+" != "+expected);}
    private static void same(float actual,float expected){if(Float.floatToIntBits(actual)!=Float.floatToIntBits(expected))throw new AssertionError(actual+" != exact "+expected);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
