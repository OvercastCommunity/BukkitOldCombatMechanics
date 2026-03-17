/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kernitus.plugin.OldCombatMechanics.module;

import com.cryptomorin.xseries.XAttribute;
import com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent;
import kernitus.plugin.OldCombatMechanics.OCMMain;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import com.cryptomorin.xseries.XEnchantment;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reverts knockback formula to 1.8.
 * Also disables netherite knockback resistance.
 */
public class ModulePlayerKnockback extends OCMModule {

    private double knockbackHorizontal;
    private double knockbackVertical;
    private double knockbackVerticalLimit;
    private double knockbackExtraHorizontal;
    private double knockbackExtraVertical;

    private final Set<UUID> handledKnockback = new HashSet<>();
    private final Map<UUID, CapturedAttackState> capturedState = new HashMap<>();
    private final Map<UUID, CapturedCooldown> capturedCooldown = new HashMap<>();
    private BukkitTask pendingCleanupTask;
    private long pendingTickCounter;

    public ModulePlayerKnockback(OCMMain plugin) {
        super(plugin, "old-player-knockback");
        reload();
    }

    @Override
    public void reload() {
        knockbackHorizontal = module().getDouble("knockback-horizontal", 0.4);
        knockbackVertical = module().getDouble("knockback-vertical", 0.4);
        knockbackVerticalLimit = module().getDouble("knockback-vertical-limit", 0.4);
        knockbackExtraHorizontal = module().getDouble("knockback-extra-horizontal", 0.5);
        knockbackExtraVertical = module().getDouble("knockback-extra-vertical", 0.1);
    }

    @Override
    public void onModesetChange(Player player) {
        Level level = ((CraftPlayer) player).getHandle().level();
        level.paperConfig().misc.disableSprintInterruptionOnAttack = !isEnabled(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        final UUID uuid = e.getPlayer().getUniqueId();
        handledKnockback.remove(uuid);
        capturedState.remove(uuid);
        capturedCooldown.remove(uuid);
        stopCleanupTaskIfIdle();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        if (!isEnabled(attacker) || !isEnabled(victim)) return;

        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        capturedCooldown.put(attacker.getUniqueId(), new CapturedCooldown(attacker.getAttackCooldown(), pendingTickCounter + 1));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityKnockbackByEntity(EntityKnockbackByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getHitBy() instanceof Player attacker)) return;

        if (!isEnabled(attacker) || !isEnabled(victim)) return;

        if (event.getCause() != EntityKnockbackByEntityEvent.Cause.ENTITY_ATTACK) return;

        final UUID uuid = victim.getUniqueId();

        if (handledKnockback.remove(uuid)) {
            final CapturedAttackState state = capturedState.remove(uuid);
            if (state == null) return;

            final double knockbackReduction = victim.getAttribute(XAttribute.KNOCKBACK_RESISTANCE.get()).getValue();
            final double resistance = 1.0 - knockbackReduction;

            if (state.bonusKnockback > 0) {
                double x = -Math.sin(Math.toRadians(state.yaw)) * state.bonusKnockback * knockbackExtraHorizontal * resistance;
                double y = knockbackExtraVertical * resistance;
                double z = Math.cos(Math.toRadians(state.yaw)) * state.bonusKnockback * knockbackExtraHorizontal * resistance;

                event.setKnockback(new Vector(x, y, z));
            } else {
                event.setCancelled(true);
            }

            stopCleanupTaskIfIdle();
            return;
        }

        final EntityEquipment equipment = attacker.getEquipment();
        int bonusKnockback = 0;
        if (equipment != null) {
            final ItemStack heldItem = equipment.getItemInMainHand().getType() == Material.AIR
                    ? equipment.getItemInOffHand()
                    : equipment.getItemInMainHand();
            if (XEnchantment.KNOCKBACK.getEnchant() != null)
                bonusKnockback = heldItem.getEnchantmentLevel(XEnchantment.KNOCKBACK.getEnchant());
        }

        final CapturedCooldown cooldown = capturedCooldown.remove(attacker.getUniqueId());
        final float attackCooldown = cooldown != null ? cooldown.attackCooldown() : 0.0F;

        if (attacker.isSprinting() && attackCooldown > 0.848F)
            bonusKnockback++;

        final Location attackerLocation = attacker.getLocation();
        final Location victimLocation = victim.getLocation();
        final float attackerYaw = attackerLocation.getYaw();

        double d0 = attackerLocation.getX() - victimLocation.getX();
        double d1 = attackerLocation.getZ() - victimLocation.getZ();

        while (d0 * d0 + d1 * d1 < 1.0E-4D) {
            d1 = (Math.random() - Math.random()) * 0.01D;
            d0 = (Math.random() - Math.random()) * 0.01D;
        }

        final double magnitude = Math.sqrt(d0 * d0 + d1 * d1);
        final double knockbackReduction = victim.getAttribute(XAttribute.KNOCKBACK_RESISTANCE.get()).getValue();
        final double resistance = 1.0 - knockbackReduction;
        final double frictionDivisor = 2.0 - knockbackReduction;

        final Vector playerVelocity = victim.getVelocity();

        double x = (playerVelocity.getX() / frictionDivisor) - (d0 / magnitude * knockbackHorizontal * resistance);
        double y = (playerVelocity.getY() / frictionDivisor) + knockbackVertical * resistance;
        double z = (playerVelocity.getZ() / frictionDivisor) - (d1 / magnitude * knockbackHorizontal * resistance);

        if (y > knockbackVerticalLimit)
            y = knockbackVerticalLimit;

        event.setKnockback(new Vector(x - playerVelocity.getX(), y - playerVelocity.getY(), z - playerVelocity.getZ()));

        handledKnockback.add(uuid);
        capturedState.put(uuid, new CapturedAttackState(bonusKnockback, attackerYaw,pendingTickCounter + 1));
        ensureCleanupTaskRunning();
    }

    private void ensureCleanupTaskRunning() {
        if (pendingCleanupTask != null) return;
        pendingTickCounter = 0;

        pendingCleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            pendingTickCounter++;
            if (capturedState.isEmpty() && capturedCooldown.isEmpty()) {
                stopCleanupTaskIfIdle();
                return;
            }

            if (!capturedState.isEmpty()) {
                final Iterator<Map.Entry<UUID, CapturedAttackState>> it = capturedState.entrySet().iterator();
                while (it.hasNext()) {
                    final Map.Entry<UUID, CapturedAttackState> entry = it.next();
                    if (entry.getValue().expiresAtTick <= pendingTickCounter) {
                        handledKnockback.remove(entry.getKey());
                        it.remove();
                    }
                }
            }

            if (!capturedCooldown.isEmpty()) {
                capturedCooldown.entrySet().removeIf(entry -> entry.getValue().expiresAtTick <= pendingTickCounter);
            }

            stopCleanupTaskIfIdle();
        }, 1L, 1L);
    }

    private void stopCleanupTaskIfIdle() {
        if (pendingCleanupTask == null) return;
        if (!capturedState.isEmpty() || !capturedCooldown.isEmpty()) return;
        pendingCleanupTask.cancel();
        pendingCleanupTask = null;
    }

    private record CapturedAttackState(int bonusKnockback, float yaw, long expiresAtTick) {}

    private record CapturedCooldown(float attackCooldown, long expiresAtTick) {}
}
