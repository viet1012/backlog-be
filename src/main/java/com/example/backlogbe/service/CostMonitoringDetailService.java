package com.example.backlogbe.service;

import com.example.backlogbe.assistant.screens.costmonitoring.CmText;
import com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringAssessment;
import com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringFocus;
import com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringSnapshot;
import com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringSnapshotLoader;
import com.example.backlogbe.assistant.screens.costmonitoring.RatioTrendTool;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostTransactionLineDto;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostTopItemsRepository;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostTransactionFilter;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostWorkingDateRepository;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostWorkingDateRepository.DailyRatio;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartPointDto;
import com.example.backlogbe.dto.costmonitoring.CostDetailResponse;
import com.example.backlogbe.dto.costmonitoring.CostDetailResponse.Daily;
import com.example.backlogbe.dto.costmonitoring.CostDetailResponse.Kpi;
import com.example.backlogbe.dto.costmonitoring.CostDetailResponse.Transaction;
import com.example.backlogbe.dto.costmonitoring.CostDetailResponse.TransactionPage;
import com.example.backlogbe.dto.costmonitoring.CostMonitoringChartResponse;
import com.example.backlogbe.dto.costmonitoring.CostTransactionFacetsResponse;
import com.example.backlogbe.logging.RequestTiming;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.function.Consumer;

// =========================================================
// COST MONITORING – CHI TIẾT 1 Ô (div × chart × tháng) cho drawer
//
// Tham số div / chart dùng mã chuẩn giống /chart (PR | MO | Guide, OT | T&SL | RM),
// map sang tên bộ phận nội bộ (PRESS / MOLD / GUIDE) để dùng chung tính toán với trợ lý AI.
//
// Tái dùng:
//   KPI / daily   : CostMonitoringService.getChart (sp_CostMonitoringChart) qua CostMonitoringSnapshotLoader,
//                   kết luận qua CostMonitoringAssessment (cùng cách tính với trợ lý AI).
//   Ratio         : CostWorkingDateRepository (F2_Working_Date.Adj_Ratio_PR / _MO / _GU).
//   Transactions  : CostTopItemsRepository (cùng bộ lọc fn_CostActualDaily trên CostTool / CostRm,
//                   chỉ cost center trực tiếp của bộ phận).
//
// Daily: daily = Act MTD hôm nay − Act MTD hôm trước trên số làm tròn 1 chữ số
//   -> Σ daily = kpi.actMtd đúng tuyệt đối (giống get_daily_series của trợ lý).
// OT: chưa có actual -> KPI chỉ có FC / Fluct / Ratio; daily, transactions rỗng.
//
// /detail/transactions và /detail/transactions/export: chỉ đọc CostTool / CostRm theo khoảng ngày
// (không chạy snapshot / SP), dùng chung loadTransactionPage với /detail; allocationK = null.
// =========================================================
@Slf4j
@Service
@RequiredArgsConstructor
public class CostMonitoringDetailService {

	private static final String CATEGORY_OT = "OT";

	private static final int DEFAULT_PAGE_SIZE = 50;
	private static final int MAX_PAGE_SIZE = 200;

	// Giới hạn số dòng file export (1 div × 1 chart × tối đa 1 tháng)
	public static final int MAX_EXPORT_ROWS = 50_000;

	// Tìm / lọc bảng giao dịch (giữ số tham số SQL xa giới hạn 2100 của SQL Server)
	private static final int MAX_QUERY_LENGTH = 200;
	private static final int MAX_QUERY_TOKENS = 10;
	private static final int MAX_FILTER_VALUES = 500;
	private static final int DEFAULT_FACET_LIMIT = 500;
	private static final int MAX_FACET_LIMIT = 1000;

	private final CostMonitoringSnapshotLoader loader;
	private final CostTopItemsRepository itemsRepository;
	private final CostWorkingDateRepository workingDateRepository;


