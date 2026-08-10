package com.example.villagertradesedit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Folia / Paper 调度器统一适配。
 *
 * <p>Folia 不再支持 {@code Bukkit.getScheduler()} 的线程任务, 而是使用:
 * <ul>
 *   <li>{@code RegionScheduler} - 在指定位置所在区域线程执行</li>
 *   <li>{@code GlobalRegionScheduler} - 全局区域线程</li>
 *   <li>{@code AsyncScheduler}   - 异步线程</li>
 *   <li>{@code EntityScheduler}  - 跟随实体所在区域</li>
 * </ul>
 * 本类在运行时通过反射检测 Folia 类是否存在来切换实现, 保证插件
 * 在 Paper 与 Folia 上均可使用。</p>
 */
public class FoliaScheduler {

    private final Plugin plugin;
    private final boolean folia;

    public FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.folia = isFoliaPresent();
    }

    private static boolean isFoliaPresent() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public boolean isFolia() {
        return folia;
    }

    /**
     * 在指定位置对应的区域线程中同步执行。
     */
    public void runAtLocation(Location loc, Runnable task) {
        if (folia) {
            Bukkit.getRegionScheduler().run(plugin, loc, t -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 在实体所在区域线程执行 (使用 EntityScheduler)。
     * 回调会在主线程或区域线程中执行, 保证可安全操作实体。
     */
    public void runForEntity(Entity entity, Runnable task) {
        if (folia) {
            entity.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 在实体所在区域线程延迟执行。
     */
    public void runForEntityDelayed(Entity entity, Runnable task, long delayTicks) {
        if (folia) {
            long delayMs = Math.max(0, delayTicks) * 50L;
            entity.getScheduler().runDelayed(plugin, t -> task.run(), null, delayMs, TimeUnit.MILLISECONDS);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    /**
     * 全局区域线程中执行。
     */
    public void runGlobal(Runnable task) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 异步执行。
     */
    public void runAsync(Runnable task) {
        if (folia) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /**
     * 全局区域延迟执行 (tick)。
     */
    public void runGlobalDelayed(long delayTicks, Consumer<Object> task) {
        if (folia) {
            long delayMs = Math.max(0, delayTicks) * 50L;
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.accept(t), delayMs, TimeUnit.MILLISECONDS);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> task.accept(null), delayTicks);
        }
    }
}
