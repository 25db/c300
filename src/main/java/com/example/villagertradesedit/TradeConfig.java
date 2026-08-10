package com.example.villagertradesedit;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * 解析 config.yml 中的交易定义与全局设置。
 *
 * <p>结构: 职业 -> 等级(1-5) -> 该等级下的交易列表</p>
 */
public class TradeConfig {

    public enum Mode {
        REPLACE,
        APPEND,
        PREPEND
    }

    /** 单条交易定义 */
    public static final class TradeEntry {
        private final Material give1;
        private final int give1Amount;
        private final Material give2;       // 可为 null
        private final int give2Amount;
        private final Material result;
        private final int resultAmount;
        private final Integer maxUses;      // 可为 null, 用全局值

        public TradeEntry(Material give1, int give1Amount,
                          Material give2, int give2Amount,
                          Material result, int resultAmount,
                          Integer maxUses) {
            this.give1 = give1;
            this.give1Amount = give1Amount;
            this.give2 = give2;
            this.give2Amount = give2Amount;
            this.result = result;
            this.resultAmount = resultAmount;
            this.maxUses = maxUses;
        }

        public Material getGive1() { return give1; }
        public int getGive1Amount() { return give1Amount; }
        public Material getGive2() { return give2; }
        public int getGive2Amount() { return give2Amount; }
        public Material getResult() { return result; }
        public int getResultAmount() { return resultAmount; }
        public Integer getMaxUses() { return maxUses; }

        @Override
        public String toString() {
            String in = give1 + "x" + give1Amount;
            if (give2 != null) in += " + " + give2 + "x" + give2Amount;
            return in + " -> " + result + "x" + resultAmount;
        }
    }

    private final VillagerTradesEditPlugin plugin;

    // 全局设置
    private boolean applyOnInteract = true;
    private boolean applyOnSpawn = true;
    private Mode mode = Mode.REPLACE;
    private int maxUses = 0;
    private boolean rewardExp = true;
    private float priceMultiplier = 0.05f;

    // 职业名(字符串, 大写) -> 等级(1-5) -> 该等级的交易列表
    private final Map<String, Map<Integer, List<TradeEntry>>> tradeMap = new HashMap<>();
    private int tradeCount = 0;

    public TradeConfig(VillagerTradesEditPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        tradeMap.clear();
        tradeCount = 0;

        FileConfiguration cfg = plugin.getConfig();

        // 全局设置
        ConfigurationSection settings = cfg.getConfigurationSection("settings");
        if (settings != null) {
            this.applyOnInteract = settings.getBoolean("apply-on-interact", true);
            this.applyOnSpawn = settings.getBoolean("apply-on-spawn", true);
            this.mode = parseMode(settings.getString("mode", "REPLACE"));
            this.maxUses = settings.getInt("max-uses", 0);
            this.rewardExp = settings.getBoolean("reward-exp", true);
            this.priceMultiplier = (float) settings.getDouble("price-multiplier", 0.05);
        }

        // 交易定义
        ConfigurationSection trades = cfg.getConfigurationSection("trades");
        if (trades == null) {
            plugin.getLogger().warning("config.yml 中未找到 trades 节, 插件将不修改任何交易。");
            return;
        }

        for (String professionKey : trades.getKeys(false)) {
            ConfigurationSection profSec = trades.getConfigurationSection(professionKey);
            if (profSec == null) continue;

            Map<Integer, List<TradeEntry>> levelMap = new EnumMap<>(HashMap::new);
            for (String levelKey : profSec.getKeys(false)) {
                int level;
                try {
                    level = Integer.parseInt(levelKey);
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("非法等级: " + levelKey + " (职业 " + professionKey + ")");
                    continue;
                }
                if (level < 1 || level > 5) {
                    plugin.getLogger().warning("等级超出范围 [1,5]: " + level + " (职业 " + professionKey + ")");
                    continue;
                }

                List<Map<?, ?>> rawList = profSec.getMapList(levelKey);
                List<TradeEntry> entries = new ArrayList<>();
                for (Map<?, ?> raw : rawList) {
                    TradeEntry e = parseEntry(raw, professionKey, level);
                    if (e != null) {
                        entries.add(e);
                        tradeCount++;
                    }
                }
                levelMap.put(level, entries);
            }
            tradeMap.put(professionKey.toUpperCase(), levelMap);
        }
    }

    private TradeEntry parseEntry(Map<?, ?> raw, String profession, int level) {
        Object g1 = raw.get("give1");
        Object g1a = raw.get("give1_amount");
        Object g2 = raw.get("give2");
        Object g2a = raw.get("give2_amount");
        Object r = raw.get("result");
        Object ra = raw.get("result_amount");
        Object mu = raw.get("max_uses");

        if (g1 == null || g1a == null || r == null || ra == null) {
            plugin.getLogger().warning("职业 " + profession + " 等级 " + level
                    + " 存在缺失字段的交易项, 已跳过。");
            return null;
        }

        Material give1 = Material.matchMaterial(String.valueOf(g1));
        Material result = Material.matchMaterial(String.valueOf(r));
        if (give1 == null || result == null) {
            plugin.getLogger().warning("职业 " + profession + " 等级 " + level
                    + " 包含无效 Material, 已跳过。give1=" + g1 + " result=" + r);
            return null;
        }

        Material give2 = (g2 == null) ? null : Material.matchMaterial(String.valueOf(g2));
        if (g2 != null && give2 == null) {
            plugin.getLogger().warning("职业 " + profession + " 等级 " + level
                    + " 的 give2 无效: " + g2 + ", 已忽略第二件物品。");
        }

        int give1Amount = toInt(g1a, 1);
        int give2Amount = toInt(g2a, 1);
        int resultAmount = toInt(ra, 1);
        Integer maxUses = (mu == null) ? null : toInt(mu, 0);

        return new TradeEntry(
                give1, give1Amount,
                give2, give2Amount,
                result, resultAmount,
                maxUses
        );
    }

    private int toInt(Object o, int def) {
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private Mode parseMode(String s) {
        if (s == null) return Mode.REPLACE;
        try {
            return Mode.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Mode.REPLACE;
        }
    }

    /**
     * 获取指定职业指定等级的交易列表 (不可变)。
     */
    public List<TradeEntry> getTrades(String profession, int level) {
        Map<Integer, List<TradeEntry>> levelMap = tradeMap.get(profession.toUpperCase());
        if (levelMap == null) return Collections.emptyList();
        List<TradeEntry> list = levelMap.get(level);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    /** 该职业是否有任何交易定义 */
    public boolean hasProfession(String profession) {
        return tradeMap.containsKey(profession.toUpperCase());
    }

    public boolean isApplyOnInteract() { return applyOnInteract; }
    public boolean isApplyOnSpawn() { return applyOnSpawn; }
    public Mode getMode() { return mode; }
    public int getMaxUses() { return maxUses; }
    public boolean isRewardExp() { return rewardExp; }
    public float getPriceMultiplier() { return priceMultiplier; }
    public int getTradeCount() { return tradeCount; }

    /** 返回所有职业名(大写) */
    public Map<String, Map<Integer, List<TradeEntry>>> getAll() {
        return Collections.unmodifiableMap(tradeMap);
    }
}
