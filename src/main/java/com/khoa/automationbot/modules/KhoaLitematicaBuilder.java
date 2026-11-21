package com.khoa.automationbot.modules;

import com.khoa.automationbot.LitematicaBuilder;
import com.khoa.automationbot.Task;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.misc.Keybind;

/**
 * Xây theo schematic đang chọn trong Litematica (cần cài Litematica + MaLiLib).
 * Prev/Next layer và Re-analyze gán bằng phím trong settings.
 */
public class KhoaLitematicaBuilder extends KhoaModule {
    public enum BuildMode { ALL, LAYER }

    private final Setting<BuildMode> buildMode = sgGeneral.add(new EnumSetting.Builder<BuildMode>()
        .name("build-mode")
        .description("ALL = tự xây toàn bộ, LAYER = xong mỗi layer thì dừng chờ Next Layer.")
        .defaultValue(BuildMode.ALL)
        .build());

    private final Setting<Integer> startLayer = sgGeneral.add(new IntSetting.Builder()
        .name("start-layer")
        .description("Layer bắt đầu (1 = thấp nhất).")
        .defaultValue(1)
        .min(1)
        .sliderMax(64)
        .build());

    @SuppressWarnings("unused")
    private final Setting<Keybind> nextLayerKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("next-layer-key")
        .description("Phím sang layer kế tiếp.")
        .defaultValue(Keybind.none())
        .action(() -> { if (task instanceof LitematicaBuilder b) b.nextLayer(); })
        .build());

    @SuppressWarnings("unused")
    private final Setting<Keybind> prevLayerKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("prev-layer-key")
        .description("Phím về layer trước.")
        .defaultValue(Keybind.none())
        .action(() -> { if (task instanceof LitematicaBuilder b) b.prevLayer(); })
        .build());

    @SuppressWarnings("unused")
    private final Setting<Keybind> reanalyzeKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("reanalyze-key")
        .description("Phím phân tích lại schematic.")
        .defaultValue(Keybind.none())
        .action(() -> { if (task instanceof LitematicaBuilder b) b.reanalyze(); })
        .build());

    public KhoaLitematicaBuilder() {
        super("khoa-litematica-builder", "Tự đặt block theo schematic đang chọn trong Litematica.");
    }

    @Override
    protected Task createTask() {
        LitematicaBuilder b = new LitematicaBuilder();
        b.mode = buildMode.get() == BuildMode.LAYER ? LitematicaBuilder.Mode.LAYER : LitematicaBuilder.Mode.ALL;
        b.layer = startLayer.get() - 1;
        return b;
    }

    @Override
    protected boolean pausedByUser() {
        return task instanceof LitematicaBuilder b && b.awaitingLayer;
    }

    @Override
    public String getInfoString() {
        if (task instanceof LitematicaBuilder b && b.analyzedOnce) {
            return b.builtBlocks() + "/" + b.totalBlocks() + " L" + (b.layer + 1);
        }
        return super.getInfoString();
    }
}
