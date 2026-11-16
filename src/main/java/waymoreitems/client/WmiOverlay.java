package waymoreitems.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import waymoreitems.WmiPayloads;
import waymoreitems.client.WmiButtons.QuickButton;
import waymoreitems.core.GuiLayout;
import waymoreitems.core.PinnedChecklist;
import waymoreitems.core.WorldKey;
import waymoreitems.core.WorldNotes;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Surcouche de Way More Items (WMI) sur les inventaires et l'écran de jeu : coordination mince des composants.
 * <ul>
 *   <li>{@link WmiSettings} : réglages enregistrés ({@link WorldNotes} : Notes et objectifs de chaque monde) ;</li>
 *   <li>{@link WmiToolbar} : barre d'outils, boutons « Paramètres » et bascule WMI / JEI ;</li>
 *   <li>{@link WmiRightPanel} : panneau de droite (onglets, recherche, catalogue, menu du clic droit) ;</li>
 *   <li>{@link WmiNotesPanel} : Notes de l'inventaire et de l'écran de jeu ;</li>
 *   <li>{@link WmiTimeWidget} : widget de l'heure, widget du coin, horloge compacte ;</li>
 *   <li>{@link WmiInventoryTools} : tri, cases verrouillées, surbrillance ;</li>
 *   <li>{@link WmiDecorations} : décorations du fichier de mise en page et relevé ;</li>
 *   <li>{@link WmiOverlayInput} : souris, clavier, saisie de texte ;</li>
 *   <li>{@link WmiDraw} et {@link WmiButtons} : primitives de dessin et boutons de l'image en cours.</li>
 * </ul>
 * Chaque composant garde le rectangle de son dernier dessin, que la saisie et le greffon JEI relisent.
 */
public final class WmiOverlay {

    final PinnedChecklist checklist = new PinnedChecklist();
    /** Notes et objectifs du monde ou serveur en cours, un fichier par monde sous {@code config/wmi/notes}. */
    final WorldNotes worldNotes = new WorldNotes(checklist, WmiPlatform.configDir().resolve("wmi").resolve("notes"));
    final WmiSettings settings = new WmiSettings(checklist, worldNotes);
    final WmiButtons buttons = new WmiButtons();
    final WmiVanillaBook book = new WmiVanillaBook(settings);
    final WmiNotesPanel notes;
    final WmiTimeWidget widget;
    final WmiToolbar toolbar;
    final WmiRightPanel panel;
    final WmiInventoryTools inventory;
    final WmiDecorations decorations;
    final WmiOverlayInput input;

    boolean visible = true;
    private int goalsSaveTicks;
    /** Connexion dont le monde a été identifié en dernier (une nouvelle connexion : monde à identifier). */
    private @Nullable ClientPacketListener worldConnection;
    private @Nullable WorldKey worldKey;
    /** Liste qui vient d'être chargée : les objectifs déjà réunis ne tintent pas. */
    private boolean silentCompletion;

    /** Calcul de mise en page de l'image en cours (relevé et sous-rectangles). */
    GuiLayout.Resolver frame;
    // Inventaire et écran de la dernière image (zones laissées libres à JEI, widget de l'heure), en unités de WMI
    int lastGuiLeft;
    int lastGuiRight;
    int lastScreenW;
    int lastScreenH;
    /** Facteur de taille de WMI de la dernière image (WmiUiScale) : unités de WMI vers unités du jeu, pour la souris et JEI. */
    float lastFactor = 1.0F;

    public WmiOverlay() {
        notes = new WmiNotesPanel(this);
        widget = new WmiTimeWidget(this);
        toolbar = new WmiToolbar(this);
        panel = new WmiRightPanel(this);
        inventory = new WmiInventoryTools(this);
        decorations = new WmiDecorations(this);
        input = new WmiOverlayInput(this);
        settings.load();
    }

    // ==================== ÉTAT ET RÉGLAGES ====================

    public WmiSettings settings() {
        return settings;
    }

    public PinnedChecklist checklist() {
        return checklist;
    }

    public WmiTheme theme() {
        return settings.theme();
    }

    public boolean isVisible() {
        return visible;
    }

    public void toggleVisible() {
        this.visible = !this.visible;
    }

