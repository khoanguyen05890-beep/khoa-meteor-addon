# KHOA AUTOMATION BOT — Meteor addon (Minecraft 1.21.11) — v2.2.0

Cột **Khoa** trong ClickGUI: `khoa-auto-miner`, `khoa-item-collector`, `khoa-litematica-builder`.
Chuột trái = bật/tắt, chuột phải = settings. Chỉ một module Khoa chạy tại một thời điểm.

## Tính năng chính

### Litematica Builder
- **Sửa lỗi "No schematic placement selected ... enclosing box = null"**: đọc hộp bao theo 3 cách
  (getEclosingBox/getEnclosingBox -> hợp các sub-region -> quét chunk của thế giới schematic).
  Tự bật *Render Schematic* của Litematica; nếu schematic rỗng sẽ báo rõ và thử lại thay vì "xây xong" ngay.
- **auto-layer**: ưu tiên layer đang hiển thị trong Litematica nếu render đúng một layer; nếu không đọc được layer hiển thị thì dùng Y đang đứng (+ `layer-offset`).
- **auto-pillar**: layer quá cao thì tự nhảy-đặt block dựng cột (cột đặt ở chỗ schematic để trống). Cần cobblestone/dirt trong túi (`scaffold-blocks`). Cột KHÔNG tự phá sau khi xong.
- **auto-supply**: quét vật liệu từng layer; trước khi lấy/mua, nếu túi không còn chỗ thì chỉ vứt các vật phẩm trong `ignore-items` (không vứt món đang cần); thiếu thì
  1. tìm trong rương/thùng/trapped chest quanh đó (`storage-radius`),
  2. không có thì `/ah <tên>` -> chọn listing có giá nhỏ nhất (đọc giá trong lore, `price-regex`) -> bấm mua -> bấm nút xác nhận nếu có.
  Có `ah-max-price` để chặn mua giá cao. Chỉ đọc trang đầu của /ah.

### Sinh tồn (mọi module Khoa, nhóm Survival)
- Đói (`hunger-threshold`) -> ăn. Hết đồ ăn -> `/shop` -> tìm đúng món theo `food-keywords` (hoặc mở danh mục Food) -> bấm món -> chỉ bấm kính xanh xác nhận (`GREEN_STAINED_GLASS_PANE`, `GREEN_STAINED_GLASS`, `LIME_STAINED_GLASS_PANE` hoặc `LIME_STAINED_GLASS`) -> mua `food-amount` cái -> ăn.

### Auto Miner
- **scan-full-height**: quét từ mặt đất xuống bedrock và lên tới giới hạn độ cao (`min-y`/`max-y`), bán kính chỉ tính theo chiều ngang.
- **dig-through**: tự đào đường xuyên đá/đất tới block ở sâu (tránh dung nham, nước, bedrock, cát/sỏi rơi).

## Build
Push lên GitHub, workflow `build` chạy rồi đăng jar + `build.log` vào Releases.
Local: JDK 21 + Gradle 9.x -> `gradle build` -> `build/libs/khoa-automation-bot-2.2.0.jar`.

## Lưu ý
Mã chưa được biên dịch/chạy thử trong môi trường có Minecraft. Nếu build lỗi, gửi `build.log`.
GUI của /ah và /shop khác nhau theo server: nếu bot không bấm đúng, chỉnh `ah-command`, `ah-name-mode`,
`price-regex`, `confirm-keywords`, `food-keywords`, `shop-command`.

## Sửa trong 2.2.0
- Auto Miner tiếp tục dùng danh sách `blocks` trong setting làm danh sách block mục tiêu.
- Litematica ưu tiên layer đơn đang hiển thị; đối chiếu đủ trạng thái block và không tự bỏ qua block khi đặt sai/không tới được.
- Khi thiếu chỗ trong túi, chỉ vứt các vật phẩm được liệt kê ở `ignore-items`; món cần lấy được giữ lại.
- `/shop` dùng quy trình hai bước: chọn đúng món trước, sau đó chỉ xác nhận bằng kính xanh. Cần chỉnh `food-keywords` cho khớp tên món trên server.
