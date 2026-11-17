package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.month.QuestionMonthDetector.MonthRange;
import com.example.backlogbe.assistant.core.screen.AnswerGuide;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostItemStatDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostLineDto;
import com.example.backlogbe.assistant.screens.costmonitoring.repository.CostTopItemsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.backlogbe.assistant.screens.costmonitoring.CmText.t;

// =========================================================
// CÂU HỎI CHI TIẾT VẬT TƯ (template, không gọi LLM) – dữ liệu từng dòng xuất kho CostTool / CostRm
//
//   item_most_issued : "tool / vật tư nào được mua (xuất, dùng) nhiều nhất" -> xếp theo SỐ LẦN xuất (dòng giao dịch),
//                      kèm số ngày, số lượng + đơn vị, K$, đơn giá TB. (Theo thành tiền: top_detail của lookup.)
//   item_when        : "<vật tư> mua nhiều nhất vào lúc nào" / hỏi 1 mã vật tư -> ngày xuất nhiều nhất, thứ trong tuần
//                      hay xuất, lần gần nhất, các tháng trước, nhịp cả tháng.
//   item_price       : "giá thay đổi thế nào" -> có vật tư cụ thể: đơn giá theo tháng (3 tháng trước + tháng này),
//                      dao động trong tháng; không có: các vật tư đổi giá ≥ PRICE_CHANGE_PCT so với TB 3 tháng trước.
//   item_anomaly     : "vật tư nào bất thường" -> tăng giá đột ngột, xuất tăng vọt so với nhịp 3 tháng, mã mới,
//                      dòng xuất lớn đơn lẻ; xếp theo K$ ảnh hưởng.
//
// Đơn giá = thành tiền / số lượng của dòng xuất (USD / đơn vị, chỉ dòng Qty > 0, Amount > 0) = giá bình quân kho
// (đầu kỳ / cuối kỳ), không phải giá mua (nghiệp vụ xác nhận 2026-11-11). Dữ liệu là xuất kho / sử dụng theo UsedDate.
// item_price 1 mã: "tháng N là X, tăng / giảm y% so với tháng N-1 (…), tăng / giảm liên tục k tháng" + chuỗi giá 1 dòng.
// Chỉ cost center trực tiếp của bộ phận (như get_top_items). Kỳ: đầu tháng -> mốc actual (T&SL hôm qua, RM hôm kia).
// =========================================================
@Slf4j
@Component
@RequiredArgsConstructor
public class CostMonitoringItemAnswers {

	static final String MOST_ISSUED = "item_most_issued";
	static final String ITEM_WHEN = "item_when";
	static final String ITEM_PRICE = "item_price";
	static final String ITEM_ANOMALY = "item_anomaly";
	static final String CUT_CANDIDATES = "item_cut_candidates";

	// Ứng viên cắt giảm: tỷ trọng tối thiểu (% chi trực tiếp của khung), tăng tối thiểu so với cùng kỳ (K$)
	static final double CUT_MIN_SHARE_PCT = 2.0;
	static final double CUT_MIN_INCREASE_K = 0.1;

	static final int BASELINE_MONTHS = 3;
	static final double PRICE_STABLE_PCT = 3.0;
	static final double PRICE_CHANGE_PCT = 10.0;
	static final double PRICE_JUMP_PCT = 20.0;
	static final double MIN_IMPACT_K = 0.05;
	static final double JUMP_MIN_IMPACT_K = 0.1;
	static final double NEW_ITEM_MIN_K = 0.5;
	static final double SURGE_RATIO = 2.0;
	static final double SURGE_MIN_K = 0.3;
	static final double BIG_LINE_K = 1.0;
	static final double PRICE_SPREAD_RATIO = 1.2;

	private static final int MAX_FLAGS = 8;

	// ---------- Nhận diện (câu đã fold: không dấu, chữ thường) ----------
	private static final Pattern ITEM_WORD = CostMonitoringQuestion.word(
			"tool|tools|vat tu|vat lieu|item|items|dung cu|dao cu|phu tung|ma vat tu|ma hang|material|materials"
					+ "|consumable|consumables|part|parts|sku");
	private static final Pattern TOOL_WORD = CostMonitoringQuestion.word("tool|tools|dung cu|dao cu|cong cu");
	private static final Pattern ISSUE_MOST =CostMonitoringQuestion.word(
			"mua nhieu nhat|mua nhieu|duoc mua|mua nhieu lan|xuat nhieu nhat|xuat nhieu|dung nhieu nhat|dung nhieu"
					+ "|su dung nhieu|lay nhieu|nhieu lan nhat|thuong xuyen nhat|thuong xuyen|so luong nhieu nhat"
					+ "|so luong lon nhat|most purchased|most bought|bought the most|bought most|most used|used the most"
					+ "|most often|most frequently|highest quantity|largest quantity");
	private static final Pattern MOST = CostMonitoringQuestion.word("nhieu nhat|most|top");
	private static final Pattern WHEN_WORD = CostMonitoringQuestion.word(
			"luc nao|khi nao|ngay nao|hom nao|thoi diem nao|thoi gian nao|bao gio|vao ngay|tuan nao|when|which day|what day");
	private static final Pattern BUY = CostMonitoringQuestion.word("mua|purchase|purchased|bought|buy|lay|xuat|dung");
	private static final Pattern EXCEED =CostMonitoringQuestion.word("vuot|exceed|exceeds|over|cap nhat|update|updated");
	private static final Pattern PRICE_WORD = CostMonitoringQuestion.word(
			"don gia|thay doi gia|tang gia|giam gia|bien dong gia|gia ca|muc gia|gia mua|gia nhap|gia xuat|gia vat tu"
					+ "|price|prices|unit price|pricing|cost per unit");
	// "giá" có dấu (không phải "giá trị", "đánh giá")
	private static final Pattern RAW_PRICE = Pattern.compile("(?<!đánh\\s)(?<![\\p{L}\\p{N}_])giá(?!\\s*trị)(?![\\p{L}\\p{N}_])");
	private static final Pattern ANOMALY = CostMonitoringQuestion.word(
			"bat thuong|dot bien|co gi la|dang ngo|nghi ngo|kho hieu|bat hop ly|abnormal|unusual|anomaly|anomalies"
					+ "|suspicious|strange|outlier|outliers");
	private static final Pattern DETAIL_WORD = CostMonitoringQuestion.word(
			"chi tiet|giao dich|dong xuat|xuat kho|detail|details|transaction|transactions");
	private static final Pattern PROFILE_ASK = CostMonitoringQuestion.word(
			"chi tiet|thong tin|the nao|ra sao|bao nhieu|la gi|tinh hinh|detail|info|how much|how many|xuat|dung|mua");
	// "Nên cắt giảm vật tư / Category nào trước" (test 0758 ext-104: LLM 26 s, "Nên cắt giảm … trước", không số / cùng kỳ)
	private static final Pattern CUT = CostMonitoringQuestion.word(
			"cat giam|cat bot|nen giam|can giam|giam bot|tiet kiem|cut|cut back|reduce|trim");
	private static final Pattern CATEGORY_WORD = CostMonitoringQuestion.word("category|categories|loai vat tu|nhom vat tu");
	// Mã vật tư: T0026400, M0012637-RX, MG001780, T9321247
	private static final Pattern CODE = Pattern.compile("(?<![A-Z0-9])((?:T|M|MG)\\d{5,8}(?:-[A-Z0-9]{1,4})?)(?![A-Z0-9])");

	private final CostMonitoringSnapshotLoader loader;
	private final CostTopItemsRepository repository;

	record Period(YearMonth month, LocalDate from, LocalDate to, LocalDate baseFrom, LocalDate baseTo, double fraction) {

		boolean empty() {
			return to.isBefore(from);
		}

		String range() {
			return CostMonitoringSnapshot.ddmm(from) + "–" + CostMonitoringSnapshot.ddmm(to);
		}
	}

	record Found(CostMonitoringFocus frame, CostItemStatDto stat, String how) {

		String frameName() {
			return frame.div() + " " + frame.cost();
		}
	}

	// type: price | new | surge | big (dùng cho phần nhận định)
	record Flag(String frame, String item, String text, double impactK, String type) {
	}

	/**
	 * @return AnswerGuide có directAnswer hoặc null (không phải câu chi tiết vật tư / không đủ dữ liệu -> luồng cũ)
	 */
	public AnswerGuide guide(String question, Map<String, String> params, String lang) {
		return guide(question, params, null, lang);
	}

