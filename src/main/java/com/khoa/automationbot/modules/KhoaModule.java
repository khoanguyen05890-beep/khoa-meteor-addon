package com.khoa.automationbot.modules;

import com.khoa.automationbot.Config;
import com.khoa.automationbot.KhoaAddon;
import com.khoa.automationbot.Motion;
import com.khoa.automationbot.Task;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * Lớp nền: bọc một Task cũ thành module Meteor.
 * Chỉ có MỘT người chơi thật nên tại một thời điểm chỉ một module Khoa được chạy.
 */
public abstract class KhoaModule extends Module {
    public enum Mode { SAFE, NORMAL, FAST }

    private static KhoaModule running;

    protected final SettingGroup sgGeneral = settings.getDefaultGroup();

    protected final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("SAFE = chậm/xoay camera mượt, NORMAL = cân bằng, FAST = nhanh.")
        .defaultValue(Mode.NORMAL)
        .build());

    protected final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Số tick giữa các hành động.")
        .defaultValue(2)
        .min(1)
        .sliderMax(10)
        .build());

    protected Task task;
    private int resumeTimer;

    public KhoaModule(String name, String description) {
        super(KhoaAddon.CATEGORY, name, description);
    }

    protected abstract Task createTask();

    /** Chép settings của module vào Config dùng chung. */
    protected void applyConfig(Config c) {
        c.mode = mode.get().name();
        c.actionDelay = actionDelay.get();
    }

    protected boolean pausedByUser() { return false; }

    @Override
    public void onActivate() {
        if (running != null && running != this && running.isActive()) {
            warning("Module " + running.title + " đang chạy - tắt nó trước.");
            toggle();
            return;
        }
        if (mc.player == null || mc.world == null) { toggle(); return; }
        running = this;
        applyConfig(Config.get());
        task = createTask();
        task.start();
        if (task.state == Task.State.ERROR) {
            error(task.status);
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        if (task != null) task.stop();
        task = null;
        if (running == this) running = null;
        Motion.release();
    }

    @Override
    public String getInfoString() {
        return task == null || task.status == null || task.status.isEmpty() ? null : task.status;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (task == null || mc.player == null || mc.world == null) return;
        applyConfig(Config.get());

        // An toàn: tạm dừng khi sắp chết
        if (!mc.player.isAlive() || mc.player.getHealth() <= 4f) {
            task.pause();
            task.status = "Paused: low health";
            return;
        }

        switch (task.state) {
            case DONE -> { info(task.status); toggle(); return; }
            case ERROR -> { error(task.status); toggle(); return; }
            case PAUSED -> {
                // Tự thử chạy lại mỗi 1 giây (túi đồ đầy, thiếu vật liệu... sẽ tự pause lại nếu chưa giải quyết)
                if (!pausedByUser() && ++resumeTimer >= 20) { resumeTimer = 0; task.resume(); }
                return;
            }
            case IDLE -> { return; }
            default -> {}
        }

        try {
            task.tick(mc);
        } catch (Throwable t) {
            KhoaAddon.LOG.error("Lỗi trong task " + task.name(), t);
            error(t.getClass().getSimpleName() + ": " + t.getMessage());
            toggle();
        }
    }
}
