package de.aci;

import org.bukkit.Material;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;

public final class CustomItem {
    public final String id;
    /** Display name with &-color codes / MiniMessage. */
    public String name;
    /** Base item the custom item is built on. */
    public Material material = Material.AMETHYST_SHARD;
    /**
     * Name of the texture in the resource pack (assets/aci/textures/item/&lt;texture&gt;.png),
     * or null/blank to keep the base item's own vanilla look (no model override).
     */
    public String texture;
    /**
     * Only used when {@link #material} is a potion-like item (POTION, SPLASH_POTION,
     * LINGERING_POTION, TIPPED_ARROW). Keeps the vanilla potion color/name/effect intact.
     * Null = plain water bottle look.
     */
    public PotionType potionType;
    public final List<String> lore = new ArrayList<>();
    public final List<ItemEffect> effects = new ArrayList<>();
    public final List<CustomRecipe> recipes = new ArrayList<>();

    public CustomItem(String id) {
        this.id = id;
        this.name = "&f" + id;
        this.texture = id;
    }
}