    public boolean isSearchFocused() {
        return visible && (panel.searchFocused || notes.postItInputFocused);
    }

    /** Cahier de recettes de Minecraft ouvert ou fermé d'office ; repris par le prochain écran. */
    public void setVanillaBookEnabled(boolean value) {
        book.reset();
        settings.setVanillaBookEnabled(value);
    }

    /** Vrai si JEI est installé et choisi comme navigateur de recettes (le panneau de droite de WMI est alors masqué). */
    public boolean jeiModeActive() {
        return settings.jeiModeActive();
    }

    /**
     * Zones que JEI doit laisser libres (appelé par le greffon {@code waymoreitems.compat.jei.WmiJeiPlugin}).
     * Mode WMI : les deux côtés de l'inventaire ; mode JEI : les seuls éléments de gauche de WMI.
     */
    public List<Rect2i> jeiExclusionAreas() {
        List<Rect2i> areas = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (!visible || !(mc.gui.screen() instanceof AbstractContainerScreen<?>) || lastScreenW <= 0) {
            return areas;
        }
        // JEI travaille en unités du jeu : zones converties depuis les unités de WMI (WmiUiScale)
        if (!jeiModeActive()) {
            Minecraft win = Minecraft.getInstance();
            int realW = win.getWindow().getGuiScaledWidth();
            int realH = win.getWindow().getGuiScaledHeight();
            int left = Math.round(lastGuiLeft * lastFactor);
            int right = Math.round(lastGuiRight * lastFactor);
            // Départ à x = 1, pas 0 : JEI 31.17 (BookmarkOverlayLayout.calculateDisplayArea) ne rogne sa zone de
            // gauche que par une exclusion qui commence après le bord ; sa zone tombe alors sous 42 de large et
            // ses boutons « favoris » et « historique » du coin inférieur gauche ne sont plus dessinés.
            areas.add(new Rect2i(1, 0, Math.max(0, left - 1), realH));
            areas.add(new Rect2i(right, 0, Math.max(0, realW - right), realH));
            return areas;
        }
        addArea(areas, toolbar.barBounds);
        addArea(areas, notes.bounds);
        addArea(areas, widget.bounds);
        addArea(areas, toolbar.settingsBounds);
        addArea(areas, toolbar.viewerBounds);
        return areas;
    }

    private void addArea(List<Rect2i> areas, GuiLayout.Rect r) {
        if (r != null && r.w() > 0 && r.h() > 0) {
            float f = lastFactor;
            int x0 = (int) Math.floor((r.x() - 2) * f);
            int y0 = (int) Math.floor((r.y() - 2) * f);
            int x1 = (int) Math.ceil((r.right() + 2) * f);
            int y1 = (int) Math.ceil((r.bottom() + 2) * f);
            areas.add(new Rect2i(x0, y0, x1 - x0, y1 - y0));
        }
    }

    /**
     * Vrai si une saisie de texte est en cours : recherche ou Notes de WMI, ou tout champ de texte de Minecraft
     * ou d'un autre mod qui a le focus sur l'écran. Tant que c'est le cas, les raccourcis de WMI sont ignorés.
     */
    public boolean isTextInputActive(Screen screen) {
        return input.isTextInputActive(screen);
    }

    /**
     * Touche N : en jeu, ouvre la petite fenêtre de saisie (note ou objet), qui a son propre champ ; dans un écran,
     * donne le focus au champ des Notes de la surcouche.
     */
    public void startInGamePostItNote(Minecraft mc) {
        if (mc.gui.screen() == null) {
            notes.endNotesInput();
            openModal(null, WmiRecipeScreen.ViewMode.POST_IT_INPUT, "");
            return;
        }
        notes.focusNotes();
    }

    /** Fin de saisie des Notes (fenêtre de la touche N validée ou fermée) : plus aucun champ en attente. */
    public void endNotesInput() {
        notes.endNotesInput();
    }

    // ==================== DROITS ET ACTIONS ====================

