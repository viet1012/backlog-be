package com.example.backlogbe.controller;

import com.example.backlogbe.assistant.screens.costmonitoring.CostChartForecasts;
import com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringSnapshotLoader;
import com.example.backlogbe.dto.costmonitoring.CostDetailResponse;
import com.example.backlogbe.dto.costmonitoring.CostDetailResponse.TransactionPage;
import com.example.backlogbe.dto.costmonitoring.CostMonitoringChartResponse;
import com.example.backlogbe.dto.costmonitoring.CostPlWeeksResponse;
import com.example.backlogbe.service.CostMonitoringDetailService;
import com.example.backlogbe.service.CostMonitoringDetailService.TransactionExport;
import com.example.backlogbe.service.CostMonitoringDetailService.TransactionScope;
import com.example.backlogbe.service.CostMonitoringService;
import com.example.backlogbe.service.CostMonitoringTransactionExcelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

// Chỉ đọc, không cần đăng nhập.
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/cost-monitoring")
@RequiredArgsConstructor
public class CostMonitoringController {

	private final CostMonitoringService service;
	private final CostMonitoringDetailService detailService;
	private final CostMonitoringTransactionExcelService transactionExcelService;
	private final CostMonitoringSnapshotLoader snapshotLoader;

	private static final String EXCEL_CONTENT_TYPE =
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");


	// =========================================================
	// CHART
	// =========================================================

	@GetMapping("/chart")
	public ResponseEntity<CostMonitoringChartResponse> getChart(
			// yyyy-MM; bỏ trống = tháng hiện tại
			@RequestParam(required = false) String month,
			// null hoặc Simu_W1..Simu_W5
			@RequestParam(required = false) String simuWeek,
			// true = bỏ qua cache snapshot, load lại từ DB và ghi đè cache
			@RequestParam(required = false, defaultValue = "false") boolean refresh
	) {

		// Dùng chung cache snapshot với trợ lý AI và /detail (cùng số ở 3 nơi trong TTL);
		// mỗi khung kèm "forecast" do BE tính (CostChartForecasts) để FE không tự tính banner / badge
		return ResponseEntity.ok(
				CostChartForecasts.withForecasts(
						snapshotLoader.load(
								CostMonitoringSnapshotLoader.normalizeMonth(month),
								simuWeek == null || simuWeek.isBlank() ? null : simuWeek.trim(),
								refresh
						)
				)
		);
	}


	// =========================================================
	// DETAIL 1 Ô (drawer): KPI + daily + transactions
	// =========================================================

	// vd /detail?div=PR&chart=T%26SL&month=2026-10&date=2026-10-05&page=0&size=50
	// div / chart cùng mã với /chart (CostChartDto.div / chart)
	@GetMapping("/detail")
	public ResponseEntity<CostDetailResponse> getDetail(
			// PR | MO | Guide (không phân biệt hoa thường); bắt buộc – thiếu / sai -> 400 từ service
			@RequestParam(required = false) String div,
			// OT | T&SL | RM; bắt buộc – thiếu / sai -> 400 từ service
			@RequestParam(required = false) String chart,
			// yyyy-MM hoặc yyyy.MM; bỏ trống = tháng hiện tại
			@RequestParam(required = false) String month,
			// null hoặc Simu_W1..Simu_W5 (ảnh hưởng PL / Estimate)
			@RequestParam(required = false) String simuWeek,
			// yyyy-MM-dd trong tháng: chỉ lọc transactions theo ngày này
			@RequestParam(required = false) String date,
			// 0-based
			@RequestParam(required = false) Integer page,
			// 1..200, mặc định 50
			@RequestParam(required = false) Integer size,
			// true = bỏ qua cache snapshot, load lại từ DB và ghi đè cache
			@RequestParam(required = false, defaultValue = "false") boolean refresh
	) {

		return ResponseEntity.ok(
				detailService.getDetail(div, chart, month, simuWeek, date, page, size, refresh)
		);
	}


	// =========================================================
	// TRANSACTIONS THEO KHOẢNG NGÀY (nhẹ: không chạy snapshot / SP)
	// =========================================================

	// vd /detail/transactions?div=PR&chart=T%26SL&from=2026-10-01&to=2026-10-15&page=0&size=50
	// Cùng shape với /detail.transactions; allocationK luôn null; OT -> rỗng
	@GetMapping("/detail/transactions")
	public ResponseEntity<TransactionPage> getTransactions(
			// PR | MO | Guide; bắt buộc
			@RequestParam(required = false) String div,
			// OT | T&SL | RM; bắt buộc
			@RequestParam(required = false) String chart,
			// yyyy-MM-dd; bắt buộc, from <= to, cùng 1 tháng
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			// 0-based
			@RequestParam(required = false) Integer page,
			// 1..200, mặc định 50
			@RequestParam(required = false) Integer size
	) {

		return ResponseEntity.ok(
				detailService.getTransactions(div, chart, from, to, page, size)
		);
	}

	// Cùng params (không page / size) -> .xlsx, thành tiền giảm dần, tối đa MAX_EXPORT_ROWS dòng
	@GetMapping("/detail/transactions/export")
	public ResponseEntity<StreamingResponseBody> exportTransactions(
			@RequestParam(required = false) String div,
			@RequestParam(required = false) String chart,
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to
	) {

		// Validate + đếm dòng trước khi ghi file: mọi lỗi trả 400 JSON
		TransactionExport plan = detailService.prepareTransactionExport(div, chart, from, to);
		TransactionScope scope = plan.scope();

		String fileName = "cost_transactions_"
				+ safeFilePart(scope.divCode()) + "_"
				+ safeFilePart(scope.cost()) + "_"
				+ scope.from().format(FILE_DATE) + "-"
				+ scope.to().format(FILE_DATE)
				+ ".xlsx";

		String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
				.replace("+", "%20");

		StreamingResponseBody body = outputStream -> transactionExcelService.export(outputStream, plan);

		return ResponseEntity.ok()
				.header(
						HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encodedFileName
				)
				.header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
				.contentType(MediaType.parseMediaType(EXCEL_CONTENT_TYPE))
				.body(body);
	}

	// "T&SL" -> "TSL" (chỉ giữ chữ / số)
	private static String safeFilePart(String value) {
		return value.replaceAll("[^A-Za-z0-9]", "");
	}


	// =========================================================
	// PL SIMULATION WEEK
	// =========================================================

	// Tuần Simulation có số trong F2_PL_TGT của tháng + tuần "Mới nhất" đang dùng
	@GetMapping("/pl-weeks")
	public ResponseEntity<CostPlWeeksResponse> getPlWeeks(
			// yyyy-MM; bỏ trống = tháng hiện tại
			@RequestParam(required = false) String month
	) {

		return ResponseEntity.ok(
				service.getPlWeeks(month)
		);
	}


	// =========================================================
	// MONTH
	// =========================================================

	// Các tháng có target trong F2_PL_TGT, dạng "2026.10", mới nhất trước
	@GetMapping("/months")
	public ResponseEntity<List<String>> getMonths() {

		return ResponseEntity.ok(
				service.getAvailableMonths()
		);
	}
}