	/**
	 * Câu hỏi về khoảng nhiều tháng (range != null, ChatOrchestrator -> answerGuideForRange):
	 *   item_most_issued      : tính trên CẢ khoảng (test 2026-11-11: "tool nào xuất nhiều nhất trong 3 tháng" trả 01/11–10/11)
	 *   item_when / item_price: khoảng ≤ BASELINE_MONTHS + 1 tháng -> tháng cuối khoảng + các tháng trước (đã có trong
	 *                           câu trả lời); dài hơn -> null (LLM)
	 *   item_anomaly          : tính theo 1 tháng (so với nhịp 3 tháng trước) -> tháng đang xem nếu nằm trong khoảng, không
	 *                           thì tháng cuối khoảng; dòng đầu nói rõ cách tính + cách hỏi tháng khác (test 0758 ext-251:
	 *                           trước trả null -> LLM 41–49 s, nói sai "không có dữ liệu bất thường")
	 */
	public AnswerGuide guide(String question, Map<String, String> params, MonthRange range, String lang) {

		if (question == null || question.isBlank()) {
			return null;
		}

		String f = CostMonitoringIntentClassifier.fold(question);
		String raw = CostMonitoringQuestion.normalize(question);
		String kind;

		try {
			kind = kind(question, f, raw);
		} catch (RuntimeException e) {
			log.warn("Assistant item kind failed error={}", e.getClass().getSimpleName());
			return null;
		}

		if (kind == null) {
			return null;
		}

		if (range != null && (CUT_CANDIDATES.equals(kind)
				|| (!MOST_ISSUED.equals(kind) && !ITEM_ANOMALY.equals(kind) && range.months() > BASELINE_MONTHS + 1))) {
			return null;
		}

		Map<String, String> safe = params != null ? params : Map.of();

		// Tháng hỏi trong câu ("tháng trước", "tháng 9", "T10/2026"…) khác tháng đang xem -> tính theo tháng đó
		// (trước: luôn lấy tháng đang xem trên màn hình, "Tháng trước tool nào mua nhiều nhất" trả số tháng này).
		// Khoảng: mốc = tháng đang xem nếu nằm trong khoảng, không thì tháng cuối khoảng ("3 tháng trước" không phải "tháng trước")
		YearMonth viewed = loader.month(safe);
		YearMonth asked = range != null ? (range.contains(viewed) ? null : range.to()) : askedMonth(question, f, viewed);
		if (asked != null && asked.isAfter(YearMonth.from(loader.today()))) {
			return null;
		}
		if (asked != null && !asked.equals(viewed)) {
			Map<String, String> copy = new LinkedHashMap<>(safe);
			copy.put("month", asked.toString());
			safe = copy;
		}

		List<CostMonitoringFocus> frames = CostMonitoringLookupAnswers.frames(question, safe);

		// "tool / dụng cụ / dao cụ" = T&SL (test 1622 ext-215 / 223: lấy cả thép RM, đơn giá 0.00 $/MM)
		if (TOOL_WORD.matcher(f).find() && CostMonitoringQuestion.costs(raw).isEmpty() && CostMonitoringFocus.from(safe) == null) {
			frames = frames.stream().filter(fr -> "T&SL".equals(fr.cost())).toList();
		}
		if (frames.isEmpty()) {
			return null;
		}

		String text;
		try {
			text = answer(kind, question, f, frames, safe, MOST_ISSUED.equals(kind) ? range : null, lang);
		} catch (RuntimeException e) {
			log.warn("Assistant item answer failed kind={} error={}", kind, e.getClass().getSimpleName());
			return null;
		}

		if (text == null || text.isBlank()) {
			return null;
		}

		boolean otherMonth = asked != null && !asked.equals(viewed);
		if (range != null && ITEM_ANOMALY.equals(kind)) {
			YearMonth detail = asked != null ? asked : viewed;
			text = "**" + t(lang, "Khoảng ", "Range ") + range.label() + "**"
					+ t(lang, " – vật tư bất thường được tính theo từng tháng (so với nhịp " + BASELINE_MONTHS + " tháng trước đó); dưới đây là tháng "
									+ detail + ". Hỏi \"Tháng MM vật tư nào bất thường?\" để xem tháng khác trong khoảng:",
							" – item anomalies are computed per month (vs the previous " + BASELINE_MONTHS + "-month pace); below is "
									+ detail + ". Ask \"Which items were unusual in month MM?\" for another month:") + "\n\n" + text;
		} else if (range != null && !MOST_ISSUED.equals(kind)) {
			// item_when / item_price theo khoảng: nói rõ tháng chi tiết + các tháng trước nằm trong câu trả lời
			YearMonth detail = asked != null ? asked : viewed;
			text = "**" + t(lang, "Khoảng ", "Range ") + range.label() + "**"
					+ t(lang, " – chi tiết tháng " + detail + ", kèm các tháng trước:",
					" – details for " + detail + ", with previous months:") + "\n\n" + text;
		} else if (otherMonth && range == null) {
			// Có khoảng (mostIssued): dòng đầu đã ghi rõ các tháng
			text = "**" + t(lang, "Tháng ", "Month ") + asked + "**"
					+ (asked.isBefore(YearMonth.from(loader.today())) ? t(lang, " (đã kết thúc)", " (closed)") : "")
					+ t(lang, " – theo tháng trong câu hỏi:", " – month from the question:") + "\n\n" + text;
		}

		return new AnswerGuide(null, false, kind, text, suggestions(kind, lang),
				"item=" + kind + (otherMonth ? "; month=" + asked : "")
						+ (range != null ? "; range=" + range.from() + ".." + range.to() : "") + "; template=yes");
	}

	// "So với tháng trước" là so sánh (giữ tháng đang xem – đơn giá / các tháng trước đã có trong câu trả lời),
	// không phải hỏi về tháng trước
	private static final Pattern PREV_MONTH = CostMonitoringQuestion.word("thang truoc|last month|previous month");
	private static final Pattern COMPARE_PREV = CostMonitoringQuestion.word(
			"so voi thang truoc|so thang truoc|hon thang truoc|voi thang truoc|cung ky|compared with last month"
					+ "|compared to last month|vs last month|than last month");

	// Tháng nêu trong câu hỏi; null = không nêu (dùng tháng đang xem)
	static YearMonth askedMonth(String question, String f, YearMonth viewed) {

		if (COMPARE_PREV.matcher(f).find()) {
			return null;
		}
		if (PREV_MONTH.matcher(f).find()) {
			return viewed.minusMonths(1);
		}

		List<YearMonth> months = com.example.backlogbe.assistant.core.month.QuestionMonthDetector.detect(question, viewed.getYear());
		return months.isEmpty() ? null : months.get(0);
	}

	// Câu hỏi có nêu vật tư (từ vật tư / tool / mã vật tư)? Dùng khi ghép "bất thường" chung với tổng quan khung
	static boolean mentionsItem(String question) {
		return question != null && (ITEM_WORD.matcher(CostMonitoringIntentClassifier.fold(question)).find()
				|| CODE.matcher(question.toUpperCase(Locale.ROOT)).find());
	}

	static String kind(String question, String f, String raw) {

		boolean price = PRICE_WORD.matcher(f).find() || RAW_PRICE.matcher(raw).find();
		boolean item = ITEM_WORD.matcher(f).find();
		boolean code = CODE.matcher(question.toUpperCase(Locale.ROOT)).find();

		if (CUT.matcher(f).find() && (item || CATEGORY_WORD.matcher(f).find()) && !price) {
			return CUT_CANDIDATES;
		}
		if (price) {
			return ITEM_PRICE;
		}
		// "khi nào / ngày nào" chỉ khi câu nói tới vật tư / mã / việc mua – xuất (tránh bắt câu theo ngày của cả khung)
		if (WHEN_WORD.matcher(f).find() && !EXCEED.matcher(f).find()
				&& (item || code || BUY.matcher(f).find())) {
			return ITEM_WHEN;
		}
		if (ISSUE_MOST.matcher(f).find() && item) {
			return MOST_ISSUED;
		}
		if (ANOMALY.matcher(f).find() && (item || DETAIL_WORD.matcher(f).find())) {
			return ITEM_ANOMALY;
		}
		if (code && PROFILE_ASK.matcher(f).find()) {
			return ITEM_WHEN;
		}
		return null;
	}

	private String answer(String kind, String question, String f, List<CostMonitoringFocus> frames,
						  Map<String, String> params, MonthRange span, String lang) {

		boolean allowTop = ISSUE_MOST.matcher(f).find() || (MOST.matcher(f).find() && ITEM_WORD.matcher(f).find());

		return switch (kind) {
			case MOST_ISSUED -> mostIssued(frames, params, span, lang);
			case ITEM_WHEN -> {
				Found found = resolve(question, frames, params, allowTop);
				yield found == null ? null : itemWhen(found, params, lang);
			}
			case ITEM_PRICE -> {
				Found found = resolve(question, frames, params, allowTop);
				yield found != null ? itemPrice(found, params, lang) : priceChanges(frames, params, lang);
			}
			case ITEM_ANOMALY -> anomalies(frames, params, lang);
			case CUT_CANDIDATES -> cutCandidates(frames, params, CATEGORY_WORD.matcher(f).find(), lang);
			default -> null;
		};
	}


	// =========================================================
	// ỨNG VIÊN CẮT GIẢM (tham khảo, không quyết định thay người dùng)
	//
	// Mục vừa chiếm tỷ trọng ≥ CUT_MIN_SHARE_PCT chi trực tiếp của khung vừa tăng ≥ CUT_MIN_INCREASE_K so với cùng kỳ
	// tháng trước (cùng số ngày), xếp theo K$ tháng này. Không có mục nào tăng -> mục lớn nhất, ghi rõ "không tăng".
	// =========================================================

