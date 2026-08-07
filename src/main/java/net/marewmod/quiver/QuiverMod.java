package net.marewmod.quiver;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.loot.v2.LootTableEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.condition.RandomChanceLootCondition;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
import net.marewmod.quiver.recipe.QuiverSmithingRecipe;
import net.marewmod.quiver.compat.AdvancedCauldronCompat;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.marewmod.quiver.item.ModItems;
import net.marewmod.quiver.item.QuiverItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuiverMod implements ModInitializer {

    public static final String MOD_ID = "quiver";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final net.minecraft.enchantment.Enchantment AUTO_REFILL =
        net.minecraft.registry.Registry.register(
            net.minecraft.registry.Registries.ENCHANTMENT,
            new Identifier(MOD_ID, "auto_refill"),
            new net.marewmod.quiver.enchantment.AutoRefillEnchantment());

    /** Client → server: scroll over a quiver slot to change selected arrow type. */
    public static final Identifier QUIVER_SCROLL_PACKET = new Identifier(MOD_ID, "scroll");

    /** Server → client: update the SelectedSlot of the equipped quiver directly. */
    public static final Identifier QUIVER_SYNC_PACKET = new Identifier(MOD_ID, "sync_slot");

    /** Client → server: toggle Auto Refill active/paused on the hovered quiver. */
    public static final Identifier QUIVER_TOGGLE_REFILL = new Identifier(MOD_ID, "toggle_refill");

    @Override
    public void onInitialize() {
        ModItems.initialize();
        QuiverSmithingRecipe.SERIALIZER.getClass();

        if (FabricLoader.getInstance().isModLoaded("advancedcauldron")) {
            AdvancedCauldronCompat.register();
        }

        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            server.execute(() -> {
                var player = handler.getPlayer();
                // Strip Auto Refill enchantment from all quivers if disabled in config
                if (!net.marewmod.quiver.config.QuiverConfig.get().auto_refill_enchantment) {
                    String arId = new net.minecraft.util.Identifier(MOD_ID, "auto_refill").toString();
                    dev.emi.trinkets.api.TrinketsApi.getTrinketComponent(player).ifPresent(comp -> {
                        for (var groupMap : comp.getInventory().values()) {
                            for (var inv : groupMap.values()) {
                                for (int i = 0; i < inv.size(); i++) {
                                    ItemStack s = inv.getStack(i);
                                    if (s.getItem() instanceof QuiverItem && hasAutoRefill(s, arId)) {
                                        ItemStack stripped = stripAutoRefill(s.copy(), arId);
                                        QuiverItem.SUPPRESS_EQUIP_SOUND.add(player.getUuid());
                                        inv.setStack(i, stripped);
                                    }
                                }
                            }
                        }
                    });
                    for (int i = 0; i < player.getInventory().size(); i++) {
                        ItemStack s = player.getInventory().getStack(i);
                        if (s.getItem() instanceof QuiverItem && hasAutoRefill(s, arId)) {
                            ItemStack stripped = stripAutoRefill(s.copy(), arId);
                            player.getInventory().setStack(i, stripped);
                        }
                    }
                    player.getInventory().markDirty();
                }
            })
        );

        ServerPlayNetworking.registerGlobalReceiver(QUIVER_SCROLL_PACKET,
            (server, player, handler, buf, responseSender) -> {
                int direction   = buf.readInt();
                String reqGroup = buf.readString();
                String reqName  = buf.readString();
                server.execute(() -> {
                    boolean found = dev.emi.trinkets.api.TrinketsApi.getTrinketComponent(player).map(comp -> {
                        var allQuivers = comp.getAllEquipped().stream()
                            .filter(p -> p.getRight().getItem() instanceof QuiverItem)
                            .toList();
                        if (allQuivers.isEmpty()) return false;

                        dev.emi.trinkets.api.SlotReference target = null;
                        if (!reqGroup.isEmpty()) {
                            for (var p : allQuivers) {
                                var st = p.getLeft().inventory().getSlotType();
                                if (st.getGroup().equals(reqGroup) && st.getName().equals(reqName)) {
                                    target = p.getLeft(); break;
                                }
                            }
                        }
                        if (target == null) target = allQuivers.get(0).getLeft();
                        if (target == null) return false;

                        final dev.emi.trinkets.api.SlotReference keep = target;
                        for (var p : allQuivers) {
                            var ref2 = p.getLeft();
                            if (ref2.inventory() == keep.inventory() && ref2.index() == keep.index()) continue;
                            ref2.inventory().setStack(ref2.index(), ItemStack.EMPTY);
                        }

                        ItemStack live = keep.inventory().getStack(keep.index());
                        int n = QuiverItem.getSlotCount(live);
                        if (n <= 1) return allQuivers.size() > 1;
                        int cur  = QuiverItem.getSelectedSlot(live);
                        int next = Math.max(0, Math.min(n - 1, cur + direction));
                        ItemStack updated = live.copy();
                        QuiverItem.setSelectedSlot(updated, next);
                        QuiverItem.SUPPRESS_EQUIP_SOUND.add(player.getUuid());
                        keep.inventory().setStack(keep.index(), updated);
                        var b2 = PacketByteBufs.create();
                        b2.writeInt(next);
                        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, QUIVER_SYNC_PACKET, b2);
                        return true;
                    }).orElse(false);

                    if (!found) {
                        for (int i = 0; i < player.getInventory().size(); i++) {
                            ItemStack s = player.getInventory().getStack(i);
                            if (!(s.getItem() instanceof QuiverItem)) continue;
                            int n = QuiverItem.getSlotCount(s);
                            if (n <= 1) continue;
                            int next = (QuiverItem.getSelectedSlot(s) + direction + n) % n;
                            QuiverItem.setSelectedSlot(s, next);
                            player.getInventory().markDirty();
                            break;
                        }
                    }

                    player.playerScreenHandler.sendContentUpdates();
                });
            });

        ServerPlayNetworking.registerGlobalReceiver(QUIVER_TOGGLE_REFILL,
            (server, player, handler, buf, responseSender) -> server.execute(() -> {
                boolean toggled = dev.emi.trinkets.api.TrinketsApi.getTrinketComponent(player)
                    .map(comp -> {
                        for (var pair : comp.getAllEquipped()) {
                            ItemStack s = pair.getLeft().inventory().getStack(pair.getLeft().index());
                            if (!(s.getItem() instanceof QuiverItem)) continue;
                            if (net.minecraft.enchantment.EnchantmentHelper.getLevel(AUTO_REFILL, s) <= 0) continue;
                            ItemStack copy = s.copy();
                            QuiverItem.toggleAutoRefill(copy);
                            QuiverItem.SUPPRESS_EQUIP_SOUND.add(player.getUuid());
                            pair.getLeft().inventory().setStack(pair.getLeft().index(), copy);
                            return true;
                        }
                        return false;
                    }).orElse(false);
                if (!toggled) {
                    for (int i = 0; i < player.getInventory().size(); i++) {
                        ItemStack s = player.getInventory().getStack(i);
                        if (!(s.getItem() instanceof QuiverItem)) continue;
                        if (net.minecraft.enchantment.EnchantmentHelper.getLevel(AUTO_REFILL, s) <= 0) continue;
                        QuiverItem.toggleAutoRefill(s);
                        player.getInventory().markDirty();
                        break;
                    }
                }
            }));

        // AutoRefill tick — pull arrows from inventory into the equipped quiver (every second)
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var player : server.getPlayerManager().getPlayerList()) {
                if (player.age % 20 != 0) continue;
                dev.emi.trinkets.api.TrinketsApi.getTrinketComponent(player).ifPresent(comp ->
                    comp.getAllEquipped().stream()
                        .filter(p -> p.getRight().getItem() instanceof QuiverItem)
                        .max(java.util.Comparator.comparingInt(p ->
                            QuiverItem.getTotalCount(p.getLeft().inventory().getStack(p.getLeft().index()))))
                        .ifPresent(pair -> {
                            dev.emi.trinkets.api.SlotReference ref = pair.getLeft();
                            ItemStack quiverStack = ref.inventory().getStack(ref.index());
                            if (net.minecraft.enchantment.EnchantmentHelper.getLevel(AUTO_REFILL, quiverStack) <= 0) return;
                            if (!QuiverItem.isAutoRefillActive(quiverStack)) return;
                            int space = QuiverItem.getMaxCapacity(quiverStack) - QuiverItem.getTotalCount(quiverStack);
                            if (space <= 0) return;
                            boolean any = false;
                            for (int i = 0; i < player.getInventory().size() && space > 0; i++) {
                                ItemStack inv = player.getInventory().getStack(i);
                                if (!(inv.getItem() instanceof net.minecraft.item.ArrowItem)) continue;
                                int inserted = QuiverItem.insertPublic(quiverStack, inv);
                                if (inserted <= 0) continue;
                                inv.decrement(inserted);
                                space -= inserted;
                                any = true;
                            }
                            if (any) {
                                QuiverItem.SUPPRESS_EQUIP_SOUND.add(player.getUuid());
                                ref.inventory().markDirty();
                            }
                        })
                );
            }
        });

        LootTableEvents.MODIFY.register((rm, manager, id, tableBuilder, source) -> {
            if (!source.isBuiltin()) return;
            net.marewmod.quiver.config.QuiverConfig cfg = net.marewmod.quiver.config.QuiverConfig.get();
            if (!cfg.auto_refill_enchantment) return;
            String tableStr = id.toString();
            for (net.marewmod.quiver.config.QuiverConfig.LootEntry entry : cfg.auto_refill_loot_entries) {
                if (!tableStr.equals(entry.table)) continue;
                net.minecraft.item.ItemStack book = net.minecraft.item.Items.ENCHANTED_BOOK.getDefaultStack();
                net.minecraft.item.EnchantedBookItem.addEnchantment(book,
                    new net.minecraft.enchantment.EnchantmentLevelEntry(AUTO_REFILL, 1));
                tableBuilder.pool(LootPool.builder()
                    .rolls(ConstantLootNumberProvider.create(1))
                    .with(ItemEntry.builder(net.minecraft.item.Items.ENCHANTED_BOOK)
                        .apply(net.minecraft.loot.function.SetNbtLootFunction.builder(book.getNbt())))
                    .conditionally(RandomChanceLootCondition.builder(entry.chance / 100f)));
            }
        });

        net.fabricmc.fabric.api.object.builder.v1.trade.TradeOfferHelper.registerVillagerOffers(
            net.minecraft.village.VillagerProfession.FLETCHER, 5,
            factories -> factories.add((entity, random) -> {
                if (!net.marewmod.quiver.config.QuiverConfig.get().auto_refill_enchantment) return null;
                net.minecraft.item.ItemStack book = net.minecraft.item.Items.ENCHANTED_BOOK.getDefaultStack();
                net.minecraft.item.EnchantedBookItem.addEnchantment(book,
                    new net.minecraft.enchantment.EnchantmentLevelEntry(AUTO_REFILL, 1));
                return new net.minecraft.village.TradeOffer(
                    new ItemStack(net.minecraft.item.Items.EMERALD, 24), book, 1, 30, 0.05f);
            }));

        LootTableEvents.MODIFY.register((resourceManager, lootManager, id, tableBuilder, source) -> {
            if (!source.isBuiltin()) return;
            String tableStr = id.toString();
            for (net.marewmod.quiver.config.QuiverConfig.LootEntry entry
                    : net.marewmod.quiver.config.QuiverConfig.get().quiver_loot_entries) {
                if (!tableStr.equals(entry.table)) continue;
                tableBuilder.pool(LootPool.builder()
                    .rolls(ConstantLootNumberProvider.create(1))
                    .with(ItemEntry.builder(ModItems.QUIVER).weight(1))
                    .conditionally(RandomChanceLootCondition.builder(entry.chance / 100f)));
            }
        });

        // Strip Auto Refill from all online players on /reload if enchantment is disabled
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(
            (server, resourceManager, success) -> {
                if (!success) return;
                if (net.marewmod.quiver.config.QuiverConfig.get().auto_refill_enchantment) return;
                String arId = new net.minecraft.util.Identifier(MOD_ID, "auto_refill").toString();
                for (var player : server.getPlayerManager().getPlayerList()) {
                    dev.emi.trinkets.api.TrinketsApi.getTrinketComponent(player).ifPresent(comp -> {
                        for (var groupMap : comp.getInventory().values()) {
                            for (var inv : groupMap.values()) {
                                for (int i = 0; i < inv.size(); i++) {
                                    ItemStack s = inv.getStack(i);
                                    if (s.getItem() instanceof QuiverItem && hasAutoRefill(s, arId)) {
                                        QuiverItem.SUPPRESS_EQUIP_SOUND.add(player.getUuid());
                                        inv.setStack(i, stripAutoRefill(s.copy(), arId));
                                    }
                                }
                            }
                        }
                    });
                    for (int i = 0; i < player.getInventory().size(); i++) {
                        ItemStack s = player.getInventory().getStack(i);
                        if (s.getItem() instanceof QuiverItem && hasAutoRefill(s, arId)) {
                            player.getInventory().setStack(i, stripAutoRefill(s.copy(), arId));
                        }
                    }
                    player.getInventory().markDirty();
                }
            });

        LOGGER.info("Wearable Quivers loaded.");
    }

    private static boolean hasAutoRefill(ItemStack stack, String enchId) {
        return net.minecraft.enchantment.EnchantmentHelper.getLevel(AUTO_REFILL, stack) > 0;
    }

    private static ItemStack stripAutoRefill(ItemStack stack, String enchId) {
        if (!stack.hasNbt()) return stack;
        net.minecraft.nbt.NbtCompound nbt = stack.getNbt();
        if (nbt == null) return stack;
        net.minecraft.nbt.NbtList enchants = nbt.getList("Enchantments", net.minecraft.nbt.NbtElement.COMPOUND_TYPE);
        for (int i = enchants.size() - 1; i >= 0; i--) {
            if (enchants.getCompound(i).getString("id").equals(enchId)) enchants.remove(i);
        }
        if (enchants.isEmpty()) nbt.remove("Enchantments");
        nbt.remove("AutoRefillActive");
        return stack;
    }
}
