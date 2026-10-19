package uz.jtscorp.filesync.sync;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * What the size check cannot see: a file whose size stayed plausible while its content
 * died. Reads nothing itself -- the check hands it the first bytes of the copy that would be
 * propagated and of the copy it would overwrite, and it compares them with each other and
 * with the file's extension.
 */
public final class ContentSignals {

    /** How much of each copy is read, on the check's thread; enough for every signal below. */
    public static final int PREFIX_BYTES = 64 * 1024;
    /** The propagated copy at least this random: encrypted or compressed. */
    static final double ENCRYPTED_ENTROPY = 7.5;
    /** The other copy at most this random: ordinary text. */
    static final double PLAIN_ENTROPY = 6.0;
    /** How many of each copy's first bytes a finding keeps, for the detail panel. */
    private static final int HEAD_KEPT = 8;

    private static final byte[] PDF = {'%', 'P', 'D', 'F'};
    private static final byte[] ZIP = {'P', 'K', 3, 4};
    private static final byte[] JPG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final Map<String, byte[]> SIGNATURES = Map.of(
            "pdf", PDF, "docx", ZIP, "xlsx", ZIP, "pptx", ZIP, "zip", ZIP, "jpg", JPG, "jpeg", JPG, "png", PNG);

    public enum Signal { ENTROPY, MAGIC, ZEROS }

    /**
     * @param laptopBad     whether the main copy is the one the signal is about -- the one Apply would propagate
     * @param ext           the file's extension, lower case, without the dot
     * @param badHead       the first bytes of the copy the signal is about
     * @param goodHead      the first bytes of the copy it would overwrite
     * @param size          the size of the copy the signal is about
     * @param badZeroShare  the share of zero bytes in that copy's prefix, 0 to 1
     */
    public record Finding(Signal signal, boolean laptopBad, String ext, double badEntropy, double goodEntropy,
                          double badZeroShare, double goodZeroShare, byte[] badHead, byte[] goodHead, long size) {}

    private ContentSignals() {}

    /**
     * The most specific signal on the propagated copy, or null when there is none. Every
     * signal compares the two copies, so both must exist.
     *
     * @param laptopIsSource whether the main copy is the one that would be propagated
     * @param source         the propagated copy's first bytes, at most {@link #PREFIX_BYTES}
     * @param other          the first bytes of the copy it would overwrite
     */
    public static Finding check(String path, boolean laptopIsSource, byte[] source, long sourceSize,
                                byte[] other, long otherSize) {
        String ext = extension(path);
        Signal signal = null;
        if (sourceSize > 0 && sourceSize == otherSize && source.length > 0 && allZero(source)
                && other.length > 0 && !allZero(other)) {
            // Wiped sectors: the size is the same, so the size check cannot see it.
            signal = Signal.ZEROS;
        } else if (SIGNATURES.containsKey(ext) && !startsWith(source, SIGNATURES.get(ext))
                && startsWith(other, SIGNATURES.get(ext))) {
            signal = Signal.MAGIC;
        } else if (looksLikeText(other) && entropy(other) <= PLAIN_ENTROPY
                && entropy(source) >= ENCRYPTED_ENTROPY) {
            // Only judged against text: a zip, a jpg or a pdf stream is random by nature.
            signal = Signal.ENTROPY;
        }
        if (signal == null) {
            return null;
        }
        return new Finding(signal, laptopIsSource, ext, entropy(source), entropy(other), zeroShare(source),
                zeroShare(other), Arrays.copyOf(source, Math.min(HEAD_KEPT, source.length)),
                Arrays.copyOf(other, Math.min(HEAD_KEPT, other.length)), sourceSize);
    }

    /** Shannon entropy in bits per byte: 0 for one repeated value, 8 for uniformly random bytes. */
    public static double entropy(byte[] bytes) {
        if (bytes.length == 0) {
            return 0;
        }
        int[] counts = new int[256];
        for (byte b : bytes) {
            counts[b & 0xff]++;
        }
        double bits = 0;
        for (int count : counts) {
            if (count > 0) {
                double p = (double) count / bytes.length;
                bits -= p * Math.log(p) / Math.log(2);
            }
        }
        return bits;
    }

    private static boolean looksLikeText(byte[] bytes) {
        if (bytes.length == 0) {
            return false;
        }
        for (byte b : bytes) {
            if (b == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean allZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static double zeroShare(byte[] bytes) {
        if (bytes.length == 0) {
            return 0;
        }
        int zeros = 0;
        for (byte b : bytes) {
            if (b == 0) {
                zeros++;
            }
        }
        return (double) zeros / bytes.length;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static String extension(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
