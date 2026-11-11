package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.screen.AnswerGuide;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostItemAmountDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostLineDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostTopItemDto;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostTopItemsRepository;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostWorkingDateRepository;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostWorkingDateRepository.WorkingDay;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import com.example.backlogbe.dto.costmonitoring.CostChartPointDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;

// =========================================================
// CÂU TRẢ LỜI TRA CỨU COST MONITORING (template, KHÔNG gọi LLM)
//
// Lý do (audit log 2026-11-09): các câu tra cứu đơn giản đi LLM mất 8–19 s / câu dù số đã có sẵn.
// Chạy TRƯỚC phân loại intent của CostMonitoringAnswerGuide; không khớp / thiếu dữ liệu -> null (luồng cũ).
//
// Loại câu hỏi (câu đã bỏ dấu, CostMonitoringIntentClassifier.fold):
//   top_detail       : máy / cost center / Category + (nhất / top / lớn …), vd "máy nào chi nhiều nhất",
//                      "cost center nào tốn nhất của guide". Mỗi khung xếp riêng; máy chỉ T&SL.
//   top_item         : vật tư + (nhất / top / lớn / mắc / đắt / chính …), vd "mã vật tư nào mắc nhất",
//                      "vật tư lớn trong press t&sl là gì". Không áp dụng cho máy / cost center / Category /
//                      1 ngày cụ thể (LLM + pre-fetch get_top_items lo).
//                      1 khung -> top 5; nhiều khung -> vật tư lớn nhất của từng khung.
//                      "mắc / đắt / expensive": thêm đơn giá TB = tổng chi / số lượng (xếp hạng vẫn theo tổng chi).
//   top_day          : "ngày nào / hôm nào" + "nhiều / cao / lớn nhất" -> ngày chi lớn nhất (+ 2 ngày kế tiếp).
//   remaining_budget : "còn lại / tối đa / được chi / ngân sách" + "mỗi ngày / 1 ngày / per day" ->
//                      (Fluct − Actual) / ngày lịch còn lại và / ngày nhà máy làm việc còn lại (Σ WD_All),
//                      so với tốc độ chi hiện tại (CostMonitoringAssessment.currentRate).
//   anomaly_today    : (bất thường / đột biến / unusual / spike) + (hôm nay / hôm qua / mới nhất / today) ->
//                      ngày có chi gần nhất của từng khung so với trung bình các ngày có chi khác trong tháng
//                      (đột biến: ≥ SPIKE_RATIO lần và ≥ SPIKE_MIN_K) + khoản lớn bất thường trong ngày đó
//                      (1 dòng ≥ 10% Actual). CostMonitoringAnswerGuide ghép tiếp template tổng quan tháng
//                      (khung dự báo vượt) – giữ hành vi cũ "hôm nay = tháng tính đến ngày có actual mới nhất".
//   when_exceed      : (khi nào / bao giờ / lúc nào / when) + (vượt / over / exceed), không hỏi riêng FC / PL ->
//                      ngày dự kiến vượt Fluct theo Estimate (CostMonitoringSnapshot.estimateCrossDay) từng khung.
//   data_update      : (cập nhật / update) + (khi nào / chưa / mới nhất …) -> ngày có Actual T&SL / RM, OT không có.
//   estimate_concept : "Estimate là gì / cách tính" -> định nghĩa cố định.
//   ot_no_data       : when_exceed / day_spend chỉ về OT -> OT không có Actual theo ngày / Estimate.
//   when_exceed_fc   : (khi nào) + (vượt) + FC (không Fluct / PL / BP) -> ngày đầu tiên Estimate (MTD) > FC tháng.
//   week_compare     : "tuần trước" + (so với / tốc độ / nhanh / chậm) -> K$ / ngày làm việc tuần này so với tuần trước.
//   remaining_budget : thêm "phải giảm chi bao nhiêu" (REDUCE); dòng ghi cả K$/ngày cần giảm và %.
//   trend            : 1–3 khung (nhiều khung ghép template từng khung).
// Bỏ qua: câu hỏi nguyên nhân, so sánh, tháng trước / cùng kỳ; tháng đã kết thúc (trừ top_item / top_day).
// Khung: CostMonitoringQuestion.frame (câu hỏi + ô drawer); không xác định được -> bộ phận nêu trong câu (hoặc cả 3)
//   × loại chi phí nêu trong câu (hoặc T&SL, RM). OT không có actual -> bỏ.
// =========================================================
@Slf4j
@Component
@RequiredArgsConstructor
public class CostMonitoringLookupAnswers {

	static final String TOP_ITEM = "top_item";
	static final String TOP_DAY = "top_day";
	static final String REMAINING_BUDGET = "remaining_budget";
	static final String ANOMALY_TODAY = "anomaly_today";
	static final String TOP_DETAIL = "top_detail";
	static final String DAY_SPEND = "day_spend";
	static final String WEEK_SPEND = "week_spend";
	// "Vì sao Fluct của X bị hạ", "Target X được nâng hay hạ" (test 2026-11-10 ext-53: LLM nói Ratio +3.6% làm Fluct
	// thấp hơn FC – sai chiều; ext-55: không nêu K$)
	static final String TARGET_CHANGE = "target_change";
	// "Có ngày nào tăng đột biến không" (test 2026-11-10 ext-54: LLM trả lời không kèm số)
	static final String SPIKE_DAYS = "spike_days";
	// "X đã vượt FC rồi à / chưa" (test 2026-11-10 ext-12: LLM chỉ nói Actual chưa đạt FC, bỏ dự báo vượt FC +32.4 K$)
	static final String ALREADY_OVER = "already_over";
	private static final Pattern ALREADY = CostMonitoringQuestion.word(
			"da vuot|vuot roi|vuot chua|already over|already exceeded|already above");
	// "Ai là người chi nhiều nhất" (ext-30: LLM hiểu thành bộ phận, cộng T&SL + RM = 223 K$)
	static final String NO_PERSON = "no_person";
	private static final Pattern PERSON = CostMonitoringQuestion.word(
			"ai la nguoi|nguoi nao|nhan vien nao|ai chi|ai xai|ai dung|ai tieu|who spent|who spends|which person|which employee");
	private static final Pattern ANY_DAY = CostMonitoringQuestion.word(
			"ngay nao|co ngay|nhung ngay|cac ngay|hom nao|which day|which days|any day|any days");

	private static final Pattern TARGET_WORD = CostMonitoringQuestion.word("fluct|target|muc tieu|tgt");
	private static final Pattern TARGET_MOVE = CostMonitoringQuestion.word(
			// không nhận "hạ" / "nâng" đứng riêng: "hả" (trợ từ) bỏ dấu cũng là "ha"
			"bi ha|ha xuong|duoc nang|nang len|nang hay ha|giam|tang|thay doi|bien dong|dieu chinh"
					+ "|lowered|raised|cut|changed|change|lower|raise");
	// "vượt Fluct / target" là câu dự báo; "Fluct là gì" là khái niệm
	private static final Pattern TARGET_EXCLUDE = CostMonitoringQuestion.word(
			"vuot|exceed|over|la gi|what is|cach tinh|cong thuc|vat tu|may|cost center|category|ngay nao");
	static final String REMAINING_DAYS = "remaining_days";
	static final String GREETING = "greeting";
	static final String COMMON_ITEM = "common_item";
	static final String PREV_MONTH = "prev_month";
	static final String TREND = "trend";
	static final String FRAME_VALUE = "frame_value";
	static final String WHY_FRAME = "why_frame";
	// "Vật tư / Category nào làm X tăng (giảm) so với tháng trước": chênh cùng kỳ tính sẵn
	// (audit test 2026-11-10: qua LLM 50 s, ghi "+3.7%" là % tăng trong khi đó là chênh / Actual)
	static final String ITEM_CHANGE = "item_change";

	// "Khi nào / bao giờ X vượt Fluct" (test 2026-11-10 ext-73..77: qua LLM 28–42 s; ext-77 bỏ sót GUIDE T&SL và
	// ghép PRESS RM "theo tốc độ hiện tại"): ngày dự kiến vượt tính sẵn (CostMonitoringSnapshot.estimateCrossDay)
	static final String WHEN_EXCEED = "when_exceed";
	private static final Pattern WHEN_WORD = CostMonitoringQuestion.word("khi nao|bao gio|luc nao|when");
	private static final Pattern EXCEED_WORD = CostMonitoringQuestion.word("vuot|over|exceed|exceeds|exceeded");
	private static final Pattern OTHER_TARGET = CostMonitoringQuestion.word("fc|fc tgt|pl|pl tgt|bp");
	private static final Pattern FLUCT_WORD = CostMonitoringQuestion.word("fluct|fluc|target|muc tieu|ngan sach|budget");
	// "Khi nào X vượt FC" (test 2026-11-11 ext-93: LLM gán ngày vượt Fluct 28/11 cho FC): ngày đầu tiên Estimate (MTD) > FC
	static final String WHEN_EXCEED_FC = "when_exceed_fc";
	private static final Pattern FC_WORD = CostMonitoringQuestion.word("fc|fc tgt");
	// "So với tuần trước, tốc độ chi có giảm không" (ext-110: chỉ trả tổng tuần trước, không so)
	static final String WEEK_COMPARE = "week_compare";
	private static final Pattern WEEK_COMPARE_WORD = CostMonitoringQuestion.word(
			"so voi|toc do|nhanh hon|cham hon|nhanh|cham|tang hay giam|vs|faster|slower|pace|rate");
	// "Mỗi ngày X phải giảm chi bao nhiêu" (ext-102: qua LLM 59 s, trả 0.8 K$/ngày – sai) -> remaining_budget
	private static final Pattern REDUCE = CostMonitoringQuestion.word(
			"giam chi bao nhieu|giam bao nhieu|giam bot bao nhieu|cat giam bao nhieu|how much to cut|cut how much"
					+ "|reduce by how much|how much should we cut|how much must we cut"
					// văn nói (1440 ext-196 / 197: "kịp kéo về không", "phải siết bao nhiêu mỗi ngày" – LLM tính Fluct / 30)
					+ "|siet bao nhieu|phai siet|siet lai|keo ve|keo lai|kip keo|giam xai|bot xai");
	// "Khi nào dữ liệu cập nhật" (ext-84 / 85: LLM bịa "buổi sáng hôm sau"): chỉ nêu ngày có actual từ snapshot
	static final String DATA_UPDATE = "data_update";
	private static final Pattern UPDATE_WORD = CostMonitoringQuestion.word(
			"cap nhat|update|updated|updates|refresh|refreshed");
	private static final Pattern UPDATE_ASK = CostMonitoringQuestion.word(
			"khi nao|bao gio|luc nao|when|how often|bao lau|den ngay nao|den hom nao|moi nhat|chua|latest");
	// "Estimate là gì" (ext-92: LLM nói Estimate theo nhịp Fluct – sai)
	static final String ESTIMATE_CONCEPT = "estimate_concept";
	// "đường nét đứt trên chart" = Estimate (1440 ext-206: LLM nói là FC TGT)
	private static final Pattern ESTIMATE_WORD = CostMonitoringQuestion.word(
			"estimate|est|net dut|duong net dut|duong dut|duong gach|dashed line|dashed|dotted line");
	private static final Pattern CONCEPT_WORD = CostMonitoringQuestion.word(
			"la gi|nghia la|y nghia|cach tinh|tinh the nao|tinh nhu the nao|cong thuc|what is|what does|meaning|how is");
	// OT chỉ có FC / Fluct (không Actual theo ngày, không Estimate) – ext-82: LLM đọc Fluct làm "chi phí ngày 28"
	static final String OT_NO_DATA = "ot_no_data";

	// top_detail: xếp hạng theo máy / cost center / Category (mỗi khung xếp riêng, không cộng T&SL với RM)
	enum Dimension {
		MACHINE, COST_CENTER, CATEGORY
	}

	private static final List<String> COSTS = List.of("T&SL", "RM");

	private static final int TOP_ITEMS_ONE = 5;
	// "top 10 vật tư", "10 máy …": số dòng người dùng yêu cầu (1 khung), tối đa MAX_TOP
	private static final int MAX_TOP = 20;
	private static final Pattern TOP_N = CostMonitoringQuestion.word(
			"top (\\d{1,2})|(\\d{1,2}) (?:vat tu|ma vat tu|may|cost center|category|loai vat tu|items?|machines?)");
	private static final int TOP_DAYS_NEXT = 2;

	// Đột biến: chi ngày gần nhất ≥ 2 lần trung bình các ngày có chi khác, và ≥ 1.0 K$
	private static final double SPIKE_RATIO = 2.0;
	private static final double SPIKE_MIN_K = 1.0;
	private static final double UNUSUAL_SHARE = 0.10;
	private static final int MAX_UNUSUAL = 3;

	private static final Pattern WHY = CostMonitoringQuestion.word(
			"vi sao|tai sao|nguyen nhan|ly do|do dau|why|cause|reason");
	private static final Pattern EXCLUDED = CostMonitoringQuestion.word(
			"so sanh|thang truoc|cung ky|compare|previous month|last month|vs|versus");

	private static final Pattern ITEM = CostMonitoringQuestion.word(
			"vat tu|ma vat tu|ma hang|item|items|material|materials");
	private static final Pattern ITEM_RANK = CostMonitoringQuestion.word(
			"nhat|top|lon|chinh|mac|dat|nhieu|highest|most|biggest|largest|expensive|costliest");
	private static final Pattern EXPENSIVE = CostMonitoringQuestion.word("mac|dat|expensive|costliest");
	// Hỏi máy / cost center / Category: không phải danh sách vật tư
	private static final Pattern NOT_ITEM = CostMonitoringQuestion.word(
			"may|cost center|category|loai vat tu|machine|machines");

	private static final Pattern COST_CENTER = CostMonitoringQuestion.word(
			"cost center|cost centers|costcenter|cost centre|trung tam chi phi");
	private static final Pattern CATEGORY = CostMonitoringQuestion.word(
			"category|categories|loai vat tu|nhom vat tu");
	private static final Pattern MACHINE_EN = CostMonitoringQuestion.word("machine|machines");
	// "máy" có dấu (câu gốc); "may" không dấu trùng "mấy" -> chỉ nhận vài cụm rõ nghĩa
	// "nhà máy" (factory) không tính (test 2026-11-11 ext-114)
	private static final Pattern MACHINE_VI = Pattern.compile("(?<!\\p{L})(?<!nhà )máy(?!\\p{L})");
	private static final Pattern MACHINE_FOLDED = CostMonitoringQuestion.word(
			"may nao|top may|top \\d{1,2} may|cac may|may chi|may ton|may lon");

	private static final int TOP_DETAIL_ONE = 5;
	private static final int TOP_DETAIL_FEW = 3;
	private static final int TOP_DETAIL_FEW_FRAMES = 3;

	private static final Pattern WHICH_DAY =CostMonitoringQuestion.word("ngay nao|hom nao|which day|what day");
	private static final Pattern MOST = CostMonitoringQuestion.word(
			"nhieu nhat|cao nhat|lon nhat|ton nhat|most|highest|largest|biggest");

	private static final Pattern BUDGET = CostMonitoringQuestion.word(
			"con lai|toi da|duoc chi|ngan sach|remaining|max|maximum|budget|allowed"
					// 1440 ext-213 "Mỗi ngày còn xài được bao nhiêu?" (LLM: "1 540 USD")
					+ "|xai duoc|con xai|duoc xai|chi duoc");
	private static final Pattern PER_DAY = CostMonitoringQuestion.word(
			"moi ngay|1 ngay|mot ngay|hang ngay|per day|a day|each day|daily");

	private static final Pattern ANOMALY = CostMonitoringQuestion.word(
			"bat thuong|dot bien|abnormal|unusual|anomaly|anomalies|spike|spikes"
					// 1440 ext-205 "Hôm qua có gì lạ không?" (LLM trả chung chung)
					+ "|co gi la|gi la khong|co la khong|la thuong");
	private static final Pattern TODAY = CostMonitoringQuestion.word(
			"hom nay|hom qua|moi nhat|gan nhat|today|yesterday|latest");

	// 1 ngày cụ thể (audit 2026-11-10: "Chủ nhật vừa rồi…" chỉ tra PRESS T&SL và trả sai; "máy nào … hôm qua" lấy
	// số cả tháng; "Ngày 5 … chi bao nhiêu" thiếu thứ)
	private static final Pattern DAY_REF = CostMonitoringQuestion.word(
			"hom qua|hom kia|yesterday|chu nhat|sunday|saturday|thu [2-7]"
					+ "|ngay \\d{1,2}(?:/\\d{1,2})?|\\d{1,2}/\\d{1,2}");
	// Thứ trong tuần: khớp trên câu CÒN DẤU ("thứ tư" ≠ "thứ tự")
	private static final Pattern WEEKDAY_RAW = Pattern.compile(
			"(?<!\\p{L})thứ (hai|ba|tư|năm|sáu|bảy)(?!\\p{L})");
	// Nhiều ngày / xu hướng -> không phải 1 ngày
	private static final Pattern ACROSS_DAYS = CostMonitoringQuestion.word(
			"theo ngay|tung ngay|cac ngay|moi ngay|hang ngay|dot bien|xu huong|daily|by day|trend");
	private static final Pattern WEEK = CostMonitoringQuestion.word("tuan nay|tuan truoc|this week|last week");
	private static final Pattern LAST_WEEK = CostMonitoringQuestion.word("tuan truoc|last week");
	private static final Pattern SPEND = CostMonitoringQuestion.word(
			"chi|phat sinh|ton|het|bao nhieu|spend|spent|spending|cost|costs");
	private static final Pattern DAY_NUMBER = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(?:ngay\\s+(\\d{1,2})(?:/(\\d{1,2}))?|(\\d{1,2})/(\\d{1,2}))(?![\\p{N}])");
	private static final Pattern WEEKDAY = Pattern.compile(
			"(?<![\\p{L}])(chu nhat|sunday|saturday|thu [2-7])(?![\\p{L}\\p{N}])");

	// So với tháng trước (chỉ tổng FC / Fluct / Actual; không hỏi vật tư / Category / nguyên nhân / bất thường)
	private static final Pattern CHANGE_UP = CostMonitoringQuestion.word(
			"tang|tang len|tang manh|increase|increased|rise|rising|rose|up|grow|grew");
	private static final Pattern CHANGE_DOWN = CostMonitoringQuestion.word(
			"giam|giam xuong|giam manh|decrease|decreased|fall|falling|fell|drop|dropped|down");
	private static final int ITEM_CHANGE_ONE = 5;
	private static final int ITEM_CHANGE_FEW = 3;

	private static final Pattern PREV_MONTH_REF = CostMonitoringQuestion.word(
			"thang truoc|cung ky|last month|previous month");
	private static final Pattern PREV_MONTH_SCOPE = CostMonitoringQuestion.word(
			"so voi|so sanh|hon|thi sao|the nao|nhu the nao|khac gi|compare|compared|vs|versus|than|how");
	private static final Pattern PREV_MONTH_EXCLUDE = CostMonitoringQuestion.word(
			"vat tu|ma vat tu|category|loai vat tu|item|items|material|may|machine|cost center"
					+ "|bat thuong|dot bien|unusual|abnormal|ngay|day|days|ratio|fluct bi|target bi");

	// Xu hướng chi theo ngày trong tháng của 1 khung
	private static final Pattern TREND_WORD = CostMonitoringQuestion.word(
			"xu huong|dien bien|trend|tiến độ chi|tien do chi|pace");

