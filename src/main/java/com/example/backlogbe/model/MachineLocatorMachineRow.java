package com.example.backlogbe.model;

// Một dòng dbo.F2_MachineLocator_Machine (các cột *_json giữ nguyên chuỗi JSON).
// review NOT NULL: false (0) nghĩa là FE không có key "r".
public record MachineLocatorMachineRow(
		String code,
		String boxesJson,
		String polysJson,
		String fitJson,
		Boolean uncertain,
		String src,
		Boolean review,
		String cadBoxesJson,
		String cadPolysJson,
		String cadFitJson
) {

	public MachineLocatorMachineRow withReview(Boolean newReview) {
		return new MachineLocatorMachineRow(
				code, boxesJson, polysJson, fitJson,
				uncertain, src, newReview,
				cadBoxesJson, cadPolysJson, cadFitJson
		);
	}

	public MachineLocatorMachineRow withCurrent(
			String newBoxesJson,
			String newPolysJson,
			String newFitJson,
			Boolean newUncertain,
			String newSrc,
			Boolean newReview
	) {
		return new MachineLocatorMachineRow(
				code, newBoxesJson, newPolysJson, newFitJson,
				newUncertain, newSrc, newReview,
				cadBoxesJson, cadPolysJson, cadFitJson
		);
	}
}
