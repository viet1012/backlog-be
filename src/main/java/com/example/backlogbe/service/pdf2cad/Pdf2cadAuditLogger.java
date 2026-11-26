package com.example.backlogbe.service.pdf2cad;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

// =========================================================
// NHẬT KÝ THAO TÁC PDF → CAD (ai làm gì, từ máy nào)
//
// Server pdf2cad chỉ thấy IP của backlog-be, nên BE tự ghi: mỗi request /api/pdf2cad/** 1 dòng JSON
// {timestamp, employeeId, ip, action, jobId, detail, status, ms} vào logger "pdf2cad.audit"
// -> appender riêng (logback-spring.xml): <pdf2cad.audit.dir>/pdf2cad-audit-yyyy-MM-dd.jsonl, giữ 90 ngày,
// KHÔNG vào log chung. Không ghi nội dung file; câu chat bot chỉ ghi độ dài.
// Không ghi các request hỏi tiến độ (GET /jobs/{id}) – mỗi giây 1 lần, không có giá trị tra cứu.
// Lỗi khi ghi không làm hỏng request.
// =========================================================
@Slf4j
@Component
@RequiredArgsConstructor
public class Pdf2cadAuditLogger {

	private static final Logger AUDIT = LoggerFactory.getLogger("pdf2cad.audit");

	private static final int MAX_DETAIL = 500;

	private final Pdf2cadProperties properties;
	private final ObjectMapper objectMapper;

	public void write(
			String employeeId,
			String ip,
			String action,
			String jobId,
			String detail,
			int status,
			long startNanos
	) {
		if (!properties.getAudit().isEnabled()) {
			return;
		}

		try {
			Map<String, Object> line = new LinkedHashMap<>();
			line.put("timestamp", OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS).toString());
			line.put("employeeId", employeeId);
			line.put("ip", ip);
			line.put("action", action);
			line.put("jobId", jobId);
			line.put("detail", detail != null && detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) + "…" : detail);
			line.put("status", status);
			line.put("ms", (System.nanoTime() - startNanos) / 1_000_000);

			AUDIT.info(objectMapper.writeValueAsString(line));
		} catch (Exception e) {
			log.warn("pdf2cad audit write failed: {}", e.getClass().getSimpleName());
		}
	}
}
