# Medical Book Translator — Android V1

Ứng dụng Android mobile-first để nhập EPUB, phân tích cấu trúc, dịch nội dung XHTML qua một API AI tương thích OpenAI và xuất lại EPUB mà không thay đổi asset/CSS/hình ảnh không cần thiết.

## V1 hiện có
- Chọn EPUB bằng Android Storage Access Framework.
- Kiểm tra `mimetype` và `META-INF/container.xml`.
- Đọc OPF manifest và nhận diện XHTML, CSS, image assets.
- Phân tích thống kê chapter: paragraphs, figures, image references, tables.
- Cấu hình endpoint AI, model và API key bằng giao diện app; không hard-code secret.
- Dịch các block XHTML (`p`, `h1`–`h6`, `figcaption`, `caption`) qua API OpenAI-compatible.
- Prompt yêu cầu giữ HTML/XML tags, số, đơn vị, viết tắt và citation.
- Rebuild EPUB và giữ toàn bộ file không được thay thế nguyên trạng.
- `mimetype` được ghi STORED theo yêu cầu EPUB.
- Xuất file qua Android `ACTION_CREATE_DOCUMENT`.

## Kiến trúc
Android UI → EPUB Analyzer → Translation Job → OpenAI-compatible endpoint → EPUB Builder.

## Build
Mở thư mục này bằng Android Studio có Android SDK 35 và Gradle/AGP 8.7.x. Chạy `app` trên Android 8.0+ (API 26+).

Môi trường tạo artifact hiện tại không có Android SDK/Gradle distribution nên APK chưa được build tại đây. Source project đã được kiểm tra phần EPUB engine độc lập bằng `javac`.

## AI endpoint & Model Manager
App hỗ trợ OpenAI Responses API và các endpoint Chat Completions tương thích. Màn hình **Cấu hình AI** có sẵn provider/model để không phải nhập tay:
- **OpenRouter — FREE**: `openrouter/free`, `inclusionai/ling-3.0-flash-sante:free`, `nvidia/nemotron-3-ultra:free`, `qwen/qwen3.8-27b:free`, Gemma 4 Free và Ling 3.0 Flash Fin Free.
- **Google Gemini — FREE tier**: `gemini-3.8-flash`, `gemini-3.7-flash`, `gemini-3.6-flash`, `gemini-3.1-flash-lite`.
- **OpenAI**: `gpt-5.6-luna`, `gpt-5.6-terra`, `gpt-5.6-sol`, `gpt-5.6`.
- **Custom OpenAI-compatible**: nhập endpoint/model tùy ý.
- Có nút **KIỂM TRA KẾT NỐI** trước khi dịch.

Mặc định mới cho bản V1.2 là OpenRouter Free:
- Endpoint: `https://openrouter.ai/api/v1/chat/completions`
- Model: `openrouter/free`
- API key: khóa API của dịch vụ.

Nếu dùng provider tương thích OpenAI chỉ có Chat Completions, nhập trực tiếp endpoint kết thúc bằng `/chat/completions`; app sẽ giữ giao thức đó. Nếu nhập base URL kết thúc bằng `/v1`, app mặc định dùng `/responses`.

Khuyến nghị production: dùng backend proxy riêng để API key không nằm trên thiết bị.

## Lưu ý V1
- Chưa dịch chữ nằm trực tiếp bên trong ảnh/bảng PNG. Đây sẽ là Image/Table Translation module V1.1.
- Chưa có translation-memory UI và resume từng segment sau khi process bị kill; đây là V1.2.
- Dịch HTML fragment phụ thuộc việc model tuân thủ yêu cầu giữ tag. Production version nên thêm HTML validator/repair trước khi rebuild.
