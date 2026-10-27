package com.github.xandergos.terraindiffusionmc.pipeline;

/** Exact cropped evaluator for {@link LaplacianUtils#laplacianDenoise(float[][], float[][], float)}. */
public final class ExactLaplacianCrop {
    @FunctionalInterface public interface PlaneReader { float[][] read(int row, int column, int height, int width); }
    private ExactLaplacianCrop() { }

    public static float[] decode(int fullH, int fullW, int lowH, int lowW, int cropR, int cropC, int cropH, int cropW, float sigma, PlaneReader residualReader, PlaneReader lowReader) {
        requireCrop(fullH, fullW, cropR, cropC, cropH, cropW);
        Rect output = new Rect(cropR, cropC, cropH, cropW);
        Rect targetLow = resizeSupport(output, lowH, lowW, fullH, fullW);
        int blurPad = kernelSize(sigma) / 2;
        Rect downLow = expand(targetLow, blurPad, lowH, lowW);
        Rect decodedRect = resizeSupport(downLow, fullH, fullW, lowH, lowW);
        Rect residualRect = union(decodedRect, output);
        float[][] residual = checked(residualReader.read(residualRect.r, residualRect.c, residualRect.h, residualRect.w), residualRect);
        // Whole low field is deliberate for the first exact version: extrapolation may depend on either edge.
        float[][] low = checked(lowReader.read(0, 0, lowH, lowW), new Rect(0, 0, lowH, lowW));
        float[][] padded = padded(low);
        int paddedH = lowH + 2, paddedW = lowW + 2;
        int resizeH = (int) Math.round(fullH + 2.0 * fullH / lowH);
        int resizeW = (int) Math.round(fullW + 2.0 * fullW / lowW);
        int padH = (int) Math.round((double) fullH / lowH), padW = (int) Math.round((double) fullW / lowW);
        Axis[] extrapRows = axes(decodedRect.r, decodedRect.h, paddedH, resizeH, padH);
        Axis[] extrapColumns = axes(decodedRect.c, decodedRect.w, paddedW, resizeW, padW);
        float[][] decoded = new float[decodedRect.h][decodedRect.w];
        for (int r = 0; r < decodedRect.h; r++) for (int c = 0; c < decodedRect.w; c++) decoded[r][c] = at(residual, residualRect, decodedRect.r + r, decodedRect.c + c) + sample(padded, extrapRows[r], extrapColumns[c]);
        Axis[] downRows = shifted(axes(downLow.r, downLow.h, fullH, lowH, 0), decodedRect.r);
        Axis[] downColumns = shifted(axes(downLow.c, downLow.w, fullW, lowW, 0), decodedRect.c);
        float[][] down = new float[downLow.h][downLow.w];
        for (int r = 0; r < downLow.h; r++) for (int c = 0; c < downLow.w; c++) down[r][c] = sample(decoded, downRows[r], downColumns[c]);
        float[] kernel = kernel(sigma);
        float[][] horizontal = new float[downLow.h][targetLow.w];
        for (int r = 0; r < downLow.h; r++) for (int c = 0; c < targetLow.w; c++) {
            float sum = 0;
            for (int k = 0; k < kernel.length; k++) sum += down[r][Math.max(0, Math.min(lowW - 1, targetLow.c + c + k - blurPad)) - downLow.c] * kernel[k];
            horizontal[r][c] = sum;
        }
        float[][] blurred = new float[targetLow.h][targetLow.w];
        for (int r = 0; r < targetLow.h; r++) for (int c = 0; c < targetLow.w; c++) {
            float sum = 0;
            for (int k = 0; k < kernel.length; k++) sum += horizontal[Math.max(0, Math.min(lowH - 1, targetLow.r + r + k - blurPad)) - downLow.r][c] * kernel[k];
            blurred[r][c] = sum;
        }
        Axis[] finalRows = shifted(axes(cropR, cropH, lowH, fullH, 0), targetLow.r);
        Axis[] finalColumns = shifted(axes(cropC, cropW, lowW, fullW, 0), targetLow.c);
        float[] result = new float[cropH * cropW];
        for (int r = 0; r < cropH; r++) for (int c = 0; c < cropW; c++) result[r * cropW + c] = at(residual, residualRect, cropR + r, cropC + c) + sample(blurred, finalRows[r], finalColumns[c]);
        return result;
    }
    private static float[][] padded(float[][] low) {
        int height = low.length;
        int width = low[0].length;
        float[][] padded = new float[height + 2][width + 2];
        for (int row = 0; row < height; row++) System.arraycopy(low[row], 0, padded[row + 1], 1, width);
        for (int column = 1; column <= width; column++) {
            padded[0][column] = height > 1 ? 2 * low[0][column - 1] - low[1][column - 1] : low[0][column - 1];
            padded[height + 1][column] = height > 1 ? 2 * low[height - 1][column - 1] - low[height - 2][column - 1] : low[height - 1][column - 1];
        }
        for (int row = 0; row < height + 2; row++) {
            padded[row][0] = width > 1 ? 2 * padded[row][1] - padded[row][2] : padded[row][1];
            padded[row][width + 1] = width > 1 ? 2 * padded[row][width] - padded[row][width - 1] : padded[row][width];
        }
        return padded;
    }