	String cutCandidates(List<CostMonitoringFocus> frames, Map<String, String> params, boolean byCategory, String lang) {

		int limit = frames.size() == 1 ? 5 : 3;

		if (frames.size() > 1) {
			try {
				frames.parallelStream().forEach(fr -> {
					Period p = period(params, fr.cost());
					if (!p.empty()) {
						amounts(fr, p.from(), p.to(), byCategory, lang);
						amounts(fr, p.from().minusMonths(1), p.to().minusMonths(1), byCategory, lang);
					}
				});
			} catch (RuntimeException e) {
				log.warn("Assistant cut-candidates warm-up failed error={}", e.getClass().getSimpleName());
			}
		}

		record Row(String label, double now, double before, double diff, double share) {
		}

		StringBuilder body = new StringBuilder();
		Period shown = null;

		for (CostMonitoringFocus frame : frames) {

			Period p = period(params, frame.cost());
			if (p.empty()) {
				continue;
			}
			shown = shown == null ? p : shown;
			Map<String, Object[]> now = amounts(frame, p.from(), p.to(), byCategory, lang);
			Map<String, Object[]> before = amounts(frame, p.from().minusMonths(1), p.to().minusMonths(1), byCategory, lang);
			double total = CmText.r2(now.values().stream().mapToDouble(v -> (double) v[1]).sum());
			if (total <= 0) {
				continue;
			}

			List<Row> rows = now.entrySet().stream().map(e -> {
				double a = CmText.r2((double) e.getValue()[1]);
				Object[] b = before.get(e.getKey());
				double prev = b == null ? 0 : CmText.r2((double) b[1]);
				return new Row((String) e.getValue()[0], a, prev, CmText.r2(a - prev), CmText.r1(a / total * 100));
			}).toList();

			List<Row> picks = rows.stream()
					.filter(r -> r.share() >= CUT_MIN_SHARE_PCT && r.diff() >= CUT_MIN_INCREASE_K)
					.sorted(Comparator.comparingDouble(Row::now).reversed()).limit(limit).toList();
			boolean fallback = picks.isEmpty();
			if (fallback) {
				picks = rows.stream().sorted(Comparator.comparingDouble(Row::now).reversed()).limit(Math.min(limit, 3)).toList();
			}

			body.append("\n**").append(frame.div()).append(' ').append(frame.cost()).append("**")
					.append(t(lang, " (chi trực tiếp ", " (direct cost ")).append(k2(total)).append(")")
					.append(fallback ? t(lang, " – không mục lớn nào tăng so với cùng kỳ; các mục lớn nhất:",
							" – no large item rose vs the same period; largest items:") : ":").append('\n');
			int i = 0;
			for (Row r : picks) {
				body.append(++i).append(". **").append(r.label()).append("**: ").append(k2(r.now()))
						.append(" (").append(CmText.pct1(r.share())).append(t(lang, " chi trực tiếp", " of direct cost")).append(")")
						.append(t(lang, "; cùng kỳ ", "; same period ")).append(k2(r.before()))
						.append(" → ").append(String.format(Locale.ROOT, "%+.2f K$", r.diff())).append('\n');
			}
		}

		if (shown == null || body.isEmpty()) {
			return null;
		}

		return t(lang, "**Để tham khảo** – " + (byCategory ? "Category" : "vật tư")
								+ " nên xem xét trước (vừa chiếm tỷ trọng lớn vừa tăng so với cùng kỳ tháng trước). Quyết định cắt giảm thuộc về người quản lý.\n",
						"**For reference** – " + (byCategory ? "categories" : "items")
								+ " to review first (large share and rising vs the same period last month). The decision belongs to the manager.\n")
				+ body
				+ "\n_" + t(lang, "Kỳ " + shown.range() + " so với cùng kỳ tháng trước; tỷ trọng = % chi phí trực tiếp của khung "
						+ "(chưa gồm phân bổ F2/HT2/HT-G); chọn mục ≥ " + CUT_MIN_SHARE_PCT + "% và tăng ≥ " + CUT_MIN_INCREASE_K + " K$.",
				"Period " + shown.range() + " vs the same period last month; share = % of the frame's direct cost "
						+ "(excl. F2/HT2/HT-G allocation); items ≥ " + CUT_MIN_SHARE_PCT + "% and up ≥ " + CUT_MIN_INCREASE_K + " K$.") + "_";
	}

	// key -> {nhãn, K$}: vật tư (Code; nhãn "Tên (Code)") hoặc Category
	private Map<String, Object[]> amounts(CostMonitoringFocus frame, LocalDate from, LocalDate to, boolean byCategory, String lang) {

		Map<String, Object[]> map = new LinkedHashMap<>();
		if (byCategory) {
			repository.sumByCategory(frame.cost(), from, to, costCenters(frame)).forEach((label, amount) ->
					map.put(label, new Object[]{CmText.clean(label), amount != null ? amount : 0.0}));
			return map;
		}
		for (com.example.backlogbe.assistant.screens.costmonitoring.dto.CostItemAmountDto i
				: repository.sumByItem(frame.cost(), from, to, costCenters(frame))) {
			String key = i.code() != null && !i.code().isBlank() ? i.code().trim() : String.valueOf(i.name());
			Object[] v = map.computeIfAbsent(key, k -> new Object[]{CmText.itemLabel(i.code(), i.name(), lang), 0.0});
			v[1] = (double) v[1] + (i.amountK() != null ? i.amountK() : 0);
		}
		return map;
	}


	// =========================================================
	// KỲ / DỮ LIỆU
	// =========================================================

	Period period(Map<String, String> params, String cost) {

		YearMonth month = loader.month(params);
		LocalDate cutoff = loader.today().minusDays("RM".equals(cost) ? 2 : 1);
		LocalDate end = month.atEndOfMonth();
		LocalDate to = cutoff.isBefore(end) ? cutoff : end;
		double fraction = to.isBefore(month.atDay(1)) ? 0 : (double) to.getDayOfMonth() / month.lengthOfMonth();

		return new Period(month, month.atDay(1), to, month.minusMonths(BASELINE_MONTHS).atDay(1),
				month.minusMonths(1).atEndOfMonth(), fraction);
	}

	// Kỳ của khoảng nhiều tháng: đầu tháng đầu khoảng -> min(cuối tháng cuối khoảng, mốc actual: T&SL hôm qua, RM hôm kia).
	// baseFrom / baseTo giữ như period() (không dùng cho mostIssued).
	Period spanPeriod(Map<String, String> params, String cost, MonthRange range) {

		Period cur = period(params, cost);
		LocalDate cutoff = loader.today().minusDays("RM".equals(cost) ? 2 : 1);
		LocalDate end = range.to().atEndOfMonth();
		LocalDate to = cutoff.isBefore(end) ? cutoff : end;

		return new Period(range.to(), range.from().atDay(1), to, cur.baseFrom(), cur.baseTo(), 1);
	}

	// Nạp trước (song song) thống kê tháng này + tháng nền của các khung vào cache repository (5 phút).
	// Test 1622 ext-221: 6 khung × 2 truy vấn tuần tự mất 11 s lần đầu, lần sau 0.1 s. Lỗi -> bỏ qua (truy vấn lại tuần tự).
	private void warm(List<CostMonitoringFocus> frames, Map<String, String> params) {

		if (frames.size() < 2) {
			return;
		}

		try {
			frames.parallelStream().forEach(frame -> {
				Period p = period(params, frame.cost());
				stats(frame, p);
				repository.itemMonthly(frame.cost(), p.baseFrom(), p.baseTo(), costCenters(frame));
			});
		} catch (RuntimeException e) {
			log.warn("Assistant item warm-up failed error={}", e.getClass().getSimpleName());
		}
	}

	// "M0012637-RX" -> "M0012637"; không có hậu tố -> null
	static String rootCode(String code) {
		if (code == null) {
			return null;
		}
		int dash = code.indexOf('-');
		return dash > 0 ? code.substring(0, dash) : null;
	}

	private static List<String> costCenters(CostMonitoringFocus frame) {
		return CostMonitoringSnapshot.DIV_COST_CENTERS.get(frame.div());
	}

	private List<CostItemStatDto> stats(CostMonitoringFocus frame, Period p) {
		return p.empty() ? List.of() : repository.itemStats(frame.cost(), p.from(), p.to(), costCenters(frame));
	}

	// Gộp các tháng nền theo vật tư (Code + đơn vị): Σ qty / posQty / posAmt, số tháng có xuất
	private Map<String, CostItemStatDto> baseline(CostMonitoringFocus frame, Period p) {

		Map<String, CostItemStatDto> map = new LinkedHashMap<>();

		for (CostItemStatDto m : repository.itemMonthly(frame.cost(), p.baseFrom(), p.baseTo(), costCenters(frame))) {
			map.merge(m.key(), m, (a, b) -> new CostItemStatDto(null, a.code(), a.name(), a.category(), a.qtyUnit(),
					sum(a.qty(), b.qty()), sum(a.amountK(), b.amountK()), a.lines() + b.lines(), a.days() + b.days(),
					sum(a.posQty(), b.posQty()), sum(a.posAmount(), b.posAmount()),
					min(a.minPrice(), b.minPrice()), max(a.maxPrice(), b.maxPrice()),
					a.lastDate() == null ? b.lastDate() : b.lastDate() == null || a.lastDate().isAfter(b.lastDate()) ? a.lastDate() : b.lastDate()));
		}

		return map;
	}

	// Số tháng nền có dữ liệu (của cả khung)
	private int baselineMonths(CostMonitoringFocus frame, Period p) {

		Set<String> months = new LinkedHashSet<>();
		for (CostItemStatDto m : repository.itemMonthly(frame.cost(), p.baseFrom(), p.baseTo(), costCenters(frame))) {
			months.add(m.period());
		}
		return months.size();
	}

	// Vật tư được hỏi: mã trong câu > tên (phần trước "/") có trong câu > (nếu cho phép) vật tư xuất nhiều lần nhất
	Found resolve(String question, List<CostMonitoringFocus> frames, Map<String, String> params, boolean allowTop) {

		Set<String> codes = new LinkedHashSet<>();
		Matcher m = CODE.matcher(question.toUpperCase(Locale.ROOT));
		while (m.find()) {
			codes.add(m.group(1));
		}

		String fq = " " + CostMonitoringIntentClassifier.fold(question).replaceAll("\\s+", " ") + " ";
		Found byCode = null;
		Found byName = null;
		Found top = null;

		for (CostMonitoringFocus frame : frames) {
			Period p = period(params, frame.cost());
			for (CostItemStatDto s : stats(frame, p)) {
				double amount = s.amountK() != null ? s.amountK() : 0;
				if (s.code() != null && codes.contains(s.code().trim().toUpperCase(Locale.ROOT))) {
					if (byCode == null || amount > nz(byCode.stat().amountK())) {
						byCode = new Found(frame, s, "code");
					}
					continue;
				}
				String head = head(s.name());
				if (head != null && fq.contains(" " + head + " ")) {
					if (byName == null || amount > nz(byName.stat().amountK())) {
						byName = new Found(frame, s, "name");
					}
				}
				if (allowTop && (top == null || s.lines() > top.stat().lines()
						|| (s.lines() == top.stat().lines() && amount > nz(top.stat().amountK())))) {
					top = new Found(frame, s, "top");
				}
			}
		}

		return byCode != null ? byCode : byName != null ? byName : top;
	}