	// divCode / chartCode: mã chuẩn giống /chart (PR | MO | Guide, OT | T&SL | RM)
	// refresh: bỏ qua cache snapshot, load lại và ghi đè cache (CostMonitoringSnapshotLoader)
	public CostDetailResponse getDetail(
			String divCode,
			String chartCode,
			String month,
			String simuWeek,
			String date,
			Integer page,
			Integer size,
			boolean refresh
	) {

		long totalStart = System.nanoTime();

		String safeDivCode = requireDivCode(divCode);
		String cost = requireChart(chartCode);

		// Mã chuẩn -> tên bộ phận nội bộ (PR -> PRESS, MO -> MOLD, Guide -> GUIDE)
		String div = CostMonitoringFocus.normalizeDiv(safeDivCode);

		int safePage = safePage(page);
		int safeSize = safeSize(size);
		requireOffsetInRange(safePage, safeSize);

		// month / simuWeek validate trong CostMonitoringService.getChart (sai -> IllegalArgumentException)
		long stepStart = System.nanoTime();
		CostMonitoringSnapshot snapshot = loader.load(
				CostMonitoringSnapshotLoader.normalizeMonth(month),
				simuWeek == null || simuWeek.isBlank() ? null : simuWeek.trim(),
				refresh
		);
		logStep("snapshot", stepStart);

		YearMonth ym = snapshot.month();
		LocalDate filterDate = parseDate(date, ym);

		CostChartDto chart = snapshot.find(div, cost);
		int actDay = chart != null ? snapshot.lastActualDay(chart) : 0;
		LocalDate asOf = actDay > 0 ? ym.atDay(actDay) : null;

		stepStart = System.nanoTime();
		TreeMap<LocalDate, DailyRatio> ratios = loadRatios(snapshot);
		logStep("loadRatios", stepStart);

		CostMonitoringAssessment assessment = chart != null ? CostMonitoringAssessment.of(snapshot, chart) : null;

		// Cùng thứ tự tính như khi truyền thẳng vào constructor (kpi -> daily -> transactions)
		Kpi kpi = buildKpi(chart, assessment, div, actDay, ratios);
		List<Daily> daily = buildDaily(chart, asOf, div, ratios);
		TransactionPage transactions = buildTransactions(cost, div, ym, asOf, filterDate, assessment, safePage, safeSize);

		CostDetailResponse response = new CostDetailResponse(
				ym.toString(),
				safeDivCode,
				cost,
				snapshot.response().simuWeek(),
				CostMonitoringChartResponse.UNIT,
				snapshot.n(),
				asOf,
				snapshot.monthClosed(),
				kpi,
				daily,
				transactions
		);

		logStep("total", totalStart);

		return response;
	}

	// 1 dòng INFO mỗi bước (điều tra API chậm); không log tham số
	private static void logStep(String step, long startNanos) {
		log.info("cost-monitoring detail: rid={} step={} durationMs={}",
				RequestTiming.rid(), step, RequestTiming.elapsedMs(startNanos));
	}


	// =========================================================
	// KPI
	// =========================================================

	private static Kpi buildKpi(
			CostChartDto chart,
			CostMonitoringAssessment a,
			String div,
			int actDay,
			TreeMap<LocalDate, DailyRatio> ratios
	) {

		Double ratioPct = latestRatioPct(ratios, div);

		if (chart == null || a == null) {
			return new Kpi(null, null, ratioPct, null, null, null, null, null, null, null, null, null);
		}

		Double act = a.act();
		Double fluct = a.fluct();

		Double currentRate;
		Double projectedEom;

		if (act == null) {
			currentRate = null;
			projectedEom = null;
		} else if (a.closed()) {
			// Tháng đã kết thúc: không còn dự báo, cuối tháng = Actual cả tháng
			currentRate = actDay > 0 ? CmText.r2(act / actDay) : null;
			projectedEom = CmText.r1(act);
		} else {
			currentRate = CmText.r2(a.currentRate());
			projectedEom = CmText.r1(a.projectedEom());
		}

		String status = act == null || fluct == null
				? null
				: CostMonitoringAssessment.exceeds(act, fluct) ? CostDetailResponse.STATUS_OVER : CostDetailResponse.STATUS_OK;

		// Estimate null (vd OT) -> null, FE hiển thị "-"; PL null mà có Est -> ghi chú theo FC
		Double estimate = CmText.r1(CostMonitoringSnapshot.estimate(chart));
		String estimateNote = estimate != null && !a.closed() && CostMonitoringSnapshot.estimateByFc(chart)
				? "(theo FC)"
				: null;

		return new Kpi(
				CmText.r1(chart.fcTgt()),
				CmText.r1(fluct),
				ratioPct,
				CmText.r1(act),
				estimate,
				estimateNote,
				a.pct() != null ? CmText.r1(a.pct()) : null,
				currentRate,
				projectedEom,
				CmText.subR1(projectedEom, fluct),
				CmText.subR1(projectedEom, chart.fcTgt()),
				status
		);
	}


