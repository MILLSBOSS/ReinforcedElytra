package me.MILLSBOSS.reinforcedElytra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.enchantment.EnchantItemEvent;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ThreadLocalRandom;
import java.nio.charset.StandardCharsets;

public class AnvilDropListener implements Listener {
    private final ReinforcedElytra plugin;
    private final NamespacedKey reinforcedKey;
    private final NamespacedKey chestplateKey;
    private final NamespacedKey elytraKey;
    private final NamespacedKey chestWearKey;
    private final NamespacedKey elytraWearKey;

    public AnvilDropListener(ReinforcedElytra plugin) {
        this.plugin = plugin;
        this.reinforcedKey = new NamespacedKey(plugin, "reinforced_elytra");
        this.chestplateKey = new NamespacedKey(plugin, "stored_chestplate");
        this.elytraKey = new NamespacedKey(plugin, "stored_elytra");
        this.chestWearKey = new NamespacedKey(plugin, "chest_wear");
        this.elytraWearKey = new NamespacedKey(plugin, "elytra_wear");
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        Player player = event.getPlayer();
        // Run a short-delay task to allow the item to land
        Bukkit.getScheduler().runTaskLater(plugin, () -> handlePossibleAnvilInteraction(item, player), 7L);
    }

    private void handlePossibleAnvilInteraction(Item itemEntity, Player player) {
        if (itemEntity == null || itemEntity.isDead() || !itemEntity.isValid()) return;
        Location loc = itemEntity.getLocation();
        Block block = loc.getBlock();
        if (!isAnvil(block.getType()) && !isAnvil(block.getRelative(0, -1, 0).getType())) {
            // Also check block below in case item center is slightly above
            return;
        }
        Location anvilLoc = isAnvil(block.getType()) ? block.getLocation() : block.getRelative(0, -1, 0).getLocation();

        // First: separation case if reinforced elytra dropped
        ItemStack stack = itemEntity.getItemStack();
        if (isReinforcedElytra(stack)) {
            separateReinforced(stack, anvilLoc);
            itemEntity.remove();
            playAnvilSound(anvilLoc);
            return;
        }

        // Otherwise, attempt to combine if a chestplate + elytra are on the anvil together
        List<Item> itemsOnAnvil = getItemsOnTopOf(anvilLoc);
        Optional<Item> chestEntityOpt = itemsOnAnvil.stream()
                .filter(it -> isChestplate(it.getItemStack()))
                .findFirst();
        Optional<Item> elytraEntityOpt = itemsOnAnvil.stream()
                .filter(it -> it.getItemStack().getType() == Material.ELYTRA && !isReinforcedElytra(it.getItemStack()))
                .findFirst();

        if (plugin.getConfig().getBoolean("enable-combined-elytra", true) && chestEntityOpt.isPresent() && elytraEntityOpt.isPresent()) {
            Item chestEntity = chestEntityOpt.get();
            Item elytraEntity = elytraEntityOpt.get();
            ItemStack chest = chestEntity.getItemStack();
            ItemStack elytra = elytraEntity.getItemStack();

            ItemStack reinforced = createReinforcedElytra(chest, elytra);

            // Remove originals
            chestEntity.remove();
            elytraEntity.remove();

            // Drop the reinforced item on the anvil
            World world = anvilLoc.getWorld();
            if (world != null) {
                Item dropped = world.dropItemNaturally(anvilLoc.clone().add(0.5, 1.0, 0.5), reinforced);
                dropped.setVelocity(new Vector(0, 0.1, 0));
            }
            playAnvilSound(anvilLoc);
        }
    }

    private boolean isChestplate(ItemStack stack) {
        if (stack == null) return false;
        Material m = stack.getType();
        return m == Material.LEATHER_CHESTPLATE || m == Material.CHAINMAIL_CHESTPLATE ||
                m == Material.IRON_CHESTPLATE || m == Material.GOLDEN_CHESTPLATE ||
                m == Material.DIAMOND_CHESTPLATE || m == Material.NETHERITE_CHESTPLATE;
    }