	// "EDM WIRE/GMWT0.3-5-N" -> "edm wire"; chỉ nhận tên ≥ 6 ký tự có khoảng trắng hoặc dấu chấm (tránh khớp nhầm)
	static String head(String name) {

		if (name == null) {
			return null;
		}

		int slash = name.indexOf('/');
		String h = CostMonitoringIntentClassifier.fold(slash > 0 ? name.substring(0, slash) : name)
				.replaceAll("\\s+", " ").trim();

		return h.length() >= 6 && (h.contains(" ") || h.contains(".")) ? h : null;
	}


	// =========================================================
	// XUẤT NHIỀU LẦN NHẤT
	// =========================================================

	// Tháng đang xem (ItemInsightTool)
	String mostIssued(List<CostMonitoringFocus> frames, Map<String, String> params, String lang) {
		return mostIssued(frames, params, null, lang);
	}

	// span != null: cả khoảng nhiều tháng (dòng đầu ghi rõ các tháng); null: tháng đang xem tới mốc actual
	String mostIssued(List<CostMonitoringFocus> frames, Map<String, String> params, MonthRange span, String lang) {

		StringBuilder sb = new StringBuilder();
		int limit = frames.size() == 1 ? 5 : 3;
		int blocks = 0;

		if (span != null) {
			Period first = spanPeriod(params, frames.get(0).cost(), span);
			boolean partial = first.to().isBefore(span.to().atEndOfMonth());
			sb.append("**").append(t(lang, "Khoảng ", "Range ")).append(span.label())
					.append(" (").append(span.months()).append(t(lang, " tháng)**", " months)**"))
					.append(partial ? t(lang, " – tháng " + span.to() + " tính đến mốc actual", " – " + span.to() + " up to the actual date") : "")
					.append(t(lang, ", theo khoảng trong câu hỏi:", ", range from the question:")).append("\n\n");
		}

		// Nạp trước (song song) thống kê các khung vào cache (test 0758: 6 khung × khoảng 3 tháng tuần tự 5.5–20 s)
		if (frames.size() > 1) {
			try {
				frames.parallelStream().forEach(fr ->
						stats(fr, span != null ? spanPeriod(params, fr.cost(), span) : period(params, fr.cost())));
			} catch (RuntimeException e) {
				log.warn("Assistant most-issued warm-up failed error={}", e.getClass().getSimpleName());
			}
		}

		for (CostMonitoringFocus frame : frames) {

			Period p = span != null ? spanPeriod(params, frame.cost(), span) : period(params, frame.cost());
			List<CostItemStatDto> list = stats(frame, p).stream()
					.filter(s -> s.lines() > 0)
					.sorted(Comparator.comparingInt(CostItemStatDto::lines).reversed()
							.thenComparing(s -> -nz(s.amountK())))
					.limit(limit)
					.toList();

			if (list.isEmpty()) {
				continue;
			}
			blocks++;

			sb.append("**").append(frame.div()).append(' ').append(frame.cost()).append("**")
					.append(t(lang, " – xuất nhiều lần nhất (", " – most frequently issued (")).append(p.range()).append("):\n");

			int i = 0;
			for (CostItemStatDto s : list) {
				sb.append(++i).append(". **").append(CmText.itemLabel(s.code(), s.name(), lang)).append("**: ")
						.append(s.lines()).append(t(lang, " lần / ", " times / ")).append(s.days()).append(t(lang, " ngày", " days"))
						.append("; ").append(t(lang, "SL ", "qty ")).append(qty(s.qty(), s.qtyUnit()))
						.append("; ").append(k2(s.amountK()));
				if (s.avgPrice() != null) {
					sb.append("; ").append(t(lang, "đơn giá TB ", "avg unit price ")).append(price(s.avgPrice(), s.qtyUnit()));
				}
				sb.append('\n');
			}
			// Nhận định: nhóm xuất nhiều lần chiếm bao nhiêu % chi phí khung; tiền tập trung ở đâu (test 1622: toàn găng tay,
			// giẻ lau – người đọc không biết nên làm gì với danh sách này)
			List<CostItemStatDto> all = stats(frame, p);
			double frameTotal = all.stream().mapToDouble(s -> Math.max(0, nz(s.amountK()))).sum();
			double listTotal = list.stream().mapToDouble(s -> Math.max(0, nz(s.amountK()))).sum();
			CostItemStatDto topAmt = all.stream().max(Comparator.comparingDouble(s -> nz(s.amountK()))).orElse(null);
			if (frameTotal > 0 && topAmt != null) {
				double listShare = listTotal / frameTotal * 100;
				sb.append(t(lang, "→ **Nhận định:** ", "→ **Assessment:** ")).append(list.size())
						.append(t(lang, " mã xuất nhiều lần nhất chiếm ", " most frequently issued codes are "))
						.append(String.format(Locale.ROOT, "%.0f%%", listShare))
						.append(t(lang, span != null ? " chi phí khung trong khoảng" : " chi phí khung",
								span != null ? " of the frame's cost in the range" : " of the frame's cost"))
						// RM là thép / phôi (không phải vật tư tiêu hao) – eval 2026-11-11 #71
						.append(listShare < 10 ? "RM".equals(frame.cost())
								? t(lang, " (xuất thường xuyên nhưng mỗi mã giá trị nhỏ so với cả khung)", " (frequent issues, each code small vs the frame)")
								: t(lang, " (vật tư tiêu hao giá trị nhỏ)", " (low-value consumables)") : "")
						.append(t(lang, "; tiền tập trung ở **", "; money is concentrated in **"))
						.append(CmText.itemLabel(topAmt.code(), topAmt.name(), lang)).append("** ").append(k2(topAmt.amountK()))
						.append(" (").append(String.format(Locale.ROOT, "%.0f%%", nz(topAmt.amountK()) / frameTotal * 100))
						.append(", ").append(topAmt.lines()).append(t(lang, " lần). ", " times). "))
						.append(listShare < 10
								? t(lang, "Muốn giảm chi nên xem nhóm giá trị lớn trước, không phải nhóm xuất nhiều lần.",
								"To cut cost, look at high-value items first, not the frequently issued ones.")
								: t(lang, "Nhóm xuất nhiều lần cũng chiếm tỷ trọng đáng kể – nên rà định mức sử dụng.",
								"Frequently issued items are also a large share – review usage norms."))
						.append('\n');
			}
			sb.append('\n');
		}

		if (blocks == 0) {
			return null;
		}

		sb.append('_').append(t(lang,
				"Tiêu chí: số lần xuất kho (số dòng giao dịch); số lượng khác đơn vị nên không xếp chung theo số lượng. "
						+ "Xếp theo thành tiền: hỏi \"Vật tư nào chi nhiều nhất\". ",
				"Criterion: number of issue lines; quantities have different units so they are not ranked together. "
						+ "By amount: ask \"Which items cost the most\". ")).append(dataNote(lang)).append('_');

		return sb.toString().trim();
	}


	// =========================================================
	// 1 VẬT TƯ: XUẤT VÀO LÚC NÀO
	// =========================================================