	// =========================================================
	// DAILY (ngày 1 -> asOf)
	// =========================================================

	private static List<Daily> buildDaily(
			CostChartDto chart,
			LocalDate asOf,
			String div,
			TreeMap<LocalDate, DailyRatio> ratios
	) {

		if (chart == null || asOf == null) {
			return List.of();
		}

		List<Daily> result = new ArrayList<>();
		Double previous = 0.0;

		for (CostChartPointDto point : chart.points()) {

			if (point.date() == null || point.date().isAfter(asOf) || point.actMtd() == null) {
				continue;
			}

			// Trên số 1 chữ số: Σ daily = cumulative cuối = kpi.actMtd
			Double daily = CmText.subR1(point.actMtd(), previous);
			previous = point.actMtd();

			DailyRatio ratio = ratios.get(point.date());

			result.add(new Daily(
					point.date(),
					daily,
					CmText.r1(point.actMtd()),
					ratio != null ? ratioPct(RatioTrendTool.ratioOf(ratio, div)) : null,
					CmText.r1(point.fluctTgt()),
					CmText.subR1(point.actMtd(), point.fluctTgt())
			));
		}

		return result;
	}


	// =========================================================
	// TRANSACTIONS (T&SL: CostTool, RM: CostRm)
	// =========================================================

	private TransactionPage buildTransactions(
			String cost,
			String div,
			YearMonth month,
			LocalDate asOf,
			LocalDate filterDate,
			CostMonitoringAssessment a,
			int page,
			int size
	) {

		if (CATEGORY_OT.equals(cost)) {
			return TransactionPage.empty(page, size);
		}

		// Mặc định cùng kỳ với Actual (ngày 1 -> asOf); lọc ngày thì chỉ ngày đó (bất kỳ ngày nào trong tháng)
		LocalDate from;
		LocalDate to;

		if (filterDate != null) {
			from = filterDate;
			to = filterDate;
		} else if (asOf != null) {
			from = month.atDay(1);
			to = asOf;
		} else {
			return TransactionPage.empty(page, size);
		}

		TransactionPage result = loadTransactionPage(cost, div, from, to, page, size, CostTransactionFilter.NONE);

		// Actual (1 chữ số) − tổng trực tiếp (2 chữ số) = phân bổ F2 / HT2 / HT-G + điều chỉnh (chỉ khi cả kỳ)
		Double totalK = result.totalAmountK();
		Double allocationK = filterDate == null && a != null && a.act() != null && totalK != null
				? CmText.r2(CmText.r1(a.act()) - totalK)
				: null;

		return result.withAllocationK(allocationK);
	}

