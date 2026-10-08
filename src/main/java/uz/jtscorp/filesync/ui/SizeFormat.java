package uz.jtscorp.filesync.ui;

/** Sizes as the UI writes them: "812 B", "1,9 KB", "48 KB". */
final class SizeFormat {

    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

    private SizeFormat() {}

    static String format(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        int unit = 0;
        // Compared after rounding, so 1023.9 KB becomes "1,0 MB" rather than "1024 KB".
        while (Math.round(value) >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        // Tenths as a whole number: 9.96 rounds to 100 tenths and falls through to "10".
        long tenths = Math.round(value * 10);
        String number = tenths < 100 ? tenths / 10 + "," + tenths % 10 : String.valueOf(Math.round(value));
        return number + " " + UNITS[unit];
    }
}