	String itemWhen(Found found, Map<String, String> params, String lang) {

		CostMonitoringFocus frame = found.frame();
		CostItemStatDto s = found.stat();
		Period p = period(params, frame.cost());

		List<CostItemStatDto> days = repository.itemDaily(frame.cost(), p.from(), p.to(), costCenters(frame), s.code()).stream()
				.filter(d -> same(d.qtyUnit(), s.qtyUnit()))
				.toList();

		StringBuilder sb = new StringBuilder();

		if ("top".equals(found.how())) {
			sb.append(t(lang, "Vật tư xuất nhiều lần nhất là ", "The most frequently issued item is "))
					.append("**").append(CmText.itemLabel(s.code(), s.name(), lang)).append("** (").append(found.frameName()).append(").\n\n");
		}

		sb.append("**").append(CmText.itemLabel(s.code(), s.name(), lang)).append("** – ").append(found.frameName())
				.append(", ").append(p.range()).append(": ")
				.append(t(lang, "xuất **", "issued **")).append(s.lines()).append(t(lang, " lần** trong ", " times** on "))
				.append(s.days()).append(t(lang, " ngày, tổng SL **", " days, total qty **")).append(qty(s.qty(), s.qtyUnit()))
				.append("**, **").append(k2(s.amountK())).append("**");
		if (s.lastDate() != null) {
			sb.append(t(lang, "; lần gần nhất ", "; last issued ")).append(dayLabel(s.lastDate(), lang));
		}
		sb.append(".\n");
		int insightAt = sb.length();

		// Ngày xuất nhiều nhất (theo số lượng, rồi K$)
		List<CostItemStatDto> topDays = days.stream()
				.sorted(Comparator.comparing((CostItemStatDto d) -> -nz(d.qty())).thenComparing(d -> -nz(d.amountK())))
				.limit(3)
				.toList();

		if (!topDays.isEmpty()) {
			sb.append('\n').append(t(lang, "**Ngày xuất nhiều nhất** (theo số lượng):", "**Top days** (by quantity):")).append('\n');
			for (CostItemStatDto d : topDays) {
				LocalDate date = LocalDate.parse(d.period());
				sb.append("- ").append(dayLabel(date, lang)).append(": ").append(qty(d.qty(), d.qtyUnit()))
						.append(", ").append(k2(d.amountK())).append(" (").append(d.lines()).append(t(lang, " lần)", " times)")).append('\n');
			}
		}

		// Thứ trong tuần hay xuất (≥ 40% số lượng, ≥ 3 ngày có xuất)
		if (days.size() >= 3) {
			Map<DayOfWeek, Double> byDow = new EnumMap<>(DayOfWeek.class);
			Map<DayOfWeek, Integer> dowDays = new EnumMap<>(DayOfWeek.class);
			double total = 0;
			for (CostItemStatDto d : days) {
				double q = Math.max(0, nz(d.qty()));
				DayOfWeek dow = LocalDate.parse(d.period()).getDayOfWeek();
				byDow.merge(dow, q, Double::sum);
				dowDays.merge(dow, 1, Integer::sum);
				total += q;
			}
			if (total > 0) {
				Map.Entry<DayOfWeek, Double> best = byDow.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
				// Cần ≥ 2 ngày cùng thứ (1 ngày xuất lớn không phải thói quen – test 1622 ext-219)
				if (best != null && best.getValue() / total >= 0.4 && dowDays.getOrDefault(best.getKey(), 0) >= 2) {
					sb.append(t(lang, "Thường xuất vào **", "Mostly issued on **")).append(DailySeriesTool.weekday(best.getKey(), lang))
							.append("** (").append(Math.round(best.getValue() / total * 100)).append(t(lang, "% số lượng).", "% of quantity).")).append('\n');
				}
			}
		}

		// Các tháng trước + nhịp cả tháng
		List<CostItemStatDto> months = monthlyOf(frame, p, s);
		sb.append('\n');
		if (months.isEmpty()) {
			sb.append(t(lang, BASELINE_MONTHS + " tháng trước không xuất mã này.", "Not issued in the previous " + BASELINE_MONTHS + " months.")).append('\n');
		} else {
			sb.append(t(lang, "Các tháng trước: ", "Previous months: "))
					.append(String.join("; ", months.stream().map(mo -> mo.period() + " " + qty(mo.qty(), mo.qtyUnit())
							+ " (" + k2(mo.amountK()) + ")").toList())).append('\n');
			Pace pace = pace(nz(s.qty()), p.fraction(), months);
			if (p.fraction() > 0 && p.fraction() < 1 && s.qty() != null && pace != null) {
				sb.append(t(lang, "Nếu giữ nhịp hiện tại: cả tháng khoảng ", "At the current pace: full month about "))
						.append(qty(pace.projected(), s.qtyUnit())).append(t(lang, " so với TB ", " vs average "))
						.append(qty(pace.base(), s.qtyUnit())).append(t(lang, "/tháng (", "/month ("))
						.append(signedPct(pace.pct())).append(").\n");
			}
		}

		sb.insert(insightAt, whenInsight(s, p, topDays, days.size(), months, lang));

		sb.append('\n').append('_').append(dataNote(lang)).append('_');
		return sb.toString().trim();
	}

	// "**Nhận định:** đang xuất nhanh hơn nhịp 3 tháng (+50%) – nên hỏi bộ phận sử dụng lý do; 56% số lượng xuất trong
	//  1 ngày 02/11 – nên kiểm tra lần xuất đó."
	private static String whenInsight(CostItemStatDto s, Period p, List<CostItemStatDto> topDays, int dayCount,
									  List<CostItemStatDto> months, String lang) {

		List<String> points = new ArrayList<>();
		double now = nz(s.qty());

		if (months.isEmpty()) {
			points.add(t(lang, "mã mới – " + BASELINE_MONTHS + " tháng trước không xuất, nên xác nhận có trong kế hoạch",
					"new code – not issued in the previous " + BASELINE_MONTHS + " months; confirm it is planned"));
		} else {
			Pace pace = pace(now, p.fraction(), months);
			if (pace != null && p.fraction() > 0) {
				double pct = pace.pct();
				String scope = p.fraction() < 1 ? t(lang, "đang xuất ", "issuing ") : t(lang, "cả tháng xuất ", "the month issued ");
				points.add(pct >= 30
						? scope + t(lang, "**nhanh hơn** nhịp " + BASELINE_MONTHS + " tháng (", "**faster** than the " + BASELINE_MONTHS + "-month pace (")
						+ signedPct(pct) + t(lang, ") – nên hỏi bộ phận sử dụng lý do (tăng sản lượng hay xuất dư)",
						") – ask the user department why (higher output or over-issuing)")
						: pct <= -30
						? scope + t(lang, "**chậm hơn** nhịp " + BASELINE_MONTHS + " tháng (", "**slower** than the " + BASELINE_MONTHS + "-month pace (")
						+ signedPct(pct) + ")"
						: scope + t(lang, "**đúng nhịp** bình thường (", "**at the normal pace** (") + signedPct(pct) + ")");
			}
		}

		if (!topDays.isEmpty() && dayCount >= 3 && now > 0) {
			CostItemStatDto peak = topDays.get(0);
			double share = nz(peak.qty()) / now * 100;
			if (share >= 40) {
				points.add(Math.round(share) + t(lang, "% số lượng xuất trong 1 ngày ", "% of the quantity on a single day ")
						+ CostMonitoringSnapshot.ddmm(LocalDate.parse(peak.period()))
						+ t(lang, " – nên kiểm tra lần xuất đó", " – worth checking that issue"));
			}
		}

		return points.isEmpty() ? ""
				: "\n" + t(lang, "**Nhận định:** ", "**Assessment:** ") + String.join("; ", points) + ".\n";
	}

	// Nhịp cả tháng của 1 vật tư: số dự phóng và TB/tháng làm tròn như hiển thị (≥ 10: số nguyên, < 10: 1 chữ số),
	// % tính từ 2 số đã làm tròn – nhận định và dòng "Nếu giữ nhịp" cùng 1 số (test 0758 ext-218: "-12.6%" và "-12.7%",
	// "327.27 BX"). TB = Σ các tháng nền / BASELINE_MONTHS; TB = 0 -> null.
	record Pace(double projected, double base, double pct) {
	}

	static Pace pace(double now, double fraction, List<CostItemStatDto> months) {
		double base = roundQty(months.stream().mapToDouble(mo -> nz(mo.qty())).sum() / BASELINE_MONTHS);
		double full = roundQty(fraction > 0 && fraction < 1 ? now / fraction : now);
		return base > 0 ? new Pace(full, base, (full / base - 1) * 100) : null;
	}

	static double roundQty(double value) {
		return Math.abs(value) >= 10 ? Math.round(value) : Math.round(value * 10) / 10.0;
	}

	private List<CostItemStatDto> monthlyOf(CostMonitoringFocus frame, Period p, CostItemStatDto s) {
		return repository.itemMonthly(frame.cost(), p.baseFrom(), p.baseTo(), costCenters(frame)).stream()
				.filter(mo -> s.code().equals(mo.code()) && same(mo.qtyUnit(), s.qtyUnit()))
				.sorted(Comparator.comparing(CostItemStatDto::period))
				.toList();
	}


	// =========================================================
	// ĐƠN GIÁ
	// =========================================================

