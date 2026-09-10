package com.example.backlogbe.service;

import com.example.backlogbe.dto.odbf.OdbfSummaryDto;
import com.example.backlogbe.repository.odbf.OdbfRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OdbfService {

	private final OdbfRepository repository;


	// =========================================================
	// SUMMARY
	// =========================================================

	@Transactional(readOnly = true)
	public List<OdbfSummaryDto> getSummary() {

		List<OdbfSummaryDto> rows =
				repository.findSummary();

		Map<GroupKey, RatioData> ratioMap =
				new LinkedHashMap<>();


		// =====================================================
		// CALCULATE TOTAL / COMPLETED
		// =====================================================

		for (OdbfSummaryDto row : rows) {

			GroupKey key =
					new GroupKey(
							row.productGrp(),
							row.exportD()
					);

			RatioData data =
					ratioMap.computeIfAbsent(
							key,
							ignored -> new RatioData()
					);


			// TOTAL PO
			data.totalPo +=
					row.countPo();


			// TOTAL QTY
			data.totalQty =
					data.totalQty.add(
							safeDecimal(
									row.sumQty()
							)
					);


			// =============================================
			// SUCCESS STATUS
			// =============================================

			if (isSuccessStatus(row.status2())) {

				data.successPo +=
						row.countPo();

				data.successQty =
						data.successQty.add(
								safeDecimal(
										row.sumQty()
								)
						);
			}
		}


		// =====================================================
		// BUILD RESULT
		// =====================================================

		List<OdbfSummaryDto> result =
				new ArrayList<>();


		for (OdbfSummaryDto row : rows) {

			GroupKey key =
					new GroupKey(
							row.productGrp(),
							row.exportD()
					);

			RatioData data =
					ratioMap.get(key);


			BigDecimal poRatio =
					calculateRatio(
							BigDecimal.valueOf(
									data.successPo
							),
							BigDecimal.valueOf(
									data.totalPo
							)
					);


			BigDecimal qtyRatio =
					calculateRatio(
							data.successQty,
							data.totalQty
					);


			result.add(
					new OdbfSummaryDto(
							row.productGrp(),
							row.status2(),
							row.exportD(),
							row.countPo(),
							row.sumQty(),
							poRatio,
							qtyRatio
					)
			);
		}


		return result;
	}


	// =========================================================
	// SUCCESS STATUS
	// =========================================================

	private boolean isSuccessStatus(
			String status
	) {

		if (status == null) {
			return false;
		}

		String normalized =
				status.trim()
						.toUpperCase();

		return normalized.equals("OK")
				|| normalized.equals("COMPLETED");
	}


	// =========================================================
	// RATIO
	// =========================================================

	private BigDecimal calculateRatio(
			BigDecimal success,
			BigDecimal total
	) {

		if (
				total == null
						|| total.compareTo(
						BigDecimal.ZERO
				) == 0
		) {
			return BigDecimal.ZERO;
		}

		return success
				.multiply(
						BigDecimal.valueOf(100)
				)
				.divide(
						total,
						2,
						RoundingMode.HALF_UP
				);
	}


	private BigDecimal safeDecimal(
			BigDecimal value
	) {

		return value == null
				? BigDecimal.ZERO
				: value;
	}


	// =========================================================
	// GROUP KEY
	// =========================================================

	private record GroupKey(
			String productGrp,
			LocalDateTime exportD
	) {
	}


	// =========================================================
	// RATIO DATA
	// =========================================================

	private static class RatioData {

		private long totalPo = 0L;
		private long successPo = 0L;

		private BigDecimal totalQty =
				BigDecimal.ZERO;

		private BigDecimal successQty =
				BigDecimal.ZERO;
	}
}