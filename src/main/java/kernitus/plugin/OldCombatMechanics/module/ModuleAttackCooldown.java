/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kernitus.plugin.OldCombatMechanics.module;

import com.cryptomorin.xseries.XAttribute;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import kernitus.plugin.OldCombatMechanics.OCMMain;
import kernitus.plugin.OldCombatMechanics.utilities.reflection.Reflector;
import kernitus.plugin.OldCombatMechanics.utilities.storage.PlayerStorage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Disables the attack cooldown.
 */
public class ModuleAttackCooldown extends OCMModule {

    private static final NamespacedKey MODIFIER_KEY = new NamespacedKey("ocm", "attack_speed_multiplier");
    private boolean excludeNormalSpears;
    private boolean excludeLungeSpears;

    public ModuleAttackCooldown(OCMMain plugin) {
        super(plugin, "disable-attack-cooldown");
    }

    @Override
    public void reload() {
        excludeNormalSpears = module().getBoolean("exclude-normal-spears", true);
        excludeLungeSpears = module().getBoolean("exclude-lunge-spears", true);
        Bukkit.getOnlinePlayers().forEach(this::adjustAttackSpeed);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerJoinEvent e) {
        adjustAttackSpeed(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerGameModeChangeEvent e) {
        adjustAttackSpeed(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        adjustAttackSpeed(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent e) {
        removeAttackSpeedModifier(e.getPlayer());
    }

    private void adjustAttackSpeed(Player player) {
        adjustAttackSpeed(player, player.getInventory().getItemInMainHand());
    }

    private void adjustAttackSpeed(Player player, ItemStack iStack) {
        if (isEnabled(player) && !isAffected(iStack)) {
            final double multiplier = module().getDouble("attack-speed-multiplier", 4.0);
            setAttackSpeedMultiplier(player, multiplier);
        } else {
            removeAttackSpeedModifier(player);
        }
    }

    @Override
    public void onModesetChange(Player player) {
        adjustAttackSpeed(player);
    }

    @EventHandler
    public void onPlayerInventorySlotChange(PlayerInventorySlotChangeEvent event) {
        if (!excludeNormalSpears && !excludeLungeSpears) return;

        Player player = event.getPlayer();
        if (!isEnabled(player)) return;

        ItemStack oldItem = event.getOldItemStack();
        ItemStack newItem = event.getNewItemStack();

        boolean hasChanged = isAffected(oldItem) != isAffected(newItem);
        if (hasChanged)
            adjustAttackSpeed(player, newItem);
    }

    private boolean isAffected(ItemStack iStack) {
        if (!Reflector.versionIsNewerOrEqualTo(1, 21, 11)) return false;

        return iStack != null && (excludeNormalSpears && !hasLungeEffect(iStack) ||
                excludeLungeSpears && hasLungeEffect(iStack)) &&
                Tag.ITEMS_SPEARS.isTagged(iStack.getType());
    }

    @EventHandler
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        if (!excludeNormalSpears && !excludeLungeSpears) return;

        Player player = event.getPlayer();
        if (!isEnabled(player)) return;

        PlayerInventory inv = player.getInventory();
        ItemStack oldItem = inv.getItem(event.getPreviousSlot());
        ItemStack newItem = inv.getItem(event.getNewSlot());

        boolean hasChanged = isAffected(oldItem) != isAffected(newItem);
        if (hasChanged)
            adjustAttackSpeed(player, newItem);
    }

    public void setAttackSpeedMultiplier(Player player, double multiplier) {
        final AttributeInstance attribute = player.getAttribute(XAttribute.ATTACK_SPEED.get());
        if (attribute == null)
            return;

        attribute.getModifiers().stream()
                .filter(m -> MODIFIER_KEY.equals(m.getKey()))
                .forEach(attribute::removeModifier);

        if (multiplier == 1.0) return;

        final AttributeModifier modifier = new AttributeModifier(
                MODIFIER_KEY, multiplier,
                Operation.MULTIPLY_SCALAR_1
        );

        attribute.addModifier(modifier);

        final String modesetName = PlayerStorage.getPlayerData(player.getUniqueId())
                .getModesetForWorld(player.getWorld().getUID());
        debug(String.format("Applied attack speed multiplier %.2fx to %s in mode %s",
                multiplier, player.getName(), modesetName), player);

        player.saveData();
    }

    public void removeAttackSpeedModifier(Player player) {
        final AttributeInstance attribute = player.getAttribute(XAttribute.ATTACK_SPEED.get());
        if (attribute == null) return;

        attribute.getModifiers().stream()
                .filter(m -> MODIFIER_KEY.equals(m.getKey()))
                .forEach(attribute::removeModifier);

        final String modesetName = PlayerStorage.getPlayerData(player.getUniqueId())
                .getModesetForWorld(player.getWorld().getUID());
        debug(String.format("Removed attack speed modifier from %s in mode %s",
                player.getName(), modesetName), player);

        player.saveData();
    }

    private boolean hasLungeEffect(ItemStack iStack) {
        if (!Reflector.versionIsNewerOrEqualTo(1, 21, 11)) return false;
        if (iStack == null) return false;
        if (!iStack.hasItemMeta()) return false;

        return iStack.getItemMeta().hasEnchant(Enchantment.LUNGE);
    }
}