    private boolean isReinforcedElytra(ItemStack stack) {
        if (stack == null || stack.getType() != Material.ELYTRA) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Byte flag = pdc.get(reinforcedKey, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    private void separateReinforced(ItemStack reinforced, Location anvilLoc) {
        ItemMeta reinforcedMeta = reinforced.getItemMeta();
        if (reinforcedMeta == null) return;

        // Load originals stored in PDC
        PersistentDataContainer pdc = reinforcedMeta.getPersistentDataContainer();
        byte[] chestBytes = pdc.get(chestplateKey, PersistentDataType.BYTE_ARRAY);
        byte[] elytraBytes = pdc.get(elytraKey, PersistentDataType.BYTE_ARRAY);
        Integer chestWear = pdc.get(chestWearKey, PersistentDataType.INTEGER);
        Integer elytraWear = pdc.get(elytraWearKey, PersistentDataType.INTEGER);
        if (chestWear == null) chestWear = 0;
        if (elytraWear == null) elytraWear = 0;

        ItemStack originalChest = chestBytes != null ? deserializeItem(chestBytes) : null;
        ItemStack originalElytra = elytraBytes != null ? deserializeItem(elytraBytes) : null;

        // Apply accumulated wears back to originals
        if (originalChest != null) {
            ItemMeta chestMeta = originalChest.getItemMeta();
            if (chestMeta instanceof Damageable chestDmg) {
                int chestMax = originalChest.getType().getMaxDurability();
                int base = chestDmg.getDamage(); // original stored damage
                int appliedDamage = Math.max(0, Math.min(base + chestWear, chestMax));
                chestDmg.setDamage(appliedDamage);
                originalChest.setItemMeta(chestMeta);
            }
        }
        if (originalElytra != null) {
            ItemMeta elyMeta = originalElytra.getItemMeta();
            if (elyMeta instanceof Damageable eDmg) {
                int elytraMax = Material.ELYTRA.getMaxDurability();
                int base = eDmg.getDamage(); // original stored damage
                int appliedDamage = Math.max(0, Math.min(base + elytraWear, elytraMax));
                eDmg.setDamage(appliedDamage);
                originalElytra.setItemMeta(elyMeta);
            }
        }

        // Drop items back into the world
        World world = anvilLoc.getWorld();
        if (world == null) return;
        if (originalChest != null) {
            world.dropItemNaturally(anvilLoc.clone().add(0.5, 1.0, 0.5), originalChest);
        }
        if (originalElytra != null) {
            world.dropItemNaturally(anvilLoc.clone().add(0.5, 1.0, 0.5), originalElytra);
        }
    }

    private ItemStack createReinforcedElytra(ItemStack chestplate, ItemStack elytra) {
        ItemStack result = new ItemStack(Material.ELYTRA, 1);
        ItemMeta meta = result.getItemMeta();
        if (meta == null) return result;

        // Display name
        meta.displayName(Component.text("Reinforced Elytra", NamedTextColor.AQUA));

        // Merge enchantments
        Map<Enchantment, Integer> enchants = new HashMap<>();
        if (chestplate.getEnchantments() != null) {
            chestplate.getEnchantments().forEach((e, lvl) -> enchants.merge(e, lvl, Math::max));
        }
        if (elytra.getEnchantments() != null) {
            elytra.getEnchantments().forEach((e, lvl) -> enchants.merge(e, lvl, Math::max));
        }
        enchants.forEach((e, lvl) -> meta.addEnchant(e, lvl, true));

        // Armor attributes based on chestplate material
        double armor = armorValue(chestplate.getType());
        double toughness = toughnessValue(chestplate.getType());
        if (armor > 0) {
            AttributeModifier armorMod = buildChestModifier("reinforced_armor", armor);
            meta.addAttributeModifier(Attribute.ARMOR, armorMod);
        }
        if (toughness > 0) {
            AttributeModifier toughMod = buildChestModifier("reinforced_toughness", toughness);
            meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, toughMod);
        }
        if (chestplate.getType() == Material.NETHERITE_CHESTPLATE) {
            AttributeModifier kbMod = buildChestModifier("reinforced_kb", 0.1);
            meta.addAttributeModifier(Attribute.KNOCKBACK_RESISTANCE, kbMod);
        }

        // Mark as reinforced and store originals
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(reinforcedKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(chestplateKey, PersistentDataType.BYTE_ARRAY, serializeItem(chestplate));
        pdc.set(elytraKey, PersistentDataType.BYTE_ARRAY, serializeItem(elytra));
        // Initialize wear trackers
        pdc.set(chestWearKey, PersistentDataType.INTEGER, 0);
        pdc.set(elytraWearKey, PersistentDataType.INTEGER, 0);

        result.setItemMeta(meta);

        // Set reinforced durability to reflect both original damages
        setReinforcedDamageFromItems(result, chestplate, elytra);
        // Update lore to show both component durabilities
        updateReinforcedLore(result);
        return result;
    }

    private AttributeModifier buildChestModifier(String keyName, double amount) {
        UUID uuid = UUID.nameUUIDFromBytes((plugin.getName() + ":" + keyName).getBytes(StandardCharsets.UTF_8));
        return new AttributeModifier(uuid, keyName, amount, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.CHEST);
    }

    private void setElytraDamageByChestplateHealth(ItemStack elytraResult, ItemStack chestplate) {
        // Copy durability semantics from the chestplate directly rather than by ratio
        ItemMeta chestMeta = chestplate.getItemMeta();
        int chestDamage = 0;
        boolean chestUnbreakable = false;
        if (chestMeta != null) {
            if (chestMeta instanceof Damageable dmg) {
                chestDamage = dmg.getDamage();
            }
            chestUnbreakable = chestMeta.isUnbreakable();
        }

        // Apply to the elytra: clamp damage to elytra's max, and mirror Unbreakable flag
        int elytraMax = Material.ELYTRA.getMaxDurability();
        int targetDamage = Math.max(0, Math.min(chestDamage, elytraMax));

        ItemMeta meta = elytraResult.getItemMeta();
        if (meta instanceof Damageable dmg) {
            dmg.setDamage(targetDamage);
            meta.setUnbreakable(chestUnbreakable);
            elytraResult.setItemMeta(meta);
        }
    }

    private void setReinforcedDamageFromItems(ItemStack reinforced, ItemStack chestplate, ItemStack elytra) {
        ItemMeta meta = reinforced.getItemMeta();
        if (!(meta instanceof Damageable rDmg)) return;
        int chestDamage = 0;
        int elytraDamage = 0;
        boolean unbreakable = false;
        ItemMeta cMeta = chestplate.getItemMeta();
        if (cMeta instanceof Damageable cDmg) chestDamage = cDmg.getDamage();
        if (cMeta != null) unbreakable = cMeta.isUnbreakable();
        ItemMeta eMeta = elytra.getItemMeta();
        if (eMeta instanceof Damageable eDmg) elytraDamage = eDmg.getDamage();
        // The reinforced item should reflect damage present on the elytra if it's worse than the chestplate
        int elytraMax = Material.ELYTRA.getMaxDurability();
        int target = Math.max(Math.min(chestDamage, elytraMax), Math.min(elytraDamage, elytraMax));
        rDmg.setDamage(target);
        meta.setUnbreakable(unbreakable);
        reinforced.setItemMeta(meta);
    }

    private void recomputeDisplayedDamageFromPDC(ItemStack reinforced) {
        ItemMeta meta = reinforced.getItemMeta();
        if (!(meta instanceof Damageable rDmg)) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        byte[] chestBytes = pdc.get(chestplateKey, PersistentDataType.BYTE_ARRAY);
        byte[] elytraBytes = pdc.get(elytraKey, PersistentDataType.BYTE_ARRAY);
        Integer chestWear = pdc.get(chestWearKey, PersistentDataType.INTEGER);
        Integer elytraWear = pdc.get(elytraWearKey, PersistentDataType.INTEGER);
        if (chestWear == null) chestWear = 0;
        if (elytraWear == null) elytraWear = 0;
        ItemStack originalChest = chestBytes != null ? deserializeItem(chestBytes) : null;
        ItemStack originalElytra = elytraBytes != null ? deserializeItem(elytraBytes) : null;
        int chestDamageBase = 0;
        int elytraDamageBase = 0;
        if (originalChest != null) {
            ItemMeta cm = originalChest.getItemMeta();
            if (cm instanceof Damageable cd) chestDamageBase = cd.getDamage();
        }
        if (originalElytra != null) {
            ItemMeta em = originalElytra.getItemMeta();
            if (em instanceof Damageable ed) elytraDamageBase = ed.getDamage();
        }
        int elytraMax = Material.ELYTRA.getMaxDurability();
        int chestStream = Math.max(0, Math.min(chestDamageBase + chestWear, elytraMax));
        int elytraStream = Math.max(0, Math.min(elytraDamageBase + elytraWear, elytraMax));
        int target = Math.max(chestStream, elytraStream);
        rDmg.setDamage(target);
        reinforced.setItemMeta(meta);
        // Also refresh lore so players can see both component durabilities
        updateReinforcedLore(reinforced);
    }

    private void updateReinforcedLore(ItemStack reinforced) {
        if (reinforced == null || reinforced.getType() != Material.ELYTRA) return;
        ItemMeta meta = reinforced.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        byte[] chestBytes = pdc.get(chestplateKey, PersistentDataType.BYTE_ARRAY);
        byte[] elytraBytes = pdc.get(elytraKey, PersistentDataType.BYTE_ARRAY);
        Integer chestWear = pdc.get(chestWearKey, PersistentDataType.INTEGER);
        Integer elytraWear = pdc.get(elytraWearKey, PersistentDataType.INTEGER);
        if (chestWear == null) chestWear = 0;
        if (elytraWear == null) elytraWear = 0;

        ItemStack originalChest = chestBytes != null ? deserializeItem(chestBytes) : null;
        ItemStack originalElytra = elytraBytes != null ? deserializeItem(elytraBytes) : null;

        int chestMax = 0;
        int elytraMax = Material.ELYTRA.getMaxDurability();
        int chestDamageBase = 0;
        int elytraDamageBase = 0;

        if (originalChest != null) {
            chestMax = originalChest.getType().getMaxDurability();
            ItemMeta cm = originalChest.getItemMeta();
            if (cm instanceof Damageable cd) chestDamageBase = cd.getDamage();
        }
        if (originalElytra != null) {
            ItemMeta em = originalElytra.getItemMeta();
            if (em instanceof Damageable ed) elytraDamageBase = ed.getDamage();
        }

        int chestDamage = chestMax > 0 ? Math.max(0, Math.min(chestDamageBase + chestWear, chestMax)) : 0;
        int elytraDamage = Math.max(0, Math.min(elytraDamageBase + elytraWear, elytraMax));

        int chestRemaining = chestMax > 0 ? Math.max(0, chestMax - chestDamage) : 0;
        int elytraRemaining = Math.max(0, elytraMax - elytraDamage);

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Chestplate Durability: ", NamedTextColor.GRAY)
                .append(Component.text(chestRemaining + "/" + chestMax, NamedTextColor.WHITE)));
        lore.add(Component.text("Elytra Durability: ", NamedTextColor.GRAY)
                .append(Component.text(elytraRemaining + "/" + elytraMax, NamedTextColor.WHITE)));

        meta.lore(lore);
        reinforced.setItemMeta(meta);
    }

    @EventHandler
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        if (!isReinforcedElytra(item)) return;
        // Cancel vanilla damage and handle ourselves
        event.setCancelled(true);
        Player player = event.getPlayer();
        boolean isGliding = player.isGliding();
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Integer chestWear = pdc.get(chestWearKey, PersistentDataType.INTEGER);
        Integer elytraWear = pdc.get(elytraWearKey, PersistentDataType.INTEGER);
        if (chestWear == null) chestWear = 0;
        if (elytraWear == null) elytraWear = 0;

        // Determine Unbreaking level from the original component relevant to this damage source
        int unbreakingLevel = 0;
        byte[] chestBytes = pdc.get(chestplateKey, PersistentDataType.BYTE_ARRAY);
        byte[] elytraBytes = pdc.get(elytraKey, PersistentDataType.BYTE_ARRAY);
        if (isGliding && elytraBytes != null) {
            ItemStack originalElytra = deserializeItem(elytraBytes);
            if (originalElytra != null) {
                unbreakingLevel = originalElytra.getEnchantmentLevel(Enchantment.UNBREAKING);
            }
        } else if (!isGliding && chestBytes != null) {
            ItemStack originalChest = deserializeItem(chestBytes);
            if (originalChest != null) {
                unbreakingLevel = originalChest.getEnchantmentLevel(Enchantment.UNBREAKING);
            }
        }
        if (unbreakingLevel < 0) unbreakingLevel = 0;

        int dmg = event.getDamage();
        // Apply Unbreaking: each point of damage only applies with probability 1/(level+1)
        if (dmg > 0) {
            int applied = 0;
            int denom = unbreakingLevel + 1;
            for (int i = 0; i < dmg; i++) {
                if (ThreadLocalRandom.current().nextInt(denom) == 0) {
                    applied++;
                }
            }
            if (applied > 0) {
                if (isGliding) {
                    elytraWear += applied;
                } else {
                    chestWear += applied;
                }
            }
        }

        pdc.set(chestWearKey, PersistentDataType.INTEGER, chestWear);
        pdc.set(elytraWearKey, PersistentDataType.INTEGER, elytraWear);
        item.setItemMeta(meta);
        // Update displayed damage to reflect worse of the two streams
        recomputeDisplayedDamageFromPDC(item);
    }

