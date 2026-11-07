package com.example.backlogbe.assistant.screens.costmonitoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// =========================================================
// NHÃN HIỂN THỊ CHO MODEL (Cost Monitoring), theo lang "vi" | "en"
//
// Context và tool chỉ gửi nhãn dễ đọc, không gửi mã nội bộ.
// Mã nội bộ (CostMonitoringAssessment, ExplainVarianceTool...) chỉ dùng trong code.
// Bảng mã -> nhãn cho bước hậu xử lý câu trả lời: resources/assistant/screens/cost-monitoring/codes.json.
// =========================================================
public final class CmText {

	private CmText() {
	}

	public static boolean isEn(String lang) {
		return "en".equals(lang);
	}

	// Chọn nhãn theo lang (mặc định tiếng Việt)
	public static String t(String lang, String vi, String en) {
		return isEn(lang) ? en : vi;
	}


	// =========================================================
	// TRẠNG THÁI -> NHÃN
	// =========================================================

	public static String badge(String code, String lang) {

		return switch (code) {
			case "OK" -> "OK";
			case "OVER" -> t(lang, "Vượt", "Over");
			case "NO_ACTUAL" -> t(lang, "Chưa có actual", "No actual yet");
			case "NO_TARGET" -> t(lang, "Chưa có target", "No target");
			default -> noData(lang);
		};
	}

	public static String pace(String code, String lang) {

		return switch (code) {
			case CostMonitoringAssessment.FASTER_THAN_PACE -> t(lang, "Chi nhanh hơn tiến độ", "Spending faster than pace");
			case CostMonitoringAssessment.ON_PACE -> t(lang, "Đúng tiến độ", "On pace");
			case CostMonitoringAssessment.SLOWER_THAN_PACE -> t(lang, "Chi chậm hơn tiến độ", "Spending slower than pace");
			default -> noData(lang);
		};
	}

	public static String risk(String code, String lang) {

		return switch (code) {
			case CostMonitoringAssessment.RISK_HIGH -> t(lang, "Rủi ro cao", "High risk");
			case CostMonitoringAssessment.RISK_WATCH -> t(lang, "Cần theo dõi", "Needs watching");
			case CostMonitoringAssessment.RISK_OK -> t(lang, "Ổn", "OK");
			case CostMonitoringAssessment.MONTH_CLOSED -> t(lang, "Tháng đã kết thúc", "Month closed");
			default -> noData(lang);
		};
	}

	// "Dự báo vượt Fluct +12.3 K$" / "Dự báo trong Fluct, còn dư 4.5 K$"
	public static String forecastOutlook(CostMonitoringAssessment a, String lang) {

		return switch (a.forecastStatus()) {
			case CostMonitoringAssessment.WILL_EXCEED -> t(lang, "Dự báo vượt Fluct ", "Forecast to exceed Fluct by ")
					+ signedK(a.forecastGap());
			case CostMonitoringAssessment.WITHIN -> t(lang, "Dự báo trong Fluct, còn dư ", "Forecast within Fluct, remaining ")
					+ k1(a.forecastGap());
			default -> noData(lang);
		};
	}


	// =========================================================
	// KỊCH BẢN CUỐI THÁNG (dùng chung context và explain_variance)
	//
	//   "Nếu từ nay chi đúng target: cuối tháng=205.3 K$; so với Fluct=+5.1 K$ (vượt Fluct); so với FC=-3.0 K$ (dưới FC)"
	//   "Nếu giữ tốc độ hiện tại: tốc độ=9.88 K$/ngày; cuối tháng=296.4 K$; so với Fluct=…; so với FC=…"
	//   "Ngân sách còn lại mỗi ngày=1.25 K$/ngày; Nhịp target mỗi ngày=6.45 K$/ngày (Fluct / N); <so sánh>"
	// Bên trong mỗi phần dùng "; " (không dùng "|") để ghép được vào dòng context.
	// =========================================================

	public static List<String> scenarioParts(CostMonitoringAssessment a, String lang) {

		List<String> parts = new ArrayList<>();

		// Nếu từ nay chi đúng target
		StringBuilder target = new StringBuilder(
				t(lang, "Nếu từ nay chi đúng target: ", "If spending follows target from now: "));
		if (a.targetScenario() == null) {
			target.append(noData(lang));
		} else {
			target.append(t(lang, "cuối tháng=", "month-end=")).append(k1(a.targetScenario()));
			appendVs(target, a.targetScenarioVsFluct(), "Fluct", lang);
			appendVs(target, a.targetScenarioVsFc(), "FC", lang);
		}
		parts.add(target.toString());

		// Nếu giữ tốc độ hiện tại
		StringBuilder linear = new StringBuilder(
				t(lang, "Nếu giữ tốc độ hiện tại: ", "If current rate continues: "));
		if (a.linearForecast() == null) {
			linear.append(noData(lang));
		} else {
			linear.append(t(lang, "tốc độ=", "rate=")).append(rate(a.currentRate(), lang))
					.append(t(lang, "; cuối tháng=", "; month-end=")).append(k1(a.linearForecast()));
			appendVs(linear, a.linearVsFluct(), "Fluct", lang);
			appendVs(linear, a.linearVsFc(), "FC", lang);
		}
		parts.add(linear.toString());

		// Ngân sách còn lại mỗi ngày vs Nhịp target mỗi ngày
		StringBuilder budget = new StringBuilder(t(lang, "Ngân sách còn lại mỗi ngày=", "Remaining budget per day="));
		budget.append(a.remainingPerDay() == null ? noData(lang) : signedRate(a.remainingPerDay(), lang))
				.append(t(lang, "; Nhịp target mỗi ngày=", "; Target daily rate="))
				.append(a.fluctDailyRate() == null ? noData(lang) : rate(a.fluctDailyRate(), lang) + " (Fluct / N)");

		String compare = budgetVsRate(a.remainingPerDay(), a.fluctDailyRate(), lang);
		if (!compare.isEmpty()) {
			budget.append("; ").append(compare);
		}
		parts.add(budget.toString());

		return parts;
	}

