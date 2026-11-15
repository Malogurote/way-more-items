package waymoreitems.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import org.jspecify.annotations.Nullable;
import waymoreitems.WmiPayloads;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Catalogue des enchantements (bouton de la sous-barre « Outils ») : une ligne par enchantement, triée par nom,
 * avec un livre enchanté par niveau ; un liseré discret sépare les lignes. Clic sur un livre : il est donné
 * (mêmes droits que le don d'objets). Les enchantements des autres mods apparaissent d'eux-mêmes (registre du monde).
 */
final class WmiEnchantScreen extends WmiListScreen {
    private static final int NAME_W = 112;
    private static final int BOOK = 18;
    private static final int ROW_PAD = 3;

    /** Enchantement du registre, avec son nom affiché et un livre par niveau (calculés une fois). */
    private record Row(String id, String name, List<ItemStack> books, List<String> levelNames, int minLevel) {
    }

    private final List<Row> rows = new ArrayList<>();

    WmiEnchantScreen(@Nullable Screen parent) {
        super(WmiText.c("wmi.enchantments.title"), parent);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements().forEach(ref -> {
            Enchantment ench = ref.value();
            List<ItemStack> books = new ArrayList<>();
            List<String> levelNames = new ArrayList<>();
            for (int lvl = ench.getMinLevel(); lvl <= ench.getMaxLevel(); lvl++) {
                books.add(EnchantmentHelper.createBook(new EnchantmentInstance(ref, lvl)));
                levelNames.add(Enchantment.getFullname(ref, lvl).getString());
            }
            rows.add(new Row(ref.key().identifier().toString(), ench.description().getString(), books, levelNames,
                    ench.getMinLevel()));
        });
        Collator collator = Collator.getInstance(Locale.getDefault());
        rows.sort((a, b) -> collator.compare(a.name(), b.name()));
    }

    @Override
    String subtitle() {
        return WmiText.s("wmi.enchantments.count", rows.size());
    }

    @Override
    String searchPlaceholder() {
        return WmiText.s("wmi.enchantments.search");
    }

    @Override
    int renderRows(GuiGraphicsExtractor g, int x, int top, int w, int mouseX, int mouseY) {
        WmiTheme th = WmiDraw.theme();
        String q = query();
        int perLine = Math.max(1, (w - NAME_W) / (BOOK + 2));
        int y = top;
        boolean first = true;
        @Nullable ItemStack hoveredBook = null;
        for (Row row : rows) {
            if (!q.isEmpty() && !row.name().toLowerCase(Locale.ROOT).contains(q)
                    && !row.id().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            int lines = (row.books().size() + perLine - 1) / perLine;
            int rowH = ROW_PAD * 2 + lines * BOOK + (lines - 1) * 2;
            if (!first) {
                WmiDraw.bar(g, x, y, x + w, y + 1, th.innerBorder);
            }
            first = false;
            if (isVisible(y, rowH)) {
                WmiDraw.drawText(g, font, WmiVector.trim(font, row.name(), NAME_W - 6), x + 2, y + ROW_PAD + (BOOK - 8) / 2, th.textMain);
                for (int i = 0; i < row.books().size(); i++) {
                    int bx = x + NAME_W + (i % perLine) * (BOOK + 2);
                    int by = y + ROW_PAD + (i / perLine) * (BOOK + 2);
                    boolean hover = hovered(bx, by, BOOK, BOOK, mouseX, mouseY);
                    WmiDraw.drawBox(g, bx, by, BOOK, BOOK, hover ? th.slotHover : th.slotBg, hover ? th.accent : th.innerBorder);
                    ItemStack book = row.books().get(i);
                    g.fakeItem(book, bx + 1, by + 1);
                    int level = row.minLevel() + i;
                    String levelName = row.levelNames().get(i);
                    addZone(bx, by, BOOK, BOOK, () -> {
                        if (WmiNetwork.send(new WmiPayloads.GiveBookPayload(row.id(), level))) {
                            flash(WmiText.s("wmi.enchantments.received", levelName));
                        }
                    }, null);
                    if (hover) {
                        hoveredBook = book;
                    }
                }
            }
            y += rowH + 1;
        }
        if (hoveredBook != null) {
            g.setTooltipForNextFrame(font, hoveredBook, WmiUiScale.real(mouseX), WmiUiScale.real(mouseY));
        }
        return y - top;
    }
}
