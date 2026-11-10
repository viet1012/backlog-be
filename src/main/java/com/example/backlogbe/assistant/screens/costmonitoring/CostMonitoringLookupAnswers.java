package com.example.backlogbe.assistant.screens.costmonitoring;

import com.example.backlogbe.assistant.core.screen.AnswerGuide;
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

	// top_detail: xếp hạng theo máy / cost center / Category (mỗi khung xếp riêng, không cộng T&SL với RM)
	enum Dimension {
		MACHINE, COST_CENTER, CATEGORY
	}

	private static final List<String> COSTS = List.of("T&SL", "RM");

	private static final int TOP_ITEMS_ONE = 5;
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
	private static final Pattern MACHINE_VI = Pattern.compile("(?<!\\p{L})máy(?!\\p{L})");
	private static final Pattern MACHINE_FOLDED = CostMonitoringQuestion.word(
			"may nao|top may|cac may|may chi|may ton|may lon");

	private static final int TOP_DETAIL_ONE = 5;
	private static final int TOP_DETAIL_FEW = 3;
	private static final int TOP_DETAIL_FEW_FRAMES = 3;

	private static final Pattern WHICH_DAY =CostMonitoringQuestion.word("ngay nao|hom nao|which day|what day");
	private static final Pattern MOST = CostMonitoringQuestion.word(
			"nhieu nhat|cao nhat|lon nhat|ton nhat|most|highest|largest|biggest");

	private static final Pattern BUDGET = CostMonitoringQuestion.word(
			"con lai|toi da|duoc chi|ngan sach|remaining|max|maximum|budget|allowed");
	private static final Pattern PER_DAY = CostMonitoringQuestion.word(
			"moi ngay|1 ngay|mot ngay|hang ngay|per day|a day|each day|daily");

	private static final Pattern ANOMALY = CostMonitoringQuestion.word(
			"bat thuong|dot bien|abnormal|unusual|anomaly|anomalies|spike|spikes");
	private static final Pattern TODAY = CostMonitoringQuestion.word(
			"hom nay|hom qua|moi nhat|gan nhat|today|yesterday|latest");

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

		if (WHY.matcher(f).find() || EXCLUDED.matcher(f).find()) {
			return null;
		}

		String kind = kind(question, f);

		if (kind == null) {
			return null;
		}

		Map<String, String> safeParams = params != null ? params : Map.of();
		CostMonitoringSnapshot snapshot = loader.load(safeParams);
		List<CostMonitoringFocus> frames = frames(question, safeParams);

		if (frames.isEmpty() || snapshot.d() < 1) {
			return null;
		}

		boolean closed = snapshot.monthClosed();
		Dimension dimension = dimension(question, f);
		Answer answer = switch (kind) {
			case TOP_DETAIL -> topDetail(snapshot, frames, dimension, lang);
			case TOP_ITEM -> topItem(snapshot, frames, EXPENSIVE.matcher(f).find(), lang);
			case TOP_DAY -> topDay(snapshot, frames, lang);
			case REMAINING_BUDGET -> closed ? null : remainingBudget(snapshot, frames, lang);
			case ANOMALY_TODAY -> closed ? null : anomalyToday(snapshot, frames, lang);
			default -> null;
		};

		if (answer == null || answer.text() == null || answer.text().isBlank()) {
			return null;
		}

		String match = "lookup=" + kind
				+ (TOP_DETAIL.equals(kind) ? "; dim=" + dimension.name().toLowerCase(Locale.ROOT) : "")
				+ "; frames="
				+ String.join(",", frames.stream().map(fr -> fr.div() + " " + fr.cost()).toList())
				+ "; template=yes";

		// toolsSufficient = false: tắt template thì LLM vẫn tự gọi tool (không có pre-fetch tương ứng)
		return new AnswerGuide(null, false, kind, answer.text(), answer.suggestions(), match);
	}

	// Loại câu hỏi tra cứu; null = không khớp
	static String kind(String question, String f) {

		if (dimension(question, f) != null && ITEM_RANK.matcher(f).find() && !WHICH_DAY.matcher(f).find()
				&& !DailySeriesPrefetchRule.isDailyQuestion(question)) {
			return TOP_DETAIL;
		}

		if (ITEM.matcher(f).find() && ITEM_RANK.matcher(f).find() && !NOT_ITEM.matcher(f).find()
				&& !DailySeriesPrefetchRule.isDailyQuestion(question)) {
			return TOP_ITEM;
		}

		if (WHICH_DAY.matcher(f).find() && MOST.matcher(f).find() && !ITEM.matcher(f).find()
				&& !NOT_ITEM.matcher(f).find()) {
			return TOP_DAY;
		}

		if (BUDGET.matcher(f).find() && PER_DAY.matcher(f).find()) {
			return REMAINING_BUDGET;
		}

		if (ANOMALY.matcher(f).find() && TODAY.matcher(f).find()) {
			return ANOMALY_TODAY;
		}

		return null;
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

	record Block(CostMonitoringFocus frame, List<Entry> entries, Double act, LocalDate to) {
	}

	private Answer topDetail(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, Dimension dim,
							 String lang) {

		List<CostMonitoringFocus> usable = dim == Dimension.MACHINE
				? frames.stream().filter(fr -> "T&SL".equals(fr.cost())).toList()
				: frames;

		if (usable.isEmpty()) {
			return new Answer(t(lang,
					"RM không ghi nhận chi phí theo máy (cột Machine trống); chỉ T&SL có số liệu theo máy.",
					"RM has no machine data (Machine column is blank); only T&SL is tracked by machine."),
					List.of(t(lang, "Máy nào chi nhiều nhất ở PRESS T&SL?", "Which machine spends the most in PRESS T&SL?")));
		}

		int limit = usable.size() == 1 ? TOP_DETAIL_ONE : usable.size() <= TOP_DETAIL_FEW_FRAMES ? TOP_DETAIL_FEW : 1;
		LocalDate from = snapshot.month().atDay(1);

		List<Block> blocks = perFrame(usable, fr -> {
			LocalDate to = itemsTo(snapshot, fr.cost());
			if (to.isBefore(from)) {
				return null;
			}
			CostChartDto chart = snapshot.find(fr.div(), fr.cost());
			Double act = chart != null ? chart.actMtdLatest() : null;
			List<Entry> entries = detailEntries(fr, dim, from, to, limit);
			return entries.isEmpty() ? null : new Block(fr, entries, act, to);
		}).stream().filter(java.util.Objects::nonNull).toList();

		if (blocks.isEmpty()) {
			return null;
		}

		LocalDate latestTo = blocks.stream().map(Block::to).max(Comparator.naturalOrder()).orElse(from);
		String period = ddmm(from) + "–" + ddmm(latestTo);
		String what = switch (dim) {
			case MACHINE -> t(lang, "Máy", "Machines");
			case COST_CENTER -> "Cost center";
			case CATEGORY -> "Category";
		};

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
					sb.append(" (Actual ").append(CmText.k1(b.act())).append(')');
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
				appendPct(sb, e.amountK(), b.act());
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
			appendPct(sb, e.amountK(), b.act());
			sb.append('\n');
		}
	}

	private static void appendPct(StringBuilder sb, Double amountK, Double act) {
		String share = ExplainVarianceTool.pctOfAct(amountK, act);
		if (!share.isEmpty()) {
			sb.append(" (").append(share).append(" Actual)");
		}
	}


	// =========================================================
	// TOP VẬT TƯ
	// =========================================================

	private Answer topItem(CostMonitoringSnapshot snapshot, List<CostMonitoringFocus> frames, boolean expensive,
						   String lang) {

		LocalDate from = snapshot.month().atDay(1);
		boolean single = frames.size() == 1;

		record Row(CostMonitoringFocus frame, CostTopItemDto item, Double act, LocalDate to) {
		}

		List<List<Row>> perFrameRows = perFrame(frames, fr -> {

			LocalDate to = itemsTo(snapshot, fr.cost());

			if (to.isBefore(from)) {
				return List.<Row>of();
			}

			CostChartDto chart = snapshot.find(fr.div(), fr.cost());
			Double act = chart != null ? chart.actMtdLatest() : null;

			return repository.findTopItems(fr.cost(), from, to,
							CostMonitoringSnapshot.DIV_COST_CENTERS.get(fr.div()), single ? TOP_ITEMS_ONE : 1)
					.stream()
					.filter(item -> item.amountK() != null && item.amountK() > 0)
					.map(item -> new Row(fr, item, act, to))
					.toList();
		});

		List<Row> rows = new ArrayList<>();
		perFrameRows.forEach(rows::addAll);
		LocalDate latestTo = rows.stream().map(Row::to).max(Comparator.naturalOrder()).orElse(null);

		if (rows.isEmpty()) {
			return null;
		}

		StringBuilder sb = new StringBuilder();
		String period = ddmm(from) + "–" + ddmm(latestTo);

		if (single) {
			CostMonitoringFocus fr = frames.get(0);
			sb.append(t(lang, "Vật tư chi nhiều nhất ở **", "Top items by spending in **"))
					.append(fr.div()).append(' ').append(fr.cost()).append("** (").append(period)
					.append(t(lang, ", theo tổng chi):", ", by total spending):")).append('\n');

			int i = 0;
			for (Row r : rows) {
				sb.append(++i).append(". ").append(itemLine(r.item(), r.act(), expensive, lang)).append('\n');
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
						.append(itemLine(r.item(), r.act(), expensive, lang)).append('\n');
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
	private static String itemLine(CostTopItemDto item, Double act, boolean expensive, String lang) {

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

			double perDay = left / remainingDays;
			sb.append(t(lang, "còn ", "")).append(CmText.k1(left)).append(t(lang, " → **", " left → **"))
					.append(CmText.rate(perDay, lang)).append("**");

			if (workDays != null && workDays > 0) {
				sb.append(" (").append(CmText.rate(left / workDays, lang))
						.append(t(lang, " làm việc", " working")).append(')');
			}

			Double rate = a.currentRate();
			if (rate != null && rate > 0) {
				sb.append(t(lang, "; đang chi ", "; currently ")).append(CmText.rate(rate, lang));
				if (rate > perDay) {
					double cut = (1 - perDay / rate) * 100;
					sb.append(t(lang, " → cần giảm ", " → needs a ")).append(CmText.pct0(cut))
							.append(t(lang, "", " cut"));
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

		LocalDate from = snapshot.month().atDay(snapshot.d() + 1);
		LocalDate to = snapshot.month().atEndOfMonth();

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
					+ (avg != null ? t(lang, " (TB ngày có chi ", " (avg spending day ") + k1(avg) + ")" : ""));

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

		if (act == null || act <= 0) {
			return List.of();
		}

		try {
			List<CostLineDto> lines = repository.findUnusualLines(fr.cost(), day, day,
					CostMonitoringSnapshot.DIV_COST_CENTERS.get(fr.div()), act * UNUSUAL_SHARE, MAX_UNUSUAL);
			if (lines == null) {
				return List.of();
			}
			// findUnusualLines còn khớp theo từ khóa (license …): chỉ giữ dòng thực sự ≥ 10% Actual
			return lines.stream().filter(l -> l.amountK() != null && l.amountK() >= act * UNUSUAL_SHARE).toList();
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
