package com.github.xandergos.terraindiffusionmc.world.river;

/**
 * Admission for the small native bank-gap repair.  It deliberately requires an
 * existing bank to attach to: a repair must never become a pier or a dam across
 * a channel.
 */
public final class NaturalRiverBankFillPolicy {
    public static final int FIRST_SAFE_PROFILE_VERSION = 13;

    private NaturalRiverBankFillPolicy() { }

    /** {@code crestSupportNeighbors} counts dry soil columns already present at
     * the proposed cap and one block below it; lower riverbed soil does not count. */
    public static boolean allows(int profileVersion, int adjacentChannels,
                                 int crestSupportNeighbors, int gapDepth) {
        if (profileVersion < FIRST_SAFE_PROFILE_VERSION) return true;
        return adjacentChannels == 1 && crestSupportNeighbors >= 1 && gapDepth <= 2;
    }

    /**
     * Admission for Streams' private bank-material fill.  Its optional lip is a
     * real extra write, so budget it together with the vertical gap rather than
     * allowing a two-block gap and a third block above it.
     */
    public static boolean allowsNative(int profileVersion, int fillDepth,
                                       boolean mayAddLip, int crestSupportNeighbors) {
        if (profileVersion < FIRST_SAFE_PROFILE_VERSION) return true;
        int filledSpan = fillDepth + (mayAddLip ? 1 : 0);
        return filledSpan >= 1 && filledSpan <= 2 && crestSupportNeighbors >= 1;
    }

    /** Keep the containing crest, but do not raise it again when it already contains the water. */
    public static boolean allowsNativeLip(int profileVersion, boolean inChannel,
                                         int lipY, int waterY) {
        return profileVersion < FIRST_SAFE_PROFILE_VERSION || inChannel || (long) lipY <= (long) waterY + 1;
    }
}