	/*
	 * Lõi dùng chung cho /detail, /detail/transactions và export:
	 * dòng xuất kho trực tiếp của bộ phận trong [from, to], thành tiền giảm dần, 1 trang.
	 * cost: T&SL | RM (OT do caller xử lý); allocationK = null (caller tự gắn nếu có Actual).
	 * filter rỗng -> truy vấn cũ (countRows / findTotal có cache + findLines);
	 * có tìm / lọc / sắp xếp -> 1 truy vấn findFilteredPage (trang + tổng theo bộ lọc).
	 */
	private TransactionPage loadTransactionPage(
			String cost,
			String div,
			LocalDate from,
			LocalDate to,
			int page,
			int size,
			CostTransactionFilter filter
	) {

		List<String> costCenters = CostMonitoringSnapshot.DIV_COST_CENTERS.get(div);

		if (!filter.isEmpty()) {
			long stepStart = System.nanoTime();
			CostTopItemsRepository.FilteredPage result =
					itemsRepository.findFilteredPage(cost, from, to, costCenters, filter, page * size, size);
			logStep("findFilteredPage", stepStart);

			return new TransactionPage(
					from,
					to,
					page,
					size,
					result.total(),
					(int) ((result.total() + size - 1) / size),
					CmText.r2(result.totalK()),
					null,
					result.lines().stream().map(CostMonitoringDetailService::toTransaction).toList()
			);
		}

		long stepStart = System.nanoTime();
		long total = itemsRepository.countRows(cost, from, to, costCenters);
		logStep("countRows", stepStart);

		stepStart = System.nanoTime();
		Double totalK = itemsRepository.findTotal(cost, from, to, costCenters);
		logStep("findTotal", stepStart);

		int offset = page * size;

		List<Transaction> items;

		if (offset >= total) {
			items = List.of();
		} else {
			stepStart = System.nanoTime();
			items = itemsRepository.findLines(cost, from, to, costCenters, offset, size).stream()
					.map(CostMonitoringDetailService::toTransaction)
					.toList();
			logStep("findLines", stepStart);
		}

		return new TransactionPage(
				from,
				to,
				page,
				size,
				total,
				(int) ((total + size - 1) / size),
				CmText.r2(totalK),
				null,
				items
		);
	}


	// =========================================================
	// TRANSACTIONS THEO KHOẢNG NGÀY (không chạy snapshot / SP)
	// =========================================================

	/*
	 * GET /detail/transactions: dòng xuất kho của div × chart trong [from, to] (cùng 1 tháng).
	 * Không có Act nên allocationK = null. OT -> trang rỗng (from / to vẫn trả về).
	 * filter: tìm / lọc / sắp xếp (parseFilter); NONE -> như trước.
	 */
	public TransactionPage getTransactions(
			String divCode,
			String chartCode,
			String from,
			String to,
			Integer page,
			Integer size,
			CostTransactionFilter filter
	) {

		TransactionScope scope = transactionScope(divCode, chartCode, from, to);

		int safePage = safePage(page);
		int safeSize = safeSize(size);
		requireOffsetInRange(safePage, safeSize);

		if (scope.isOt()) {
			return new TransactionPage(scope.from(), scope.to(), safePage, safeSize, 0, 0, null, null, List.of());
		}

		return loadTransactionPage(scope.cost(), scope.div(), scope.from(), scope.to(), safePage, safeSize, filter);
	}

	/*
	 * Bước 1 của export: validate + đếm dòng + tổng (trước khi ghi file -> lỗi trả 400 JSON).
	 * Quá MAX_EXPORT_ROWS -> 400, yêu cầu thu hẹp khoảng ngày / bộ lọc.
	 */
	public TransactionExport prepareTransactionExport(
			String divCode,
			String chartCode,
			String from,
			String to,
			CostTransactionFilter filter
	) {

		TransactionScope scope = transactionScope(divCode, chartCode, from, to);

		if (scope.isOt()) {
			return new TransactionExport(scope, 0, null, filter);
		}

		List<String> costCenters = CostMonitoringSnapshot.DIV_COST_CENTERS.get(scope.div());

		long rowCount;
		Double totalK;

		if (filter.hasConditions()) {
			CostTopItemsRepository.FilteredSummary summary =
					itemsRepository.summarizeFiltered(scope.cost(), scope.from(), scope.to(), costCenters, filter);
			rowCount = summary.total();
			totalK = summary.totalK();
		} else {
			rowCount = itemsRepository.countRows(scope.cost(), scope.from(), scope.to(), costCenters);
			totalK = null;
		}

		if (rowCount > MAX_EXPORT_ROWS) {
			throw new IllegalArgumentException(String.format(Locale.ROOT,
					"Too many rows to export (%d). Maximum is %d; please narrow the date range.",
					rowCount, MAX_EXPORT_ROWS));
		}

		if (!filter.hasConditions()) {
			totalK = itemsRepository.findTotal(scope.cost(), scope.from(), scope.to(), costCenters);
		}

		return new TransactionExport(scope, rowCount, CmText.r2(totalK), filter);
	}

