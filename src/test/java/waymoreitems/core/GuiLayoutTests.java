package waymoreitems.core;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Vérifications de la mise en page déclarative ({@link GuiLayout}) : les valeurs par défaut doivent reproduire au
 * pixel près l'ancien calcul codé en dur de {@code WmiOverlay.renderContainerOverlay}, recopié ici comme référence.
 */
final class GuiLayoutTests {
    private GuiLayoutTests() {
    }

    private enum Bar { VERTICAL, HORIZONTAL, HIDDEN }

    /** Ancien calcul (référence) : rend les rectangles par identifiant, {@code null} si absent. */
    private static Map<String, GuiLayout.Rect> legacy(int W, int H, GuiLayout.Rect inv, double ratio, Bar bar,
                                                      int sortW, int settingsW, int viewerW, int vBarH, int hBarW,
                                                      int notesH, int widgetW, int widgetH) {
        Map<String, GuiLayout.Rect> out = new java.util.LinkedHashMap<>();
        int margin = H >= 260 ? 10 : 6;
        int guiLeft = inv.x();
        int guiW = inv.w();
        int panelRight = W - margin;
        int maxAllowed = Math.max(110, panelRight - (guiLeft + guiW + 10));
        int minAllowed = Math.min(maxAllowed, 118);
        int desired = (int) Math.round(W * ratio);
        int rpW = Math.max(minAllowed, Math.min(maxAllowed, desired));
        int rpX = panelRight - rpW;
        out.put("rightPanel", new GuiLayout.Rect(rpX, margin, rpW, H - margin * 2));
        out.put("sortBar", new GuiLayout.Rect(guiLeft + guiW - sortW, Math.max(2, inv.y() - 15), sortW, 13));
        // Boutons carrés collés au bas du panneau de droite, à sa gauche : engrenage, bascule au-dessus
        int sx = rpX - 4 - 20;
        int sy = H - margin - 20;
        out.put("settings", new GuiLayout.Rect(sx, sy, 20, 20));
        out.put("viewerToggle", new GuiLayout.Rect(sx, sy - 4 - 20, 20, 20));
        int leftEdge = margin;
        int notesY = margin;
        if (bar == Bar.VERTICAL) {
            int h = Math.min(vBarH, H - margin * 2);
            out.put("toolbarVertical", new GuiLayout.Rect(margin, margin, 26, h));
            leftEdge = margin + 26 + 6;
        } else if (bar == Bar.HORIZONTAL) {
            int maxW = rpX - 6 - margin;
            if (maxW >= 60) {
                out.put("toolbarHorizontal", new GuiLayout.Rect(margin, margin, Math.min(maxW, hBarW), 22));
                notesY = margin + 22 + 6;
            }
        }
        int notesW = Math.min(136, Math.max(108, guiLeft - leftEdge - 6));
        out.put("notes", new GuiLayout.Rect(leftEdge, notesY, notesW, notesH));
        int areaLeft = leftEdge;
        int areaRight = guiLeft - 6;
        int w = Math.max(widgetW, 120);
        boolean hidden = false;
        if (areaRight - areaLeft < w) {
            w = areaRight - areaLeft;
            hidden = w < 96;
        }
        if (!hidden) {
            int x = areaLeft + (areaRight - areaLeft - w) / 2;
            int y = Math.max(notesY + notesH + 6, (H - widgetH) / 2);
            out.put("widget", new GuiLayout.Rect(x, y, w, widgetH));
        }
        return out;
    }

