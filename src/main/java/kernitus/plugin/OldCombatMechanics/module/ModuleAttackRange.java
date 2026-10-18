/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kernitus.plugin.OldCombatMechanics.module;

import kernitus.plugin.OldCombatMechanics.OCMMain;
import kernitus.plugin.OldCombatMechanics.utilities.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Applies the 1.8-style attack range (reach + hitbox margin) to melee weapons when Paper exposes
 * the required AttackRange data-component API.
 * Gracefully disables itself on Spigot or other servers where the component API is absent.
 * <p>
 * The component is kept on every weapon in the player's inventory rather than being added to and removed
 * from the held item on each hotbar change. Removing and re-adding it made the server resend the sword
 * on every switch, so the client used vanilla hit detection until the update arrived. Weapons that enter
 * the inventory by other means (kits, pickups, containers) are covered by a batched refresh on the next tick.
 * The component is still removed from items that leave the player (drops, death, quitting).
 */
public class ModuleAttackRange extends OCMModule implements Listener {

    private static final String[] WEAPONS = {"sword", "axe", "pickaxe", "spade", "shovel", "hoe", "trident", "mace"};
    private static final String SLOT_CHANGE_EVENT = "io.papermc.paper.event.player.PlayerInventorySlotChangeEvent";

    private boolean supported;
    private float minRange;
    private float maxRange;
    private float minCreative;
    private float maxCreative;
    private float hitboxMargin;
    private float mobFactor;

    private PaperAttackRangeAdapter paperAdapter;

    private final Set<UUID> pendingRefresh = new LinkedHashSet<>();
    private BukkitTask refreshTask;

    public ModuleAttackRange(OCMMain plugin) {
        super(plugin, "attack-range");
        initialiseReflection();
        registerSyncListener(plugin);
        reload();
    }

    private void initialiseReflection() {
        try {
            paperAdapter = new PaperAttackRangeAdapter();
            supported = true;
        } catch (Throwable t) {
            supported = false;
            Messenger.warn("Attack range data-component API not available; module disabled. Required Paper data-component classes or methods were not detected. (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }
    }

    /**
     * Reports whether the runtime exposes the Paper AttackRange data-component API this module needs.
     * Kept public so integration tests can use the same capability signal as production code.
     */
    public static boolean isAttackRangeApiAvailable() {
        return PaperAttackRangeAdapter.isAvailable();
    }

    @Override
    public void reload() {
        if (!supported) return;
        minRange = (float) module().getDouble("min-range", 0.0);
        maxRange = (float) module().getDouble("max-range", 3.0);
        minCreative = (float) module().getDouble("min-creative-range", 0.0);
        maxCreative = (float) module().getDouble("max-creative-range", 4.0);
        hitboxMargin = (float) module().getDouble("hitbox-margin", 0.1);
        mobFactor = (float) module().getDouble("mob-factor", 1.0);
        // Values may have changed, so rewrite the component on every weapon rather than skipping items that have one.
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshInventory(player, true);
        }
    }

    @Override
    public void onModesetChange(Player player) {
        refreshInventory(player, true);
    }

    /**
     * Applies or removes the component on the item in the main hand straight away.
     * Kept for integration tests, which invoke it reflectively.
     */
    private void applyToHeld(Player player) {
        if (!supported) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) return;
        applyToItem(player, item, true);
    }

