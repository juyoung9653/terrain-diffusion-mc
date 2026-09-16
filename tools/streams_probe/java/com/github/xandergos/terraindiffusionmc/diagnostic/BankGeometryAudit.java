package com.github.xandergos.terraindiffusionmc.diagnostic;

import java.lang.reflect.Array;

/** Exercises the actual private bank-corner policy in the Minecraft runtime. */
final class BankGeometryAudit {
    private BankGeometryAudit() { }
    static void run() throws ReflectiveOperationException {
        Class<?> banks = Class.forName("com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRiverBanks");
        Class<?> column = Class.forName(banks.getName()+"$BankPlan");
        var constructor = column.getDeclaredConstructor(boolean.class,boolean.class,int.class,double.class,double.class,boolean.class,boolean.class);
        constructor.setAccessible(true);
        Object grid = Array.newInstance(column,3,3);
        var corner = banks.getDeclaredMethod("connectingCorner",grid.getClass(),int.class,int.class);
        corner.setAccessible(true);
        for (int x=0;x<3;x++) for (int z=0;z<3;z++)
            Array.set(Array.get(grid,x),z,constructor.newInstance(false,true,187,1.0,1.3,false,false));
        // North is the disconnected highest arm. East already reaches south.
        Array.set(Array.get(grid,1),0,constructor.newInstance(true,true,189,1.0,0.0,false,false));
        Array.set(Array.get(grid,2),1,constructor.newInstance(true,true,188,1.0,0.0,false,false));
        Array.set(Array.get(grid,2),2,constructor.newInstance(true,true,187,1.0,0.0,false,false));
        Array.set(Array.get(grid,1),2,constructor.newInstance(true,true,187,1.0,0.0,false,false));
        require((Boolean)corner.invoke(null,grid,1,1),"lower arm hid disconnected highest arm");
        Array.set(Array.get(grid,2),1,constructor.newInstance(true,true,189,1.0,0.0,false,false));
        require((Boolean)corner.invoke(null,grid,1,1),"equal-highest arm hid disconnected arm");
        Array.set(Array.get(grid,2),0,constructor.newInstance(true,true,190,1.0,0.0,false,false));
        require((Boolean)corner.invoke(null,grid,1,1),"uphill detour accepted as flowing connection");
        Array.set(Array.get(grid,2),0,constructor.newInstance(true,true,189,1.0,0.0,false,false));
        require(!(Boolean)corner.invoke(null,grid,1,1),"existing downhill bend was unnecessarily excavated");
        for (int x=0;x<3;x++) for (int z=0;z<3;z++)
            Array.set(Array.get(grid,x),z,constructor.newInstance(false,true,188,0.7,1.05,false,false));
        Array.set(Array.get(grid,1),0,constructor.newInstance(true,true,188,0.7,0.0,false,false));
        require(!(Boolean)corner.invoke(null,grid,1,1),"single same-grade arm mistaken for a gap");
        Array.set(Array.get(grid,1),2,constructor.newInstance(true,true,188,0.7,0.0,false,false));
        require((Boolean)corner.invoke(null,grid,1,1),"same-grade narrow channel gap omitted");
        require(!com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRiverRaster.connectedCrossing(2,2,
                (x,z) -> x.equals(z)),"diagonal-only pool crossed dry bridges");
        require(com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRiverRaster.connectedCrossing(2,2,
                (x,z) -> x.equals(z) || x==z+1),"face-connected diagonal pool rejected");
        require(!com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRiverRaster.connectedCrossing(0,4,
                (x,z) -> z!=2),"pool crossed dry meander neck");
        System.out.println("TD BankGeometryAudit: PASS six corner cases including same-grade gap");
    }
    private static void require(boolean valid,String message) {
        if (!valid) throw new AssertionError(message);
    }
}
