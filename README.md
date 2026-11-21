# KHOA AUTOMATION BOT — Meteor addon (Minecraft 1.21.11) — v2.3.1

Cột **Khoa** trong ClickGUI: `khoa-auto-miner`, `khoa-item-collector`, `khoa-litematica-builder`.
Chuột trái = bật/tắt, chuột phải = settings. Chỉ một module Khoa chạy tại một thời điểm.

- **place-delay / place-jitter** (2.3.1): đặt block chậm hơn (mặc định 8 tick ≈ 0,4 giây, ngẫu nhiên ±3) để chơi server có anti-cheat; chỉnh trong settings của module. Nên để `mode = SAFE` cho camera xoay chậm.

## Mới ở 2.3.0 (Litematica Builder)
- **auto-bridge**: chỗ cần đặt mà đi bộ không tới được thì đặt block liên tục (cầu ngang theo hướng tới đích, hoặc dựng cột lên) tới đúng chỗ rồi mới đặt block thật. Cần cobblestone/dirt (`scaffold-blocks`).
- **clean-scaffold**: đập hết block tạm đã đặt khi xong phần đó / sắp đi chỗ khác / block tạm đang chiếm chỗ cần đặt. Cầu thì lùi dần rồi đập, cột thì đập block dưới chân và rơi xuống.
- Block bị mất đột ngột: cứ ~20 giây quét lại toàn bộ và thử lại cả các ô từng thất bại, nên block thiếu sẽ được đặt lại.
- **place-fluids**: ô nước/lava nguồn trong schematic được đổ bằng xô (`water_bucket`/`lava_bucket`); block có waterlogged (lá, slab, stairs...) thì đặt block trước rồi đổ nước vào. Hết xô đầy mà có xô rỗng thì tự múc lại ở nguồn nước/lava gần đó (không múc trong vùng schematic); không có thì đi lấy xô qua rương//ah như vật liệu khác.

## Sửa lỗi 2.2.1
- **Litematica Builder không xây gì khi Litematica đang ở chế độ single layer / range** (chỉ hiện "Set layer to y = ..." liên tục): hộp layer đang hiển thị bị tính sai (quét X/Z tại tâm schematic, ngoài layer) nên bot tưởng layer rỗng rồi nhảy layer qua lại. Đã sửa trong `LitematicaBridge.visibleBox`.

## Mới ở 2.2.0

### Litematica Builder
- **Xây đúng layer Litematica đang hiển thị** (`follow-litematica-layer`): đọc `LayerRange` của Litematica
  (single layer / all above / all below / layer range, trục X/Y/Z) bằng `isPositionWithinRange`, chỉ xây phần đang hiện,
  từ thấp lên cao. Đổi layer trong Litematica thì bot đổi theo ngay. Chế độ hiển thị `ALL` thì dùng `auto-layer` như cũ.
  Xây xong layer thì tự chuyển Litematica sang layer kế (`advance-litematica-layer`, tắt nếu muốn tự đổi).
- **Tự chỉnh hướng khi đặt block** (`auto-orient`): stairs, slab trên/dưới, log/pillar (trục), furnace, chest, piston,
  observer, hopper, trapdoor, button, biển báo (16 hướng)... Bot thử mọi tổ hợp mặt tựa / điểm bấm / yaw / pitch và hỏi
  chính block (`getPlacementState`) xem ra trạng thái nào, chọn cách khớp schematic. Cửa/giường/cây cao: chỉ đặt nửa dưới.
- **Ném ender pearl lên layer đang xây** (`use-ender-pearl`): layer cao hơn chỗ đứng mà không đi bộ lên được thì bot tìm
  ô đứng có sàn thật gần block cần đặt, mô phỏng quỹ đạo ngọc để tìm góc ném, cầm ngọc, ngắm, ném. Cần ender pearl trong túi
  và máu > 8 (ngọc trừ 5 máu). Không ném được thì dựng cột như cũ.

### Mua đồ
- **/shop** làm đúng theo GUI server (xem video): `/shop` -> danh mục FOOD (nhận theo tên hoặc theo icon là đồ ăn) ->
  bấm món -> trang "Mua ..." -> chỉnh số lượng bằng các ô `+ Thêm N` -> **bấm KÍNH XANH (hàng dưới) mới mua**.
  Không bấm nhầm kính đỏ (hủy) hay các ô `+ Thêm`. Mua chưa đủ thì lặp lại. /ah cũng nhận kính xanh làm nút xác nhận.
- **Vứt đồ không cần thiết** (`auto-drop`, `min-free-slots`, `keep-items`): trước khi mua hoặc lấy đồ từ rương, nếu túi
  còn ít ô trống thì vứt các stack không cần. KHÔNG vứt: vật liệu schematic, block dựng cột, đồ ăn, ender pearl, món đang mua,
  công cụ/vũ khí/giáp, đồ enchant/đặt tên, shulker box, đồ quý (diamond, netherite, totem, elytra...), `keep-items`.

## Mới ở 2.1.0

### Litematica Builder
- **Sửa lỗi "No schematic placement selected ... enclosing box = null"**: đọc hộp bao theo 3 cách
  (getEclosingBox/getEnclosingBox -> hợp các sub-region -> quét chunk của thế giới schematic).
  Tự bật *Render Schematic* của Litematica; nếu schematic rỗng sẽ báo rõ và thử lại thay vì "xây xong" ngay.
- **auto-layer**: layer bắt đầu = Y đang đứng (+ `layer-offset`), xây xong layer thì tự nâng lên layer kế.
- **auto-pillar**: layer quá cao thì tự nhảy-đặt block dựng cột (cột đặt ở chỗ schematic để trống). Cần cobblestone/dirt trong túi (`scaffold-blocks`). Cột KHÔNG tự phá sau khi xong.
- **auto-supply**: quét vật liệu từng layer; thiếu thì
  1. tìm trong rương/thùng/trapped chest quanh đó (`storage-radius`),
  2. không có thì `/ah <tên>` -> chọn listing có giá nhỏ nhất (đọc giá trong lore, `price-regex`) -> bấm mua -> bấm nút xác nhận nếu có.
  Có `ah-max-price` để chặn mua giá cao. Chỉ đọc trang đầu của /ah.

### Sinh tồn (mọi module Khoa, nhóm Survival)
- Đói (`hunger-threshold`) -> ăn. Hết đồ ăn -> `/shop` -> tìm "cooked beef/thịt bò nướng" (hoặc mở danh mục Food) -> mua `food-amount` cái -> ăn.

### Auto Miner
- **scan-full-height**: quét từ mặt đất xuống bedrock và lên tới giới hạn độ cao (`min-y`/`max-y`), bán kính chỉ tính theo chiều ngang.
- **dig-through**: tự đào đường xuyên đá/đất tới block ở sâu (tránh dung nham, nước, bedrock, cát/sỏi rơi).

## Build
Push lên GitHub, workflow `build` chạy rồi đăng jar + `build.log` vào Releases.
Local: JDK 21 + Gradle 9.x -> `gradle build` -> `build/libs/khoa-automation-bot-2.3.1.jar`.

## Lưu ý
Mã chưa được biên dịch/chạy thử trong môi trường có Minecraft (đã kiểm tra cú pháp). Nếu build lỗi, gửi `build.log`.
GUI của /ah và /shop khác nhau theo server: nếu bot không bấm đúng, chỉnh `ah-command`, `ah-name-mode`,
`price-regex`, `confirm-keywords`, `food-keywords`, `shop-command`.
