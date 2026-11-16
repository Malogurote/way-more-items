package waymoreitems.core;

import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiConsumer;

/** Réglages typés, classement des objets, heure du monde et recherche du champ de saisie actif. */
final class CoreLogicTests {

    private CoreLogicTests() {
    }

    static void run(BiConsumer<String, Boolean> check) {
        settings(check);
        notes(check);
        categories(check);
        worldClock(check);
        focusSearch(check);
    }

    private static void settings(BiConsumer<String, Boolean> check) {
        ClientSettings defaults = new ClientSettings();
        ClientSettings read = new ClientSettings();
        read.gridIconSize = 30;
        read.readFrom(SettingsFile.empty());
        check.accept("reglages types : fichier vide = valeurs par defaut", read.gridIconSize == defaults.gridIconSize
                && read.uiScale == ClientSettings.UI_SCALE_AUTO && read.catalogSort == ClientSettings.CatalogSort.MOD
                && read.timeFormat == ClientSettings.TimeFormat.LANGUAGE && read.vanillaBookEnabled && !read.jeiMode);

        ClientSettings changed = new ClientSettings();
        changed.gridIconSize = 24;
        changed.uiScale = 3;
        changed.hudScale = 1.5;
        changed.hudPosXRatio = 0.25;
        changed.jeiMode = true;
        changed.catalogSort = ClientSettings.CatalogSort.ID;
        changed.timeFormat = ClientSettings.TimeFormat.H12;
        changed.doneBehavior = ClientSettings.DoneBehavior.HIDE;
        changed.hudSide = ClientSettings.HudSide.RIGHT;
        changed.sortOrder = InventorySorter.SortOrder.NAME_ONLY;
        changed.nightAlert = true;
        changed.panelOpacity = 55;
        changed.customHue = 0.25F;
        Properties props = new Properties();
        changed.writeTo(props);
        ClientSettings back = new ClientSettings();
        back.readFrom(SettingsFile.of(props));
        check.accept("reglages types : aller-retour sans perte", back.gridIconSize == 24 && back.uiScale == 3
                && back.hudScale == 1.5 && back.hudPosXRatio == 0.25 && back.jeiMode
                && back.catalogSort == ClientSettings.CatalogSort.ID && back.timeFormat == ClientSettings.TimeFormat.H12
                && back.doneBehavior == ClientSettings.DoneBehavior.HIDE && back.hudSide == ClientSettings.HudSide.RIGHT
                && back.sortOrder == InventorySorter.SortOrder.NAME_ONLY && back.nightAlert && back.panelOpacity == 55
                && Math.abs(back.customHue - 0.25F) < 1e-4);
        check.accept("reglages types : navigateur ecrit en clair", "JEI".equals(props.getProperty("recipeViewer")));

        Properties bad = new Properties();
        bad.setProperty("gridIconSize", "500");
        bad.setProperty("uiScale", "-7");
        bad.setProperty("panelOpacity", "3");
        bad.setProperty("catalogSort", "INCONNU");
        bad.setProperty("hudScale", "abc");
        ClientSettings clamped = new ClientSettings();
        clamped.readFrom(SettingsFile.of(bad));
        check.accept("reglages types : bornes appliquees", clamped.gridIconSize == ClientSettings.GRID_ICON_MAX
                && clamped.uiScale == ClientSettings.UI_SCALE_AUTO && clamped.panelOpacity == ClientSettings.PANEL_OPACITY_MIN);
        check.accept("reglages types : valeur illisible = defaut de la cle",
                clamped.catalogSort == ClientSettings.CatalogSort.MOD && clamped.hudScale == 1.0);

        Properties hsv = new Properties();
        hsv.setProperty("customHsv", "0.5,x,0.5");
        ClientSettings color = new ClientSettings();
        color.customSat = 0.3F;
        color.readFrom(SettingsFile.of(hsv));
        check.accept("reglages types : couleur illisible, saturation courante gardee", color.customSat == 0.3F);
        hsv.setProperty("customHsv", "2,-1,0.5");
        color.readFrom(SettingsFile.of(hsv));
        check.accept("reglages types : couleur bornee de 0 a 1", color.customHue == 1.0F && color.customSat == 0.0F);
        color.resetCustomColor();
        check.accept("reglages types : couleur par defaut retablie", color.customHue == ClientSettings.DEFAULT_CUSTOM_HUE
                && color.customColor() == defaults.customColor());

        check.accept("alpha : moitie", ClientSettings.scaleAlpha(0xFF123456, 0.5) == 0x80123456);
        check.accept("alpha : facteur borne", ClientSettings.scaleAlpha(0x80123456, 2.0) == 0x80123456
                && ClientSettings.scaleAlpha(0xFF123456, -1.0) == 0x00123456);
    }

    private static void notes(BiConsumer<String, Boolean> check) {
        PinnedChecklist list = new PinnedChecklist();
        list.addNote("Fer");
        list.addNote("Deux\nlignes");
        list.toggleNote(1);
        String saved = ClientSettings.encodeNotes(list);
        check.accept("notes : codage une ligne par note", "0Fer\n1Deux lignes".equals(saved));
        PinnedChecklist back = new PinnedChecklist();
        ClientSettings.decodeNotes(saved + "\nx", back);
        check.accept("notes : relecture, ligne trop courte ignoree", back.notes().size() == 2
                && !back.notes().get(0).done() && back.notes().get(1).done()
                && "Deux lignes".equals(back.notes().get(1).text()));
        PinnedChecklist empty = new PinnedChecklist();
        ClientSettings.decodeNotes("", empty);
        check.accept("notes : texte vide, aucune note", empty.notes().isEmpty());
    }