    /**
     * Actions de monde (heure, météo, mode de jeu, soin, purge) : même règle que le serveur
     * ({@code WmiPayloads.canRunWorldActions}) : propriétaire de la partie solo ou permission de commande. Le client
     * hôte d'une partie ouverte au réseau local en est le propriétaire ; ses invités ne le sont pas.
     */
    public boolean hasAdminRights(Minecraft mc) {
        // Serveur sans WMI : actions et don masqués (ils passent tous par le serveur)
        if (mc.player == null || !WmiNetwork.serverHasWmi()) {
            return false;
        }
        return mc.hasSingleplayerServer()
                || mc.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /**
     * Don d'objets : droits d'administration, ou mode créatif (le créatif suffit pour le don
     * seul ; même règle que {@code WmiPayloads.canGive}).
     */
    public boolean canGiveItems(Minecraft mc) {
        return hasAdminRights(mc) || (mc.player != null && mc.player.isCreative() && WmiNetwork.serverHasWmi());
    }

    private @Nullable Inventory snapshotSource;
    private long snapshotGameTime = Long.MIN_VALUE;
    private int snapshotTimesChanged = -1;
    private Map<String, Integer> snapshot = Map.of();

    /**
     * Contenu de l'inventaire du joueur ({@code objet -> quantité}, non modifiable). Relu au plus une fois par tick
     * de jeu, ou plus tôt si l'inventaire signale un changement : la surcouche le demande à chaque image.
     */
    public Map<String, Integer> snapshotPlayerInventory() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            snapshotSource = null;
            return Map.of();
        }
        Inventory inv = mc.player.getInventory();
        long gameTime = mc.level != null ? mc.level.getGameTime() : Long.MIN_VALUE + 1;
        if (inv == snapshotSource && gameTime == snapshotGameTime && inv.getTimesChanged() == snapshotTimesChanged) {
            return snapshot;
        }
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                map.merge(id, st.getCount(), Integer::sum);
            }
        }
        // Fluides des seaux pleins, comptés en millibuckets (Notes, fabricabilité)
        WmiClientIndex.peek().fluidsInContainers(map).forEach(map::putIfAbsent);
        snapshotSource = inv;
        snapshotGameTime = gameTime;
        snapshotTimesChanged = inv.getTimesChanged();
        snapshot = Collections.unmodifiableMap(map);
        return snapshot;
    }

    public void giveItemToPlayer(String itemId, int count) {
        // Un fluide se donne dans son seau plein (rien s'il n'en a pas) : seuls les objets passent par le serveur
        itemId = WmiFluids.giveTarget(itemId);
        if (itemId == null) {
            return;
        }
        // Outils, armes, armures, seaux... : jamais plus que la taille de pile de l'objet (1 pour les non empilables)
        ItemStack st = WmiClientIndex.stackFor(itemId);
        int capped = st.isEmpty() ? count : Math.max(1, Math.min(count, st.getMaxStackSize()));
        WmiNetwork.send(new WmiPayloads.GivePayload(itemId, capped));
    }

    public void openModal(Screen parent, WmiRecipeScreen.ViewMode mode, String itemId) {
        Minecraft.getInstance().gui.setScreen(new WmiRecipeScreen(parent, mode, itemId, checklist, snapshotPlayerInventory()));
    }

    /** Usages d'une machine, ouverts sur l'onglet du procédé {@code typeId} (zone cliquable d'un écran de machine). */
    public void openWorkstation(Screen parent, String stationItemId, String typeId) {
        WmiRecipeScreen screen = new WmiRecipeScreen(parent, WmiRecipeScreen.ViewMode.USES, stationItemId, checklist,
                snapshotPlayerInventory());
        screen.focusType(typeId);
        Minecraft.getInstance().gui.setScreen(screen);
    }

    public void tickClient() {
        Minecraft mc = Minecraft.getInstance();
        // Noms d'objets en anglais pour tout le jeu (option) : recouvrir la table de traduction après chaque rechargement
        WmiNames.syncGameLanguage();
        // Recherche du catalogue effacée au retour au jeu (option de l'onglet Catalogue de « Paramètres »)
        if (settings.clearSearchOnClose && mc.gui.screen() == null && !panel.searchQuery.isEmpty()) {
            panel.searchQuery = "";
            panel.currentPage = 0;
            panel.searchFocused = false;
        }
        // Notes du monde ou serveur en cours (toutes dimensions confondues) : liste rechargée au changement de monde ;
        // la saisie en cours (champ des Notes) n'est pas touchée et ira dans la nouvelle liste
        if (worldNotes.switchTo(currentWorldKey(mc))) {
            silentCompletion = true;
        }
        // Objectifs ajoutés depuis la fenêtre des recettes ou retirés : enregistrés au plus toutes les 2 secondes
        if (++goalsSaveTicks >= 40) {
            goalsSaveTicks = 0;
            worldNotes.flush();
        }
        if (mc.player == null || checklist.isEmpty()) {
            return;
        }
        if (checklist.goalCount() > 0 && !WmiClientIndex.peek().allItems().isEmpty()) {
            checklist.useIndex(WmiClientIndex.peek());
        }
        List<String> completedNow = checklist.updateInventory(snapshotPlayerInventory());
        if (silentCompletion) {
            silentCompletion = false;
            return;
        }
        if (!completedNow.isEmpty() && settings.doneSound) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5F, 1.35F);
        }
    }

    /**
     * Monde ou serveur en cours, identifié une fois par connexion : dossier de sauvegarde en solo (hôte d'un monde
     * ouvert au réseau compris), adresse du serveur en multijoueur, nom pour un Realm ; {@code null} hors d'un monde.
     */
    private @Nullable WorldKey currentWorldKey(Minecraft mc) {
        ClientPacketListener connection = mc.level == null ? null : mc.getConnection();
        if (connection != worldConnection) {
            worldConnection = connection;
            worldKey = connection == null ? null : identifyWorld(mc);
        }
        return worldKey;
    }

    private static @Nullable WorldKey identifyWorld(Minecraft mc) {
        if (mc.isLocalServer() && mc.getSingleplayerServer() != null) {
            java.nio.file.Path folder = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
            return folder == null ? null : WorldKey.singleplayer(folder.toString());
        }
        ServerData server = mc.getCurrentServer();
        if (server == null) {
            return null;
        }
        return server.isRealm() ? WorldKey.realm(server.name) : WorldKey.server(server.ip);
    }

    // ==================== ÉCRAN DE JEU ====================

    /** Widget du coin et Notes en jeu ; rend la hauteur occupée. */
    public int renderHudChecklist(GuiGraphicsExtractor g, Font font) {
        return notes.renderHudChecklist(g, font);
    }

    public void renderHudClock(GuiGraphicsExtractor g, Font font) {
        widget.renderHudClock(g, font);
    }

    public int hudClockBossShift() {
        return widget.hudClockBossShift();
    }

    // ==================== SUPERPOSITION SUR LES CONTENEURS / INVENTAIRE ====================

    public void renderContainerOverlay(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!WmiPerf.enabled()) {
            renderContainerOverlayBody(screen, g, mouseX, mouseY);
            return;
        }
        WmiPerf.frame(!visible ? "hidden" : jeiModeActive() ? "JEI" : "WMI");
        long t = WmiPerf.start();
        try {
            renderContainerOverlayBody(screen, g, mouseX, mouseY);
        } finally {
            WmiPerf.stop(WmiPerf.OVERLAY, t);
        }
    }

    private void renderContainerOverlayBody(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        panel.hoveredOverlayItemId = null;
        input.syncTextInput(screen);
        buttons.drawn.clear();
        toolbar.barBounds = null;
        panel.tabRow = null;
        widget.bounds = null;
        toolbar.viewerBounds = null;
        if (!visible) {
            return;
        }

        book.syncVanillaRecipeBookOnOpen(screen);

        // 1. Surbrillance dans le conteneur et cases verrouillées : posées sur les cases vanilla, en unités du jeu
        inventory.renderSlotOverlays(screen, g, screen.getLeftPos(), screen.getTopPos());

        // Le reste de la surcouche est dessiné à la taille propre de WMI (WmiUiScale) ; les infobulles, différées,
        // sont dessinées après la portée, en unités du jeu
        float f = WmiUiScale.push(g);
        lastFactor = f;
        boolean tooltipSet;
        try {
            tooltipSet = renderScaledOverlay(screen, g, Math.round(mouseX / f), Math.round(mouseY / f));
        } finally {
            WmiUiScale.pop(g);
        }
        // La surcouche est dessinée sur ScreenEvent.Render.Post, que NeoForge envoie APRÈS
        // Screen.extractRenderStateWithTooltipAndSubtitles, donc après que l'écran a déjà dessiné les infobulles
        // de l'image (extractDeferredElements) : sans ce second passage, l'infobulle de WMI n'apparaît jamais.
        if (tooltipSet) {
            g.extractDeferredElements(mouseX, mouseY, 0.0F);
        }
    }

    /** Surcouche en unités de WMI (souris comprise) ; rend vrai si une infobulle a été demandée. */
    private boolean renderScaledOverlay(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        float f = lastFactor;

        int screenW = WmiUiScale.guiWidth(g);
        int screenH = WmiUiScale.guiHeight(g);

        // Inventaire vanilla converti en unités de WMI : les éléments ancrés sur lui restent à sa place
        int guiLeft = Math.round(screen.getLeftPos() / f);
        int guiTop = Math.round(screen.getTopPos() / f);
        int guiW = Math.round(screen.getImageWidth() / f);
        int guiH = Math.round(screen.getImageHeight() / f);
        // Panneaux accolés à l'inventaire que déclare la machine (source externe) : les éléments ancrés s'en écartent
        GuiLayout.Rect anchor = new GuiLayout.Rect(guiLeft, guiTop, guiW, guiH);
        List<Rect2i> extra = WmiExternalSources.screenAreas(screen);
        if (!extra.isEmpty()) {
            List<int[]> areas = new ArrayList<>();
            for (Rect2i r : extra) {
                int x0 = (int) Math.floor(r.getX() / f);
                int y0 = (int) Math.floor(r.getY() / f);
                areas.add(new int[] {x0, y0, (int) Math.ceil((r.getX() + r.getWidth()) / f) - x0,
                        (int) Math.ceil((r.getY() + r.getHeight()) / f) - y0});
            }
            int[] ext = waymoreitems.core.ImportedRecipeLayout.extendWithAdjacent(
                    new int[] {guiLeft, guiTop, guiW, guiH}, areas, 2);
            anchor = new GuiLayout.Rect(ext[0], ext[1], ext[2], ext[3]);
        }
        lastGuiLeft = anchor.x();
        lastGuiRight = anchor.right();
        lastScreenW = screenW;
        lastScreenH = screenH;

        // Mise en page déclarative (GuiLayout) : calcul d'origine, ou config/wmi-layout.json rechargé à chaud.
        // Chaque élément est placé dans l'ordre GuiLayout.OVERLAY_ORDER, avec sa taille naturelle mesurée ici.
        GuiLayout.Resolver lay = WmiLayoutRuntime.layout().resolver(screenW, screenH, anchor);
        frame = lay;

        // 0. Panneau de droite : largeur réglable à la souris (proportion de l'écran), bornée par la mise en page
        GuiLayout.Rect rp = panel.place(lay, screenW, mouseX);

        // 2. Boutons de tri directement sur l'inventaire
        if (!(screen instanceof CreativeModeInventoryScreen)) {
            inventory.renderInventorySortHeader(screen, g, font, lay, guiLeft, guiTop, guiW, mouseX, mouseY);
        }

        // 2 bis. Boutons carrés « Paramètres » (engrenage) et, au-dessus, bascule WMI / JEI, au bas du panneau de droite
        toolbar.renderSettingsButton(screen, g, font, lay, mouseX, mouseY);
        if (WmiPlatform.jeiInstalled()) {
            toolbar.renderViewerToggle(g, font, lay, mouseX, mouseY);
        }

        // 3. Barre d'outils : verticale à gauche (par défaut), horizontale en haut ou masquée
        if (settings.utilityBarPosition == WmiToolbar.UtilityBarPosition.LEFT_VERTICAL) {
            toolbar.renderUtilityBarVertical(screen, g, font, lay, mouseX, mouseY);
        } else if (settings.utilityBarPosition == WmiToolbar.UtilityBarPosition.TOP_HORIZONTAL) {
            toolbar.renderUtilityBarHorizontal(screen, g, font, lay, mouseX, mouseY);
        }

        // 4. Notes, à droite de la barre verticale ou sous la barre horizontale
        notes.renderInScreen(g, font, lay, mouseX, mouseY, screenW, screenH);

        // 5. Widget central gauche (heure, biome, jour), facultatif
        if (settings.showCenterLeftHud) {
            widget.renderCenterLeftHud(g, font, lay, mouseX, mouseY);
        }

        // 6. Grand panneau de droite (masqué en mode JEI : la liste de JEI prend la place)
        boolean rightDrawn = false;
        if (jeiModeActive()) {
            panel.bounds = null;
        } else if (lay.drawable("rightPanel")) {
            long tp = WmiPerf.start();
            panel.renderRightBinder(screen, g, font, rp.x(), rp.y(), rp.w(), rp.h(), mouseX, mouseY);
            WmiPerf.stop(WmiPerf.RIGHT_PANEL, tp);
            rightDrawn = true;
        }

        // 7. Décorations ajoutées dans le fichier de mise en page (texte, séparateur, cadre, objet), sans code
        Map<String, Boolean> drawnNow = new LinkedHashMap<>();
        for (String id : lay.rects().keySet()) {
            drawnNow.put(id, !"rightPanel".equals(id) || rightDrawn);
        }
        decorations.renderDecorations(g, font, lay, drawnNow);

        // 7 bis. Menu du clic droit sur un objet du catalogue, au-dessus de tout (sans infobulle d'objet dessous)
        if (panel.ctxMenuItemId != null) {
            if (panel.ctxMenuScreen == screen) {
                panel.renderItemMenu(g, font, mouseX, mouseY, screenW, screenH);
                panel.hoveredOverlayItemId = null;
            } else {
                panel.ctxMenuItemId = null;
            }
        }

        // 8. Infobulles au premier plan
        boolean tooltipSet = false;
        if (panel.hoveredOverlayItemId != null) {
            ItemStack st = WmiClientIndex.stackFor(panel.hoveredOverlayItemId);
            if (!st.isEmpty()) {
                WmiNames.tooltip(g, font, st, mouseX, mouseY);
                tooltipSet = true;
            } else if (WmiFluids.tooltip(g, font, panel.hoveredOverlayItemId, 0, mouseX, mouseY)) {
                tooltipSet = true;
            }
        } else {
            for (QuickButton qb : buttons.drawn) {
                if (qb.contains(mouseX, mouseY) && qb.tooltip() != null && !qb.tooltip().isEmpty()) {
                    g.setTooltipForNextFrame(Component.literal(qb.tooltip()), WmiUiScale.real(mouseX), WmiUiScale.real(mouseY));
                    tooltipSet = true;
                    break;
                }
            }
        }

        // 9. Relevé pour le concepteur d'interfaces (/wmi releve)
        if (WmiLayoutRuntime.captureArmed()) {
            Map<String, Boolean> drawn = new LinkedHashMap<>();
            for (String id : lay.rects().keySet()) {
                boolean partOfRight = "rightPanel".equals(id) || id.startsWith("rightPanel.");
                drawn.put(id, !partOfRight || rightDrawn);
            }
            WmiLayoutRuntime.captureIfArmed(screen, lay, screenW, screenH, decorations.captureContext(mc), drawn);
        }
        return tooltipSet;
    }

    // ==================== SAISIE (voir WmiOverlayInput) ====================

    public boolean onMouseClicked(AbstractContainerScreen<?> screen, MouseButtonEvent event, boolean doubleClick) {
        return input.onMouseClicked(screen, event, doubleClick);
    }

    public boolean onMouseReleased(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        return input.onMouseReleased(screen, event);
    }

    public boolean onMouseScrolled(AbstractContainerScreen<?> screen, double mouseX, double mouseY, double scrollDeltaY) {
        return input.onMouseScrolled(screen, mouseX, mouseY, scrollDeltaY);
    }

    public boolean onCharTyped(CharacterEvent event) {
        return input.onCharTyped(event);
    }

    public boolean onKeyPressed(AbstractContainerScreen<?> screen, KeyEvent event) {
        return input.onKeyPressed(screen, event);
    }
}
