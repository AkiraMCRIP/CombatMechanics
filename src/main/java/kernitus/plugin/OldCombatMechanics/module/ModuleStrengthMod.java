/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kernitus.plugin.OldCombatMechanics.module;

import kernitus.plugin.OldCombatMechanics.OCMMain;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

/**
 * Multiplies a player's outgoing damage while they have Strength,
 * using a separate configurable multiplier per Strength level.
 */
public class ModuleStrengthMod extends OCMModule {

    public ModuleStrengthMod(OCMMain plugin) {
        super(plugin, "old-strength-mod");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(@NotNull EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;

        final Entity entity = event.getDamager();
        if (!(entity instanceof Player)) return;
        final Player damager = (Player) entity;

        if (event.getCause() == EntityDamageEvent.DamageCause.THORNS) return;
        if (!damager.hasPotionEffect(PotionEffectType.STRENGTH)) return;

        int level = 0;
        for (PotionEffect effect : damager.getActivePotionEffects()) {
            if (!effect.getType().equals(PotionEffectType.STRENGTH)) continue;
            level = effect.getAmplifier() + 1;
            break;
        }
        debug("Strength potion detected at level: " + level, damager);

        final double originalDamage = event.getDamage();
        final double multiplier;
        switch (level) {
            case 1:
                multiplier = module().getDouble("level-1", 1.3);
                debug("Applied STR level 1 multiplier: " + multiplier, damager);
                break;
            case 2:
                multiplier = module().getDouble("level-2", 1.6);
                debug("Applied STR level 2 multiplier: " + multiplier, damager);
                break;
            default:
                multiplier = module().getDouble("level-3", 1.9);
                debug("Applied STR level 3+ multiplier: " + multiplier, damager);
                break;
        }

        final double finalDamage = originalDamage * multiplier;
        if (finalDamage != originalDamage) {
            event.setDamage(finalDamage);
            debug("[DEBUG] Final damage after all modifiers: " + finalDamage, damager);
        }
    }
}