	String itemPrice(Found found, Map<String, String> params, String lang) {

		CostMonitoringFocus frame = found.frame();
		CostItemStatDto s = found.stat();
		Period p = period(params, frame.cost());
		List<CostItemStatDto> months = monthlyOf(frame, p, s);

		StringBuilder sb = new StringBuilder();
		if ("top".equals(found.how())) {
			sb.append(t(lang, "Vật tư xuất nhiều lần nhất là ", "The most frequently issued item is "))
					.append("**").append(CmText.itemLabel(s.code(), s.name(), lang)).append("** (").append(found.frameName()).append(").\n\n");
		}

		// Dạng (phản hồi người dùng 2026-11-11):
		//   **Tên (Code)** (KHUNG): tháng 11 là **278.49 $/ST**, tăng +1.0% so với tháng 10 (275.72), tăng liên tục 4 tháng
		//   (+3.3% so với tháng 8).
		//   08: 269.61 (36 ST) · 09: 270.92 (32 ST) · 10: 275.72 (40 ST) · 11 đến 10/11: 278.49 (18 ST)
		String unit = s.qtyUnit();
		Double cur = s.avgPrice();
		YearMonth prevYm = p.month().minusMonths(1);
		CostItemStatDto prev = months.stream().filter(mo -> prevYm.toString().equals(mo.period())).findFirst().orElse(null);

		sb.append("**").append(CmText.itemLabel(s.code(), s.name(), lang)).append("** (").append(found.frameName()).append("): ");

		if (cur == null) {
			sb.append(t(lang, "tháng " + p.month().getMonthValue() + " chưa có dòng xuất dương nên chưa tính được đơn giá.",
					"no positive issue lines in month " + p.month().getMonthValue() + ", so no unit price."));
		} else {
			sb.append(t(lang, "tháng " + p.month().getMonthValue() + " là **", "month " + p.month().getMonthValue() + ": **"))
					.append(price(cur, unit)).append("**");

			if (prev != null && prev.avgPrice() != null) {
				sb.append(", ").append(direction((cur / prev.avgPrice() - 1) * 100, lang))
						.append(t(lang, " so với tháng " + prevYm.getMonthValue() + " (", " vs month " + prevYm.getMonthValue() + " ("))
						.append(money(prev.avgPrice())).append(')');
			} else {
				sb.append(t(lang, ", tháng " + prevYm.getMonthValue() + " không xuất mã này",
						", not issued in month " + prevYm.getMonthValue()));
			}

			// Xu hướng: số tháng liền nhau tăng (giảm) liên tục tính tới tháng này
			List<YearMonth> yms = new ArrayList<>();
			List<Double> prices = new ArrayList<>();
			for (CostItemStatDto mo : months) {
				if (mo.avgPrice() != null) {
					yms.add(YearMonth.parse(mo.period()));
					prices.add(mo.avgPrice());
				}
			}
			yms.add(p.month());
			prices.add(cur);

			int streak = 1;
			int dir = 0;
			for (int i = prices.size() - 1; i > 0; i--) {
				int d = Double.compare(prices.get(i), prices.get(i - 1));
				if (d == 0 || !yms.get(i - 1).plusMonths(1).equals(yms.get(i)) || (dir != 0 && d != dir)) {
					break;
				}
				dir = d;
				streak++;
			}

			double baseQty = months.stream().mapToDouble(mo -> nz(mo.posQty())).sum();
			double baseAmt = months.stream().mapToDouble(mo -> nz(mo.posAmount())).sum();
			Double base = baseQty > 0 ? baseAmt / baseQty : null;

			if (streak >= 3) {
				int first = prices.size() - streak;
				sb.append(dir > 0 ? t(lang, ", tăng liên tục ", ", up ") : t(lang, ", giảm liên tục ", ", down "))
						.append(streak).append(t(lang, " tháng (", " months in a row ("))
						.append(signedPct((cur / prices.get(first) - 1) * 100))
						.append(t(lang, " so với tháng ", " vs month ")).append(yms.get(first).getMonthValue()).append(')');
			} else if (base != null) {
				sb.append(t(lang, "; so với TB " + BASELINE_MONTHS + " tháng trước ", "; vs the previous " + BASELINE_MONTHS + "-month average "))
						.append(signedPct((cur / base - 1) * 100));
			}
			sb.append('.');

			// Nhận định: giá đi đều 1 chiều -> lượng xuất tháng này tốn thêm / bớt bao nhiêu so với giá đầu chuỗi; có đáng lo không
			sb.append('\n').append(t(lang, "**Nhận định:** ", "**Assessment:** "));
			double vsPrev = prev != null && prev.avgPrice() != null ? (cur / prev.avgPrice() - 1) * 100 : 0;
			if (base != null && (cur / base - 1) * 100 >= PRICE_JUMP_PCT) {
				sb.append(t(lang, "giá tăng mạnh so với " + BASELINE_MONTHS + " tháng trước – **nên kiểm tra lại giá nhập / nhà cung cấp**.",
						"sharp rise vs the previous " + BASELINE_MONTHS + " months – **review purchase price / supplier**."));
			} else if (streak >= 3) {
				int first = prices.size() - streak;
				double totalPct = (cur / prices.get(first) - 1) * 100;
				double extra = (cur - prices.get(first)) * nz(s.posQty()) / 1000.0;
				sb.append(dir > 0 ? t(lang, "giá tăng đều " + streak + " tháng; so với giá tháng " + yms.get(first).getMonthValue()
								+ ", lượng xuất tháng này tốn thêm khoảng ", "price has risen " + streak + " months in a row; vs month "
								+ yms.get(first).getMonthValue() + " prices, this month's quantity costs about ")
								: t(lang, "giá giảm đều " + streak + " tháng; so với giá tháng " + yms.get(first).getMonthValue()
								+ ", lượng xuất tháng này rẻ hơn khoảng ", "price has fallen " + streak + " months in a row; vs month "
								+ yms.get(first).getMonthValue() + " prices, this month's quantity costs about "))
						.append(signedK2(extra)).append(". ")
						.append(dir > 0 && totalPct >= 5
								? t(lang, "Xu hướng tăng rõ – nên theo dõi / đàm phán giá nhập.", "A clear upward trend – monitor / negotiate the purchase price.")
								: dir > 0 ? t(lang, "Mức tăng nhỏ, chưa đáng lo; chi phí mã này thay đổi chủ yếu do lượng xuất.",
								"A small rise, not a concern yet; this item's cost changes mainly with volume.")
								: t(lang, "Có lợi cho chi phí.", "Favourable for cost."));
			} else if (Math.abs(vsPrev) < PRICE_STABLE_PCT) {
				sb.append(t(lang, "giá ổn định – chi phí của mã này thay đổi chủ yếu do lượng xuất, không phải giá.",
						"price is stable – this item's cost changes mainly with volume, not price."));
			} else {
				sb.append(t(lang, "giá dao động, chưa thành xu hướng – theo dõi thêm 1–2 tháng.",
						"price fluctuates without a clear trend – watch for another 1–2 months."));
			}
		}
		sb.append("\n\n");

		// Chuỗi đơn giá theo tháng, 1 dòng
		List<String> parts = new ArrayList<>();
		for (CostItemStatDto mo : months) {
			parts.add(monthLabel(YearMonth.parse(mo.period()), p.month()) + ": "
					+ (mo.avgPrice() == null ? "–" : money(mo.avgPrice())) + " (" + qty(mo.posQty(), unit) + ")");
		}
		boolean partial = p.to().isBefore(p.month().atEndOfMonth());
		parts.add(monthLabel(p.month(), p.month())
				+ (partial ? t(lang, " đến ", " to ") + CostMonitoringSnapshot.ddmm(p.to()) : "") + ": "
				+ (cur == null ? "–" : money(cur)) + " (" + qty(s.posQty(), unit) + ")");
		sb.append(String.join(" · ", parts)).append('\n');

		if (s.minPrice() != null && s.maxPrice() != null && s.minPrice() > 0 && s.maxPrice() / s.minPrice() >= PRICE_SPREAD_RATIO) {
			sb.append(t(lang, "Trong tháng đơn giá dao động mạnh: thấp nhất ", "Prices vary a lot within the month: lowest "))
					.append(price(s.minPrice(), s.qtyUnit())).append(t(lang, ", cao nhất ", ", highest ")).append(price(s.maxPrice(), s.qtyUnit()))
					.append(" (").append(signedPct((s.maxPrice() / s.minPrice() - 1) * 100)).append(").\n");
		}

		sb.append('\n').append('_').append(priceNote(lang)).append('_');
		return sb.toString().trim();
	}

	// Các vật tư đổi giá ≥ PRICE_CHANGE_PCT so với TB 3 tháng trước, xếp theo K$ ảnh hưởng
	String priceChanges(List<CostMonitoringFocus> frames, Map<String, String> params, String lang) {

		warm(frames, params);

		StringBuilder sb = new StringBuilder();
		int compared = 0;
		int blocks = 0;

		for (CostMonitoringFocus frame : frames) {

			Period p = period(params, frame.cost());
			Map<String, CostItemStatDto> base = baseline(frame, p);
			List<double[]> rows = new ArrayList<>();
			List<CostItemStatDto> items = new ArrayList<>();
			double net = 0;

			for (CostItemStatDto s : stats(frame, p)) {
				CostItemStatDto b = base.get(s.key());
				Double cur = s.avgPrice();
				Double old = b != null ? b.avgPrice() : null;
				if (cur == null || old == null || old <= 0) {
					continue;
				}
				compared++;
				double pct = (cur / old - 1) * 100;
				double impact = (cur - old) * nz(s.posQty()) / 1000.0;
				net += impact;
				if (Math.abs(pct) >= PRICE_CHANGE_PCT && Math.abs(impact) >= MIN_IMPACT_K) {
					rows.add(new double[]{pct, impact, old, cur});
					items.add(s);
				}
			}

			List<Integer> up = new ArrayList<>();
			List<Integer> down = new ArrayList<>();
			for (int i = 0; i < rows.size(); i++) {
				(rows.get(i)[1] > 0 ? up : down).add(i);
			}
			up.sort(Comparator.comparingDouble(i -> -rows.get(i)[1]));
			down.sort(Comparator.comparingDouble(i -> rows.get(i)[1]));

			blocks++;
			sb.append("**").append(frame.div()).append(' ').append(frame.cost()).append("**")
					.append(t(lang, " – đơn giá đổi ≥ " + (int) PRICE_CHANGE_PCT + "% so với TB " + BASELINE_MONTHS + " tháng trước (",
							" – unit price changed ≥ " + (int) PRICE_CHANGE_PCT + "% vs the previous " + BASELINE_MONTHS + "-month average ("))
					.append(p.range()).append("):\n");

			if (up.isEmpty() && down.isEmpty()) {
				sb.append(t(lang, "- Không có vật tư nào đổi giá đáng kể.", "- No significant price change.")).append('\n');
			}
			appendPriceRows(sb, t(lang, "Tăng giá", "Price up"), up, rows, items, 5, lang);
			appendPriceRows(sb, t(lang, "Giảm giá", "Price down"), down, rows, items, 3, lang);
			sb.append(priceVerdict(frame, params, net, lang)).append("\n\n");
		}

		if (blocks == 0) {
			return null;
		}

		sb.append('_').append(t(lang, "Đã so " + compared + " vật tư có xuất ở cả 2 kỳ. Ảnh hưởng = (giá mới − giá TB cũ) × SL tháng này. ",
				"Compared " + compared + " items issued in both periods. Impact = (new − old average price) × this month's qty. "))
				.append(priceNote(lang)).append('_');
		return sb.toString().trim();
	}

	private static void appendPriceRows(StringBuilder sb, String label, List<Integer> idx, List<double[]> rows,
										List<CostItemStatDto> items, int limit, String lang) {

		if (idx.isEmpty()) {
			return;
		}

		sb.append(label).append(":\n");
		for (int i : idx.stream().limit(limit).toList()) {
			double[] r = rows.get(i);
			CostItemStatDto s = items.get(i);
			sb.append("- **").append(CmText.itemLabel(s.code(), s.name(), lang)).append("**: ")
					.append(price(r[2], s.qtyUnit())).append(" → ").append(price(r[3], s.qtyUnit()))
					.append(" (").append(signedPct(r[0])).append("); ").append(t(lang, "SL tháng này ", "qty this month "))
					.append(qty(s.posQty(), s.qtyUnit())).append(t(lang, " → ảnh hưởng ", " → impact "))
					.append(signedK2(r[1])).append('\n');
		}
	}


	// =========================================================
	// BẤT THƯỜNG Ở CHI TIẾT VẬT TƯ
	// =========================================================

