package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.screen.AnswerGuide;
import com.example.backlogbe.assistant.screens.costmonitoring.CostMonitoringLookupAnswers.Daily;
import com.example.backlogbe.dto.costmonitoring.CostChartDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;

// =========================================================
// CÂU HỎI CẤP QUẢN LÝ COST MONITORING (template, KHÔNG gọi LLM)
//
// Lý do (test 2026-11-11, nhóm 30–34): các câu quản lý qua LLM 12–70 s và sai nghiêm trọng:
//   "Có gì cần tôi chú ý" -> "không có khung nào cần điều chỉnh" (5 khung dự báo vượt); "ok hay không" -> nói về OT;
//   "phải giảm chi bao nhiêu mỗi ngày" -> 0.8 K$/ngày (đúng ~2.9); "báo cáo giám đốc" ghi "theo PL" khi đang theo FC;
//   "Duyệt tăng target" -> trả template Ratio; "nhà máy" bị hiểu là "máy"; "Dự báo hôm nay so với hôm qua" bịa "không đổi".
// Chạy TRƯỚC CostMonitoringLookupAnswers / phân loại intent (CostMonitoringAnswerGuide.guide). Không khớp / thiếu dữ liệu
// / tháng đã kết thúc (với loại cần dự báo) -> null (luồng cũ).
//
// Loại câu hỏi (câu đã fold, CostMonitoringIntentClassifier.fold):
//   refuse_action     : duyệt / sửa / tăng target … lên N -> từ chối (trợ lý chỉ đọc số).
//   export_unsupported: xuất Excel / PDF / gửi email -> chưa hỗ trợ, đề nghị tóm tắt bằng chữ.
//   no_owner          : ai chịu trách nhiệm / phụ trách -> không có dữ liệu người, nêu bộ phận / cost center.
//   no_production_data: "… do sản lượng / doanh số" -> không có dữ liệu sản lượng; kiểm tra tiền đề + chiều Ratio.
//   multi_month       : "N tháng gần đây" -> kết quả từng tháng đã kết thúc (Actual so với Fluct / FC).
//   factory_prev_month: tổng nhiều bộ phận ("toàn nhà máy", "3 bộ phận", "tổng") so với cùng kỳ tháng trước.
//   what_if           : "nếu giảm / tăng x%" -> Actual + (Estimate − Actual) × (1 ∓ x), so với Fluct / FC + mức giảm tối
//                       thiểu để không vượt. Ghi rõ là giả định.
//   no_forecast_history: dự báo hôm nay so với hôm qua -> không lưu lịch sử dự báo; nêu dự báo hiện tại.
//   forecast_reliability: dự báo có đáng tin không -> cách tính, khung theo FC / PL, % tháng đã qua, khung 2 cách trái chiều.
//   badge_explain     : "vì sao ghi Vượt 106%" -> % dự báo = Estimate cuối tháng / Fluct; % thực tế = Actual / Fluct.
//   what to save      : saving_total – tổng phần dự báo vượt Fluct (gộp) + tổng ròng (ghi rõ).
//   total_forecast    : tổng dự báo cuối tháng T&SL + RM theo bộ phận và cả 3 bộ phận so với Fluct / FC (code cộng).
//   division_rank     : bộ phận nào đáng lo -> tổng K$ dự báo vượt Fluct theo bộ phận + khung đơn lẻ nặng nhất.
//   report_list       : cần báo cáo khung nào -> đã vượt thực tế trước, rồi dự báo vượt theo K$ giảm dần.
//   attention         : có gì cần tôi chú ý -> tối đa 3 điểm theo mức độ.
//   short_status      : ok hay không / tóm lại -> 1–2 câu.
//   exec_summary      : tóm tắt / báo cáo nhanh / giám đốc / meeting -> ≤ 8 dòng (kết luận, 3 điểm, đề xuất);
//                       "trong 3 câu" -> đúng 3 câu.
// Phạm vi khung: câu tổng hợp (summary, report, total, saving, division, reliability, attention) chỉ theo bộ phận / loại
//   chi phí NÊU trong câu (không bám ô drawer), mặc định 3 bộ phận × T&SL, RM (OT không có Actual). Câu 1 khung
//   (short, what-if, badge, history, production) dùng CostMonitoringLookupAnswers.frames (có drawer).
// Mọi số hiển thị / cộng / trừ tính từ số đã làm tròn 1 chữ số; "vượt" theo CostMonitoringAssessment (giống FE).
// =========================================================
@Slf4j
@Component
@RequiredArgsConstructor
public class CostMonitoringManagerAnswers {

	static final String REFUSE_ACTION = "refuse_action";
	static final String EXPORT = "export_unsupported";
	static final String OWNER = "no_owner";
	static final String PRODUCTION = "no_production_data";
	static final String MULTI_MONTH = "multi_month";
	static final String FACTORY_PREV = "factory_prev_month";
	static final String WHAT_IF = "what_if";
	static final String NO_HISTORY = "no_forecast_history";
	static final String RELIABILITY = "forecast_reliability";
	static final String BADGE = "badge_explain";
	static final String SAVING = "saving_total";
	static final String TOTAL = "total_forecast";
	static final String DIV_RANK = "division_rank";
	static final String REPORT_LIST = "report_list";
	static final String ATTENTION = "attention";
	static final String SHORT = "short_status";
	static final String SUMMARY = "exec_summary";
	// "Bạn là ai" (test 2026-11-11 ext-31: qua LLM 13 s; khi LLM treo thì LLM_ERROR 170 s)
	static final String IDENTITY = "identity";
	// "OT của PRESS tháng này bao nhiêu" (ext-32: qua LLM 7.5 s; khi LLM treo LLM_ERROR 170 s)
	static final String OT_VALUE = "ot_value";
	// "Bộ phận nào chi nhiều nhất?" – xếp bộ phận theo tổng Actual T&SL + RM (1303 ext-161)
	static final String DIV_SPEND = "division_spend";
	// Văn nói lãnh đạo (test 1440 nhóm 40) – trước đi LLM, kết luận sai "chi dưới Fluct nên ổn":
	// "PRESS dạo này tiêu hoang quá nhỉ?", "Sao cái này cao vậy?" -> kiểm tra theo tiến độ + dự báo
	static final String SPEND_CHECK = "spend_check";
	// "Khung nào đang đỏ?" – badge Vượt (Actual > Fluct); LLM liệt kê khung DỰ BÁO vượt như đã vượt
	static final String RED_FRAMES = "red_frames";
	// "Tháng trước có đỡ hơn không?", "RM có ổn không hay lại vượt như tháng trước?" – LLM: "không có dữ liệu"
	static final String STATUS_PREV = "status_vs_prev";

	private static final Pattern IDENTITY_WORD = CostMonitoringQuestion.word(
			"ban la ai|ban ten gi|ten ban la gi|ban la gi|who are you|what is your name|your name|ban lam duoc gi"
					+ "|ban giup duoc gi|ban co the lam gi|ban ho tro gi|what can you do");
	private static final Pattern VALUE_WORD = CostMonitoringQuestion.word(
			"bao nhieu|bn|how much|actual|so lieu|la bao nhieu|chi phi|cost|spent");
	private static final Pattern OT_EXCLUDE = CostMonitoringQuestion.word(
			"vuot|exceed|over|khi nao|bao gio|when|du bao|forecast|cuoi thang");

	private static final List<String> COSTS = List.of("T&SL", "RM");

