package com.example.backlogbe.service.costot;

import com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringSnapshotLoader;
import com.example.backlogbe.dto.costmonitoring.CostMonitoringChartResponse;
import com.example.backlogbe.dto.costmonitoring.CostOtImportResult;
import com.example.backlogbe.dto.costmonitoring.CostOtStatusResponse;
import com.example.backlogbe.logging.RequestTiming;
import com.example.backlogbe.repository.costmonitoring.CostOtRepository;
import com.example.backlogbe.repository.costmonitoring.CostOtRepository.OtRow;
import com.example.backlogbe.repository.costmonitoring.CostOtRepository.StatusRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

// =========================================================
// NẠP ACTUAL OT CHO COST MONITORING
//
// 2 cách, cùng 1 lõi (CostOtExcelParser -> [KVH].dbo.CostOtDaily):
//   1. Upload file  : POST /api/cost-monitoring/ot/import  (FE chọn file .xlsx)
//   2. Đọc từ share : POST /api/cost-monitoring/ot/import-from-share
//                     + job tự động (cost-monitoring.ot.share-cron, mặc định tắt)
//      Đường dẫn: cost-monitoring.ot.share-path. Tài khoản chạy BE (không phải máy người dùng)
//      phải đọc được share \\192.168.0.6\dtp\...
//
// Ghi: thay toàn bộ dòng của (tháng, SubDiv) có trong file (1 transaction), rồi xóa cache snapshot
// để /chart, /detail, trợ lý AI thấy số mới ngay.
// Quyền: COST_MONITORING / X (@RequirePerm ở CostMonitoringController); mã NV từ phiên đăng nhập (CurrentUser).
// Log: chỉ nguồn (upload / share), số dòng, thời gian – không ghi tên file, mã NV.
// =========================================================
@Slf4j
@Service
public class CostOtImportService {

	private static final Map<String, String> SUBDIV_TO_DIV = Map.of(
			"PR", "PR",
			"MO", "MO",
			"PG", "Guide",
			"MG", "Guide"
	);

	private static final List<String> SUBDIVS = List.of("PR", "MO", "PG", "MG");

	private final CostOtRepository repository;
	private final CostMonitoringSnapshotLoader snapshotLoader;
	private final TransactionTemplate tx;

	private final String sharePath;

	// 1 lần import tại 1 thời điểm (upload / share / job)
	private final Object importLock = new Object();

	// Job tự động: bỏ qua nếu file trên share chưa đổi từ lần nạp trước
	private volatile long lastShareModified = -1;

	public CostOtImportService(
			CostOtRepository repository,
			CostMonitoringSnapshotLoader snapshotLoader,
			PlatformTransactionManager transactionManager,
			// vd //192.168.0.6/dtp/9_Daily Costmonitoring/FY26 Daily Cost monitoning_FY26.xlsx (trống = tắt)
			@Value("${cost-monitoring.ot.share-path:}") String sharePath
	) {
		this.repository = repository;
		this.snapshotLoader = snapshotLoader;
		this.tx = new TransactionTemplate(transactionManager);
		this.sharePath = sharePath == null ? "" : sharePath.trim();
	}


	// =========================================================
	// 1. UPLOAD
	// =========================================================

	public CostOtImportResult importUpload(
			MultipartFile file,
			String month,
			boolean dryRun,
			String employeeId
	) {

		requireImporter(employeeId);

		if (file == null || file.isEmpty()) {
			throw new IllegalArgumentException("file is required (.xlsx).");
		}

		String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
		String lower = name.toLowerCase(Locale.ROOT);

		if (!lower.endsWith(".xlsx") && !lower.endsWith(".xlsm")) {
			throw new IllegalArgumentException("Chỉ nhận file .xlsx / .xlsm (file FY.. Daily Cost monitoning).");
		}

		YearMonth ym = parseMonth(month);

		try (InputStream in = file.getInputStream()) {
			return run("upload", in, safeSource(name), ym, dryRun, employeeId);
		} catch (IOException e) {
			throw new IllegalArgumentException("Không đọc được file upload.");
		}
	}


	// =========================================================
	// 2. SHARE (nút trên FE + job)
	// =========================================================