	// "; so với Fluct=+5.1 K$ (vượt Fluct)" / "; so với FC=-3.0 K$ (dưới FC, còn dư)"
	private static void appendVs(StringBuilder sb, Double diff, String name, String lang) {

		String text = signedK(diff,
				t(lang, "vượt ", "above ") + name,
				t(lang, "dưới " + name + ", còn dư", "below " + name + ", headroom"));

		if (text.isEmpty()) {
			return;
		}

		if (text.startsWith("0.0")) {
			text = text + t(lang, " (bằng " + name + ")", " (equal to " + name + ")");
		}

		sb.append(t(lang, "; so với ", "; vs ")).append(name).append('=').append(text);
	}

	// So ngân sách còn lại mỗi ngày với nhịp target (code tự so sánh, model không tự tính)
	private static String budgetVsRate(Double remainingPerDay, Double dailyRate, String lang) {

		if (remainingPerDay == null || dailyRate == null) {
			return "";
		}

		if (remainingPerDay < 0) {
			return t(lang, "đã hết ngân sách (Actual đã vượt Fluct)", "budget used up (Actual already above Fluct)");
		}

		String diff = String.format(Locale.ROOT, "%.2f", remainingPerDay - dailyRate);

		if (diff.equals("0.00") || diff.equals("-0.00")) {
			return t(lang, "bằng nhịp target", "equal to target daily rate");
		}

		return remainingPerDay < dailyRate
				? t(lang,
				"thấp hơn nhịp target: phải chi chậm hơn nhịp target mới không vượt Fluct",
				"below target daily rate: spending must be slower than target rate to stay within Fluct")
				: t(lang,
				"cao hơn nhịp target: chi đúng nhịp target vẫn không vượt Fluct",
				"above target daily rate: spending at target rate stays within Fluct");
	}

	// "+1.25 K$/ngày" / "-0.50 K$/ngày"; null -> trống
	private static String signedRate(Double value, String lang) {

		String text = rate(value, lang);

		return !text.isEmpty() && value > 0 ? "+" + text : text;
	}

	// Tháng đã kết thúc: "Vượt Fluct +12.3 K$" / "Trong Fluct, còn dư 4.5 K$" / "Bằng Fluct"; thiếu số -> chưa đủ dữ liệu
	public static String monthResult(CostMonitoringAssessment a, String lang) {

		Double gap = a.monthResultGap();

		if (gap == null || gap.isNaN() || gap.isInfinite()) {
			return noData(lang);
		}

		String text = signedK(gap);

		if (text.startsWith("0.0")) {
			return t(lang, "Bằng Fluct", "Equal to Fluct");
		}

		return gap > 0
				? t(lang, "Vượt Fluct ", "Over Fluct by ") + text
				: t(lang, "Trong Fluct, còn dư ", "Within Fluct, remaining ") + k1(-gap);
	}

	public static String monthStatus(boolean closed, String lang) {
		return closed
				? t(lang, "đã kết thúc", "closed")
				: t(lang, "đang diễn ra", "in progress");
	}

	public static String forecastBase(String base, String lang) {

		if ("PL".equals(base)) {
			return t(lang, "theo PL", "based on PL");
		}

		if ("Fluct".equals(base)) {
			return t(lang, "theo Fluct", "based on Fluct");
		}

		return "";
	}

	public static String mode(String code, String lang) {

		return switch (code) {
			case CostMonitoringAssessment.MODE_IN_PROGRESS ->
					t(lang, "Chưa vượt Fluct, phân tích so với tiến độ", "Not over Fluct yet, analysed against pace");
			case CostMonitoringAssessment.MODE_OVER ->
					t(lang, "Đã vượt Fluct, tách phần target bị hạ và phần chi vượt FC",
							"Over Fluct, split into target cut and spending above FC");
			case CostMonitoringAssessment.MODE_CLOSED_WITHIN ->
					t(lang, "Tháng đã kết thúc, trong Fluct", "Month closed, within Fluct");
			default -> noData(lang);
		};
	}

