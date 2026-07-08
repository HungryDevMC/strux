package dev.gesp.structural.minecraft.item;

import dev.gesp.structural.minecraft.hook.EconomyCharges;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.manager.StructureManager.Reinforced;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The Support Beam: a craftable item players right-click onto a block to raise
 * its load capacity (reinforcement). This is the counterplay to the siege
 * destruction mechanics — and a natural sellable (kits, perks, paid recipes).
 *
 * <pre>
 *   Recipe (shaped, default):     Use:
 *     I . .                       right-click a tracked block with a beam in hand
 *     I . .   → Support Beam ×2   → +per-item reinforcement (up to the cap), one
 *     I . .                          beam consumed; placement is cancelled
 * </pre>
 *
 * <p>The beam itself is the cost on the item path. A configured Vault charge can
 * be layered on top (e.g. for paid siege servers), but defaults to free.
 */
public final class ReinforcementItem implements Listener {

    private static final Material BASE = Material.BLAZE_ROD; // rod-like, non-placeable

    private final Plugin plugin;
    private final StructureManager structureManager;
    private final EconomyCharges economy;
    private final NamespacedKey itemKey;
    private final NamespacedKey recipeKey;

    private final double perItem;
    private final double max;
    private final double cost;
    private final int recipeYield;

    public ReinforcementItem(
            Plugin plugin,
            StructureManager structureManager,
            EconomyCharges economy,
            double perItem,
            double max,
            double cost,
            int recipeYield) {
        this.plugin = plugin;
        this.structureManager = structureManager;
        this.economy = economy;
        this.itemKey = new NamespacedKey(plugin, "support_beam");
        this.recipeKey = new NamespacedKey(plugin, "support_beam");
        this.perItem = perItem;
        this.max = max;
        this.cost = cost;
        this.recipeYield = Math.max(1, recipeYield);
    }

    /** Build a stack of support beams. */
    @SuppressWarnings("deprecation")
    public ItemStack create(int amount) {
        ItemStack item = new ItemStack(BASE, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§bSupport Beam");
        int pct = (int) Math.round(perItem * 100);
        meta.setLore(List.of(
                "§7Right-click a structure block to reinforce it.",
                "§7+§a" + pct + "%§7 load capacity per beam (max §a" + (int) Math.round((max - 1.0) * 100) + "%§7).",
                "§8Strux reinforcement"));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Register the crafting recipe (3 iron ingots in a column → support beams). */
    public void registerRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, create(recipeYield));
        recipe.shape("I", "I", "I");
        recipe.setIngredient('I', Material.IRON_INGOT);
        try {
            plugin.getServer().addRecipe(recipe);
        } catch (IllegalStateException alreadyRegistered) {
            // Recipe survives a reload in some Paper versions; ignore.
        }
    }

    /** Remove the recipe (on disable / reload). */
    public void unregisterRecipe() {
        plugin.getServer().removeRecipe(recipeKey);
    }

    public boolean isBeam(ItemStack item) {
        return item != null
                && item.getType() == BASE
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // only main-hand, once
        }
        Player player = event.getPlayer();
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (!isBeam(inHand)) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        // Never let the beam place/interact like a normal item.
        event.setCancelled(true);

        // Check eligibility BEFORE charging / consuming the beam.
        switch (structureManager.canReinforce(clicked, max)) {
            case NOT_TRACKED -> {
                player.sendMessage("§cThat block isn't part of a tracked structure. "
                        + "§7Use §e/strux scan §7on the build first.");
                return;
            }
            case IS_GROUND -> {
                player.sendMessage("§7Foundation blocks are already immovable.");
                return;
            }
            case AT_MAX -> {
                player.sendMessage("§eThat block is already fully reinforced.");
                return;
            }
            case OK -> {
                /* fall through to apply below */
            }
        }

        if (!economy.charge(player, cost, "reinforce a block")) {
            return; // couldn't pay; nothing consumed or changed
        }
        Reinforced r = structureManager.reinforce(clicked, perItem, max);
        inHand.setAmount(inHand.getAmount() - 1);
        int pct = (int) Math.round((r.multiplier() - 1.0) * 100);
        player.sendMessage("§aReinforced §7→ §a+" + pct + "%§7 capacity.");
        clicked.getWorld().playSound(clicked.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
    }
}