    private static Axis[] axes(int first, int count, int source, int destination, int offset) {
        Axis[] result = new Axis[count];
        for (int i = 0; i < count; i++) result[i] = axis(first + i + offset, source, destination);
        return result;
    }

    private static Axis axis(int destinationIndex, int source, int destination) {
        float coordinate = ((destinationIndex + .5f) * source / destination) - .5f;
        int first = (int) Math.floor(coordinate);
        int second = first + 1;
        return new Axis(Math.max(0, Math.min(source - 1, first)), Math.max(0, Math.min(source - 1, second)), coordinate - first);
    }

    private static Axis[] shifted(Axis[] axes, int origin) {
        Axis[] result = new Axis[axes.length];
        for (int i = 0; i < axes.length; i++) {
            Axis axis = axes[i];
            result[i] = new Axis(axis.a - origin, axis.b - origin, axis.weight);
        }
        return result;
    }

    private static float sample(float[][] values, Axis row, Axis column) {
        float rowWeight = row.weight;
        float columnWeight = column.weight;
        return (1 - rowWeight) * (1 - columnWeight) * values[row.a][column.a]
                + (1 - rowWeight) * columnWeight * values[row.a][column.b]
                + rowWeight * (1 - columnWeight) * values[row.b][column.a]
                + rowWeight * columnWeight * values[row.b][column.b];
    }

    private static Rect resizeSupport(Rect output, int sourceH, int sourceW, int destinationH, int destinationW) {
        int minimumRow = sourceH - 1, maximumRow = 0;
        int minimumColumn = sourceW - 1, maximumColumn = 0;
        for (int row = output.r; row < output.r + output.h; row++) {
            Axis axis = axis(row, sourceH, destinationH);
            minimumRow = Math.min(minimumRow, axis.a);
            maximumRow = Math.max(maximumRow, axis.b);
        }
        for (int column = output.c; column < output.c + output.w; column++) {
            Axis axis = axis(column, sourceW, destinationW);
            minimumColumn = Math.min(minimumColumn, axis.a);
            maximumColumn = Math.max(maximumColumn, axis.b);
        }
        return new Rect(minimumRow, minimumColumn, maximumRow - minimumRow + 1, maximumColumn - minimumColumn + 1);
    }

    private static int kernelSize(float sigma) { return ((int) (sigma * 2) / 2) * 2 + 1; }

    private static float[] kernel(float sigma) {
        int length = kernelSize(sigma), half = length / 2;
        float[] kernel = new float[length];
        float sum = 0;
        for (int i = 0; i < length; i++) {
            float x = i - half;
            kernel[i] = (float) Math.exp(-.5 * x * x / (sigma * sigma));
            sum += kernel[i];
        }
        for (int i = 0; i < length; i++) kernel[i] /= sum;
        return kernel;
    }

    private static Rect expand(Rect rect, int pad, int height, int width) {
        int firstRow = Math.max(0, rect.r - pad), firstColumn = Math.max(0, rect.c - pad);
        int lastRow = Math.min(height, rect.r + rect.h + pad), lastColumn = Math.min(width, rect.c + rect.w + pad);
        return new Rect(firstRow, firstColumn, lastRow - firstRow, lastColumn - firstColumn);
    }

    private static Rect union(Rect first, Rect second) {
        int row = Math.min(first.r, second.r), column = Math.min(first.c, second.c);
        int endRow = Math.max(first.r + first.h, second.r + second.h), endColumn = Math.max(first.c + first.w, second.c + second.w);
        return new Rect(row, column, endRow - row, endColumn - column);
    }

    private static float at(float[][] values, Rect rectangle, int row, int column) {
        return values[row - rectangle.r][column - rectangle.c];
    }

    private static float[][] checked(float[][] values, Rect rectangle) {
        if (values == null || values.length != rectangle.h) throw new IllegalArgumentException("reader height");
        for (float[] row : values) if (row == null || row.length != rectangle.w) throw new IllegalArgumentException("reader width");
        return values;
    }

    private static void requireCrop(int height, int width, int row, int column, int cropHeight, int cropWidth) {
        if (height < 1 || width < 1 || row < 0 || column < 0 || cropHeight < 1 || cropWidth < 1 || row + cropHeight > height || column + cropWidth > width) throw new IllegalArgumentException("crop");
    }

    private record Axis(int a,int b,float weight) { }
    private record Rect(int r,int c,int h,int w) { }
}