	private static final Pattern APPROVE = CostMonitoringQuestion.word("duyet|phe duyet|approve|approval");
	private static final Pattern EDIT_TARGET = Pattern.compile(
			"(?<![\\p{L}\\p{N}_])(?:sua|doi|chinh|dat|set|change|update|edit|tang|giam|nang|ha)\\s+"
					+ "(?:target|fluct|fc|pl|bp|muc tieu|ngan sach|budget)(?:\\s+\\S+){0,4}?\\s+(?:len|xuong|thanh|to|=)\\s*\\d");
	private static final Pattern EXPORT_WORD = CostMonitoringQuestion.word(
			"xuat (?:bao cao|file|excel|pdf|csv|ra)|export|download|tai xuong|tai file|excel|pdf|powerpoint|pptx"
					+ "|gui (?:email|mail)|send (?:an )?e?mail");
	private static final Pattern OWNER_WORD = CostMonitoringQuestion.word(
			"ai chiu trach nhiem|ai phu trach|ai quan ly|nguoi phu trach|nguoi chiu trach nhiem|nguoi quan ly"
					+ "|who is responsible|who owns|who is in charge|person in charge");
	private static final Pattern PRODUCTION_WORD = CostMonitoringQuestion.word(
			"san luong|san xuat|production|output|volume|doanh so|doanh thu|sales");
	private static final Pattern CAUSAL = CostMonitoringQuestion.word(
			"do|vi|boi|because|due|caused|anh huong|lien quan");
	private static final Pattern MONTHS_BACK = CostMonitoringQuestion.word(
			"(\\d{1,2}|hai|ba|bon|nam|sau) thang (?:gan day|gan nhat|qua|vua qua|truoc)"
					+ "|(?:last|past|previous) (\\d{1,2}|two|three|four|five|six) months");
	private static final Pattern MONTHS_RECENT = CostMonitoringQuestion.word(
			"may thang gan day|cac thang gan day|cac thang truoc|nhung thang gan day|recent months");
	private static final Pattern PREV_MONTH_REF = CostMonitoringQuestion.word(
			"thang truoc|cung ky|last month|previous month");
	private static final Pattern SCOPE_ALL = CostMonitoringQuestion.word(
			"toan nhamay|ca nhamay|nhamay|toan bo|tong|3 bo phan|ba bo phan|cac bo phan|tat ca"
					+ "|factory|plant|overall|total|all divisions|whole");
	private static final Pattern PREV_COMPARE = CostMonitoringQuestion.word(
			"so voi|tang|giam|hon|compare|compared|vs|increase|decrease|higher|lower|than|up|down");
	// Hỏi vật tư / máy / cost center / Category -> luồng tra cứu (CostMonitoringLookupAnswers)
	private static final Pattern DETAIL = CostMonitoringQuestion.word(
			"vat tu|ma vat tu|item|items|material|materials|may nao|machine|machines|cost center|category|loai vat tu");
	private static final Pattern FORECAST_WORD = CostMonitoringQuestion.word("du bao|estimate|forecast|est|du kien");
	private static final Pattern HISTORY_CHANGE = CostMonitoringQuestion.word(
			"so voi hom qua|hom qua|thay doi|bien dong|changed|change|yesterday|since yesterday");
	private static final Pattern RELIABLE = CostMonitoringQuestion.word(
			"dang tin|tin cay|tin duoc|chinh xac|sai so|reliable|reliability|accurate|accuracy|trust|trustworthy");
	private static final Pattern BADGE_WORD = CostMonitoringQuestion.word("badge");
	// "Vượt 106%", "OK 44%"; văn nói "cái số 108% … là sao" (1440 ext-207 qua LLM)
	private static final Pattern BADGE_PCT = Pattern.compile(
			"(?:vuot|ok|over|so|con so|muc|ty le|cai)\\s+\\d{1,3}(?:[.,]\\d)?\\s*%");
	private static final Pattern BADGE_ASK = CostMonitoringQuestion.word(
			"ghi|hien|hien thi|la gi|nghia|vi sao|tai sao|why|show|shows|means|mean|la sao|sao vay|the nao");
	private static final Pattern IF_WORD = CostMonitoringQuestion.word("neu|gia su|gia dinh|what if|if|suppose|assuming");
	private static final Pattern PERCENT = Pattern.compile("(\\d{1,3}(?:[.,]\\d+)?)\\s*%");
	private static final Pattern DIR_DOWN = CostMonitoringQuestion.word(
			"giam|cat|cat giam|tiet kiem|reduce|reduces|reduced|cut|cuts|lower|lowers|decrease|decreases|save|saves");
	private static final Pattern DIR_UP = CostMonitoringQuestion.word("tang|increase|increases|raise|raises|more");
	private static final Pattern SAVE_WORD = CostMonitoringQuestion.word(
			"tiet kiem|cat giam|giam bot|phai giam|can giam|save|saving|savings|cut|reduce");
	private static final Pattern TOTAL_ASK = CostMonitoringQuestion.word(
			"tong|tong cong|tong so|total|in total|altogether|bao nhieu|how much");
	private static final Pattern PER_DAY = CostMonitoringQuestion.word(
			"moi ngay|1 ngay|mot ngay|hang ngay|per day|a day|each day|daily");
	private static final Pattern TOTAL_SCOPE = CostMonitoringQuestion.word(
			"tong chi phi|tong chi|tong cong|tong cac|toan nhamay|ca nhamay|3 bo phan|ba bo phan|tat ca bo phan"
					+ "|ca 3 bo phan|cac bo phan|total|overall|whole factory|all divisions");
	private static final Pattern TOTAL_ASK2 = CostMonitoringQuestion.word(
			// không nhận "bao nhiêu" đơn thuần: "X tổng cộng chi bao nhiêu" là hỏi Actual, không phải dự báo
			"du kien|cuoi thang|du bao|forecast|target|so voi|month-end|month end|estimate|fluct|fc");
	private static final Pattern DIV_WHICH = CostMonitoringQuestion.word(
			"bo phan nao|ben nao|ong nao|xuong nao|which division|which department|which divisions");
	// Không nhận "nhất / most" đơn thuần: "bộ phận nào chi nhiều nhất" là hỏi Actual
	private static final Pattern WORRY = CostMonitoringQuestion.word(
			"lo|dang lo|lo nhat|te|te nhat|nguy|xau|van de|worst|rui ro|concern|concerning|worried|riskiest"
					+ "|vuot nhieu|kem");
	// "Bộ phận nào chi nhiều nhất" = hỏi Actual (test 2026-11-11 1303 ext-161: qua LLM 38 s, trả "PRESS RM" – là khung,
	// không phải bộ phận)
	private static final Pattern SPEND_MOST = CostMonitoringQuestion.word(
			"chi nhieu nhat|chi nhieu|ton nhat|ton nhieu nhat|ton kem nhat|chi cao nhat|chi lon nhat"
					+ "|chi dam|dam hon|chi nhieu hon|ton hon|xai nhieu|xai nhieu nhat|xai nhieu hon"
					+ "|spends the most|spend the most|spent the most|spending the most|highest spending|most spending|biggest spender");
	// "Ông nào chi nhiều nhất" – dữ liệu không theo người; hiểu là bộ phận (ghi rõ cách hiểu)
	private static final Pattern PERSON_LIKE = CostMonitoringQuestion.word("ong nao|ai|thang nao|anh nao|chi nao");
	private static final Pattern HIGH_SPEND = CostMonitoringQuestion.word(
			"tieu hoang|dot tien|chi nhieu qua|chi nhieu vay|chi qua tay|chi manh tay|xai nhieu qua|xai qua tay|xai nhieu vay"
					+ "|ton qua|ton kem qua|ton vay|cao vay|cao qua|cao the|nhieu vay|nhieu qua vay|ghe vay|ghe qua"
					+ "|overspending|spending too much|so high|too high");
	private static final Pattern DAY_WHICH = CostMonitoringQuestion.word("ngay nao|hom nao|which day|what day");
	private static final Pattern RED_WORD = CostMonitoringQuestion.word(
			"dang do|mau do|bao do|badge do|bi do|len do|hien do|red|in the red");
	private static final Pattern STATUS_PREV_WORD = CostMonitoringQuestion.word(
			"do hon|kha hon|tot hon|te hon|xau hon|nhu thang truoc|giong thang truoc|lai vuot|lap lai"
					+ "|better than last month|worse than last month|better|worse|again");
	private static final Pattern OVER_HOW_MUCH = CostMonitoringQuestion.word(
			"vuot (?:thi vuot )?bao nhieu|vuot het bao nhieu|vuot tong cong bao nhieu|vuot nhieu khong"
					+ "|over by how much|exceed by how much|how much over");
	// "Tháng này tình hình sao rồi?" (không nêu khung) -> tóm tắt
	private static final Pattern OVERALL_STATUS = CostMonitoringQuestion.word(
			"tinh hinh (?:thang nay |chung |chi phi )?(?:sao roi|the nao|ra sao|sao)|thang nay (?:sao roi|the nao roi|ra sao roi)"
					+ "|tinh hinh chung|how are we doing|how is it going");
	// Câu có "tuần này / tuần trước" -> week_compare / week_spend của lookup (1303 ext-177 bị total_forecast bắt nhầm)
	private static final Pattern WEEK_REF = CostMonitoringQuestion.word(
			"tuan nay|tuan truoc|tuan qua|tuan vua roi|this week|last week|previous week");
	// "Chi phí toàn nhà máy tháng này thế nào?" -> tóm tắt (1303 ext-166: qua LLM, chỉ cộng Actual)
	private static final Pattern HOW_WORD = CostMonitoringQuestion.word(
			"the nao|ra sao|nhu the nao|nhu nao|how is|how are|how does|how do");
	private static final Pattern WHY = CostMonitoringQuestion.word(
			"vi sao|tai sao|nguyen nhan|ly do|do dau|why|cause|reason");
	private static final Pattern REPORT_WORD = CostMonitoringQuestion.word("bao cao|report|escalate|trinh");
	private static final Pattern WHICH_FRAMES = CostMonitoringQuestion.word(
			"khung nao|nhung khung|cac khung nao|which frames|which charts|which ones");
	private static final Pattern ATTENTION_WORD = CostMonitoringQuestion.word(
			"can (?:toi |em |minh |anh |chi |sep )?(?:chu y|luu y|quan tam|de y)|dang chu y|dang luu y"
					+ "|should i (?:watch|focus|look at|worry about)|need my attention|needs my attention|pay attention"
					// văn nói (1440 ext-186 "Có gì căng không em?" – LLM: "không có khung nào gặp vấn đề"; ext-189 / 199)
					+ "|co gi cang|cang khong|cang ko|co gi nong|co gi dang lo|dang lo khong|co van de gi|co gi nguy"
					+ "|lam gi truoc|nen lam gi|uu tien gi|uu tien lam gi|bat dau tu dau|what should i do|where should i start");
	private static final Pattern SHORT_WORD = CostMonitoringQuestion.word(
			"ok hay khong|ok khong|ok chua|on hay khong|tom lai|noi ngan gon|ngan gon|tom gon|in short|bottom line"
					+ "|tldr|yes or no"
					// văn nói (1440 ext-187 / 191 / 209 / 212: LLM kết luận "ổn" dù 5/6 khung dự báo vượt)
					+ "|ok ko|ok k|ok hong|on khong|on ko|on k|on hong|co sao khong|co sao ko|co sao k|co sao hong"
					+ "|chay ngan sach|chay tui|vo ngan sach|noi nhanh|nhanh gon|overall");
	private static final Pattern SUMMARY_WORD = CostMonitoringQuestion.word(
			"tom tat|tong ket|summary|summarize|summarise|bao cao nhanh|bao cao ngan|bao cao cho|bao cao len|giam doc"
					+ "|cap tren|ban giam doc|ban lanh dao|lanh dao|management|manager|meeting|cuoc hop|hop giao ban"
					+ "|briefing|brief|executive"
					// 1440 ext-201 "Anh cần 1 câu để báo sếp tổng"
					+ "|bao sep|sep tong|bao cao sep|bao len sep|bao cao len tren|boss");
	// "trong 3 câu", "3 dòng", "in 3 sentences" -> bản 3 câu
	private static final Pattern THREE_SENTENCES = CostMonitoringQuestion.word(
			"(?:1|2|3|mot|hai|ba) (?:cau|dong)|(?:1|2|3|one|two|three) (?:sentences?|lines?)");

	private static final double SPIKE_RATIO = 2.0;
	private static final double SPIKE_MIN_K = 1.0;
	private static final int MAX_MONTHS_BACK = 6;
	private static final int MAX_POINTS = 3;

	private final CostMonitoringSnapshotLoader loader;
	private final com.example.backlogbe.assistant.core.config.AssistantProperties assistantProperties;

	record Row(String name, String div, String cost, CostChartDto chart, CostMonitoringAssessment a) {
	}

	record Answer(String text, List<String> suggestions) {
	}

	/**
	 * @return AnswerGuide có directAnswer (template) hoặc null (không khớp / thiếu dữ liệu -> luồng cũ)
	 */
	public AnswerGuide guide(String question, Map<String, String> params, String lang) {

		if (question == null || question.isBlank()) {
			return null;
		}

		String f = CostMonitoringIntentClassifier.fold(question);
		String kind;
		try {
			kind = kind(question, f);
		} catch (RuntimeException e) {
			// Lỗi phân loại không được làm hỏng cả request (500 INTERNAL) – đi luồng cũ
			log.warn("Assistant manager kind failed error={}", e.getClass().getSimpleName());
			return null;
		}

		if (kind == null) {
			return null;
		}

		Map<String, String> safeParams = params != null ? params : Map.of();
		Answer answer;

		try {
			answer = answer(kind, question, f, safeParams, lang);
		} catch (RuntimeException e) {
			log.warn("Assistant manager answer failed kind={} error={}", kind, e.getClass().getSimpleName());
			return null;
		}

		if (answer == null || answer.text() == null || answer.text().isBlank()) {
			return null;
		}

		return new AnswerGuide(null, false, kind, answer.text(), answer.suggestions(),
				"manager=" + kind + "; template=yes");
	}

	// Loại câu hỏi; null = không khớp (thứ tự = ưu tiên)
	static String kind(String question, String f) {

		if (IDENTITY_WORD.matcher(f).find()) {
			return IDENTITY;
		}
		if (APPROVE.matcher(f).find() || EDIT_TARGET.matcher(f).find()) {
			return REFUSE_ACTION;
		}
		if (EXPORT_WORD.matcher(f).find()) {
			return EXPORT;
		}
		if (OWNER_WORD.matcher(f).find()) {
			return OWNER;
		}
		if (PRODUCTION_WORD.matcher(f).find() && CAUSAL.matcher(f).find()) {
			return PRODUCTION;
		}
		if (monthsBack(f) > 0) {
			return MULTI_MONTH;
		}

		boolean detail = DETAIL.matcher(f).find() || CostMonitoringLookupAnswers.dimension(question, f) != null;

		// Câu "tháng trước / cùng kỳ": chỉ nhận tổng nhiều bộ phận; còn lại để luồng tháng trước / item_change lo
		if (PREV_MONTH_REF.matcher(f).find()) {
			if (detail) {
				return null;
			}
			if (SCOPE_ALL.matcher(f).find() && PREV_COMPARE.matcher(f).find()) {
				return FACTORY_PREV;
			}
			// "Tháng trước có đỡ hơn không?", "… hay lại vượt như tháng trước?" (không có drawer-style "so với tháng trước thì sao")
			return STATUS_PREV_WORD.matcher(f).find() ? STATUS_PREV : null;
		}

		if (detail) {
			return null;
		}

		if (RED_WORD.matcher(f).find()) {
			return RED_FRAMES;
		}

		// Chỉ hỏi OT + hỏi số (không hỏi vượt / dự báo / 1 ngày cụ thể – ot_no_data của lookup lo)
		if (List.of("OT").equals(CostMonitoringQuestion.costs(CostMonitoringQuestion.normalize(question)))
				&& VALUE_WORD.matcher(f).find() && !OT_EXCLUDE.matcher(f).find() && !WHY.matcher(f).find()
				&& !CostMonitoringLookupAnswers.singleDay(question, f)) {
			return OT_VALUE;
		}

		if (IF_WORD.matcher(f).find() && PERCENT.matcher(f).find()
				&& (DIR_DOWN.matcher(f).find() || DIR_UP.matcher(f).find())) {
			return WHAT_IF;
		}
		if (FORECAST_WORD.matcher(f).find() && HISTORY_CHANGE.matcher(f).find()) {
			return NO_HISTORY;
		}
		if (RELIABLE.matcher(f).find()) {
			return RELIABILITY;
		}
		if (BADGE_WORD.matcher(f).find() || (BADGE_PCT.matcher(f).find() && BADGE_ASK.matcher(f).find())) {
			return BADGE;
		}
		if (SAVE_WORD.matcher(f).find() && TOTAL_ASK.matcher(f).find() && !PER_DAY.matcher(f).find()) {
			return SAVING;
		}
		// "Vượt thì vượt bao nhiêu, nói con số thôi" (1440 ext-198: LLM thiếu tổng)
		if (OVER_HOW_MUCH.matcher(f).find() && !PER_DAY.matcher(f).find()
				&& !CostMonitoringLookupAnswers.singleDay(question, f)) {
			return SAVING;
		}
		if (WEEK_REF.matcher(f).find()) {
			return null;
		}
		if (TOTAL_SCOPE.matcher(f).find() && TOTAL_ASK2.matcher(f).find()) {
			return TOTAL;
		}
		if (DIV_WHICH.matcher(f).find() && WORRY.matcher(f).find()) {
			return DIV_RANK;
		}
		if (DIV_WHICH.matcher(f).find() && SPEND_MOST.matcher(f).find() && !FORECAST_WORD.matcher(f).find()) {
			return DIV_SPEND;
		}
		if (TOTAL_SCOPE.matcher(f).find() && HOW_WORD.matcher(f).find() && !WHY.matcher(f).find()) {
			return SUMMARY;
		}
		if (REPORT_WORD.matcher(f).find() && WHICH_FRAMES.matcher(f).find()) {
			return REPORT_LIST;
		}
		if (ATTENTION_WORD.matcher(f).find()) {
			return ATTENTION;
		}
		// "Ngắn gọn: vì sao X vượt", "Tóm tắt nguyên nhân" -> luồng nguyên nhân
		if (WHY.matcher(f).find()) {
			return null;
		}
		if (HIGH_SPEND.matcher(f).find() && !DAY_WHICH.matcher(f).find()
				&& !CostMonitoringLookupAnswers.singleDay(question, f)) {
			return SPEND_CHECK;
		}
		if (SHORT_WORD.matcher(f).find()) {
			return SHORT;
		}
		if (OVERALL_STATUS.matcher(f).find()
				&& CostMonitoringQuestion.divs(CostMonitoringQuestion.normalize(question)).isEmpty()) {
			return SUMMARY;
		}
		if (SUMMARY_WORD.matcher(f).find()) {
			return SUMMARY;
		}

		return null;
	}