	// Bước 2 của export: đọc từng dòng (theo bộ lọc / thứ tự của bảng), tối đa MAX_EXPORT_ROWS; OT -> không có dòng
	public void forEachTransaction(TransactionExport export, Consumer<Transaction> consumer) {

		TransactionScope scope = export.scope();

		if (scope.isOt()) {
			return;
		}

		List<String> costCenters = CostMonitoringSnapshot.DIV_COST_CENTERS.get(scope.div());

		if (export.filter().isEmpty()) {
			itemsRepository.streamLines(
					scope.cost(),
					scope.from(),
					scope.to(),
					costCenters,
					MAX_EXPORT_ROWS,
					line -> consumer.accept(toTransaction(line))
			);
			return;
		}

		itemsRepository.streamFilteredLines(
				scope.cost(),
				scope.from(),
				scope.to(),
				costCenters,
				export.filter(),
				MAX_EXPORT_ROWS,
				line -> consumer.accept(toTransaction(line))
		);
	}

	/*
	 * GET /detail/transactions/facets: giá trị cho dropdown lọc 1 cột (kiểu Excel).
	 * Áp dụng từ khóa + bộ lọc các cột khác (bỏ bộ lọc của chính cột này), đếm số dòng mỗi giá trị.
	 * OT -> rỗng.
	 */
	public CostTransactionFacetsResponse getTransactionFacets(
			String divCode,
			String chartCode,
			String from,
			String to,
			String column,
			String facetQ,
			Integer limit,
			CostTransactionFilter filter
	) {

		TransactionScope scope = transactionScope(divCode, chartCode, from, to);

		CostTransactionFilter.FacetColumn facetColumn = CostTransactionFilter.FacetColumn.fromParam(
				column == null ? "" : column.trim());

		if (facetColumn == null) {
			throw new IllegalArgumentException("column must be one of date, category, costCenter, machine.");
		}

		int safeLimit = limit == null ? DEFAULT_FACET_LIMIT : limit;

		if (safeLimit < 1 || safeLimit > MAX_FACET_LIMIT) {
			throw new IllegalArgumentException("limit must be between 1 and " + MAX_FACET_LIMIT + ".");
		}

		if (facetQ != null && facetQ.length() > MAX_QUERY_LENGTH) {
			throw new IllegalArgumentException("facetQ must be at most " + MAX_QUERY_LENGTH + " characters.");
		}

		if (scope.isOt()) {
			return new CostTransactionFacetsResponse(facetColumn.param(), scope.from(), scope.to(), 0, false, List.of());
		}

		long stepStart = System.nanoTime();
		CostTopItemsRepository.FacetRows rows = itemsRepository.findFacet(
				scope.cost(),
				scope.from(),
				scope.to(),
				CostMonitoringSnapshot.DIV_COST_CENTERS.get(scope.div()),
				filter,
				facetColumn,
				facetQ,
				safeLimit
		);
		logStep("findFacet", stepStart);

		List<CostTransactionFacetsResponse.Value> values = rows.rows().stream()
				.map(r -> {
					String value = r.value() == null ? "" : r.value();
					return new CostTransactionFacetsResponse.Value(value, value, r.count());
				})
				.toList();

		return new CostTransactionFacetsResponse(
				facetColumn.param(),
				scope.from(),
				scope.to(),
				rows.groups(),
				rows.groups() > values.size(),
				values
		);
	}

