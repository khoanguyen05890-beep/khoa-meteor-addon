# KHOAMOXE — Meteor addon (Minecraft 1.21.11) — v2.5.5

Cột **Khoa** trong ClickGUI: `khoa-auto-miner`, `khoa-auto-sand`, `khoa-item-collector`, `khoa-litematica-builder`.
Chuột trái = bật/tắt, chuột phải = settings. Chỉ một module Khoa chạy tại một thời điểm.

## Sửa lỗi 2.5.5 — KEEP_SHULKER (mua shulker/ender chest, đặt, bỏ cát vào)
- **/shop**: danh mục End Stone / Totem giờ nhận theo ICON (end_stone, totem) chứ không chỉ theo tên chữ; không tìm thấy thì báo tên các ô trong menu để chỉnh `end-category`.
- **Tìm chỗ đặt rộng hơn** (±3 ô, lệch ±1 tầng); chỗ quá chật thì tự đi tới nơi thoáng rồi đặt.
- **Đặt rương không còn phụ thuộc crosshair**: ngắm vào mặt trên block tựa rồi bấm thẳng.
- **Đập shulker/ender chest**: tự cầm công cụ nhanh nhất trong cả túi (không chỉ hotbar). Ender chest chỉ đập lấy lại khi có cúp **Silk Touch** (không có thì để lại tại chỗ, tránh mất 40 giây đập vô ích).
- Bán/cất lỗi thì báo lý do ra chat (`[khoamoxe] ...`); thử lại sau ~20 giây.

## Mới ở 2.5.4 — hạn chế đào xuống (`max-dig-down`, mặc định 1)
- Chỉ đào block không thấp hơn mốc chân quá `max-dig-down` block (1 = chỉ lớp sàn, không khoét hố sâu; 0 = không đào sàn). Mốc tự cập nhật khi leo cồn, đi tìm cát xa hoặc thoát kẹt. Cát đè lên đầu vẫn được đào để khỏi ngạt. Xẻng 3x3 chạm cạnh bên có thể lấy thêm 1 hàng dưới do server đào cả vùng.

## Mới ở 2.5.3 — Auto Sand: đào GẦN NHẤT và đào NHANH (`fast-mine`, mặc định bật)
- **Quét tức thời quanh người** (±7 ngang, -5..+4 dọc): có mục tiêu ngay, không phải chờ vòng quét lớn (~5 giây) như trước. Vòng quét xa chỉ dùng khi hết cát gần.
- **Luôn chọn cát gần nhất**; cát trong tầm với (đứng yên đào được) đào trước, chỉ đi khi hết cát trong tầm. Ưu tiên 3x3 giờ chỉ là phụ (không còn đi xa chỉ vì chỗ nhiều cát hơn).
- **Đào nhanh**: đào xong một block là sang block kế ngay trong cùng tick (không qua bước tìm/nghỉ), dùng `attackBlock` cho block mới để khỏi dính độ trễ 5 tick sau mỗi lần vỡ, xoay camera nhanh hơn (>= 90°/tick), nhặt cát gộp một lượt khi hết cát trong tầm.
- Các chỗ từng bị đánh dấu hỏng được thử lại sau ~20 giây (trước đây bị bỏ mãi mãi tới khi chống kẹt chạy).
- Trạng thái khi không thấy cát hiển thị thêm số cát tìm thấy để dễ biết bot đang kẹt ở đâu.

## Mới ở 2.5.0 — Auto Sand Bot (`khoa-auto-sand`)
- **Dò cát + đào tự động**: quét cát/cát đỏ quanh bạn (`radius`), đi tới, đào, nhặt. Cát đè lên đầu thì đào ngay để khỏi ngạt; không đào cát ngay dưới chân; tránh chỗ sát dung nham.
- **Xẻng tên có "amethyst" (`amethyst-3x3`, `shovel-keyword`)**: cầm xẻng đó và chọn chỗ đào có NHIỀU cát nhất trong vùng 3x3 (đi xa hơn một chút cho chỗ đầy cát). Khung 3x3 hiển thị theo mặt block bot đang đập.
- **Hiển thị (Render)**: *sợi chỉ* từ người chơi qua các ô đường đi tới block đang đào (`render-thread`) và *khung sáng* quanh block đang đào + vùng 3x3 (`render-box`); chỉnh màu trong nhóm Render.
- **Đầy kho** (`full-mode`, còn < `reserve-slots` ô trống và có >= 64 cát; trước hết quăng đồ thừa):
  - `SELL_ORDER`: `/order sand` -> chọn đơn **giá cao nhất** -> shift-click bỏ cát vào -> đóng GUI (ESC) -> bấm kính xanh xác nhận.
  - `KEEP_SHULKER` (giữ cát): thiếu thì `/shop` -> danh mục End Stone -> shulker -> xác nhận, rồi ender chest -> xác nhận; đặt ender chest, đặt shulker, mở shulker, bỏ hết cát vào, **đập shulker (auto tool)**, nhặt lại, mở ender chest, bỏ shulker vào, rồi đập ender chest lấy lại (`pickup-ender-chest`).
