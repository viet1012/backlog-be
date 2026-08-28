package com.example.backlogbe.repository.facconfirm;


import java.util.Map;

public final class FacConfirmFilterField {

	private static final Map<String, String> FIELD_MAP =
			Map.ofEntries(

					Map.entry(
							"ferth",
							"bl.FERTH"
					),

					Map.entry(
							"productGrp",
							"bl.ProductGrp"
					),

					Map.entry(
							"aufnr",
							"bl.AUFNR"
					),

					Map.entry(
							"zglobalCode",
							"bl.ZGLOBAL_CODE"
					),

					Map.entry(
							"pname",
							"bl.PNAME"
					),

					Map.entry(
							"issueD",
							"bl.IssueD"
					),

					Map.entry(
							"exportD",
							"bl.ExportD"
					),

					Map.entry(
							"cusId",
							"bl.RRONYU1"
					),

					Map.entry(
							"shipBy",
							"bl.ShipBy"
					),

					Map.entry(
							"mtoId",
							"bl.MTO_ID"
					),

					Map.entry(
							"prtAddcmt2",
							"bl.PRT_ADDCMT2"
					),

					Map.entry(
							"currentProcess",
							"bl.CurrentProcess"
					),

					Map.entry(
							"finalQty",
							"bl.FinalQty"
					),

					Map.entry(
							"toDrill",
							"bl.ToDrill"
					),

					Map.entry(
							"toHeat",
							"bl.ToHeat"
					),

					Map.entry(
							"heatStart",
							"bl.TimeSQuenching"
					),

					Map.entry(
							"heatFinish",
							"bl.TimeFHeat"
					),

					Map.entry(
							"toPk",
							"bl.ToPK"
					)
			);


	private FacConfirmFilterField() {
	}

	public static boolean supports(
			String field
	) {

		return field != null
				&& FIELD_MAP.containsKey(field);
	}


	public static String column(
			String field
	) {

		String column =
				FIELD_MAP.get(field);

		if (column == null) {
			throw new IllegalArgumentException(
					"Unsupported Fac Confirm filter field: "
							+ field
			);
		}

		return column;
	}
}