package waymoreitems.client;

import net.minecraft.network.chat.Component;
import waymoreitems.core.CoverageReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Commande {@code /wmi coverage} : compare les recettes connues du jeu à celles que l'index a lues, mod par mod et
 * type par type, écrit le rapport complet dans {@code config/wmi-coverage.txt} et en résume le début dans le tchat.
 */
final class WmiCoverage {
    static final String FILE_NAME = "wmi-coverage.txt";
    /** Nombre de mods listés dans le tchat (ceux qui ont le plus de recettes non lues). */
    private static final int CHAT_MODS = 6;

    private WmiCoverage() {
    }

    /** Lance la mesure ; renvoie les lignes à afficher dans le tchat. */
    static List<Component> run() {
        List<CoverageReport.Sample> samples = WmiClientIndex.coverageSamples();
        List<Component> out = new ArrayList<>();
        if (samples == null || samples.isEmpty()) {
            out.add(WmiText.c("wmi.cmd.prefix", WmiText.s("wmi.coverage.none")));
            return out;
        }
        CoverageReport report = CoverageReport.of(samples);
        out.add(WmiText.c("wmi.cmd.prefix", WmiText.s("wmi.coverage.summary", report.read(), report.received(),
                pct(report.percent()), report.mods().size())));
        int shown = 0;
        for (CoverageReport.ModCoverage m : report.mods()) {
            if (m.unread() == 0 || shown >= CHAT_MODS) {
                break;
            }
            out.add(WmiText.c("wmi.coverage.chat_mod", m.mod(), m.read(), m.received(), pct(m.percent())));
            shown++;
        }
        if (shown == 0) {
            out.add(WmiText.c("wmi.coverage.all_read"));
        }
        try {
            Path file = WmiPlatform.configFile(FILE_NAME);
            Files.writeString(file, fileText(report), StandardCharsets.UTF_8);
            out.add(WmiText.c("wmi.coverage.saved", "config/" + FILE_NAME));
        } catch (IOException | RuntimeException e) {
            out.add(WmiText.c("wmi.coverage.failed", String.valueOf(e.getMessage())));
        }
        return out;
    }

    private static String pct(double p) {
        return CoverageReport.formatPercent(p) + "%";
    }

    private static String fileText(CoverageReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append(WmiText.s("wmi.coverage.file.title")).append('\n');
        sb.append(WmiText.s("wmi.coverage.file.total", report.read(), report.received(), pct(report.percent()),
                report.unread())).append("\n\n");
        for (CoverageReport.ModCoverage m : report.mods()) {
            sb.append(WmiText.s("wmi.coverage.file.mod", m.mod(), m.read(), m.received(), pct(m.percent()),
                    m.unread())).append('\n');
            for (CoverageReport.TypeCoverage t : m.types()) {
                sb.append(WmiText.s("wmi.coverage.file.type", t.typeId(), t.read(), t.received(), t.unread()))
                        .append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