	String anomalies(List<CostMonitoringFocus> frames, Map<String, String> params, String lang) {

		warm(frames, params);

		List<Flag> flags = new ArrayList<>();
		int maxBaseMonths = 0;
		Period shown = null;

		for (CostMonitoringFocus frame : frames) {

			Period p = period(params, frame.cost());
			if (p.empty()) {
				continue;
			}
			shown = shown == null ? p : shown;
			String name = frame.div() + " " + frame.cost();
			Map<String, CostItemStatDto> base = baseline(frame, p);
			Set<String> baseCodes = new LinkedHashSet<>();
			base.values().forEach(b -> baseCodes.add(b.code()));
			int baseMonths = baselineMonths(frame, p);
			maxBaseMonths = Math.max(maxBaseMonths, baseMonths);

			for (CostItemStatDto s : stats(frame, p)) {

				String label = CmText.itemLabel(s.code(), s.name(), lang);
				double amount = nz(s.amountK());
				CostItemStatDto b = base.get(s.key());

				// 1. Tăng giá đột ngột
				Double cur = s.avgPrice();
				Double old = b != null ? b.avgPrice() : null;
				if (cur != null && old != null && old > 0) {
					double pct = (cur / old - 1) * 100;
					double impact = (cur - old) * nz(s.posQty()) / 1000.0;
					if (pct >= PRICE_JUMP_PCT && impact >= JUMP_MIN_IMPACT_K) {
						flags.add(new Flag(name, label, t(lang, "đơn giá tăng ", "unit price up ") + signedPct(pct) + " ("
								+ price(old, s.qtyUnit()) + " → " + price(cur, s.qtyUnit()) + t(lang, "), ảnh hưởng ", "), impact ")
								+ signedK2(impact), impact, "price"));
					}
				}

				if (baseMonths == 0) {
					continue;
				}

				// 2. Mã mới (3 tháng trước không xuất). Mã biến thể "M0012637-RX" có mã gốc đã xuất -> ghi là biến thể
				if (b == null) {
					if (amount >= NEW_ITEM_MIN_K) {
						String root = rootCode(s.code());
						boolean variant = root != null && baseCodes.contains(root);
						flags.add(new Flag(name, label, (variant
								? t(lang, "mã biến thể của " + root + " (3 tháng trước chỉ xuất mã gốc); tháng này ",
								"variant of " + root + " (only the base code was issued before); this month ")
								: t(lang, "mã mới – " + BASELINE_MONTHS + " tháng trước không xuất; tháng này ",
								"new code – not issued in the previous " + BASELINE_MONTHS + " months; this month "))
								+ k2(amount) + " (" + s.lines() + t(lang, " lần)", " times)"), variant ? amount / 2 : amount, "new"));
					}
					continue;
				}

				// 3. Xuất tăng vọt so với nhịp TB 3 tháng (quy về cùng thời điểm trong tháng).
				// Chỉ vật tư xuất đều (≥ 3 lần trong 3 tháng trước, ≥ 2 lần tháng này) – món mua lẻ 1 lần không có "nhịp"
				// (test 1622: "1 ST, gấp 9.0 lần"); phần vượt nhịp (K$) ≥ SURGE_MIN_K (trước: 89 điểm, quá nhiễu).
				double expected = nz(b.qty()) / baseMonths * p.fraction();
				double now = nz(s.qty());
				double surgeImpact = now > 0 ? amount * (1 - expected / now) : 0;
				if (expected > 0 && b.lines() >= 3 && s.lines() >= 2 && now >= SURGE_RATIO * expected
						&& surgeImpact >= SURGE_MIN_K) {
					double impact = surgeImpact;
					flags.add(new Flag(name, label, t(lang, "xuất ", "issued ") + qty(now, s.qtyUnit())
							+ t(lang, " đến nay, gấp ", " so far, ") + String.format(Locale.ROOT, "%.1f", now / expected)
							+ t(lang, " lần nhịp TB " + BASELINE_MONTHS + " tháng (≈ ", "× the " + BASELINE_MONTHS + "-month pace (≈ ")
							+ qty(expected, s.qtyUnit()) + t(lang, " tới cùng thời điểm); ", " by this point); ") + k2(amount), impact, "surge"));
				}
			}

			// 4. Dòng xuất lớn đơn lẻ / bản quyền – phần mềm
			for (CostLineDto line : repository.findUnusualLines(frame.cost(), p.from(), p.to(), costCenters(frame), BIG_LINE_K, 3)) {
				double amount = nz(line.amountK());
				flags.add(new Flag(name, CmText.itemLabel(line.code(), line.name(), lang),
						t(lang, "1 dòng xuất lớn ", "single large issue line ") + k2(amount)
								+ (line.date() != null ? t(lang, " ngày ", " on ") + dayLabel(line.date(), lang) : ""), amount, "big"));
			}
		}

		if (shown == null) {
			return null;
		}

		// Cùng vật tư + khung: gộp thành 1 dòng (test 1622: SOLIDWORK hiện 2 lần – dòng lớn + xuất tăng vọt)
		Map<String, Flag> merged = new LinkedHashMap<>();
		for (Flag fl : flags) {
			merged.merge(fl.frame() + "|" + fl.item(), fl, (a, b) -> new Flag(a.frame(), a.item(),
					a.text() + "; " + b.text(), Math.max(a.impactK(), b.impactK()),
					a.impactK() >= b.impactK() ? a.type() : b.type()));
		}
		flags = new ArrayList<>(merged.values());
		flags.sort(Comparator.comparingDouble(Flag::impactK).reversed());

		StringBuilder sb = new StringBuilder();

		// Nhận định trước danh sách (phản hồi người dùng 2026-11-11: "chưa thấy mục đích của 1 AI" – chỉ liệt kê số)
		if (!flags.isEmpty()) {
			sb.append(anomalyInsight(flags, lang)).append("\n\n");
		}

		sb.append(t(lang, "**Chi tiết** – tháng ", "**Details** – month "))
				.append(shown.month()).append(t(lang, " đến ", " to ")).append(CostMonitoringSnapshot.ddmm(shown.to()))
				.append(t(lang, ", so với " + BASELINE_MONTHS + " tháng trước", ", vs the previous " + BASELINE_MONTHS + " months"))
				.append(maxBaseMonths < BASELINE_MONTHS ? t(lang, " (chỉ có " + maxBaseMonths + " tháng dữ liệu)", " (only " + maxBaseMonths + " months of data)") : "")
				.append(":\n");

		if (flags.isEmpty()) {
			sb.append(t(lang, "Không thấy vật tư bất thường theo các tiêu chí bên dưới.", "No item anomaly found by the criteria below.")).append('\n');
		} else {
			int i = 0;
			for (Flag fl : flags.stream().limit(MAX_FLAGS).toList()) {
				sb.append(++i).append(". **").append(fl.item()).append("** (").append(fl.frame()).append("): ").append(fl.text()).append('\n');
			}
			if (flags.size() > MAX_FLAGS) {
				sb.append(t(lang, "… và " + (flags.size() - MAX_FLAGS) + " điểm nhỏ hơn.", "… and " + (flags.size() - MAX_FLAGS) + " smaller items.")).append('\n');
			}
		}

		sb.append('\n').append('_').append(t(lang,
				"Tiêu chí: đơn giá tăng ≥ " + (int) PRICE_JUMP_PCT + "% (ảnh hưởng ≥ " + JUMP_MIN_IMPACT_K + " K$); xuất ≥ " + SURGE_RATIO
						+ " lần nhịp TB " + BASELINE_MONTHS + " tháng (≥ " + SURGE_MIN_K + " K$); mã mới ≥ " + NEW_ITEM_MIN_K + " K$; 1 dòng ≥ "
						+ BIG_LINE_K + " K$ hoặc bản quyền / phần mềm. Xếp theo K$ ảnh hưởng. Đây là gợi ý để kiểm tra, chưa khẳng định có sai phạm. ",
				"Criteria: unit price up ≥ " + (int) PRICE_JUMP_PCT + "% (impact ≥ " + JUMP_MIN_IMPACT_K + " K$); issued ≥ " + SURGE_RATIO
						+ "× the " + BASELINE_MONTHS + "-month pace (≥ " + SURGE_MIN_K + " K$); new code ≥ " + NEW_ITEM_MIN_K + " K$; single line ≥ "
						+ BIG_LINE_K + " K$ or license / software. Sorted by impact. These are pointers to check, not findings of wrongdoing. "))
				.append(dataNote(lang)).append('_');

		return sb.toString().trim();
	}


	// =========================================================
	// NHẬN ĐỊNH (phần "AI": số nói lên điều gì + nên làm gì; tính bằng code)
	// =========================================================

	// "**Nhận định:** 58 vật tư đáng kiểm tra, giá trị liên quan ~32 K$. Nhiều nhất là xuất tăng vọt (40 mục, 21 K$), tập
	//  trung ở PRESS RM (30 mục).\n**Nên làm:** kiểm tra trước 3 mục đầu (tổng 6.2 K$); hỏi bộ phận sử dụng …"
	private static String anomalyInsight(List<Flag> flags, String lang) {

		double total = 0;
		Map<String, double[]> byType = new LinkedHashMap<>();
		Map<String, Integer> byFrame = new LinkedHashMap<>();
		for (Flag fl : flags) {
			total += fl.impactK();
			double[] agg = byType.computeIfAbsent(fl.type(), k -> new double[2]);
			agg[0]++;
			agg[1] += fl.impactK();
			byFrame.merge(fl.frame(), 1, Integer::sum);
		}

		Map.Entry<String, double[]> topType = byType.entrySet().stream()
				.max(Comparator.comparingDouble(e -> e.getValue()[1])).orElseThrow();
		Map.Entry<String, Integer> topFrame = byFrame.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
		int first = Math.min(3, flags.size());
		double firstSum = flags.stream().limit(first).mapToDouble(Flag::impactK).sum();

		StringBuilder sb = new StringBuilder();
		sb.append(t(lang, "**Nhận định:** ", "**Assessment:** ")).append(flags.size())
				.append(t(lang, " vật tư đáng kiểm tra, giá trị liên quan khoảng ", " items worth checking, about "))
				.append(k2(total)).append(t(lang, ". Nhiều nhất là ", " involved. Mostly ")).append(typeLabel(topType.getKey(), lang))
				.append(" (").append((int) topType.getValue()[0]).append(t(lang, " mục, ", " items, ")).append(k2(topType.getValue()[1])).append(')');
		if (byFrame.size() > 1) {
			sb.append(t(lang, ", tập trung ở ", ", concentrated in ")).append(topFrame.getKey())
					.append(" (").append(topFrame.getValue()).append(t(lang, " mục)", " items)"));
		}
		sb.append(".\n").append(t(lang, "**Nên làm:** kiểm tra trước ", "**Next step:** check the first "))
				.append(first).append(t(lang, " mục đầu (tổng ", " items first (total ")).append(k2(firstSum)).append("); ")
				.append(typeAdvice(topType.getKey(), lang)).append('.');

		return sb.toString();
	}