	// Hỏi giá trị (Actual / FC / Fluct / PL) của 1 khung: "X tháng này bao nhiêu", "Fluct của X là bao nhiêu"
	private static final Pattern VALUE_ASK = CostMonitoringQuestion.word(
			"bao nhieu|la bao nhieu|bn|bnhieu|how much|what is the|hien tai|hien nay|dang la");
	private static final Pattern METRIC = CostMonitoringQuestion.word(
			"actual|act|fc|fc tgt|fluct|fluc|pl|pl tgt|target|tgt|forecast|chi phi|chi|spent|spending|cost");
	private static final Pattern TARGET_METRIC = CostMonitoringQuestion.word("fluct|fluc|fc|fc tgt|pl|pl tgt|target|tgt");
	private static final Pattern NON_TARGET = CostMonitoringQuestion.word(
			"actual|act|chi phi|chi|spent|spending|du bao|du kien|estimate|forecast|vuot|exceed");
	private static final Pattern END_OF_MONTH = CostMonitoringQuestion.word("cuoi thang|month-end|month end");
	private static final Pattern VALUE_EXCLUDE = CostMonitoringQuestion.word(
			"vuot|du bao|cuoi thang|cu chi|exceed|forecast to|month-end|month end|ngan sach|con lai|toi da"
					+ "|vat tu|may|cost center|category|item|machine|ratio|ngay|hom|day|yesterday"
					+ "|tuan|week|thang truoc|cung ky|last month|doanh thu|revenue");

	// Ngân sách còn lại (không cần "mỗi ngày")
	private static final Pattern BUDGET_LEFT = CostMonitoringQuestion.word(
			"con bao nhieu tien|con lai bao nhieu|bao nhieu tien|con bao nhieu de chi|con du bao nhieu"
					+ "|budget left|remaining budget|how much is left|how much left");

	private static final Pattern DAYS_LEFT = CostMonitoringQuestion.word(
			"con may ngay|con bao nhieu ngay|con lai may ngay|con lai bao nhieu ngay|may ngay nua|bao nhieu ngay nua"
					+ "|days left|days remaining|how many days");

	private static final Pattern GREETING_ONLY = Pattern.compile(
			"^(?:hello|hi|hey|xin chao|chao|chao ban|alo|good (?:morning|afternoon|evening))(?:\\s+(?:ban|em|bot|there))?[\\s!.?]*$");

	// Mã vật tư chung giữa các khung
	private static final Pattern COMMON = CostMonitoringQuestion.word(
			"o ca|ca hai|ca 2|chung|trung nhau|both|in common|common|shared");
	private static final int COMMON_TOP_N = 50;
	private static final int COMMON_MAX_LINES = 10;

	private final CostMonitoringSnapshotLoader loader;
	private final CostTopItemsRepository repository;
	private final CostWorkingDateRepository workingDateRepository;

	/**
	 * @return AnswerGuide có directAnswer (template) hoặc null (không khớp / thiếu dữ liệu -> luồng cũ)
	 */
	public AnswerGuide guide(String question, Map<String, String> params, String lang) {

		if (question == null || question.isBlank()) {
			return null;
		}

		String f = CostMonitoringIntentClassifier.fold(question);

		// Chào hỏi: không cần dữ liệu
		if (GREETING_ONLY.matcher(f).matches()) {
			Answer greeting = greeting(lang);
			return new AnswerGuide(null, false, GREETING, greeting.text(), greeting.suggestions(),
					"lookup=" + GREETING + "; template=yes");
		}

		// Vật tư / Category / máy / cost center tăng (giảm) so với tháng trước – kể cả câu "vì sao …"
		String kind = itemChangeKind(question, f);

		if (kind == null && TARGET_WORD.matcher(f).find() && TARGET_MOVE.matcher(f).find()
				&& !TARGET_EXCLUDE.matcher(f).find()) {
			kind = TARGET_CHANGE;
		}

		if (kind == null && WHY.matcher(f).find()) {
			return null;
		}

		// So với tháng trước (tổng FC / Fluct / Actual) của 1 khung: template; các câu "tháng trước" khác -> LLM
		if (kind == null) {
			kind = prevMonthKind(question, f);
		}

		if (kind == null && EXCLUDED.matcher(f).find()) {
			return null;
		}

		if (kind == null) {
			kind = kind(question, f);
		}

		if (kind == null) {
			return null;
		}

		// Khái niệm Estimate: không cần dữ liệu
		if (ESTIMATE_CONCEPT.equals(kind)) {
			Answer concept = estimateConcept(lang);
			return new AnswerGuide(null, false, kind, concept.text(), concept.suggestions(),
					"lookup=" + kind + "; template=yes");
		}

		Map<String, String> safeParams = params != null ? params : Map.of();
		CostMonitoringSnapshot snapshot = loader.load(safeParams);

		if (DATA_UPDATE.equals(kind)) {
			Answer update = dataUpdate(snapshot, lang);
			return new AnswerGuide(null, false, kind, update.text(), update.suggestions(),
					"lookup=" + kind + "; template=yes");
		}

		// Chỉ hỏi OT: không có Actual theo ngày / Estimate / ngày dự kiến vượt -> nói rõ, không qua LLM
		if ((WHEN_EXCEED.equals(kind) || WHEN_EXCEED_FC.equals(kind) || DAY_SPEND.equals(kind))
				&& otOnly(question, safeParams)) {
			Answer ot = otNoData(lang);
			return new AnswerGuide(null, false, OT_NO_DATA, ot.text(), ot.suggestions(),
					"lookup=" + kind + "; cost=OT; template=yes");
		}

		List<CostMonitoringFocus> frames = frames(question, safeParams);

		if (frames.isEmpty() || snapshot.d() < 1) {
			return null;
		}

		// 1 ngày cụ thể (hôm qua, Chủ nhật, ngày 5…): không xác định được / ngoài tháng đang xem -> LLM;
		// ngày trong tháng nhưng sau hôm nay -> trả lời "chưa tới"
		LocalDate day = null;
		if (singleDay(question, f)) {
			LocalDate future = futureDay(f, snapshot.month(), loader.today());
			if (future != null && (DAY_SPEND.equals(kind) || TOP_DETAIL.equals(kind) || TOP_ITEM.equals(kind))) {
				Answer futureAnswer = futureDayAnswer(snapshot, frames, future, lang);
				return new AnswerGuide(null, false, DAY_SPEND, futureAnswer.text(), futureAnswer.suggestions(),
						"lookup=" + kind + "; day=" + future + " (future); template=yes");
			}
			day = resolveDay(question, f, snapshot.month(), loader.today());
		}

		if (DAY_SPEND.equals(kind) && day == null) {
			return null;
		}
		if ((TOP_DETAIL.equals(kind) || TOP_ITEM.equals(kind)) && singleDay(question, f) && day == null) {
			return null;
		}

		boolean closed = snapshot.monthClosed();
		Dimension dimension = dimension(question, f);
		Answer answer = switch (kind) {
			case TOP_DETAIL -> topDetail(snapshot, frames, dimension, day, requestedTop(f), lang);
			case TOP_ITEM -> topItem(snapshot, frames, EXPENSIVE.matcher(f).find(), day, requestedTop(f), lang);
			case TOP_DAY -> topDay(snapshot, frames, lang);
			case DAY_SPEND -> daySpend(snapshot, frames, day, lang);
			case WEEK_SPEND -> weekSpend(snapshot, frames, LAST_WEEK.matcher(f).find(), lang);
			case TARGET_CHANGE -> targetChange(snapshot, frames, lang);
			case SPIKE_DAYS -> spikeDays(snapshot, frames, lang);
			case ALREADY_OVER -> closed ? null : alreadyOver(snapshot, frames, lang);
			case NO_PERSON -> new Answer(t(lang,
					"Dữ liệu chi phí **không ghi theo người** (chỉ có bộ phận, loại chi phí, vật tư, máy, cost center), "
							+ "nên không xác định được ai chi nhiều nhất.",
					"Cost data is **not recorded by person** (only by division, cost type, item, machine, cost center), "
							+ "so it cannot tell who spent the most."),
					List.of(t(lang, "Máy nào chi nhiều nhất?", "Which machine spends the most?"),
							t(lang, "Cost center nào chi nhiều nhất?", "Which cost center spends the most?")));
			case COMMON_ITEM -> frames.size() < 2 ? null : commonItems(snapshot, frames, lang);
			case REMAINING_DAYS -> closed ? null : remainingDays(snapshot, lang);
			case REMAINING_BUDGET -> closed ? null : remainingBudget(snapshot, frames, lang);
			case ANOMALY_TODAY -> closed ? null : anomalyToday(snapshot, frames, lang);
			case PREV_MONTH -> frames.size() == 1 ? previousMonth(snapshot, frames.get(0), lang) : null;
			case ITEM_CHANGE -> itemChange(snapshot, frames, dimension,
					CHANGE_DOWN.matcher(f).find() && !CHANGE_UP.matcher(f).find(), WHY.matcher(f).find(), lang);
			case TREND -> closed || frames.size() > 3 ? null
					: frames.size() == 1 ? trend(snapshot, frames.get(0), lang) : trendMany(snapshot, frames, lang);
			case FRAME_VALUE -> frameValue(snapshot, frames, lang);
			case WHEN_EXCEED -> closed ? null : whenExceed(snapshot, frames, lang);
			case WHEN_EXCEED_FC -> closed ? null : whenExceedFc(snapshot, frames, lang);
			case WEEK_COMPARE -> closed ? null : weekCompare(snapshot, frames, lang);
			default -> null;
		};

		if (answer == null || answer.text() == null || answer.text().isBlank()) {
			return null;
		}

		String match = "lookup=" + kind
				+ (TOP_DETAIL.equals(kind) ? "; dim=" + dimension.name().toLowerCase(Locale.ROOT) : "")
				+ (day != null ? "; day=" + day : "")
				+ "; frames="
				+ String.join(",", frames.stream().map(fr -> fr.div() + " " + fr.cost()).toList())
				+ "; template=yes";

		// toolsSufficient = false: tắt template thì LLM vẫn tự gọi tool (không có pre-fetch tương ứng)
		return new AnswerGuide(null, false, kind, answer.text(), answer.suggestions(), match);
	}

	// Loại câu hỏi tra cứu; null = không khớp
	static String kind(String question, String f) {

		if (PERSON.matcher(f).find()) {
			return NO_PERSON;
		}

		if (ESTIMATE_WORD.matcher(f).find() && CONCEPT_WORD.matcher(f).find()) {
			return ESTIMATE_CONCEPT;
		}

		// Hỏi cập nhật Actual (không phải Fluct / Ratio / target – các số đó đổi theo Ratio, xem target_change)
		if (UPDATE_WORD.matcher(f).find() && UPDATE_ASK.matcher(f).find() && !EXCEED_WORD.matcher(f).find()
				&& !OTHER_TARGET.matcher(f).find() && !FLUCT_WORD.matcher(f).find()
				&& !CostMonitoringQuestion.word("ratio").matcher(f).find()) {
			return DATA_UPDATE;
		}

		// Chỉ khi có cả từ hỏi thời điểm và từ vượt (giống CostMonitoringIntentClassifier.WHEN + EXCEED);
		// hỏi mốc khác Fluct (FC / PL / BP) mà không nhắc Fluct -> LLM (ngày dự kiến chỉ tính theo Fluct)
		if (WHEN_WORD.matcher(f).find() && EXCEED_WORD.matcher(f).find()) {
			if (!OTHER_TARGET.matcher(f).find() || FLUCT_WORD.matcher(f).find()) {
				return WHEN_EXCEED;
			}
			// Chỉ hỏi FC (không PL / BP): ngày Estimate vượt FC; PL / BP -> LLM
			if (FC_WORD.matcher(f).find() && !CostMonitoringQuestion.word("pl|pl tgt|bp").matcher(f).find()) {
				return WHEN_EXCEED_FC;
			}
		}

		if (ALREADY.matcher(f).find() && !CostMonitoringQuestion.word("thang truoc|cung ky|last month").matcher(f).find()) {
			return ALREADY_OVER;
		}

		// Câu hỏi theo ngày chỉ được tra cứu khi hỏi đúng 1 ngày (hôm qua, Chủ nhật, ngày 5…)
		boolean dailyOk = !DailySeriesPrefetchRule.isDailyQuestion(question) || singleDay(question, f);
		Dimension dim = dimension(question, f);
		boolean item = ITEM.matcher(f).find() && !NOT_ITEM.matcher(f).find();

		if (dim != null && ITEM_RANK.matcher(f).find() && !WHICH_DAY.matcher(f).find() && dailyOk) {
			return TOP_DETAIL;
		}

		if (item && COMMON.matcher(f).find()) {
			return COMMON_ITEM;
		}

		if (item && ITEM_RANK.matcher(f).find() && dailyOk) {
			return TOP_ITEM;
		}

		if (DAYS_LEFT.matcher(f).find() && !PER_DAY.matcher(f).find()) {
			return REMAINING_DAYS;
		}

		if (WHICH_DAY.matcher(f).find() && MOST.matcher(f).find() && !ITEM.matcher(f).find()
				&& !NOT_ITEM.matcher(f).find()) {
			return TOP_DAY;
		}

		if ((BUDGET.matcher(f).find() && PER_DAY.matcher(f).find()) || BUDGET_LEFT.matcher(f).find()
				|| (REDUCE.matcher(f).find() && dim == null && !item)) {
			return REMAINING_BUDGET;
		}

		if (ANOMALY.matcher(f).find() && TODAY.matcher(f).find()) {
			return ANOMALY_TODAY;
		}

		if (ANOMALY.matcher(f).find() && ANY_DAY.matcher(f).find() && dim == null && !ITEM.matcher(f).find()) {
			return SPIKE_DAYS;
		}

		if (LAST_WEEK.matcher(f).find() && WEEK_COMPARE_WORD.matcher(f).find() && dim == null && !ITEM.matcher(f).find()
				&& !ANOMALY.matcher(f).find()) {
			return WEEK_COMPARE;
		}

		if (WEEK.matcher(f).find() && SPEND.matcher(f).find() && dim == null && !ITEM.matcher(f).find()
				&& !MOST.matcher(f).find() && !ANOMALY.matcher(f).find()) {
			return WEEK_SPEND;
		}

		if (singleDay(question, f) && SPEND.matcher(f).find() && !ANOMALY.matcher(f).find() && dim == null
				&& !ITEM.matcher(f).find() && !MOST.matcher(f).find()) {
			return DAY_SPEND;
		}

		if (TREND_WORD.matcher(f).find() && dim == null && !ITEM.matcher(f).find() && !ANOMALY.matcher(f).find()
				&& !CostMonitoringQuestion.word("ratio|fluct").matcher(f).find()) {
			return TREND;
		}

		// Chỉ hỏi target (Fluct / FC / PL) "cuối tháng là bao nhiêu": target là số cả tháng, không phải dự báo
		// (1303 ext-34: trả template dự báo thay vì giá trị Fluct)
		String fv = TARGET_METRIC.matcher(f).find() && !NON_TARGET.matcher(f).find()
				? END_OF_MONTH.matcher(f).replaceAll(" ") : f;

		if (VALUE_ASK.matcher(f).find() && METRIC.matcher(f).find() && !VALUE_EXCLUDE.matcher(fv).find()
				&& dim == null && !ITEM.matcher(f).find() && !MOST.matcher(f).find()
				&& !CostMonitoringQuestion.isConcept(CostMonitoringQuestion.normalize(question))) {
			return FRAME_VALUE;
		}

		return null;
	}

	// "Vật tư / Category nào làm X tăng / giảm so với tháng trước / cùng kỳ" -> ITEM_CHANGE; khác -> null
	static String itemChangeKind(String question, String f) {

		boolean subject = ITEM.matcher(f).find() || dimension(question, f) != null;
		boolean change = CHANGE_UP.matcher(f).find() || CHANGE_DOWN.matcher(f).find();

		// "Vì sao vật tư X tăng so với tháng trước" cũng vào đây (test 2026-11-10 ext-45: LLM nói tổng MOLD RM
		// tăng trong khi thực tế giảm -5.86 K$); template nêu chiều tổng trước (kiểm tra tiền đề)
		return subject && change && PREV_MONTH_REF.matcher(f).find() ? ITEM_CHANGE : null;
	}

	// "So với tháng trước X thế nào", "X tháng này chi nhiều hơn cùng kỳ không" (1 khung, chỉ tổng) -> PREV_MONTH; khác -> null
	static String prevMonthKind(String question, String f) {

		if (!PREV_MONTH_REF.matcher(f).find() || !PREV_MONTH_SCOPE.matcher(f).find()
				|| PREV_MONTH_EXCLUDE.matcher(f).find() || WHY.matcher(f).find()) {
			return null;
		}

		return PREV_MONTH;
	}

	// Ngày được hỏi nằm trong tháng đang xem nhưng SAU hôm nay (chưa tới); khác -> null
	static LocalDate futureDay(String f, YearMonth month, LocalDate today) {

		java.util.regex.Matcher n = DAY_NUMBER.matcher(f);

		if (!n.find()) {
			return null;
		}

		String dd = n.group(1) != null ? n.group(1) : n.group(3);
		String mm = n.group(1) != null ? n.group(2) : n.group(4);
		int d = Integer.parseInt(dd);

		if (mm != null && Integer.parseInt(mm) != month.getMonthValue()) {
			return null;
		}
		if (d < 1 || d > month.lengthOfMonth()) {
			return null;
		}

		LocalDate day = month.atDay(d);

		return day.isAfter(today) ? day : null;
	}

	/*
	 * Ngày chưa tới: chưa có Actual; kèm Estimate (MTD) của SP tại ngày đó cho từng khung được hỏi
	 *   (Actual cộng dồn dự kiến đến hết ngày đó, so với Fluct của chính ngày đó, số làm tròn 1 chữ số).
	 * Khung không có Estimate (vd OT) thì bỏ qua; không khung nào có -> chỉ câu "chưa tới".
	 */
	private static Answer futureDayAnswer(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames,
										  LocalDate day, String lang) {

		StringBuilder sb = new StringBuilder(t(lang,
				"Ngày **" + dayLabel(day, lang) + "** chưa tới nên chưa có Actual.",
				"**" + dayLabel(day, lang) + "** has not come yet, so there is no Actual."));

		List<String> lines = new ArrayList<>();

		for (CostMonitoringFocus frame : frames) {

			CostChartDto chart = snapshot.find(frame.div(), frame.cost());
			Double est = chart != null ? CostMonitoringSnapshot.estimateAt(chart, day) : null;

			if (est == null) {
				continue;
			}

			Double fluctDay = CostMonitoringSnapshot.fluctAt(chart, day);
			String base = CmText.forecastBase(chart.plTgt() != null ? "PL" : "FC", lang);
			String line = "- " + CostMonitoringSnapshot.divName(frame.div()) + " " + frame.cost() + ": "
					+ t(lang, "dự kiến cộng dồn khoảng **", "expected cumulative about **") + k1(est) + "**"
					+ " (" + base + ")";

			if (fluctDay != null) {
				// Vượt / không: số gốc (exceeds, giống FE); số chênh hiển thị từ số làm tròn 1 chữ số
				line += t(lang, "; Fluct ngày đó ", "; Fluct that day ") + k1(fluctDay)
						+ (CostMonitoringAssessment.exceeds(est, fluctDay)
						? t(lang, " → vượt Fluct ", " → over Fluct ") + CmText.signedK(CmText.subR1(est, fluctDay))
						: t(lang, " → trong Fluct, còn dư ", " → within Fluct, ") + k1(CmText.subR1(fluctDay, est))
						+ t(lang, "", " left"));
			}

			lines.add(line);
		}

		if (!lines.isEmpty()) {
			sb.append(t(lang, " Theo đường Estimate:", " By the Estimate line:")).append('\n')
					.append(String.join("\n", lines));
		}

		return new Answer(sb.toString(),
				List.of(t(lang, "Hôm qua có gì bất thường không?", "Anything unusual yesterday?"),
						t(lang, "Tháng này có khung nào dự báo vượt Fluct?", "Which frames are forecast to exceed Fluct?")));
	}

