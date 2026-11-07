package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.tool.AssistantTool;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartPointDto;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostWorkingDateRepository;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostWorkingDateRepository.WorkingDay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;
import static com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringSnapshot.fmt;

// =========================================================
// TOOL get_daily_series(div, cost)
//
// Chuỗi theo ngày của 1 khung, từ ngày 1 đến ngày D (nhãn theo lang):
//   ngày|thứ|ngày nghỉ|WD_Office|chi trong ngày|Actual cộng dồn|Fluct (giá trị trong ngày)|Fluct theo tiến độ
//   |% đã dùng|% thời gian
//   thứ          = T2..T7, CN (en: Mon..Sun), chỉ để hiển thị
//   ngày nghỉ    = "nghỉ" khi F2_Working_Date.WD_All = 0 (nhà máy nghỉ); KHÔNG suy từ thứ trong tuần.
//                  Ngày không có dòng / lỗi đọc -> trống
//   WD_Office    = giá trị WD_Office (0 = văn phòng nghỉ, 1 = làm việc); không có -> trống
//   daily        = Act MTD hôm đó − Act MTD gần nhất trước đó
//   FluctDay     = Fluct cả tháng tính tại ngày đó (point.fluctTgt, đúng như tooltip chart)
//   Fluct theo tiến độ = FluctDay / N × ngày
//   % đã dùng    = Actual cộng dồn / FluctDay × 100
//   % thời gian  = ngày / N × 100
// So sánh trong ngày dùng FluctDay, không dùng Fluct cuối tháng.
// Tháng lấy từ tham số màn hình (model không tự đặt).
// =========================================================
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySeriesTool implements AssistantTool {

	private final CostMonitoringSnapshotLoader loader;
	private final CostWorkingDateRepository workingDateRepository;

	@Override
	public String name() {
		return "get_daily_series";
	}

	@Override
	public String description() {
		return "Lấy chi phí theo từng ngày của 1 khung trong tháng đang xem (thứ trong tuần, ngày nhà máy nghỉ, "
				+ "WD_Office, chi trong ngày, "
				+ "Actual cộng dồn, Fluct của chính ngày đó, Fluct theo tiến độ, % đã dùng, % thời gian), "
				+ "từ ngày 1 đến ngày có dữ liệu mới nhất. Dùng cho câu hỏi về xu hướng theo ngày, "
				+ "một ngày cụ thể, ngày nào chi nhiều, tăng đột biến.";
	}

	@Override
	public Map<String, Object> parameters() {
		return CostMonitoringToolArgs.divCostSchema(CostMonitoringSnapshot.COSTS, Map.of());
	}

	@Override
	public String execute(Map<String, Object> args, Map<String, String> screenParams, String lang) {

		String div = CostMonitoringToolArgs.div(args);
		String cost = CostMonitoringToolArgs.cost(args, CostMonitoringSnapshot.COSTS);

		CostMonitoringSnapshot snapshot = loader.load(screenParams);
		CostChartDto chart = snapshot.find(div, cost);

		String header = div + " " + cost + t(lang, "|Tháng=", "|Month=") + snapshot.month();

		if (chart == null || snapshot.d() < 1) {
			return header + "|" + t(lang, "Không có dữ liệu", "No data");
		}

		int n = snapshot.n();
		LocalDate from = snapshot.month().atDay(1);
		LocalDate until = snapshot.month().atDay(snapshot.d());

		Map<LocalDate, WorkingDay> workingDays = loadWorkingDays(from, until);

		StringBuilder sb = new StringBuilder();

		sb.append(header)
				.append(t(lang, "|Trạng thái tháng=", "|Month status=")).append(CmText.monthStatus(snapshot.monthClosed(), lang))
				.append(t(lang, "|Số ngày trong tháng (N)=", "|Days in month (N)=")).append(n)
				.append(t(lang, "|Đến ngày (D)=", "|Up to day (D)=")).append(snapshot.d())
				.append(t(lang, "|Đơn vị=K$", "|Unit=K$"))
				.append('\n');
		sb.append(t(lang,
				"[Chi phí theo ngày] cột=ngày|thứ|ngày nghỉ (nhà máy, WD_All=0)|WD_Office (0 = văn phòng nghỉ)"
						+ "|chi trong ngày|Actual cộng dồn|Fluct (giá trị trong ngày)"
						+ "|Fluct theo tiến độ|% đã dùng|% thời gian",
				"[Daily cost] cols=day|weekday|holiday (factory, WD_All=0)|WD_Office (0 = office closed)"
						+ "|spent that day|cumulative actual|Fluct (value on that day)"
						+ "|Fluct by pace|% used|% of time")).append('\n');

		Double lastMtd = 0.0;

		for (CostChartPointDto point : chart.points()) {

			if (point.date() == null || point.date().isAfter(until)) {
				continue;
			}

			int day = point.date().getDayOfMonth();

			Double daily = null;
			// Chi trong ngày = Actual cộng dồn hôm nay − hôm trước, trên số hiển thị (1 chữ số)
			if (point.actMtd() != null) {
				daily = CmText.subR1(point.actMtd(), lastMtd);
				lastMtd = point.actMtd();
			}

			Double fluctDay = point.fluctTgt();
			Double fluctByPace = fluctDay != null && n > 0 ? fluctDay / n * day : null;
			Double usedPct = point.actMtd() != null && fluctDay != null && fluctDay != 0
					? point.actMtd() / fluctDay * 100
					: null;
			double timePct = n > 0 ? (double) day / n * 100 : 0;

			WorkingDay wd = workingDays.get(point.date());

			sb.append(String.format("%02d", day))
					.append('|').append(weekday(point.date().getDayOfWeek(), lang))
					.append('|').append(holidayFlag(wd == null ? null : wd.wdAll(), lang))
					.append('|').append(flagValue(wd == null ? null : wd.wdOffice()))
					.append('|').append(fmt(daily))
					.append('|').append(fmt(point.actMtd()))
					.append('|').append(fmt(fluctDay))
					.append('|').append(fmt(fluctByPace))
					.append('|').append(pct(usedPct))
					.append('|').append(pct(timePct))
					.append('\n');
		}

		if (workingDays.isEmpty()) {
			sb.append(t(lang,
					"Không có lịch ngày nghỉ (cột ngày nghỉ và WD_Office trống); không suy ngày nghỉ từ thứ.",
					"No holiday calendar (holiday and WD_Office columns empty); do not infer holidays from weekday."))
					.append('\n');
		}

		if (chart.actMtdLatest() == null) {
			sb.append(t(lang, "Khung này chưa có actual trong tháng.", "This chart has no actual this month."));
		}

		return sb.toString().trim();
	}

	// Lỗi đọc lịch làm việc thì vẫn trả chuỗi theo ngày, cột ngày nghỉ để trống
	private Map<LocalDate, WorkingDay> loadWorkingDays(LocalDate from, LocalDate to) {

		try {
			return workingDateRepository.findWorkingDays(from, to);
		} catch (RuntimeException e) {
			log.warn("get_daily_series: cannot load working days error={}", e.getClass().getSimpleName());
			return Map.of();
		}
	}

	static String weekday(DayOfWeek dow, String lang) {

		if (CmText.isEn(lang)) {
			return switch (dow) {
				case MONDAY -> "Mon";
				case TUESDAY -> "Tue";
				case WEDNESDAY -> "Wed";
				case THURSDAY -> "Thu";
				case FRIDAY -> "Fri";
				case SATURDAY -> "Sat";
				case SUNDAY -> "Sun";
			};
		}

		return dow == DayOfWeek.SUNDAY ? "CN" : "T" + (dow.getValue() + 1);
	}

	// WD_All = 0 -> "nghỉ"; còn lại / không có dòng -> trống
	private static String holidayFlag(Double wd, String lang) {
		return wd != null && wd == 0 ? t(lang, "nghỉ", "holiday") : "";
	}

	// 0 / 1 (giá trị lẻ giữ 1 chữ số); không có -> trống
	static String flagValue(Double value) {

		if (value == null || value.isNaN() || value.isInfinite()) {
			return "";
		}

		return value == Math.rint(value) ? String.valueOf(value.longValue()) : fmt(value);
	}

	private static String pct(Double value) {
		return value == null || value.isNaN() || value.isInfinite()
				? ""
				: String.format(Locale.ROOT, "%.1f%%", value);
	}
}
