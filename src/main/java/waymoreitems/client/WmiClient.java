package waymoreitems.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.minecraft.ChatFormatting;
import org.joml.Matrix3x2fStack;
import waymoreitems.WmiMod;
import waymoreitems.WmiPayloads;

import java.util.List;

/**
 * Point d'entrée côté client de Way More Items (WMI).
 */
@Mod(value = WmiMod.MODID, dist = Dist.CLIENT)
public final class WmiClient {
    public static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(WmiMod.MODID, "main"));

    public static final KeyMapping KEY_RECIPES = new KeyMapping("key.wmi.recipes", InputConstants.KEY_R, CATEGORY);
    public static final KeyMapping KEY_USES = new KeyMapping("key.wmi.uses", InputConstants.KEY_U, CATEGORY);
    public static final KeyMapping KEY_TOGGLE_OVERLAY = new KeyMapping("key.wmi.toggle_overlay", InputConstants.KEY_O, CATEGORY);
    /**
     * Fenêtre « Accès rapide » : commandes, note ou objet des Notes. Modifiable aussi dans les paramètres de WMI. Touche
     * placée sous Échap (code de position, « ² » en AZERTY, « ` » en QWERTY), libre dans Minecraft.
     */
    public static final KeyMapping KEY_QUICK = new KeyMapping("key.wmi.quick", InputConstants.KEY_GRAVE, CATEGORY);
    public static final KeyMapping KEY_PIN = new KeyMapping("key.wmi.pin", InputConstants.KEY_J, CATEGORY);
    public static final KeyMapping KEY_POSTIT = new KeyMapping("key.wmi.postit", InputConstants.KEY_N, CATEGORY);
    public static final KeyMapping KEY_SORT = new KeyMapping("key.wmi.sort", InputConstants.KEY_B, CATEGORY);

    private static final Identifier HUD_LAYER_ID = Identifier.fromNamespaceAndPath(WmiMod.MODID, "checklist_hud");
    private static final WmiOverlay OVERLAY = new WmiOverlay();

    public static WmiOverlay overlay() {
        return OVERLAY;
    }

    public WmiClient(IEventBus modBus) {
        modBus.addListener(WmiClient::registerKeys);
        modBus.addListener(WmiClient::registerHudLayer);
        modBus.addListener(WmiVector::registerPipelines);
        modBus.addListener(WmiItemPip::register);
        // Paquet serveur vers client (liste des biomes) : gestionnaire enregistré ici, côté client seulement
        modBus.addListener((net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent e) ->
                e.register(WmiPayloads.BiomeListPayload.TYPE, WmiBiomeScreen::onBiomeList));

        NeoForge.EVENT_BUS.addListener(WmiClient::onRecipesReceived);
        NeoForge.EVENT_BUS.addListener(WmiClient::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(WmiClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(WmiClient::registerClientCommands);
        NeoForge.EVENT_BUS.addListener(WmiClient::onRenderLevelAfter);
        NeoForge.EVENT_BUS.addListener(WmiClient::onItemTooltip);
        NeoForge.EVENT_BUS.addListener(WmiClient::onScreenRenderPost);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, WmiClient::onGuiLayerPre);
        NeoForge.EVENT_BUS.addListener(WmiClient::onGuiLayerPost);
        // Priorité haute aussi pour les clics : JEI les écoute à la priorité normale et ouvrirait sa propre fenêtre
        // sur la flèche d'une machine avant que WMI ne réponde
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, WmiClient::onScreenMouseClickedPre);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, WmiClient::onScreenMouseReleasedPre);
        NeoForge.EVENT_BUS.addListener(WmiClient::onScreenMouseScrolledPre);
        // Priorité haute : en mode WMI, WMI répond aux touches avant JEI et annule l'événement
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, WmiClient::onScreenKeyPressedPre);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, WmiClient::onScreenCharTypedPre);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(KEY_RECIPES);
        event.register(KEY_USES);
        event.register(KEY_TOGGLE_OVERLAY);
        event.register(KEY_QUICK);
        event.register(KEY_PIN);
        event.register(KEY_POSTIT);
        event.register(KEY_SORT);
    }

    /**
     * Commandes client du concepteur d'interfaces : {@code /wmi releve} (relevé de la prochaine image d'un écran à
     * inventaire) et {@code /wmi mise_en_page} (relecture immédiate de {@code config/wmi-layout.json}).
     */
    private static void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wmi")
                .then(Commands.literal("releve").executes(ctx -> {
                    WmiLayoutRuntime.armCapture();
                    ctx.getSource().sendSuccess(() -> WmiText.c("wmi.cmd.capture_armed"), false);
                    return 1;
                }))
                .then(Commands.literal("mise_en_page").executes(ctx -> {
                    String msg = WmiLayoutRuntime.reload(true);
                    // Les comptes rendus de réussite ou d'erreur sont déjà affichés par reload
                    if (msg.equals(WmiLayoutRuntime.noLayoutFileMessage())) {
                        ctx.getSource().sendSuccess(() -> WmiText.c("wmi.cmd.prefix", msg), false);
                    }
                    return 1;
                })));
    }

    private static void registerHudLayer(RegisterGuiLayersEvent event) {
        event.registerAboveAll(HUD_LAYER_ID, WmiClient::renderHud);
    }

    /**
     * HUD de WMI masqué : interface du jeu cachée (F1) ou écran de débogage ouvert (F3), chacun selon son réglage de
     * l'onglet HUD de « Paramètres ».
     */
    static boolean hudSuppressed(Minecraft mc) {
        WmiSettings s = OVERLAY.settings();
        return (s.hideWithF1 && mc.gui.hud.isHidden()) || (s.hideWithDebug && mc.getDebugOverlay().showDebugScreen());
    }

    private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || hudSuppressed(mc) || mc.gui.screen() != null) {
            return;
        }
        // Taille propre de WMI, indépendante de celle du jeu (WmiUiScale)
        WmiUiScale.push(graphics);
        try {
            OVERLAY.renderHudChecklist(graphics, mc.font);
            OVERLAY.renderHudClock(graphics, mc.font);
        } finally {
            WmiUiScale.pop(graphics);
        }
    }

    private static void onRecipesReceived(RecipesReceivedEvent event) {
        WmiClientIndex.onRecipesSynced(event.getRecipeMap());
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WmiClientIndex.clear();
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        WmiClientIndex.tickClient();
        OVERLAY.tickClient();
        WmiLayoutRuntime.tick();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.screen() != null) {
            return;
        }
        while (KEY_QUICK.consumeClick()) {
            OVERLAY.openModal(null, WmiRecipeScreen.ViewMode.COMMAND_PALETTE, "");
        }
        while (KEY_POSTIT.consumeClick()) {
            OVERLAY.startInGamePostItNote(mc);
        }
        while (KEY_RECIPES.consumeClick()) {
            ItemStack held = mc.player.getMainHandItem();
            if (!held.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
                OVERLAY.openModal(null, WmiRecipeScreen.ViewMode.RECIPES, id);
            }
        }
        while (KEY_USES.consumeClick()) {
            ItemStack held = mc.player.getMainHandItem();
            if (!held.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
                OVERLAY.openModal(null, WmiRecipeScreen.ViewMode.USES, id);
            }
        }
        while (KEY_SORT.consumeClick()) {
            WmiNetwork.send(new WmiPayloads.SortPayload(false, List.of(), 0));
        }
        while (KEY_TOGGLE_OVERLAY.consumeClick()) {
            OVERLAY.toggleVisible();
        }
    }

    /**
     * Nom du mod d'origine en bas de l'infobulle d'un objet (option de l'onglet Général de « Paramètres ») ; jamais
     * pendant la lecture des infobulles par l'index (la recherche par infobulle n'en tiendrait pas compte), ni si
     * une ligne porte déjà ce nom (autre mod qui l'affiche).
     */
    private static void onItemTooltip(ItemTooltipEvent event) {
        if (!OVERLAY.settings().modInTooltip || WmiClientIndex.collectingTooltips || event.getEntity() == null) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }
        String ns = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();
        String name = "minecraft".equals(ns) ? "Minecraft" : WmiPlatform.modDisplayName(ns);
        List<Component> tip = event.getToolTip();
        for (Component line : tip) {
            if (line.getString().equals(name)) {
                return;
            }
        }
        tip.add(Component.literal(name).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC));
    }

    private static void onRenderLevelAfter(RenderLevelStageEvent.AfterLevel event) {
        WmiBlurHelper.onAfterLevel();
    }

    /** Pile décalée pour les barres de boss, tant que le retrait n'a pas eu lieu ({@code null} sinon). */
    private static Matrix3x2fStack bossShiftPose;

    /**
     * Barres de boss : descendues sous l'horloge compacte quand elle est affichée. Le décalage est une translation de la
     * pile de matrices, posée avant la couche et retirée après elle. Le dépôt est mémorisé : si un autre mod annule la
     * couche après WMI, « Post » n'a pas lieu, et le décalage est alors retiré au début de la couche suivante.
     */
    private static void onGuiLayerPre(RenderGuiLayerEvent.Pre event) {
        Matrix3x2fStack pose = event.getGuiGraphics().pose();
        popBossShift(pose);
        if (VanillaGuiLayers.BOSS_OVERLAY.equals(event.getName())) {
            int shift = OVERLAY.hudClockBossShift();
            if (shift > 0) {
                pose.pushMatrix();
                pose.translate(0.0F, (float) shift);
                bossShiftPose = pose;
            }
        }
    }

    private static void onGuiLayerPost(RenderGuiLayerEvent.Post event) {
        if (VanillaGuiLayers.BOSS_OVERLAY.equals(event.getName())) {
            popBossShift(event.getGuiGraphics().pose());
        }
    }

    /** Retire le décalage s'il a été posé sur cette pile ; une pile d'une autre image est seulement oubliée. */
    private static void popBossShift(Matrix3x2fStack pose) {
        if (bossShiftPose != null) {
            if (bossShiftPose == pose) {
                pose.popMatrix();
            }
            bossShiftPose = null;
        }
    }

    private static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?> containerScreen) {
            OVERLAY.renderContainerOverlay(containerScreen, event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
        }
    }

    private static void onScreenMouseClickedPre(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?> containerScreen) {
            if (OVERLAY.onMouseClicked(containerScreen, event.getMouseButtonEvent(), false)) {
                event.setCanceled(true);
            }
        }
    }

    private static void onScreenMouseReleasedPre(ScreenEvent.MouseButtonReleased.Pre event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?> containerScreen) {
            if (OVERLAY.onMouseReleased(containerScreen, event.getMouseButtonEvent())) {
                event.setCanceled(true);
            }
        }
    }

    private static void onScreenMouseScrolledPre(ScreenEvent.MouseScrolled.Pre event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?> containerScreen) {
            if (OVERLAY.onMouseScrolled(containerScreen, event.getMouseX(), event.getMouseY(), event.getScrollDeltaY())) {
                event.setCanceled(true);
            }
        }
    }

    private static void onScreenKeyPressedPre(ScreenEvent.KeyPressed.Pre event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?> containerScreen) {
            if (OVERLAY.onKeyPressed(containerScreen, event.getKeyEvent())) {
                event.setCanceled(true);
            }
        }
    }

    private static void onScreenCharTypedPre(ScreenEvent.CharacterTyped.Pre event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?>) {
            if (OVERLAY.onCharTyped(event.getCharacterEvent())) {
                event.setCanceled(true);
            }
        }
    }
}