    private static Map<String, GuiLayout.Rect> declarative(GuiLayout layout, int W, int H, GuiLayout.Rect inv, double ratio,
                                                           Bar bar, int sortW, int settingsW, int viewerW, int vBarH,
                                                           int hBarW, int notesH, int widgetW, int widgetH) {
        GuiLayout.Resolver r = layout.resolver(W, H, inv).constant("rightPanelRatio", ratio);
        r.place("rightPanel", 0, 0);
        r.place("sortBar", sortW, 13);
        r.place("settings", settingsW, 16);
        r.place("viewerToggle", viewerW, 16);
        if (bar == Bar.VERTICAL) {
            r.place("toolbarVertical", 26, vBarH);
        } else if (bar == Bar.HORIZONTAL) {
            r.place("toolbarHorizontal", hBarW, 22);
        }
        GuiLayout.Rect n = r.place("notes", 0, 0);
        r.set("notes", new GuiLayout.Rect(n.x(), n.y(), n.w(), notesH));
        r.place("widget", widgetW, widgetH);
        return r.rects();
    }

    static void run(BiConsumer<String, Boolean> check) {
        GuiLayout layout = GuiLayout.defaults();
        check.accept("mise en page : valeurs par défaut sans renvoi invalide", layout.validate().isEmpty());

        List<int[]> invSizes = List.of(new int[] {176, 166}, new int[] {195, 136}, new int[] {176, 222}, new int[] {176, 168});
        double[] ratios = {0.16, 0.275, 0.4, 0.65};
        for (Bar bar : Bar.values()) {
            boolean all = true;
            String firstDiff = "";
            for (int W = 320; W <= 1300 && all; W += 37) {
                for (int H = 240; H <= 760 && all; H += 29) {
                    for (int[] inv : invSizes) {
                        GuiLayout.Rect invRect = new GuiLayout.Rect((W - inv[0]) / 2, (H - inv[1]) / 2, inv[0], inv[1]);
                        for (double ratio : ratios) {
                            for (int notesH : new int[] {22, 70}) {
                                for (int widgetW : new int[] {100, 150}) {
                                    Object[] a = {W, H, invRect, ratio, bar, 126, 74, 80, 300, 420, notesH, widgetW, 81};
                                    Map<String, GuiLayout.Rect> exp = legacy(W, H, invRect, ratio, bar, 126, 74, 80, 300, 420, notesH, widgetW, 81);
                                    Map<String, GuiLayout.Rect> got = declarative(layout, W, H, invRect, ratio, bar, 126, 74, 80, 300, 420, notesH, widgetW, 81);
                                    if (!exp.equals(got)) {
                                        all = false;
                                        firstDiff = java.util.Arrays.toString(a) + " attendu " + exp + " obtenu " + got;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!all) {
                System.out.println("  écart : " + firstDiff);
            }
            check.accept("mise en page : calcul d'origine reproduit au pixel près (barre " + bar + ")", all);
        }

        // Petit écran : marge réduite à 6 sous 260 unités de haut
        GuiLayout.Resolver small = layout.resolver(400, 250, new GuiLayout.Rect(112, 42, 176, 166));
        check.accept("mise en page : marge conditionnelle (6 sous 260 de haut)", small.constantInt("margin") == 6);

        // Modification partielle : seules les Notes changent, le reste garde ses valeurs d'origine
        GuiLayout moved = GuiLayout.fromJson("{\"format\":\"wmi_gui_layout\",\"version\":1,\"elements\":{\"notes\":"
                + "{\"y\":{\"to\":\"screen.bottom\",\"offset\":\"-margin\",\"align\":\"end\"}}}}");
        GuiLayout.Rect inv = new GuiLayout.Rect(232, 97, 176, 166);
        GuiLayout.Resolver r1 = moved.resolver(640, 360, inv);
        r1.place("rightPanel", 0, 0);
        GuiLayout.Rect notes = r1.place("notes", 0, 40);
        GuiLayout.Resolver r0 = layout.resolver(640, 360, inv);
        GuiLayout.Rect rp0 = r0.place("rightPanel", 0, 0);
        check.accept("mise en page : surcharge partielle appliquée (Notes en bas)",
                notes != null && notes.bottom() == 350 && notes.x() == 10 && r1.get("rightPanel").equals(rp0));

        // Ancrage sur un élément absent sans repli : l'élément devient absent
        GuiLayout orphan = GuiLayout.fromJson("{\"elements\":{\"viewerToggle\":{\"x\":{\"to\":\"toolbarVertical.right\"}}}}");
        check.accept("mise en page : renvoi en avant signalé par la validation", !orphan.validate().isEmpty());

        // Aller-retour JSON : le document complet relu donne le même calcul
        String full = MiniJson.write(layout.toDocument());
        GuiLayout again = GuiLayout.fromJson(full);
        Map<String, GuiLayout.Rect> a = declarative(layout, 854, 480, new GuiLayout.Rect(339, 157, 176, 166), 0.275, Bar.VERTICAL, 82, 74, 80, 200, 300, 40, 130, 81);
        Map<String, GuiLayout.Rect> b = declarative(again, 854, 480, new GuiLayout.Rect(339, 157, 176, 166), 0.275, Bar.VERTICAL, 82, 74, 80, 200, 300, 40, 130, 81);
        check.accept("mise en page : aller-retour JSON sans perte", a.equals(b));

        // Sous-éléments : maquette, en règles relatives aux bords du panneau de droite
        boolean childrenOk = true;
        for (int pw = 104; pw <= 400 && childrenOk; pw += 13) {
            for (int ph = 120; ph <= 600 && childrenOk; ph += 37) {
                for (boolean admin : new boolean[] {true, false}) {
                    int px = 900 - pw;
                    int py = 10;
                    GuiLayout.Resolver r = layout.resolver(910, 620, new GuiLayout.Rect(300, 200, 176, 166));
                    r.set("rightPanel", new GuiLayout.Rect(px, py, pw, ph));
                    GuiLayout.Rect tabs = r.place("rightPanel.tabs", 0, 19);
                    GuiLayout.Rect mode = admin ? r.place("rightPanel.clickMode", 50, 8) : null;
                    GuiLayout.Rect header = r.place("rightPanel.header", 0, 13);
                    GuiLayout.Rect search = r.place("rightPanel.search", 0, 13);
                    GuiLayout.Rect sep = r.place("rightPanel.separator", 0, 1);
                    GuiLayout.Rect grid = r.place("rightPanel.grid", 0, 0);
                    GuiLayout.Rect footer = r.place("rightPanel.footer", 0, 12);
                    int headerY = py + 4 + 19 + 6;
                    int searchY = headerY + 13 + 3;
                    int sepY = searchY + 13 + 7;
                    int gridY = sepY + 1 + 5;
                    childrenOk = tabs.equals(new GuiLayout.Rect(px + 5, py + 4, pw - 10, 19))
                            && (!admin || mode.equals(new GuiLayout.Rect(px + pw - 5 - 50, py + ph - 5 - 8, 50, 8)))
                            && header.equals(new GuiLayout.Rect(px + 5, headerY, pw - 10, 13))
                            && search.equals(new GuiLayout.Rect(px + 5, searchY, pw - 10, 13))
                            && sep.equals(new GuiLayout.Rect(px + 6, sepY, pw - 12, 1))
                            && grid.equals(new GuiLayout.Rect(px + 6, gridY, pw - 10, py + ph - 17 - gridY))
                            && footer.equals(new GuiLayout.Rect(px + 5, py + ph - 15, pw - 10, 12))
                            && r.get("rightPanel.filter") == null
                            && (!admin || grid.bottom() <= mode.y());
                }
            }
        }
        check.accept("mise en page : sous-éléments du panneau de droite selon la maquette (grille au-dessus de la bascule)", childrenOk);

        // Maquette mesurée (1920 × 1080, échelle 4 : 480 × 270, inventaire en 152, 52) : coordonnées relevées
        GuiLayout.Resolver m = layout.resolver(480, 270, new GuiLayout.Rect(152, 52, 176, 166));
        m.set("rightPanel", new GuiLayout.Rect(338, 10, 132, 250));
        m.place("rightPanel.tabs", 0, 19);
        GuiLayout.Rect mMode = m.place("rightPanel.clickMode", 58, 8);
        GuiLayout.Rect mHeader = m.place("rightPanel.header", 0, 13);
        GuiLayout.Rect mSearch = m.place("rightPanel.search", 0, 13);
        GuiLayout.Rect mSep = m.place("rightPanel.separator", 0, 1);
        GuiLayout.Rect mGrid = m.place("rightPanel.grid", 0, 0);
        m.set("notes", new GuiLayout.Rect(42, 10, 108, 22));
        GuiLayout.Rect mIcon = m.place("notes.icon", 12, 12);
        GuiLayout.Rect mTitle = m.place("notes.title", 36, 9);
        GuiLayout.Rect mHud = m.place("notes.hud", 20, 18);
        check.accept("mise en page : pièces de la maquette à leur place mesurée",
                mMode.equals(new GuiLayout.Rect(407, 247, 58, 8))
                        && mHeader.x() + 1 == 344 && mHeader.y() + 2 == 41
                        && mSearch.equals(new GuiLayout.Rect(343, 55, 122, 13))
                        && mSep.equals(new GuiLayout.Rect(344, 75, 120, 1))
                        && mGrid.x() == 344 && mGrid.y() == 81 && mGrid.right() == 466 && mGrid.bottom() <= 245
                        && mIcon.equals(new GuiLayout.Rect(46, 16, 12, 12))
                        && mTitle.equals(new GuiLayout.Rect(59, 17, 36, 9))
                        && mHud.equals(new GuiLayout.Rect(152, 10, 20, 18)));

        boolean notesOk = true;
        for (int bw = 108; bw <= 136; bw++) {
            GuiLayout.Resolver r = layout.resolver(640, 360, inv);
            r.set("notes", new GuiLayout.Rect(42, 10, bw, 0));
            GuiLayout.Rect icon = r.place("notes.icon", 12, 12);
            GuiLayout.Rect title = r.place("notes.title", 40, 9);
            GuiLayout.Rect hud = r.place("notes.hud", 20, 18);
            notesOk &= icon.equals(new GuiLayout.Rect(46, 16, 12, 12))
                    && title.equals(new GuiLayout.Rect(59, 17, 40, 9))
                    && hud.equals(new GuiLayout.Rect(inv.x(), 10, 20, 18))
                    && r.get("notes.add") == null && r.get("notes.collapse") == null;
        }
        check.accept("mise en page : en-tête des Notes (icône, titre, HUD au bord de l'inventaire)", notesOk);

        // Décorations : lues, validées, placées sur un élément
        GuiLayout deco = GuiLayout.fromJson("{\"decorations\":{\"titre\":{\"type\":\"text\",\"text\":\"Atelier\",\"in\":\"rightPanel\","
                + "\"x\":{\"to\":\"rightPanel.left\",\"offset\":6},\"y\":{\"to\":\"rightPanel.bottom\",\"offset\":-30},"
                + "\"w\":{\"content\":true},\"h\":{\"content\":true}}}}");
        GuiLayout.Resolver rd = deco.resolver(640, 360, inv);
        GuiLayout.Rect rpd = rd.place("rightPanel", 0, 0);
        GuiLayout.Rect t = rd.place("titre", 40, 9);
        check.accept("mise en page : décoration lue, valide et ancrée sur son panneau",
                deco.validate().isEmpty() && deco.decorations().size() == 1 && t != null
                        && t.x() == rpd.x() + 6 && t.y() == rpd.bottom() - 30 && t.w() == 40
                        && MiniJson.write(deco.toDocument()).contains("\"decorations\""));
        GuiLayout badDeco = GuiLayout.fromJson("{\"decorations\":{\"z\":{\"type\":\"video\",\"in\":\"inconnu\"}}}");
        check.accept("mise en page : décoration invalide signalée", badDeco.validate().size() == 2);

        boolean rejected = false;
        try {
            GuiLayout.fromJson("{\"elements\": {\"notes\": }");
        } catch (IllegalArgumentException ex) {
            rejected = ex.getMessage().startsWith("json|1|");
        }
        check.accept("mise en page : JSON invalide refusé avec sa position", rejected);
    }
}
