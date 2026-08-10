package com.example.villagertradesedit;

import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.entity.VillagerReplenishTradeEvent;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 监听村民相关事件, 在合适时机应用自定义交易。
 *
 * <p>注意: Folia 中事件触发线程为该实体所在区域线程 (与 Paper 主线程对应),
 * 因此直接调用 {@link AbstractVillager} 的方法都是线程安全的, 无需切换线程。</p>
 *
 * <p>{@link VillagerAcquireTradeEvent} / {@link VillagerReplenishTradeEvent}
 * 这些事件在 Folia 中依然按区域分发, 我们用 MONITOR 级别仅观察, 实际修改放在
 * 玩家右键与生成事件中, 避免与原版逻辑冲突。</p>
 */
public class VillagerListener implements Listener {

    private final VillagerTradesEditPlugin plugin;

    public VillagerListener(VillagerTradesEditPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 玩家右键村民: 在打开交易界面之前覆盖交易列表。
     *
     * <p>该事件在区域线程触发, 可直接操作实体。设置 result 为 DENY 不影响我们
     * 已经修改的 recipes, 只会阻止原版默认 GUI 弹出 -- 因此不要 DENY。</p>
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!plugin.getTradeConfig().isApplyOnInteract()) return;

        Entity entity = event.getRightClicked();
        if (!(entity instanceof AbstractVillager villager)) return;
        if (villager.isDead()) return;

        // 防止反复处理: 用村民的 UUID + 当前等级标识
        apply(villager);
    }

    /**
     * 村民生成: 应用一次交易。
     *
     * <p>Folia 中 CreatureSpawnEvent 在区域线程触发, 可安全操作实体。
     * 不过村民生成时职业/等级可能尚未稳定 (例如幼年村民), 因此做 1 tick 延后。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!plugin.getTradeConfig().isApplyOnSpawn()) return;
        if (!(event.getEntity() instanceof AbstractVillager villager)) return;

        // Folia: 使用 EntityScheduler 延迟 1 tick 后应用
        plugin.getScheduler().runForEntityDelayed(villager, () -> {
            if (!villager.isDead()) apply(villager);
        }, 1L);
    }

    /**
     * 监听村民获取新交易 (升级 / 解锁), 实时刷新一次, 避免原版覆盖我们的定义。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAcquireTrade(VillagerAcquireTradeEvent event) {
        if (!plugin.getTradeConfig().isApplyOnSpawn()) return;
        if (!(event.getEntity() instanceof AbstractVillager villager)) return;

        // 延迟 1 tick 后重新应用, 让原版先完成解锁
        plugin.getScheduler().runForEntityDelayed(villager, () -> {
            if (!villager.isDead()) apply(villager);
        }, 1L);
    }

    /**
     * 通用: 应用交易到村民。
     */
    private void apply(AbstractVillager villager) {
        try {
            // 跳过幼年村民
            if (villager instanceof Villager v && !v.isAdult()) return;

            int n = plugin.getTradeModifier().applyToVillager(villager);
            if (n > 0 && plugin.getLogger().isLoggable(java.util.logging.Level.FINE)) {
                plugin.getLogger().fine("已为村民 " + villager.getUniqueId() + " 应用 " + n + " 条交易。");
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("应用交易失败: " + ex.getMessage());
        }
    }
}