	public static String mainCause(String code, String lang) {

		return switch (code) {
			case ExplainVarianceTool.CAUSE_EARLY_OVERSPEND ->
					t(lang, "Chưa vượt nhưng đang chi nhiều hơn tiến độ", "Not over yet but spending ahead of pace");
			case ExplainVarianceTool.CAUSE_ON_TRACK -> t(lang, "Chi trong tiến độ", "Spending within pace");
			case ExplainVarianceTool.CAUSE_TARGET_CUT ->
					t(lang, "Vượt chủ yếu do target bị hạ (doanh số/tồn kho thấp)",
							"Over mainly because the target was cut (low sales/inventory)");
			case ExplainVarianceTool.CAUSE_OVER_SPEND ->
					t(lang, "Vượt chủ yếu do chi nhiều hơn FC", "Over mainly because spending exceeded FC");
			case ExplainVarianceTool.CAUSE_BOTH ->
					t(lang, "Vượt do cả target bị hạ và chi nhiều hơn FC",
							"Over because of both target cut and spending above FC");
			case ExplainVarianceTool.CAUSE_NONE -> t(lang, "Actual gần bằng Fluct", "Actual is close to Fluct");
			default -> noData(lang);
		};
	}

	public static String noData(String lang) {
		return t(lang, "Chưa đủ dữ liệu", "Not enough data");
	}

	public static String none(String lang) {
		return t(lang, "(không có)", "(none)");
	}


	// =========================================================
	// VẬT TƯ
	// =========================================================

	private static final int ITEM_NAME_MAX = 40;

	// "Tên (Code)"; tên lấy từ [Text], cắt tối đa 40 ký tự (+ "…").
	// Thiếu tên -> chỉ Code; thiếu Code -> chỉ tên; thiếu cả 2 -> "(trống)" / "(blank)".
	public static String itemLabel(String code, String name, String lang) {

		String c = clean(code);
		String n = clean(name);

		if (n.length() > ITEM_NAME_MAX) {
			n = n.substring(0, ITEM_NAME_MAX).trim() + "…";
		}

		if (n.isEmpty() && c.isEmpty()) {
			return t(lang, "(trống)", "(blank)");
		}

		if (n.isEmpty()) {
			return c;
		}

		return c.isEmpty() ? n : n + " (" + c + ")";
	}

	// "|" / xuống dòng trong dữ liệu sẽ làm lệch cột
	public static String clean(String value) {
		return value == null ? "" : value.replace('|', '/').replaceAll("[\\r\\n]+", " ").trim();
	}


	// =========================================================
	// SỐ
	// =========================================================

	// Làm tròn đúng như số hiển thị (String.format %.1f / %.2f, HALF_UP), để mọi phép cộng / trừ
	// hiển thị tính từ số người dùng nhìn thấy: người dùng tự trừ 2 số trên màn hình ra đúng kết quả.
	// null / NaN / vô cực -> null
	public static Double r1(Double value) {
		return round(value, "%.1f");
	}

	public static Double r2(Double value) {
		return round(value, "%.2f");
	}

	// r1(a) − r1(b), làm tròn lại 1 chữ số (bỏ sai số dấu phẩy động); thiếu số -> null
	public static Double subR1(Double a, Double b) {

		Double ra = r1(a);
		Double rb = r1(b);

		return ra == null || rb == null ? null : r1(ra - rb);
	}

	private static Double round(Double value, String format) {

		if (value == null || value.isNaN() || value.isInfinite()) {
			return null;
		}

		return Double.valueOf(String.format(Locale.ROOT, format, value));
	}

	// "175.2 K$"; null -> trống
	public static String k1(Double value) {

		if (value == null || value.isNaN() || value.isInfinite()) {
			return "";
		}

		return String.format(Locale.ROOT, "%.1f K$", value);
	}

	// "+18.6 K$" / "-5.2 K$" / "0.0 K$"; null -> trống
	public static String signedK(Double value) {

		if (value == null || value.isNaN() || value.isInfinite()) {
			return "";
		}

		String number = String.format(Locale.ROOT, "%.1f", value);

		if (number.equals("0.0") || number.equals("-0.0")) {
			return "0.0 K$";
		}

		return (value > 0 ? "+" : "") + number + " K$";
	}

	// "+18.6 K$ (chi nhiều hơn tiến độ)"; = 0 -> "0.0 K$"; null -> trống
	public static String signedK(Double value, String positiveLabel, String negativeLabel) {

		String text = signedK(value);

		if (text.isEmpty() || text.startsWith("0.0")) {
			return text;
		}

		return text + " (" + (value > 0 ? positiveLabel : negativeLabel) + ")";
	}

	// "1.3 K$/ngày"; null -> trống
	public static String rate(Double value, String lang) {

		if (value == null || value.isNaN() || value.isInfinite()) {
			return "";
		}

		return String.format(Locale.ROOT, "%.2f", value) + t(lang, " K$/ngày", " K$/day");
	}

	// "19%" (số nguyên); null -> trống
	public static String pct0(Double value) {
		return value == null || value.isNaN() || value.isInfinite() ? "" : Math.round(value) + "%";
	}


	// Bảng mã nội bộ -> nhãn (hậu xử lý câu trả lời): resources/assistant/screens/cost-monitoring/codes.json
}
