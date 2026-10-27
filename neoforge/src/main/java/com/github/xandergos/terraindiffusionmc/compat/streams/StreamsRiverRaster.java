package com.github.xandergos.terraindiffusionmc.compat.streams;

import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.world.level.WorldGenLevel;

/** V4 voxel resolution and pool continuity, independent of native graph persistence. */
public final class StreamsRiverRaster {
    /* Metadata only: these ClassValues deliberately retain no engine, plan, lake,
       or world instance.  Streams invokes this path for every rastered column. */
    private static final ClassValue<MethodLookup> CONFIG = methods("config");
    private static final ClassValue<MethodLookup> LAKE_NEAR = methods("lakeNear", int.class, int.class, int.class);
    private static final ClassValue<MethodLookup> PLAN = methods("plan", int.class, int.class);
    private static final ClassValue<MethodLookup> LAKE_NEAR_CHUNK = methods("lakeNearChunk", int.class, int.class, int.class);
    private static final ClassValue<MethodLookup> LAKE_LEVEL = methods("level");

    private StreamsRiverRaster() { }

    private static ClassValue<MethodLookup> methods(String name, Class<?>... parameters) {
        return new ClassValue<>() {
            @Override protected MethodLookup computeValue(Class<?> type) {
                try { return new MethodLookup(type.getMethod(name, parameters)); }
                catch (NoSuchMethodException failure) { return new MethodLookup(failure); }
            }
        };
    }
    private static Method method(ClassValue<MethodLookup> methods, Class<?> type)
            throws NoSuchMethodException {
        return methods.get(type).get();
    }
    private record MethodLookup(Method value, NoSuchMethodException failure) {
        MethodLookup(Method value) { this(value, null); }
        MethodLookup(NoSuchMethodException failure) { this(null, failure); }
        Method get() throws NoSuchMethodException {
            if (failure != null) throw failure;
            return value;
        }
    }

    public static Object resolve(Object engine, Object column, int x, int z) {
        if (column == null || !flag(column,"inCorridor")) return column;
        try {
            Object config = method(CONFIG, engine.getClass()).invoke(engine);
            if (StreamsNaturalRiverAdapter.version(config) < 4) return column;
            double width = number(column,"width");
            // Half a voxel diagonal keeps a continuous centreline face-connected.
            // Sub-voxel source taper must not turn a spring into isolated puddles.
            if (!(width > 0)) return column;
            double resolvedWidth = Math.max(width,.71);
            boolean channel = number(column,"distance") <= resolvedWidth;
            int water = (int)number(column,"waterSurfaceY"), bankReference = water;
            if (!channel) {
                // A lake's backwater can stand above the incised stream plan.
                // Keep adjacent dry banks at that waterline instead of cutting
                // a new opening into the shore. Never raise channel water here.
                Object lake = method(LAKE_NEAR, engine.getClass()).invoke(engine,x,z,8);
                if (lake != null) {
                    int lakeLevel = ((Number)method(LAKE_LEVEL, lake.getClass()).invoke(lake)).intValue();
                    if (lakeLevel > water && lakeLevel-water <= Math.ceil(number(config,"ditchInset"))) bankReference=lakeLevel;
                }
            }
            if (resolvedWidth == width && bankReference == water) return column;
            return StreamsRecordAccess.copy(column, Map.of("width",resolvedWidth,
                    "inChannel",channel,"lateral",number(column,"lateral") * width / resolvedWidth,
                    "waterSurfaceY",bankReference,"bedY",(int)number(column,"bedY")+bankReference-water));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Streams v4 raster adapter",failure);
        }
    }

    public static int poolGrade(WorldGenLevel level, int x, int z, String reason, int current, int proposed) {
        if (proposed >= current || !reason.startsWith("junction pool (neighbour at r=")) return proposed;
        try {
            Object engine = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                    .getMethod("engine",WorldGenLevel.class).invoke(null,level);
            Object config = method(CONFIG, engine.getClass()).invoke(engine);
            if (StreamsNaturalRiverAdapter.version(config) < 4) return proposed;
            Method plan = method(PLAN, engine.getClass());
            Object centre = plan.invoke(engine,x,z);
            if (flag(centre,"source") || flag(centre,"terminal")
                    || (Boolean) method(LAKE_NEAR_CHUNK, engine.getClass())
                    .invoke(engine,x & ~15,z & ~15,8)) return proposed;
            int radiusStart = reason.indexOf("r=")+2;
            int radius = Integer.parseInt(reason.substring(radiusStart,reason.indexOf(' ',radiusStart)));
            int sideStart = reason.indexOf("side=")+5;
            int side = Integer.parseInt(reason.substring(sideStart,reason.indexOf(')',sideStart)));
            double fx = number(centre,"flowDirX"), fz = number(centre,"flowDirZ");
            double length = Math.hypot(fx,fz);
            if (length <= 1e-6) return proposed;
            int dx = (int)Math.round(-fz/length*radius*side), dz = (int)Math.round(fx/length*radius*side);
            boolean connected = connectedCrossing(dx,dz,(ox,oz) -> {
                try { return flag(plan.invoke(engine,x+ox,z+oz),"inChannel"); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot inspect pool crossing",failure); }
            });
            return connected ? proposed : current;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Streams v4 pool adapter",failure);
        }
    }
    public static boolean connectedCrossing(int dx,int dz,java.util.function.BiPredicate<Integer,Integer> channel) {
        int steps = Math.max(Math.abs(dx),Math.abs(dz))*2;
        int previousX=0,previousZ=0;
        for (int i=1;i<=steps;i++) {
            int x=(int)Math.round((double)dx*i/steps), z=(int)Math.round((double)dz*i/steps);
            if (!channel.test(x,z)) return false;
            if (x != previousX && z != previousZ
                    && !channel.test(previousX,z) && !channel.test(x,previousZ)) return false;
            previousX=x; previousZ=z;
        }
        return true;
    }
    private static boolean flag(Object p,String key) { return (Boolean)StreamsRecordAccess.read(p,key); }
    private static double number(Object p,String key) { return StreamsRecordAccess.number(p,key); }
}
