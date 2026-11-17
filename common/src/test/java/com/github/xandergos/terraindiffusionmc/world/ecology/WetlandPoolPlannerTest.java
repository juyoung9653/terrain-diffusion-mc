package com.github.xandergos.terraindiffusionmc.world.ecology;

/** Planning failures must return before the adapter can write any blocks. */
public final class WetlandPoolPlannerTest {
    public static void main(String[] args) {
        acceptsOneBlockRelief();
        rejectsObstructionCliffBedForeignTopAndIneligibleCell();
        rejectsLeakingRaisedBankLowBankHoleAndFluid();
        rejectsChunkEscape();
        System.out.println("WetlandPoolPlannerTest: PASS");
    }
    private static void acceptsOneBlockRelief() {
        Grid grid=new Grid(); grid.top[8][8]=65;
        check(!WetlandPoolPlanner.plan(0,0,8,8,3,-1L,grid).isEmpty(),"one-block relief accepted");
    }
    private static void rejectsObstructionCliffBedForeignTopAndIneligibleCell() {
        Grid grid=new Grid(); grid.clear[8][8]=false;
        check(WetlandPoolPlanner.plan(0,0,8,8,3,-1L,grid).isEmpty(),"clearance obstruction rejected");
        grid=new Grid(); grid.top[8][8]=66;
        check(WetlandPoolPlanner.plan(0,0,8,8,3,-1L,grid).isEmpty(),"two-block cliff rejected");
        grid=new Grid(); grid.bed[8][8]=false;
        check(WetlandPoolPlanner.plan(0,0,8,8,3,-1L,grid).isEmpty(),"foreign bed rejected");
        grid=new Grid(); grid.natural[8][8]=false;
        check(WetlandPoolPlanner.plan(0,0,8,8,3,-1L,grid).isEmpty(),"foreign top rejected");
        grid=new Grid(); grid.eligible[8][8]=false;
        check(WetlandPoolPlanner.plan(0,0,8,8,3,-1L,grid).isEmpty(),"ineligible cell rejected");
    }
    private static void rejectsLeakingRaisedBankLowBankHoleAndFluid() {
        Grid grid=new Grid();
        grid.top[4][8]=65;
        grid.naturalAt(4,64,8,false);
        check(WetlandPoolPlanner.plan(0,0,8,8,3,-1L,grid).isEmpty(),"raised bank must close at water height");
        grid=new Grid(); grid.naturalAt(4,64,8,false);
        check(WetlandPoolPlanner.plan(0,0,8,8,3,-1L,grid).isEmpty(),"low-bank hole rejected");
        grid=new Grid(); grid.fluid[8][8]=true;
        check(WetlandPoolPlanner.plan(0,0,8,8,3,-1L,grid).isEmpty(),"preexisting fluid rejected");
    }
    private static void rejectsChunkEscape() {
        check(WetlandPoolPlanner.plan(0,0,3,3,3,-1L,new Grid()).isEmpty(),"ring outside chunk rejected");
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static final class Grid implements WetlandPoolPlanner.Surface {
        final int[][] top=new int[16][16]; final boolean[][] clear=new boolean[16][16];
        final boolean[][] bed=new boolean[16][16]; final boolean[][] natural=new boolean[16][16];
        final boolean[][] eligible=new boolean[16][16]; final boolean[][] fluid=new boolean[16][16];
        final boolean[][][] naturalByY=new boolean[16][80][16];
        Grid() { for(int x=0;x<16;x++)for(int z=0;z<16;z++){top[x][z]=64;clear[x][z]=true;bed[x][z]=true;natural[x][z]=true;eligible[x][z]=true;for(int y=0;y<80;y++)naturalByY[x][y][z]=true;} }
        @Override public int topY(int x,int z){return top[x][z];}
        @Override public boolean eligible(int x,int z){return eligible[x][z];}
        @Override public boolean naturalTop(int x,int y,int z){return natural[x][z]&&naturalByY[x][y][z];}
        @Override public boolean naturalBed(int x,int y,int z){return bed[x][z];}
        @Override public boolean clear(int x,int y,int z){return clear[x][z];}
        @Override public boolean fluid(int x,int y,int z){return fluid[x][z];}
        void naturalAt(int x,int y,int z,boolean value){naturalByY[x][y][z]=value;}
    }
}
