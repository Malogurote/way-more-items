package waymoreitems;

import com.mojang.datafixers.util.Pair;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import waymoreitems.core.CommandPalette;
import waymoreitems.core.InventorySorter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Paquets réseau client -> serveur de Way More Items :
 * <ul>
 *   <li>{@link ActionPayload} : exécute une action rapide (jour, midi, nuit, météo, mode de jeu, soin, purge), réservée au propriétaire de la partie solo ou à la permission de commande ;</li>
 *   <li>{@link GivePayload} : donne un objet en quantité demandée (mêmes droits, ou mode créatif) ;</li>
 *   <li>{@link SortPayload} : trie l'inventaire du joueur ou le conteneur ouvert en respectant les cases verrouillées (autorisé en survie) ;</li>
 *   <li>{@link PlaceRecipePayload} : bouton « Placer dans la grille » : la recette est placée dans la grille d'artisanat ouverte comme par le livre de recettes (autorisé en survie) ;</li>
 *   <li>{@link DeleteCarriedPayload} : détruit l'objet tenu au curseur, déposé sur le panneau de droite ;</li>
 *   <li>{@link GiveBookPayload} : donne un livre enchanté (catalogue des enchantements ; droits du don) ;</li>
 *   <li>{@link BiomeTeleportPayload} : téléporte au biome demandé le plus proche (droits des actions de monde) ;</li>
 *   <li>{@link BiomeListPayload} (serveur vers client) : biomes possibles de la dimension, réponse à l'action {@code biome_list}.</li>
 * </ul>
 */
public final class WmiPayloads {

    public record ActionPayload(String actionId) implements CustomPacketPayload {
        public static final Type<ActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(WmiMod.MODID, "action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUtf(p.actionId),
                buf -> new ActionPayload(buf.readUtf(64))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record GivePayload(String itemId, int count) implements CustomPacketPayload {
        public static final Type<GivePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(WmiMod.MODID, "give"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GivePayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.itemId);
                    buf.writeVarInt(p.count);
                },
                buf -> new GivePayload(buf.readUtf(256), buf.readVarInt())
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SortPayload(boolean sortContainer, List<Integer> lockedSlots, int sortOrderOrdinal) implements CustomPacketPayload {
        public static final Type<SortPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(WmiMod.MODID, "sort"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SortPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.sortContainer);
                    buf.writeVarInt(p.lockedSlots.size());
                    for (int idx : p.lockedSlots) {
                        buf.writeVarInt(idx);
                    }
                    buf.writeVarInt(p.sortOrderOrdinal);
                },
                buf -> {
                    boolean container = buf.readBoolean();
                    int n = Math.min(256, Math.max(0, buf.readVarInt()));
                    List<Integer> locked = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        locked.add(buf.readVarInt());
                    }
                    int ord = buf.readVarInt();
                    return new SortPayload(container, locked, ord);
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Bouton contextuel « Placer dans la grille » : demande au serveur de placer la recette {@code recipeId}
     * dans la grille d'artisanat ouverte, par le même mécanisme que le livre de recettes
     * ({@code RecipeBookMenu.handlePlacement} : disposition des recettes à forme, objets déjà posés rendus).
     * {@code maxItems} : autant d'exemplaires que possible (comme Maj + clic dans le livre).
     */
    public record PlaceRecipePayload(String recipeId, boolean maxItems) implements CustomPacketPayload {
        public static final Type<PlaceRecipePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(WmiMod.MODID, "place_recipe"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlaceRecipePayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.recipeId, 256);
                    buf.writeBoolean(p.maxItems);
                },
                buf -> new PlaceRecipePayload(buf.readUtf(256), buf.readBoolean())
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Vrai si {@code recipe} est une recette d'artisanat plaçable dans une grille {@code width} x {@code height}
     * (recette à forme : largeur et hauteur ; sans forme : nombre d'ingrédients). Commun au client (affichage du
     * bouton) et au serveur (contrôle).
     */
    public static boolean fitsCraftingGrid(Recipe<?> recipe, int width, int height) {
        if (!(recipe instanceof CraftingRecipe) || recipe.placementInfo().isImpossibleToPlace()) {
            return false;
        }
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getWidth() <= width && shaped.getHeight() <= height;
        }
        return recipe.placementInfo().ingredients().size() <= width * height;
    }

