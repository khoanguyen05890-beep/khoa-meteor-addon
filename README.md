# KHOA AUTOMATION BOT — Meteor addon (Minecraft 1.21.11)

Addon cho Meteor Client. Thêm cột **Khoa** trong ClickGUI với 3 module:

| Module | Chức năng |
|---|---|
| `khoa-auto-miner` | Quét, đi tới, chọn công cụ, đào block đã chọn, nhặt đồ rơi |
| `khoa-item-collector` | Đi nhặt vật phẩm rơi (ưu tiên / bỏ qua theo danh sách) |
| `khoa-litematica-builder` | Đặt block theo schematic đang chọn trong Litematica |

Chuột trái = bật/tắt, chuột phải = mở settings. Chỉ một module Khoa chạy tại một thời điểm.
Menu riêng (phím K / Shift+click), Dashboard, Bot Manager đã bỏ — dùng ClickGUI của Meteor.

## Cài
Mods: Fabric Loader 0.18+, Meteor Client 1.21.11, file addon này. Litematica + MaLiLib là tuỳ chọn.

## Build
Push lên GitHub, workflow `build` chạy rồi đăng jar + `build.log` vào Releases.
Local: JDK 21 + Gradle 9.x → `gradle build` → `build/libs/khoa-automation-bot-2.0.0.jar`.

## Lưu ý
Mã chưa được biên dịch/chạy thử. Nếu build lỗi, gửi `build.log`.
