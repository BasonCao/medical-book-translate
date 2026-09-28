# Medical Book Translator — Android V1.5

Ứng dụng Android mobile-first để nhập EPUB, phân tích cấu trúc, dịch nội dung XHTML qua một API AI tương thích OpenAI và xuất lại EPUB mà không thay đổi asset/CSS/hình ảnh không cần thiết.

## V1.5 hiện có
- Chọn EPUB bằng Android Storage Access Framework.
- Kiểm tra `mimetype` và `META-INF/container.xml`.
- Đọc OPF manifest và nhận diện XHTML, CSS, image assets.
- Phân tích thống kê chapter: paragraphs, figures, image references, tables.
- Cấu hình endpoint AI, model và API key bằng giao diện app; không hard-code secret.
- Dịch theo **translation unit**: paragraph, heading, figcaption/caption, list và table-cell HTML (`th`/`td`), thay vì gửi cả XHTML cho AI.
- Batch tối đa 8 unit hoặc khoảng 12.000 ký tự/request để giảm số request và chi phí free-tier.
- Lưu từng unit ngay sau khi dịch thành công; có `progress.json` + thư mục `units/` để resume sau khi app bị đóng, mất mạng hoặc hết quota.
- Sau mỗi batch tạo `translated-current.epub`, nên phần đã dịch không bị mất.
- Nếu toàn bộ provider thất bại, trạng thái chuyển sang **PAUSED**, không restart từ đầu; bấm **Dịch / Tiếp tục** để chạy tiếp.
- Nếu nhập một EPUB đã dịch một phần, heuristic tiếng Việt sẽ tránh dịch lại phần đã có tiếng Việt.
- Giữ nguyên EPUB asset/CSS/image/ID/class không liên quan; chỉ thay nội dung translation unit.
- Prompt yêu cầu giữ HTML/XML tags, số, đơn vị, viết tắt và citation.
- Rebuild EPUB sau mỗi batch và giữ toàn bộ file không được thay thế nguyên trạng.
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

## Lưu ý V1.5
- Chữ nằm trực tiếp trong ảnh/bảng PNG vẫn giữ nguyên ảnh gốc; module OCR/image-table translation sẽ là bước tiếp theo.
- Translation queue hiện lưu local trên Android, không cần backend và không hard-code API key.
- Các unit đã dịch được khóa theo `sourceHash`; đổi source sẽ tạo unit mới, tránh áp nhầm bản dịch vào nội dung khác.
- V1.5 ưu tiên độ bền/resume và tiết kiệm request. Production version tiếp theo nên bổ sung HTML validator/repair, terminology memory UI và medical QA tự động.


## V1.7 — Free AI Pool

- Default mode is **FREE AI POOL**: OpenRouter Free → Gemini Free.
- The app automatically skips a provider after quota/rate-limit errors and continues with the next eligible provider.
- Paid providers are not used automatically in Free Pool mode. OpenAI/DeepSeek/Mistral/custom can be enabled explicitly as paid fallback.
- OpenRouter currently advertises free-model API access with a 50 requests/day limit; Gemini API documents a Free tier for selected models, with limits varying by project/model. These limits can change, so the app treats provider errors as the source of truth for routing.
