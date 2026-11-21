package com.khoa.automationbot.modules;

import com.khoa.automationbot.LitematicaBuilder;
import com.khoa.automationbot.Config;
import com.khoa.automationbot.Task;
import com.khoa.automationbot.Tools;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Xây theo schematic đang chọn trong Litematica (cần cài Litematica + MaLiLib).
 * Prev/Next layer và Re-analyze gán bằng phím trong settings.
 */
public class KhoaLitematicaBuilder extends KhoaModule {
    public enum BuildMode { ALL, LAYER }
    public enum NameMode { ID_UNDERSCORE, ID_SPACE, DISPLAY_NAME }

    private final SettingGroup sgLayer = settings.createGroup("Layer");
    private final SettingGroup sgSupply = settings.createGroup("Supply");

    // ---- Layer ----
    private final Setting<Boolean> followLayer = sgLayer.add(new BoolSetting.Builder()
        .name("follow-litematica-layer")
        .description("Xây ĐÚNG layer mà Litematica đang hiển thị (single / above / below / range, mọi trục). Chế độ hiển thị ALL thì dùng auto-layer.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> advanceLayer = sgLayer.add(new BoolSetting.Builder()
        .name("advance-litematica-layer")
        .description("Xây xong layer đang hiện thì tự chuyển layer của Litematica lên layer kế (tắt = chờ bạn tự đổi layer).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoOrient = sgLayer.add(new BoolSetting.Builder()
        .name("auto-orient")
        .description("Khi đặt block tự chỉnh hướng nhìn/mặt bấm cho đúng schematic (stairs, slab trên/dưới, log, furnace, piston, trapdoor...).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> usePearl = sgLayer.add(new BoolSetting.Builder()
        .name("use-ender-pearl")
        .description("Layer cao hơn chỗ đứng thì ném ender pearl lên chỗ đã có sàn gần layer đang xây (cần ender pearl trong túi, máu > 8). Không được thì dựng cột.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoLayer = sgLayer.add(new BoolSetting.Builder()
        .name("auto-layer")
        .description("Tự đặt layer bắt đầu = độ cao Y đang đứng, xây xong layer thì nâng lên layer kế.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> layerOffset = sgLayer.add(new IntSetting.Builder()
        .name("layer-offset")
        .description("Cộng thêm vào Y đang đứng khi chọn layer (-1 = layer ngay dưới chân).")
        .defaultValue(0)
        .range(-64, 64)
        .sliderRange(-5, 5)
        .build());

    private final Setting<Boolean> autoPillar = sgLayer.add(new BoolSetting.Builder()
        .name("auto-pillar")
        .description("Layer quá cao thì tự nhảy-đặt block dựng cột để lên (cột nằm ngoài phần schematic cần xây).")
        .defaultValue(true)
        .build());

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Số tick chờ giữa 2 lần đặt block (20 tick = 1 giây). Chơi server có anti-cheat thì để 8-15 hoặc cao hơn.")
        .defaultValue(8)
        .min(1)
        .sliderRange(2, 30)
        .build());

    private final Setting<Integer> placeJitter = sgGeneral.add(new IntSetting.Builder()
        .name("place-jitter")
        .description("Ngẫu nhiên +- số tick cho mỗi lần đặt để nhịp giống người hơn.")
        .defaultValue(3)
        .min(0)
        .sliderRange(0, 10)
        .build());

    private final Setting<Boolean> autoBridge = sgLayer.add(new BoolSetting.Builder()
        .name("auto-bridge")
        .description("Chỗ cần đặt mà đi bộ không tới được thì đặt block liên tục (cầu ngang / cột) tới đúng chỗ rồi mới đặt block thật.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> cleanScaffold = sgLayer.add(new BoolSetting.Builder()
        .name("clean-scaffold")
        .description("Đập các block tạm (cầu/cột) đã đặt sau khi xong phần đó.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> placeFluids = sgLayer.add(new BoolSetting.Builder()
        .name("place-fluids")
        .description("Đổ nước/lava theo schematic bằng xô (water_bucket/lava_bucket): block có waterlogged (vd. lá) thì đặt block trước rồi đổ nước vào. Hết xô đầy thì tự múc lại nếu có nguồn gần.")
        .defaultValue(true)
        .build());

    private final Setting<List<Item>> scaffoldItems = sgLayer.add(new ItemListSetting.Builder()
        .name("scaffold-blocks")
        .description("Block dùng để dựng cột.")
        .defaultValue(new ArrayList<>(List.of(Items.COBBLESTONE, Items.DIRT)))
        .build());

    // ---- Supply ----
    private final Setting<Boolean> autoSupply = sgSupply.add(new BoolSetting.Builder()
        .name("auto-supply")
        .description("Quét vật liệu của layer; thiếu thì lấy trong rương, không có nữa thì mua trên /ah.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> useStorage = sgSupply.add(new BoolSetting.Builder()
        .name("use-storage")
        .description("Tìm vật liệu trong rương/thùng gần đó trước.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> storageRadius = sgSupply.add(new IntSetting.Builder()
        .name("storage-radius")
        .description("Bán kính tìm rương/thùng.")
        .defaultValue(32)
        .min(4)
        .sliderMax(64)
        .build());

    private final Setting<Boolean> useAh = sgSupply.add(new BoolSetting.Builder()
        .name("use-ah")
        .description("Không có trong rương thì gõ /ah <tên> và mua cái giá nhỏ nhất.")
        .defaultValue(true)
        .build());

    private final Setting<String> ahCommand = sgSupply.add(new StringSetting.Builder()
        .name("ah-command")
        .description("Lệnh tìm trên /ah. {item} = tên vật phẩm.")
        .defaultValue("ah {item}")
        .build());

    private final Setting<NameMode> ahNameMode = sgSupply.add(new EnumSetting.Builder<NameMode>()
        .name("ah-name-mode")
        .description("Cách viết tên vật phẩm trong lệnh /ah.")
        .defaultValue(NameMode.ID_UNDERSCORE)
        .build());

    private final Setting<Double> ahMaxPrice = sgSupply.add(new DoubleSetting.Builder()
        .name("ah-max-price")
        .description("Không mua nếu giá cao hơn mức này (0 = không giới hạn).")
        .defaultValue(0)
        .min(0)
        .sliderMax(1000000)
        .build());

    private final Setting<Boolean> ahPerUnit = sgSupply.add(new BoolSetting.Builder()
        .name("ah-per-unit")
        .description("So sánh giá trên mỗi vật phẩm thay vì tổng giá của cả stack.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> ahMaxBuys = sgSupply.add(new IntSetting.Builder()
        .name("ah-max-buys")
        .description("Số lần mua tối đa mỗi đợt lấy vật liệu.")
        .defaultValue(5)
        .min(1)
        .sliderMax(20)
        .build());

    private final Setting<Integer> supplyMax = sgSupply.add(new IntSetting.Builder()
        .name("supply-max")
        .description("Số vật phẩm lấy tối đa mỗi đợt cho một loại block.")
        .defaultValue(64)
        .min(1)
        .sliderMax(512)
        .build());

    private final Setting<String> priceRegex = sgSupply.add(new StringSetting.Builder()
        .name("price-regex")
        .description("Regex đọc giá trong lore của /ah (nhóm 1 hoặc 2 là số tiền).")
        .defaultValue(Config.DEFAULT_PRICE_REGEX)
        .build());

    private final Setting<List<String>> confirmKeywords = sgSupply.add(new StringListSetting.Builder()
        .name("confirm-keywords")
        .description("Tên nút xác nhận mua trong GUI (không phân biệt hoa thường).")
        .defaultValue(new ArrayList<>(List.of("confirm", "xác nhận", "xac nhan", "accept", "đồng ý", "dong y", "purchase", "buy", "mua", "yes")))
        .build());

    private final Setting<BuildMode> buildMode = sgGeneral.add(new EnumSetting.Builder<BuildMode>()
        .name("build-mode")
        .description("ALL = tự xây toàn bộ, LAYER = xong mỗi layer thì dừng chờ Next Layer.")
        .defaultValue(BuildMode.ALL)
        .build());

    private final Setting<Integer> startLayer = sgGeneral.add(new IntSetting.Builder()
        .name("start-layer")
        .description("Layer bắt đầu khi tắt auto-layer (1 = thấp nhất).")
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
    protected void applyConfig(Config c) {
        super.applyConfig(c);
        c.followLayer = followLayer.get();
        c.advanceLayer = advanceLayer.get();
        c.autoOrient = autoOrient.get();
        c.usePearl = usePearl.get();
        c.autoLayer = autoLayer.get();
        c.layerOffset = layerOffset.get();
        c.autoPillar = autoPillar.get();
        c.placeDelay = placeDelay.get();
        c.placeJitter = placeJitter.get();
        c.autoBridge = autoBridge.get();
        c.cleanScaffold = cleanScaffold.get();
        c.placeFluids = placeFluids.get();
        c.scaffoldItems = scaffoldItems.get().stream().map(Tools::itemId).toList();
        c.autoSupply = autoSupply.get();
        c.useStorage = useStorage.get();
        c.storageRadius = storageRadius.get();
        c.useAh = useAh.get();
        c.ahCommand = ahCommand.get();
        c.ahNameMode = ahNameMode.get().name();
        c.ahMaxPrice = ahMaxPrice.get();
        c.ahPerUnit = ahPerUnit.get();
        c.ahMaxBuys = ahMaxBuys.get();
        c.supplyMax = supplyMax.get();
        c.priceRegex = priceRegex.get();
        c.confirmKeywords = new ArrayList<>(confirmKeywords.get());
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