	/*
	 * Tham số tìm / lọc / sắp xếp của /detail/transactions (+ export, facets) -> CostTransactionFilter.
	 * q    : tách theo khoảng trắng, tối đa MAX_QUERY_TOKENS từ, AND.
	 * sort : "field,dir" – field: date | qty | unitPrice | amountK; dir: asc | desc (mặc định desc).
	 * dates: yyyy-MM-dd; category / costCenter / machine: "" = ô trống. Mỗi cột tối đa MAX_FILTER_VALUES giá trị.
	 * Tham số nhiều giá trị: lặp lại tên (category=A&category=B) – controller đọc bằng getParameterValues
	 * (không tách theo dấu phẩy vì tên vật tư / cost center có thể chứa dấu phẩy).
	 */
	public static CostTransactionFilter parseFilter(
			String q,
			String sort,
			List<String> dates,
			List<String> categories,
			List<String> costCenters,
			List<String> machines
	) {

		List<String> tokens = List.of();

		if (q != null && !q.isBlank()) {
			if (q.length() > MAX_QUERY_LENGTH) {
				throw new IllegalArgumentException("q must be at most " + MAX_QUERY_LENGTH + " characters.");
			}
			tokens = java.util.Arrays.stream(q.trim().split("\\s+"))
					.filter(t -> !t.isBlank())
					.distinct()
					.toList();
			if (tokens.size() > MAX_QUERY_TOKENS) {
				throw new IllegalArgumentException("q must have at most " + MAX_QUERY_TOKENS + " words.");
			}
		}

		CostTransactionFilter.SortField sortField = null;
		boolean ascending = false;

		if (sort != null && !sort.isBlank()) {
			String[] parts = sort.trim().split(",", -1);
			sortField = CostTransactionFilter.SortField.fromParam(parts[0].trim());
			String dir = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "desc";

			if (sortField == null || parts.length > 2 || !(dir.equals("asc") || dir.equals("desc"))) {
				throw new IllegalArgumentException(
						"sort must be <field>,<asc|desc> with field one of date, qty, unitPrice, amountK.");
			}
			ascending = dir.equals("asc");
		}

		List<LocalDate> parsedDates = limited(dates, "date").stream()
				.filter(d -> d != null && !d.isBlank())
				.map(d -> {
					try {
						return LocalDate.parse(d.trim());
					} catch (DateTimeParseException e) {
						throw new IllegalArgumentException("date must be yyyy-MM-dd.");
					}
				})
				.distinct()
				.toList();

		return new CostTransactionFilter(
				tokens,
				parsedDates,
				limited(categories, "category"),
				limited(costCenters, "costCenter"),
				limited(machines, "machine"),
				sortField,
				ascending
		);
	}

	private static List<String> limited(List<String> values, String name) {

		if (values == null || values.isEmpty()) {
			return List.of();
		}

		if (values.size() > MAX_FILTER_VALUES) {
			throw new IllegalArgumentException(name + " accepts at most " + MAX_FILTER_VALUES + " values.");
		}

		return values.stream().map(v -> v == null ? "" : v).toList();
	}

	private TransactionScope transactionScope(
			String divCode,
			String chartCode,
			String from,
			String to
	) {

		String safeDivCode = requireDivCode(divCode);
		String cost = requireChart(chartCode);

		LocalDate fromDate = requireDate(from, "from");
		LocalDate toDate = requireDate(to, "to");

		if (!YearMonth.from(fromDate).equals(YearMonth.from(toDate))) {
			throw new IllegalArgumentException("from and to must be within the same month.");
		}
		if (fromDate.isAfter(toDate)) {
			throw new IllegalArgumentException("from must be on or before to.");
		}

		return new TransactionScope(
				safeDivCode,
				CostMonitoringFocus.normalizeDiv(safeDivCode),
				cost,
				fromDate,
				toDate
		);
	}

	/*
	 * Bộ lọc đã validate.
	 * divCode: mã chuẩn (PR | MO | Guide); div: tên nội bộ (PRESS | MOLD | GUIDE); cost: OT | T&SL | RM.
	 */
	public record TransactionScope(
			String divCode,
			String div,
			String cost,
			LocalDate from,
			LocalDate to
	) {

		public boolean isOt() {
			return CATEGORY_OT.equals(cost);
		}
	}

	// rowCount / totalAmountK (K$, 2 chữ số) tính lúc chuẩn bị, dùng cho phần header của file
	// filter: tìm / lọc / sắp xếp giống bảng đang xem (NONE = cả kỳ, thành tiền giảm dần)
	public record TransactionExport(
			TransactionScope scope,
			long rowCount,
			Double totalAmountK,
			CostTransactionFilter filter
	) {
	}

