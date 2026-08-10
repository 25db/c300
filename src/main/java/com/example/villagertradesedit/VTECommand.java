package com.example.villagertradesedit;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 命令处理: /vte reload | /vte apply | /vte list
 */
public class VTECommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.DARK_AQUA + "[VTE] " + ChatColor.GRAY;

    private final VillagerTradesEditPlugin plugin;

    public VTECommand(VillagerTradesEditPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (!checkPerm(sender)) return true;
                long start = System.currentTimeMillis();
                plugin.reload();
                long cost = System.currentTimeMillis() - start;
                sender.sendMessage(PREFIX + "配置已重新加载, 用时 " + cost + "ms。"
                        + " 共 " + plugin.getTradeConfig().getTradeCount() + " 条交易。"
                        + " Folia: " + plugin.getScheduler().isFolia());
            }
            case "apply" -> {
                if (!checkPerm(sender)) return true;
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(PREFIX + "该命令只能由玩家执行。");
                    return true;
                }
                applyNearby(p);
            }
            case "list" -> {
                if (!checkPerm(sender)) return true;
                listTrades(sender);
            }
            case "info" -> {
                if (!checkPerm(sender)) return true;
                sender.sendMessage(PREFIX + "Folia 兼容: " + plugin.getScheduler().isFolia());
                sender.sendMessage(PREFIX + "模式: " + plugin.getTradeConfig().getMode());
                sender.sendMessage(PREFIX + "总交易条数: " + plugin.getTradeConfig().getTradeCount());
                sender.sendMessage(PREFIX + "右键应用: " + plugin.getTradeConfig().isApplyOnInteract()
                        + " | 生成应用: " + plugin.getTradeConfig().isApplyOnSpawn());
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(PREFIX + "VillagerTradesEdit 命令:");
        s.sendMessage(ChatColor.AQUA + "/vte reload" + ChatColor.GRAY + " - 重新加载配置");
        s.sendMessage(ChatColor.AQUA + "/vte apply" + ChatColor.GRAY + " - 对 10 格内村民立即应用交易");
        s.sendMessage(ChatColor.AQUA + "/vte list" + ChatColor.GRAY + " - 列出当前所有配置交易");
        s.sendMessage(ChatColor.AQUA + "/vte info" + ChatColor.GRAY + " - 显示运行时信息");
    }

    private boolean checkPerm(CommandSender s) {
        if (!s.hasPermission("vte.admin")) {
            s.sendMessage(PREFIX + ChatColor.RED + "你没有权限执行此操作。");
            return false;
        }
        return true;
    }

    private void applyNearby(Player p) {
        List<Entity> nearby = p.getNearbyEntities(10, 10, 10);
        int total = 0;
        int villagers = 0;
        for (Entity e : nearby) {
            if (e instanceof AbstractVillager v) {
                villagers++;
                // 必须在村民所在区域线程执行
                plugin.getScheduler().runForEntity(v, () -> {
                    int n = plugin.getTradeModifier().applyToVillager(v);
                    if (n > 0) {
                        p.sendMessage(PREFIX + "已为 " + v.getType() + " 应用 " + n + " 条交易。");
                    }
                });
                total++;
            }
        }
        p.sendMessage(PREFIX + "已对 " + villagers + " 个村民应用交易 (排队中)。");
    }

    private void listTrades(CommandSender s) {
        Map<String, Map<Integer, List<TradeConfig.TradeEntry>>> all = plugin.getTradeConfig().getAll();
        if (all.isEmpty()) {
            s.sendMessage(PREFIX + "当前没有配置任何交易。");
            return;
        }
        for (Map.Entry<String, Map<Integer, List<TradeConfig.TradeEntry>>> prof : all.entrySet()) {
            s.sendMessage(ChatColor.GOLD + "=== " + prof.getKey() + " ===");
            for (Map.Entry<Integer, List<TradeConfig.TradeEntry>> lvl : prof.getValue().entrySet()) {
                s.sendMessage(ChatColor.YELLOW + "  Lv" + lvl.getKey() + ":");
                for (TradeConfig.TradeEntry e : lvl.getValue()) {
                    s.sendMessage(ChatColor.GRAY + "    - " + e);
                }
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(List.of("reload", "apply", "list", "info"));
            opts.removeIf(o -> !o.startsWith(args[0].toLowerCase()));
            return opts;
        }
        return Collections.emptyList();
    }
}
