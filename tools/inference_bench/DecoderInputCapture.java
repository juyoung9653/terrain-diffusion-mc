package com.github.xandergos.terraindiffusionmc.pipeline;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Injected immediately before the production decoder call in a copied WorldPipeline. */
public final class DecoderInputCapture {
    private static final int FLOATS=5*256*256;
    private static final Path OUTPUT=Path.of(required("td.capture.output"));
    private static final int MAX_PER_REGION=Integer.parseInt(System.getProperty("td.capture.maxPerRegion", "4"));
    private static final long SEED=Long.parseLong(System.getProperty("td.capture.seed"));
    private static final List<Entry> ENTRIES=new ArrayList<>();
    private static final List<Region> REGIONS=new ArrayList<>();
    private static String region;
    private static int regionCount;

    private record Entry(String id,String x,String noise,int tileI,int tileJ,int pixelI,int pixelJ,float noiseLabel) { }
    private record Region(String id,int i,int j,String elevation,int captured) { }
    private DecoderInputCapture() { }

    public static void capture(int tileI,int tileJ,int pixelI,int pixelJ,float[] x,float noiseLabel) {
        if (region==null || regionCount>=MAX_PER_REGION) return;
        if (x.length!=FLOATS) throw new IllegalArgumentException("unexpected decoder input length: "+x.length);
        String stem=region+"-tile-"+tileI+"-"+tileJ+"-"+regionCount;
        try {
            Files.createDirectories(OUTPUT);
            writeFloats(OUTPUT.resolve(stem+".x.bin"), x);
            writeFloats(OUTPUT.resolve(stem+".noise_labels.bin"), new float[]{noiseLabel});
            ENTRIES.add(new Entry(stem,stem+".x.bin",stem+".noise_labels.bin",tileI,tileJ,pixelI,pixelJ,noiseLabel));
            regionCount++;
        } catch (IOException failure) { throw new IllegalStateException("failed to capture decoder input", failure); }
    }

    private static void beginRegion(String id) { region=id; regionCount=0; }

    public static void main(String[] args) throws Exception {
        if (args.length!=0) throw new IllegalArgumentException("arguments are not supported");
        if (Files.exists(OUTPUT.resolve("manifest.json")))
            throw new IllegalStateException("refusing to overwrite completed capture manifest");
        Files.createDirectories(OUTPUT);
        try (WorldPipeline pipeline=new WorldPipeline(SEED)) {
            runRegion(pipeline,"woodland",-1024,-240);
            runRegion(pipeline,"ocean",16384,16384);
            runRegion(pipeline,"third-region",-8192,4096);
        }
        if (ENTRIES.isEmpty()) throw new IllegalStateException("capture produced no decoder windows");
        writeManifest();
    }

    private static void runRegion(WorldPipeline pipeline,String id,int i,int j) {
        beginRegion(id);
        // Preserve the production crop's fixed 218-pixel Laplacian halo.
        float[] elevation=pipeline.getElevationCrop(i-218,j-218,i+256+218,j+256+218,i,j,i+256,j+256);
        if (regionCount==0) throw new IllegalStateException("no decoder input for "+id);
        String elevationFile=id+".elevation.bin";
        try { writeFloats(OUTPUT.resolve(elevationFile), elevation); }
        catch (IOException failure) { throw new IllegalStateException("failed to save elevation crop", failure); }
        REGIONS.add(new Region(id,i,j,elevationFile,regionCount));
    }

    private static void writeFloats(Path path,float[] values) throws IOException {
        try (DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            for (float value:values) out.writeInt(Integer.reverseBytes(Float.floatToRawIntBits(value)));
        }
    }

    private static void writeManifest() throws IOException {
        StringBuilder json=new StringBuilder("{\n  \"kind\": \"production_world_pipeline_decoder_input_capture\",");
        json.append("\n  \"seed\": ").append(SEED).append(',');
        json.append("\n  \"shape\": [1, 5, 256, 256],");
        json.append("\n  \"floatEncoding\": \"little-endian float32\",");
        json.append("\n  \"pipelineSourceSha256\": \"").append(required("td.capture.pipelineSourceSha256")).append("\",");
        json.append("\n  \"releaseJarSha256\": \"").append(required("td.capture.releaseJarSha256")).append("\",");
        json.append("\n  \"modelSha256\": \"").append(required("td.capture.decoderSha256")).append("\",");
        json.append("\n  \"decoderCandidateSha256\": \"").append(required("td.capture.decoderCandidateSha256")).append("\",");
        json.append("\n  \"queryContract\": \"getElevationCrop(i-218,j-218,i+256+218,j+256+218,i,j,i+256,j+256)\",");
        json.append("\n  \"regions\": [");
        for (int index=0;index<REGIONS.size();index++) {
            Region entry=REGIONS.get(index); if (index>0) json.append(',');
            json.append("\n    {\"id\": \"").append(entry.id).append("\", \"i\": ").append(entry.i)
                    .append(", \"j\": ").append(entry.j).append(", \"queryBounds\": [")
                    .append(entry.i-218).append(',').append(entry.j-218).append(',').append(entry.i+474).append(',').append(entry.j+474)
                    .append("], \"cropBounds\": [").append(entry.i).append(',').append(entry.j).append(',').append(entry.i+256).append(',').append(entry.j+256)
                    .append("], \"elevation\": \"").append(entry.elevation).append("\", \"capturedWindows\": ").append(entry.captured).append('}');
        }
        json.append("\n  ],");
        json.append("\n  \"cases\": [");
        for (int index=0;index<ENTRIES.size();index++) {
            Entry entry=ENTRIES.get(index); if (index>0) json.append(',');
            json.append("\n    {\"id\": \"").append(entry.id).append("\", \"x\": \"").append(entry.x)
                    .append("\", \"noiseLabels\": \"").append(entry.noise).append("\", \"region\": \"")
                    .append(entry.id.split("-tile-")[0]).append("\", \"tile\": [").append(entry.tileI).append(',').append(entry.tileJ)
                    .append("], \"pixel\": [").append(entry.pixelI).append(',').append(entry.pixelJ)
                    .append("], \"noiseLabel\": ").append(entry.noiseLabel).append('}');
        }
        json.append("\n  ]\n}\n");
        Files.writeString(OUTPUT.resolve("manifest.json"), json.toString());
    }

    private static String required(String property) {
        String value=System.getProperty(property); if (value==null || value.isBlank()) throw new IllegalStateException("missing -D"+property);
        return value;
    }
}
