package waymoreitems.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mise en page déclarative de la surcouche de WMI (format {@code wmi_gui_layout}, version 1), en Java pur.
 * <p>Chaque élément décrit sa position sur chaque axe par un ancrage (bord de l'écran, de l'inventaire ou d'un autre
 * élément, avec décalage) et sa taille (fixe, proportion, contenu ou remplissage jusqu'à un bord), bornée par un
 * minimum, un maximum et des limites. Les valeurs par défaut ({@link #DEFAULT_JSON}) reproduisent exactement le
 * calcul d'origine de {@code WmiOverlay} ; un fichier {@code wmi-layout.json} ne contient que ce qui change.
 *
 * <h2>Grammaire</h2>
 * <ul>
 * <li>Bord : {@code {"to": "<réf>", "offset": n}} ; réf = {@code screen|inventory|<élément>} suivi de
 * {@code .left|.right|.top|.bottom|.width|.height}. Décalage : nombre, ou nom de constante précédé ou non de
 * {@code -}.</li>
 * <li>Axe ({@code x}, {@code y}) : {@code to}+{@code offset} et {@code align} ({@code start}, {@code center},
 * {@code end}), ou {@code between: [bord, bord]}, {@code align} et un {@code offset} facultatif appliqué après ; {@code atLeast: bord} (position minimale) ;
 * {@code else: axe} si un élément cité est absent.</li>
 * <li>Taille ({@code w}, {@code h}) : {@code fixed}, {@code content: true}, {@code ratio}+{@code of}, ou
 * {@code fill: bord} (de la position de départ jusqu'au bord) ; bornes {@code min}, {@code max},
 * {@code limit: [bords]} (le bord opposé ne les dépasse pas), {@code limitFloor} (plancher de cette limite),
 * {@code fitSpan} (pas plus large que l'intervalle {@code between}) ; {@code hideBelow} et
 * {@code hideIfMaxBelow} rendent l'élément absent.</li>
 * <li>{@code visibleMin: [w, h]} : en deçà, l'élément est calculé (et peut servir d'ancrage) mais pas dessiné.</li>
 * <li>Sous-éléments : identifiants pointés ({@code rightPanel.search}), placés par le code pendant le dessin de leur
 * panneau, ancrés d'ordinaire sur ses bords ({@code rightPanel.left}).</li>
 * <li>Décorations ({@code decorations}) : éléments ajoutés sans code, calculés et dessinés après tous les autres,
 * dans l'ordre du fichier ; {@code type} = {@code text} ({@code text}, {@code size} normal, big ou huge,
 * {@code textAlign}), {@code separator}, {@code frame} ({@code style} panel, card ou box) ou {@code item}
 * ({@code item} = identifiant d'objet) ; {@code color} = clé de palette ({@code textMain}, {@code accent}…) ou
 * {@code #AARRGGBB} ; {@code in} = élément dont la décoration suit l'affichage (absente s'il n'est pas dessiné).</li>
 * </ul>
 */
public final class GuiLayout {
    public static final String FORMAT = "wmi_gui_layout";
    public static final int VERSION = 1;

    /** Ordre de calcul de la surcouche d'inventaire (un élément ne peut citer que ceux qui le précèdent). */
    public static final List<String> OVERLAY_ORDER = List.of(
            "rightPanel", "sortBar", "settings", "viewerToggle", "toolbarVertical", "toolbarHorizontal",
            "notes", "notes.icon", "notes.title", "notes.hud", "widget",
            "rightPanel.tabs", "rightPanel.clickMode", "rightPanel.header", "rightPanel.search",
            "rightPanel.separator", "rightPanel.grid", "rightPanel.footer");

    /** Types de décorations dessinées depuis le fichier, sans code : texte, séparateur, cadre, icône d'objet. */
    public static final Set<String> DECORATION_TYPES = Set.of("text", "separator", "frame", "item");

    private static final Set<String> EDGE_NAMES = Set.of("left", "right", "top", "bottom", "width", "height");

    /** Mise en page par défaut de la surcouche d'inventaire (calcul d'origine, puis maquette pour les sous-éléments du panneau de droite et des Notes). */
    public static final String DEFAULT_JSON = """
            {
              "format": "wmi_gui_layout",
              "version": 1,
              "screen": "overlay",
              "constants": {
                "margin": {"value": 10, "ifHeightBelow": 260, "then": 6},
                "gap": 6,
                "rightPanelRatio": 0.275
              },
              "elements": {
                "rightPanel": {
                  "label": "Panneau de droite (onglets, recherche, grille)",
                  "x": {"to": "screen.right", "offset": "-margin", "align": "end"},
                  "y": {"to": "screen.top", "offset": "margin"},
                  "w": {"ratio": "rightPanelRatio", "of": "screen.width", "min": 118,
                        "limit": [{"to": "inventory.right", "offset": 10}], "limitFloor": 110},
                  "h": {"fill": {"to": "screen.bottom", "offset": "-margin"}},
                  "visibleMin": [104, 120]
                },
                "sortBar": {
                  "label": "Boutons de tri au-dessus de l'inventaire",
                  "x": {"to": "inventory.right", "align": "end"},
                  "y": {"to": "inventory.top", "offset": -15, "atLeast": {"to": "screen.top", "offset": 2}},
                  "w": {"content": true},
                  "h": {"fixed": 13}
                },
                "settings": {
                  "label": "Bouton « Paramètres » (engrenage), collé au bas du panneau de droite, à sa gauche",
                  "x": {"to": "rightPanel.left", "offset": -4, "align": "end"},
                  "y": {"to": "rightPanel.bottom", "align": "end"},
                  "w": {"fixed": 20},
                  "h": {"fixed": 20}
                },
                "viewerToggle": {
                  "label": "Bascule WMI / JEI (si JEI est installé), au-dessus de « Paramètres »",
                  "x": {"to": "rightPanel.left", "offset": -4, "align": "end"},
                  "y": {"to": "settings.top", "offset": -4, "align": "end"},
                  "w": {"fixed": 20},
                  "h": {"fixed": 20}
                },
                "toolbarVertical": {
                  "label": "Barre d'outils verticale",
                  "x": {"to": "screen.left", "offset": "margin"},
                  "y": {"to": "screen.top", "offset": "margin"},
                  "w": {"content": true},
                  "h": {"content": true, "limit": [{"to": "screen.bottom", "offset": "-margin"}]}
                },
                "toolbarHorizontal": {
                  "label": "Barre d'outils horizontale",
                  "x": {"to": "screen.left", "offset": "margin"},
                  "y": {"to": "screen.top", "offset": "margin"},
                  "w": {"content": true, "limit": [{"to": "rightPanel.left", "offset": -6}],
                        "hideIfMaxBelow": 60},
                  "h": {"content": true}
                },
                "notes.icon": {
                  "label": "Notes : icône du livre (clic : écrire une note)",
                  "x": {"to": "notes.left", "offset": 4},
                  "y": {"to": "notes.top", "offset": 6},
                  "w": {"fixed": 12},
                  "h": {"fixed": 12}
                },
                "notes.title": {
                  "label": "Notes : titre (clic : réduire ou déplier)",
                  "x": {"to": "notes.left", "offset": 17},
                  "y": {"to": "notes.top", "offset": 7},
                  "w": {"content": true},
                  "h": {"fixed": 9}
                },
                "notes.hud": {
                  "label": "Notes : bouton « HUD »",
                  "x": {"to": "inventory.left"},
                  "y": {"to": "toolbarHorizontal.bottom", "offset": "gap", "else": {"to": "screen.top", "offset": "margin"}},
                  "w": {"fixed": 20},
                  "h": {"fixed": 18}
                },
                "rightPanel.tabs": {
                  "label": "Panneau de droite : onglets par mod",
                  "x": {"to": "rightPanel.left", "offset": 5},
                  "y": {"to": "rightPanel.top", "offset": 4},
                  "w": {"fill": {"to": "rightPanel.right", "offset": -5}},
                  "h": {"fixed": 19}
                },
                "rightPanel.clickMode": {
                  "label": "Panneau de droite : bascule « Mode : Donner / Recette »",
                  "x": {"to": "rightPanel.right", "offset": -5, "align": "end"},
                  "y": {"to": "rightPanel.bottom", "offset": -5, "align": "end"},
                  "w": {"content": true},
                  "h": {"fixed": 8}
                },
                "rightPanel.header": {
                  "label": "Panneau de droite : titre de l'onglet",
                  "x": {"to": "rightPanel.left", "offset": 5},
                  "y": {"to": "rightPanel.tabs.bottom", "offset": 6},
                  "w": {"fill": {"to": "rightPanel.right", "offset": -5}},
                  "h": {"fixed": 13}
                },
                "rightPanel.search": {
                  "label": "Panneau de droite : recherche",
                  "x": {"to": "rightPanel.left", "offset": 5},
                  "y": {"to": "rightPanel.header.bottom", "offset": 3},
                  "w": {"fill": {"to": "rightPanel.right", "offset": -5}},
                  "h": {"fixed": 13}
                },
                "rightPanel.separator": {
                  "label": "Panneau de droite : trait entre la recherche et la grille",
                  "x": {"to": "rightPanel.left", "offset": 6},
                  "y": {"to": "rightPanel.search.bottom", "offset": 7},
                  "w": {"fill": {"to": "rightPanel.right", "offset": -6}},
                  "h": {"fixed": 1}
                },
                "rightPanel.grid": {
                  "label": "Panneau de droite : grille d'objets (zone ; la grille s'y centre)",
                  "x": {"to": "rightPanel.left", "offset": 6},
                  "y": {"to": "rightPanel.separator.bottom", "offset": 5},
                  "w": {"fill": {"to": "rightPanel.right", "offset": -4}},
                  "h": {"fill": {"to": "rightPanel.bottom", "offset": -17}}
                },
                "rightPanel.footer": {
                  "label": "Panneau de droite : pagination",
                  "x": {"to": "rightPanel.left", "offset": 5},
                  "y": {"to": "rightPanel.bottom", "offset": -15},
                  "w": {"fill": {"to": "rightPanel.right", "offset": -5}},
                  "h": {"fixed": 12}
                },
                "notes": {
                  "label": "Notes",
                  "x": {"to": "toolbarVertical.right", "offset": "gap", "else": {"to": "screen.left", "offset": "margin"}},
                  "y": {"to": "toolbarHorizontal.bottom", "offset": "gap", "else": {"to": "screen.top", "offset": "margin"}},
                  "w": {"fill": {"to": "inventory.left", "offset": "-gap"}, "min": 108, "max": 136},
                  "h": {"content": true}
                },
                "widget": {
                  "label": "Widget (heure, biome, jour)",
                  "x": {"between": [{"to": "notes.left"}, {"to": "inventory.left", "offset": "-gap"}], "align": "center"},
                  "y": {"between": [{"to": "screen.top"}, {"to": "screen.bottom"}], "align": "center",
                        "atLeast": {"to": "notes.bottom", "offset": "gap"}},
                  "w": {"content": true, "min": 120, "fitSpan": true, "hideBelow": 96},
                  "h": {"content": true}
                }
              }
            }
            """;

    private final Map<String, Object> constants;
    private final Map<String, Map<String, Object>> elements;
    private final Map<String, Map<String, Object>> decorations;

    private GuiLayout(Map<String, Object> constants, Map<String, Map<String, Object>> elements,
                      Map<String, Map<String, Object>> decorations) {
        this.constants = constants;
        this.elements = elements;
        this.decorations = decorations;
    }

    /** Rectangle en unités d'interface. */
    public record Rect(int x, int y, int w, int h) {
        public int right() {
            return x + w;
        }

        public int bottom() {
            return y + h;
        }

        public boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }

        public List<Object> toList() {
            return List.of((double) x, (double) y, (double) w, (double) h);
        }
    }

    // ==================== CONSTRUCTION ====================

    /** Mise en page d'origine. */
    public static GuiLayout defaults() {
        return fromDocument(null);
    }

    /** Mise en page d'origine, modifiée par le texte JSON donné (seuls les champs présents remplacent). */
    public static GuiLayout fromJson(String json) {
        Object doc = MiniJson.parse(json);
        if (!(doc instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("not_object");
        }
        return fromDocument(asMap(doc));
    }

    /**
     * Fusionne {@code overrides} sur la mise en page d'origine : constantes remplacées une à une ; pour chaque
     * élément, chaque propriété présente ({@code x}, {@code y}, {@code w}, {@code h}, {@code visibleMin}…)
     * remplace celle d'origine. Les éléments inconnus du code sont ignorés.
     */
    @SuppressWarnings("unchecked")
    public static GuiLayout fromDocument(Map<String, Object> overrides) {
        Map<String, Object> base = (Map<String, Object>) MiniJson.parse(DEFAULT_JSON);
        Map<String, Object> constants = new LinkedHashMap<>(asMap(base.get("constants")));
        Map<String, Map<String, Object>> elements = new LinkedHashMap<>();
        Map<String, Map<String, Object>> decorations = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : asMap(base.get("elements")).entrySet()) {
            elements.put(e.getKey(), new LinkedHashMap<>(asMap(e.getValue())));
        }
        if (overrides != null) {
            Object f = overrides.get("format");
            if (f != null && !FORMAT.equals(f)) {
                throw new IllegalArgumentException("Format inattendu : " + f + " (attendu : " + FORMAT + ").");
            }
            Object c = overrides.get("constants");
            if (c instanceof Map<?, ?>) {
                constants.putAll(asMap(c));
            }
            Object decs = overrides.get("decorations");
            if (decs instanceof Map<?, ?>) {
                for (Map.Entry<String, Object> e : asMap(decs).entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> && !elements.containsKey(e.getKey())) {
                        decorations.put(e.getKey(), new LinkedHashMap<>(asMap(e.getValue())));
                    }
                }
            }
            Object els = overrides.get("elements");
            if (els instanceof Map<?, ?>) {
                for (Map.Entry<String, Object> e : asMap(els).entrySet()) {
                    Map<String, Object> target = elements.get(e.getKey());
                    if (target != null && e.getValue() instanceof Map<?, ?>) {
                        target.putAll(asMap(e.getValue()));
                    }
                }
            }
        }
        return new GuiLayout(constants, elements, decorations);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : Map.of();
    }

    /** Document complet (constantes et éléments), au format {@code wmi_gui_layout}. */
    public Map<String, Object> toDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", FORMAT);
        doc.put("version", (double) VERSION);
        doc.put("screen", "overlay");
        doc.put("constants", new LinkedHashMap<>(constants));
        doc.put("elements", new LinkedHashMap<>(elements));
        if (!decorations.isEmpty()) {
            doc.put("decorations", new LinkedHashMap<>(decorations));
        }
        return doc;
    }

    /** Élément ou décoration portant cet identifiant, ou {@code null}. */
    public Map<String, Object> element(String id) {
        Map<String, Object> el = elements.get(id);
        return el != null ? el : decorations.get(id);
    }

    /** Décorations, dans l'ordre du fichier. */
    public Map<String, Map<String, Object>> decorations() {
        return decorations;
    }

    /**
     * Contrôle les renvois : chaque bord cité doit désigner l'écran, l'inventaire ou un élément calculé avant
     * (ordre {@link #OVERLAY_ORDER}), et chaque constante citée doit exister. Rend la liste des problèmes.
     */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        List<String> before = new ArrayList<>();
        for (String id : OVERLAY_ORDER) {
            Map<String, Object> el = elements.get(id);
            if (el == null) {
                continue;
            }
            for (String key : List.of("x", "y", "w", "h")) {
                checkRefs(id + "." + key, el.get(key), before, problems);
            }
            before.add(id);
        }
        // Les décorations sont calculées après tous les éléments : elles peuvent citer n'importe lequel
        before.clear();
        before.addAll(elements.keySet());
        for (Map.Entry<String, Map<String, Object>> d : decorations.entrySet()) {
            String id = d.getKey();
            Map<String, Object> el = d.getValue();
            Object type = el.get("type");
            if (!(type instanceof String t) || !DECORATION_TYPES.contains(t)) {
                problems.add("unknown_decoration|" + id + "|" + type);
            }
            Object in = el.get("in");
            if (in != null && !elements.containsKey(String.valueOf(in))) {
                problems.add("unknown_in|" + id + "|" + in);
            }
            for (String key : List.of("x", "y", "w", "h")) {
                checkRefs(id + "." + key, el.get(key), before, problems);
            }
            before.add(id);
        }
        return problems;
    }

    private void checkRefs(String where, Object node, List<String> before, List<String> problems) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                if ("to".equals(k) && v instanceof String ref) {
                    int dot = ref.lastIndexOf('.');
                    String owner = dot < 0 ? ref : ref.substring(0, dot);
                    String edge = dot < 0 ? "" : ref.substring(dot + 1);
                    if (!EDGE_NAMES.contains(edge)) {
                        problems.add("unknown_edge|" + where + "|" + ref);
                    } else if (!"screen".equals(owner) && !"inventory".equals(owner) && !before.contains(owner)) {
                        problems.add("not_before|" + where + "|" + owner);
                    }
                } else if (("offset".equals(k) || "ratio".equals(k)) && v instanceof String name) {
                    String n = name.startsWith("-") ? name.substring(1) : name;
                    if (!constants.containsKey(n)) {
                        problems.add("unknown_constant|" + where + "|" + n);
                    }
                } else {
                    checkRefs(where, v, before, problems);
                }
            }
        } else if (node instanceof List<?> l) {
            for (Object o : l) {
                checkRefs(where, o, before, problems);
            }
        }
    }

    /** Nouveau calcul pour une image. */
    public Resolver resolver(int screenW, int screenH, Rect inventory) {
        return new Resolver(screenW, screenH, inventory);
    }

    // ==================== CALCUL ====================

    /** Calcul des rectangles d'une image, élément par élément, dans l'ordre où le code les place. */
    public final class Resolver {
        private final int screenW;
        private final int screenH;
        private final Rect inventory;
        private final Map<String, Object> localConstants = new LinkedHashMap<>();
        private final Map<String, Rect> rects = new LinkedHashMap<>();
        private final Map<String, int[]> contents = new LinkedHashMap<>();
        private final Map<String, Rect> measuredOnly = new LinkedHashMap<>();

        private Resolver(int screenW, int screenH, Rect inventory) {
            this.screenW = screenW;
            this.screenH = screenH;
            this.inventory = inventory;
        }

        /** Remplace une constante pour ce calcul (réglage du joueur, par exemple la largeur du panneau). */
        public Resolver constant(String name, double value) {
            localConstants.put(name, value);
            return this;
        }

        /**
         * Place l'élément {@code id} ; {@code contentW} et {@code contentH} sont les tailles naturelles mesurées
         * par le code (utilisées par {@code content: true}). Rend {@code null} si l'élément est absent.
         */
        public Rect place(String id, int contentW, int contentH) {
            Map<String, Object> el = element(id);
            contents.put(id, new int[] {contentW, contentH});
            if (el == null) {
                return null;
            }
            int[] hx;
            int[] vy;
            try {
                hx = axis(asMap(el.get("x")), asMap(el.get("w")), contentW, true);
                vy = hx == null ? null : axis(asMap(el.get("y")), asMap(el.get("h")), contentH, false);
            } catch (Hidden h) {
                hx = null;
                vy = null;
            }
            if (hx == null || vy == null) {
                rects.remove(id);
                return null;
            }
            Rect r = new Rect(hx[0], vy[0], hx[1], vy[1]);
            rects.put(id, r);
            return r;
        }

        /** Remplace le rectangle retenu (taille réelle connue après le dessin, par exemple la hauteur des Notes). */
        public void set(String id, Rect r) {
            rects.put(id, r);
        }

        /** Enregistre un sous-rectangle mesuré (relevé seulement, sans effet sur le calcul). */
        public void record(String id, int x, int y, int w, int h) {
            measuredOnly.put(id, new Rect(x, y, w, h));
        }

        public Rect get(String id) {
            return rects.get(id);
        }

        /** Vrai si l'élément est placé et atteint sa taille minimale de dessin ({@code visibleMin}). */
        public boolean drawable(String id) {
            Rect r = rects.get(id);
            if (r == null) {
                return false;
            }
            Map<String, Object> el = element(id);
            Object vm = el == null ? null : el.get("visibleMin");
            if (vm instanceof List<?> l && l.size() == 2) {
                return r.w() >= toInt(l.get(0)) && r.h() >= toInt(l.get(1));
            }
            return true;
        }

        public Map<String, Rect> rects() {
            return rects;
        }

        public Map<String, Rect> measuredOnly() {
            return measuredOnly;
        }

        public Map<String, int[]> contents() {
            return contents;
        }

        public int constantInt(String name) {
            return (int) Math.round(constant(name));
        }

        /** Valeur d'une constante, conditionnelle ou non ({@code {"value", "ifHeightBelow", "then"}}). */
        public double constant(String name) {
            Object v = localConstants.containsKey(name) ? localConstants.get(name) : constants.get(name);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v instanceof Map<?, ?> m) {
                Map<String, Object> c = asMap(m);
                double value = num(c.get("value"), 0);
                if (c.get("ifHeightBelow") instanceof Number lim && screenH < lim.doubleValue()) {
                    return num(c.get("then"), value);
                }
                if (c.get("ifWidthBelow") instanceof Number lim && screenW < lim.doubleValue()) {
                    return num(c.get("then"), value);
                }
                return value;
            }
            throw new IllegalArgumentException("constante inconnue : " + name);
        }

        private double num(Object o, double def) {
            if (o instanceof Number n) {
                return n.doubleValue();
            }
            if (o instanceof String s && !s.isEmpty()) {
                return s.startsWith("-") ? -constant(s.substring(1)) : constant(s);
            }
            return def;
        }

        private int toInt(Object o) {
            return (int) Math.round(num(o, 0));
        }

        /** Signal interne : un bord cité appartient à un élément absent. */
        private static final class Absent extends RuntimeException {
            Absent() {
                super(null, null, false, false);
            }
        }

        /** Signal interne : l'élément est masqué par une règle de taille ({@code hideBelow}, {@code hideIfMaxBelow}). */
        private static final class Hidden extends RuntimeException {
            Hidden() {
                super(null, null, false, false);
            }
        }

        private int edge(Map<String, Object> e) {
            Object to = e.get("to");
            if (!(to instanceof String ref)) {
                throw new Absent();
            }
            int dot = ref.lastIndexOf('.');
            String owner = dot < 0 ? ref : ref.substring(0, dot);
            String which = dot < 0 ? "" : ref.substring(dot + 1);
            Rect r = switch (owner) {
                case "screen" -> new Rect(0, 0, screenW, screenH);
                case "inventory" -> inventory;
                default -> rects.get(owner);
            };
            if (r == null) {
                throw new Absent();
            }
            int base = switch (which) {
                case "left" -> r.x();
                case "right" -> r.right();
                case "top" -> r.y();
                case "bottom" -> r.bottom();
                case "width" -> r.w();
                case "height" -> r.h();
                default -> throw new Absent();
            };
            return base + toInt(e.get("offset"));
        }

        /** Rend {position, taille} sur un axe, ou {@code null} si l'élément est absent. */
        private int[] axis(Map<String, Object> ax, Map<String, Object> size, int content, boolean horizontal) {
            try {
                return axisOrThrow(ax, size, content, horizontal);
            } catch (Absent a) {
                Object alt = ax.get("else");
                if (alt instanceof Map<?, ?>) {
                    return axis(asMap(alt), size, content, horizontal);
                }
                return null;
            }
        }

        private int[] axisOrThrow(Map<String, Object> ax, Map<String, Object> size, int content, boolean horizontal) {
            String align = ax.get("align") instanceof String s ? s : "start";
            boolean between = ax.get("between") instanceof List<?> l && l.size() == 2;
            int a;
            int b = 0;
            if (between) {
                List<?> l = (List<?>) ax.get("between");
                a = edge(asMap(l.get(0)));
                b = edge(asMap(l.get(1)));
            } else {
                a = edge(ax);
            }
            int pos;
            int len;
            Integer hiOrNull = null;
            if (size.get("fill") instanceof Map<?, ?> fill) {
                pos = a;
                len = edge(asMap(fill)) - pos;
            } else {
                if (size.get("fixed") != null) {
                    len = toInt(size.get("fixed"));
                } else if (size.get("ratio") != null) {
                    String of = size.get("of") instanceof String s ? s : (horizontal ? "screen.width" : "screen.height");
                    int ref = edge(Map.of("to", of));
                    len = (int) Math.round(ref * num(size.get("ratio"), 0));
                } else {
                    len = content;
                }
                // Bornes issues des limites : le bord opposé ne dépasse pas le bord limite
                if (size.get("limit") instanceof List<?> limits && !limits.isEmpty()) {
                    int best = Integer.MAX_VALUE;
                    for (Object o : limits) {
                        int lim = edge(asMap(o));
                        int room = "end".equals(align) ? a - lim : lim - a;
                        best = Math.min(best, room);
                    }
                    if (size.get("limitFloor") != null) {
                        best = Math.max(toInt(size.get("limitFloor")), best);
                    }
                    hiOrNull = best;
                }
                if (between && Boolean.TRUE.equals(size.get("fitSpan"))) {
                    hiOrNull = hiOrNull == null ? b - a : Math.min(hiOrNull, b - a);
                }
                pos = 0;
            }
            Integer max = size.get("max") != null ? toInt(size.get("max")) : null;
            Integer hi = hiOrNull;
            if (max != null) {
                hi = hiOrNull == null ? max : Integer.valueOf(Math.min(max, hiOrNull));
            }
            if (hi != null && size.get("hideIfMaxBelow") != null && hi < toInt(size.get("hideIfMaxBelow"))) {
                throw new Hidden();
            }
            if (size.get("min") != null) {
                int lo = toInt(size.get("min"));
                if (hi != null) {
                    lo = Math.min(lo, hi);
                }
                len = Math.max(lo, len);
            }
            if (hi != null) {
                len = Math.min(hi, len);
            }
            if (size.get("hideBelow") != null && len < toInt(size.get("hideBelow"))) {
                throw new Hidden();
            }
            if (!(size.get("fill") instanceof Map<?, ?>)) {
                if (between) {
                    // Décalage facultatif après centrage (élément déplacé dans le concepteur)
                    pos = switch (align) {
                        case "end" -> b - len;
                        case "center" -> a + (b - a - len) / 2;
                        default -> a;
                    } + toInt(ax.get("offset"));
                } else {
                    pos = switch (align) {
                        case "end" -> a - len;
                        case "center" -> a - len / 2;
                        default -> a;
                    };
                }
            }
            if (ax.get("atLeast") instanceof Map<?, ?> min) {
                pos = Math.max(pos, edge(asMap(min)));
            }
            return new int[] {pos, len};
        }
    }
}
