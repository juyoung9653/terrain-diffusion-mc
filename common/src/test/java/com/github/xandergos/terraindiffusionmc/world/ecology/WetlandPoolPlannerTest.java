package com.github.xandergos.terraindiffusionmc.world.ecology;

/** Planning failures must return before the adapter can write any blocks. */
public final class WetlandPoolPlannerTest {
    public static void main(String[] args) {
        fillsIrregularEnclosedBowlAboveExistingBed();
        rejectsFlatOpenPlaneAndDeepOrDiagonalLeaks();
        rejectsObstructionForeignBedAndIneligibleCell();
        rejectsFluidAndChunkEscape();
        isDeterministicAtNegativeChunkCoordinates();
        System.out.println("WetlandPoolPlannerTest: PASS");
    }
    private static void fillsIrregularEnclosedBowlAboveExistingBed() {
        Grid grid=bowl();
        var plan=WetlandPoolPlanner.plan(0,0,8,8,grid);
        check(plan.size()==6,"irregular bowl has its terrain-defined area");
        for(var cell:plan) check(cell.topY()==64 && cell.waterY()==65,
                "water height is exactly one above the retained bed");
        check(contains(plan,7,7)&&contains(plan,8,7)&&contains(plan,8,8)
                        &&contains(plan,8,9)&&contains(plan,9,9)&&contains(plan,7,8),
                "fill follows the irregular low ground, not a radius");
    }
    private static void rejectsFlatOpenPlaneAndDeepOrDiagonalLeaks() {
        check(WetlandPoolPlanner.plan(0,0,8,8,new Grid()).isEmpty(),"flat wet plane reaches chunk edge");
        Grid grid=bowl(); grid.topAt(7,7,63);
        check(WetlandPoolPlanner.plan(0,0,8,8,grid).isEmpty(),"deep connected cell rejected");
        grid=bowl(); grid.topAt(6,6,64);
        check(WetlandPoolPlanner.plan(0,0,8,8,grid).isEmpty(),"diagonal low-bank leak rejected");
        grid=bowl(); grid.topAt(7,6,66); grid.bedAt(7,65,6,false);
        check(WetlandPoolPlanner.plan(0,0,8,8,grid).isEmpty(),"raised hollow bank rejected at water level");
    }
    private static void rejectsObstructionForeignBedAndIneligibleCell() {
        Grid grid=bowl(); grid.clearAt(8,65,8,false);
        check(WetlandPoolPlanner.plan(0,0,8,8,grid).isEmpty(),"water clearance obstruction rejected");
        grid=bowl(); grid.bed[8][8]=false;
        check(WetlandPoolPlanner.plan(0,0,8,8,grid).isEmpty(),"foreign bed rejected");
        grid=bowl(); grid.natural[8][8]=false;
        check(WetlandPoolPlanner.plan(0,0,8,8,grid).isEmpty(),"foreign top rejected");
        grid=bowl(); grid.eligible[8][8]=false;
        check(WetlandPoolPlanner.plan(0,0,8,8,grid).isEmpty(),"ineligible cell rejected");
    }
    private static void rejectsFluidAndChunkEscape() {
        Grid grid=bowl(); grid.fluid[8][8]=true;
        check(WetlandPoolPlanner.plan(0,0,8,8,grid).isEmpty(),"preexisting fluid rejected");
        grid=new Grid(); grid.topAt(0,1,64); grid.topAt(1,1,64); grid.topAt(1,2,64); grid.topAt(0,2,64);
        check(WetlandPoolPlanner.plan(0,0,1,1,grid).isEmpty(),"basin touching chunk edge rejected");
    }
    private static void isDeterministicAtNegativeChunkCoordinates() {
        Grid grid=bowl(); var first=WetlandPoolPlanner.plan(-16,-32,-8,-24,grid);
        var second=WetlandPoolPlanner.plan(-16,-32,-8,-24,grid);
        check(first.equals(second)&&first.size()==6,"negative chunk plan is deterministic");
    }
    private static Grid bowl() {
        Grid grid=new Grid();
        for(int[] point:new int[][]{{7,7},{8,7},{8,8},{8,9},{9,9},{7,8}}) grid.topAt(point[0],point[1],64);
        return grid;
    }
    private static boolean contains(java.util.List<WetlandPoolPlanner.Cell> cells,int x,int z) {
        return cells.stream().anyMatch(cell -> cell.x()==x&&cell.z()==z);
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static final class Grid implements WetlandPoolPlanner.Surface {
        final int[][] top=new int[16][16]; final boolean[][] clear=new boolean[16][16];
        final boolean[][] bed=new boolean[16][16]; final boolean[][] natural=new boolean[16][16];
        final boolean[][] eligible=new boolean[16][16]; final boolean[][] fluid=new boolean[16][16];
        final boolean[][][] naturalByY=new boolean[16][80][16];
        final boolean[][][] bedByY=new boolean[16][80][16];
        final boolean[][][] clearByY=new boolean[16][80][16];
        Grid() { for(int x=0;x<16;x++)for(int z=0;z<16;z++){top[x][z]=65;clear[x][z]=true;bed[x][z]=true;natural[x][z]=true;eligible[x][z]=true;for(int y=0;y<80;y++){naturalByY[x][y][z]=true;bedByY[x][y][z]=true;clearByY[x][y][z]=true;}} }
        @Override public int topY(int x,int z){return top[x&15][z&15];}
        @Override public boolean eligible(int x,int z){return eligible[x&15][z&15];}
        @Override public boolean naturalTop(int x,int y,int z){return natural[x&15][z&15]&&naturalByY[x&15][y][z&15];}
        @Override public boolean naturalBed(int x,int y,int z){return bed[x&15][z&15]&&bedByY[x&15][y][z&15];}
        @Override public boolean clear(int x,int y,int z){return clear[x&15][z&15]&&clearByY[x&15][y][z&15];}
        @Override public boolean fluid(int x,int y,int z){return fluid[x&15][z&15];}
        void topAt(int x,int z,int value){top[x&15][z&15]=value;}
        void bedAt(int x,int y,int z,boolean value){bedByY[x&15][y][z&15]=value;}
        void clearAt(int x,int y,int z,boolean value){clearByY[x&15][y][z&15]=value;}
    }
}
