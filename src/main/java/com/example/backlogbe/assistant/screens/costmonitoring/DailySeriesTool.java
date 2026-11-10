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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;
import static com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringSnapshot.fmt;

// =========================================================
// TOOL get_daily_series(div, cost)
//
// Chuỗi theo ngày của 1 khung, từ ngày 1 đến ngày D; month_end=true (hỏi đúng 1 khung) -> đến cuối tháng
// (nhãn theo lang):
//   ngày|thứ|ngày nghỉ|WD_Office|chi trong ngày|Actual cộng dồn|Estimate (MTD)|Fluct (giá trị trong ngày)
//   |Fluct theo tiến độ|% đã dùng|% thời gian
//   Estimate (MTD) = Est_MTD của SP (đường Estimate: Actual cuối + COALESCE(PL, FC) theo ngày làm việc), có từ ngày
//                  Actual cuối; ngày sau D (chỉ khi month_end=true) chỉ có Estimate / Fluct (chưa có Actual).
//                  Tháng đã kết thúc: D = N.
//   Dòng tổng kết (luôn có, kể cả month_end=false / prefetch nhiều khung):
//     Estimate cuối tháng (cơ sở PL / FC) + Ngày dự kiến vượt Fluct (CmText.estimateCrossText).
//   thứ          = Thứ Hai..Thứ Bảy, Chủ nhật (en: Mon..Sun), chỉ để hiển thị
//   ngày nghỉ    = "nghỉ" khi F2_Working_Date.WD_All = 0 (nhà máy nghỉ); KHÔNG suy từ thứ trong tuần.
//                  Ngày không có dòng / lỗi đọc -> trống
//   WD_Office    = giá trị WD_Office (0 = văn phòng nghỉ, 1 = làm việc); không có -> trống
//   daily        = Act MTD hôm đó − Act MTD gần nhất trước đó
//   FluctDay     = Fluct cả tháng tính tại ngày đó (point.fluctTgt, đúng như tooltip chart)
//   Fluct theo tiến độ = FluctDay / N × ngày
//   % đã dùng    = Actual cộng dồn / FluctDay × 100
//   % thời gian  = ngày / N × 100
// So sánh trong ngày dùng FluctDay, không dùng Fluct cuối tháng.
// Dòng đối chiếu cuối: Σ chi trong ngày = Actual cộng dồn mới nhất (telescoping trên số 1 chữ số),
//   kèm danh sách ngày âm (trả hàng / điều chỉnh) – CostMonitoringDetailService dùng cùng cách tính.
// Tháng lấy từ tham số màn hình (model không tự đặt).
// =========================================================
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySeriesTool implements AssistantTool {

	// true = chuỗi đến hết tháng (ngày chưa tới có Estimate (MTD)); chỉ dùng khi hỏi đúng 1 khung
	static final String ARG_MONTH_END = "month_end";

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
				+ "Actual cộng dồn, Estimate (MTD) dự kiến, Fluct của chính ngày đó, Fluct theo tiến độ, % đã dùng, "
				+ "% thời gian), từ ngày 1 đến ngày có dữ liệu mới nhất, kèm Estimate cuối tháng và ngày dự kiến vượt Fluct. "
				+ "Dùng cho câu hỏi về xu hướng theo ngày, một ngày cụ thể, ngày nào chi nhiều, tăng đột biến, "
				+ "đến ngày X dự kiến bao nhiêu, khi nào vượt Fluct.";
	}

	@Override
	public Map<String, Object> parameters() {
		return CostMonitoringToolArgs.divCostSchema(CostMonitoringSnapshot.COSTS, Map.of(
				ARG_MONTH_END, Map.of(
						"type", "boolean",
						"description", "true = thêm các ngày chưa tới đến hết tháng (chỉ có Estimate (MTD)). "
								+ "Chỉ đặt true khi câu hỏi về ĐÚNG 1 khung và hỏi ngày chưa tới / khi nào vượt; "
								+ "hỏi nhiều khung thì bỏ trống (dùng dòng tóm tắt)."
				)
		));
	}

	@Override
	public String execute(Map<String, Object> args, Map<String, String> screenParams, String lang) {

		String div = CostMonitoringToolArgs.div(args, screenParams);
		String cost = CostMonitoringToolArgs.cost(args, CostMonitoringSnapshot.COSTS, screenParams);

		CostMonitoringSnapshot snapshot = loader.load(screenParams);
		CostChartDto chart = snapshot.find(div, cost);

		String header = div + " " + cost + t(lang, "|Tháng=", "|Month=") + snapshot.month();

		if (chart == null || snapshot.d() < 1) {
			return header + "|" + t(lang, "Không có dữ liệu", "No data");
		}

		// OT: không có Actual theo ngày / Estimate – không trả chuỗi chỉ có Fluct (test 2026-11-10 ext-82: model đọc
		// Fluct 53.2 K$ thành "chi phí ngày 28")
		if ("OT".equals(cost)) {
			return header + "|" + t(lang,
					"OT chưa có Actual theo ngày (không có chi trong ngày, Estimate hay ngày dự kiến vượt); chỉ có FC / Fluct. "
							+ "Không nêu số theo ngày cho OT.",
					"OT has no daily Actual (no daily spending, Estimate or expected exceed day); FC / Fluct only. "
							+ "Do not give daily figures for OT.");
		}

		int n = snapshot.n();
		LocalDate from = snapshot.month().atDay(1);
		// month_end=true (1 khung): đến cuối tháng, ngày sau D chỉ có Estimate (MTD) / Fluct; khác: đến ngày D
		boolean monthEnd = monthEnd(args);
		LocalDate until = monthEnd ? snapshot.month().atEndOfMonth() : snapshot.month().atDay(snapshot.d());

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
						+ "|chi trong ngày|Actual cộng dồn|Estimate (MTD, dự kiến cộng dồn)|Fluct (giá trị trong ngày)"
						+ "|Fluct theo tiến độ|% đã dùng|% thời gian"
						+ (monthEnd ? " (ngày sau D chưa tới: chỉ có Estimate, không phải Actual)" : ""),
				"[Daily cost] cols=day|weekday|holiday (factory, WD_All=0)|WD_Office (0 = office closed)"
						+ "|spent that day|cumulative actual|Estimate (MTD, expected cumulative)|Fluct (value on that day)"
						+ "|Fluct by pace|% used|% of time"
						+ (monthEnd ? " (days after D have not come yet: Estimate only, not Actual)" : ""))).append('\n');

		Double lastMtd = 0.0;
		// Σ chi trong ngày (1 chữ số) và các ngày âm, để đối chiếu với Actual cộng dồn
		double dailySum = 0.0;
		boolean hasActual = false;
		List<String> negativeDays = new ArrayList<>();

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
				hasActual = true;
				dailySum = CmText.r1(dailySum + daily);
				if (daily < 0) {
					negativeDays.add(String.format("%02d", day) + " (" + fmt(daily) + ")");
				}
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
					.append('|').append(fmt(point.estMtd()))
					.append('|').append(fmt(fluctDay))
					.append('|').append(fmt(fluctByPace))
					.append('|').append(pct(usedPct))
					.append('|').append(pct(timePct))
					.append('\n');
		}

		// Đối chiếu: Σ chi trong ngày = Actual cộng dồn mới nhất (cùng số làm tròn 1 chữ số).
		// Model không tự cộng một vài ngày rồi so với Actual; ngày âm (trả hàng / điều chỉnh) cũng được tính.
		if (hasActual) {
			sb.append(t(lang, "Tổng chi trong ngày (mọi ngày đến ngày có actual mới nhất)=",
							"Sum of daily spending (all days up to latest actual)="))
					.append(fmt(dailySum)).append(" K$")
					.append(t(lang, "|Actual cộng dồn mới nhất=", "|Latest cumulative actual="))
					.append(fmt(lastMtd)).append(" K$")
					.append(t(lang, "|Ngày âm (trả hàng / điều chỉnh)=", "|Negative days (returns / adjustments)="))
					.append(negativeDays.isEmpty() ? CmText.none(lang) : String.join(", ", negativeDays))
					.append('\n');
		}

		// Estimate cuối tháng + ngày dự kiến vượt Fluct (tính sẵn, model không tự dò cột Estimate)
		if (!snapshot.monthClosed() && chart.estMonthEnd() != null) {
			String base = CmText.forecastBase(chart.plTgt() != null ? "PL" : "FC", lang);
			sb.append(t(lang, "Estimate cuối tháng (", "Month-end Estimate ("))
					.append(base).append(")=").append(fmt(chart.estMonthEnd())).append(" K$");
			String cross = CmText.estimateCrossText(chart, lang);
			if (cross != null) {
				sb.append(t(lang, "|Ngày dự kiến vượt Fluct (theo Estimate)=", "|Expected day to exceed Fluct (by Estimate)="))
						.append(cross);
			}
			sb.append('\n');
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

	// month_end: Boolean hoặc chuỗi "true"; thiếu / khác -> false
	private static boolean monthEnd(Map<String, Object> args) {
		Object value = args.get(ARG_MONTH_END);
		return value instanceof Boolean b ? b : value != null && "true".equalsIgnoreCase(value.toString().trim());
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

		// Viết đầy đủ: "T3" làm model viết "thứ T3" (audit 2026-11-10)
		return switch (dow) {
			case MONDAY -> "Thứ Hai";
			case TUESDAY -> "Thứ Ba";
			case WEDNESDAY -> "Thứ Tư";
			case THURSDAY -> "Thứ Năm";
			case FRIDAY -> "Thứ Sáu";
			case SATURDAY -> "Thứ Bảy";
			case SUNDAY -> "Chủ nhật";
		};
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