    @EventHandler
    public void onItemMend(PlayerItemMendEvent event) {
        ItemStack item = event.getItem();
        if (!isReinforcedElytra(item)) return;
        // Cancel vanilla mend and apply to both components logically
        event.setCancelled(true);
        int amount = event.getRepairAmount();
        if (amount <= 0) return;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Integer chestWear = pdc.get(chestWearKey, PersistentDataType.INTEGER);
        Integer elytraWear = pdc.get(elytraWearKey, PersistentDataType.INTEGER);
        if (chestWear == null) chestWear = 0;
        if (elytraWear == null) elytraWear = 0;
        // Subtract repair from both wear streams; allow negative to represent healing original base damage
        long newChestWear = (long) chestWear - amount;
        long newElytraWear = (long) elytraWear - amount;
        // Soft clamp to int range
        chestWear = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, newChestWear));
        elytraWear = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, newElytraWear));
        pdc.set(chestWearKey, PersistentDataType.INTEGER, chestWear);
        pdc.set(elytraWearKey, PersistentDataType.INTEGER, elytraWear);
        item.setItemMeta(meta);
        // Update displayed damage after repair
        recomputeDisplayedDamageFromPDC(item);
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack left = event.getInventory().getFirstItem();
        ItemStack right = event.getInventory().getSecondItem();
        ItemStack result = event.getResult();
        if (left == null || result == null) return;
        if (!isReinforcedElytra(left)) return;

        // Block adding enchantments to a reinforced elytra via anvil (enchanted book or enchanted items)
        if (right != null) {
            boolean rightHasEnchants = !right.getEnchantments().isEmpty();
            ItemMeta rMeta = right.getItemMeta();
            if (rMeta instanceof EnchantmentStorageMeta storageMeta) {
                rightHasEnchants = rightHasEnchants || !storageMeta.getStoredEnchants().isEmpty();
            }
            if (rightHasEnchants) {
                // Disallow by clearing the result; renaming-only operations (right null) still work; repairs with unenchanted items still allowed
                event.setResult(null);
                return;
            }
        }

        // If anvil produced a repaired result, propagate healing to both components via PDC counters
        ItemMeta leftMeta = left.getItemMeta();
        ItemMeta resMeta = result.getItemMeta();
        if (!(leftMeta instanceof Damageable lDmg) || !(resMeta instanceof Damageable rDmg)) return;
        int leftDamage = lDmg.getDamage();
        int resDamage = rDmg.getDamage();
        if (resDamage >= leftDamage) return; // not a repair
        int repair = leftDamage - resDamage;
        // Update wear counters on the RESULT item so the taken item carries correct state
        PersistentDataContainer pdc = resMeta.getPersistentDataContainer();
        Integer chestWear = pdc.get(chestWearKey, PersistentDataType.INTEGER);
        Integer elytraWear = pdc.get(elytraWearKey, PersistentDataType.INTEGER);
        if (chestWear == null) chestWear = 0;
        if (elytraWear == null) elytraWear = 0;
        long newChestWear = (long) chestWear - repair;
        long newElytraWear = (long) elytraWear - repair;
        chestWear = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, newChestWear));
        elytraWear = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, newElytraWear));
        pdc.set(chestWearKey, PersistentDataType.INTEGER, chestWear);
        pdc.set(elytraWearKey, PersistentDataType.INTEGER, elytraWear);
        result.setItemMeta(resMeta);
        // Recompute damage display to remain consistent with counters
        recomputeDisplayedDamageFromPDC(result);
        event.setResult(result);
    }

    private double armorValue(Material chest) {
        return switch (chest) {
            case LEATHER_CHESTPLATE -> 3.0;
            case GOLDEN_CHESTPLATE -> 5.0;
            case CHAINMAIL_CHESTPLATE -> 5.0;
            case IRON_CHESTPLATE -> 6.0;
            case DIAMOND_CHESTPLATE -> 8.0;
            case NETHERITE_CHESTPLATE -> 8.0;
            default -> 0.0;
        };
    }

    private double toughnessValue(Material chest) {
        return switch (chest) {
            case DIAMOND_CHESTPLATE -> 2.0;
            case NETHERITE_CHESTPLATE -> 3.0;
            default -> 0.0;
        };
    }

    private boolean isAnvil(Material type) {
        return type == Material.ANVIL || type == Material.CHIPPED_ANVIL || type == Material.DAMAGED_ANVIL;
    }

    private List<Item> getItemsOnTopOf(Location anvilLoc) {
        World world = anvilLoc.getWorld();
        if (world == null) return Collections.emptyList();
        BoundingBox box = BoundingBox.of(anvilLoc.clone().add(0.5, 1.0, 0.5), 0.6, 0.6, 0.6);
        return world.getNearbyEntities(box).stream()
                .filter(e -> e instanceof Item)
                .map(e -> (Item) e)
                .collect(Collectors.toList());
    }

    private void playAnvilSound(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;
        world.playSound(loc, Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);
    }

    private byte[] serializeItem(ItemStack item) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
            oos.writeObject(item);
            return baos.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
            return new byte[0];
        }
    }

    private ItemStack deserializeItem(byte[] data) {
        if (data == null || data.length == 0) return null;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bais)) {
            Object obj = ois.readObject();
            if (obj instanceof ItemStack is) return is;
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }
}