	public CostOtImportResult importFromShare(
			String month,
			boolean dryRun,
			String employeeId
	) {

		requireImporter(employeeId);

		Path path = requireSharePath();
		YearMonth ym = parseMonth(month);

		try (InputStream in = Files.newInputStream(path)) {
			CostOtImportResult result = run("share", in, "share:" + path.getFileName(), ym, dryRun, employeeId);
			if (!dryRun && ym == null) {
				lastShareModified = Files.getLastModifiedTime(path).toMillis();
			}
			return result;
		} catch (IOException e) {
			log.warn("cost-monitoring ot import: share read failed ({})", e.getClass().getSimpleName());
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"BE không đọc được file trên share. Kiểm tra file tồn tại và tài khoản chạy BE có quyền đọc thư mục.");
		}
	}

	// cron "-" (mặc định) = tắt. vd "0 15 8-18 * * MON-SAT": phút 15 mỗi giờ 8h-18h
	@Scheduled(cron = "${cost-monitoring.ot.share-cron:-}")
	public void scheduledShareImport() {

		if (sharePath.isEmpty()) {
			return;
		}

		try {
			Path path = Path.of(sharePath);
			long modified = Files.getLastModifiedTime(path).toMillis();

			if (modified == lastShareModified) {
				return;   // file chưa đổi
			}

			try (InputStream in = Files.newInputStream(path)) {
				run("share-job", in, "share:" + path.getFileName(), null, false, null);
			}

			lastShareModified = modified;
		} catch (Exception e) {
			log.warn("cost-monitoring ot import: scheduled share import failed ({})", e.getClass().getSimpleName());
		}
	}

	public boolean shareImportEnabled() {
		return !sharePath.isEmpty();
	}


	// =========================================================
	// STATUS
	// =========================================================

	public CostOtStatusResponse status(String month) {

		YearMonth ym = parseMonth(month);
		if (ym == null) {
			ym = YearMonth.now();
		}

		Map<String, StatusRow> bySub = repository.findStatus(ym).stream()
				.collect(Collectors.toMap(StatusRow::subDiv, Function.identity(), (a, b) -> a));

		List<CostOtStatusResponse.Row> rows = SUBDIVS.stream()
				.map(sub -> {
					StatusRow s = bySub.get(sub);
					return s == null
							? new CostOtStatusResponse.Row(sub, SUBDIV_TO_DIV.get(sub), 0, null, null, null, null)
							: new CostOtStatusResponse.Row(sub, SUBDIV_TO_DIV.get(sub), s.days(), s.lastDate(),
									r2(s.totalK()), s.loadedAt(), s.source());
				})
				.toList();

		return new CostOtStatusResponse(ym.toString(), CostMonitoringChartResponse.UNIT, shareImportEnabled(), rows);
	}


	// =========================================================
	// LÕI
	// =========================================================

	private CostOtImportResult run(
			String kind,
			InputStream in,
			String source,
			YearMonth month,
			boolean dryRun,
			String employeeId
	) throws IOException {

		long start = System.nanoTime();

		synchronized (importLock) {

			// Chỉ lấy tới hôm nay (ô ngày tương lai, nếu có số 0 do công thức, không tính là Actual)
			CostOtExcelParser.ParseResult parsed = CostOtExcelParser.parse(in, month, LocalDate.now());

			// (tháng, SubDiv) -> dòng
			TreeMap<String, List<OtRow>> groups = new TreeMap<>();
			Map<String, String> sheetOf = new TreeMap<>();

			for (CostOtExcelParser.SheetResult sheet : parsed.sheets()) {
				for (OtRow row : sheet.rows()) {
					String key = YearMonth.from(row.date()) + "|" + sheet.subDiv();
					groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
					sheetOf.put(key, sheet.sheet());
				}
			}

			if (groups.isEmpty()) {
				throw new IllegalArgumentException(month != null
						? "File không có số OT của tháng " + month + "."
						: "File không có số OT nào (cột Actual / OT trống).");
			}

			List<CostOtImportResult.Summary> summaries = groups.entrySet().stream()
					.map(e -> toSummary(e.getKey(), sheetOf.get(e.getKey()), e.getValue()))
					.toList();

			int written = 0;

			if (!dryRun) {
				Integer n = tx.execute(status -> {
					int count = 0;
					for (Map.Entry<String, List<OtRow>> e : groups.entrySet()) {
						String[] k = e.getKey().split("\\|");
						count += repository.replaceMonth(YearMonth.parse(k[0]), k[1], e.getValue(), source, employeeId);
					}
					return count;
				});
				written = n != null ? n : 0;

				// /chart, /detail, trợ lý AI đọc lại SP ngay
				snapshotLoader.invalidateAll();
			}

			log.info("cost-monitoring ot import: rid={} kind={} dryRun={} groups={} rows={} warnings={} durationMs={}",
					RequestTiming.rid(), kind, dryRun, groups.size(), written, parsed.warnings().size(),
					RequestTiming.elapsedMs(start));

			return new CostOtImportResult(source, dryRun, LocalDateTime.now(), written, summaries, parsed.warnings());
		}
	}

	private static CostOtImportResult.Summary toSummary(String key, String sheet, List<OtRow> rows) {

		String[] k = key.split("\\|");
		BigDecimal total = rows.stream().map(OtRow::amountK).reduce(BigDecimal.ZERO, BigDecimal::add);

		return new CostOtImportResult.Summary(
				k[0],
				k[1],
				SUBDIV_TO_DIV.get(k[1]),
				sheet,
				rows.size(),
				rows.get(0).date(),
				rows.get(rows.size() - 1).date(),
				r2(total.doubleValue())
		);
	}


	// =========================================================
	// QUYỀN / THAM SỐ
	// =========================================================

	// Quyền kiểm ở controller (COST_MONITORING / X); ở đây chỉ cần mã NV để ghi vào dữ liệu nạp
	private void requireImporter(String employeeId) {

		if (employeeId == null || employeeId.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login is required.");
		}
	}

	private Path requireSharePath() {

		if (sharePath.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Chưa cấu hình cost-monitoring.ot.share-path trên BE.");
		}

		try {
			return Path.of(sharePath);
		} catch (InvalidPathException e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"cost-monitoring.ot.share-path không hợp lệ.");
		}
	}

	// null / rỗng -> null (mọi tháng trong file)
	private static YearMonth parseMonth(String value) {

		if (value == null || value.isBlank()) {
			return null;
		}

		try {
			return YearMonth.parse(value.trim().replace('.', '-'));
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("month must be yyyy-MM.");
		}
	}

	// Tên file gốc: bỏ đường dẫn, cắt 200 ký tự
	private static String safeSource(String name) {

		String base = name.replace('\\', '/');
		base = base.substring(base.lastIndexOf('/') + 1);
		return base.length() > 200 ? base.substring(0, 200) : base;
	}

	private static Double r2(Double v) {
		return v == null ? null : Math.round(v * 100.0) / 100.0;
	}
}
