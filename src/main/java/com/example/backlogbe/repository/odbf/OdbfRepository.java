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
				                ToPK,
				                PK_Received
				            ) > ExportD,
				            'Late',
				            'OK'
				        ) AS ODBF_Judge,
				
				        *
				
				    FROM od
				
				    WHERE
				        ShortLT <> 'ShortLT'
				)
				
				SELECT
				    ProductGrp,
				    ODBF_Judge,
				    ExportD,
				
				    COUNT(AUFNR) AS CountPO,
				
				    COALESCE(
				        SUM(
				            CAST(
				                COALESCE(
				                    FinalQty,
				                    0
				                )
				                AS DECIMAL(38, 4)
				            )
				        ),
				        0
				    ) AS SumQty
				
				FROM bl
				
				WHERE
				    Div = 'PR'
				    AND ShortLT = ''
				
				GROUP BY
				    ProductGrp,
				    ODBF_Judge,
				    ExportD
				
				ORDER BY
				    ProductGrp,
				    ExportD,
				    ODBF_Judge
				""";

		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> {

					Timestamp exportTimestamp =
							rs.getTimestamp("ExportD");

					BigDecimal sumQty =
							rs.getBigDecimal("SumQty");

					return new OdbfSummaryDto(
							rs.getString("ProductGrp"),

							rs.getString("ODBF_Judge"),

							exportTimestamp == null
									? null
									: exportTimestamp.toLocalDateTime(),

							rs.getLong("CountPO"),

							sumQty == null
									? BigDecimal.ZERO
									: sumQty,

							// Service sẽ tính
							null,

							// Service sẽ tính
							null
					);
				}
		);
	}
}