- **Đói**: `/shop` -> bấm danh mục thịt bò -> **cà rốt vàng** -> `+64` -> xác nhận -> ăn (`food-item`, `food-buy-amount`). Hết chỗ trống thì quăng bớt đồ thừa rồi mua.
- **Xẻng sắp hỏng** (`auto-repair`, `repair-percent`): `/shop` -> danh mục totem -> exp -> `+64` -> xác nhận; đưa xẻng sang tay phụ, cầm chai exp, nhìn xuống đất ném tới khi xẻng gần đầy độ bền (cần **Mending**), rồi trả xẻng về chỗ cũ. Xẻng không có Mending thì bot báo và không sửa.
- **Đào liên tục, quét xa** (2.5.1): `radius` tới 128 block ngang (chỉ phần chunk đã tải) và `scan-height` ±16 theo chiều cao; quét liên tục không nghỉ. Hết cát gần thì bot tự đi tìm xa hơn theo một hướng rồi quét lại.
- **Chống kẹt**: nhiều lần không tới được cát thì bot đào xuyên tới chỗ thoáng (thấy trời); không được thì nhảy + đặt block dưới chân leo lên (đập block chắn trên đầu nếu có). Block leo ưu tiên block rác, hết thì dùng cát.
- **Tránh lava / vực** (2.5.2, áp dụng cho mọi module dùng đường đi): không đi vào ô sát dung nham (khối 3x3x3 quanh ô đứng, kể cả ô bên dưới) mà tìm đường vòng; ô sát mép vực (rơi sâu hơn 3 block) bị phạt nặng nên đường đi tránh xa mép. Đang đi mà dung nham mới chảy tới trước mặt thì dừng và tính lại đường.
- **Vứt đồ không liên quan liên tục** (cobblestone, dirt... không còn được giữ); chai exp, đồ ăn, công cụ, shulker/ender chest được giữ.
- Tên danh mục / món trong `/shop` là từ khoá chỉnh được (`end-category`, `exp-category`, `exp-keywords`) nếu server đặt tên khác.

## Mới ở 2.4.0 (Litematica Builder)
- **temp-support**: block lơ lửng không có chỗ tựa thì bot tự đặt block tựa tạm (dây block tạm nối từ chỗ đã có tựa tới đích, tối đa 5 bước, chỉ đặt ở ô schematic để trống), đặt block thật rồi đập block tạm. Không còn bỏ cả layer vì "chờ điểm tựa".
- **break-wrong-blocks**: chỗ cần đặt đang có block sai loại thì đập đi rồi đặt đúng (không đập rương/lò/block chứa đồ, bedrock, chỗ sát dung nham).
- **Chọn mục tiêu thông minh hơn (nhanh hơn)**: đặt hết những chỗ trong tầm với trước rồi mới đi, thấp lên cao, gần nhất, giữ mục tiêu đang làm dở (không chạy qua chạy lại). `place-delay` mặc định 5 tick, `place-jitter` 2 (setting cũ đã lưu thì tự chỉnh lại).

## Sửa lỗi 2.3.2 (Litematica Builder)
- **Đổ lava/nước sai chỗ, đổ trước khi có block chặn** (`fluid-walls-first`, mặc định bật): chỉ đổ một ô nước/lava khi mọi block đặc kề nó (6 hướng) trong schematic đã được đặt xong. Block chặn còn thiếu được đặt TRƯỚC, kể cả nằm ngoài layer Litematica đang hiển thị. Ô nào có block chặn không đặt được thì không đổ.
- **Đứng xa / đặt xuyên tường nhìn như hack**: bot chọn điểm ngắm có tia nhìn thật trúng đúng mặt block tựa (không bị vật cản che), xoay camera tới đó, rồi chỉ bấm khi crosshair thật đang chỉ đúng chỗ và bấm đúng điểm crosshair chỉ. Điểm bấm lệch ngẫu nhiên nhẹ trên mặt block. Xô nước/lava cũng chỉ đổ khi crosshair chỉ đúng ô cần đổ.
- **place-reach** (mặc định 3.0, vanilla 4.5): bot đi tới gần hơn trước khi đặt/đổ. Nên để `mode = SAFE` cho camera xoay chậm.

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
Local: JDK 21 + Gradle 9.x -> `gradle build` -> `build/libs/khoamoxe-2.3.1.jar`.

## Lưu ý
Mã chưa được biên dịch/chạy thử trong môi trường có Minecraft (đã kiểm tra cú pháp). Nếu build lỗi, gửi `build.log`.
GUI của /ah và /shop khác nhau theo server: nếu bot không bấm đúng, chỉnh `ah-command`, `ah-name-mode`,
`price-regex`, `confirm-keywords`, `food-keywords`, `shop-command`.
