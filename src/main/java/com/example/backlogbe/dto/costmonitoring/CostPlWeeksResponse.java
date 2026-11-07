package com.example.backlogbe.dto.costmonitoring;

import java.util.List;

/*
 * GET /api/cost-monitoring/pl-weeks?month=yyyy-MM.
 *
 * weeks      : các tuần Simulation (Simu_W1..Simu_W5) có số trong F2_PL_TGT của tháng,
 *              tính trên 9 khung Div × Cate của màn hình, tăng dần. Rỗng = chưa có PL simulation.
 * latestWeek : tuần "Mới nhất (tự động)" đang dùng; null nếu weeks rỗng.
 *              SP chọn tuần mới nhất theo TỪNG khung, nên đây là tuần mới nhất trong các khung.
 * latestMixed: true khi các khung đang dùng tuần khác nhau (khung thiếu số tuần mới nhất
 *              sẽ lấy tuần cũ hơn) – lúc đó latestWeek chỉ là tuần mới nhất, không phải của mọi khung.
 */
public record CostPlWeeksResponse(
		String month,
		List<String> weeks,
		String latestWeek,
		boolean latestMixed
) {
}
