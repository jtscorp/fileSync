package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import uz.jtscorp.filesync.config.ProfileStats;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** The sentences that describe a profile outside a check: the sidebar line and the setup hint. */
final class ProfileText {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private ProfileText() {}

    /** The line under a profile's name in the sidebar. */
    static String sidebarLine(boolean configured, ProfileStats stats, ZonedDateTime now) {
        if (!configured) {
            return Texts.t("profile.unconfigured");
        }
        if (stats.lastSync() == null) {
            return Texts.t("profile.never");
        }
        ZonedDateTime when = stats.lastSync().atZone(now.getZone());
        long days = ChronoUnit.DAYS.between(when.toLocalDate(), LocalDate.from(now));
        if (days <= 0) {
            return Texts.t("profile.last.today", TIME.format(when));
        }
        if (days == 1) {
            return Texts.t("profile.last.yesterday", TIME.format(when));
        }
        return days < 7 ? Texts.n("profile.last.days", days) : Texts.t("profile.last.date", DATE.format(when));
    }

    /** "12 480 fayl · 18,2 GB", or empty before the first check has counted anything. */
    static String counts(ProfileStats stats) {
        if (!stats.hasCounts()) {
            return "";
        }
        String files = String.format("%,d", stats.fileCount()).replace(',', ' ');
        String form = "profile.counts" + (stats.fileCount() == 1 ? ".one" : "");
        // The count is shown grouped ("12 480"), so the sentence is filled with the text.
        return Texts.t(Texts.bundle().containsKey(form) ? form : "profile.counts", files,
                SizeFormat.format(stats.totalBytes()));
    }

    /** The line between the two folder fields on the setup screen. */
    static String syncHint(boolean configured, ProfileStats stats) {
        String base = Texts.t("profile.twoWay");
        if (!configured) {
            return base + " · " + Texts.t("profile.noFolders");
        }
        String counts = counts(stats);
        return counts.isEmpty() ? base : base + " · " + counts;
    }
}
