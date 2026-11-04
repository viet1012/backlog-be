package com.example.backlogbe.service;

import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartPointDto;
import com.example.backlogbe.dto.costmonitoring.CostChartRowDto;
import com.example.backlogbe.dto.costmonitoring.CostMonitoringChartResponse;
import com.example.backlogbe.repository.costmonitoring.CostMonitoringRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// =========================================================
// COST MONITORING
//
// Chỉ đọc, không cần đăng nhập.
// Dựng lại 9 chart Excel "KVH Cost Monitoring":
// Div (PR, MO, Guide) × Chart (OT, T&SL, RM), trục X theo ngày trong tháng.
// Số liệu tính trong [KVH].[dbo].[sp_CostMonitoringChart], service chỉ gom nhóm.
// =========================================================
@Service
@RequiredArgsConstructor
public class CostMonitoringService {

	// =========================================================
	// CONFIG
	// =========================================================

	private static final List<String> DIVS =
			List.of("PR", "MO", "Guide");

	private static final List<String> CHARTS =
			List.of("OT", "T&SL", "RM");

	private static final Set<String> SIMU_WEEKS =
			Set.of("Simu_W1", "Simu_W2", "Simu_W3", "Simu_W4", "Simu_W5");


	private final CostMonitoringRepository repository;


	// Khóa nhóm (Div, Chart)
	private record ChartKey(
			String div,
			String chart
	) {
	}


	// =========================================================
	// CHART
	// =========================================================

	public CostMonitoringChartResponse getChart(
			String month,
			String simuWeek
	) {

		YearMonth safeMonth = parseMonth(month);
		String safeSimuWeek = parseSimuWeek(simuWeek);

		Map<ChartKey, List<CostChartRowDto>> rowsByChart = new LinkedHashMap<>();

		// Tạo sẵn 9 chart theo thứ tự cố định (thiếu dữ liệu vẫn trả chart rỗng)
		for (String div : DIVS) {
			for (String chart : CHARTS) {
				rowsByChart.put(new ChartKey(div, chart), new ArrayList<>());
			}
		}

		// Div / Chart ngoài danh sách (nếu SP trả thêm) xếp sau, theo thứ tự gặp
		for (CostChartRowDto row : repository.findChartRows(safeMonth.atDay(1), safeSimuWeek)) {
			rowsByChart
					.computeIfAbsent(new ChartKey(row.div(), row.chart()), key -> new ArrayList<>())
					.add(row);
		}

		LocalDate monthEnd = safeMonth.atEndOfMonth();

		List<CostChartDto> charts =
				rowsByChart.entrySet().stream()
						.map(entry -> toChartDto(entry.getKey(), entry.getValue(), monthEnd))
						.toList();

		return new CostMonitoringChartResponse(
				safeMonth.toString(),
				safeSimuWeek,
				CostMonitoringChartResponse.UNIT,
				charts
		);
	}


	// =========================================================
	// MONTH
	// =========================================================

	public List<String> getAvailableMonths() {

		return repository.findAvailableMonths().stream()
				.filter(month -> month != null && !month.isBlank())
				.map(String::trim)
				.toList();
	}


	// =========================================================
	// PARAM
	// =========================================================

	// null / rỗng -> tháng hiện tại
	private static YearMonth parseMonth(String value) {

		if (value == null || value.isBlank()) {
			return YearMonth.now();
		}

		try {
			return YearMonth.parse(value.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("month must be yyyy-MM.");
		}
	}

	// null / rỗng -> SP tự lấy tuần Simu mới nhất có số
	private static String parseSimuWeek(String value) {

		if (value == null || value.isBlank()) {
			return null;
		}

		String safeValue = value.trim();

		if (!SIMU_WEEKS.contains(safeValue)) {
			throw new IllegalArgumentException("simuWeek must be one of Simu_W1..Simu_W5.");
		}

		return safeValue;
	}


	// =========================================================
	// MAPPING
	// =========================================================

	private static CostChartDto toChartDto(
			ChartKey key,
			List<CostChartRowDto> rows,
			LocalDate monthEnd
	) {

		List<CostChartRowDto> sorted =
				rows.stream()
						.sorted(Comparator.comparing(CostChartRowDto::d, Comparator.nullsLast(Comparator.naturalOrder())))
						.toList();

		CostChartRowDto first = sorted.isEmpty() ? null : sorted.get(0);

		Double actMtdLatest = null;
		Double estMonthEnd = null;
		List<CostChartPointDto> points = new ArrayList<>();

		for (CostChartRowDto row : sorted) {
			points.add(
					new CostChartPointDto(
							row.d(),
							row.plMtd(),
							row.actMtd(),
							row.estMtd(),
							row.fluctTgt()
					)
			);

			if (row.actMtd() != null) {
				actMtdLatest = row.actMtd();
			}

			if (monthEnd.equals(row.d())) {
				estMonthEnd = row.estMtd();
			}
		}

		return new CostChartDto(
				key.div(),
				key.chart(),
				first != null ? first.fcTgt() : null,
				first != null ? first.plTgt() : null,
				first != null ? first.bp() : null,
				actMtdLatest,
				estMonthEnd,
				points
		);
	}
}