	private Answer answer(String kind, String question, String f, Map<String, String> params, String lang) {

		switch (kind) {
			case REFUSE_ACTION -> {
				return refuseAction(lang);
			}
			case EXPORT -> {
				return exportUnsupported(lang);
			}
			case OWNER -> {
				return owner(question, params, lang);
			}
			case IDENTITY -> {
				return identity(lang);
			}
			default -> {
			}
		}

		CostMonitoringSnapshot snapshot = loader.load(params);

		if (OT_VALUE.equals(kind)) {
			return otValue(snapshot, question, lang);
		}

		if (MULTI_MONTH.equals(kind)) {
			return multiMonth(snapshot, CostMonitoringLookupAnswers.frames(question, params), monthsBack(f), lang);
		}
		if (FACTORY_PREV.equals(kind)) {
			return factoryPrev(snapshot, scope(question), lang);
		}

		// Các loại còn lại dựa trên dự báo tháng đang diễn ra
		if (snapshot.monthClosed() || snapshot.d() < 1) {
			return null;
		}

		List<Row> focus = rows(snapshot, CostMonitoringLookupAnswers.frames(question, params));
		List<Row> all = rows(snapshot, scope(question));

		return switch (kind) {
			case PRODUCTION -> production(snapshot, focus, lang);
			case WHAT_IF -> whatIf(snapshot, focus.isEmpty() ? all : focus, f, lang);
			case NO_HISTORY -> noHistory(snapshot, focus.isEmpty() ? all : focus, lang);
			case RELIABILITY -> reliability(snapshot, all, lang);
			case BADGE -> badge(snapshot, focus, lang);
			case SAVING -> saving(snapshot, focus.isEmpty() ? all : focus, lang);
			case TOTAL -> total(snapshot, all, lang);
			case DIV_RANK -> divisionRank(snapshot, all, lang);
			case DIV_SPEND -> divisionSpend(snapshot, all, PERSON_LIKE.matcher(f).find(), lang);
			case SPEND_CHECK -> spendCheck(snapshot, focus.isEmpty() ? all : focus, lang);
			case RED_FRAMES -> redFrames(snapshot, all, lang);
			case STATUS_PREV -> statusVsPrev(snapshot, all, lang);
			case REPORT_LIST -> reportList(snapshot, all, lang);
			case ATTENTION -> attention(snapshot, all, lang);
			case SHORT -> shortStatus(snapshot, focus.isEmpty() ? all : focus, lang);
			case SUMMARY -> summary(snapshot, all, THREE_SENTENCES.matcher(f).find(), lang);
			default -> null;
		};
	}


	// =========================================================
	// KHÔNG CẦN DỮ LIỆU
	// =========================================================

	private static Answer refuseAction(String lang) {
		return new Answer(t(lang,
				"Mình **chỉ đọc số liệu**, không duyệt hay sửa target. FC / PL / BP TGT lấy từ dữ liệu kế hoạch; "
						+ "Fluct do hệ thống tính = FC × (1 + Ratio Sale&Inven). Muốn đổi target, vui lòng làm theo quy trình "
						+ "phê duyệt của bộ phận phụ trách kế hoạch.",
				"I **only read the data**; I cannot approve or change targets. FC / PL / BP TGT come from the planning data; "
						+ "Fluct is calculated as FC × (1 + Sale&Inven Ratio). To change a target, please follow the approval "
						+ "process of the planning owner."),
				List.of(t(lang, "Target MOLD được nâng hay hạ?", "Was the MOLD target raised or cut?"),
						t(lang, "Còn bao nhiêu tiền để chi cho MOLD RM?", "How much budget is left for MOLD RM?")));
	}

	private static Answer exportUnsupported(String lang) {
		return new Answer(t(lang,
				"Trợ lý **chưa hỗ trợ xuất file** (Excel / PDF) hay gửi email. Mình có thể tóm tắt bằng chữ để bạn sao chép, "
						+ "ví dụ hỏi \"Báo cáo nhanh cho giám đốc\".",
				"The assistant **cannot export files** (Excel / PDF) or send emails yet. I can write a text summary you can "
						+ "copy, e.g. ask \"Quick report for management\"."),
				List.of(t(lang, "Báo cáo nhanh cho giám đốc", "Quick report for management"),
						t(lang, "Cần báo cáo lên cấp trên những khung nào?", "Which frames should be escalated?")));
	}

	private Answer identity(String lang) {

		String name = assistantProperties.resolvedName();
		List<String> samples = List.of(
				t(lang, "Tóm tắt tình hình chi phí tháng này trong 3 câu", "Summarize this month in 3 sentences"),
				t(lang, "Khi nào PRESS T&SL vượt Fluct?", "When will PRESS T&SL exceed Fluct?"),
				t(lang, "Vì sao MOLD RM dự báo vượt?", "Why is MOLD RM forecast to exceed?"));

		StringBuilder sb = new StringBuilder(t(lang,
				"Mình là **" + name + "**, trợ lý của màn hình Cost Monitoring: đọc số OT / T&SL / RM (Actual, FC, Fluct, PL, "
						+ "Estimate) để trả lời về tình hình, dự báo cuối tháng, ngày dự kiến vượt, vật tư / máy / cost center "
						+ "và so với tháng trước. Mình chỉ đọc số, không sửa dữ liệu. Bạn có thể hỏi:",
				"I am **" + name + "**, the assistant for the Cost Monitoring screen: I read OT / T&SL / RM figures (Actual, "
						+ "FC, Fluct, PL, Estimate) to answer about status, month-end forecast, expected excess date, items / "
						+ "machines / cost centers and the previous month. I only read data, I do not change it. You can ask:"))
				.append('\n');
		samples.forEach(s -> sb.append("- ").append(s).append('\n'));

		return new Answer(sb.toString().trim(), samples);
	}

	// OT: chỉ có FC / Fluct (chưa có Actual) – nêu target từng bộ phận được hỏi, không bịa số đã chi
	private static Answer otValue(CostMonitoringSnapshot s, String question, String lang) {

		List<String> divs = CostMonitoringQuestion.divs(CostMonitoringQuestion.normalize(question));
		if (divs.isEmpty()) {
			divs = CostMonitoringSnapshot.DIVS;
		}

		StringBuilder sb = new StringBuilder(t(lang,
				"Khung **OT** chưa có Actual trên màn hình này (chỉ có FC / Fluct), nên chưa có số đã chi. Target tháng "
						+ s.month() + ":",
				"**OT** frames have no Actual on this screen (FC / Fluct only), so there is no spending yet. Targets for "
						+ s.month() + ":")).append('\n');

		int lines = 0;
		for (String div : divs) {
			CostChartDto chart = s.find(div, "OT");
			if (chart == null) {
				continue;
			}
			lines++;
			sb.append("- ").append(div).append(" OT: FC ").append(CmText.k1(chart.fcTgt()))
					.append("; Fluct ").append(CmText.k1(CostMonitoringSnapshot.fluct(chart))).append('\n');
		}

		if (lines == 0) {
			return null;
		}

		sb.append('\n').append(shortFooter(s, lang));

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Tháng này có khung nào dự báo vượt Fluct?", "Which frames are forecast to exceed Fluct?")));
	}

	private static Answer owner(String question, Map<String, String> params, String lang) {

		CostMonitoringFocus fr = CostMonitoringQuestion.frame(question, params);
		StringBuilder sb = new StringBuilder(t(lang,
				"Dữ liệu màn hình **không có thông tin người phụ trách**, nên mình không nêu tên được.",
				"The screen data **has no owner / person information**, so I cannot name anyone."));

		if (fr != null) {
			List<String> centers = CostMonitoringSnapshot.DIV_COST_CENTERS.get(fr.div());
			sb.append(t(lang, " Khung **", " Frame **")).append(fr.div()).append(' ').append(fr.cost())
					.append(t(lang, "** thuộc bộ phận **", "** belongs to division **")).append(fr.div()).append("**");
			if (centers != null && !centers.isEmpty()) {
				sb.append(" (cost center ").append(String.join(", ", centers)).append(')');
			}
			sb.append(t(lang, "; vui lòng hỏi quản lý bộ phận đó.", "; please check with that division's manager."));
		}

		return new Answer(sb.toString(), List.of(
				t(lang, "Cost center nào chi nhiều nhất?", "Which cost center spends the most?")));
	}


	// =========================================================
	// TÓM TẮT / BÁO CÁO / NGẮN GỌN / CẦN CHÚ Ý
	// =========================================================

