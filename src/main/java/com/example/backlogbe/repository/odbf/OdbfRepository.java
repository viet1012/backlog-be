package com.example.backlogbe.repository.odbf;

import com.example.backlogbe.dto.odbf.OdbfSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class OdbfRepository {

	private final JdbcTemplate jdbcTemplate;


	public List<OdbfSummaryDto> findSummary() {

		String sql = """
				WITH od AS (
				    SELECT
				
				        CAST(
				            ExportD - IssueD
				            AS INT
				        ) AS IssueLT,
				
				        IIF(
				            (
				                PRT_ADDCMT2 LIKE '%Bu%'
				                OR AUFNR LIKE '72%'
				            ),
				            '',
				            IIF(
				                CAST(
				                    ExportD - IssueD
				                    AS INT
				                )
				                -
				                COALESCE(
				                    WaitingDays,
				                    IIF(
				                        ProductGrp = 'Retainer',
				                        3,
				                        IIF(
				                            ProductGrp = 'Sprue Bush'
				                            OR ProductGrp = 'Taper Block',
				                            1,
				                            0
				                        )
				                    )
				                )
				                <= 1,
				                'ShortLT',
				                ''
				            )
				        ) AS ShortLT,
				
				        *
				
				    FROM F2_Backlog_Main
				
				    WHERE
				        CountODBF <> 'No Count'
				),
				
				bl AS (
				    SELECT
				
				        IIF(
				            COALESCE(
				                od.ToPK,
				                od.PK_Received
				            ) > od.ExportD
				            OR COALESCE(
				                od.ToPK,
				                od.PK_Received
				            ) IS NULL,
				            'Late',
				            'OK'
				        ) AS ODBF_Judge,
				
				        od.*,
				
				        fac.ConfirmFnTime,
				
				        IIF(
				            COALESCE(
				                od.ToPK,
				                od.PK_Received
				            ) IS NULL,
				
				            IIF(
				                fac.ConfirmFnTime < od.ExportD,
				                'OK',
				                'Late'
				            ),
				
				            NULL
				        ) AS FacSimu
				
				    FROM od
				
				    LEFT JOIN F2_Backlog_Fac_Confirm fac
				        ON od.AUFNR = fac.AUNFR
				       AND fac.ProcessGrp = 'To Packing'
				
				    WHERE
				        od.ShortLT <> 'ShortLT'
				)
				
				SELECT
				
				    bl.ProductGrp,
				
				    COALESCE(
				        bl.FacSimu,
				        bl.ODBF_Judge
				    ) AS Status2,
				
				    bl.ExportD,
				
				    COUNT(
				        bl.AUFNR
				    ) AS CountPO,
				
				    COALESCE(
				        SUM(
				            CAST(
				                COALESCE(
				                    bl.FinalQty,
				                    0
				                )
				                AS DECIMAL(38, 4)
				            )
				        ),
				        0
				    ) AS SumQty
				
				FROM bl
				
				WHERE
				    bl.Div = 'PR'
				    AND bl.ShortLT = ''
				
				GROUP BY
				    bl.ProductGrp,
				    bl.ExportD,
				    COALESCE(
				        bl.FacSimu,
				        bl.ODBF_Judge
				    )
				
				ORDER BY
				    bl.ProductGrp,
				    bl.ExportD,
				    Status2
				""";


		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> {

					Timestamp exportTimestamp =
							rs.getTimestamp(
									"ExportD"
							);

					BigDecimal sumQty =
							rs.getBigDecimal(
									"SumQty"
							);


					return new OdbfSummaryDto(
							rs.getString(
									"ProductGrp"
							),

							// field thứ 2 của DTO = status2
							rs.getString(
									"Status2"
							),

							exportTimestamp == null
									? null
									: exportTimestamp
									.toLocalDateTime(),

							rs.getLong(
									"CountPO"
							),

							sumQty == null
									? BigDecimal.ZERO
									: sumQty,

							// Service tính poRatio
							null,

							// Service tính qtyRatio
							null
					);
				}
		);
	}
}