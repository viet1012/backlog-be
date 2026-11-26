package com.example.backlogbe.assistant.core.audit.admin;

import com.example.backlogbe.assistant.core.audit.admin.dto.AssistantLogStats;
import com.example.backlogbe.assistant.core.audit.admin.dto.AssistantLogSummary;
import com.example.backlogbe.auth.Perm;
import com.example.backlogbe.auth.RequirePerm;
import com.example.backlogbe.dto.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

// =========================================================
// ADMIN – AUDIT LOG TRỢ LÝ AI (đọc file JSONL, không DB).
// Quyền: ASSISTANT_LOG – list / stats / detail = V, export = X (@RequirePerm, token Authorization: Bearer).
//
// GET /api/admin/assistant-logs           -> PageResponse<AssistantLogSummary>
// GET /api/admin/assistant-logs/{rid}     -> bản ghi JSON đầy đủ
// GET /api/admin/assistant-logs/stats     -> AssistantLogStats
// GET /api/admin/assistant-logs/export    -> .xlsx
//
// Bộ lọc chung: from, to (yyyy-MM-dd), userId, q, ok, source (chat|eval), minMs – xem AssistantLogFilter.
// list / export: sort (timestamp|totalMs), order (asc|desc), mặc định timestamp desc; list: page (từ 0), size (≤ 200).
// =========================================================
@RestController
@RequestMapping("/api/admin/assistant-logs")
@RequiredArgsConstructor
@RequirePerm(feature = Perm.ASSISTANT_LOG, action = Perm.VIEW)
public class AssistantLogAdminController {

	private static final String EXCEL_CONTENT_TYPE =
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

	private final AssistantLogQueryService queryService;
	private final AssistantLogExcelService excelService;

	@GetMapping
	public ResponseEntity<PageResponse<AssistantLogSummary>> list(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) String userId,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) Boolean ok,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) Long minMs,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String order,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size
	) {

		AssistantLogFilter filter = AssistantLogFilter.of(from, to, userId, q, ok, source, minMs);

		return ResponseEntity.ok(queryService.list(filter, sort, order, page, size));
	}

	@GetMapping("/stats")
	public ResponseEntity<AssistantLogStats> stats(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) String userId,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) Boolean ok,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) Long minMs
	) {

		return ResponseEntity.ok(
				queryService.stats(AssistantLogFilter.of(from, to, userId, q, ok, source, minMs))
		);
	}

	@GetMapping("/export")
	@RequirePerm(feature = Perm.ASSISTANT_LOG, action = Perm.EXPORT_IMPORT)
	public ResponseEntity<StreamingResponseBody> export(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) String userId,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) Boolean ok,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) Long minMs,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String order
	) {

		AssistantLogFilter filter = AssistantLogFilter.of(from, to, userId, q, ok, source, minMs);

		// Đọc + lọc trước: lỗi tham số / đọc file trả JSON lỗi thay vì file .xlsx hỏng
		AssistantLogQueryService.ExportPlan plan = queryService.planExport(filter, sort, order);

		String fileName = "assistant-logs_"
				+ filter.from().format(FILE_DATE) + "-" + filter.to().format(FILE_DATE) + "_"
				+ LocalDate.now().format(FILE_DATE) + ".xlsx";

		StreamingResponseBody body = outputStream -> excelService.export(outputStream, plan);

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
				.header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
				.contentType(MediaType.parseMediaType(EXCEL_CONTENT_TYPE))
				.body(body);
	}

	// Khai báo sau /stats, /export: Spring ưu tiên path cố định hơn biến {rid}
	@GetMapping("/{rid}")
	public ResponseEntity<JsonNode> detail(
			@PathVariable String rid
	) {

		return ResponseEntity.ok(queryService.detail(rid));
	}
}
