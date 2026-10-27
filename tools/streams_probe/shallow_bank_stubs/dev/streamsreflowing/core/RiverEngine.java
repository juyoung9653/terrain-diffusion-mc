package dev.streamsreflowing.core;
import dev.streamsreflowing.core.compose.ColumnPlan;
public abstract class RiverEngine { public abstract Object config(); public abstract boolean lakeNearChunk(int x,int z,int radius); public abstract ColumnPlan plan(int x,int z); }