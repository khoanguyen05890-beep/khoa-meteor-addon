# KHOA AUTOMATION BOT — Meteor addon (Minecraft 1.21.11) — v2.1.0

Cột **Khoa** trong ClickGUI: `khoa-auto-miner`, `khoa-item-collector`, `khoa-litematica-builder`.
Chuột trái = bật/tắt, chuột phải = settings. Chỉ một module Khoa chạy tại một thời điểm.

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
Local: JDK 21 + Gradle 9.x -> `gradle build` -> `build/libs/khoa-automation-bot-2.1.0.jar`.

## Lưu ý
Mã chưa được biên dịch/chạy thử trong môi trường có Minecraft. Nếu build lỗi, gửi `build.log`.
GUI của /ah và /shop khác nhau theo server: nếu bot không bấm đúng, chỉnh `ah-command`, `ah-name-mode`,
`price-regex`, `confirm-keywords`, `food-keywords`, `shop-command`.