	/*
	 * "Khi nào X vượt Fluct": theo đường Estimate (CostMonitoringSnapshot.estimateCrossDay, quy tắc vượt exceeds).
	 * Nhóm (thứ tự hiển thị): 0 đã vượt thực tế | 1 dự kiến vượt (ngày sớm trước) | 2 không vượt trong tháng |
	 *   3 chưa có Actual / Estimate. Mỗi khung kèm dự báo cuối tháng chính (CmText.estimateBrief).
	 */
	private static Answer whenExceed(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, String lang) {

		record Row(int group, LocalDate cross, String name, String text) {
		}

		List<Row> rows = new ArrayList<>();

		for (CostMonitoringFocus fr : frames) {

			CostChartDto chart = snapshot.find(fr.div(), fr.cost());
			if (chart == null) {
				continue;
			}

			CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);
			String name = CostMonitoringSnapshot.divName(fr.div()) + " " + fr.cost();

			if (a.act() == null || a.fluct() == null) {
				rows.add(new Row(3, null, name, t(lang, "chưa có Actual nên chưa có Estimate", "no Actual yet, so no Estimate")));
				continue;
			}

			if (a.alreadyOver()) {
				rows.add(new Row(0, null, name, t(lang, "**đã vượt Fluct thực tế**: Actual ", "**already above Fluct**: Actual ")
						+ k1(a.act()) + t(lang, ", vượt Fluct ", ", over Fluct ") + CmText.signedK(CmText.subR1(a.act(), a.fluct()))));
				continue;
			}

			LocalDate cross = CostMonitoringSnapshot.estimateCrossDay(chart);

			if (cross != null) {
				rows.add(new Row(1, cross, name, t(lang, "dự kiến vượt Fluct từ **", "expected to exceed Fluct from **")
						+ dayLabel(cross, lang) + "**; " + CmText.estimateBrief(a, lang)));
			} else if (chart.estMonthEnd() != null) {
				rows.add(new Row(2, null, name, t(lang, "dự kiến **không vượt** Fluct trong tháng; ",
						"expected **not to exceed** Fluct this month; ") + CmText.estimateBrief(a, lang)));
			} else {
				rows.add(new Row(3, null, name, t(lang, "chưa có Estimate", "no Estimate yet")));
			}
		}

		if (rows.isEmpty()) {
			return null;
		}

		rows.sort(Comparator.comparingInt(Row::group)
				.thenComparing(Row::cross, Comparator.nullsLast(Comparator.naturalOrder())));

		StringBuilder sb = new StringBuilder();

		if (rows.size() == 1) {
			Row r = rows.get(0);
			sb.append("**").append(r.name()).append("** ").append(r.text()).append('\n');
		} else {
			long expected = rows.stream().filter(r -> r.group() == 1).count();
			long over = rows.stream().filter(r -> r.group() == 0).count();
			sb.append(t(lang, "Theo đường Estimate, **" + expected + "/" + rows.size() + "** khung dự kiến vượt Fluct trong tháng",
							"By the Estimate line, **" + expected + "/" + rows.size() + "** frame(s) are expected to exceed Fluct this month"))
					.append(over > 0 ? t(lang, ", " + over + " khung đã vượt thực tế", ", " + over + " already above Fluct") : "")
					.append(":\n");
			for (Row r : rows) {
				sb.append("- **").append(r.name()).append("**: ").append(r.text()).append('\n');
			}
		}

		sb.append('\n').append(t(lang,
				"_Ngày dự kiến vượt = ngày đầu tiên Estimate (MTD) vượt Fluct của chính ngày đó (Estimate theo PL; chưa có PL thì theo FC). ",
				"_Expected day = first day the Estimate (MTD) exceeds that day's Fluct (Estimate based on PL; FC when no PL). "))
				.append(footer(snapshot, lang).substring(1));

		Row first = rows.get(0);
		List<String> suggestions = first.group() <= 1
				? List.of(t(lang, "Vì sao " + first.name() + " dự báo vượt?", "Why is " + first.name() + " forecast to exceed?"),
				t(lang, "Ngân sách còn lại mỗi ngày của " + first.name(), "Remaining daily budget of " + first.name()))
				: List.of(t(lang, "Tháng này có khung nào dự báo vượt Fluct?", "Which frames are forecast to exceed Fluct?"));