	private Answer summary(CostMonitoringSnapshot s, List<Row> rows, boolean threeSentences, String lang) {

		if (rows.isEmpty()) {
			return null;
		}

		List<Row> flagged = flagged(rows);
		Row byK = flagged.isEmpty() ? null : flagged.get(0);
		Row byPct = flagged.stream().max(Comparator.comparing(
				(Row r) -> CmText.r1(r.a().estimatePctOfFluct()), Comparator.nullsFirst(Comparator.naturalOrder()))).orElse(null);
		Row earliest = earliestCross(rows);
		List<Row> within = rows.stream().filter(r -> !isFlagged(r)).toList();

		String s1 = conclusionSentence(rows, flagged, lang);
		String s2 = byK == null
				? highestSentence(rows, lang)
				: t(lang, "Nặng nhất là **", "The heaviest is **") + byK.name() + "**: " + forecastVsFluct(byK, lang)
				+ crossSuffix(byK, lang) + ".";
		String s3 = withinSentence(within, lang);

		StringBuilder sb = new StringBuilder();

		if (threeSentences) {
			sb.append(s1).append(' ').append(s2).append(' ').append(s3).append("\n\n");
		} else {
			sb.append(t(lang, "**Kết luận:** ", "**Conclusion:** ")).append(s1).append('\n');
			sb.append(t(lang, "**Điểm chính:**", "**Key points:**")).append('\n');
			sb.append("1. ").append(s2).append('\n');
			if (byPct != null && byPct != byK) {
				sb.append("2. ").append(t(lang, "Cao nhất theo % Fluct: **", "Highest as % of Fluct: **")).append(byPct.name())
						.append("** (").append(CmText.pct1(byPct.a().estimatePctOfFluct())).append(t(lang, " Fluct, ", " of Fluct, "))
						.append(CmText.signedK(byPct.a().estimateOver())).append(')').append(crossSuffix(byPct, lang)).append(".\n");
			} else if (earliest != null && earliest != byK) {
				sb.append("2. ").append(t(lang, "Dự kiến vượt sớm nhất: **", "Earliest expected excess: **"))
						.append(earliest.name()).append("**").append(crossSuffix(earliest, lang)).append(".\n");
			} else {
				sb.append("2. ").append(t(lang, "Chưa khung nào vượt Fluct thực tế (Actual ≤ Fluct).",
						"No frame is above Fluct in actual spending yet (Actual ≤ Fluct).")).append('\n');
			}
			sb.append("3. ").append(s3).append('\n');
			String watch = watchLine(byK, lang);
			if (watch != null) {
				sb.append(t(lang, "**Đề xuất theo dõi:** ", "**Suggested follow-up:** ")).append(watch).append('\n');
			}
			sb.append('\n');
		}

		sb.append(dataFooter(s, rows, lang));

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Cần báo cáo lên cấp trên những khung nào?", "Which frames should be escalated?"),
				t(lang, "Bộ phận nào đáng lo nhất?", "Which division is the most concerning?"),
				t(lang, "Để các khung không vượt thì phải tiết kiệm tổng cộng bao nhiêu?",
						"How much must be saved in total to keep all frames within Fluct?")));
	}

	private Answer shortStatus(CostMonitoringSnapshot s, List<Row> rows, String lang) {

		if (rows.isEmpty()) {
			return null;
		}

		StringBuilder sb = new StringBuilder();

		if (rows.size() == 1) {
			sb.append(frameShort(rows.get(0), lang));
		} else {
			List<Row> flagged = flagged(rows);
			sb.append(flagged.isEmpty() ? t(lang, "**Ổn:** ", "**OK:** ") : t(lang, "**Chưa ổn:** ", "**Not OK:** "))
					.append(conclusionSentence(rows, flagged, lang));
			if (!flagged.isEmpty()) {
				Row top = flagged.get(0);
				sb.append(t(lang, " Nặng nhất **", " Heaviest: **")).append(top.name()).append("** ")
						.append(CmText.signedK(top.a().estimateOver())).append(" (")
						.append(CmText.pct1(top.a().estimatePctOfFluct())).append(t(lang, " Fluct).", " of Fluct)."));
			}
		}

		sb.append("\n\n").append(shortFooter(s, lang));

		Row first = rows.get(0);
		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Báo cáo nhanh cho giám đốc", "Quick report for management"),
				t(lang, "Mỗi ngày còn lại " + first.name() + " được chi tối đa bao nhiêu?",
						"How much can " + first.name() + " spend per remaining day?")));
	}

	// 1 khung, tối đa 2 câu: thực tế + dự báo (+ ngày dự kiến vượt)
	private static String frameShort(Row r, String lang) {

		CostMonitoringAssessment a = r.a();
		StringBuilder sb = new StringBuilder("**").append(r.name()).append("**: ");

		if (a.alreadyOver()) {
			return sb.append(t(lang, "**đã vượt** Fluct thực tế ", "**already above** Fluct ")).append(CmText.signedK(a.monthResultGap()))
					.append(" (Actual ").append(CmText.k1(a.act())).append(" / Fluct ").append(CmText.k1(a.fluct())).append(')')
					.append(a.estimateEom() == null ? "" : t(lang, "; dự báo cuối tháng ", "; month-end forecast ")
							+ CmText.k1(a.estimateEom()))
					.append('.').toString();
		}

		sb.append(t(lang, "thực tế **chưa vượt** (Actual ", "actual spending is **not above** Fluct yet (Actual "))
				.append(CmText.k1(a.act())).append(" / Fluct ").append(CmText.k1(a.fluct())).append(")");

		if (a.estimateEom() == null) {
			return sb.append(t(lang, "; chưa có dự báo.", "; no forecast yet.")).toString();
		}

		if (a.estimateWillExceed()) {
			sb.append(t(lang, "; **dự báo vượt** Fluct ", "; **forecast above** Fluct by ")).append(CmText.signedK(a.estimateOver()))
					.append(" (").append(CmText.pct1(a.estimatePctOfFluct())).append(t(lang, " Fluct)", " of Fluct)"))
					.append(crossSuffix(r, lang));
		} else {
			sb.append(t(lang, "; dự báo cuối tháng **trong Fluct** (", "; month-end forecast **within Fluct** ("))
					.append(CmText.pct1(a.estimatePctOfFluct())).append(t(lang, " Fluct, còn dư ", " of Fluct, "))
					.append(CmText.k1(CmText.subR1(a.fluct(), a.estimateEom()))).append(t(lang, ")", " left)"));
			if (CostMonitoringAnswerGuide.positive(a.estimateOverFc())) {
				sb.append(t(lang, " nhưng vượt FC ", " but above FC ")).append(CmText.signedK(a.estimateOverFc()));
			}
		}

		return sb.append('.').toString();
	}

	private Answer reportList(CostMonitoringSnapshot s, List<Row> rows, String lang) {

		if (rows.isEmpty()) {
			return null;
		}

		List<Row> over = rows.stream().filter(r -> r.a().alreadyOver())
				.sorted(Comparator.comparing((Row r) -> CmText.r1(r.a().monthResultGap()), Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();
		List<Row> forecast = flagged(rows).stream().filter(r -> !r.a().alreadyOver()).toList();
		List<Row> within = rows.stream().filter(r -> !isFlagged(r)).toList();

		StringBuilder sb = new StringBuilder();
		int count = over.size() + forecast.size();

		if (count == 0) {
			sb.append(t(lang, "Chưa có khung nào cần báo cáo: không khung T&SL / RM nào vượt hay dự báo vượt Fluct.",
					"No frame needs escalation: no T&SL / RM frame is above or forecast above Fluct.")).append('\n');
		} else {
			sb.append(t(lang, "Nên báo cáo **" + count + " khung** (đã vượt thực tế trước, rồi dự báo vượt theo K$ giảm dần):",
					"Escalate **" + count + " frame(s)** (already over first, then forecast excess in K$, descending):")).append('\n');
			int i = 0;
			for (Row r : over) {
				sb.append(++i).append(". **").append(r.name()).append("** – ")
						.append(t(lang, "đã vượt thực tế: Actual ", "already over: Actual ")).append(CmText.k1(r.a().act()))
						.append(" / Fluct ").append(CmText.k1(r.a().fluct())).append(" (").append(CmText.signedK(r.a().monthResultGap()))
						.append(')');
				if (r.a().estimateEom() != null) {
					sb.append(t(lang, "; dự báo cuối tháng ", "; month-end forecast ")).append(CmText.k1(r.a().estimateEom()));
				}
				sb.append('\n');
			}
			for (Row r : forecast) {
				sb.append(++i).append(". **").append(r.name()).append("** – ").append(forecastVsFluct(r, lang))
						.append(crossSuffix(r, lang)).append('\n');
			}
		}

		if (!within.isEmpty()) {
			sb.append(t(lang, "Chưa cần báo cáo (dự báo trong Fluct): ", "No escalation needed (forecast within Fluct): "))
					.append(String.join("; ", within.stream().map(r -> withinText(r, lang)).toList())).append('\n');
		}

		sb.append('\n').append(dataFooter(s, rows, lang));

		Row first = !over.isEmpty() ? over.get(0) : !forecast.isEmpty() ? forecast.get(0) : rows.get(0);
		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Vì sao " + first.name() + " dự báo vượt?", "Why is " + first.name() + " forecast to exceed?"),
				t(lang, "Báo cáo nhanh cho giám đốc", "Quick report for management")));
	}

	private Answer attention(CostMonitoringSnapshot s, List<Row> rows, String lang) {

		if (rows.isEmpty()) {
			return null;
		}

		List<String> points = new ArrayList<>();
		List<Row> used = new ArrayList<>();

		// 1. Đã vượt thực tế
		for (Row r : rows) {
			if (r.a().alreadyOver() && points.size() < MAX_POINTS) {
				points.add("**" + r.name() + t(lang, " đã vượt Fluct thực tế** ", " is already above Fluct** ")
						+ CmText.signedK(r.a().monthResultGap()) + " (Actual " + CmText.k1(r.a().act()) + " / Fluct "
						+ CmText.k1(r.a().fluct()) + ").");
				used.add(r);
			}
		}

		// 2. Dự báo vượt nhiều nhất theo K$ (+ ngân sách mỗi ngày)
		List<Row> forecast = flagged(rows).stream().filter(r -> !r.a().alreadyOver()).toList();
		if (!forecast.isEmpty() && points.size() < MAX_POINTS) {
			Row top = forecast.get(0);
			String watch = watchLine(top, lang);
			points.add("**" + top.name() + t(lang, "** dự báo vượt Fluct nhiều nhất: ", "** has the largest forecast excess: ")
					+ CmText.signedK(top.a().estimateOver()) + " (" + CmText.pct1(top.a().estimatePctOfFluct())
					+ t(lang, " Fluct)", " of Fluct)") + crossSuffix(top, lang)
					+ (watch == null ? "." : "; " + watch));
			used.add(top);
		}

		// 3. Dự kiến vượt sớm nhất (khác khung trên)
		Row earliest = earliestCross(rows);
		if (earliest != null && !used.contains(earliest) && points.size() < MAX_POINTS) {
			points.add("**" + earliest.name() + t(lang, "** dự kiến vượt Fluct sớm nhất", "** is expected to exceed Fluct earliest")
					+ crossSuffix(earliest, lang) + t(lang, "; dự báo ", "; forecast ")
					+ CmText.signedK(earliest.a().estimateOver()) + ".");
			used.add(earliest);
		}

		// 4. Chi đột biến ở ngày có số mới nhất
		for (Row r : rows) {
			if (points.size() >= MAX_POINTS) {
				break;
			}
			String spike = latestSpike(s, r, lang);
			if (spike != null) {
				points.add(spike);
			}
		}

		// 5. Còn chỗ: khung dự báo vượt tiếp theo
		for (Row r : forecast) {
			if (points.size() >= MAX_POINTS) {
				break;
			}
			if (!used.contains(r)) {
				points.add("**" + r.name() + t(lang, "** dự báo vượt Fluct ", "** forecast above Fluct by ")
						+ CmText.signedK(r.a().estimateOver()) + " (" + CmText.pct1(r.a().estimatePctOfFluct())
						+ t(lang, " Fluct)", " of Fluct)") + crossSuffix(r, lang) + ".");
				used.add(r);
			}
		}

		StringBuilder sb = new StringBuilder();
		List<Row> flagged = flagged(rows);

		if (points.isEmpty()) {
			sb.append(t(lang, "Không có điểm cần chú ý gấp: không khung T&SL / RM nào vượt hay dự báo vượt Fluct, "
							+ "không có ngày chi đột biến gần nhất.",
					"Nothing urgent: no T&SL / RM frame is above or forecast above Fluct, and no recent spending spike.")).append('\n');
		} else {
			sb.append(t(lang, "**" + points.size() + " điểm cần chú ý** (xếp theo mức độ; " + flagged.size() + "/" + rows.size()
							+ " khung dự báo vượt Fluct):",
					"**" + points.size() + " point(s) to watch** (by severity; " + flagged.size() + "/" + rows.size()
							+ " frames forecast above Fluct):")).append('\n');
			int i = 0;
			for (String p : points) {
				sb.append(++i).append(". ").append(p).append('\n');
			}
		}

		sb.append('\n').append(dataFooter(s, rows, lang));

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Tháng này có khung nào dự báo vượt Fluct?", "Which frames are forecast to exceed Fluct?"),
				t(lang, "Cần báo cáo lên cấp trên những khung nào?", "Which frames should be escalated?")));
	}

	// Ngày có số mới nhất của khung ≥ 2 lần TB các ngày có chi khác và ≥ 1.0 K$ -> 1 điểm; không -> null
	private static String latestSpike(CostMonitoringSnapshot s, Row r, String lang) {

		List<Daily> spending = new ArrayList<>(CostMonitoringLookupAnswers.dailies(s, r.chart()));
		spending.removeIf(d -> d.amount() == null || Math.round(d.amount() * 10) <= 0);

		if (spending.size() < 2) {
			return null;
		}

		Daily last = spending.get(spending.size() - 1);
		double others = spending.stream().mapToDouble(Daily::amount).sum() - last.amount();
		double avg = others / (spending.size() - 1);

		if (avg <= 0 || last.amount() < SPIKE_MIN_K || last.amount() / avg < SPIKE_RATIO) {
			return null;
		}

		return "**" + r.name() + t(lang, "** chi đột biến ngày ", "** spending spike on ") + dayLabel(last.date(), lang) + ": "
				+ CmText.k1(last.amount()) + " (" + String.format(Locale.ROOT, "%.1f", Math.floor(last.amount() / avg * 10) / 10)
				+ t(lang, " lần TB ", "× avg ") + CmText.k1(CmText.r1(avg)) + ").";
	}


	// =========================================================
	// TỔNG / TIẾT KIỆM / BỘ PHẬN
	// =========================================================

	private Answer total(CostMonitoringSnapshot s, List<Row> rows, String lang) {

		List<Row> withEst = rows.stream().filter(r -> r.a().estimateEom() != null).toList();

		if (withEst.isEmpty()) {
			return null;
		}

		String costs = costsLabel(withEst);
		StringBuilder sb = new StringBuilder();
		Map<String, List<Row>> byDiv = byDiv(withEst);

		sb.append(t(lang, "Dự báo cuối tháng (" + costs + ") so với target, cộng theo bộ phận:",
				"Month-end forecast (" + costs + ") vs target, summed by division:")).append('\n');

		for (Map.Entry<String, List<Row>> e : byDiv.entrySet()) {
			double est = sumR1(e.getValue(), r -> r.a().estimateEom());
			double fluct = sumR1(e.getValue(), r -> r.a().fluct());
			double fc = sumR1(e.getValue(), r -> r.a().fc());
			sb.append("- **").append(e.getKey()).append("**: ").append(CmText.k1(est))
					.append(t(lang, " so với Fluct ", " vs Fluct ")).append(CmText.k1(fluct)).append(" → ")
					.append(CmText.signedK(CmText.r1(est - fluct)))
					.append("; FC ").append(CmText.k1(fc)).append(" → ").append(CmText.signedK(CmText.r1(est - fc))).append('\n');
		}

		double est = sumR1(withEst, r -> r.a().estimateEom());
		double fluct = sumR1(withEst, r -> r.a().fluct());
		double fc = sumR1(withEst, r -> r.a().fc());
		Double diff = CmText.r1(est - fluct);
		Double pct = fluct > 0 ? CmText.r1(est / fluct * 100) : null;

		sb.append('\n').append(t(lang, "**Tổng " + byDiv.size() + " bộ phận:** dự báo cuối tháng khoảng **",
						"**Total of " + byDiv.size() + " division(s):** month-end forecast about **"))
				.append(CmText.k1(est)).append(t(lang, "** so với Fluct ", "** vs Fluct ")).append(CmText.k1(fluct))
				.append(" → **").append(CmText.signedK(diff)).append("**")
				.append(pct == null ? "" : " (" + CmText.pct1(pct) + t(lang, " Fluct)", " of Fluct)"))
				.append(t(lang, "; so với FC ", "; vs FC ")).append(CmText.k1(fc)).append(" → ")
				.append(CmText.signedK(CmText.r1(est - fc))).append(".\n\n");

		sb.append('_').append(t(lang,
						"OT không tính vì chưa có Actual / dự báo. Tổng cộng từ số từng khung đã làm tròn 1 chữ số; bù trừ giữa "
								+ "các khung chỉ để tham khảo vì mỗi khung có target riêng. ",
						"OT is excluded (no Actual / forecast). Totals add the per-frame numbers rounded to 1 decimal; offsets "
								+ "between frames are for reference only since each frame has its own target. "))
				.append(dataFooter(s, rows, lang).substring(1));

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Bộ phận nào đáng lo nhất?", "Which division is the most concerning?"),
				t(lang, "Để các khung không vượt thì phải tiết kiệm tổng cộng bao nhiêu?",
						"How much must be saved in total to keep all frames within Fluct?")));
	}

	private Answer saving(CostMonitoringSnapshot s, List<Row> rows, String lang) {

		List<Row> withEst = rows.stream().filter(r -> r.a().estimateOver() != null).toList();

		if (withEst.isEmpty()) {
			return null;
		}

		List<Row> over = withEst.stream().filter(r -> CostMonitoringAnswerGuide.positive(r.a().estimateOver()))
				.sorted(Comparator.comparing((Row r) -> r.a().estimateOver()).reversed()).toList();
		List<Row> under = withEst.stream().filter(r -> !CostMonitoringAnswerGuide.positive(r.a().estimateOver())).toList();

		StringBuilder sb = new StringBuilder();

		if (over.isEmpty()) {
			sb.append(t(lang, "Không cần tiết kiệm thêm: không khung nào dự báo vượt Fluct cuối tháng.",
					"No extra saving needed: no frame is forecast above Fluct at month-end.")).append('\n');
		} else {
			double gross = sumR1(over, r -> r.a().estimateOver());
			sb.append(t(lang, "Để " + (over.size() == 1 ? "khung" : over.size() + " khung") + " dự báo vượt về đúng Fluct, cần giảm tổng cộng khoảng **",
							"To bring " + over.size() + " frame(s) back to Fluct, total cuts of about **"))
					.append(CmText.k1(gross)).append(t(lang, "** so với dự báo:", "** vs the forecast are needed:")).append('\n');
			for (Row r : over) {
				sb.append("- ").append(r.name()).append(": ").append(CmText.signedK(r.a().estimateOver()))
						.append(t(lang, " (dự báo ", " (forecast ")).append(CmText.k1(r.a().estimateEom()))
						.append(" / Fluct ").append(CmText.k1(r.a().fluct())).append(")\n");
			}
			if (!under.isEmpty()) {
				double net = sumR1(withEst, r -> r.a().estimateOver());
				sb.append(t(lang, "Tổng ròng (bù trừ phần còn dư của ", "Net total (offsetting the headroom of "))
						.append(String.join(", ", under.stream()
								.map(r -> r.name() + " " + CmText.signedK(r.a().estimateOver())).toList()))
						.append("): ").append(CmText.signedK(CmText.r1(net)))
						.append(t(lang, " – chỉ tham khảo, vì mỗi khung có target riêng.",
								" – reference only, since each frame has its own target.")).append('\n');
			}
		}

		sb.append('\n').append(dataFooter(s, rows, lang));

		Row first = over.isEmpty() ? withEst.get(0) : over.get(0);
		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Mỗi ngày " + first.name() + " phải giảm chi bao nhiêu để không vượt Fluct?",
						"How much must " + first.name() + " cut per day to stay within Fluct?"),
				t(lang, "Nếu từ mai giảm 10% chi tiêu thì " + first.name() + " có còn vượt không?",
						"If spending drops 10% from tomorrow, will " + first.name() + " still exceed?")));
	}

	private Answer divisionRank(CostMonitoringSnapshot s, List<Row> rows, String lang) {

		List<Row> withEst = rows.stream().filter(r -> r.a().estimateOver() != null).toList();

		if (withEst.isEmpty()) {
			return null;
		}

		record Div(String name, double over, List<Row> rows) {
		}

		List<Div> divs = new ArrayList<>();
		byDiv(withEst).forEach((name, list) -> divs.add(new Div(name, sumR1(list, r -> r.a().estimateOver()), list)));
		divs.sort(Comparator.comparingDouble(Div::over).reversed());

		Div top = divs.get(0);
		StringBuilder sb = new StringBuilder();

		sb.append(t(lang, "Tiêu chí: tổng K$ dự báo cuối tháng vượt Fluct (" + costsLabel(withEst) + ") của từng bộ phận.",
				"Criterion: total forecast excess over Fluct in K$ (" + costsLabel(withEst) + ") per division.")).append('\n');

		if (CostMonitoringAnswerGuide.positive(top.over())) {
			sb.append(t(lang, "Đáng lo nhất: **", "Most concerning: **")).append(top.name()).append("** (")
					.append(CmText.signedK(top.over())).append(").\n");
		} else {
			sb.append(t(lang, "Không bộ phận nào dự báo vượt Fluct (tính gộp).", "No division is forecast above Fluct (combined).")).append('\n');
		}

		int i = 0;
		for (Div d : divs) {
			sb.append(++i).append(". **").append(d.name()).append("**: ").append(CmText.signedK(d.over())).append(" (")
					.append(String.join(", ", d.rows().stream()
							.map(r -> r.cost() + " " + CmText.signedK(r.a().estimateOver())).toList()))
					.append(")\n");
		}

		List<Row> flagged = flagged(withEst);
		if (!flagged.isEmpty()) {
			Row byK = flagged.get(0);
			Row byPct = flagged.stream().max(Comparator.comparing((Row r) -> CmText.r1(r.a().estimatePctOfFluct()),
					Comparator.nullsFirst(Comparator.naturalOrder()))).orElse(byK);
			sb.append(t(lang, "Theo khung đơn lẻ: vượt nhiều nhất theo K$ là **", "By single frame: largest excess in K$ is **"))
					.append(byK.name()).append("** ").append(CmText.signedK(byK.a().estimateOver()));
			if (byPct != byK) {
				sb.append(t(lang, "; cao nhất theo % là **", "; highest by % is **")).append(byPct.name()).append("** (")
						.append(CmText.pct1(byPct.a().estimatePctOfFluct())).append(t(lang, " Fluct)", " of Fluct)"));
			}
			sb.append(".\n");
		}

		sb.append('\n').append(dataFooter(s, rows, lang));

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Tình hình " + top.name() + " T&SL thế nào?", "How is " + top.name() + " T&SL doing?"),
				t(lang, "Cần báo cáo lên cấp trên những khung nào?", "Which frames should be escalated?")));
	}

	// Xếp bộ phận theo tổng Actual (T&SL + RM, số đã làm tròn); OT không có Actual
	private Answer divisionSpend(CostMonitoringSnapshot s, List<Row> rows, boolean personLike, String lang) {

		List<Row> withAct = rows.stream().filter(r -> CmText.r1(r.a().act()) != null).toList();

		if (withAct.isEmpty()) {
			return null;
		}

		record Div(String name, double act, List<Row> rows) {
		}

		List<Div> divs = new ArrayList<>();
		byDiv(withAct).forEach((name, list) -> divs.add(new Div(name, sumR1(list, r -> r.a().act()), list)));
		divs.sort(Comparator.comparingDouble(Div::act).reversed());

		Div top = divs.get(0);
		StringBuilder sb = new StringBuilder();
		if (personLike) {
			sb.append(t(lang, "_Dữ liệu không ghi theo người – mình hiểu câu hỏi là theo bộ phận._\n",
					"_Data is not recorded per person – answering per division._\n"));
		}
		sb.append(t(lang,
				"Bộ phận chi nhiều nhất (tổng Actual " + costsLabel(withAct) + " tháng " + s.month() + "): **" + top.name()
						+ "** " + CmText.k1(top.act()) + ".",
				"Division with the highest spending (total Actual " + costsLabel(withAct) + ", month " + s.month() + "): **"
						+ top.name() + "** " + CmText.k1(top.act()) + ".")).append('\n');

		int i = 0;
		for (Div d : divs) {
			sb.append(++i).append(". **").append(d.name()).append("**: ").append(CmText.k1(d.act())).append(" (")
					.append(String.join(", ", d.rows().stream()
							.map(r -> r.cost() + " " + CmText.k1(CmText.r1(r.a().act()))).toList()))
					.append(')');
			// Tiêu chí thứ 2: dự báo cuối tháng so với Fluct (gộp các khung của bộ phận)
			List<Row> withEst = d.rows().stream().filter(r -> r.a().estimateOver() != null).toList();
			if (!withEst.isEmpty()) {
				sb.append(t(lang, "; dự báo cuối tháng so với Fluct ", "; month-end forecast vs Fluct "))
						.append(CmText.signedK(sumR1(withEst, r -> r.a().estimateOver())));
			}
			sb.append('\n');
		}

		sb.append(t(lang,
				"Chi nhiều chưa có nghĩa là vượt: mỗi khung có target riêng (xem \"Bộ phận nào đáng lo nhất?\"). OT chưa có Actual nên không tính.",
				"Higher spending does not mean over target: each frame has its own target (ask \"Which division is the most concerning?\"). "
						+ "OT has no Actual, so it is excluded.")).append('\n');

		sb.append('\n').append(dataFooter(s, rows, lang));

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Bộ phận nào đáng lo nhất?", "Which division is the most concerning?"),
				t(lang, "Cost center nào chi nhiều nhất ở " + top.name() + "?", "Which cost center spends the most in " + top.name() + "?")));
	}

	// "PRESS dạo này tiêu hoang quá nhỉ?", "Sao cái này cao vậy?": kiểm tra tiền đề theo TIẾN ĐỘ và DỰ BÁO, không so Actual với
	// Fluct cả tháng (1440: LLM kết luận "Actual < Fluct nên không tiêu hoang" khi mới qua 1/3 tháng)
	private Answer spendCheck(CostMonitoringSnapshot s, List<Row> rows, String lang) {

		List<Row> list = rows.stream()
				.filter(r -> r.a().act() != null && r.a().fluct() != null && r.a().excessVsPace() != null)
				.toList();
		if (list.isEmpty()) {
			return null;
		}

		List<Row> fast = list.stream().filter(r -> CostMonitoringAnswerGuide.positive(CmText.r1(r.a().excessVsPace()))).toList();
		List<Row> forecastOver = list.stream().filter(r -> r.a().estimateWillExceed()).toList();
		boolean single = list.size() == 1;

		StringBuilder sb = new StringBuilder();
		if (!fast.isEmpty()) {
			sb.append(t(lang, "**Đúng là đang chi nhanh** hơn tiến độ ở ", "**Yes – spending is ahead of pace** in "))
					.append(single ? "**" + list.get(0).name() + "**" : fast.size() + "/" + list.size() + t(lang, " khung", " frames"));
			sb.append(forecastOver.isEmpty()
					? t(lang, ", nhưng dự báo cuối tháng vẫn trong Fluct.", ", but the month-end forecast is still within Fluct.")
					: t(lang, "; " + forecastOver.size() + " khung dự báo cuối tháng vượt Fluct.",
					"; " + forecastOver.size() + " frame(s) forecast above Fluct at month-end."));
		} else {
			sb.append(t(lang, "**Chưa thấy chi quá tay:** ", "**No overspending yet:** "))
					.append(t(lang, "chi chưa vượt tiến độ", "spending is not ahead of pace"))
					.append(forecastOver.isEmpty() ? t(lang, " và dự báo cuối tháng trong Fluct.", " and the month-end forecast is within Fluct.")
							: t(lang, ", nhưng " + forecastOver.size() + " khung dự báo cuối tháng vượt Fluct.",
							", but " + forecastOver.size() + " frame(s) are forecast above Fluct."));
		}
		sb.append("\n\n");

		for (Row r : list) {
			CostMonitoringAssessment a = r.a();
			Double excess = CmText.r1(a.excessVsPace());
			sb.append("- **").append(r.name()).append("**: Actual ").append(CmText.k1(a.act()))
					.append(" / Fluct ").append(CmText.k1(a.fluct()));
			if (a.pct() != null) {
				sb.append(" (").append(CmText.pct1(a.pct())).append(" Fluct)");
			}
			sb.append(t(lang, "; so với tiến độ ", "; vs pace ")).append(CmText.signedK(excess))
					.append(CostMonitoringAnswerGuide.positive(excess) ? t(lang, " (chi nhanh hơn)", " (ahead)")
							: excess != null && excess < 0 ? t(lang, " (chi chậm hơn)", " (behind)") : "");
			// Target bị hạ / được nâng (Fluct so với FC)
			Double fluctVsFc = CmText.subR1(a.fluct(), a.fc());
			if (fluctVsFc != null && fluctVsFc != 0) {
				sb.append(fluctVsFc < 0 ? t(lang, "; Fluct thấp hơn FC ", "; Fluct below FC ") : t(lang, "; Fluct cao hơn FC ", "; Fluct above FC "))
						.append(CmText.signedK(fluctVsFc));
			}
			sb.append(t(lang, "; dự báo: ", "; forecast: ")).append(CmText.estimateBrief(a, lang));
			String cross = a.estimateWillExceed() && !CostMonitoringAssessment.exceeds(a.act(), a.fluct())
					? CmText.estimateCrossText(r.chart(), lang) : null;
			if (cross != null) {
				sb.append(t(lang, "; dự kiến vượt từ ", "; expected to exceed from ")).append(cross);
			}
			sb.append('\n');
		}

		sb.append('\n').append('_').append(t(lang,
				"Đánh giá theo tiến độ (Actual so với Fluct × số ngày đã qua / số ngày tháng) và dự báo cuối tháng, không so Actual với "
						+ "Fluct cả tháng (mới qua " + s.d() + "/" + s.month().lengthOfMonth() + " ngày). ",
				"Judged by pace (Actual vs Fluct × elapsed days / days in month) and month-end forecast, not Actual vs the full-month "
						+ "Fluct (day " + s.d() + "/" + s.month().lengthOfMonth() + "). ")).append(dataFooter(s, list, lang).substring(1));

		Row first = list.get(0);
		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Vì sao " + first.name() + " dự báo vượt?", "Why is " + first.name() + " forecast to exceed?"),
				t(lang, "Mỗi ngày " + first.name() + " còn chi được bao nhiêu?", "How much can " + first.name() + " spend per day?")));
	}

	// "Khung nào đang đỏ?": đỏ = badge Vượt (Actual > Fluct, quy tắc FE); khung chỉ DỰ BÁO vượt thì badge vẫn OK
	private Answer redFrames(CostMonitoringSnapshot s, List<Row> rows, String lang) {

		List<Row> list = rows.stream().filter(r -> r.a().act() != null && r.a().fluct() != null).toList();
		if (list.isEmpty()) {
			return null;
		}

		List<Row> red = list.stream().filter(r -> CostMonitoringAssessment.exceeds(r.a().act(), r.a().fluct())).toList();
		List<Row> forecastOver = list.stream()
				.filter(r -> !red.contains(r) && r.a().estimateWillExceed())
				.sorted(Comparator.comparing((Row r) -> CmText.r1(r.a().estimateOver()), Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();

		StringBuilder sb = new StringBuilder();
		if (red.isEmpty()) {
			sb.append(t(lang, "**Chưa có khung nào đỏ** (badge \"Vượt\" = Actual > Fluct): " + list.size() + "/" + list.size()
							+ " khung thực tế vẫn trong Fluct.",
					"**No frame is red** (badge \"Over\" = Actual > Fluct): all " + list.size() + " frames are within Fluct in actual spending."));
		} else {
			sb.append(t(lang, "**" + red.size() + " khung đang đỏ** (Actual > Fluct):", "**" + red.size() + " red frame(s)** (Actual > Fluct):"))
					.append('\n');
			for (Row r : red) {
				sb.append("- **").append(r.name()).append("**: Actual ").append(CmText.k1(r.a().act())).append("; ")
						.append(CmText.versus("Fluct", CmText.subR1(r.a().act(), r.a().fluct()), null, lang)).append('\n');
			}
		}

		if (!forecastOver.isEmpty()) {
			sb.append(red.isEmpty() ? "\n\n" : "\n").append(t(lang, "Sắp đỏ – chưa vượt nhưng **dự báo cuối tháng vượt Fluct** (" + forecastOver.size() + " khung):",
					"About to turn red – not over yet but **forecast above Fluct** (" + forecastOver.size() + " frames):")).append('\n');
			for (Row r : forecastOver) {
				String cross = CmText.estimateCrossText(r.chart(), lang);
				sb.append("- ").append(r.name()).append(": ").append(CmText.signedK(r.a().estimateOver())).append(" (")
						.append(CmText.pct1(r.a().estimatePctOfFluct())).append(t(lang, " Fluct)", " of Fluct)"))
						.append(cross == null ? "" : t(lang, ", dự kiến vượt từ ", ", expected from ") + cross).append('\n');
			}
		}

		sb.append('\n').append(dataFooter(s, list, lang));

		Row first = forecastOver.isEmpty() ? (red.isEmpty() ? list.get(0) : red.get(0)) : forecastOver.get(0);
		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Vì sao " + first.name() + " dự báo vượt?", "Why is " + first.name() + " forecast to exceed?"),
				t(lang, "Mỗi ngày còn lại được chi tối đa bao nhiêu?", "How much can we spend per day for the rest of the month?")));
	}

	// "Tháng trước có đỡ hơn không?", "RM có ổn không hay lại vượt như tháng trước?": tháng này (thực tế + dự báo) đặt cạnh
	// kết quả cả tháng trước (Actual so với Fluct). So sánh tổng phần vượt: dự báo tháng này với kết quả tháng trước.
	private Answer statusVsPrev(CostMonitoringSnapshot s, List<Row> rows, String lang) {

		CostMonitoringSnapshot previous;
		try {
			previous = loader.load(s.month().minusMonths(1).toString(), null);
		} catch (RuntimeException e) {
			return null;
		}

		List<Row> cur = rows.stream().filter(r -> r.a().act() != null && r.a().fluct() != null).toList();
		if (cur.isEmpty()) {
			return null;
		}

		List<CostMonitoringFocus> focuses = cur.stream().map(r -> new CostMonitoringFocus(r.div(), r.cost())).toList();
		Map<String, Row> prev = new LinkedHashMap<>();
		for (Row r : rows(previous, focuses)) {
			prev.put(r.name(), r);
		}
		if (prev.isEmpty()) {
			return null;
		}

		List<Row> prevOver = prev.values().stream().filter(r -> CostMonitoringAssessment.exceeds(r.a().act(), r.a().fluct())).toList();
		List<Row> curOver = cur.stream().filter(r -> r.a().estimateWillExceed()).toList();
		double prevGap = sumR1(prevOver, r -> CmText.subR1(r.a().act(), r.a().fluct()));
		double curGap = sumR1(curOver, r -> r.a().estimateOver());

		String verdict;
		if (curOver.size() < prevOver.size() || (curOver.size() == prevOver.size() && curGap < prevGap)) {
			verdict = t(lang, "**Tháng này đang đỡ hơn tháng trước** (theo dự báo)", "**This month looks better than last month** (by forecast)");
		} else if (curOver.size() > prevOver.size() || curGap > prevGap) {
			verdict = t(lang, "**Tháng này đang tệ hơn tháng trước** (theo dự báo)", "**This month looks worse than last month** (by forecast)");
		} else {
			verdict = t(lang, "**Tháng này tương tự tháng trước** (theo dự báo)", "**This month looks similar to last month** (by forecast)");
		}

		StringBuilder sb = new StringBuilder(verdict).append(": ")
				.append(t(lang, "tháng trước (" + previous.month() + ") " + prevOver.size() + "/" + prev.size() + " khung vượt Fluct, tổng "
								+ CmText.signedK(prevGap) + "; tháng này dự báo " + curOver.size() + "/" + cur.size() + " khung vượt, tổng "
								+ CmText.signedK(curGap) + ".",
						"last month (" + previous.month() + ") " + prevOver.size() + "/" + prev.size() + " frames above Fluct, total "
								+ CmText.signedK(prevGap) + "; this month " + curOver.size() + "/" + cur.size() + " forecast above, total "
								+ CmText.signedK(curGap) + "."))
				.append("\n\n");

		for (Row r : cur) {
			Row p = prev.get(r.name());
			sb.append("- **").append(r.name()).append("**: ").append(t(lang, "tháng này dự báo ", "this month forecast "))
					.append(CmText.estimateBrief(r.a(), lang));
			if (p != null) {
				sb.append(t(lang, " | tháng trước: Actual ", " | last month: Actual ")).append(CmText.k1(p.a().act())).append(", ")
						.append(CmText.versus("Fluct", CmText.subR1(p.a().act(), p.a().fluct()), null, lang));
			}
			sb.append('\n');
		}

		sb.append('\n').append('_').append(t(lang,
				"Tháng trước là kết quả cả tháng (Actual), tháng này là dự báo cuối tháng (Estimate) – chưa phải số đã chi. ",
				"Last month is the full-month result (Actual); this month is the month-end forecast (Estimate), not spent yet. "))
				.append(dataFooter(s, cur, lang).substring(1));

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Vật tư nào tăng so với tháng trước?", "Which items increased vs last month?"),
				t(lang, "Tháng trước có gì bất thường?", "Anything unusual last month?")));
	}


	// =========================================================
	// KỊCH BẢN / ĐỘ TIN CẬY / BADGE / LỊCH SỬ / SẢN LƯỢNG
	// =========================================================

	private Answer whatIf(CostMonitoringSnapshot s, List<Row> rows, String f, String lang) {

		Matcher m = PERCENT.matcher(f);
		if (!m.find() || rows.isEmpty()) {
			return null;
		}

		double pct = Double.parseDouble(m.group(1).replace(',', '.'));
		if (pct <= 0 || pct > 100) {
			return null;
		}

		boolean up = DIR_UP.matcher(f).find() && !DIR_DOWN.matcher(f).find();
		double factor = up ? 1 + pct / 100 : 1 - pct / 100;
		String pctText = java.math.BigDecimal.valueOf(pct).stripTrailingZeros().toPlainString() + "%";

		StringBuilder sb = new StringBuilder(t(lang,
				"**Giả định:** phần chi còn lại (sau ngày có Actual mới nhất, theo đường Estimate) " + (up ? "tăng " : "giảm ") + pctText + ".",
				"**Assumption:** the remaining spending (after the latest Actual day, along the Estimate line) "
						+ (up ? "rises " : "drops ") + pctText + ".")).append('\n');

		int lines = 0;

		for (Row r : rows) {

			CostMonitoringAssessment a = r.a();
			Double act = CmText.r1(a.act());
			Double est = CmText.r1(a.estimateEom());
			Double fluct = CmText.r1(a.fluct());

			if (act == null || est == null || fluct == null) {
				continue;
			}

			Double remaining = CmText.r1(est - act);
			Double newRemaining = CmText.r1(remaining * factor);
			Double newEst = CmText.r1(act + newRemaining);
			Double gap = CmText.subR1(newEst, fluct);
			lines++;

			sb.append("- **").append(r.name()).append("**: ").append(t(lang, "hiện dự báo ", "current forecast "))
					.append(CmText.k1(est)).append(" (").append(CmText.versus("Fluct", a.estimateOver(), null, lang)).append(')')
					.append(t(lang, " → nếu " + (up ? "tăng " : "giảm ") + pctText + ": phần còn lại ",
							" → with " + (up ? "+" : "-") + pctText + ": remaining "))
					.append(CmText.k1(remaining)).append(" → ").append(CmText.k1(newRemaining))
					.append(t(lang, ", cuối tháng khoảng **", ", month-end about **")).append(CmText.k1(newEst)).append("** → ")
					.append(CostMonitoringAnswerGuide.positive(gap)
							? t(lang, "**vẫn vượt Fluct** ", "**still above Fluct** ") + CmText.signedK(gap)
							: t(lang, "**trong Fluct**, còn dư ", "**within Fluct**, ") + CmText.k1(CmText.r1(-gap)) + t(lang, "", " left"));

			// Mức giảm tối thiểu phần còn lại để không vượt Fluct
			if (!up && CostMonitoringAnswerGuide.positive(a.estimateOver()) && remaining > 0) {
				double need = (est - fluct) / remaining * 100;
				sb.append(need >= 100
						? t(lang, "; giảm 100% phần còn lại vẫn vượt (Actual đã sát / vượt Fluct)",
						"; even cutting 100% of the rest is not enough")
						: t(lang, "; để không vượt cần giảm ít nhất **", "; to stay within Fluct cut at least **")
						+ String.format(Locale.ROOT, "%.1f", Math.ceil(need * 10) / 10) + t(lang, "%** phần còn lại", "%** of the rest"));
			}
			sb.append('\n');
		}

		if (lines == 0) {
			return null;
		}

		sb.append('\n').append('_').append(t(lang,
				"Kịch bản giả định, không phải dự báo của hệ thống. Estimate theo PL (chưa có PL thì theo FC). ",
				"Hypothetical scenario, not the system forecast. Estimate based on PL (FC when no PL). "))
				.append(shortFooter(s, lang).substring(1));

		Row first = rows.get(0);
		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Mỗi ngày " + first.name() + " phải giảm chi bao nhiêu để không vượt Fluct?",
						"How much must " + first.name() + " cut per day to stay within Fluct?")));
	}

	private Answer reliability(CostMonitoringSnapshot s, List<Row> rows, String lang) {

		if (rows.isEmpty()) {
			return null;
		}

		List<String> byPl = rows.stream().filter(r -> "PL".equals(r.a().estimateBase())).map(Row::name).toList();
		List<String> byFc = rows.stream().filter(r -> "FC".equals(r.a().estimateBase())).map(Row::name).toList();
		List<Row> disagree = rows.stream().filter(r -> r.a().projectionsDisagree()).toList();
		double elapsed = (double) s.d() / s.n() * 100;

		StringBuilder sb = new StringBuilder(t(lang,
				"Dự báo cuối tháng = **Estimate**: Actual đến ngày có số mới nhất + PL TGT (chưa có PL thì FC TGT) phân bổ theo "
						+ "ngày làm việc còn lại, tức giả định từ nay chi đúng kế hoạch. Độ tin cậy phụ thuộc:",
				"Month-end forecast = **Estimate**: Actual up to the latest day + PL TGT (FC TGT when no PL) spread over the "
						+ "remaining working days, i.e. it assumes spending follows the plan from now on. Reliability depends on:")).append('\n');

		sb.append(t(lang, "- Mới qua ", "- Only ")).append(s.d()).append('/').append(s.n())
				.append(t(lang, " ngày (" + CmText.pct0(elapsed) + " tháng): phần còn lại là ước tính, có thể lệch.",
						" days passed (" + CmText.pct0(elapsed) + " of the month): the rest is an estimate and can deviate.")).append('\n');

		if (!byFc.isEmpty()) {
			sb.append(t(lang, "- Đang theo FC (chưa có PL tuần Simu): ", "- Based on FC (no PL for the Simu week): "))
					.append(String.join(", ", byFc)).append('\n');
		}
		if (!byPl.isEmpty()) {
			sb.append(t(lang, "- Theo PL: ", "- Based on PL: ")).append(String.join(", ", byPl)).append('\n');
		}
		if (!disagree.isEmpty()) {
			sb.append(t(lang, "- Kém chắc chắn hơn (theo tốc độ chi hiện tại thì kết luận ngược lại): ",
							"- Less certain (the current spending rate gives the opposite conclusion): "))
					.append(String.join("; ", disagree.stream()
							.map(r -> r.name() + " – " + CmText.projectedNote(r.a(), lang)).toList()))
					.append('\n');
		}
		sb.append(t(lang, "- Hệ thống không tính % độ tin cậy.", "- The system does not compute a confidence %.")).append("\n\n");
		sb.append(dataFooter(s, rows, lang));

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Estimate là gì?", "What is Estimate?"),
				t(lang, "Tháng này có khung nào dự báo vượt Fluct?", "Which frames are forecast to exceed Fluct?")));
	}

	private Answer badge(CostMonitoringSnapshot s, List<Row> rows, String lang) {

		if (rows.isEmpty() || rows.size() > 3) {
			return null;
		}

		StringBuilder sb = new StringBuilder();

		for (Row r : rows) {
			CostMonitoringAssessment a = r.a();
			sb.append("- **").append(r.name()).append("**: ");
			if (a.estimateEom() != null && !a.alreadyOver()) {
				sb.append(t(lang, "% dự báo = Estimate cuối tháng / Fluct = ", "forecast % = month-end Estimate / Fluct = "))
						.append(CmText.k1(a.estimateEom())).append(" / ").append(CmText.k1(a.fluct())).append(" ≈ **")
						.append(CmText.pct1(a.estimatePctOfFluct())).append("**")
						.append(" (").append(CmText.versus("Fluct", a.estimateOver(), null, lang)).append(")")
						.append(crossSuffix(r, lang)).append(t(lang, ". Thực tế ", ". Actual "));
			}
			sb.append(t(lang, "Actual / Fluct = ", "Actual / Fluct = ")).append(CmText.k1(a.act())).append(" / ")
					.append(CmText.k1(a.fluct())).append(" ≈ ").append(CmText.pct1(a.pct())).append(" → ")
					.append(a.alreadyOver()
							? t(lang, "**đã vượt** ", "**already over** ") + CmText.signedK(a.monthResultGap())
							: t(lang, "**chưa vượt**", "**not over yet**"))
					.append(".\n");
		}

		sb.append('\n').append('_').append(t(lang,
						"Badge theo Actual: \"OK x%\" khi Actual ≤ Fluct, \"Vượt x%\" khi Actual > Fluct; % dự báo = Estimate cuối "
								+ "tháng / Fluct. ",
						"Actual badge: \"OK x%\" when Actual ≤ Fluct, \"Over x%\" when Actual > Fluct; forecast % = month-end "
								+ "Estimate / Fluct. "))
				.append(shortFooter(s, lang).substring(1));

		Row first = rows.get(0);
		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Vì sao " + first.name() + " dự báo vượt?", "Why is " + first.name() + " forecast to exceed?"),
				t(lang, "Estimate là gì?", "What is Estimate?")));
	}

	private Answer noHistory(CostMonitoringSnapshot s, List<Row> rows, String lang) {

		if (rows.isEmpty()) {
			return null;
		}

		StringBuilder sb = new StringBuilder(t(lang,
				"Hệ thống **không lưu lịch sử dự báo theo ngày**, nên không so được dự báo hôm nay với hôm qua. Dự báo hiện tại:",
				"The system **does not keep a daily history of forecasts**, so today's forecast cannot be compared with "
						+ "yesterday's. Current forecast:")).append('\n');

		for (Row r : rows) {
			sb.append("- ").append(r.name()).append(": ").append(CmText.estimateBrief(r.a(), lang));
			List<Daily> days = CostMonitoringLookupAnswers.dailies(s, r.chart());
			if (!days.isEmpty()) {
				Daily last = days.get(days.size() - 1);
				sb.append(t(lang, "; chi ngày gần nhất ", "; latest day ")).append(dayLabel(last.date(), lang)).append(' ')
						.append(CmText.k1(last.amount()));
			}
			sb.append('\n');
		}

		sb.append('\n').append('_').append(t(lang,
						"Dự báo đổi khi có Actual ngày mới (Estimate bắt đầu từ Actual mới nhất), khi PL tuần Simu đổi, hoặc khi Fluct "
								+ "đổi theo Ratio Sale&Inven. ",
						"The forecast changes with each new Actual day (Estimate starts from the latest Actual), when the Simu "
								+ "week PL changes, or when Fluct moves with the Sale&Inven Ratio. "))
				.append(shortFooter(s, lang).substring(1));

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Vì sao Fluct của " + rows.get(0).div() + " bị hạ?", "Why was the " + rows.get(0).div() + " Fluct cut?")));
	}

	private Answer production(CostMonitoringSnapshot s, List<Row> rows, String lang) {

		StringBuilder sb = new StringBuilder(t(lang,
				"Màn hình **không có dữ liệu sản lượng / doanh số chi tiết**, nên không kết luận được nguyên nhân là do sản lượng.",
				"The screen has **no detailed production / sales data**, so it cannot conclude that output is the cause."));

		if (rows.isEmpty() || rows.size() > 2) {
			return new Answer(sb.toString(), List.of());
		}

		sb.append('\n');

		for (Row r : rows) {
			CostMonitoringAssessment a = r.a();
			sb.append("- **").append(r.name()).append("**: ");
			if (a.alreadyOver()) {
				sb.append(t(lang, "đã vượt Fluct thực tế ", "already above Fluct ")).append(CmText.signedK(a.monthResultGap()));
			} else {
				sb.append(t(lang, "thực tế **chưa vượt** (Actual ", "**not above** Fluct in actual spending (Actual "))
						.append(CmText.k1(a.act())).append(" / Fluct ").append(CmText.k1(a.fluct())).append(")");
				if (a.estimateWillExceed()) {
					sb.append(t(lang, ", chỉ **dự báo** vượt ", ", only **forecast** above by ")).append(CmText.signedK(a.estimateOver()));
				}
			}
			Double fluctVsFc = CmText.subR1(a.fluct(), a.fc());
			if (fluctVsFc != null && Math.round(fluctVsFc * 10) != 0) {
				sb.append(t(lang, ". Fluct đã điều chỉnh theo Ratio Sale&Inven: Fluct so với FC ",
								". Fluct is already adjusted by the Sale&Inven Ratio: Fluct vs FC "))
						.append(CmText.signedK(fluctVsFc))
						.append(fluctVsFc < 0
								? t(lang, " (target bị hạ – làm khung dễ vượt Fluct hơn, nhưng đó là điều chỉnh target, không chứng minh sản lượng tăng)",
								" (target cut – makes exceeding Fluct easier, but it is a target adjustment, not proof of higher output)")
								: t(lang, " (target được nâng)", " (target raised)"));
			}
			sb.append(".\n");
		}

		sb.append('\n').append(shortFooter(s, lang));

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Vì sao Fluct của " + rows.get(0).div() + " bị hạ?", "Why was the " + rows.get(0).div() + " Fluct cut?"),
				t(lang, "Vì sao " + rows.get(0).name() + " dự báo vượt?", "Why is " + rows.get(0).name() + " forecast to exceed?")));
	}


	// =========================================================
	// NHIỀU THÁNG / CÙNG KỲ TOÀN NHÀ MÁY
	// =========================================================

	private Answer multiMonth(CostMonitoringSnapshot current, List<CostMonitoringFocus> frames, int back, String lang) {

		if (frames.isEmpty()) {
			return null;
		}

		YearMonth viewed = current.month();
		StringBuilder sb = new StringBuilder();
		int overMonths = 0;
		int months = 0;
		boolean single = frames.size() == 1;
		List<String> lines = new ArrayList<>();

		for (int i = back; i >= 1; i--) {

			YearMonth month = viewed.minusMonths(i);
			CostMonitoringSnapshot snap;
			try {
				snap = loader.load(month.toString(), null);
			} catch (RuntimeException e) {
				lines.add("- " + month + ": " + t(lang, "không đọc được dữ liệu", "data not available"));
				continue;
			}

			List<Row> rows = rows(snap, frames);
			if (rows.isEmpty()) {
				lines.add("- " + month + ": " + t(lang, "chưa có dữ liệu", "no data"));
				continue;
			}
			months++;

			List<Row> over = rows.stream().filter(r -> CostMonitoringAssessment.exceeds(r.a().act(), r.a().fluct())).toList();
			if (!over.isEmpty()) {
				overMonths++;
			}

			if (single) {
				CostMonitoringAssessment a = rows.get(0).a();
				lines.add("- " + month + ": Actual " + CmText.k1(a.act()) + "; "
						+ CmText.versus("Fluct", CmText.subR1(a.act(), a.fluct()), null, lang) + " (Fluct " + CmText.k1(a.fluct())
						+ "); " + CmText.versus("FC", CmText.subR1(a.act(), a.fc()), null, lang));
			} else {
				lines.add("- " + month + ": " + over.size() + "/" + rows.size() + t(lang, " khung vượt Fluct", " frames above Fluct")
						+ (over.isEmpty() ? "" : " (" + String.join(", ", over.stream()
						.sorted(Comparator.comparing((Row r) -> CmText.r1(r.a().monthResultGap()),
								Comparator.nullsLast(Comparator.reverseOrder())))
						.map(r -> r.name() + " " + CmText.signedK(r.a().monthResultGap())).toList()) + ")"));
			}
		}

		if (months == 0) {
			return null;
		}

		String subject = single ? "**" + frames.get(0).div() + " " + frames.get(0).cost() + "**" : t(lang, "Các khung", "Frames");
		sb.append(subject).append(t(lang, " – " + back + " tháng gần đây (đã kết thúc): ", " – last " + back + " closed months: "))
				.append(overMonths == 0
						? t(lang, "không tháng nào vượt Fluct.", "no month above Fluct.")
						: t(lang, overMonths + "/" + months + " tháng có vượt Fluct.", overMonths + "/" + months + " month(s) above Fluct."))
				.append('\n');
		lines.forEach(l -> sb.append(l).append('\n'));
		sb.append('\n').append('_').append(t(lang, "Kết quả tháng = Actual cả tháng so với Fluct / FC (số làm tròn 1 chữ số).",
				"Month result = full-month Actual vs Fluct / FC (rounded to 1 decimal).")).append('_');

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Tháng trước có gì bất thường?", "Anything unusual last month?")));
	}

	private Answer factoryPrev(CostMonitoringSnapshot current, List<CostMonitoringFocus> frames, String lang) {

		CostMonitoringSnapshot previous;
		try {
			previous = loader.load(current.month().minusMonths(1).toString(), null);
		} catch (RuntimeException e) {
			return null;
		}

		record Pair(String div, String cost, double cur, double prev, int day) {
		}

		List<Pair> pairs = new ArrayList<>();

		for (CostMonitoringFocus fr : frames) {
			CostChartDto cur = current.find(fr.div(), fr.cost());
			CostChartDto prev = previous.find(fr.div(), fr.cost());
			if (cur == null || prev == null) {
				continue;
			}
			int last = current.lastActualDay(cur);
			int d = last > 0 ? Math.min(last, current.d()) : current.d();
			Double c = CmText.r1(current.actAt(cur, d));
			Double p = CmText.r1(previous.actAt(prev, Math.min(d, previous.n())));
			if (c == null || p == null) {
				continue;
			}
			pairs.add(new Pair(fr.div(), fr.cost(), c, p, d));
		}

		if (pairs.isEmpty()) {
			return null;
		}

		String costs = String.join(" + ", pairs.stream().map(Pair::cost).distinct().toList());
		Map<String, double[]> byDiv = new LinkedHashMap<>();
		for (Pair p : pairs) {
			double[] v = byDiv.computeIfAbsent(p.div(), k -> new double[2]);
			v[0] += p.cur();
			v[1] += p.prev();
		}

		StringBuilder sb = new StringBuilder();
		double cur = 0;
		double prev = 0;
		String topDiv = null;
		double topDiff = Double.NEGATIVE_INFINITY;
		List<String> lines = new ArrayList<>();

		for (Map.Entry<String, double[]> e : byDiv.entrySet()) {
			double c = CmText.r1(e.getValue()[0]);
			double p = CmText.r1(e.getValue()[1]);
			double diff = CmText.r1(c - p);
			cur += c;
			prev += p;
			if (diff > topDiff) {
				topDiff = diff;
				topDiv = e.getKey();
			}
			lines.add("- " + e.getKey() + ": " + CmText.k1(c) + t(lang, " so với ", " vs ") + CmText.k1(p) + " → "
					+ CmText.signedK(diff) + pctChange(diff, p));
		}

		cur = CmText.r1(cur);
		prev = CmText.r1(prev);
		double diff = CmText.r1(cur - prev);

		sb.append(t(lang, "**Chi " + costs + "** " + byDiv.size() + " bộ phận, cùng kỳ tháng " + current.month() + " so với "
								+ previous.month() + ": **" + CmText.k1(cur) + "** so với " + CmText.k1(prev) + " → **"
								+ CmText.signedK(diff) + "**" + pctChange(diff, prev) + " – "
								+ (diff > 0 ? "tăng" : diff < 0 ? "giảm" : "không đổi") + ".",
						"**" + costs + " spending**, " + byDiv.size() + " division(s), same period " + current.month() + " vs "
								+ previous.month() + ": **" + CmText.k1(cur) + "** vs " + CmText.k1(prev) + " → **"
								+ CmText.signedK(diff) + "**" + pctChange(diff, prev) + " – "
								+ (diff > 0 ? "up" : diff < 0 ? "down" : "unchanged") + "."))
				.append('\n');
		lines.forEach(l -> sb.append(l).append('\n'));
		if (topDiv != null && topDiff > 0) {
			sb.append(t(lang, "Bộ phận tăng nhiều nhất: **", "Largest increase: **")).append(topDiv).append("** ")
					.append(CmText.signedK(topDiff)).append(".\n");
		}

		String periods = String.join("; ", pairs.stream()
				.collect(java.util.stream.Collectors.toMap(Pair::cost, Pair::day, Integer::min, LinkedHashMap::new))
				.entrySet().stream()
				.map(e -> e.getKey() + " 01–" + String.format(Locale.ROOT, "%02d", e.getValue())).toList());
		sb.append('\n').append('_').append(t(lang, "Kỳ so sánh (cùng số ngày ở 2 tháng): ", "Periods (same days in both months): "))
				.append(periods).append(t(lang, ". Actual trên chart; RM ghi nhận chậm 1 ngày.", ". Actual from the chart; RM lags 1 day."))
				.append('_');

		return new Answer(sb.toString().trim(), List.of(
				t(lang, "Vật tư nào tăng so với tháng trước?", "Which items increased vs last month?")));
	}

	private static String pctChange(double diff, double base) {
		return base > 0 ? " (" + CmText.signedPct1(diff / base * 100) + ")" : "";
	}


	// =========================================================
	// CHUNG
	// =========================================================

	// Bộ phận / loại chi phí NÊU trong câu (không bám drawer); mặc định 3 bộ phận × T&SL, RM
	static List<CostMonitoringFocus> scope(String question) {

		String q = CostMonitoringQuestion.normalize(question);
		List<String> divs = CostMonitoringQuestion.divs(q);
		List<String> costs = CostMonitoringQuestion.costs(q).stream().filter(COSTS::contains).toList();

		if (divs.isEmpty()) {
			divs = CostMonitoringSnapshot.DIVS;
		}
		if (costs.isEmpty()) {
			costs = COSTS;
		}

		List<CostMonitoringFocus> frames = new ArrayList<>();
		for (String cost : costs) {
			for (String div : divs) {
				frames.add(new CostMonitoringFocus(div, cost));
			}
		}
		return frames;
	}

	// Khung có Actual và Fluct (OT bị loại vì không có Actual)
	private static List<Row> rows(CostMonitoringSnapshot s, List<CostMonitoringFocus> frames) {

		List<Row> rows = new ArrayList<>();

		for (CostMonitoringFocus fr : frames) {
			CostChartDto chart = s.find(fr.div(), fr.cost());
			if (chart == null) {
				continue;
			}
			CostMonitoringAssessment a = CostMonitoringAssessment.of(s, chart);
			if (a.act() == null || a.fluct() == null) {
				continue;
			}
			rows.add(new Row(fr.div() + " " + fr.cost(), fr.div(), fr.cost(), chart, a));
		}

		return rows;
	}

	private static boolean isFlagged(Row r) {
		return r.a().alreadyOver() || r.a().estimateWillExceed();
	}

	// Đã vượt / dự báo vượt Fluct, sắp theo K$ dự báo vượt giảm dần
	private static List<Row> flagged(List<Row> rows) {
		return rows.stream().filter(CostMonitoringManagerAnswers::isFlagged)
				.sorted(Comparator.comparing((Row r) -> r.a().estimateOver() != null ? r.a().estimateOver() : r.a().monthResultGap(),
						Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();
	}

	private static Row earliestCross(List<Row> rows) {

		Row best = null;
		LocalDate bestDay = null;

		for (Row r : rows) {
			if (r.a().alreadyOver()) {
				continue;
			}
			LocalDate day = CostMonitoringSnapshot.estimateCrossDay(r.chart());
			if (day != null && (bestDay == null || day.isBefore(bestDay))) {
				best = r;
				bestDay = day;
			}
		}

		return best;
	}

	// "5/6 khung T&SL / RM dự báo vượt Fluct cuối tháng (tổng phần vượt +50.8 K$); chưa khung nào vượt thực tế."
	private static String conclusionSentence(List<Row> rows, List<Row> flagged, String lang) {

		long already = rows.stream().filter(r -> r.a().alreadyOver()).count();
		String costs = costsLabel(rows);

		if (flagged.isEmpty()) {
			return t(lang, "Không khung " + costs + " nào dự báo vượt Fluct cuối tháng; chưa khung nào vượt thực tế.",
					"No " + costs + " frame is forecast above Fluct at month-end; none is above Fluct yet.");
		}

		double total = sumR1(flagged.stream().filter(r -> CostMonitoringAnswerGuide.positive(r.a().estimateOver())).toList(),
				r -> r.a().estimateOver());

		return t(lang, "**" + flagged.size() + "/" + rows.size() + "** khung " + costs + " dự báo vượt Fluct cuối tháng (tổng phần vượt "
						+ CmText.signedK(total) + ")",
				"**" + flagged.size() + "/" + rows.size() + "** " + costs + " frames are forecast above Fluct at month-end (total excess "
						+ CmText.signedK(total) + ")")
				+ (already == 0
				? t(lang, "; chưa khung nào vượt thực tế.", "; none is above Fluct in actual spending yet.")
				: t(lang, "; " + already + " khung đã vượt thực tế.", "; " + already + " already above Fluct."));
	}

	private static String highestSentence(List<Row> rows, String lang) {

		Optional<Row> top = rows.stream().filter(r -> r.a().estimatePctOfFluct() != null)
				.max(Comparator.comparing(r -> r.a().estimatePctOfFluct()));

		return top.map(r -> t(lang, "Cao nhất là **", "The highest is **") + r.name() + "** ("
						+ CmText.pct1(r.a().estimatePctOfFluct()) + t(lang, " Fluct, còn dư ", " of Fluct, ")
						+ CmText.k1(CmText.subR1(r.a().fluct(), r.a().estimateEom())) + t(lang, ").", " left)."))
				.orElse(t(lang, "Chưa đủ dữ liệu dự báo.", "Not enough forecast data."));
	}

	private static String withinSentence(List<Row> within, String lang) {

		String ot = t(lang, "OT chưa có Actual nên chưa đánh giá.", "OT has no Actual, so it is not assessed.");

		if (within.isEmpty()) {
			return t(lang, "Không khung nào còn trong Fluct theo dự báo; ", "No frame stays within Fluct by forecast; ") + ot;
		}

		return t(lang, "Trong Fluct: ", "Within Fluct: ")
				+ String.join("; ", within.stream().map(r -> withinText(r, lang)).toList()) + ". " + ot;
	}

	// "PRESS RM (99.8% Fluct, còn dư 0.8 K$; vượt FC +8.1 K$)"
	private static String withinText(Row r, String lang) {

		CostMonitoringAssessment a = r.a();

		if (a.estimateEom() == null) {
			return r.name() + t(lang, " (chưa có dự báo)", " (no forecast)");
		}

		return r.name() + " (" + CmText.pct1(a.estimatePctOfFluct()) + t(lang, " Fluct, còn dư ", " of Fluct, ")
				+ CmText.k1(CmText.subR1(a.fluct(), a.estimateEom())) + t(lang, "", " left")
				+ (CostMonitoringAnswerGuide.positive(a.estimateOverFc())
				? t(lang, "; vượt FC ", "; above FC ") + CmText.signedK(a.estimateOverFc()) : "")
				+ ")";
	}

	// "dự báo 193.3 K$ so với Fluct 178.3 K$ (+15.0 K$, 108.4% Fluct)"
	private static String forecastVsFluct(Row r, String lang) {
		CostMonitoringAssessment a = r.a();
		return t(lang, "dự báo ", "forecast ") + CmText.k1(a.estimateEom()) + t(lang, " so với Fluct ", " vs Fluct ")
				+ CmText.k1(a.fluct()) + " (" + CmText.signedK(a.estimateOver()) + ", " + CmText.pct1(a.estimatePctOfFluct())
				+ t(lang, " Fluct)", " of Fluct)");
	}

	// ", dự kiến vượt từ 28/11 (Thứ Bảy)"; không có -> ""
	private static String crossSuffix(Row r, String lang) {

		if (r.a().alreadyOver()) {
			return "";
		}

		LocalDate day = CostMonitoringSnapshot.estimateCrossDay(r.chart());

		return day == null ? "" : t(lang, ", dự kiến vượt từ ", ", expected to exceed from ") + dayLabel(day, lang);
	}

	// "giữ PRESS T&SL ≤ 4.99 K$/ngày (đang chi 7.85 K$/ngày, cần giảm 36%)"; không có số -> null
	private static String watchLine(Row r, String lang) {

		if (r == null) {
			return null;
		}

		CostMonitoringAssessment a = r.a();
		Double perDay = a.remainingPerDay();
		Double rate = a.currentRate();

		if (perDay == null || rate == null || rate <= 0) {
			return null;
		}

		if (perDay <= 0) {
			return t(lang, r.name() + " đã hết ngân sách Fluct.", r.name() + " has no Fluct budget left.");
		}

		return t(lang, "giữ " + r.name() + " ≤ ", "keep " + r.name() + " ≤ ") + CmText.rate(perDay, lang)
				+ t(lang, " (đang chi ", " (currently ") + CmText.rate(rate, lang)
				+ (rate > perDay
				? t(lang, ", cần giảm ", ", cut ") + CmText.pct0((1 - perDay / rate) * 100) + ")."
				: t(lang, ", trong ngân sách).", ", within budget)."));
	}

	private static Map<String, List<Row>> byDiv(List<Row> rows) {
		Map<String, List<Row>> map = new LinkedHashMap<>();
		for (String div : CostMonitoringSnapshot.DIVS) {
			List<Row> list = rows.stream().filter(r -> div.equals(r.div())).toList();
			if (!list.isEmpty()) {
				map.put(div, list);
			}
		}
		return map;
	}

	private static String costsLabel(List<Row> rows) {
		return String.join(" / ", COSTS.stream().filter(c -> rows.stream().anyMatch(r -> c.equals(r.cost()))).toList());
	}

	// Cộng các số đã làm tròn 1 chữ số, làm tròn lại 1 chữ số
	private static double sumR1(List<Row> rows, Function<Row, Double> value) {
		double sum = 0;
		for (Row r : rows) {
			Double v = CmText.r1(value.apply(r));
			if (v != null) {
				sum += v;
			}
		}
		Double total = CmText.r1(sum);
		return total == null ? 0 : total;
	}

	static int monthsBack(String f) {

		Matcher m = MONTHS_BACK.matcher(f);

		if (m.find()) {
			String n = m.group(1) != null ? m.group(1) : m.group(2);
			int value = switch (n) {
				case "hai", "two" -> 2;
				case "ba", "three" -> 3;
				case "bon", "four" -> 4;
				case "nam", "five" -> 5;
				case "sau", "six" -> 6;
				default -> Integer.parseInt(n);
			};
			return value < 2 ? 0 : Math.min(value, MAX_MONTHS_BACK);
		}

		return MONTHS_RECENT.matcher(f).find() ? 3 : 0;
	}

	// "_Số liệu tháng 2026-11 (Actual T&SL đến 10/11, RM đến 09/11); dự báo = Estimate cuối tháng theo FC (chưa có PL).
	//  Actual là số đã chi, dự báo là ước tính._"
	private static String dataFooter(CostMonitoringSnapshot s, List<Row> rows, String lang) {

		int tsl = 0;
		int rm = 0;

		for (Row r : rows) {
			int day = s.lastActualDay(r.chart());
			if ("T&SL".equals(r.cost())) {
				tsl = Math.max(tsl, day);
			} else if ("RM".equals(r.cost())) {
				rm = Math.max(rm, day);
			}
		}

		List<String> parts = new ArrayList<>();
		if (tsl > 0) {
			parts.add("T&SL " + t(lang, "đến ", "up to ") + CostMonitoringSnapshot.ddmm(s.month().atDay(tsl)));
		}
		if (rm > 0) {
			parts.add("RM " + t(lang, "đến ", "up to ") + CostMonitoringSnapshot.ddmm(s.month().atDay(rm)));
		}

		boolean anyPl = rows.stream().anyMatch(r -> "PL".equals(r.a().estimateBase()));
		boolean anyFc = rows.stream().anyMatch(r -> "FC".equals(r.a().estimateBase()));
		String base = anyPl && anyFc ? t(lang, "theo PL, khung chưa có PL theo FC", "based on PL, FC where no PL")
				: anyFc ? t(lang, "theo FC (chưa có PL)", "based on FC (no PL yet)")
				: anyPl ? t(lang, "theo PL", "based on PL")
				: t(lang, "theo nhịp Fluct", "based on the Fluct pace");

		return "_" + t(lang, "Số liệu tháng ", "Data for month ") + s.month()
				+ (parts.isEmpty() ? "" : " (Actual " + String.join(", ", parts) + ")")
				+ t(lang, "; dự báo = Estimate cuối tháng ", "; forecast = month-end Estimate ") + base
				+ t(lang, ". Actual là số đã chi, dự báo là ước tính._", ". Actual is spent, forecast is an estimate._");
	}

	private static String shortFooter(CostMonitoringSnapshot s, String lang) {
		return "_" + t(lang, "Số liệu tháng ", "Data for month ") + s.month()
				+ t(lang, ", actual đến ngày ", ", actual up to day ") + s.d() + "._";
	}

	private static String dayLabel(LocalDate date, String lang) {
		return CostMonitoringSnapshot.ddmm(date) + " (" + DailySeriesTool.weekday(date.getDayOfWeek(), lang) + ")";
	}

}