    private static void categories(BiConsumer<String, Boolean> check) {
        check.accept("classement : bloc a entite = machine",
                ItemCategories.MACHINES.equals(ItemCategories.classify(true, true, false, "chest", Set.of())));
        check.accept("classement : bloc nomme comme une machine",
                ItemCategories.MACHINES.equals(ItemCategories.classify(true, false, false, "Blast_Furnace", Set.of())));
        check.accept("classement : bloc ordinaire",
                ItemCategories.BLOCKS.equals(ItemCategories.classify(true, false, false, "stone", Set.of())));
        check.accept("classement : etiquette d'outil",
                ItemCategories.TOOLS.equals(ItemCategories.classify(false, false, false, "thing", Set.of("minecraft:enchantable/mining"))));
        check.accept("classement : objet qui s'use",
                ItemCategories.TOOLS.equals(ItemCategories.classify(false, false, true, "thing", Set.of())));
        check.accept("classement : objet nomme comme une arme",
                ItemCategories.TOOLS.equals(ItemCategories.classify(false, false, false, "iron_sword", Set.of())));
        check.accept("classement : materiau par defaut",
                ItemCategories.MATERIALS.equals(ItemCategories.classify(false, false, false, "iron_ingot", Set.of("c:ingots"))));
        check.accept("classement : codes tries dans l'ordre d'affichage",
                ItemCategories.MACHINES.compareTo(ItemCategories.BLOCKS) < 0
                        && ItemCategories.BLOCKS.compareTo(ItemCategories.TOOLS) < 0
                        && ItemCategories.TOOLS.compareTo(ItemCategories.MATERIALS) < 0);
    }

    private static void worldClock(BiConsumer<String, Boolean> check) {
        check.accept("heure : tick 0 = 6 h 00, jour 1", WorldClock.hours(0) == 6 && WorldClock.minutes(0) == 0
                && WorldClock.dayNumber(0) == 1);
        check.accept("heure : tick 18000 = minuit", WorldClock.hours(18000) == 0 && WorldClock.minutes(18000) == 0);
        check.accept("heure : tick 6500 = 12 h 30", WorldClock.hours(6500) == 12 && WorldClock.minutes(6500) == 30);
        check.accept("heure : jour 2 au tick 24000", WorldClock.dayNumber(24000) == 2 && WorldClock.hours(24000) == 6);
        check.accept("heure decimale", WorldClock.hourOfDay(6500) == 12.5);
        check.accept("heure sur 12", WorldClock.twelveHour(0) == 12 && WorldClock.twelveHour(12) == 12
                && WorldClock.twelveHour(13) == 1 && WorldClock.twelveHour(11) == 11);
        check.accept("alerte de nuit : de 12000 a 13188 seulement", !WorldClock.nightWarning(11999)
                && WorldClock.nightWarning(12000) && WorldClock.nightWarning(13187) && !WorldClock.nightWarning(13188)
                && WorldClock.nightWarning(24000 + 12500) && WorldClock.nightWarning(-11500));
        check.accept("soleil : lever 5 h, zenith a midi, coucher 19 h", WorldClock.sunProgress(5.0) == 0.0
                && WorldClock.sunProgress(12.0) == 0.5 && WorldClock.sunProgress(19.0) == 1.0
                && Math.abs(WorldClock.arcHeight(0.5) - 1.0) < 1e-9 && Math.abs(WorldClock.arcHeight(0.0)) < 1e-9);
        check.accept("soleil : couche la nuit", !WorldClock.aboveHorizon(WorldClock.sunProgress(2.0))
                && !WorldClock.aboveHorizon(WorldClock.sunProgress(21.0)));
        check.accept("lune : de 19 h a 5 h, haute a minuit", WorldClock.moonProgress(19.0) == 0.0
                && WorldClock.moonProgress(0.0) == 0.5 && WorldClock.moonProgress(5.0) == 1.0
                && !WorldClock.aboveHorizon(WorldClock.moonProgress(12.0)));
    }

    /** Élément d'interface de test : capte la saisie ou non, enfants éventuels. */
    private record Node(String name, boolean captures, List<Node> kids) {
        Node(String name, boolean captures) {
            this(name, captures, null);
        }
    }

    private static void focusSearch(BiConsumer<String, Boolean> check) {
        Node field = new Node("champ", true);
        Node deep = new Node("n1", false, List.of(new Node("n2", false, List.of(new Node("profond", true)))));
        Node container = new Node("panneau", false, List.of(new Node("bouton", false), field));
        Node r1 = FocusSearch.find(List.of(new Node("a", false), container), Node::captures, Node::kids, 4);
        check.accept("garde de saisie : champ trouve dans un conteneur", r1 == field);
        check.accept("garde de saisie : aucun champ actif",
                FocusSearch.find(List.of(new Node("a", false), new Node("b", false, List.of())), Node::captures, Node::kids, 4) == null);
        check.accept("garde de saisie : profondeur bornee",
                FocusSearch.find(List.of(deep), Node::captures, Node::kids, 1) == null
                        && FocusSearch.find(List.of(deep), Node::captures, Node::kids, 2) != null);
        Node first = new Node("premier", true);
        check.accept("garde de saisie : premier dans l'ordre",
                FocusSearch.find(List.of(first, container), Node::captures, Node::kids, 4) == first);
        check.accept("garde de saisie : liste vide", FocusSearch.find(List.<Node>of(), Node::captures, Node::kids, 4) == null);
    }
}
