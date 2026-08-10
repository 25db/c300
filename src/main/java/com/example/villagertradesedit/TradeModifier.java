package com.example.villagertradesedit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.entity.Villager;
import org.bukkit.entity.AbstractVillager;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link TradeConfig} 中的交易定义应用到村民身上。
 *
 * <p>核心 API:
 * <ul>
 *   <li>{@link AbstractVillager#getRecipes()} - 获取当前交易列表</li>
 *   <li>{@link AbstractVillager#setRecipes(List)} - 设置交易列表</li>
 *   <li>{@link Villager#getProfession()} - 获取职业</li>
 *   <li>{@link Villager#getVillagerLevel()} - 获取等级 (1-5)</li>
 * </ul>
 * 必须在区域线程 (主线程) 中调用。</p>
 */
public class TradeModifier {

    private final VillagerTradesEditPlugin plugin;

    public TradeModifier(VillagerTradesEditPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 对一个村民应用当前等级的交易。
     *
     * @param villager 村民 (已转为 Bukkit Villager 或 AbstractVillager)
     * @return 应用的交易条数; 0 表示无修改
     */
    public int applyToVillager(AbstractVillager villager) {
        if (villager == null || villager.isDead()) return 0;

        TradeConfig cfg = plugin.getTradeConfig();

        String profession;
        int level;
        if (villager instanceof Villager v) {
            profession = v.getProfession().name();
            level = v.getVillagerLevel();
        } else {
            // Wandering Trader / 其他: 无职业
            profession = "NONE";
            level = 1;
        }

        List<TradeConfig.TradeEntry> entries = cfg.getTrades(profession, level);
        if (entries.isEmpty()) {
            return 0;
        }

        List<MerchantRecipe> current = villager.getRecipes();
        List<MerchantRecipe> newRecipes = buildRecipes(entries, cfg);

        List<MerchantRecipe> result;
        switch (cfg.getMode()) {
            case APPEND -> {
                result = new ArrayList<>(current);
                result.addAll(newRecipes);
            }
            case PREPEND -> {
                result = new ArrayList<>(newRecipes);
                result.addAll(current);
            }
            case REPLACE -> {
                result = newRecipes;
            }
            default -> result = newRecipes;
        }

        villager.setRecipes(result);
        return newRecipes.size();
    }

    private List<MerchantRecipe> buildRecipes(List<TradeConfig.TradeEntry> entries, TradeConfig cfg) {
        List<MerchantRecipe> list = new ArrayList<>(entries.size());
        for (TradeConfig.TradeEntry e : entries) {
            MerchantRecipe recipe = createRecipe(e, cfg);
            if (recipe != null) list.add(recipe);
        }
        return list;
    }

    private MerchantRecipe createRecipe(TradeConfig.TradeEntry e, TradeConfig cfg) {
        ItemStack result = new ItemStack(e.getResult(), Math.max(1, e.getResultAmount()));
        MerchantRecipe recipe = new MerchantRecipe(result, 0);

        // 添加输入物品
        ItemStack ing1 = new ItemStack(e.getGive1(), Math.max(1, e.getGive1Amount()));
        recipe.addIngredient(ing1);

        if (e.getGive2() != null && e.getGive2() != Material.AIR) {
            ItemStack ing2 = new ItemStack(e.getGive2(), Math.max(1, e.getGive2Amount()));
            recipe.addIngredient(ing2);
        }

        // 使用次数
        int maxUses = (e.getMaxUses() != null) ? e.getMaxUses() : cfg.getMaxUses();
        if (maxUses <= 0) maxUses = 100000; // 实际上无限
        recipe.setMaxUses(maxUses);
        recipe.setUses(0);

        // 经验与价格倍率
        recipe.setExperienceReward(cfg.isRewardExp());
        recipe.setPriceMultiplier(cfg.getPriceMultiplier());
        // 村民升级所需经验
        recipe.setVillagerExperience(2);

        return recipe;
    }
}