		return new Answer(sb.toString().trim(), suggestions);
	}

	// "Khi nào dữ liệu cập nhật": chỉ nêu ngày có Actual mới nhất (T&SL / RM) từ snapshot, không đoán giờ cập nhật
	private static Answer dataUpdate(CostMonitoringSnapshot snapshot, String lang) {

		int tsl = 0;
		int rm = 0;

		for (CostChartDto chart : snapshot.response().charts()) {
			int day = snapshot.lastActualDay(chart);
			if ("T&SL".equals(chart.chart())) {
				tsl = Math.max(tsl, day);
			} else if ("RM".equals(chart.chart())) {
				rm = Math.max(rm, day);
			}
		}

		String none = t(lang, "chưa có", "none yet");
		StringBuilder sb = new StringBuilder(t(lang,
				"Số liệu Actual cập nhật theo ngày. Tháng " + snapshot.month() + " hiện có:",
				"Actual data is updated daily. Month " + snapshot.month() + " currently has:")).append('\n');
		sb.append(t(lang, "- T&SL: Actual đến **", "- T&SL: Actual up to **"))
				.append(tsl > 0 ? dayLabel(snapshot.month().atDay(tsl), lang) : none).append("**\n");
		sb.append(t(lang, "- RM: Actual đến **", "- RM: Actual up to **"))
				.append(rm > 0 ? dayLabel(snapshot.month().atDay(rm), lang) : none).append("**")
				.append(t(lang, " (RM ghi nhận chậm hơn T&SL khoảng 1 ngày)", " (RM is recorded about 1 day later than T&SL)"))
				.append('\n');
		sb.append(t(lang, "- OT: chưa có Actual (chỉ có FC / Fluct)", "- OT: no Actual (FC / Fluct only)")).append("\n\n");
		sb.append(t(lang, "_Màn hình không có thông tin giờ cập nhật cụ thể._", "_The screen has no exact update time._"));

		return new Answer(sb.toString(),
				List.of(t(lang, "Hôm qua có gì bất thường không?", "Anything unusual yesterday?"),
						t(lang, "Tháng này có khung nào dự báo vượt Fluct?", "Which frames are forecast to exceed Fluct?")));
	}

	// "Estimate là gì": định nghĩa cố định (khớp sp_CostMonitoringChart Est_MTD)
	private static Answer estimateConcept(String lang) {
		return new Answer(t(lang,
				"**Estimate (MTD)** (đường nét đứt trên chart) là chi phí cộng dồn **dự kiến** đến từng ngày:\n"
						+ "- Bắt đầu từ Actual của ngày có số mới nhất (tại ngày đó Estimate = Actual).\n"
						+ "- Các ngày sau cộng thêm PL TGT phân bổ theo ngày làm việc của nhà máy (ngày nghỉ không cộng); "
						+ "chưa có PL thì dùng FC TGT (ghi \"theo FC\").\n"
						+ "- Estimate ngày cuối tháng là dự báo cuối tháng chính, so với Fluct để biết dự báo vượt / trong Fluct.\n"
						+ "- Khung chưa có Actual (vd OT) thì không có Estimate.",
				"**Estimate (MTD)** (dashed line on the chart) is the **expected** cumulative cost up to each day:\n"
						+ "- It starts from the Actual of the latest day with data (on that day Estimate = Actual).\n"
						+ "- Later days add PL TGT spread over factory working days (holidays add nothing); "
						+ "without PL it uses FC TGT (\"based on FC\").\n"
						+ "- The month-end Estimate is the main month-end forecast, compared with Fluct to tell over / within.\n"
						+ "- Frames without Actual (e.g. OT) have no Estimate."),
				List.of(t(lang, "Khi nào có khung vượt Fluct?", "When will any frame exceed Fluct?"),
						t(lang, "Fluct là gì?", "What is Fluct?")));
	}

	// OT: chỉ có FC / Fluct
	private static Answer otNoData(String lang) {
		return new Answer(t(lang,
				"Khung **OT** chưa có Actual trên màn hình này (chỉ có FC / Fluct), nên không có số chi theo ngày, "
						+ "đường Estimate hay ngày dự kiến vượt.",
				"**OT** frames have no Actual on this screen (FC / Fluct only), so there is no daily spending, "
						+ "Estimate line or expected day to exceed."),
				List.of(t(lang, "Tháng này có khung nào dự báo vượt Fluct?", "Which frames are forecast to exceed Fluct?")));
	}

	// Câu hỏi chỉ về OT (câu hỏi / ô drawer)
	private static boolean otOnly(String question, Map<String, String> params) {

		CostMonitoringFocus one = CostMonitoringQuestion.frame(question, params);

		if (one != null) {
			return "OT".equals(one.cost());
		}

		return List.of("OT").equals(CostMonitoringQuestion.costs(CostMonitoringQuestion.normalize(question)));
	}

	// Số dòng người dùng yêu cầu ("top 10", "3 máy"); không nêu -> 0 (mặc định)
	static int requestedTop(String f) {
		java.util.regex.Matcher m = TOP_N.matcher(f);
		if (!m.find()) {
			return 0;
		}
		int n = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
		return n < 1 ? 0 : Math.min(n, MAX_TOP);
	}

	// Hỏi đúng 1 ngày: có mốc ngày, không hỏi "ngày nào", không hỏi nhiều ngày / xu hướng
	static boolean singleDay(String question, String f) {
		boolean ref = DAY_REF.matcher(f).find()
				|| WEEKDAY_RAW.matcher(CostMonitoringQuestion.normalize(question)).find();
		return ref && !WHICH_DAY.matcher(f).find() && !ACROSS_DAYS.matcher(f).find();
	}

	/*
	 * Ngày được hỏi (trong tháng đang xem, không sau hôm nay); không xác định được -> null
	 *   hôm qua / yesterday = today − 1; hôm kia = today − 2
	 *   Chủ nhật / thứ X (vừa rồi) = ngày gần nhất TRƯỚC hôm nay có thứ đó
	 *   ngày N / N/M = ngày N của tháng đang xem (M phải trùng tháng)
	 */
	static LocalDate resolveDay(String question, String f, YearMonth month, LocalDate today) {

		LocalDate day = null;
		java.util.regex.Matcher raw = WEEKDAY_RAW.matcher(CostMonitoringQuestion.normalize(question));

		if (CostMonitoringQuestion.word("hom kia").matcher(f).find()) {
			day = today.minusDays(2);
		} else if (CostMonitoringQuestion.word("hom qua|yesterday").matcher(f).find()) {
			day = today.minusDays(1);
		} else {
			java.util.regex.Matcher w = WEEKDAY.matcher(f);
			java.util.regex.Matcher n = DAY_NUMBER.matcher(f);
			if (n.find()) {
				String dd = n.group(1) != null ? n.group(1) : n.group(3);
				String mm = n.group(1) != null ? n.group(2) : n.group(4);
				int d = Integer.parseInt(dd);
				if (mm != null && Integer.parseInt(mm) != month.getMonthValue()) {
					return null;
				}
				if (d < 1 || d > month.lengthOfMonth()) {
					return null;
				}
				day = month.atDay(d);
			} else {
				// "chu nhat" / "thu 3" (đã bỏ dấu) hoặc "thứ ba" (còn dấu, bỏ dấu để tra bảng)
				String text = w.find() ? w.group(1)
						: raw.find() ? CostMonitoringIntentClassifier.fold("thứ " + raw.group(1)) : null;
				java.time.DayOfWeek dow = text == null ? null : weekdayOf(text);
				if (dow != null) {
					day = today.minusDays(1).with(java.time.temporal.TemporalAdjusters.previousOrSame(dow));
				}
			}
		}

		if (day == null || !YearMonth.from(day).equals(month) || day.isAfter(today)) {
			return null;
		}

		return day;
	}

	private static java.time.DayOfWeek weekdayOf(String text) {
		return switch (text) {
			case "chu nhat", "sunday" -> java.time.DayOfWeek.SUNDAY;
			case "saturday", "thu bay", "thu 7" -> java.time.DayOfWeek.SATURDAY;
			case "thu hai", "thu 2" -> java.time.DayOfWeek.MONDAY;
			case "thu ba", "thu 3" -> java.time.DayOfWeek.TUESDAY;
			case "thu tu", "thu 4" -> java.time.DayOfWeek.WEDNESDAY;
			case "thu nam", "thu 5" -> java.time.DayOfWeek.THURSDAY;
			case "thu sau", "thu 6" -> java.time.DayOfWeek.FRIDAY;
			default -> null;
		};
	}

	// Máy / cost center / Category được hỏi; null = không hỏi (Category ưu tiên vì "loại vật tư" chứa "vật tư")
	static Dimension dimension(String question, String f) {

		if (CATEGORY.matcher(f).find()) {
			return Dimension.CATEGORY;
		}

		if (COST_CENTER.matcher(f).find()) {
			return Dimension.COST_CENTER;
		}

		String raw = question == null ? "" : java.text.Normalizer
				.normalize(question, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

		if (MACHINE_VI.matcher(raw).find() || MACHINE_EN.matcher(f).find() || MACHINE_FOLDED.matcher(f).find()) {
			return Dimension.MACHINE;
		}

		return null;
	}

	// Đúng 1 khung (câu hỏi + drawer) -> [khung]; không -> bộ phận nêu trong câu (hoặc ô drawer, hoặc cả 3)
	// × loại chi phí nêu trong câu (hoặc T&SL, RM). OT bỏ.
	static List<CostMonitoringFocus> frames(String question, Map<String, String> params) {

		CostMonitoringFocus one = CostMonitoringQuestion.frame(question, params);

		if (one != null) {
			return COSTS.contains(one.cost()) ? List.of(one) : List.of();
		}

		String q = CostMonitoringQuestion.normalize(question);

		List<String> divs = CostMonitoringQuestion.divs(q);
		if (divs.isEmpty()) {
			divs = CostMonitoringSnapshot.DIVS;
		}

		List<String> costs = CostMonitoringQuestion.costs(q).stream().filter(COSTS::contains).toList();
		if (costs.isEmpty()) {
			costs = CostMonitoringQuestion.costs(q).isEmpty() ? COSTS : List.of();
		}

		List<CostMonitoringFocus> frames = new ArrayList<>();

		for (String cost : costs) {
			for (String div : divs) {
				frames.add(new CostMonitoringFocus(div, cost));
			}
		}

		return frames;
	}

	record Answer(String text, List<String> suggestions) {
	}


	// =========================================================
	// VẬT TƯ / CATEGORY TĂNG (GIẢM) SO VỚI CÙNG KỲ THÁNG TRƯỚC
	//
	// Kỳ: ngày 1 -> ngày actual mới nhất của CHÍNH khung (RM trễ 1 ngày), so với cùng số ngày tháng trước
	// (giống explain_variance). Chênh = số tháng này − cùng kỳ trên số đã làm tròn 2 chữ số. Chỉ chi phí trực tiếp.
	// =========================================================

	record Change(String label, double current, double previous) {
		double diff() {
			return CmText.r2(current - previous);
		}
	}

	private static final int ALL_ROWS = 5000;

	private Answer itemChange(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> allFrames, Dimension dim,
							  boolean down, boolean why, String lang) {

		// Máy chỉ có ở T&SL
		List<CostMonitoringFocus> frames = dim == Dimension.MACHINE
				? allFrames.stream().filter(fr -> "T&SL".equals(fr.cost())).toList()
				: allFrames;
		if (frames.isEmpty()) {
			return new Answer(t(lang,
					"RM không ghi nhận chi phí theo máy (cột Machine trống); chỉ T&SL có số liệu theo máy.",
					"RM has no machine data (Machine column is blank); only T&SL is tracked by machine."), List.of());
		}

		int limit = frames.size() == 1 ? ITEM_CHANGE_ONE : ITEM_CHANGE_FEW;
		YearMonth month = snapshot.month();
		YearMonth prevMonth = month.minusMonths(1);

		record ChangeBlock(CostMonitoringFocus frame, int day, List<Change> changes, Double curTotal, Double prevTotal) {
		}

		List<ChangeBlock> blocks = perFrame(frames, fr -> {
			CostChartDto chart = snapshot.find(fr.div(), fr.cost());
			if (chart == null) {
				return null;
			}
			int last = snapshot.lastActualDay(chart);
			int day = last > 0 ? last : snapshot.d();
			if (day < 1) {
				return null;
			}
			LocalDate curFrom = month.atDay(1);
			LocalDate curTo = month.atDay(day);
			LocalDate prevFrom = prevMonth.atDay(1);
			LocalDate prevTo = prevMonth.atDay(Math.min(day, prevMonth.lengthOfMonth()));
			List<String> centers = CostMonitoringSnapshot.DIV_COST_CENTERS.get(fr.div());

			Map<String, Double> cur = amounts(dim, fr.cost(), curFrom, curTo, centers, lang);
			Map<String, Double> prev = amounts(dim, fr.cost(), prevFrom, prevTo, centers, lang);

			Set<String> labels = new LinkedHashSet<>(cur.keySet());
			labels.addAll(prev.keySet());

			List<Change> changes = labels.stream()
					.map(l -> new Change(CmText.clean(l), CmText.r2(cur.getOrDefault(l, 0.0)), CmText.r2(prev.getOrDefault(l, 0.0))))
					.filter(c -> down ? c.diff() < 0 : c.diff() > 0)
					.sorted(down ? Comparator.comparingDouble(Change::diff)
							: Comparator.comparingDouble(Change::diff).reversed())
					.limit(limit)
					.toList();

			double curTotal = cur.values().stream().mapToDouble(Double::doubleValue).sum();
			double prevTotal = prev.values().stream().mapToDouble(Double::doubleValue).sum();
			return new ChangeBlock(fr, day, changes, curTotal, prevTotal);
		}).stream().filter(java.util.Objects::nonNull).toList();

		if (blocks.isEmpty()) {
			return null;
		}

		String what = dim == null ? t(lang, "Vật tư", "Items") : switch (dim) {
			case CATEGORY -> "Category";
			case MACHINE -> t(lang, "Máy", "Machines");
			case COST_CENTER -> "Cost center";
		};
		String verb = down ? t(lang, " giảm nhiều nhất", " falling most") : t(lang, " tăng nhiều nhất", " rising most");

		StringBuilder sb = new StringBuilder();

		for (ChangeBlock b : blocks) {
			String name = b.frame().div() + " " + b.frame().cost();
			int prevDay = Math.min(b.day(), prevMonth.lengthOfMonth());

			// "Vì sao … tăng / giảm": nêu chiều thực tế của tổng trước (tiền đề có thể sai)
			if (why) {
				double total = CmText.r2(CmText.r2(b.curTotal()) - CmText.r2(b.prevTotal()));
				boolean totalUp = total > 0;
				String sum = k2(b.curTotal()) + t(lang, " so với ", " vs ") + k2(b.prevTotal()) + ", "
						+ (total > 0 ? "+" : "") + String.format(Locale.ROOT, "%.2f K$", total);
				if (totalUp == !down) {
					double explained = b.changes().stream().mapToDouble(Change::diff).sum();
					sb.append("**").append(name).append("** ")
							.append(down ? t(lang, "giảm", "decreased") : t(lang, "tăng", "increased"))
							.append(t(lang, " so với cùng kỳ tháng trước (tổng chi trực tiếp ", " vs the same period last month (total direct cost "))
							.append(sum).append(")");
					if (total != 0) {
						sb.append(t(lang, "; các mục dưới đây chiếm ", "; the items below account for "))
								.append(CmText.pct0(Math.abs(explained / total) * 100))
								.append(t(lang, " mức thay đổi", " of the change"));
					}
					sb.append(".\n");
				} else {
					sb.append("**").append(name).append("** ").append(t(lang, "thực tế **", "actually **"))
							.append(totalUp ? t(lang, "tăng", "increased") : t(lang, "giảm", "decreased"))
							.append(t(lang, "** so với cùng kỳ tháng trước (tổng chi trực tiếp ", "** vs the same period last month (total direct cost "))
							.append(sum).append(")")
							.append(down ? t(lang, "; tuy vậy một số mục giảm:", "; still, some items decreased:")
									: t(lang, "; tuy vậy một số mục tăng (bị bù bởi mục giảm):", "; still, some items increased (offset by decreases):"))
							.append('\n');
				}
			}
			sb.append(why ? "" : "**" + name + "** – ").append(what).append(verb)
					.append(t(lang, " so với cùng kỳ tháng trước (", " vs the same period last month ("))
					.append(ddmm(month.atDay(1))).append("–").append(ddmm(month.atDay(b.day())))
					.append(t(lang, " so với ", " vs ")).append(ddmm(prevMonth.atDay(1))).append("–")
					.append(ddmm(prevMonth.atDay(prevDay))).append("):\n");

			if (b.changes().isEmpty()) {
				sb.append(t(lang, down ? "- Không có mục nào giảm.\n" : "- Không có mục nào tăng.\n",
						down ? "- Nothing decreased.\n" : "- Nothing increased.\n"));
			}

			int i = 0;
			for (Change c : b.changes()) {
				sb.append(++i).append(". **").append(c.label()).append("**: ")
						.append(k2(c.current())).append(t(lang, " (cùng kỳ ", " (same period "))
						.append(k2(c.previous())).append(") → **")
						.append(c.diff() > 0 ? "+" : "").append(String.format(Locale.ROOT, "%.2f K$", c.diff())).append("**");
				if (c.previous() == 0) {
					sb.append(t(lang, " (tháng trước cùng kỳ không có)", " (none in the same period last month)"));
				}
				sb.append('\n');
			}

			// Câu "vì sao": tổng đã nêu ở câu đầu -> không lặp
			if (!why) {
				double totalDiff = CmText.r2(b.curTotal()) - CmText.r2(b.prevTotal());
				sb.append(t(lang, "Tổng chi trực tiếp: ", "Total direct cost: ")).append(k2(b.curTotal()))
						.append(t(lang, " so với ", " vs ")).append(k2(b.prevTotal())).append(" → ")
						.append(totalDiff > 0 ? "+" : "").append(String.format(Locale.ROOT, "%.2f K$", totalDiff))
						.append('\n');
			}
			sb.append('\n');
		}

		sb.append('_').append(t(lang,
				"Chênh = tháng này − cùng kỳ tháng trước (K$). Chỉ chi phí trực tiếp, chưa gồm phân bổ F2/HT2/HT-G.",
				"Change = this month − same period last month (K$). Direct cost only, excludes F2/HT2/HT-G allocation.")).append('_');

		String name = blocks.get(0).frame().div() + " " + blocks.get(0).frame().cost();
		return new Answer(sb.toString().trim(), List.of(
				t(lang, "So với tháng trước " + name + " thế nào?", "How does " + name + " compare with last month?"),
				t(lang, "Vì sao " + name + " dự báo vượt?", "Why is " + name + " forecast to exceed?")));
	}

	// Nhãn -> K$ theo chiều được hỏi (null = vật tư) trong [from, to]
	private Map<String, Double> amounts(Dimension dim, String cost, LocalDate from, LocalDate to, List<String> centers,
										String lang) {
		if (dim == null) {
			return byItem(repository.sumByItem(cost, from, to, centers), lang);
		}
		Map<String, Double> map = new java.util.LinkedHashMap<>();
		switch (dim) {
			case CATEGORY -> map.putAll(repository.sumByCategory(cost, from, to, centers));
			case MACHINE -> repository.findTopMachines(cost, from, to, centers, ALL_ROWS)
					.forEach(m -> map.merge(CmText.clean(m.machine()), m.amountK() != null ? m.amountK() : 0.0, Double::sum));
			case COST_CENTER -> repository.findTopCostCenters(cost, from, to, centers, ALL_ROWS)
					.forEach(c -> map.merge(CmText.clean(c.name())
									+ (c.code() == null || c.code().isBlank() ? "" : " (" + CmText.clean(c.code()) + ")"),
							c.amountK() != null ? c.amountK() : 0.0, Double::sum));
		}
		return map;
	}

	// Vật tư -> K$, khóa "Tên (Code)" (trùng nhãn thì cộng dồn)
	private static Map<String, Double> byItem(List<CostItemAmountDto> rows, String lang) {
		Map<String, Double> map = new java.util.LinkedHashMap<>();
		for (CostItemAmountDto row : rows) {
			map.merge(CmText.itemLabel(row.code(), row.name(), lang), row.amountK() != null ? row.amountK() : 0.0, Double::sum);
		}
		return map;
	}

	// Truy vấn DB từng khung song song (audit 2026-11-10: "vật tư lớn" 6 khung tuần tự mất 24 s); giữ thứ tự khung
	private static <T> List<T> perFrame(List<CostMonitoringFocus> frames, Function<CostMonitoringFocus, T> query) {
		return frames.size() <= 1
				? frames.stream().map(query).toList()
				: frames.parallelStream().map(query).toList();
	}


	// =========================================================
	// TOP MÁY / COST CENTER / CATEGORY
	//
	// Audit 2026-11-10: qua LLM (37–39 s) trả lời sai – "máy nào" trả về tên khung; "cost center của guide" cộng
	// 2 cost center khác nhau của T&SL và RM; "Category" tự cộng từ top 5 vật tư. Template: mỗi khung xếp riêng.
	// Máy: chỉ T&SL (RM không ghi Machine).
	// =========================================================

	record Entry(String label, Double amountK) {
	}

	// basis: nhãn của act trong % ("Actual" cả tháng, hoặc "chi trong ngày")
	record Block(CostMonitoringFocus frame, List<Entry> entries, Double act, LocalDate to, String basis) {
	}

	// Khoảng ngày tra vật tư / máy: cả tháng (đến mốc actual) hoặc đúng 1 ngày; chưa có dữ liệu -> null
	private LocalDate[] range(CostMonitoringSnapshot snapshot, String cost, LocalDate day) {

		LocalDate from = day != null ? day : snapshot.month().atDay(1);
		LocalDate to = itemsTo(snapshot, cost);

		if (day != null) {
			if (day.isAfter(to)) {
				return null;
			}
			to = day;
		}

		return to.isBefore(from) ? null : new LocalDate[]{from, to};
	}

	// Chi trong ngày của 1 khung (get_daily_series); không có số -> null
	private static Double dayAmount(CostMonitoringSnapshot snapshot, CostChartDto chart, LocalDate day) {
		return chart == null ? null : dailies(snapshot, chart).stream()
				.filter(d -> d.date().equals(day)).map(Daily::amount).findFirst().orElse(null);
	}

	// Không có dòng nào cho ngày được hỏi: trả lời rõ (không để LLM lấy số cả tháng)
	private static Answer noDataForDay(LocalDate day, String what, String lang) {
		return new Answer(t(lang,
				"Ngày **" + dayLabel(day, lang) + "** không có dòng chi nào ghi " + what
						+ " ở khung được hỏi (ngày nghỉ, không phát sinh hoặc chưa có dữ liệu – RM ghi nhận chậm 1 ngày).",
				"No " + what + " lines on **" + dayLabel(day, lang)
						+ "** in the asked frame(s) (holiday, no spending, or data not yet loaded – RM lags 1 day)."),
				List.of(t(lang, "Có gì bất thường trong tháng này?", "Anything unusual this month?")));
	}

	private Answer topDetail(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, Dimension dim,
							 LocalDate day, int topN, String lang) {

		List<CostMonitoringFocus> usable = dim == Dimension.MACHINE
				? frames.stream().filter(fr -> "T&SL".equals(fr.cost())).toList()
				: frames;

		if (usable.isEmpty()) {
			return new Answer(t(lang,
					"RM không ghi nhận chi phí theo máy (cột Machine trống); chỉ T&SL có số liệu theo máy.",
					"RM has no machine data (Machine column is blank); only T&SL is tracked by machine."),
					List.of(t(lang, "Máy nào chi nhiều nhất ở PRESS T&SL?", "Which machine spends the most in PRESS T&SL?")));
		}

		int limit = usable.size() == 1 ? (topN > 0 ? topN : TOP_DETAIL_ONE)
				: usable.size() <= TOP_DETAIL_FEW_FRAMES ? TOP_DETAIL_FEW : 1;
		LocalDate from = day != null ? day : snapshot.month().atDay(1);

		// 1 ngày: % so với chi trong ngày đó của khung (không so với Actual cả tháng)
		List<Block> blocks = perFrame(usable, fr -> {
			LocalDate[] r = range(snapshot, fr.cost(), day);
			if (r == null) {
				return null;
			}
			CostChartDto chart = snapshot.find(fr.div(), fr.cost());
			Double act = day != null ? dayAmount(snapshot, chart, day)
					: chart != null ? chart.actMtdLatest() : null;
			List<Entry> entries = detailEntries(fr, dim, r[0], r[1], limit);
			return entries.isEmpty() ? null : new Block(fr, entries, act, r[1],
					day != null ? t(lang, "chi trong ngày", "of the day's spending") : "Actual");
		}).stream().filter(java.util.Objects::nonNull).toList();

		String what = switch (dim) {
			case MACHINE -> t(lang, "Máy", "Machines");
			case COST_CENTER -> "Cost center";
			case CATEGORY -> "Category";
		};

		if (blocks.isEmpty()) {
			return day != null ? noDataForDay(day, what, lang) : null;
		}

		LocalDate latestTo = blocks.stream().map(Block::to).max(Comparator.naturalOrder()).orElse(from);
		String period = day != null
				? t(lang, "ngày ", "day ") + dayLabel(day, lang)
				: ddmm(from) + "–" + ddmm(latestTo);

		StringBuilder sb = new StringBuilder();

		if (blocks.size() == 1) {
			Block b = blocks.get(0);
			sb.append(what).append(t(lang, " chi nhiều nhất ở **", " with the highest spending in **"))
					.append(b.frame().div()).append(' ').append(b.frame().cost()).append("** (").append(period).append("):\n");
			appendEntries(sb, b);
		} else if (limit > 1) {
			sb.append(what).append(t(lang, " chi nhiều nhất của từng khung (", " with the highest spending per frame ("))
					.append(period).append("):\n");
			for (Block b : blocks) {
				sb.append("\n**").append(b.frame().div()).append(' ').append(b.frame().cost()).append("**");
				if (b.act() != null) {
					sb.append(" (").append(day != null ? t(lang, "chi trong ngày ", "day spending ") : "Actual ")
							.append(CmText.k1(b.act())).append(')');
				}
				sb.append(":\n");
				appendEntries(sb, b);
			}
		} else {
			sb.append(what).append(t(lang, " chi nhiều nhất của từng khung (", " with the highest spending per frame ("))
					.append(period).append("):\n");
			for (Block b : blocks) {
				Entry e = b.entries().get(0);
				sb.append("- ").append(b.frame().div()).append(' ').append(b.frame().cost()).append(": **")
						.append(e.label()).append("** – ").append(k2(e.amountK()));
				appendPct(sb, e.amountK(), b.act(), b.basis());
				sb.append('\n');
			}
		}

		sb.append('\n').append('_');
		if (blocks.size() > 1) {
			sb.append(t(lang, "Mỗi khung xếp riêng (không cộng T&SL với RM). ",
					"Each frame ranked separately (T&SL and RM are not added). "));
		}
		if (dim == Dimension.MACHINE) {
			sb.append(t(lang, "Chỉ dòng có ghi Machine. ", "Only lines with a Machine. "));
		}
		sb.append(t(lang, "Chỉ chi phí trực tiếp, chưa gồm phân bổ F2/HT2/HT-G.",
				"Direct cost only, excludes F2/HT2/HT-G allocation.")).append('_');

		CostMonitoringFocus focus = blocks.stream()
				.max(Comparator.comparing((Block b) -> b.entries().get(0).amountK()))
				.map(Block::frame).orElse(blocks.get(0).frame());
		String name = focus.div() + " " + focus.cost();
		List<String> suggestions = List.of(
				t(lang, "Vật tư nào chi nhiều nhất ở " + name + "?", "Which items cost the most in " + name + "?"),
				t(lang, "Ngày nào " + name + " chi nhiều nhất?", "Which day did " + name + " spend the most?"));

		return new Answer(sb.toString().trim(), suggestions);
	}

	private List<Entry> detailEntries(CostMonitoringFocus fr, Dimension dim, LocalDate from, LocalDate to, int limit) {

		List<String> centers = CostMonitoringSnapshot.DIV_COST_CENTERS.get(fr.div());

		List<Entry> entries = switch (dim) {
			case MACHINE -> repository.findTopMachines(fr.cost(), from, to, centers, limit).stream()
					.map(m -> new Entry(CmText.clean(m.machine()), m.amountK())).toList();
			case COST_CENTER -> repository.findTopCostCenters(fr.cost(), from, to, centers, limit).stream()
					.map(c -> new Entry(CmText.clean(c.name())
							+ (c.code() == null || c.code().isBlank() ? "" : " (" + CmText.clean(c.code()) + ")"),
							c.amountK())).toList();
			case CATEGORY -> repository.sumByCategory(fr.cost(), from, to, centers).entrySet().stream()
					.sorted(Map.Entry.<String, Double>comparingByValue().reversed())
					.limit(limit)
					.map(e -> new Entry(CmText.clean(e.getKey()), e.getValue())).toList();
		};

		return entries.stream().filter(e -> e.amountK() != null && e.amountK() > 0).toList();
	}

	private static void appendEntries(StringBuilder sb, Block b) {
		int i = 0;
		for (Entry e : b.entries()) {
			sb.append(++i).append(". **").append(e.label()).append("**: ").append(k2(e.amountK()));
			appendPct(sb, e.amountK(), b.act(), b.basis());
			sb.append('\n');
		}
	}

	private static void appendPct(StringBuilder sb, Double amountK, Double act, String basis) {
		String share = ExplainVarianceTool.pctOfAct(amountK, act);
		if (!share.isEmpty()) {
			sb.append(" (").append(share).append(' ').append(basis).append(')');
		}
	}


	// =========================================================
	// TOP VẬT TƯ
	// =========================================================

	private Answer topItem(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, boolean expensive,
						   LocalDate day, int topN, String lang) {

		LocalDate from = day != null ? day : snapshot.month().atDay(1);
		boolean single = frames.size() == 1;

		record Row(CostMonitoringFocus frame, CostTopItemDto item, Double act, LocalDate to) {
		}

		List<List<Row>> perFrameRows = perFrame(frames, fr -> {

			LocalDate[] r = range(snapshot, fr.cost(), day);

			if (r == null) {
				return List.<Row>of();
			}

			LocalDate to = r[1];
			CostChartDto chart = snapshot.find(fr.div(), fr.cost());
			// 1 ngày: không ghi % Actual cả tháng (dễ hiểu nhầm)
			Double act = day != null ? null : chart != null ? chart.actMtdLatest() : null;

			return repository.findTopItems(fr.cost(), from, to,
							CostMonitoringSnapshot.DIV_COST_CENTERS.get(fr.div()), single ? (topN > 0 ? topN : TOP_ITEMS_ONE) : 1)
					.stream()
					.filter(item -> item.amountK() != null && item.amountK() > 0)
					.map(item -> new Row(fr, item, act, to))
					.toList();
		});

		List<Row> rows = new ArrayList<>();
		perFrameRows.forEach(rows::addAll);
		LocalDate latestTo = rows.stream().map(Row::to).max(Comparator.naturalOrder()).orElse(null);

		if (rows.isEmpty()) {
			return day != null ? noDataForDay(day, t(lang, "vật tư", "item"), lang) : null;
		}

		StringBuilder sb = new StringBuilder();
		String period = day != null
				? t(lang, "ngày ", "day ") + dayLabel(day, lang)
				: ddmm(from) + "–" + ddmm(latestTo);

		if (single) {
			CostMonitoringFocus fr = frames.get(0);
			sb.append(t(lang, "Vật tư chi nhiều nhất ở **", "Top items by spending in **"))
					.append(fr.div()).append(' ').append(fr.cost()).append("** (").append(period)
					.append(t(lang, ", theo tổng chi):", ", by total spending):")).append('\n');

			int i = 0;
			for (Row r : rows) {
				sb.append(++i).append(". ").append(itemLine(r.item(), r.act(), expensive, true, lang)).append('\n');
			}
		} else {
			rows.sort(Comparator.comparing((Row r) -> r.item().amountK()).reversed());
			Row top = rows.get(0);

			sb.append(t(lang, "Vật tư chi nhiều nhất là **", "The top item is **"))
					.append(CmText.itemLabel(top.item().code(), top.item().name(), lang)).append("**")
					.append(t(lang, " ở ", " in ")).append(top.frame().div()).append(' ').append(top.frame().cost())
					.append(": ").append(k2(top.item().amountK()))
					.append(" (").append(period).append(").\n\n")
					.append(t(lang, "**Vật tư lớn nhất của từng khung:**", "**Top item per frame:**")).append('\n');

			for (Row r : rows) {
				sb.append("- ").append(r.frame().div()).append(' ').append(r.frame().cost()).append(": ")
						.append(itemLine(r.item(), r.act(), expensive, false, lang)).append('\n');
			}
		}

		sb.append('\n').append('_');
		if (expensive) {
			sb.append(t(lang, "Xếp theo tổng chi (K$); đơn giá TB = tổng chi / số lượng. ",
					"Ranked by total spending (K$); avg unit price = total / quantity. "));
		}
		sb.append(t(lang, "Chỉ chi phí trực tiếp, chưa gồm phân bổ F2/HT2/HT-G.",
				"Direct cost only, excludes F2/HT2/HT-G allocation.")).append('_');

		List<String> suggestions = new ArrayList<>();
		CostMonitoringFocus focus = single ? frames.get(0) : rows.get(0).frame();
		String name = focus.div() + " " + focus.cost();
		suggestions.add(t(lang, "Ngày nào " + name + " chi nhiều nhất?", "Which day did " + name + " spend the most?"));
		suggestions.add(t(lang, "Cứ chi như vậy thì " + name + " có vượt FC không?",
				"If spending continues like this, will " + name + " exceed FC?"));

		return new Answer(sb.toString().trim(), suggestions);
	}

	// "**Tên (Code)** – Category: 4.18 K$ (6.8% Actual); SL 3 PC, đơn giá TB ≈ 1393 $"
	// showQty: danh sách 1 khung luôn ghi số lượng (audit 2026-11-10: "Top 10 vật tư" thiếu số lượng)
	private static String itemLine(CostTopItemDto item, Double act, boolean expensive, boolean showQty, String lang) {

		StringBuilder sb = new StringBuilder("**")
				.append(CmText.itemLabel(item.code(), item.name(), lang)).append("**");

		String category = CmText.clean(item.category());
		if (!category.isEmpty()) {
			sb.append(" – ").append(category);
		}

		sb.append(": ").append(k2(item.amountK()));

		String share = ExplainVarianceTool.pctOfAct(item.amountK(), act);
		if (!share.isEmpty()) {
			sb.append(" (").append(share).append(" Actual)");
		}

		if (expensive && item.qty() != null && item.qty() > 0) {
			double unit = item.amountK() * 1000 / item.qty();
			String unitLabel = item.qtyUnit() == null || item.qtyUnit().isBlank() ? "" : " " + CmText.clean(item.qtyUnit());
			sb.append(t(lang, "; SL ", "; qty ")).append(qty(item.qty())).append(unitLabel)
					.append(t(lang, ", đơn giá TB ≈ ", ", avg unit price ≈ "))
					.append(String.format(Locale.ROOT, unit >= 100 ? "%.0f" : "%.2f", unit)).append(" $");
		} else if (showQty && item.qty() != null && item.qty() != 0) {
			String unitLabel = item.qtyUnit() == null || item.qtyUnit().isBlank() ? "" : " " + CmText.clean(item.qtyUnit());
			sb.append(t(lang, "; SL ", "; qty ")).append(qty(item.qty())).append(unitLabel);
		}

		return sb.toString();
	}

	// Mốc actual giống TopItemsTool: T&SL đến hôm qua, RM đến hôm kia, không quá cuối tháng
	private LocalDate itemsTo(CostMonitoringSnapshot snapshot, String cost) {

		LocalDate cutoff = loader.today().minusDays("RM".equals(cost) ? 2 : 1);
		LocalDate end = snapshot.month().atEndOfMonth();

		return cutoff.isBefore(end) ? cutoff : end;
	}


	// =========================================================
	// NGÀY CHI NHIỀU NHẤT
	// =========================================================

	private Answer topDay(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, String lang) {

		StringBuilder sb = new StringBuilder();
		boolean single = frames.size() == 1;
		boolean any = false;

		if (!single) {
			sb.append(t(lang, "**Ngày chi nhiều nhất của từng khung:**", "**Top spending day per frame:**")).append('\n');
		}

		for (CostMonitoringFocus fr : frames) {

			CostChartDto chart = snapshot.find(fr.div(), fr.cost());

			if (chart == null) {
				continue;
			}

			List<Daily> days = new ArrayList<>(dailies(snapshot, chart));
			days.removeIf(d -> d.amount() == null || Math.round(d.amount() * 10) <= 0);

			if (days.isEmpty()) {
				continue;
			}

			days.sort(Comparator.comparing(Daily::amount).reversed());
			Daily top = days.get(0);
			Double act = CmText.r1(chart.actMtdLatest());
			String name = fr.div() + " " + fr.cost();
			any = true;

			if (single) {
				sb.append(t(lang, "Ngày **", "**")).append(dayLabel(top.date(), lang))
						.append(t(lang, "** là ngày **", "** is the top spending day of **")).append(name)
						.append(t(lang, "** chi nhiều nhất: **", "**: **")).append(k1(top.amount())).append("**");
				appendShare(sb, top.amount(), act, lang);
				sb.append(".\n");

				List<Daily> next = days.subList(1, Math.min(days.size(), 1 + TOP_DAYS_NEXT));
				if (!next.isEmpty()) {
					sb.append(t(lang, "Tiếp theo: ", "Next: "))
							.append(String.join("; ", next.stream()
									.map(d -> dayLabel(d.date(), lang) + " " + k1(d.amount())).toList()))
							.append(".\n");
				}
			} else {
				sb.append("- ").append(name).append(": ").append(dayLabel(top.date(), lang)).append(" – ")
						.append(k1(top.amount()));
				appendShare(sb, top.amount(), act, lang);
				sb.append('\n');
			}
		}

		if (!any) {
			return null;
		}

		sb.append('\n').append(footer(snapshot, lang));

		List<String> suggestions = new ArrayList<>();
		if (single) {
			String name = frames.get(0).div() + " " + frames.get(0).cost();
			suggestions.add(t(lang, "Vật tư nào chi nhiều nhất ở " + name + "?", "Which items cost the most in " + name + "?"));
			suggestions.add(t(lang, "Vì sao " + name + " dự báo vượt?", "Why is " + name + " forecast to exceed?"));
		}

		return new Answer(sb.toString().trim(), suggestions);
	}

	// " (35.1% Actual đến nay 27.1 K$)"
	private static void appendShare(StringBuilder sb, Double amount, Double act, String lang) {
		if (act != null && act > 0 && amount != null) {
			sb.append(" (").append(CmText.pct1(CmText.r1(amount) / act * 100))
					.append(t(lang, " Actual đến nay ", " of Actual to date ")).append(CmText.k1(act)).append(')');
		}
	}


	// =========================================================
	// NGÂN SÁCH CÒN LẠI MỖI NGÀY
	// =========================================================

	private Answer remainingBudget(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, String lang) {

		int remainingDays = snapshot.n() - snapshot.d();

		if (remainingDays <= 0) {
			return null;
		}

		Double workDays = remainingWorkDays(snapshot);

		StringBuilder sb = new StringBuilder();

		sb.append(t(lang, "Để cuối tháng không vượt **Fluct** (target), còn ", "To stay within **Fluct** (target) by month-end, "))
				.append(remainingDays).append(t(lang, " ngày", " days left"));
		if (workDays != null) {
			sb.append(" (").append(qty(workDays)).append(t(lang, " ngày nhà máy làm việc)", " factory working days)"));
		}
		sb.append(t(lang, ", mỗi ngày được chi tối đa:", "; maximum spending per day:")).append('\n');

		int lines = 0;
		CostMonitoringFocus tightest = null;
		double tightestCut = 0;

		for (CostMonitoringFocus fr : frames) {

			CostChartDto chart = snapshot.find(fr.div(), fr.cost());

			if (chart == null) {
				continue;
			}

			CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);
			Double left = CmText.subR1(a.fluct(), a.act());

			if (left == null) {
				continue;
			}

			String name = fr.div() + " " + fr.cost();
			sb.append("- **").append(name).append("**: ");
			lines++;

			if (left <= 0) {
				sb.append(t(lang, "đã vượt Fluct ", "already above Fluct by ")).append(CmText.signedK(-left))
						.append(t(lang, " → không còn ngân sách", " → no budget left")).append('\n');
				continue;
			}

			// Ngày còn lại theo ngày actual của CHÍNH khung (RM trễ 1 ngày: ngày D chưa ghi nhận, vẫn còn ngân sách)
			int actDay = snapshot.lastActualDay(chart);
			int frameDays = actDay > 0 && actDay < snapshot.d() ? snapshot.n() - actDay : remainingDays;
			Double frameWorkDays = frameDays == remainingDays ? workDays
					: workDays(snapshot.month().atDay(actDay + 1), snapshot.month().atEndOfMonth());

			double perDay = left / frameDays;
			sb.append(t(lang, "còn ", "")).append(CmText.k1(left)).append(t(lang, " → **", " left → **"))
					.append(CmText.rate(perDay, lang)).append("**");

			if (frameWorkDays != null && frameWorkDays > 0) {
				sb.append(" (").append(CmText.rate(left / frameWorkDays, lang))
						.append(t(lang, " làm việc", " working")).append(')');
			}

			if (frameDays != remainingDays) {
				sb.append(t(lang, " – còn " + frameDays + " ngày (actual đến ngày " + actDay + ")",
						" – " + frameDays + " days left (actual up to day " + actDay + ")"));
			}

			Double rate = a.currentRate();
			if (rate != null && rate > 0) {
				sb.append(t(lang, "; đang chi ", "; currently ")).append(CmText.rate(rate, lang));
				if (rate > perDay) {
					double cut = (1 - perDay / rate) * 100;
					// Kèm số K$/ngày phải giảm (test 2026-11-11 ext-102: LLM tự tính "0.8 K$/ngày", đúng ~2.9)
					sb.append(t(lang, " → cần giảm **", " → cut **")).append(CmText.rate(CmText.r2(CmText.r2(rate) - CmText.r2(perDay)), lang))
							.append("** (").append(CmText.pct0(cut)).append(')');
					if (cut > tightestCut) {
						tightestCut = cut;
						tightest = fr;
					}
				} else {
					sb.append(t(lang, " → trong ngân sách", " → within budget"));
				}
			}

			sb.append('\n');
		}

		if (lines == 0) {
			return null;
		}

		sb.append('\n').append('_')
				.append(t(lang, "Ngân sách/ngày = (Fluct − Actual) / ngày còn lại; ", "Budget/day = (Fluct − Actual) / days left; "))
				.append(workDays != null
						? t(lang, "ngày làm việc theo lịch nhà máy (WD_All). ", "working days from the factory calendar (WD_All). ")
						: t(lang, "chưa có lịch ngày nghỉ nên chỉ tính ngày lịch. ", "no holiday calendar, calendar days only. "))
				.append(t(lang, "Số liệu tháng ", "Data for month ")).append(snapshot.month())
				.append(t(lang, ", actual đến ngày ", ", actual up to day ")).append(snapshot.d()).append("._");

		List<String> suggestions = new ArrayList<>();
		if (tightest != null) {
			String name = tightest.div() + " " + tightest.cost();
			suggestions.add(t(lang, "Vì sao " + name + " dự báo vượt?", "Why is " + name + " forecast to exceed?"));
			suggestions.add(t(lang, "Vật tư nào chi nhiều nhất ở " + name + "?", "Which items cost the most in " + name + "?"));
		}

		return new Answer(sb.toString().trim(), suggestions);
	}

	// Σ WD_All từ ngày D + 1 đến cuối tháng; không có lịch / lỗi đọc -> null
	private Double remainingWorkDays(CostMonitoringSnapshot snapshot) {

		return workDays(snapshot.month().atDay(snapshot.d() + 1), snapshot.month().atEndOfMonth());
	}

	// Σ WD_All từ from đến to (gồm 2 đầu); không có lịch / lỗi đọc -> null
	private Double workDays(LocalDate from, LocalDate to) {

		if (from.isAfter(to)) {
			return 0.0;
		}

		try {
			Map<LocalDate, WorkingDay> days = workingDateRepository.findWorkingDays(from, to);
			if (days == null || days.isEmpty()) {
				return null;
			}
			double sum = 0;
			for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
				WorkingDay wd = days.get(d);
				if (wd == null || wd.wdAll() == null) {
					return null;
				}
				sum += wd.wdAll();
			}
			return sum;
		} catch (RuntimeException e) {
			log.warn("Assistant lookup: cannot load working days error={}", e.getClass().getSimpleName());
			return null;
		}
	}


	// =========================================================
	// BẤT THƯỜNG HÔM NAY
	// =========================================================

	private Answer anomalyToday(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, String lang) {

		LocalDate latest = snapshot.month().atDay(snapshot.d());

		List<String> spikes = new ArrayList<>();
		List<String> latestLines = new ArrayList<>();
		boolean anySpendOnLatest = false;
		CostMonitoringFocus firstSpike = null;

		for (CostMonitoringFocus fr : frames) {

			CostChartDto chart = snapshot.find(fr.div(), fr.cost());

			if (chart == null) {
				continue;
			}

			List<Daily> days = dailies(snapshot, chart);
			Daily last = null;

			for (Daily d : days) {
				if (d.amount() != null && Math.round(d.amount() * 10) > 0) {
					last = d;
				}
			}

			if (last == null) {
				continue;
			}

			if (last.date().equals(latest)) {
				anySpendOnLatest = true;
			}

			// Trung bình các ngày có chi khác (không gồm ngày đang xét)
			double sum = 0;
			int count = 0;
			for (Daily d : days) {
				if (d != last && d.amount() != null && Math.round(d.amount() * 10) > 0) {
					sum += d.amount();
					count++;
				}
			}
			Double avg = count > 0 ? CmText.r1(sum / count) : null;
			String name = fr.div() + " " + fr.cost();

			latestLines.add("- " + name + ": " + dayLabel(last.date(), lang) + " " + k1(last.amount())
					+ (avg != null ? t(lang, " (TB các ngày có chi khác ", " (avg of other spending days ") + k1(avg) + ")" : ""));

			boolean spike = avg != null && avg > 0 && last.amount() >= SPIKE_MIN_K && last.amount() / avg >= SPIKE_RATIO;

			List<CostLineDto> unusual = unusualLines(fr, last.date(), chart.actMtdLatest());

			if (spike || !unusual.isEmpty()) {

				StringBuilder s = new StringBuilder("- **").append(name).append("**: ")
						.append(dayLabel(last.date(), lang)).append(t(lang, " chi ", " spent ")).append(k1(last.amount()));
				if (spike) {
					s.append(t(lang, ", gấp ", ", ")).append(String.format(Locale.ROOT, "%.1f", last.amount() / avg))
							.append(t(lang, " lần trung bình ngày có chi (", "× the average spending day ("))
							.append(k1(avg)).append(')');
				}
				for (CostLineDto line : unusual) {
					s.append("\n  - ").append(t(lang, "khoản lớn: ", "large line: "))
							.append(CmText.itemLabel(line.code(), line.name(), lang)).append(' ')
							.append(k2(line.amountK()));
					String share = ExplainVarianceTool.pctOfAct(line.amountK(), chart.actMtdLatest());
					if (!share.isEmpty()) {
						s.append(" (").append(share).append(" Actual)");
					}
				}
				spikes.add(s.toString());
				if (firstSpike == null) {
					firstSpike = fr;
				}
			}
		}

		if (latestLines.isEmpty()) {
			return null;
		}

		StringBuilder sb = new StringBuilder();

		sb.append(t(lang, "Dữ liệu mới nhất: **", "Latest data: **")).append(dayLabel(latest, lang)).append("**");
		if (!anySpendOnLatest) {
			sb.append(t(lang, " – không phát sinh chi (ngày nghỉ / chưa ghi nhận); xét ngày có chi gần nhất của từng khung.",
					" – no spending (holiday / not yet recorded); checking each frame's most recent spending day."));
		}
		sb.append('\n').append('\n');

		if (spikes.isEmpty()) {
			sb.append(t(lang, "**Không có chi đột biến** ở ngày có chi gần nhất (ngưỡng: ≥ ",
							"**No spending spike** on the most recent spending day (threshold: ≥ "))
					.append(String.format(Locale.ROOT, "%.0f", SPIKE_RATIO))
					.append(t(lang, " lần trung bình ngày có chi, hoặc 1 khoản ≥ 10% Actual).",
							"× the average spending day, or a single line ≥ 10% of Actual)."))
					.append('\n');
		} else {
			sb.append(t(lang, "**Có " + spikes.size() + " khung chi bất thường:**",
					"**" + spikes.size() + " frame(s) with unusual spending:**")).append('\n');
			spikes.forEach(s -> sb.append(s).append('\n'));
		}

		sb.append('\n').append(t(lang, "**Chi ngày gần nhất:**", "**Most recent spending day:**")).append('\n');
		latestLines.forEach(l -> sb.append(l).append('\n'));

		// Không footer / không tóm tắt dự báo: CostMonitoringAnswerGuide ghép thêm template tổng quan tháng (nếu có),
		// footer nằm cuối phần đó; dòng "Dữ liệu mới nhất" ở trên đã nêu mốc ngày.
		Set<String> suggestions = new LinkedHashSet<>();
		suggestions.add(t(lang, "Có gì bất thường trong tháng này?", "Anything unusual this month?"));
		if (firstSpike != null) {
			String name = firstSpike.div() + " " + firstSpike.cost();
			suggestions.add(t(lang, "Vật tư nào chi nhiều nhất ở " + name + "?", "Which items cost the most in " + name + "?"));
		}

		return new Answer(sb.toString().trim(), List.copyOf(suggestions));
	}

	// 1 dòng ≥ 10% Actual của khung trong đúng ngày đó; lỗi đọc -> rỗng
	private List<CostLineDto> unusualLines(CostMonitoringFocus fr, LocalDate day, Double act) {
		// findUnusualLines còn khớp theo từ khóa (license …): unusualLines(from, to) chỉ giữ dòng thực sự ≥ 10% Actual
		return unusualLines(fr, day, day, act);
	}


	// =========================================================
	// CHI PHÍ 1 NGÀY CỤ THỂ (hôm qua, Chủ nhật, ngày 5…)
	// Mỗi khung: chi trong ngày; ngày sau mốc actual của khung -> "chưa có số"; 0 -> "không phát sinh".
	// =========================================================

	// =========================================================
	// TARGET (FLUCT) ĐƯỢC NÂNG / BỊ HẠ
	// Fluct = FC × (1 + Ratio Sale&Inven). Mỗi bộ phận: Ratio đầu tháng -> mới nhất (F2_Working_Date, như get_ratio_trend);
	// mỗi loại chi phí: Fluct so với FC (dương = được nâng, âm = bị hạ) và Fluct đầu tháng -> hiện tại.
	// =========================================================

	private Answer targetChange(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, String lang) {

		List<String> divs = frames.stream().map(CostMonitoringFocus::div).distinct().toList();
		LocalDate from = snapshot.month().atDay(1);
		LocalDate until = RatioTrendTool.lastRatioDay(snapshot);
		java.util.TreeMap<LocalDate, CostWorkingDateRepository.DailyRatio> ratios = until.isBefore(from)
				? new java.util.TreeMap<>() : workingDateRepository.findRatios(from, until);

		StringBuilder sb = new StringBuilder();
		String firstNamed = null;

		for (String div : divs) {

			// Ratio đầu tháng / mới nhất / ngày giảm (tăng) mạnh nhất
			Map.Entry<LocalDate, Double> first = null;
			Map.Entry<LocalDate, Double> last = null;
			Map.Entry<LocalDate, Double> prev = null;
			Map.Entry<LocalDate, Double> bigDown = null;
			Map.Entry<LocalDate, Double> bigUp = null;
			for (Map.Entry<LocalDate, CostWorkingDateRepository.DailyRatio> e : ratios.entrySet()) {
				Double r = RatioTrendTool.ratioOf(e.getValue(), div);
				if (r == null) {
					continue;
				}
				Map.Entry<LocalDate, Double> cur = Map.entry(e.getKey(), r);
				if (first == null) {
					first = cur;
				}
				if (prev != null) {
					double delta = CmText.r1(r * 100) - CmText.r1(prev.getValue() * 100);
					if (delta < 0 && (bigDown == null || delta < bigDown.getValue())) {
						bigDown = Map.entry(e.getKey(), delta);
					}
					if (delta > 0 && (bigUp == null || delta > bigUp.getValue())) {
						bigUp = Map.entry(e.getKey(), delta);
					}
				}
				prev = cur;
				last = cur;
			}

			sb.append("**").append(div).append("**");
			if (last != null) {
				double change = CmText.r1(CmText.r1(last.getValue() * 100) - CmText.r1(first.getValue() * 100));
				sb.append(t(lang, " – Ratio Sale&Inven: đầu tháng ", " – Sale&Inven ratio: month start "))
						.append(RatioTrendTool.pct(first.getValue())).append(" (").append(ddmm(first.getKey())).append(") → ")
						.append(t(lang, "mới nhất ", "latest ")).append(RatioTrendTool.pct(last.getValue()))
						.append(" (").append(ddmm(last.getKey())).append("), ")
						.append(String.format(Locale.ROOT, "%+.1f", change)).append(t(lang, " điểm %", " pp"));
			}
			sb.append(":\n");

			for (String cost : CostMonitoringSnapshot.COSTS) {
				CostChartDto chart = snapshot.find(div, cost);
				if (chart == null) {
					continue;
				}
				Double fluct = CostMonitoringSnapshot.fluct(chart);
				Double fc = chart.fcTgt();
				if (fluct == null || fc == null) {
					continue;
				}
				Double startFluct = null;
				for (CostChartPointDto p : chart.points()) {
					if (p.fluctTgt() != null && p.date() != null && YearMonth.from(p.date()).equals(snapshot.month())) {
						startFluct = p.fluctTgt();
						break;
					}
				}
				Double vsFc = CmText.subR1(fluct, fc);
				String name = div + " " + cost;
				if (firstNamed == null) {
					firstNamed = name;
				}
				sb.append("- ").append(name).append(": Fluct ").append(k1(fluct)).append(t(lang, " so với FC ", " vs FC "))
						.append(k1(fc)).append(" → ").append(CmText.signedK(vsFc)).append(" (")
						.append(vsFc == null || Math.round(vsFc * 10) == 0 ? t(lang, "bằng FC", "equal to FC")
								: vsFc > 0 ? t(lang, "target **được nâng**", "target **raised**")
								: t(lang, "target **bị hạ**", "target **cut**"))
						.append(')');
				Double moved = CmText.subR1(fluct, startFluct);
				if (moved != null && Math.round(moved * 10) != 0) {
					sb.append(t(lang, "; so với đầu tháng (", "; vs month start ("))
							.append(k1(startFluct)).append(") ").append(CmText.signedK(moved));
				}
				sb.append('\n');
			}

			if (bigDown != null || bigUp != null) {
				sb.append(t(lang, "- Ngày Ratio đổi mạnh nhất: ", "- Largest ratio moves: "));
				List<String> parts = new ArrayList<>();
				if (bigDown != null) {
					parts.add(t(lang, "giảm ", "down ") + ddmm(bigDown.getKey()) + " ("
							+ String.format(Locale.ROOT, "%+.1f", bigDown.getValue()) + t(lang, " điểm %)", " pp)"));
				}
				if (bigUp != null) {
					parts.add(t(lang, "tăng ", "up ") + ddmm(bigUp.getKey()) + " ("
							+ String.format(Locale.ROOT, "%+.1f", bigUp.getValue()) + t(lang, " điểm %)", " pp)"));
				}
				sb.append(String.join("; ", parts)).append('\n');
			}
			sb.append('\n');
		}

		if (firstNamed == null) {
			return null;
		}

		sb.append('_').append(t(lang,
				"Fluct = FC × (1 + Ratio): Ratio dương → target được nâng so với FC, Ratio âm → bị hạ. Ratio giảm trong tháng làm "
						+ "Fluct thấp dần nhưng vẫn cao hơn FC nếu Ratio còn dương. Chưa có dữ liệu doanh số / tồn kho chi tiết "
						+ "nên chưa tách được Ratio đổi do doanh số hay tồn kho.",
				"Fluct = FC × (1 + Ratio): positive ratio → target raised vs FC, negative → cut. A falling ratio lowers Fluct "
						+ "but it stays above FC while the ratio is positive. No detailed sales / inventory data to split the cause."))
				.append('_');

		String div0 = divs.get(0);
		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Ratio Sale&Inven của " + div0 + " thay đổi thế nào trong tháng?", "How did " + div0 + "'s ratio change this month?"),
				t(lang, "Cứ chi như vậy thì " + firstNamed + " có vượt FC không?", "At this rate, will " + firstNamed + " exceed FC?")));
	}

	// =========================================================
	// "ĐÃ VƯỢT … RỒI À / CHƯA": Actual hiện tại so với Fluct và FC (thực tế) + dự báo cuối tháng (phân biệt rõ)
	// =========================================================

	private Answer alreadyOver(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, String lang) {

		StringBuilder sb = new StringBuilder();
		int lines = 0;
		boolean single = frames.size() == 1;
		List<String> overNames = new ArrayList<>();
		int forecastOver = 0;

		for (CostMonitoringFocus fr : frames) {
			CostChartDto chart = snapshot.find(fr.div(), fr.cost());
			if (chart == null) {
				continue;
			}
			CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);
			if (a.act() == null || a.fluct() == null) {
				continue;
			}
			String name = fr.div() + " " + fr.cost();
			// Quy tắc vượt giống FE (số gốc)
			boolean overFluct = CostMonitoringAssessment.exceeds(a.act(), a.fluct());
			boolean overFc = CostMonitoringAssessment.exceeds(a.act(), a.fc());
			if (overFluct) {
				overNames.add(name);
			} else if (a.estimateWillExceed()) {
				forecastOver++;
			}
			lines++;

			if (single) {
				sb.append(overFluct || overFc ? t(lang, "**Rồi.** ", "**Yes.** ") : t(lang, "**Chưa.** ", "**Not yet.** "));
			}
			sb.append(single ? "**" + name + "**" : "- **" + name + "**")
					.append(t(lang, " – thực tế đến nay: Actual ", " – actual to date: Actual ")).append(k1(a.act()))
					.append("; ").append(CmText.versus("Fluct", CmText.subR1(a.act(), a.fluct()), null, lang));
			if (a.fc() != null) {
				sb.append("; ").append(CmText.versus("FC", CmText.subR1(a.act(), a.fc()), null, lang));
			}
			sb.append('\n');
			sb.append(single ? "" : "  ").append(t(lang, "**Dự báo:** ", "**Forecast:** "))
					.append(CmText.estimateBrief(a, lang));
			// Chưa vượt thực tế nhưng dự báo vượt: ngày dự kiến vượt (1303 ext-109)
			String cross = overFluct || !a.estimateWillExceed() ? null : CmText.estimateCrossText(chart, lang);
			if (cross != null) {
				sb.append(t(lang, "; dự kiến vượt Fluct từ ", "; expected to exceed Fluct from ")).append(cross);
			}
			sb.append('\n');
		}

		if (lines == 0) {
			return null;
		}

		// Nhiều khung: câu kết luận trước danh sách (1303 ext-91 chỉ có danh sách 6 khung)
		if (!single) {
			String head = overNames.isEmpty()
					? t(lang, "**Chưa khung nào vượt Fluct thực tế** (Actual ≤ Fluct ở " + lines + "/" + lines + " khung)",
					"**No frame is above Fluct in actual spending** (Actual ≤ Fluct in " + lines + "/" + lines + " frames)")
					: t(lang, "**" + overNames.size() + "/" + lines + " khung đã vượt Fluct thực tế:** ",
					"**" + overNames.size() + "/" + lines + " frame(s) already above Fluct:** ") + String.join(", ", overNames);
			if (forecastOver > 0) {
				head += t(lang, "; " + forecastOver + " khung khác chưa vượt nhưng dự báo cuối tháng vượt.",
						"; " + forecastOver + " other frame(s) not above yet but forecast to exceed at month-end.");
			} else {
				head += ".";
			}
			sb.insert(0, head + "\n\n");
		}

		sb.append('\n').append(footer(snapshot, lang));
		CostMonitoringFocus first = frames.get(0);
		String name = first.div() + " " + first.cost();
		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Vì sao " + name + " dự báo vượt?", "Why is " + name + " forecast to exceed?"),
				t(lang, "Mỗi ngày còn lại " + name + " được chi tối đa bao nhiêu?", "How much can " + name + " spend per day?")));
	}

	// =========================================================
	// NGÀY ĐỘT BIẾN TRONG THÁNG
	// Mỗi ngày có chi so với TB các ngày có chi KHÁC của khung: đột biến khi ≥ SPIKE_RATIO lần và ≥ SPIKE_MIN_K
	// (cùng ngưỡng anomaly_today). Không có -> nêu ngày cao nhất và bội số so với TB.
	// =========================================================

	private Answer spikeDays(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, String lang) {

		StringBuilder sb = new StringBuilder();
		int lines = 0;
		int spikeFrames = 0;
		CostMonitoringFocus firstSpike = null;

		for (CostMonitoringFocus fr : frames) {
			CostChartDto chart = snapshot.find(fr.div(), fr.cost());
			if (chart == null) {
				continue;
			}
			List<Daily> spending = new ArrayList<>(dailies(snapshot, chart));
			spending.removeIf(d -> d.amount() == null || Math.round(d.amount() * 10) <= 0);
			if (spending.size() < 2) {
				continue;
			}
			double total = spending.stream().mapToDouble(Daily::amount).sum();
			List<String> spikes = new ArrayList<>();
			Daily top = null;
			double topRatio = 0;
			double topAvg = 0;
			for (Daily d : spending) {
				double avg = (total - d.amount()) / (spending.size() - 1);
				double ratio = avg > 0 ? d.amount() / avg : 0;
				if (top == null || d.amount() > top.amount()) {
					top = d;
					topRatio = ratio;
					topAvg = avg;
				}
				if (avg > 0 && d.amount() >= SPIKE_MIN_K && ratio >= SPIKE_RATIO) {
					spikes.add(dayLabel(d.date(), lang) + " **" + k1(d.amount()) + "** ("
							+ String.format(Locale.ROOT, "%.1f", ratio) + t(lang, " lần TB ", "× avg ") + k1(CmText.r1(avg)) + ")");
				}
			}
			String name = fr.div() + " " + fr.cost();
			lines++;
			if (!spikes.isEmpty()) {
				spikeFrames++;
				if (firstSpike == null) {
					firstSpike = fr;
				}
				sb.append("- **").append(name).append("**: ").append(String.join("; ", spikes)).append('\n');
			} else {
				sb.append("- ").append(name).append(t(lang, ": không có ngày đột biến; cao nhất ", ": no spike; highest "))
						.append(dayLabel(top.date(), lang)).append(' ').append(k1(top.amount())).append(" (")
						// làm tròn xuống: 1.94 lần không hiện "2.0 lần" khi ngưỡng là ≥ 2 (test ext-54)
						.append(String.format(Locale.ROOT, "%.1f", Math.floor(topRatio * 10) / 10))
						.append(t(lang, " lần TB ", "× avg "))
						.append(k1(CmText.r1(topAvg))).append(")\n");
			}
		}

		if (lines == 0) {
			return null;
		}

		// 1 khung: không ghi "1/1 khung" (test 2026-11-11 ext-54)
		String head = spikeFrames == 0
				? t(lang, "**Không có ngày chi đột biến**", "**No spending spike**")
				: lines == 1
				? t(lang, "**Có ngày chi đột biến:**", "**Spending spike found:**")
				: t(lang, "**Có ngày chi đột biến ở " + spikeFrames + "/" + lines + " khung:**",
				"**Spending spikes in " + spikeFrames + "/" + lines + " frame(s):**");

		String text = head + "\n" + sb + "\n_" + t(lang, "Đột biến: chi trong ngày ≥ ", "Spike: daily spending ≥ ")
				+ String.format(Locale.ROOT, "%.0f", SPIKE_RATIO)
				+ t(lang, " lần TB các ngày có chi khác và ≥ ", "× the average of other spending days and ≥ ")
				+ String.format(Locale.ROOT, "%.1f", SPIKE_MIN_K) + " K$. "
				+ t(lang, "Số liệu tháng ", "Data for month ") + snapshot.month()
				+ t(lang, ", actual đến ngày ", ", actual up to day ") + snapshot.d() + "._";

		CostMonitoringFocus focus = firstSpike != null ? firstSpike : frames.get(0);
		String name = focus.div() + " " + focus.cost();
		return new Answer(text.trim(), List.of(
				t(lang, "Ngày nào " + name + " chi nhiều nhất?", "Which day did " + name + " spend the most?"),
				t(lang, "Vật tư nào chi nhiều nhất ở " + name + "?", "Which items cost the most in " + name + "?")));
	}

	// =========================================================
	// CHI THEO TUẦN ("tuần này / tuần trước chi bao nhiêu")
	// Tuần = Thứ Hai -> Chủ nhật theo hôm nay (test 2026-11-10 ext-26: LLM lấy 01/11–07/11 cho "tuần này"
	// khi hôm nay là 10/11). Cộng chi trong ngày (dailies) trong tuần, chỉ phần nằm trong tháng đang xem và đã có số.
	// =========================================================

	private Answer weekSpend(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, boolean lastWeek,
							 String lang) {

		LocalDate monday = loader.today().with(java.time.DayOfWeek.MONDAY).minusWeeks(lastWeek ? 1 : 0);
		LocalDate sunday = monday.plusDays(6);
		LocalDate monthStart = snapshot.month().atDay(1);
		LocalDate monthEnd = snapshot.month().atEndOfMonth();
		LocalDate from = monday.isBefore(monthStart) ? monthStart : monday;
		LocalDate to = sunday.isAfter(monthEnd) ? monthEnd : sunday;

		if (to.isBefore(from)) {
			return null;
		}

		String label = lastWeek ? t(lang, "Tuần trước", "Last week") : t(lang, "Tuần này", "This week");
		StringBuilder sb = new StringBuilder();
		sb.append("**").append(label).append("** (").append(dayLabel(monday, lang)).append(" – ")
				.append(dayLabel(sunday, lang)).append("):\n");

		int lines = 0;
		for (CostMonitoringFocus fr : frames) {
			CostChartDto chart = snapshot.find(fr.div(), fr.cost());
			if (chart == null) {
				continue;
			}
			double sum = 0;
			LocalDate lastDay = null;
			for (Daily d : dailies(snapshot, chart)) {
				if (!d.date().isBefore(from) && !d.date().isAfter(to) && d.amount() != null) {
					sum += d.amount();
					lastDay = d.date();
				}
			}
			String name = fr.div() + " " + fr.cost();
			lines++;
			if (lastDay == null) {
				sb.append("- ").append(name).append(": ").append(t(lang, "chưa có số", "no data yet"))
						.append("RM".equals(fr.cost()) ? t(lang, " (RM ghi nhận chậm 1 ngày)", " (RM lags 1 day)") : "")
						.append('\n');
				continue;
			}
			sb.append("- ").append(name).append(": **").append(k1(sum)).append("** (")
					.append(ddmm(from)).append("–").append(ddmm(lastDay)).append(")\n");
		}

		if (lines == 0) {
			return null;
		}

		sb.append('\n').append('_');
		if (monday.isBefore(monthStart)) {
			sb.append(t(lang, "Chỉ tính các ngày thuộc tháng " + snapshot.month() + ". ",
					"Only days in month " + snapshot.month() + " are counted. "));
		}
		sb.append(t(lang, "Tuần tính từ Thứ Hai đến Chủ nhật; cộng chi trong ngày đã có số. ",
						"Week = Monday to Sunday; sum of daily spending with data. "))
				.append(t(lang, "Số liệu tháng ", "Data for month ")).append(snapshot.month())
				.append(t(lang, ", actual đến ngày ", ", actual up to day ")).append(snapshot.d()).append("._");

		CostMonitoringFocus first = frames.get(0);
		String name = first.div() + " " + first.cost();
		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Ngày nào " + name + " chi nhiều nhất?", "Which day did " + name + " spend the most?"),
				t(lang, lastWeek ? "Tuần này " + name + " chi bao nhiêu?" : "Tuần trước " + name + " chi bao nhiêu?",
						lastWeek ? "How much did " + name + " spend this week?" : "How much did " + name + " spend last week?")));
	}

	private Answer daySpend(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, LocalDate day,
							String lang) {

		boolean single = frames.size() == 1;
		List<String> lines = new ArrayList<>();
		int withSpend = 0;
		int withData = 0;
		String singleText = null;

		for (CostMonitoringFocus fr : frames) {

			CostChartDto chart = snapshot.find(fr.div(), fr.cost());

			if (chart == null) {
				continue;
			}

			String name = fr.div() + " " + fr.cost();
			int last = snapshot.lastActualDay(chart);
			Double amount = dayAmount(snapshot, chart, day);
			String value;

			if (last < day.getDayOfMonth() || amount == null) {
				value = t(lang, "chưa có số", "no data yet")
						+ ("RM".equals(fr.cost()) ? t(lang, " (RM ghi nhận chậm 1 ngày)", " (RM lags 1 day)") : "");
			} else {
				withData++;
				if (Math.round(amount * 10) == 0) {
					value = t(lang, "0.0 K$ (không phát sinh)", "0.0 K$ (no spending)");
				} else {
					withSpend++;
					value = "**" + k1(amount) + "**";
					Double avg = averageSpendingDay(snapshot, chart, day);
					if (avg != null) {
						// TB không gồm ngày đang xét -> ghi rõ "các ngày khác" (1303: GUIDE T&SL ghi TB 3.4 / 3.2 / 3.3 ở 3 câu)
					value += t(lang, " (TB các ngày có chi khác ", " (avg of other spending days ") + k1(avg) + ")";
					}
				}
			}

			lines.add("- " + name + ": " + value);

			if (single) {
				singleText = t(lang, "Ngày **", "On **") + dayLabel(day, lang) + t(lang, "** **", "** **")
						+ name + t(lang, "** chi: ", "** spent: ") + value + ".";
			}
		}

		if (lines.isEmpty()) {
			return null;
		}

		StringBuilder sb = new StringBuilder();

		if (single) {
			sb.append(singleText).append('\n');
		} else {
			sb.append(t(lang, "Chi phí ngày **", "Spending on **")).append(dayLabel(day, lang)).append("**: ");
			if (withData == 0) {
				sb.append(t(lang, "chưa có số ở các khung.", "no data yet in any frame."));
			} else if (withSpend == 0) {
				sb.append(t(lang, "**không phát sinh** ở các khung đã có số.", "**no spending** in frames with data."));
			} else {
				sb.append(t(lang, "có phát sinh ở " + withSpend + "/" + lines.size() + " khung.",
						"spending in " + withSpend + "/" + lines.size() + " frames."));
			}
			sb.append('\n');
			lines.forEach(l -> sb.append(l).append('\n'));
		}

		sb.append('\n').append(footer(snapshot, lang));

		CostMonitoringFocus first = frames.get(0);
		String name = first.div() + " " + first.cost();
		List<String> suggestions = List.of(
				t(lang, "Vật tư nào chi nhiều nhất ở " + name + "?", "Which items cost the most in " + name + "?"),
				t(lang, "Có gì bất thường trong tháng này?", "Anything unusual this month?"));

		return new Answer(sb.toString().trim(), suggestions);
	}

	// Trung bình các ngày có chi trong tháng (không gồm ngày đang xét); không có -> null
	private static Double averageSpendingDay(CostMonitoringSnapshot snapshot, CostChartDto chart, LocalDate exclude) {
		double sum = 0;
		int count = 0;
		for (Daily d : dailies(snapshot, chart)) {
			if (!d.date().equals(exclude) && d.amount() != null && Math.round(d.amount() * 10) > 0) {
				sum += d.amount();
				count++;
			}
		}
		return count > 0 ? CmText.r1(sum / count) : null;
	}


	// =========================================================
	// THÁNG CÒN MẤY NGÀY (audit 2026-11-10: qua LLM 28 s)
	// =========================================================

	private Answer remainingDays(CostMonitoringSnapshot snapshot, String lang) {

		LocalDate today = loader.today();

		if (!YearMonth.from(today).equals(snapshot.month())) {
			return null;
		}

		LocalDate end = snapshot.month().atEndOfMonth();
		int after = end.getDayOfMonth() - today.getDayOfMonth();
		Double work = workDays(today, end);

		StringBuilder sb = new StringBuilder();
		sb.append(t(lang, "Tháng ", "Month ")).append(snapshot.month())
				.append(t(lang, " có ", " has ")).append(snapshot.n())
				.append(t(lang, " ngày. Hôm nay **", " days. Today is **")).append(dayLabel(today, lang))
				.append(t(lang, "**, còn **", "**, **")).append(after)
				.append(t(lang, " ngày** sau hôm nay (", " days** left after today ("))
				.append(after + 1).append(t(lang, " ngày nếu tính cả hôm nay)", " including today)"));
		if (work != null) {
			sb.append(t(lang, "; trong đó ", "; ")).append(qty(work))
					.append(t(lang, " ngày nhà máy làm việc (từ hôm nay, theo lịch WD_All)",
							" factory working days (from today, WD_All calendar)"));
		}
		sb.append(".\n\n").append(footer(snapshot, lang));

		return new Answer(sb.toString(), List.of(
				t(lang, "Mỗi ngày còn lại được chi tối đa bao nhiêu để không vượt target?",
						"How much can we spend per remaining day to stay within target?"),
				t(lang, "Tháng này có khung nào dự báo vượt Fluct?", "Which frames are forecast to exceed Fluct?")));
	}


	// =========================================================
	// CHÀO HỎI
	// =========================================================

	private static Answer greeting(String lang) {

		List<String> samples = List.of(
				t(lang, "Tháng này có khung nào dự báo vượt Fluct?", "Which frames are forecast to exceed Fluct this month?"),
				t(lang, "Top vật tư PRESS T&SL", "Top items in PRESS T&SL"),
				t(lang, "Hôm qua có gì bất thường không?", "Anything unusual yesterday?"));

		StringBuilder sb = new StringBuilder(t(lang,
				"Xin chào! Mình hỗ trợ xem chi phí OT / T&SL / RM (Actual, FC, Fluct, PL) trên màn hình này. Bạn có thể hỏi:",
				"Hello! I can help with OT / T&SL / RM costs (Actual, FC, Fluct, PL) on this screen. You can ask:"))
				.append('\n');
		samples.forEach(s -> sb.append("- ").append(s).append('\n'));

		return new Answer(sb.toString().trim(), samples);
	}


	// =========================================================
	// MÃ VẬT TƯ CHUNG GIỮA CÁC KHUNG (audit 2026-11-10: LLM tự so 2 danh sách top 5, dễ bịa)
	// Chỉ xét top COMMON_TOP_N vật tư (theo tổng chi) của mỗi khung.
	// =========================================================

	private Answer commonItems(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, String lang) {

		List<Map<String, CostTopItemDto>> perFrameItems = perFrame(frames, fr -> {
			LocalDate[] r = range(snapshot, fr.cost(), null);
			Map<String, CostTopItemDto> byCode = new java.util.LinkedHashMap<>();
			if (r != null) {
				repository.findTopItems(fr.cost(), r[0], r[1],
								CostMonitoringSnapshot.DIV_COST_CENTERS.get(fr.div()), COMMON_TOP_N)
						.stream()
						.filter(i -> i.code() != null && !i.code().isBlank() && i.amountK() != null && i.amountK() > 0)
						.forEach(i -> byCode.putIfAbsent(i.code().trim(), i));
			}
			return byCode;
		});

		if (perFrameItems.stream().anyMatch(Map::isEmpty)) {
			return null;
		}

		Set<String> common = new LinkedHashSet<>(perFrameItems.get(0).keySet());
		perFrameItems.forEach(m -> common.retainAll(m.keySet()));

		String names = String.join(t(lang, " và ", " and "),
				frames.stream().map(fr -> fr.div() + " " + fr.cost()).toList());
		StringBuilder sb = new StringBuilder();

		if (common.isEmpty()) {
			sb.append(t(lang, "Không có mã vật tư nào xuất hiện ở cả ", "No item code appears in both "))
					.append(names).append(t(lang, " (trong top " + COMMON_TOP_N + " vật tư mỗi khung).",
							" (within the top " + COMMON_TOP_N + " items of each frame)."));
		} else {
			List<String> sorted = common.stream()
					.sorted(Comparator.comparing((String c) -> perFrameItems.stream()
							.mapToDouble(m -> m.get(c).amountK()).sum()).reversed())
					.toList();

			sb.append(t(lang, "Có **" + common.size() + "** mã vật tư xuất hiện ở cả ",
							"**" + common.size() + "** item code(s) appear in both "))
					.append(names).append(t(lang, " (trong top " + COMMON_TOP_N + " mỗi khung):",
							" (within the top " + COMMON_TOP_N + " of each frame):")).append('\n');

			for (String code : sorted.subList(0, Math.min(sorted.size(), COMMON_MAX_LINES))) {
				CostTopItemDto first = perFrameItems.get(0).get(code);
				sb.append("- **").append(CmText.itemLabel(first.code(), first.name(), lang)).append("**: ");
				List<String> parts = new ArrayList<>();
				for (int i = 0; i < frames.size(); i++) {
					parts.add(frames.get(i).div() + " " + frames.get(i).cost() + " "
							+ k2(perFrameItems.get(i).get(code).amountK()));
				}
				sb.append(String.join("; ", parts)).append('\n');
			}
			if (sorted.size() > COMMON_MAX_LINES) {
				sb.append(t(lang, "- … và " + (sorted.size() - COMMON_MAX_LINES) + " mã khác",
						"- … and " + (sorted.size() - COMMON_MAX_LINES) + " more")).append('\n');
			}
		}

		sb.append('\n').append('_').append(t(lang,
				"Chỉ xét top " + COMMON_TOP_N + " vật tư theo tổng chi của mỗi khung; chỉ chi phí trực tiếp.",
				"Only the top " + COMMON_TOP_N + " items by spending per frame; direct cost only.")).append('_');

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Top vật tư " + frames.get(0).div() + " " + frames.get(0).cost(),
						"Top items in " + frames.get(0).div() + " " + frames.get(0).cost())));
	}


	// =========================================================
	// SO VỚI THÁNG TRƯỚC (1 khung) – cùng cách tính ComparePreviousMonthTool, render cho người đọc
	// (test 2026-11-10 seed-17: LLM trả "không có dữ liệu" và nhắc tên tool)
	// =========================================================

	private Answer previousMonth(CostMonitoringSnapshot current, CostMonitoringFocus fr, String lang) {

		CostChartDto cur = current.find(fr.div(), fr.cost());

		if (cur == null) {
			return null;
		}

		CostMonitoringSnapshot previous = loader.load(current.month().minusMonths(1).toString(), null);
		CostChartDto prev = previous.find(fr.div(), fr.cost());
		String name = fr.div() + " " + fr.cost();

		if (prev == null) {
			return new Answer(t(lang,
					"Không có dữ liệu tháng " + previous.month() + " của " + name + " để so sánh.",
					"No data for " + previous.month() + " in " + name + " to compare."), List.of());
		}

		int last = current.lastActualDay(cur);
		int d = last > 0 ? Math.min(last, current.d()) : current.d();
		int prevDay = Math.min(d, previous.n());

		Double curAct = current.actAt(cur, d);
		Double prevAct = previous.actAt(prev, prevDay);
		Double curFluct = CostMonitoringSnapshot.fluct(cur);
		Double prevFluct = CostMonitoringSnapshot.fluct(prev);
		Double actDiff = CmText.subR1(curAct, prevAct);
		Double prevActR = CmText.r1(prevAct);
		Double actPct = actDiff == null || prevActR == null || prevActR == 0 ? null : actDiff / prevActR * 100;

		StringBuilder sb = new StringBuilder();
		sb.append("**").append(name).append("** ").append(current.month())
				.append(t(lang, " so với tháng trước ", " vs previous month ")).append(previous.month()).append(":\n");

		sb.append(t(lang, "- Actual cùng kỳ (đến ngày ", "- Actual same period (up to day ")).append(d).append("): **")
				.append(k1(curAct)).append("**").append(t(lang, " so với ", " vs ")).append(k1(prevAct));
		if (actDiff != null) {
			sb.append(" → ").append(CmText.signedK(actDiff))
					.append(actPct == null ? "" : " (" + CmText.signedPct1(actPct) + ")")
					.append(actDiff > 0 ? t(lang, ", tháng này chi nhiều hơn", ", higher this month")
							: actDiff < 0 ? t(lang, ", tháng này chi ít hơn", ", lower this month") : t(lang, ", bằng nhau", ", equal"));
		}
		sb.append('\n');

		sb.append("- FC: **").append(k1(cur.fcTgt())).append("**").append(t(lang, " so với ", " vs ")).append(k1(prev.fcTgt()))
				.append(" → ").append(CmText.signedK(CmText.subR1(cur.fcTgt(), prev.fcTgt()))).append('\n');
		sb.append("- Fluct: **").append(k1(curFluct)).append("**").append(t(lang, " so với ", " vs ")).append(k1(prevFluct))
				.append(" → ").append(CmText.signedK(CmText.subR1(curFluct, prevFluct)));
		Double fluctDiff = CmText.subR1(curFluct, prevFluct);
		if (fluctDiff != null && fluctDiff != 0) {
			sb.append(fluctDiff > 0 ? t(lang, " (target được nâng)", " (target raised)") : t(lang, " (target bị hạ)", " (target cut)"));
		}
		sb.append('\n');

		sb.append(t(lang, "- Actual cả tháng trước: ", "- Previous month full Actual: ")).append(k1(prev.actMtdLatest()));
		Double prevResult = CmText.subR1(prev.actMtdLatest(), prevFluct);
		if (prevResult != null) {
			sb.append(" (").append(CmText.versus("Fluct", prevResult, null, lang)).append(')');
		}
		sb.append('\n');

		sb.append('\n').append(footer(current, lang));

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Vật tư nào làm " + name + " tăng so với tháng trước?", "Which items drove " + name + " up vs last month?"),
				t(lang, "Cuối tháng " + name + " có vượt không?", "Will " + name + " exceed by month-end?")));
	}


	// =========================================================
	// XU HƯỚNG CHI THEO NGÀY (1 khung) – % đã dùng so với % thời gian, tốc độ chi, ngày cao / thấp nhất
	// (test 2026-11-10 seed-33: LLM mô tả sai "Fluct theo tiến độ tăng liên tục", trộn số dự báo)
	// =========================================================

	private Answer trend(CostMonitoringSnapshot snapshot, CostMonitoringFocus fr, String lang) {

		CostChartDto chart = snapshot.find(fr.div(), fr.cost());

		if (chart == null) {
			return null;
		}

		CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);
		String name = fr.div() + " " + fr.cost();

		if (a.act() == null || a.fluct() == null || a.fluct() == 0) {
			return new Answer(t(lang, name + " chưa có Actual tháng này.", name + " has no Actual this month yet."), List.of());
		}

		int actDay = Math.max(1, snapshot.lastActualDay(chart));
		double usedPct = a.act() / a.fluct() * 100;
		double timePct = (double) actDay / snapshot.n() * 100;
		List<Daily> days = new ArrayList<>(dailies(snapshot, chart));
		days.removeIf(x -> x.amount() == null);
		List<Daily> spending = days.stream().filter(x -> Math.round(x.amount() * 10) > 0).toList();

		StringBuilder sb = new StringBuilder();
		sb.append(t(lang, "Xu hướng chi **", "Spending trend of **")).append(name).append("** ")
				.append(t(lang, "đến ngày ", "up to day ")).append(actDay).append(":\n");

		sb.append(t(lang, "- Đã dùng **", "- Used **")).append(CmText.pct1(usedPct)).append(" Fluct** (")
				.append(k1(a.act())).append(" / ").append(k1(a.fluct())).append(t(lang, ") sau ", ") after "))
				.append(CmText.pct1(timePct)).append(t(lang, " thời gian → ", " of the month → "));
		if (a.excessVsPace() != null) {
			sb.append(CmText.signedK(a.excessVsPace(), t(lang, "chi nhanh hơn tiến độ", "ahead of pace"),
					t(lang, "chi chậm hơn tiến độ", "behind pace")));
		}
		sb.append('\n');

		if (a.currentRate() != null) {
			sb.append(t(lang, "- Tốc độ chi hiện tại **", "- Current rate **")).append(CmText.rate(a.currentRate(), lang)).append("**");
			if (a.targetRate() != null) {
				sb.append(t(lang, " so với nhịp Fluct ", " vs Fluct pace ")).append(CmText.rate(a.targetRate(), lang));
			}
			sb.append('\n');
		}

		if (!spending.isEmpty()) {
			Daily max = spending.stream().max(Comparator.comparing(Daily::amount)).get();
			Daily min = spending.stream().min(Comparator.comparing(Daily::amount)).get();
			sb.append(t(lang, "- Ngày chi cao nhất ", "- Highest day ")).append(dayLabel(max.date(), lang)).append(" **")
					.append(k1(max.amount())).append("**");
			if (spending.size() > 1) {
				sb.append(t(lang, "; thấp nhất (có chi) ", "; lowest (with spending) ")).append(dayLabel(min.date(), lang))
						.append(' ').append(k1(min.amount()));
			}
			sb.append(t(lang, "; TB ngày có chi ", "; avg spending day "))
					.append(k1(spending.stream().mapToDouble(Daily::amount).sum() / spending.size()));
			long zero = days.size() - spending.size();
			if (zero > 0) {
				sb.append(t(lang, "; ", "; ")).append(zero).append(t(lang, " ngày không phát sinh", " day(s) with no spending"));
			}
			sb.append('\n');

			// Nửa đầu / nửa sau các ngày có chi: tăng / giảm / ổn định (chênh TB ≥ 20%)
			if (spending.size() >= 4) {
				int half = spending.size() / 2;
				double first = spending.subList(0, half).stream().mapToDouble(Daily::amount).average().orElse(0);
				double second = spending.subList(half, spending.size()).stream().mapToDouble(Daily::amount).average().orElse(0);
				String direction = first <= 0 ? "" : second >= first * 1.2 ? t(lang, "tăng dần", "rising")
						: second <= first * 0.8 ? t(lang, "giảm dần", "falling") : t(lang, "tương đối ổn định", "fairly stable");
				if (!direction.isEmpty()) {
					sb.append(t(lang, "- Nhịp chi theo ngày: **", "- Daily pattern: **")).append(direction).append("** (TB ")
							.append(t(lang, "nửa đầu ", "first half ")).append(k1(first))
							.append(t(lang, ", nửa sau ", ", second half ")).append(k1(second)).append(")\n");
				}
			}
		}

		sb.append(t(lang, "- Dự báo: ", "- Forecast: ")).append(CmText.estimateBrief(a, lang)).append('\n');

		sb.append('\n').append(footer(snapshot, lang));

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Ngày nào " + name + " chi nhiều nhất?", "Which day did " + name + " spend the most?"),
				t(lang, "Vì sao " + name + " dự báo vượt?", "Why is " + name + " forecast to exceed?")));
	}


	// Xu hướng 2–3 khung (test 2026-11-11 ext-89: "Xu hướng chi T&SL và RM của MOLD" qua LLM 101 s, mô tả sai Fluct):
	// ghép template từng khung, 1 footer chung
	private Answer trendMany(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, String lang) {

		List<String> parts = new ArrayList<>();
		List<String> suggestions = List.of();

		for (CostMonitoringFocus fr : frames) {
			Answer one = trend(snapshot, fr, lang);
			if (one == null || one.text() == null) {
				continue;
			}
			String text = one.text();
			int cut = text.lastIndexOf("\n\n_");
			parts.add(cut > 0 ? text.substring(0, cut) : text);
			if (suggestions.isEmpty()) {
				suggestions = one.suggestions();
			}
		}

		if (parts.isEmpty()) {
			return null;
		}

		return new Answer(String.join("\n\n", parts) + "\n\n" + footer(snapshot, lang), suggestions);
	}


	// =========================================================
	// "KHI NÀO X VƯỢT FC": ngày đầu tiên SAU ngày có Actual cuối mà Estimate (MTD) > FC tháng (exceeds, số gốc).
	// FC cố định cả tháng (không điều chỉnh theo Ratio như Fluct) -> ngày vượt FC có thể khác ngày vượt Fluct.
	// =========================================================

	private static Answer whenExceedFc(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, String lang) {

		List<String> lines = new ArrayList<>();
		String first = null;

		for (CostMonitoringFocus fr : frames) {

			CostChartDto chart = snapshot.find(fr.div(), fr.cost());
			if (chart == null) {
				continue;
			}

			CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);
			String name = fr.div() + " " + fr.cost();
			Double fc = chart.fcTgt();
			String text;

			if (a.act() == null || fc == null) {
				text = t(lang, "chưa có Actual / FC", "no Actual / FC yet");
			} else if (CostMonitoringAssessment.exceeds(a.act(), fc)) {
				text = t(lang, "**đã vượt FC thực tế**: Actual ", "**already above FC**: Actual ") + k1(a.act())
						+ " / FC " + k1(fc) + " (" + CmText.signedK(CmText.subR1(a.act(), fc)) + ")";
			} else {
				LocalDate cross = fcCrossDay(chart, fc);
				String est = a.estimateEom() == null ? ""
						: t(lang, "; cuối tháng khoảng ", "; month-end about ") + k1(a.estimateEom()) + " ("
						+ CmText.versus("FC", a.estimateOverFc(), null, lang) + ")";
				text = cross != null
						? t(lang, "dự kiến vượt FC (" + k1(fc) + ") từ **", "expected to exceed FC (" + k1(fc) + ") from **")
						+ dayLabel(cross, lang) + "**" + est
						: a.estimateEom() != null
						? t(lang, "dự kiến **không vượt** FC (" + k1(fc) + ") trong tháng", "expected **not to exceed** FC ("
						+ k1(fc) + ") this month") + est
						: t(lang, "chưa có Estimate", "no Estimate yet");
				String fluctCross = CmText.estimateCrossText(chart, lang);
				if (fluctCross != null) {
					text += t(lang, "; mốc Fluct: ", "; Fluct: ") + fluctCross;
				}
			}

			if (first == null) {
				first = name;
			}
			lines.add((frames.size() == 1 ? "**" + name + "** " : "- **" + name + "**: ") + text);
		}

		if (lines.isEmpty()) {
			return null;
		}

		String body = String.join("\n", lines) + "\n\n_" + t(lang,
				"Ngày dự kiến vượt FC = ngày đầu tiên Estimate (MTD) vượt FC tháng; FC cố định cả tháng, còn Fluct đổi theo Ratio "
						+ "nên 2 mốc có thể khác ngày. ",
				"Expected FC day = first day the Estimate (MTD) exceeds the monthly FC; FC is fixed for the month while Fluct "
						+ "moves with the Ratio, so the two dates can differ. ")
				+ footer(snapshot, lang).substring(1);

		return new Answer(body, List.of(
				t(lang, "Khi nào " + first + " vượt Fluct?", "When will " + first + " exceed Fluct?"),
				t(lang, "Vì sao " + first + " dự báo vượt?", "Why is " + first + " forecast to exceed?")));
	}

	// Ngày đầu tiên sau ngày có Actual cuối mà Estimate (MTD) > FC; Actual cuối đã vượt / không vượt -> null
	private static LocalDate fcCrossDay(CostChartDto chart, Double fc) {

		LocalDate lastAct = null;

		for (CostChartPointDto point : chart.points()) {
			if (point.actMtd() != null && point.date() != null && (lastAct == null || point.date().isAfter(lastAct))) {
				lastAct = point.date();
			}
		}

		if (lastAct == null) {
			return null;
		}

		LocalDate cross = null;

		for (CostChartPointDto point : chart.points()) {
			if (point.date() == null || !point.date().isAfter(lastAct) || point.estMtd() == null) {
				continue;
			}
			if (CostMonitoringAssessment.exceeds(point.estMtd(), fc) && (cross == null || point.date().isBefore(cross))) {
				cross = point.date();
			}
		}

		return cross;
	}


	// =========================================================
	// TỐC ĐỘ CHI TUẦN NÀY SO VỚI TUẦN TRƯỚC (K$ / ngày làm việc; không có lịch -> ngày lịch)
	// Tuần = Thứ Hai -> Chủ nhật theo hôm nay; chỉ ngày thuộc tháng đang xem và đã có số của CHÍNH khung.
	// =========================================================

	private Answer weekCompare(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, String lang) {

		LocalDate thisMonday = loader.today().with(java.time.DayOfWeek.MONDAY);
		LocalDate lastMonday = thisMonday.minusWeeks(1);
		LocalDate monthStart = snapshot.month().atDay(1);

		record Span(LocalDate from, LocalDate to, double sum, double days, boolean work) {
		}

		List<String> lines = new ArrayList<>();
		boolean anyWork = false;
		int thisWeekDays = 0;
		String first = null;

		for (CostMonitoringFocus fr : frames) {

			CostChartDto chart = snapshot.find(fr.div(), fr.cost());
			if (chart == null) {
				continue;
			}

			List<Daily> days = dailies(snapshot, chart).stream().filter(d -> d.amount() != null).toList();
			if (days.isEmpty()) {
				continue;
			}
			LocalDate lastData = days.get(days.size() - 1).date();

			Span[] spans = new Span[2];
			LocalDate[] mondays = {thisMonday, lastMonday};

			for (int i = 0; i < 2; i++) {
				LocalDate from = mondays[i].isBefore(monthStart) ? monthStart : mondays[i];
				LocalDate end = mondays[i].plusDays(6);
				LocalDate to = end.isAfter(lastData) ? lastData : end;
				if (to.isBefore(from)) {
					continue;
				}
				double sum = 0;
				for (Daily d : days) {
					if (!d.date().isBefore(from) && !d.date().isAfter(to)) {
						sum += d.amount();
					}
				}
				Double work = workDays(from, to);
				boolean useWork = work != null && work > 0;
				double count = useWork ? work : java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
				spans[i] = new Span(from, to, CmText.r1(sum), count, useWork);
			}

			String name = fr.div() + " " + fr.cost();
			if (first == null) {
				first = name;
			}

			if (spans[0] == null || spans[1] == null) {
				lines.add("- **" + name + "**: " + (spans[0] == null
						? t(lang, "tuần này chưa có số", "no data this week yet")
						+ ("RM".equals(fr.cost()) ? t(lang, " (RM ghi nhận chậm 1 ngày)", " (RM lags 1 day)") : "")
						: t(lang, "tuần trước không có số trong tháng này", "no data last week in this month")));
				continue;
			}

			anyWork |= spans[0].work() && spans[1].work();
			thisWeekDays = Math.max(thisWeekDays, (int) (java.time.temporal.ChronoUnit.DAYS.between(spans[0].from(), spans[0].to()) + 1));

			Double cur = CmText.r2(spans[0].sum() / spans[0].days());
			Double prev = CmText.r2(spans[1].sum() / spans[1].days());
			Double diff = CmText.r2(cur - prev);
			Double pct = prev != null && prev > 0 ? diff / prev * 100 : null;

			String verdict = Math.round(diff * 100) == 0 ? t(lang, "**tương đương**", "**about the same**")
					: diff < 0 ? t(lang, "**chậm hơn**", "**slower**") : t(lang, "**nhanh hơn**", "**faster**");

			lines.add("- **" + name + "**: " + t(lang, "tuần này ", "this week ") + "**" + CmText.rate(cur, lang) + "** ("
					+ k1(spans[0].sum()) + " / " + qty(spans[0].days()) + t(lang, " ngày, ", " days, ")
					+ ddmm(spans[0].from()) + "–" + ddmm(spans[0].to()) + t(lang, ") so với tuần trước ", ") vs last week ")
					+ CmText.rate(prev, lang) + " (" + k1(spans[1].sum()) + " / " + qty(spans[1].days())
					+ t(lang, " ngày, ", " days, ") + ddmm(spans[1].from()) + "–" + ddmm(spans[1].to()) + ") → " + verdict
					+ " " + (diff > 0 ? "+" : "") + String.format(Locale.ROOT, "%.2f", diff) + t(lang, " K$/ngày", " K$/day")
					+ (pct == null ? "" : " (" + CmText.signedPct1(pct) + ")"));
		}

		if (lines.isEmpty()) {
			return null;
		}

		StringBuilder sb = new StringBuilder(t(lang, "Tốc độ chi tuần này so với tuần trước (K$ / ngày",
				"Spending rate this week vs last week (K$ per ")).append(anyWork ? t(lang, " làm việc):", " working day):")
				: t(lang, "):", " day):")).append('\n');
		lines.forEach(l -> sb.append(l).append('\n'));
		sb.append('\n').append('_').append(t(lang, "Tuần này mới có " + thisWeekDays + " ngày số liệu nên chưa đủ tuần, chỉ so tốc độ "
								+ "trung bình. ",
						"This week has only " + thisWeekDays + " day(s) of data, so only the average rate is compared. "))
				.append(anyWork ? t(lang, "Ngày làm việc theo lịch nhà máy (WD_All). ", "Working days from the factory calendar (WD_All). ") : "")
				.append(footer(snapshot, lang).substring(1));

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Tuần trước " + first + " chi bao nhiêu?", "How much did " + first + " spend last week?"),
				t(lang, "Mỗi ngày còn lại " + first + " được chi tối đa bao nhiêu?", "How much can " + first + " spend per remaining day?")));
	}


	// =========================================================
	// GIÁ TRỊ HIỆN TẠI CỦA KHUNG (Actual, FC, Fluct, PL, % đã dùng) – "X tháng này bao nhiêu", "Fluct của X là bao nhiêu"
	// =========================================================

	private Answer frameValue(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, String lang) {

		StringBuilder sb = new StringBuilder();
		int lines = 0;

		for (CostMonitoringFocus fr : frames) {

			CostChartDto chart = snapshot.find(fr.div(), fr.cost());

			if (chart == null) {
				continue;
			}

			String name = fr.div() + " " + fr.cost();
			Double fluct = CostMonitoringSnapshot.fluct(chart);
			lines++;

			sb.append("- **").append(name).append("**: ");
			if (chart.actMtdLatest() == null) {
				sb.append(t(lang, "Actual chưa có", "Actual not available"));
			} else {
				sb.append("Actual **").append(k1(chart.actMtdLatest())).append("**");
				int last = snapshot.lastActualDay(chart);
				if (last > 0) {
					sb.append(t(lang, " (đến ngày ", " (up to day ")).append(last).append(')');
				}
				if (fluct != null && fluct != 0) {
					sb.append(t(lang, ", đã dùng ", ", used ")).append(CmText.pct1(chart.actMtdLatest() / fluct * 100)).append(" Fluct");
				}
			}
			sb.append("; FC ").append(k1(chart.fcTgt())).append("; Fluct ").append(k1(fluct));
			if (chart.plTgt() != null) {
				sb.append("; PL ").append(k1(chart.plTgt()));
			}
			sb.append('\n');
		}

		if (lines == 0) {
			return null;
		}

		sb.append('\n').append(t(lang, "_Fluct = FC × (1 + Ratio), thay đổi theo ngày; ", "_Fluct = FC × (1 + Ratio), changes daily; "))
				.append(t(lang, "số liệu tháng ", "data for month ")).append(snapshot.month())
				.append(t(lang, ", actual đến ngày ", ", actual up to day ")).append(snapshot.d()).append("._");

		CostMonitoringFocus first = frames.get(0);
		String name = first.div() + " " + first.cost();

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Cuối tháng " + name + " có vượt không?", "Will " + name + " exceed by month-end?"),
				t(lang, "Vì sao Fluct của " + first.div() + " thay đổi?", "Why did " + first.div() + "'s Fluct change?")));
	}


	// =========================================================
	// VÌ SAO (1 khung) – nguyên nhân tính bằng code (CostMonitoringAnswerGuide gọi khi câu hỏi không hỏi thêm vật tư /
	// Category / tháng trước). Test 2026-11-10 seed-11: LLM bịa phép tính "54.5 K$/ngày × 9 = 490.5".
	//   Chưa vượt Fluct : chi vượt tiến độ (Actual − Fluct × D/N), 3 ngày chi lớn nhất, khoản lớn ≥ 10% Actual
	//   Đã vượt Fluct   : phần vượt = target bị hạ (FC − Fluct) + chi vượt FC (Actual − FC)
	//   Fluct so với FC : Ratio dương (target được nâng) KHÔNG phải nguyên nhân; Ratio âm góp phần
	// =========================================================

	public AnswerGuide why(CostMonitoringFocus fr, Map<String, String> params, String lang, String match) {
		return why(fr, params, lang, match, false);
	}

	// "X thì sao / thế nào / tình hình X" (FRAME, 1 khung): cùng nội dung, tiêu đề "Chi tiết" thay "Nguyên nhân"
	// (test 2026-11-10 seed-19: "MOLD RM thì sao?" qua LLM 43 s)
	static final String FRAME_STATUS = "frame_status";

	public AnswerGuide frameStatus(CostMonitoringFocus fr, Map<String, String> params, String lang, String match) {
		return why(fr, params, lang, match, true);
	}

	private AnswerGuide why(CostMonitoringFocus fr, Map<String, String> params, String lang, String match, boolean status) {

		Map<String, String> safeParams = params != null ? params : Map.of();
		CostMonitoringSnapshot snapshot = loader.load(safeParams);
		CostChartDto chart = snapshot.find(fr.div(), fr.cost());

		if (chart == null || snapshot.monthClosed()) {
			return null;
		}

		CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);
		String name = fr.div() + " " + fr.cost();

		if (a.act() == null || a.fluct() == null) {
			return new AnswerGuide(null, false, WHY_FRAME,
					t(lang, "**" + name + "** chưa có Actual tháng này nên chưa phân tích được nguyên nhân (chưa vượt hay dự báo gì).",
							"**" + name + "** has no Actual this month yet, so there is nothing to explain."),
					List.of(t(lang, "Tình hình chi phí tháng này thế nào?", "How are costs this month?")),
					match + "; lookup=" + WHY_FRAME + "; noActual; template=yes");
		}

		boolean over = a.alreadyOver();
		boolean forecastOver = a.estimateWillExceed();
		boolean ahead = a.excessVsPace() != null && a.excessVsPace() > 0;
		Double fluctVsFc = CmText.subR1(a.fluct(), a.fc());

		StringBuilder sb = new StringBuilder();

		// Câu đầu: kết luận
		if (over) {
			sb.append("**").append(name).append(t(lang, "** đã vượt Fluct ", "** is already above Fluct "))
					.append(CmText.signedK(CmText.subR1(a.act(), a.fluct()))).append(" (Actual ").append(k1(a.act()))
					.append(", Fluct ").append(k1(a.fluct())).append(").\n");
		} else if (forecastOver || ahead) {
			sb.append("**").append(name).append(t(lang, "** chưa vượt Fluct (Actual ", "** is not above Fluct yet (Actual "))
					.append(k1(a.act())).append(" / ").append(k1(a.fluct())).append(")")
					.append(forecastOver ? t(lang, " nhưng dự báo cuối tháng vượt", " but is forecast to exceed by month-end")
							: t(lang, " nhưng đang chi nhanh hơn tiến độ", " but is spending ahead of pace"))
					.append(".\n");
		} else {
			sb.append("**").append(name).append(t(lang, "** hiện không vượt và không dự báo vượt Fluct", "** is neither above nor forecast above Fluct"))
					.append(" (Actual ").append(k1(a.act())).append(" / ").append(k1(a.fluct())).append(").\n");
		}

		sb.append('\n').append(status ? t(lang, "**Chi tiết (hệ thống tính):**", "**Details (computed):**")
				: t(lang, "**Nguyên nhân (hệ thống tính):**", "**Causes (computed):**")).append('\n');

		if (over) {
			sb.append(t(lang, "- Phần vượt Fluct ", "- Excess over Fluct ")).append(CmText.signedK(CmText.subR1(a.act(), a.fluct())))
					.append(t(lang, " = target bị hạ (FC − Fluct) ", " = target cut (FC − Fluct) "))
					.append(CmText.signedK(CmText.subR1(a.fc(), a.fluct())))
					.append(t(lang, " + chi vượt FC (Actual − FC) ", " + spending above FC (Actual − FC) "))
					.append(CmText.signedK(CmText.subR1(a.act(), a.fc()))).append('\n');
		} else if (a.excessVsPace() != null) {
			sb.append(t(lang, "- Chi so với tiến độ (Actual − Fluct × D/N): ", "- Spending vs pace (Actual − Fluct × D/N): "))
					.append(CmText.signedK(a.excessVsPace(),
							t(lang, "chi nhanh hơn tiến độ – nguyên nhân chính", "ahead of pace – main cause"),
							t(lang, "chi trong tiến độ", "within pace")));
			if (a.currentRate() != null && a.targetRate() != null) {
				sb.append(t(lang, "; tốc độ ", "; rate ")).append(CmText.rate(a.currentRate(), lang))
						.append(t(lang, " so với nhịp Fluct ", " vs Fluct pace ")).append(CmText.rate(a.targetRate(), lang));
			}
			sb.append('\n');
		}

		if (fluctVsFc != null && Math.round(fluctVsFc * 10) < 0) {
			sb.append(t(lang, "- Fluct thấp hơn FC ", "- Fluct below FC ")).append(CmText.signedK(fluctVsFc))
					.append(t(lang, " (Ratio âm, target bị hạ) → góp phần làm dễ vượt Fluct", " (negative Ratio, target cut) → makes exceeding Fluct easier"))
					.append('\n');
		} else if (fluctVsFc != null && Math.round(fluctVsFc * 10) > 0) {
			sb.append(t(lang, "- Fluct cao hơn FC ", "- Fluct above FC ")).append(CmText.signedK(fluctVsFc))
					.append(t(lang, " (Ratio dương, target được nâng) → không phải nguyên nhân vượt", " (positive Ratio, target raised) → not a cause"))
					.append('\n');
		}

		// 3 ngày chi lớn nhất
		List<Daily> spending = new ArrayList<>(dailies(snapshot, chart));
		spending.removeIf(x -> x.amount() == null || Math.round(x.amount() * 10) <= 0);
		spending.sort(Comparator.comparing(Daily::amount).reversed());
		if (!spending.isEmpty()) {
			List<String> parts = new ArrayList<>();
			for (Daily d : spending.subList(0, Math.min(3, spending.size()))) {
				parts.add(dayLabel(d.date(), lang) + " " + k1(d.amount()) + " (" + CmText.pct1(CmText.r1(d.amount()) / a.act() * 100) + ")");
			}
			sb.append(t(lang, "- 3 ngày chi lớn nhất (% của Actual): ", "- Top 3 spending days (% of Actual): "))
					.append(String.join("; ", parts)).append('\n');
		}

		// Khoản lớn bất thường trong tháng (1 dòng ≥ 10% Actual)
		List<CostLineDto> unusual = unusualLines(fr, snapshot.month().atDay(1), itemsTo(snapshot, fr.cost()), a.act());
		for (CostLineDto line : unusual) {
			sb.append(t(lang, "- Khoản lớn: ", "- Large line: ")).append(CmText.itemLabel(line.code(), line.name(), lang))
					.append(line.date() != null ? " " + ddmm(line.date()) : "").append(' ').append(k2(line.amountK()))
					.append(" (").append(ExplainVarianceTool.pctOfAct(line.amountK(), a.act())).append(" Actual)")
					.append(t(lang, " – vẫn tính vào ngân sách tháng", " – still counts toward the monthly budget")).append('\n');
		}

		// Tình hình / đánh giá 1 khung: thêm ngân sách mỗi ngày so với tốc độ chi và kịch bản giữ tốc độ
		// (test 2026-11-10 ext-51: LLM nói "đúng tiến độ", "ngân sách 16.93 K$/ngày vẫn đủ" khi đang chi 19.29 K$/ngày)
		if (status && !over) {
			if (a.remainingPerDay() != null && a.remainingPerDay() > 0) {
				sb.append(t(lang, "- Ngân sách còn lại mỗi ngày: ", "- Budget left per day: "))
						.append(CmText.rate(a.remainingPerDay(), lang));
				if (a.currentRate() != null && a.currentRate() > 0) {
					sb.append(t(lang, "; đang chi ", "; currently ")).append(CmText.rate(a.currentRate(), lang));
					if (a.currentRate() > a.remainingPerDay()) {
						sb.append(t(lang, " → cần giảm ", " → needs a ")).append(CmText.pct0((1 - a.remainingPerDay() / a.currentRate()) * 100))
								.append(t(lang, "", " cut"));
					} else {
						sb.append(t(lang, " → trong ngân sách", " → within budget"));
					}
				}
				sb.append('\n');
			}
			if (a.linearForecast() != null) {
				sb.append(t(lang, "- Nếu giữ tốc độ hiện tại: cuối tháng khoảng ", "- At the current rate: month-end about "))
						.append(k1(a.linearForecast())).append("; ")
						.append(CmText.versus("Fluct", a.linearVsFluct(), null, lang)).append('\n');
			}
		}

		// Dòng dự báo riêng (trước đây "- cuối tháng …" đứng lẻ, không rõ của khung nào)
		sb.append('\n').append(t(lang, "**Dự báo:** ", "**Forecast:** ")).append(CmText.estimateBrief(a, lang)).append('\n');
		sb.append('\n').append(footer(snapshot, lang));

		List<String> suggestions = List.of(
				t(lang, "Vật tư nào chi nhiều nhất ở " + name + "?", "Which items cost the most in " + name + "?"),
				t(lang, "Vật tư nào làm " + name + " tăng so với tháng trước?", "Which items drove " + name + " up vs last month?"),
				t(lang, "Cứ chi như vậy thì " + name + " cuối tháng bao nhiêu?", "At this rate, where will " + name + " end the month?"));

		String kind = status ? FRAME_STATUS : WHY_FRAME;
		return new AnswerGuide(null, false, kind, sb.toString().trim(), suggestions,
				match + "; lookup=" + kind + "; template=yes");
	}

	// Khoản lớn ≥ 10% Actual trong khoảng ngày (tối đa MAX_UNUSUAL)
	private List<CostLineDto> unusualLines(CostMonitoringFocus fr, LocalDate from, LocalDate to, Double act) {

		if (act == null || act <= 0 || to.isBefore(from)) {
			return List.of();
		}

		try {
			List<CostLineDto> lines = repository.findUnusualLines(fr.cost(), from, to,
					CostMonitoringSnapshot.DIV_COST_CENTERS.get(fr.div()), act * UNUSUAL_SHARE, MAX_UNUSUAL);
			return lines == null ? List.of()
					: lines.stream().filter(l -> l.amountK() != null && l.amountK() >= act * UNUSUAL_SHARE).toList();
		} catch (RuntimeException e) {
			log.warn("Assistant lookup: cannot load unusual lines error={}", e.getClass().getSimpleName());
			return List.of();
		}
	}


	// =========================================================
	// CHUNG
	// =========================================================

	record Daily(LocalDate date, Double amount) {
	}

	// Chi trong ngày = Act MTD hôm đó − Act MTD gần nhất trước đó (số 1 chữ số, giống get_daily_series)
	static List<Daily> dailies(CostMonitoringSnapshot snapshot, CostChartDto chart) {

		LocalDate until = snapshot.month().atDay(snapshot.d());
		List<Daily> result = new ArrayList<>();
		Double lastMtd = 0.0;

		for (CostChartPointDto point : chart.points()) {

			if (point.date() == null || point.date().isAfter(until)
					|| !YearMonth.from(point.date()).equals(snapshot.month())) {
				continue;
			}

			if (point.actMtd() != null) {
				result.add(new Daily(point.date(), CmText.subR1(point.actMtd(), lastMtd)));
				lastMtd = point.actMtd();
			}
		}

		return result;
	}

	private static String footer(CostMonitoringSnapshot snapshot, String lang) {
		return "_" + t(lang, "Số liệu tháng ", "Data for month ") + snapshot.month()
				+ t(lang, ", actual đến ngày ", ", actual up to day ") + snapshot.d() + "._";
	}

	// "02/11 (T2)"
	private static String dayLabel(LocalDate date, String lang) {
		return ddmm(date) + " (" + DailySeriesTool.weekday(date.getDayOfWeek(), lang) + ")";
	}

	private static String ddmm(LocalDate date) {
		return String.format(Locale.ROOT, "%02d/%02d", date.getDayOfMonth(), date.getMonthValue());
	}

	private static String k1(Double value) {
		return CmText.k1(value);
	}

	private static String k2(Double value) {
		return value == null ? "" : String.format(Locale.ROOT, "%.2f K$", value);
	}

	// Bỏ số 0 thừa, tối đa 2 chữ số
	private static String qty(Double value) {
		return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP)
				.stripTrailingZeros().toPlainString();
	}
}