    public record DeleteCarriedPayload(boolean singleItem) implements CustomPacketPayload {
        public static final Type<DeleteCarriedPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(WmiMod.MODID, "delete_carried"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DeleteCarriedPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBoolean(p.singleItem),
                buf -> new DeleteCarriedPayload(buf.readBoolean())
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Catalogue des enchantements : donne le livre enchanté {@code enchantmentId} au niveau {@code level}. */
    public record GiveBookPayload(String enchantmentId, int level) implements CustomPacketPayload {
        public static final Type<GiveBookPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(WmiMod.MODID, "give_book"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GiveBookPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.enchantmentId);
                    buf.writeVarInt(p.level);
                },
                buf -> new GiveBookPayload(buf.readUtf(256), buf.readVarInt())
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Fenêtre des biomes : téléporte le joueur au plus proche du biome {@code biomeId}. */
    public record BiomeTeleportPayload(String biomeId) implements CustomPacketPayload {
        public static final Type<BiomeTeleportPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(WmiMod.MODID, "biome_teleport"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BiomeTeleportPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUtf(p.biomeId),
                buf -> new BiomeTeleportPayload(buf.readUtf(256))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Serveur vers client : biomes que la dimension du joueur peut générer (réponse à l'action {@code biome_list}).
     * Le gestionnaire est enregistré côté client ({@code RegisterClientPayloadHandlersEvent}).
     */
    public record BiomeListPayload(List<String> biomeIds) implements CustomPacketPayload {
        public static final int MAX_BIOMES = 2048;
        public static final Type<BiomeListPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(WmiMod.MODID, "biome_list"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BiomeListPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    int n = Math.min(MAX_BIOMES, p.biomeIds.size());
                    buf.writeVarInt(n);
                    for (int i = 0; i < n; i++) {
                        buf.writeUtf(p.biomeIds.get(i));
                    }
                },
                buf -> {
                    int n = Math.min(MAX_BIOMES, Math.max(0, buf.readVarInt()));
                    List<String> ids = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        ids.add(buf.readUtf(256));
                    }
                    return new BiomeListPayload(ids);
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private WmiPayloads() {
    }

    static void register(RegisterPayloadHandlersEvent event) {
        // Version 2 : livres enchantés, liste des biomes et téléportation vers un biome
        PayloadRegistrar reg = event.registrar("2").optional();
        reg.playToServer(ActionPayload.TYPE, ActionPayload.STREAM_CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        handleAction(sp, payload.actionId());
                    }
                }));
        reg.playToServer(GivePayload.TYPE, GivePayload.STREAM_CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        handleGive(sp, payload.itemId(), payload.count());
                    }
                }));
        reg.playToServer(SortPayload.TYPE, SortPayload.STREAM_CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        handleSort(sp, payload);
                    }
                }));
        reg.playToServer(PlaceRecipePayload.TYPE, PlaceRecipePayload.STREAM_CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        handlePlaceRecipe(sp, payload);
                    }
                }));
        reg.playToServer(DeleteCarriedPayload.TYPE, DeleteCarriedPayload.STREAM_CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        handleDeleteCarried(sp, payload.singleItem());
                    }
                }));
        reg.playToServer(GiveBookPayload.TYPE, GiveBookPayload.STREAM_CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        handleGiveBook(sp, payload.enchantmentId(), payload.level());
                    }
                }));
        reg.playToServer(BiomeTeleportPayload.TYPE, BiomeTeleportPayload.STREAM_CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        handleBiomeTeleport(sp, payload.biomeId());
                    }
                }));
        reg.playToClient(BiomeListPayload.TYPE, BiomeListPayload.STREAM_CODEC);
    }

    /**
     * Actions de monde (heure, météo, mode de jeu, soin, purge) : propriétaire de la partie solo ou permission de
     * commande. {@code isSingleplayer()} n'est pas un critère : il reste vrai quand la partie est ouverte au réseau local ;
     * {@code isSingleplayerOwner} ne vaut que pour l'hôte (faux sur un serveur dédié).
     */
    private static boolean canRunWorldActions(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server.isSingleplayerOwner(player.nameAndId())
                || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** Don d'objets : droits ci-dessus, ou mode créatif (le créatif suffit pour le don seul). */
    private static boolean canGive(ServerPlayer player) {
        return player.isCreative() || canRunWorldActions(player);
    }

    private static void syncPlayerInventory(ServerPlayer player) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastFullState();
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastFullState();
        }
    }

    private static void setWorldTimeOfDay(MinecraftServer server, ServerLevel level, long targetOffsetTicks) {
        Holder<DimensionType> dim = level.dimensionTypeRegistration();
        Optional<Holder<WorldClock>> clockOpt = dim.value().defaultClock();
        if (clockOpt.isPresent()) {
            Holder<WorldClock> clock = clockOpt.get();
            ServerClockManager clockManager = server.clockManager();
            long current = level.getDefaultClockTime();
            long dayBase = (current / 24000L) * 24000L;
            clockManager.setTotalTicks(clock, dayBase + targetOffsetTicks);
        } else {
            Commands commands = server.getCommands();
            var source = server.createCommandSourceStack().withLevel(level).withSuppressedOutput();
            commands.performPrefixedCommand(source, "time set " + targetOffsetTicks + "t");
        }
    }

    private static void handleAction(ServerPlayer player, String actionId) {
        if (CommandPalette.WorldAction.SORT_INVENTORY.id().equals(actionId)) {
            handleSort(player, new SortPayload(false, List.of(), 0));
            return;
        }
        if (!canRunWorldActions(player)) {
            player.sendOverlayMessage(Component.translatable("gui.wmi.no_permission"));
            return;
        }
        MinecraftServer server = player.level().getServer();

        switch (actionId) {
            case "time_day" -> {
                // Tick 0 = 6 h (matin), 6000 = midi, 13000 = 19 h (soir)
                setWorldTimeOfDay(server, player.level(), 0L);
                player.sendOverlayMessage(Component.translatable("gui.wmi.action.time_day"));
            }
            case "time_noon" -> {
                setWorldTimeOfDay(server, player.level(), 6000L);
                player.sendOverlayMessage(Component.translatable("gui.wmi.action.time_noon"));
            }
            case "time_night" -> {
                setWorldTimeOfDay(server, player.level(), 13000L);
                player.sendOverlayMessage(Component.translatable("gui.wmi.action.time_night"));
            }
            case "weather_clear" -> {
                server.setWeatherParameters(ServerLevel.RAIN_DELAY.sample(player.getRandom()), 0, false, false);
                player.sendOverlayMessage(Component.translatable("gui.wmi.action.weather_clear"));
            }
            case "weather_rain" -> {
                server.setWeatherParameters(0, ServerLevel.RAIN_DURATION.sample(player.getRandom()), true, false);
                player.sendOverlayMessage(Component.translatable("gui.wmi.action.weather_rain"));
            }
            case "weather_thunder" -> {
                server.setWeatherParameters(0, ServerLevel.THUNDER_DURATION.sample(player.getRandom()), true, true);
                player.sendOverlayMessage(Component.translatable("gui.wmi.action.weather_thunder"));
            }
            case "mode_survival" -> {
                player.setGameMode(GameType.SURVIVAL);
                player.sendOverlayMessage(Component.translatable("gui.wmi.action.mode_survival"));
            }
            case "mode_creative" -> {
                player.setGameMode(GameType.CREATIVE);
                player.sendOverlayMessage(Component.translatable("gui.wmi.action.mode_creative"));
            }
            case "mode_adventure" -> {
                player.setGameMode(GameType.ADVENTURE);
                player.sendOverlayMessage(Component.translatable("gui.wmi.action.mode_adventure"));
            }
            case "mode_spectator" -> {
                player.setGameMode(GameType.SPECTATOR);
                player.sendOverlayMessage(Component.translatable("gui.wmi.action.mode_spectator"));
            }
            case "heal_feed" -> {
                player.setHealth(player.getMaxHealth());
                player.getFoodData().setFoodLevel(20);
                player.getFoodData().setSaturation(20.0F);
                player.clearFire();
                player.sendOverlayMessage(Component.translatable("gui.wmi.action.heal_feed"));
            }
            case "biome_list" -> {
                List<String> ids = new ArrayList<>();
                for (Holder<Biome> biome : player.level().getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
                    biome.unwrapKey().ifPresent(k -> ids.add(k.identifier().toString()));
                }
                PacketDistributor.sendToPlayer(player, new BiomeListPayload(ids));
            }
            case "clear_inventory" -> {
                // Comme /clear sans argument : inventaire, armure, seconde main et grille d'artisanat du joueur
                int removed = player.getInventory().clearOrCountMatchingItems(s -> true, false, -1,
                        player.inventoryMenu.getCraftSlots());
                player.containerMenu.broadcastChanges();
                player.inventoryMenu.slotsChanged(player.getInventory());
                player.sendOverlayMessage(Component.translatable("gui.wmi.action.clear_inventory", removed));
            }
            case "clear_drops" -> {
                AABB box = player.getBoundingBox().inflate(64.0);
                List<ItemEntity> drops = player.level().getEntitiesOfClass(ItemEntity.class, box);
                for (ItemEntity itemEntity : drops) {
                    itemEntity.discard();
                }
                player.sendOverlayMessage(Component.translatable("gui.wmi.action.clear_drops", drops.size()));
            }
        }
    }

    /** Catalogue des enchantements : mêmes droits que le don d'objets ; niveau borné à ceux de l'enchantement. */
    private static void handleGiveBook(ServerPlayer player, String enchantmentId, int level) {
        if (!canGive(player)) {
            player.sendOverlayMessage(Component.translatable("gui.wmi.no_permission"));
            return;
        }
        Identifier id = Identifier.tryParse(enchantmentId);
        if (id == null) {
            return;
        }
        Optional<Holder.Reference<Enchantment>> holder = player.level().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT).get(ResourceKey.create(Registries.ENCHANTMENT, id));
        if (holder.isEmpty()) {
            return;
        }
        Enchantment enchantment = holder.get().value();
        int lvl = Math.max(enchantment.getMinLevel(), Math.min(enchantment.getMaxLevel(), level));
        ItemStack book = EnchantmentHelper.createBook(new EnchantmentInstance(holder.get(), lvl));
        Component name = Enchantment.getFullname(holder.get(), lvl);
        if (!player.getInventory().add(book) && !book.isEmpty()) {
            Block.popResource(player.level(), player.blockPosition(), book);
        }
        syncPlayerInventory(player);
        player.sendOverlayMessage(Component.translatable("gui.wmi.gave_book", name));
    }

    /** Dernière recherche de biome par joueur (millisecondes) : une recherche au plus toutes les deux secondes. */
    private static final Map<java.util.UUID, Long> LAST_BIOME_SEARCH = new HashMap<>();
    private static final long BIOME_SEARCH_COOLDOWN_MS = 2000L;

    /**
     * Fenêtre des biomes : recherche du biome le plus proche comme {@code /locate biome} (rayon de 6 400 blocs,
     * pas de 32 horizontalement et 64 verticalement, sur le fil du serveur : la recherche peut durer plusieurs
     * secondes, comme la commande), puis téléportation sur un sol sûr. Mêmes droits que les actions de monde.
     */
    private static void handleBiomeTeleport(ServerPlayer player, String biomeId) {
        if (!canRunWorldActions(player)) {
            player.sendOverlayMessage(Component.translatable("gui.wmi.no_permission"));
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_BIOME_SEARCH.get(player.getUUID());
        if (last != null && now - last < BIOME_SEARCH_COOLDOWN_MS) {
            return;
        }
        LAST_BIOME_SEARCH.put(player.getUUID(), now);
        Identifier id = Identifier.tryParse(biomeId);
        if (id == null) {
            return;
        }
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, id);
        Component name = Component.translatable(Util.makeDescriptionId("biome", id));
        ServerLevel level = player.level();
        Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(h -> h.is(key), player.blockPosition(), 6400, 32, 64);
        if (found == null) {
            player.sendOverlayMessage(Component.translatable("gui.wmi.biome.not_found", name));
            return;
        }
        BlockPos target = safeStandingPos(level, found.getFirst());
        int distance = (int) Math.round(Math.sqrt(player.blockPosition().distSqr(target)));
        player.teleportTo(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, Set.of(),
                player.getYRot(), player.getXRot(), true);
        player.sendOverlayMessage(Component.translatable("gui.wmi.biome.teleported", name, distance));
    }

    /**
     * Position où le joueur tient debout : surface (carte des hauteurs) dans une dimension sans plafond ; sinon,
     * recherche d'un sol plein surmonté de deux blocs d'air sans liquide, vers le bas puis vers le haut.
     */
    private static BlockPos safeStandingPos(ServerLevel level, BlockPos pos) {
        // Level.getHeight renvoie le fond du monde pour un tronçon non chargé (le joueur tomberait sous le monde) :
        // le tronçon cible est d'abord chargé, généré au besoin, comme par une téléportation
        level.getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        if (!level.dimensionType().hasCeiling()) {
            return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        }
        int minY = level.getMinY() + 1;
        int maxY = level.getMinY() + level.dimensionType().logicalHeight() - 2;
        int start = Math.max(minY, Math.min(maxY, pos.getY()));
        for (int y = start; y >= minY; y--) {
            if (canStand(level, pos.atY(y))) {
                return pos.atY(y);
            }
        }
        for (int y = start + 1; y <= maxY; y++) {
            if (canStand(level, pos.atY(y))) {
                return pos.atY(y);
            }
        }
        return pos.atY(start);
    }

    private static boolean canStand(ServerLevel level, BlockPos feet) {
        BlockState ground = level.getBlockState(feet.below());
        BlockState body = level.getBlockState(feet);
        BlockState head = level.getBlockState(feet.above());
        return ground.isCollisionShapeFullBlock(level, feet.below()) && ground.getFluidState().isEmpty()
                && body.isAir() && head.isAir();
    }

    private static void handleDeleteCarried(ServerPlayer player, boolean singleItem) {
        AbstractContainerMenu menu = player.containerMenu != null ? player.containerMenu : player.inventoryMenu;
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            if (singleItem && carried.getCount() > 1) {
                carried.shrink(1);
            } else {
                menu.setCarried(ItemStack.EMPTY);
            }
        }
        if (menu != player.inventoryMenu && !player.inventoryMenu.getCarried().isEmpty()) {
            if (singleItem && player.inventoryMenu.getCarried().getCount() > 1) {
                player.inventoryMenu.getCarried().shrink(1);
            } else {
                player.inventoryMenu.setCarried(ItemStack.EMPTY);
            }
        }
        syncPlayerInventory(player);
    }

    private static void handleGive(ServerPlayer player, String itemId, int requestedCount) {
        if (!canGive(player)) {
            player.sendOverlayMessage(Component.translatable("gui.wmi.no_permission"));
            return;
        }
        Identifier id;
        try {
            id = Identifier.parse(itemId);
        } catch (Exception e) {
            return;
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == null || item == Items.AIR) {
            return;
        }
        int total = Math.min(640, Math.max(1, requestedCount));
        int maxStack = Math.max(1, item.getDefaultMaxStackSize());
        int rem = total;
        while (rem > 0) {
            int batch = Math.min(rem, maxStack);
            ItemStack stack = new ItemStack(item, batch);
            if (!player.getInventory().add(stack) && !stack.isEmpty()) {
                Block.popResource(player.level(), player.blockPosition(), stack);
            }
            rem -= batch;
        }
        syncPlayerInventory(player);
        player.sendOverlayMessage(Component.translatable("gui.wmi.gave", total, item.getName(new ItemStack(item))));
    }

    private static void handleSort(ServerPlayer player, SortPayload payload) {
        AbstractContainerMenu menu = player.containerMenu;
        Inventory playerInv = player.getInventory();
        InventorySorter.SortOrder[] orders = InventorySorter.SortOrder.values();
        InventorySorter.SortOrder order = orders[Math.floorMod(payload.sortOrderOrdinal(), orders.length)];

        // 1. Si demandé, trier d'abord le conteneur externe (coffre, caisse, etc.)
        if (payload.sortContainer() && menu != null && menu != player.inventoryMenu) {
            if (!menu.stillValid(player)) {
                return;
            }
            List<Slot> containerSlots = new ArrayList<>();
            for (Slot s : menu.slots) {
                if (s.container != playerInv && !(s instanceof ResultSlot) && s.mayPickup(player)) {
                    containerSlots.add(s);
                }
            }
            if (containerSlots.size() > 1) {
                sortMenuSlots(containerSlots, order);
                syncPlayerInventory(player);
                player.sendOverlayMessage(Component.translatable("gui.wmi.sorted"));
                return;
            }
        }

        // 2. Sinon, trier directement les 27 cases principales du sac du joueur (indices 9..35 de Inventory)
        // Cela fonctionne aussi bien dans l'inventaire Survie, Créatif, un coffre ou directement en jeu (touche B).
        Set<Integer> locked = new HashSet<>(payload.lockedSlots());
        List<ItemStack> stacks = new ArrayList<>(27);
        for (int i = 0; i < 27; i++) {
            stacks.add(playerInv.getItem(9 + i));
        }
        SortInput in = describe(stacks);
        List<InventorySorter.SlotStack> sorted = InventorySorter.sort(in.slots(), locked, order);
        for (int i = 0; i < 27; i++) {
            if (!locked.contains(i)) {
                playerInv.setItem(9 + i, in.stackFor(sorted.get(i)));
            }
        }
        syncPlayerInventory(player);
        player.sendOverlayMessage(Component.translatable("gui.wmi.sorted"));
    }

    /**
     * Piles d'une plage de cases pour le tri, et un modèle (quantité 1) par groupe. Deux piles ne sont regroupées que si
     * {@link ItemStack#isSameItemSameComponents} est vrai : jamais par empreinte, pour ne pas confondre deux objets
     * aux composants différents.
     */
    private record SortInput(List<InventorySorter.SlotStack> slots, Map<String, ItemStack> templates) {
        ItemStack stackFor(InventorySorter.SlotStack res) {
            ItemStack tpl = res == null ? null : templates.get(res.stackKey());
            return tpl == null ? ItemStack.EMPTY : tpl.copyWithCount(res.count());
        }
    }

    private static SortInput describe(List<ItemStack> stacks) {
        List<InventorySorter.SlotStack> input = new ArrayList<>(stacks.size());
        Map<String, ItemStack> templates = new HashMap<>();
        List<String> keys = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                input.add(null);
                continue;
            }
            Identifier itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String itemId = itemKey.toString();
            String stackKey = null;
            for (String k : keys) {
                if (ItemStack.isSameItemSameComponents(templates.get(k), stack)) {
                    stackKey = k;
                    break;
                }
            }
            if (stackKey == null) {
                stackKey = itemId + "#" + keys.size();
                keys.add(stackKey);
                templates.put(stackKey, stack.copyWithCount(1));
            }
            input.add(new InventorySorter.SlotStack(
                    itemId,
                    stackKey,
                    itemKey.getNamespace(),
                    categorizeStack(stack),
                    stack.getHoverName().getString(),
                    stack.getCount(),
                    stack.getMaxStackSize()
            ));
        }
        return new SortInput(input, templates);
    }

    /**
     * Trie les cases d'un conteneur. Une case n'est réécrite que si elle accepte chacun des objets déplacés
     * ({@code mayPlace}) en pile pleine ({@code getMaxStackSize}) : cases filtrées, réservées ou de capacité réduite
     * (alambic, machines, cases virtuelles) restent en place.
     */
    private static void sortMenuSlots(List<Slot> targetSlots, InventorySorter.SortOrder order) {
        List<ItemStack> stacks = new ArrayList<>(targetSlots.size());
        for (Slot slot : targetSlots) {
            stacks.add(slot.getItem());
        }
        SortInput in = describe(stacks);
        Set<Integer> locked = InventorySorter.restrictedSlots(in.slots(), Set.of(), (i, candidate) -> {
            ItemStack tpl = in.templates().get(candidate.stackKey());
            Slot slot = targetSlots.get(i);
            return tpl != null && slot.mayPlace(tpl) ? slot.getMaxStackSize(tpl) : 0;
        });
        List<InventorySorter.SlotStack> sorted = InventorySorter.sort(in.slots(), locked, order);
        for (int i = 0; i < targetSlots.size(); i++) {
            if (!locked.contains(i)) {
                targetSlots.get(i).set(in.stackFor(sorted.get(i)));
            }
        }
    }

    private static String categorizeStack(ItemStack stack) {
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
            return "01_blocks";
        }
        if (stack.getMaxStackSize() == 1) {
            return "02_equipment";
        }
        return "03_items";
    }

    /**
     * Place la recette dans la grille d'artisanat ouverte. Contrôles repris du traitement de
     * {@code ServerboundPlaceRecipePacket} par Minecraft (menu valide, joueur non spectateur, recette plaçable),
     * sauf l'exigence que la recette figure dans le livre du joueur : WMI montre toutes les recettes, et le
     * placement ne déplace que des objets que le joueur possède déjà.
     */
    private static void handlePlaceRecipe(ServerPlayer player, PlaceRecipePayload payload) {
        if (player.isSpectator() || !(player.containerMenu instanceof AbstractCraftingMenu menu) || !menu.stillValid(player)) {
            return;
        }
        Identifier id = Identifier.tryParse(payload.recipeId());
        if (id == null || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        Optional<RecipeHolder<?>> holder = level.recipeAccess().byKey(ResourceKey.create(Registries.RECIPE, id));
        if (holder.isEmpty() || !fitsCraftingGrid(holder.get().value(), menu.getGridWidth(), menu.getGridHeight())) {
            return;
        }
        menu.handlePlacement(payload.maxItems(), player.isCreative(), holder.get(), level, player.getInventory());
    }
}