    /**
     * Brings every slot of the player's inventory, including armour and off-hand, in line with the module state.
     *
     * @param force whether to rewrite the component on weapons that already have one
     */
    private void refreshInventory(Player player, boolean force) {
        if (!supported || !player.isOnline()) return;
        final PlayerInventory inventory = player.getInventory();
        final boolean enabled = isEnabled(player);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            applyToItem(inventory.getItem(slot), enabled, force);
        }
    }

    private void applyToItem(Player player, ItemStack item, boolean force) {
        applyToItem(item, isEnabled(player), force);
    }

    private void applyToItem(ItemStack item, boolean enabled, boolean force) {
        if (item == null || item.getType() == Material.AIR) return;
        if (!enabled || !isWeapon(item.getType())) {
            stripComponent(item);
            return;
        }
        // Skip unchanged weapons so the server does not resend them to the client.
        if (!force && paperAdapter.hasComponent(item)) return;
        applyAttackRange(item);
    }

    /**
     * Queues an inventory refresh for the next tick. Several inventory changes in one tick, such as a kit
     * being handed out, collapse into a single pass.
     */
    private void scheduleRefresh(Player player) {
        if (!supported) return;
        pendingRefresh.add(player.getUniqueId());
        if (refreshTask != null) return;
        refreshTask = Bukkit.getScheduler().runTask(plugin, () -> {
            refreshTask = null;
            final UUID[] queued = pendingRefresh.toArray(new UUID[0]);
            pendingRefresh.clear();
            for (UUID uuid : queued) {
                final Player queuedPlayer = Bukkit.getPlayer(uuid);
                if (queuedPlayer != null) refreshInventory(queuedPlayer, false);
            }
        });
    }

    private boolean isWeapon(Material material) {
        final String name = material.name().toLowerCase(Locale.ROOT);
        return Arrays.stream(WEAPONS).anyMatch(name::endsWith);
    }

    private void applyAttackRange(ItemStack item) {
        paperAdapter.apply(item, minRange, maxRange, minCreative, maxCreative, hitboxMargin, mobFactor);
    }

    private void stripComponent(ItemStack item) {
        if (!supported || paperAdapter == null || item == null) return;
        paperAdapter.clear(item);
    }

    private void stripInventory(Player player) {
        if (!supported) return;
        final PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            stripComponent(inventory.getItem(slot));
        }
    }

    /**
     * Registered once and never unregistered, so items are still cleaned up after the module is disabled.
     * Every handler re-checks the module state through {@link #isEnabled(org.bukkit.entity.HumanEntity)}.
     */
    private void registerSyncListener(Plugin plugin) {
        final SyncListener listener = new SyncListener();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        registerSlotChangeHook(listener, plugin);
    }

    /**
     * Paper fires PlayerInventorySlotChangeEvent for every change to a player's inventory, including items set
     * by other plugins. It is not part of the Spigot API this plugin compiles against, so it is registered
     * reflectively when present.
     */
    private void registerSlotChangeHook(Listener listener, Plugin plugin) {
        if (!supported) return;
        final Class<? extends Event> eventClass;
        final Method newItemGetter;
        try {
            eventClass = Class.forName(SLOT_CHANGE_EVENT).asSubclass(Event.class);
            newItemGetter = eventClass.getMethod("getNewItemStack");
        } catch (ClassNotFoundException | ClassCastException | NoSuchMethodException e) {
            return;
        }
        Bukkit.getPluginManager().registerEvent(eventClass, listener, EventPriority.MONITOR, (ignored, event) -> {
            if (!eventClass.isInstance(event) || !(event instanceof PlayerEvent)) return;
            final Player player = ((PlayerEvent) event).getPlayer();
            final ItemStack newItem;
            try {
                newItem = (ItemStack) newItemGetter.invoke(event);
            } catch (ReflectiveOperationException | ClassCastException e) {
                scheduleRefresh(player);
                return;
            }
            // Durability loss on every hit also fires this event; only queue work when the item needs fixing.
            if (needsChange(player, newItem)) scheduleRefresh(player);
        }, plugin, false);
    }

    private boolean needsChange(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        final boolean hasComponent = paperAdapter.hasComponent(item);
        final boolean wanted = isWeapon(item.getType()) && isEnabled(player);
        return wanted != hasComponent;
    }

    private class SyncListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            refreshInventory(event.getPlayer(), false);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRespawn(PlayerRespawnEvent event) {
            scheduleRefresh(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onWorldChange(PlayerChangedWorldEvent event) {
            refreshInventory(event.getPlayer(), false);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onHeldChange(PlayerItemHeldEvent event) {
            // The new item normally has the component already, so this changes nothing and sends nothing.
            final Player player = event.getPlayer();
            applyToItem(player, player.getInventory().getItem(event.getNewSlot()), false);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onSwap(PlayerSwapHandItemsEvent event) {
            scheduleRefresh(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPickup(EntityPickupItemEvent event) {
            if (event.getEntity() instanceof Player) scheduleRefresh((Player) event.getEntity());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onInventoryClose(InventoryCloseEvent event) {
            if (event.getPlayer() instanceof Player) scheduleRefresh((Player) event.getPlayer());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDrop(PlayerDropItemEvent event) {
            stripComponent(event.getItemDrop().getItemStack());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDeath(PlayerDeathEvent event) {
            event.getDrops().forEach(ModuleAttackRange.this::stripComponent);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            pendingRefresh.remove(event.getPlayer().getUniqueId());
            stripInventory(event.getPlayer());
        }
    }

    private static class PaperAttackRangeAdapter {
        @SuppressWarnings("unchecked")
        private static final Predicate<Object> COPY_ALL_COMPONENTS = ignored -> true;

        private final Object attackRangeType;
        private final java.lang.reflect.Method attackRangeFactory;
        private final java.lang.reflect.Method minReachSetter;
        private final java.lang.reflect.Method maxReachSetter;
        private final java.lang.reflect.Method minCreativeSetter;
        private final java.lang.reflect.Method maxCreativeSetter;
        private final java.lang.reflect.Method hitboxSetter;
        private final java.lang.reflect.Method mobFactorSetter;
        private final java.lang.reflect.Method buildMethod;
        private final java.lang.reflect.Method itemSetData;
        private final java.lang.reflect.Method itemHasData;
        private final java.lang.reflect.Method itemUnsetData;
        private final java.lang.reflect.Method itemEnsureServerConversions;
        private final java.lang.reflect.Method itemCopyDataFrom;
        private boolean warned;

        PaperAttackRangeAdapter() throws Exception {
            Class<?> dct = Class.forName("io.papermc.paper.datacomponent.DataComponentTypes");
            Class<?> ar = Class.forName("io.papermc.paper.datacomponent.item.AttackRange");
            Class<?> builder = Class.forName("io.papermc.paper.datacomponent.item.AttackRange$Builder");
            attackRangeType = dct.getField("ATTACK_RANGE").get(null);
            attackRangeFactory = ar.getMethod("attackRange");
            minReachSetter = builder.getMethod("minReach", float.class);
            maxReachSetter = builder.getMethod("maxReach", float.class);
            minCreativeSetter = builder.getMethod("minCreativeReach", float.class);
            maxCreativeSetter = builder.getMethod("maxCreativeReach", float.class);
            hitboxSetter = builder.getMethod("hitboxMargin", float.class);
            mobFactorSetter = builder.getMethod("mobFactor", float.class);
            buildMethod = builder.getMethod("build");
            Class<?> dctClass = Class.forName("io.papermc.paper.datacomponent.DataComponentType");
            itemSetData = findSetDataMethod(dctClass, ar);
            itemHasData = ItemStack.class.getMethod("hasData", dctClass);
            itemUnsetData = ItemStack.class.getMethod("unsetData", dctClass);

            Method ensureMethod = null;
            Method copyMethod = null;
            try {
                ensureMethod = ItemStack.class.getMethod("ensureServerConversions");
                copyMethod = ItemStack.class.getMethod("copyDataFrom", ItemStack.class, Predicate.class);
            } catch (NoSuchMethodException ignored) {
                // Older/newer API shape; keep as best-effort no-op.
            }
            itemEnsureServerConversions = ensureMethod;
            itemCopyDataFrom = copyMethod;
        }

        private static boolean isAvailable() {
            try {
                new PaperAttackRangeAdapter();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private Method findSetDataMethod(Class<?> dctClass, Class<?> valueClass) throws NoSuchMethodException {
            for (Method m : ItemStack.class.getMethods()) {
                if (!m.getName().equals("setData")) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 2) continue;
                // accept any data component type class
                if (!dctClass.isAssignableFrom(params[0]) && !params[0].getName().contains("DataComponentType")) continue;
                if (!params[1].isAssignableFrom(valueClass) && !valueClass.isAssignableFrom(params[1]) && !params[1].isAssignableFrom(Object.class)) continue;
                return m;
            }
            throw new NoSuchMethodException(ItemStack.class.getName() + "#setData(DataComponentType, AttackRange)");
        }

        void apply(ItemStack stack, float min, float max, float minCreative, float maxCreative, float margin, float mobFactor) {
            try {
                Object builder = attackRangeFactory.invoke(null);
                invokeSetter(minReachSetter, builder, min);
                invokeSetter(maxReachSetter, builder, max);
                invokeSetter(minCreativeSetter, builder, minCreative);
                invokeSetter(maxCreativeSetter, builder, maxCreative);
                invokeSetter(hitboxSetter, builder, margin);
                invokeSetter(mobFactorSetter, builder, mobFactor);
                Object arObj = buildMethod.invoke(builder);
                itemSetData.invoke(stack, attackRangeType, arObj);
                ensureServerConversions(stack);
            } catch (Throwable t) {
                if (!warned) {
                    Messenger.warn("Attack range component application failed; leaving item unchanged. (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
                    warned = true;
                }
            }
        }

        private void invokeSetter(Method setter, Object builder, float value) throws Exception {
            Object result = setter.invoke(builder, value);
            if (result != null && !setter.getReturnType().equals(void.class) && !setter.getReturnType().equals(Void.class)) {
                // Some Paper versions return the builder for chaining; others mutate in place.
                // We do not need to capture the returned value because all calls target the same instance.
            }
        }

        boolean hasComponent(ItemStack stack) {
            try {
                return (boolean) itemHasData.invoke(stack, attackRangeType);
            } catch (Throwable t) {
                return false;
            }
        }

        void clear(ItemStack stack) {
            try {
                if (hasComponent(stack)) {
                    itemUnsetData.invoke(stack, attackRangeType);
                    ensureServerConversions(stack);
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }

        private void ensureServerConversions(ItemStack stack) {
            if (stack == null || itemEnsureServerConversions == null || itemCopyDataFrom == null) return;
            try {
                Object converted = itemEnsureServerConversions.invoke(stack);
                if (!(converted instanceof ItemStack)) return;
                if (converted == stack) return;
                itemCopyDataFrom.invoke(stack, converted, COPY_ALL_COMPONENTS);
            } catch (Throwable ignored) {
                // no-op: best-effort sync only
            }
        }
    }
}
