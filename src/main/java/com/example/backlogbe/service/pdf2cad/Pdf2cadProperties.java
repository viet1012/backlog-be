package com.example.backlogbe.service.pdf2cad;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

// =========================================================
// PDF2CAD PROXY CONFIG (pdf2cad.*)
//
// pdf2cad.base-url            : server pdf2cad (FastAPI, run_server.bat / dịch vụ), vd http://192.168.x.x:5020
// pdf2cad.connect-timeout-sec : thời gian chờ kết nối
// pdf2cad.read-timeout-sec    : chờ trả lời (bot dùng LLM, "Dạy bot" chạy OCR có thể lâu)
// pdf2cad.allowed-roles       : role được dùng màn PDF → CAD (mặc định ADMIN, giống menu FE)
// pdf2cad.auth-required       : bật kiểm tra token + role (mặc định false – tạm thời chưa xác thực)
// pdf2cad.max-upload-mb       : tổng dung lượng 1 lần tải lên (stream thẳng sang pdf2cad, không qua
//                               spring.servlet.multipart.* nên không ảnh hưởng API upload khác)
// pdf2cad.job-ttl-hours       : BE nhớ job thuộc về ai trong bấy lâu (bằng --ttl của pdf2cad, mặc định 24)
// pdf2cad.audit.enabled/dir   : nhật ký thao tác (logger "pdf2cad.audit", file JSONL, xem logback-spring.xml)
// =========================================================
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "pdf2cad")
public class Pdf2cadProperties {

	private String baseUrl = "http://localhost:5020";

	private int connectTimeoutSec = 5;

	private int readTimeoutSec = 300;

	private List<String> allowedRoles = List.of("ADMIN");

	/**
	 * true: bắt buộc token đăng nhập + role (allowed-roles). false (tạm thời, mặc định): không chặn,
	 * danh tính lấy từ token nếu có, không thì header X-Employee-Id (không xác minh) – chỉ dùng cho
	 * nhật ký và chủ job.
	 */
	private boolean authRequired = false;

	private int maxUploadMb = 200;

	private int jobTtlHours = 24;

	private Audit audit = new Audit();

	@Getter
	@Setter
	public static class Audit {

		private boolean enabled = true;

		private String dir = "logs/pdf2cad";
	}

	public long maxUploadBytes() {
		return (long) maxUploadMb * 1024 * 1024;
	}
}
