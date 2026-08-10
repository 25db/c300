package com.example.villagertradesedit;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * VillagerTradesEdit 插件主类
 *
 * <p>Folia 1.21.1 兼容。事件监听在 Bukkit 主线程 (区域线程) 中正常工作;
 * 当需要调度任务时, 使用 {@link FoliaScheduler} 进行适配, 避免直接调用
 * 已被 Folia 弃用的 {@code Bukkit.getScheduler()}。</p>
 */
public final class VillagerTradesEditPlugin extends JavaPlugin {

    private static VillagerTradesEditPlugin instance;

    private TradeConfig tradeConfig;
    private TradeModifier tradeModifier;
    private FoliaScheduler scheduler;

    @Override
    public void onEnable() {
        instance = this;

        // 保存默认配置
        saveDefaultConfig();

        // 检测 Folia 环境
        this.scheduler = new FoliaScheduler(this);

        // 加载交易配置
        this.tradeConfig = new TradeConfig(this);
        this.tradeConfig.load();

        // 交易修改器
        this.tradeModifier = new TradeModifier(this);

        // 注册事件监听
        getServer().getPluginManager().registerEvents(new VillagerListener(this), this);

        // 注册命令
        VTECommand cmd = new VTECommand(this);
        if (getCommand("vte") != null) {
            getCommand("vte").setExecutor(cmd);
            getCommand("vte").setTabCompleter(cmd);
        }

        getLogger().info("VillagerTradesEdit 已启用! Folia 兼容: " + scheduler.isFolia());
        getLogger().info("已加载 " + tradeConfig.getTradeCount() + " 条自定义交易。");
    }

    @Override
    public void onDisable() {
        getLogger().info("VillagerTradesEdit 已禁用。");
    }

    /**
     * 重新加载配置
     */
    public void reload() {
        reloadConfig();
        this.tradeConfig.load();
    }

    public static VillagerTradesEditPlugin getInstance() {
        return instance;
    }

    public TradeConfig getTradeConfig() {
        return tradeConfig;
    }

    public TradeModifier getTradeModifier() {
        return tradeModifier;
    }

    public FoliaScheduler getScheduler() {
        return scheduler;
    }
}