	private static Transaction toTransaction(CostTransactionLineDto line) {

		return new Transaction(
				line.date(),
				line.code(),
				line.name(),
				line.category(),
				line.qty(),
				line.qtyUnit(),
				line.unitPrice(),
				line.amount(),
				line.amount() != null ? line.amount() / 1000.0 : null,
				line.costCenter(),
				line.costCenterName(),
				line.machine()
		);
	}


	// =========================================================
	// RATIO
	// =========================================================

	// Ngày 1 -> min(cuối tháng, hôm qua); lỗi đọc -> rỗng (KPI / daily vẫn trả, ratio null)
	private TreeMap<LocalDate, DailyRatio> loadRatios(CostMonitoringSnapshot snapshot) {

		LocalDate from = snapshot.month().atDay(1);
		LocalDate until = RatioTrendTool.lastRatioDay(snapshot);

		if (until.isBefore(from)) {
			return new TreeMap<>();
		}

		try {
			return workingDateRepository.findRatios(from, until);
		} catch (RuntimeException e) {
			log.warn("cost-monitoring detail: cannot load ratios error={}", e.getClass().getSimpleName());
			return new TreeMap<>();
		}
	}

	// Ratio khác null mới nhất của bộ phận, %
	private static Double latestRatioPct(TreeMap<LocalDate, DailyRatio> ratios, String div) {

		for (DailyRatio r : ratios.descendingMap().values()) {
			Double value = RatioTrendTool.ratioOf(r, div);
			if (value != null) {
				return ratioPct(value);
			}
		}

		return null;
	}

	// 0.053 -> 5.3 (%, 1 chữ số); null -> null
	private static Double ratioPct(Double ratio) {
		return ratio == null ? null : CmText.r1(ratio * 100);
	}


	// =========================================================
	// PARAM
	// =========================================================

	private static String requireDivCode(String value) {

		String code = canonical(value, CostMonitoringService.DIVS);

		if (code == null) {
			throw new IllegalArgumentException(
					"div must be one of " + String.join(", ", CostMonitoringService.DIVS) + ".");
		}

		return code;
	}

	private static String requireChart(String value) {

		String code = canonical(value, CostMonitoringService.CHARTS);

		if (code == null) {
			throw new IllegalArgumentException(
					"chart must be one of " + String.join(", ", CostMonitoringService.CHARTS) + ".");
		}

		return code;
	}

	private static int safePage(Integer page) {

		int value = page == null ? 0 : page;

		if (value < 0) {
			throw new IllegalArgumentException("page must be >= 0.");
		}

		return value;
	}

	private static int safeSize(Integer size) {

		int value = size == null ? DEFAULT_PAGE_SIZE : size;

		if (value < 1 || value > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE + ".");
		}

		return value;
	}

	private static void requireOffsetInRange(int page, int size) {

		if ((long) page * size > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("page is too large.");
		}
	}

	// Bắt buộc, yyyy-MM-dd
	private static LocalDate requireDate(String value, String name) {

		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " is required (yyyy-MM-dd).");
		}

		try {
			return LocalDate.parse(value.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException(name + " must be yyyy-MM-dd.");
		}
	}

	// Giá trị trong allowed (không phân biệt hoa thường) -> đúng chính tả trong allowed; khác -> null
	private static String canonical(String value, List<String> allowed) {

		if (value == null || value.isBlank()) {
			return null;
		}

		String trimmed = value.trim();

		return allowed.stream()
				.filter(code -> code.equalsIgnoreCase(trimmed))
				.findFirst()
				.orElse(null);
	}

	// null / rỗng -> không lọc; phải là yyyy-MM-dd và thuộc tháng đang xem
	private static LocalDate parseDate(String value, YearMonth month) {

		if (value == null || value.isBlank()) {
			return null;
		}

		LocalDate date;

		try {
			date = LocalDate.parse(value.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("date must be yyyy-MM-dd.");
		}

		if (!YearMonth.from(date).equals(month)) {
			throw new IllegalArgumentException(
					String.format(Locale.ROOT, "date must be within month %s.", month));
		}

		return date;
	}
}