	// "→ Nhận định: giá đổi làm chi phí khung tăng ròng +0.45 K$, so với dự báo vượt Fluct +15.0 K$ (3%) → giá không phải
	//  nguyên nhân chính, chủ yếu do lượng xuất."  net = Σ (giá mới − giá TB cũ) × SL tháng này của mọi mã so được.
	private String priceVerdict(CostMonitoringFocus frame, Map<String, String> params, double net, String lang) {

		StringBuilder sb = new StringBuilder(t(lang, "→ **Nhận định:** giá đổi làm chi phí khung ", "→ **Assessment:** price changes "))
				.append(net >= 0 ? t(lang, "tăng ròng ", "add net ") : t(lang, "giảm ròng ", "save net "))
				.append(signedK2(net)).append(t(lang, " tháng này", " this month"));

		Double over = null;
		try {
			CostMonitoringSnapshot snapshot = loader.load(params);
			var chart = snapshot.find(frame.div(), frame.cost());
			if (chart != null) {
				CostMonitoringAssessment a = CostMonitoringAssessment.of(snapshot, chart);
				over = snapshot.monthClosed() ? a.monthResultGap() : a.estimateOver();
			}
		} catch (RuntimeException e) {
			log.warn("Assistant item price verdict snapshot failed error={}", e.getClass().getSimpleName());
		}

		if (over != null && over > 0 && net > 0) {
			double share = net / over * 100;
			sb.append(t(lang, ", so với phần vượt Fluct ", " vs the excess over Fluct ")).append(CmText.signedK(CmText.r1(over)))
					.append(" (").append(Math.round(share)).append("%) → ")
					.append(share < 20 ? t(lang, "giá **không phải nguyên nhân chính**, chủ yếu do lượng xuất.",
							"price is **not the main cause**; it is mostly volume.")
							: share < 50 ? t(lang, "giá **góp một phần**, lượng xuất vẫn là chính.",
							"price **contributes partly**; volume is still the main driver.")
							: t(lang, "giá là **yếu tố đáng kể** – nên xem lại giá nhập / nhà cung cấp.",
							"price is **a major factor** – review purchase prices / suppliers."));
		} else if (net <= 0) {
			sb.append(t(lang, " → giá không làm tăng chi phí.", " → prices do not add cost."));
		} else {
			sb.append('.');
		}

		return sb.toString();
	}

	private static String typeLabel(String type, String lang) {
		return switch (type) {
			case "price" -> t(lang, "tăng giá", "price increases");
			case "new" -> t(lang, "mã mới", "new codes");
			case "surge" -> t(lang, "xuất tăng vọt so với nhịp " + BASELINE_MONTHS + " tháng",
					"issues far above the " + BASELINE_MONTHS + "-month pace");
			default -> t(lang, "khoản xuất lớn 1 lần", "single large issues");
		};
	}

	private static String typeAdvice(String type, String lang) {
		return switch (type) {
			case "price" -> t(lang, "xem lại giá nhập / nhà cung cấp của các mã tăng giá",
					"review purchase prices / suppliers of the items whose price rose");
			case "new" -> t(lang, "xác nhận các mã mới có trong kế hoạch sử dụng", "confirm the new codes are planned");
			case "surge" -> t(lang, "hỏi bộ phận sử dụng lý do xuất tăng (tăng sản lượng, đổi kế hoạch hay xuất dư / tồn tại xưởng)",
					"ask the user department why issues jumped (higher output, plan change or over-issuing / shop-floor stock)");
			default -> t(lang, "đối chiếu chứng từ – khoản 1 lần (vd bản quyền phần mềm) thường không lặp lại hằng tháng",
					"check the documents – one-off items (e.g. software licences) usually do not recur monthly");
		};
	}


	// =========================================================
	// FORMAT
	// =========================================================

	private static List<String> suggestions(String kind, String lang) {
		return switch (kind) {
			case MOST_ISSUED -> List.of(t(lang, "Vật tư xuất nhiều nhất mua vào lúc nào?", "When is the most issued item used?"),
					t(lang, "Giá vật tư thay đổi thế nào?", "How have item prices changed?"));
			case ITEM_WHEN -> List.of(t(lang, "Giá vật tư này thay đổi thế nào?", "How has this item's price changed?"),
					t(lang, "Vật tư nào bất thường?", "Which items look unusual?"));
			case ITEM_PRICE -> List.of(t(lang, "Vật tư nào bất thường?", "Which items look unusual?"),
					t(lang, "Tool nào được mua nhiều nhất?", "Which tools are issued most often?"));
			case CUT_CANDIDATES -> List.of(t(lang, "Vật tư nào tăng so với tháng trước?", "Which items increased vs last month?"),
					t(lang, "Vật tư nào bất thường?", "Which items look unusual?"));
			default -> List.of(t(lang, "Giá vật tư thay đổi thế nào?", "How have item prices changed?"),
					t(lang, "Tool nào được mua nhiều nhất?", "Which tools are issued most often?"));
		};
	}

	private static String dataNote(String lang) {
		return t(lang,
				"Dữ liệu là dòng xuất kho / sử dụng theo ngày UsedDate (không phải ngày đặt mua – cần xác nhận); chỉ cost center trực tiếp.",
				"Data are stock issue / usage lines by UsedDate (not purchase-order dates – to be confirmed); direct cost centers only.");
	}

	// Xác nhận nghiệp vụ 2026-11-11: đơn giá xuất kho là giá bình quân kho (đầu kỳ / cuối kỳ), không phải giá mua
	private static String priceNote(String lang) {
		return t(lang,
				"Đơn giá là giá bình quân kho (đầu kỳ / cuối kỳ), không phải giá mua.",
				"Unit price is the stock average price (opening / closing), not the purchase price.");
	}

	// "tăng +1.0%" / "giảm -2.0%" / "không đổi"
	private static String direction(double pct, String lang) {
		if (Math.abs(pct) < 0.05) {
			return t(lang, "không đổi", "unchanged");
		}
		return (pct > 0 ? t(lang, "tăng ", "up ") : t(lang, "giảm ", "down ")) + signedPct(pct);
	}

	// "08" (cùng năm với tháng đang xét) / "2025-12" (khác năm)
	private static String monthLabel(YearMonth ym, YearMonth current) {
		return ym.getYear() == current.getYear() ? String.format(Locale.ROOT, "%02d", ym.getMonthValue()) : ym.toString();
	}

	private static String dayLabel(LocalDate date, String lang) {
		return CostMonitoringSnapshot.ddmm(date) + " (" + DailySeriesTool.weekday(date.getDayOfWeek(), lang) + ")";
	}

	static String k2(Double value) {
		return value == null ? "" : String.format(Locale.ROOT, "%.2f K$", value);
	}

	private static String signedK2(double value) {
		return String.format(Locale.ROOT, "%+.2f K$", value);
	}

	private static String signedPct(double value) {
		return String.format(Locale.ROOT, "%+.1f%%", value);
	}

	// Đơn giá nhỏ (vd thép RM theo MM ~0.006 $/MM) cần thêm chữ số, không thì "0.06 → 0.06 (+11.2%)" (test 1622)
	private static String money(double usd) {
		double a = Math.abs(usd);
		return a >= 1 ? String.format(Locale.ROOT, "%,.2f", usd)
				: a >= 0.01 ? String.format(Locale.ROOT, "%.4f", usd)
				: String.format(Locale.ROOT, "%.6f", usd);
	}

	// "278.33 $/ST"
	static String price(Double usd, String unit) {
		if (usd == null) {
			return "";
		}
		String u = CmText.clean(unit);
		return money(usd) + " $/" + (u.isEmpty() ? "đv" : u);
	}

	// "12.5 ST": tối đa 2 chữ số thập phân, bỏ số 0 thừa
	static String qty(Double value, String unit) {
		if (value == null || value.isNaN() || value.isInfinite()) {
			return "";
		}
		String number = java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP)
				.stripTrailingZeros().toPlainString();
		String u = CmText.clean(unit);
		return u.isEmpty() ? number : number + " " + u;
	}

	private static boolean same(String a, String b) {
		return (a == null ? "" : a.trim()).equalsIgnoreCase(b == null ? "" : b.trim());
	}

	private static double nz(Double v) {
		return v == null || v.isNaN() ? 0 : v;
	}

	private static Double sum(Double a, Double b) {
		return a == null ? b : b == null ? a : a + b;
	}

	private static Double min(Double a, Double b) {
		return a == null ? b : b == null ? a : Math.min(a, b);
	}

	private static Double max(Double a, Double b) {
		return a == null ? b : b == null ? a : Math.max(a, b);
	}
}
