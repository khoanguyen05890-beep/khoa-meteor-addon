package com.khoa.automationbot.modules;

import com.khoa.automationbot.Config;
import com.khoa.automationbot.KhoaAddon;
import com.khoa.automationbot.Motion;
import com.khoa.automationbot.Task;
import meteordevelopment.meteorclient.events.world.TickEvent;
import com.khoa.automationbot.Hunger;
import com.khoa.automationbot.Tools;
import meteordevelopment.meteorclient.settings.*;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import java.util.ArrayList;
import java.util.List;
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

    // ---- sinh tồn: đói -> ăn; hết đồ ăn -> /shop mua thịt bò nướng ----
    protected final SettingGroup sgSurvival = settings.createGroup("Survival");

    private final Setting<Boolean> autoEat = sgSurvival.add(new BoolSetting.Builder()
        .name("auto-eat")
        .description("Tự ăn khi đói; hết đồ ăn thì mua ở /shop.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> hungerThreshold = sgSurvival.add(new IntSetting.Builder()
        .name("hunger-threshold")
        .description("Ăn khi thanh đói <= giá trị này (20 = đầy).")
        .defaultValue(14)
        .min(1)
        .sliderMax(19)
        .build());

    private final Setting<List<Item>> eatItems = sgSurvival.add(new ItemListSetting.Builder()
        .name("eat-items")
        .description("Đồ ăn ưu tiên (cũng là món sẽ mua ở /shop - món đầu tiên).")
        .defaultValue(new ArrayList<>(List.of(Items.COOKED_BEEF)))
        .build());

    private final Setting<String> shopCommand = sgSurvival.add(new StringSetting.Builder()
        .name("shop-command")
        .description("Lệnh mở shop của server (không có dấu /).")
        .defaultValue("shop")
        .build());

    private final Setting<List<String>> foodKeywords = sgSurvival.add(new StringListSetting.Builder()
        .name("food-keywords")
        .description("Từ khoá tên món trong /shop (không phân biệt hoa thường).")
        .defaultValue(new ArrayList<>(List.of("cooked beef", "steak", "thịt bò nướng", "thit bo nuong")))
        .build());

    private final Setting<Integer> foodAmount = sgSurvival.add(new IntSetting.Builder()
        .name("food-amount")
        .description("Số đồ ăn mua mỗi lần.")
        .defaultValue(16)
        .min(1)
        .sliderMax(64)
        .build());

    protected final Hunger hunger = new Hunger();
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
        c.autoEat = autoEat.get();
        c.hungerThreshold = hungerThreshold.get();
        c.eatItems = eatItems.get().stream().map(Tools::itemId).toList();
        c.shopCommand = shopCommand.get();
        c.foodKeywords = new ArrayList<>(foodKeywords.get());
        c.foodAmount = foodAmount.get();
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
        hunger.reset();
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
        hunger.reset();
        if (running == this) running = null;
        Motion.release();
    }

    @Override
    public String getInfoString() {
        if (hunger.active()) return hunger.status;
        return task == null || task.status == null || task.status.isEmpty() ? null : task.status;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (task == null || mc.player == null || mc.world == null) return;
        applyConfig(Config.get());

        // Đói: ăn / đi mua đồ ăn trước, task chính tạm nhường
        if (hunger.tick(mc)) return;

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
