package com.example.backlogbe.assistant.screens._template;

import java.util.Map;

// =========================================================
// MẪU CONTEXT PROVIDER – SAO CHÉP, KHÔNG DÙNG TRỰC TIẾP (không có @Component)
//
// Quy ước context (model nhỏ đọc tốt nhất khi):
//   - Dòng đầu: tháng / phạm vi dữ liệu đang xem.
//   - Các dòng tổng hợp do CODE tính sẵn (vượt / không vượt, rủi ro…), model chỉ diễn đạt lại.
//   - Mỗi đối tượng 1 dòng "Tên: Nhãn=giá trị|Nhãn=giá trị", giá trị trống = chưa có dữ liệu.
//   - Nhãn theo lang ("vi" | "en"); KHÔNG gửi mã nội bộ (nếu lỡ lọt, khai trong codes.json để hậu xử lý thay).
//   - Số làm tròn như trên màn hình; phép cộng / trừ hiển thị tính từ số đã làm tròn.
// =========================================================
public class TemplateContextProvider {

	public String buildContext(Map<String, String> params, String lang) {

		boolean en = "en".equals(lang);
		StringBuilder sb = new StringBuilder();

		sb.append(en ? "Month shown on screen: " : "Tháng đang xem trên màn hình: ")
				.append(params.getOrDefault("month", ""))
				.append('\n');

		// TODO: gọi service sẵn có của màn hình, ghi từng dòng dữ liệu
		sb.append(en ? "Empty value = no data." : "Giá trị trống = chưa có dữ liệu.");

		return sb.toString();
	}
}
