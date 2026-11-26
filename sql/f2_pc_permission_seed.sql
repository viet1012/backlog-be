/* =====================================================================================
   f2_pc_permission_seed.sql  (bản sửa cho DB compatibility level 110 – KHÔNG dùng STRING_SPLIT)
   Nạp phân quyền cho 2 app vào:
     F2_ProductionControl_App / _RolePerm / _Assign  (+ view vw_F2_ProductionControl_EmpPerm)
   - PCW     : backlog-be  (ProductionControlAuthService.determineRoles + các chỗ check role)
   - SPATROL : patrol_be   (PermissionService, SPatrol_Role, SPatrol_RolePermission, HSE_EmpID.Role)

   Chạy lại được: xóa RolePerm / Assign theo AppCode rồi nạp lại (App: MERGE).
   Tách chuỗi 'A,B,C' bằng XML (CROSS APPLY ... nodes) thay cho STRING_SPLIT.
   STRING_AGG (mục 4) chạy được trên SQL Server 2022 ở mọi compatibility level.

   QUY ƯỚC ACTIONS – PCW: V xem, C tạo, E sửa, D xóa, X import/export
   QUY ƯỚC ACTIONS – SPATROL: BEFORE, AFTER, RECHECK, SUMMARY

   Thứ tự:
     0. REVIEW (comment, chạy tay trước)
     1. App   2. PCW   3. SPATROL   (trong transaction)
     4. Đối chiếu quyền cũ với vw_F2_ProductionControl_EmpPerm (sau COMMIT, chỉ đọc)
   ===================================================================================== */


-- =====================================================================================
-- 0. REVIEW – chạy tay, KHÔNG nằm trong transaction. Dùng kết quả sửa các dòng "-- TODO xác nhận".
-- =====================================================================================

-- REVIEW (PCW-1): ADMIN cũ = Dept = 'K COMMON' VÀ ("PE IT" nằm trong Section HOẶC Line HOẶC Group)
/*
SELECT d.Fac, d.Dept, d.Section, d.Line, d.[Group], COUNT(*) AS SoNV
FROM dbo.DATASPC d
WHERE d.[Date of resign(form)] IS NULL
  AND UPPER(LTRIM(RTRIM(ISNULL(d.Dept, '')))) = 'K COMMON'
  AND (   CHARINDEX('PE IT', UPPER(ISNULL(d.Section, ''))) > 0
       OR CHARINDEX('PE IT', UPPER(ISNULL(d.Line,    ''))) > 0
       OR CHARINDEX('PE IT', UPPER(ISNULL(d.[Group], ''))) > 0)
GROUP BY d.Fac, d.Dept, d.Section, d.Line, d.[Group]
ORDER BY d.Fac, d.Dept, d.Section, d.Line, d.[Group];
*/

-- REVIEW (PCW-2): PC cũ = "PC" nằm trong Section/Line/Group (bắt nhầm cả SPC, PCB, EPC…)
/*
SELECT d.Fac, d.Dept, d.Section, d.Line, d.[Group],
       CASE WHEN CHARINDEX('PC', UPPER(ISNULL(d.Section, ''))) > 0 THEN 'Section'
            WHEN CHARINDEX('PC', UPPER(ISNULL(d.Line,    ''))) > 0 THEN 'Line'
            ELSE 'Group' END AS KhopCot,
       COUNT(*) AS SoNV
FROM dbo.DATASPC d
WHERE d.[Date of resign(form)] IS NULL
  AND (   CHARINDEX('PC', UPPER(ISNULL(d.Section, ''))) > 0
       OR CHARINDEX('PC', UPPER(ISNULL(d.Line,    ''))) > 0
       OR CHARINDEX('PC', UPPER(ISNULL(d.[Group], ''))) > 0)
GROUP BY d.Fac, d.Dept, d.Section, d.Line, d.[Group]
ORDER BY d.Fac, d.Dept, d.Section, d.Line, d.[Group];
*/

-- REVIEW (PCW-3): PRO cũ = "PRO" nằm trong Section/Line/Group (bắt nhầm cả PROCESS, PROJECT…)
/*
SELECT d.Fac, d.Dept, d.Section, d.Line, d.[Group],
       CASE WHEN CHARINDEX('PRO', UPPER(ISNULL(d.Section, ''))) > 0 THEN 'Section'
            WHEN CHARINDEX('PRO', UPPER(ISNULL(d.Line,    ''))) > 0 THEN 'Line'
            ELSE 'Group' END AS KhopCot,
       COUNT(*) AS SoNV
FROM dbo.DATASPC d
WHERE d.[Date of resign(form)] IS NULL
  AND (   CHARINDEX('PRO', UPPER(ISNULL(d.Section, ''))) > 0
       OR CHARINDEX('PRO', UPPER(ISNULL(d.Line,    ''))) > 0
       OR CHARINDEX('PRO', UPPER(ISNULL(d.[Group], ''))) > 0)
GROUP BY d.Fac, d.Dept, d.Section, d.Line, d.[Group]
ORDER BY d.Fac, d.Dept, d.Section, d.Line, d.[Group];
*/

-- REVIEW (PCW-4): code cũ đọc HR từ dbo.F2_HR_Data, hệ thống mới đọc vw_F2_ProductionControl_Employee.
--                 NV có tài khoản mà thiếu / lệch giữa 2 nguồn.
/*
SELECT a.EmpCode, e.Source,
       h.Dept AS HR_Dept, h.Section AS HR_Section, h.Line AS HR_Line, h.[Group] AS HR_Group,
       e.Dept AS New_Dept, e.Section AS New_Section, e.Line AS New_Line, e.Grp AS New_Group
FROM (SELECT DISTINCT UPPER(LTRIM(RTRIM(Account))) AS EmpCode FROM dbo.HSE_Patrol_Account WHERE Account IS NOT NULL) a
OUTER APPLY (SELECT TOP 1 Dept, Section, Line, [Group] FROM dbo.F2_HR_Data
             WHERE LTRIM(RTRIM(CAST(Code AS VARCHAR(50)))) = a.EmpCode ORDER BY id DESC) h
LEFT JOIN dbo.vw_F2_ProductionControl_Employee e ON e.EmpCode = a.EmpCode
WHERE e.EmpCode IS NULL
   OR ISNULL(h.Dept, '')    <> ISNULL(e.Dept, '')
   OR ISNULL(h.Section, '') <> ISNULL(e.Section, '')
   OR ISNULL(h.Line, '')    <> ISNULL(e.Line, '')
   OR ISNULL(h.[Group], '') <> ISNULL(e.Grp, '');
*/

-- REVIEW (SPATROL-1): kiểu dữ liệu mã NV
/*
SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, COLLATION_NAME
FROM INFORMATION_SCHEMA.COLUMNS
WHERE (TABLE_NAME = 'HSE_EmpID' AND COLUMN_NAME = 'EmpID')
   OR (TABLE_NAME = 'DATASPC'   AND COLUMN_NAME = 'Code')
   OR (TABLE_NAME = 'F2_ProductionControl_Assign' AND COLUMN_NAME = 'EmpCode');
*/

-- REVIEW (SPATROL-2): HSE_EmpID có Role nhưng KHÔNG có trong vw_F2_ProductionControl_Employee
--                     (DATASPC + ExtEmployee) -> sẽ không nhận quyền nào.
/*
SELECT e.EmpID, e.EmpName, e.Role
FROM dbo.HSE_EmpID e
WHERE LTRIM(RTRIM(ISNULL(e.Role, ''))) <> ''
  AND NOT EXISTS (SELECT 1 FROM dbo.vw_F2_ProductionControl_Employee v
                  WHERE v.EmpCode = LTRIM(RTRIM(e.EmpID)));

-- Mã có trong ExtEmployee nhưng trùng DATASPC (quyền gán theo mã sẽ rơi vào người trong DATASPC)
SELECT x.EmpCode, x.Name, d.Name AS TrungVoi_DATASPC
FROM dbo.F2_ProductionControl_ExtEmployee x
JOIN dbo.DATASPC d ON CAST(d.Code AS VARCHAR(20)) = x.EmpCode;

-- Role trong HSE_EmpID không có trong SPatrol_Role (code cũ bỏ qua; seed cũng bỏ qua)
SELECT r.RoleCode, COUNT(*) AS SoNV
FROM dbo.HSE_EmpID e
CROSS APPLY (SELECT CAST('<a>' + REPLACE(REPLACE(REPLACE(ISNULL(e.Role, ''), '&', '&amp;'), '<', '&lt;'), ',', '</a><a>') + '</a>' AS XML) AS x) rx
CROSS APPLY rx.x.nodes('/a') rn(v)
CROSS APPLY (SELECT UPPER(LTRIM(RTRIM(rn.v.value('.', 'NVARCHAR(100)')))) AS RoleCode) r
WHERE r.RoleCode <> ''
  AND NOT EXISTS (SELECT 1 FROM dbo.SPatrol_Role sr WHERE UPPER(LTRIM(RTRIM(sr.RoleCode))) = r.RoleCode)
GROUP BY r.RoleCode;

-- GroupCode không khớp PatrolGroup (code cũ bỏ qua; seed cũng bỏ qua)
SELECT * FROM dbo.SPatrol_RolePermission
WHERE UPPER(LTRIM(RTRIM(GroupCode))) NOT IN ('PATROL', 'AUDIT', 'QUALITYPATROL', 'ASSETUPDATE', 'FIXEDASSET');
*/


SET XACT_ABORT ON;
SET NOCOUNT ON;

BEGIN TRAN;

DELETE FROM dbo.F2_ProductionControl_Assign   WHERE AppCode IN ('PCW', 'SPATROL');
DELETE FROM dbo.F2_ProductionControl_RolePerm WHERE AppCode IN ('PCW', 'SPATROL');


-- =====================================================================================
-- 1. App
-- =====================================================================================
MERGE dbo.F2_ProductionControl_App AS t
USING (VALUES
    ('PCW',     N'Production Control Web (backlog-be)'),
    ('SPATROL', N'S-Patrol (patrol_be)')
) AS s (AppCode, AppName)
ON t.AppCode = s.AppCode
WHEN MATCHED THEN UPDATE SET AppName = s.AppName
WHEN NOT MATCHED THEN INSERT (AppCode, AppName) VALUES (s.AppCode, s.AppName);


-- =====================================================================================
-- 2. PCW (backlog-be) – BE chỉ check ADMIN:
--   MACHINE_LOCATOR  C,E,D,X (ghi, xóa, import layout); xem không check
--   ASSISTANT_LOG    V,X     (xem, export log)
--   COST_MONITORING  X       (import OT); xem không check
--   PC, PRO, USER: BE không check -> không có RolePerm (chỉ có Assign)
-- =====================================================================================
INSERT INTO dbo.F2_ProductionControl_RolePerm (AppCode, RoleCode, Feature, Actions) VALUES
    ('PCW', 'ADMIN',     '*',               '*'),     -- toàn quyền
    ('PCW', 'PC',        'BACKLOG',         'V,X'),   -- Backlog, Remain PO, Sale follow
    ('PCW', 'PC',        'FAC_CONFIRM',     'V,E,X'),
    ('PCW', 'PC',        'ODBF',            'V'),
    ('PCW', 'PC',        'SHIPMENT',        'V,X'),   -- Shipment, Packing list
    ('PCW', 'PRO',       'FAC_CONFIRM',     'V,E,X'),
    ('PCW', 'PRO',       'ODBF',            'V'),
    ('PCW', 'PRO',       'SHIPMENT',        'V,X'),
    ('PCW', 'USER',      'MACHINE_LOCATOR', 'V'),
    ('PCW', 'USER',      'GENERAL',         'V'),     -- SPH, Asakai, PC training, Sales status, Management
    ('PCW', 'COST_VIEW', 'COST_MONITORING', 'V');     -- Band1-3; import/export OT chỉ ADMIN

-- Assign PCW: giá trị chính xác (đã chốt theo REVIEW PCW-1..3)
INSERT INTO dbo.F2_ProductionControl_Assign
    (AppCode, RoleCode, EmpCode, Fac, Dept, Section, Line, Grp, MaxBand, ValidTo, Note)
VALUES
    ('PCW', 'USER',  NULL, NULL,        NULL,        NULL,         NULL, NULL, NULL, NULL, N'Mặc định: mọi người'),
    -- ADMIN: 5 người
    ('PCW', 'ADMIN', NULL, NULL,        N'K Common', N'PE IT',     NULL, NULL, NULL, NULL, N'IT'),
    -- PC: tạm thời chỉ Fac_2 (16 người)
    ('PCW', 'PC',    NULL, N'K Common', NULL,        N'PC',        NULL, NULL, NULL, NULL, N'Fac_2'),
    ('PCW', 'PC',    NULL, NULL,        NULL,        N'Guide PC',  NULL, NULL, NULL, NULL, N'Fac_2'),
    ('PCW', 'PC',    NULL, NULL,        NULL,        N'KM PC',     NULL, NULL, NULL, NULL, N'Fac_2'),
    ('PCW', 'PC',    NULL, NULL,        NULL,        N'KP PC',     NULL, NULL, NULL, NULL, N'Fac_2'),
    -- PRO: bộ phận sản xuất + PRO PE (bỏ Processing, Production..., meviy Process)
    ('PCW', 'PRO',   NULL, NULL,        NULL,        N'KP Pro',    NULL, NULL, NULL, NULL, NULL),
    ('PCW', 'PRO',   NULL, NULL,        NULL,        N'KM Pro',    NULL, NULL, NULL, NULL, NULL),
    ('PCW', 'PRO',   NULL, NULL,        NULL,        N'Guide Pro', NULL, NULL, NULL, NULL, NULL),
    ('PCW', 'PRO',   NULL, NULL,        NULL,        N'PRO PE',    NULL, NULL, NULL, NULL, N'K Common'),
    -- Cost Monitoring: Band1-3 (ADMIN đã có qua '*')
    ('PCW', 'COST_VIEW', NULL, NULL,    NULL,        NULL,         NULL, NULL, 3,    NULL, N'Band1-3 xem Cost Monitoring');


-- =====================================================================================
-- 3. SPATROL (patrol_be)
--   Feature = PatrolGroup; Actions = CanBefore/CanAfter/CanRecheck/CanSummary
--   Chỉ role có trong SPatrol_Role (riêng USER luôn tính); IsSuper=1 -> toàn quyền
-- =====================================================================================

-- 3a. Role IsSuper = 1 -> toàn quyền
INSERT INTO dbo.F2_ProductionControl_RolePerm (AppCode, RoleCode, Feature, Actions)
SELECT DISTINCT 'SPATROL', UPPER(LTRIM(RTRIM(r.RoleCode))), '*', '*'
FROM dbo.SPatrol_Role r
WHERE r.IsSuper = 1
  AND LTRIM(RTRIM(ISNULL(r.RoleCode, ''))) <> '';

-- 3b. Role thường
INSERT INTO dbo.F2_ProductionControl_RolePerm (AppCode, RoleCode, Feature, Actions)
SELECT 'SPATROL', x.RoleCode, x.Feature,
       STUFF(CONCAT(CASE WHEN x.B = 1 THEN ',BEFORE'  ELSE '' END,
                    CASE WHEN x.A = 1 THEN ',AFTER'   ELSE '' END,
                    CASE WHEN x.R = 1 THEN ',RECHECK' ELSE '' END,
                    CASE WHEN x.S = 1 THEN ',SUMMARY' ELSE '' END), 1, 1, '')
FROM (
    SELECT n.RoleCode, n.Feature,
           MAX(CASE WHEN p.CanBefore  = 1 THEN 1 ELSE 0 END) AS B,
           MAX(CASE WHEN p.CanAfter   = 1 THEN 1 ELSE 0 END) AS A,
           MAX(CASE WHEN p.CanRecheck = 1 THEN 1 ELSE 0 END) AS R,
           MAX(CASE WHEN p.CanSummary = 1 THEN 1 ELSE 0 END) AS S
    FROM dbo.SPatrol_RolePermission p
    CROSS APPLY (SELECT
        UPPER(LTRIM(RTRIM(p.RoleCode))) AS RoleCode,
        CASE UPPER(LTRIM(RTRIM(p.GroupCode)))
            WHEN 'PATROL'        THEN 'Patrol'
            WHEN 'AUDIT'         THEN 'Audit'
            WHEN 'QUALITYPATROL' THEN 'QualityPatrol'
            WHEN 'ASSETUPDATE'   THEN 'AssetUpdate'
            WHEN 'FIXEDASSET'    THEN 'FixedAsset'
        END AS Feature
    ) n
    WHERE n.Feature IS NOT NULL
    GROUP BY n.RoleCode, n.Feature
) x
WHERE (x.B + x.A + x.R + x.S) > 0
  AND (x.RoleCode = 'USER'
       OR EXISTS (SELECT 1 FROM dbo.SPatrol_Role r WHERE UPPER(LTRIM(RTRIM(r.RoleCode))) = x.RoleCode))
  AND NOT EXISTS (SELECT 1 FROM dbo.SPatrol_Role r
                  WHERE r.IsSuper = 1 AND UPPER(LTRIM(RTRIM(r.RoleCode))) = x.RoleCode);

-- 3c. USER cho mọi người
INSERT INTO dbo.F2_ProductionControl_Assign
    (AppCode, RoleCode, EmpCode, Fac, Dept, Section, Line, Grp, MaxBand, ValidTo, Note)
VALUES
    ('SPATROL', 'USER', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, N'Mặc định: mọi người');

-- 3d. Gán riêng từ HSE_EmpID.Role (tách dấu phẩy bằng XML), giữ nguyên EmpID (kể cả số 0 đầu)
--     Bỏ qua mã trùng giữa ExtEmployee và DATASPC (xem REVIEW SPATROL-2) để không trao nhầm quyền.
INSERT INTO dbo.F2_ProductionControl_Assign
    (AppCode, RoleCode, EmpCode, Fac, Dept, Section, Line, Grp, MaxBand, ValidTo, Note)
SELECT DISTINCT 'SPATROL', r.RoleCode, LTRIM(RTRIM(e.EmpID)),
       NULL, NULL, NULL, NULL, NULL, NULL, NULL, N'Từ HSE_EmpID.Role'
FROM dbo.HSE_EmpID e
CROSS APPLY (SELECT CAST('<a>' + REPLACE(REPLACE(REPLACE(ISNULL(e.Role, ''), '&', '&amp;'), '<', '&lt;'), ',', '</a><a>') + '</a>' AS XML) AS x) rx
CROSS APPLY rx.x.nodes('/a') rn(v)
CROSS APPLY (SELECT UPPER(LTRIM(RTRIM(rn.v.value('.', 'NVARCHAR(100)')))) AS RoleCode) r
WHERE LTRIM(RTRIM(ISNULL(e.EmpID, ''))) <> ''
  AND r.RoleCode <> ''
  AND r.RoleCode <> 'USER'
  AND EXISTS (SELECT 1 FROM dbo.SPatrol_Role sr WHERE UPPER(LTRIM(RTRIM(sr.RoleCode))) = r.RoleCode)
  AND NOT EXISTS (SELECT 1
                  FROM dbo.F2_ProductionControl_ExtEmployee x
                  JOIN dbo.DATASPC d ON CAST(d.Code AS VARCHAR(20)) = x.EmpCode
                  WHERE x.EmpCode = LTRIM(RTRIM(e.EmpID)));

COMMIT;


-- =====================================================================================
-- 4. ĐỐI CHIẾU (chỉ đọc, sau COMMIT). TrangThai: OK | CHI_CU (mất quyền) | CHI_MOI (thừa quyền)
-- =====================================================================================

-- ---------- 4.1 PCW ----------
IF OBJECT_ID('tempdb..#PcwOldRole') IS NOT NULL DROP TABLE #PcwOldRole;
IF OBJECT_ID('tempdb..#PcwSample')  IS NOT NULL DROP TABLE #PcwSample;

;WITH acc AS (
    SELECT DISTINCT UPPER(LTRIM(RTRIM(Account))) AS EmpCode
    FROM dbo.HSE_Patrol_Account
    WHERE LTRIM(RTRIM(ISNULL(Account, ''))) <> ''
),
f AS (
    SELECT a.EmpCode,
           CASE WHEN h.HasHr = 1 AND h.Dept = 'K COMMON'
                 AND (CHARINDEX('PE IT', h.Sec) > 0 OR CHARINDEX('PE IT', h.Lin) > 0 OR CHARINDEX('PE IT', h.Grp) > 0)
                THEN 1 ELSE 0 END AS IsAdmin,
           CASE WHEN CHARINDEX('PC',  h.Sec) > 0 OR CHARINDEX('PC',  h.Lin) > 0 OR CHARINDEX('PC',  h.Grp) > 0 THEN 1 ELSE 0 END AS IsPc,
           CASE WHEN CHARINDEX('PRO', h.Sec) > 0 OR CHARINDEX('PRO', h.Lin) > 0 OR CHARINDEX('PRO', h.Grp) > 0 THEN 1 ELSE 0 END AS IsPro
    FROM acc a
    OUTER APPLY (
        SELECT TOP 1 1 AS HasHr,
               UPPER(LTRIM(RTRIM(ISNULL(Dept,    '')))) AS Dept,
               UPPER(LTRIM(RTRIM(ISNULL(Section, '')))) AS Sec,
               UPPER(LTRIM(RTRIM(ISNULL(Line,    '')))) AS Lin,
               UPPER(LTRIM(RTRIM(ISNULL([Group], '')))) AS Grp
        FROM dbo.F2_HR_Data
        WHERE LTRIM(RTRIM(CAST(Code AS VARCHAR(50)))) = a.EmpCode
        ORDER BY id DESC
    ) h
)
SELECT EmpCode, RoleCode
INTO #PcwOldRole
FROM (
    SELECT EmpCode, 'ADMIN' AS RoleCode FROM f WHERE IsAdmin = 1
    UNION ALL SELECT EmpCode, 'PC'   FROM f WHERE IsAdmin = 0 AND IsPc  = 1
    UNION ALL SELECT EmpCode, 'PRO'  FROM f WHERE IsAdmin = 0 AND IsPro = 1
    UNION ALL SELECT EmpCode, 'USER' FROM f WHERE IsAdmin = 0 AND IsPc = 0 AND IsPro = 0
) u;

SELECT DISTINCT EmpCode
INTO #PcwSample
FROM (SELECT EmpCode, ROW_NUMBER() OVER (PARTITION BY RoleCode ORDER BY EmpCode) AS rn FROM #PcwOldRole) s
WHERE rn <= 3;
-- INSERT INTO #PcwSample (EmpCode) VALUES ('...');

;WITH oldPerm AS (
    SELECT o.EmpCode, m.Feature, m.ActionCode
    FROM #PcwOldRole o
    JOIN (VALUES
        ('ADMIN', 'MACHINE_LOCATOR', 'C'), ('ADMIN', 'MACHINE_LOCATOR', 'E'),
        ('ADMIN', 'MACHINE_LOCATOR', 'D'), ('ADMIN', 'MACHINE_LOCATOR', 'X'),
        ('ADMIN', 'ASSISTANT_LOG',   'V'), ('ADMIN', 'ASSISTANT_LOG',   'X'),
        ('ADMIN', 'COST_MONITORING', 'X')
    ) m (RoleCode, Feature, ActionCode) ON m.RoleCode = o.RoleCode
    WHERE o.EmpCode IN (SELECT EmpCode FROM #PcwSample)
),
cat AS (
    SELECT fe.Feature, ac.ActionCode
    FROM (VALUES ('MACHINE_LOCATOR'), ('ASSISTANT_LOG'), ('COST_MONITORING')) fe (Feature)
    CROSS JOIN (VALUES ('V'), ('C'), ('E'), ('D'), ('X')) ac (ActionCode)
),
newPerm AS (
    SELECT DISTINCT v.EmpCode, c.Feature, c.ActionCode
    FROM dbo.vw_F2_ProductionControl_EmpPerm v
    JOIN cat c ON (v.Feature = '*' OR v.Feature = c.Feature)
              AND (v.ActionCode = '*' OR v.ActionCode = c.ActionCode)
    WHERE v.AppCode = 'PCW'
      AND v.EmpCode IN (SELECT EmpCode FROM #PcwSample)
)
-- Lưu ý: ADMIN giờ là '*' nên các ô V/C/E/D/X khác của ADMIN sẽ hiện CHI_MOI – đây là thay đổi có chủ ý.
SELECT COALESCE(o.EmpCode, n.EmpCode)       AS EmpCode,
       (SELECT STRING_AGG(r.RoleCode, ',') FROM #PcwOldRole r WHERE r.EmpCode = COALESCE(o.EmpCode, n.EmpCode)) AS RoleCu,
       COALESCE(o.Feature, n.Feature)       AS Feature,
       COALESCE(o.ActionCode, n.ActionCode) AS ActionCode,
       CASE WHEN o.EmpCode IS NULL THEN 'CHI_MOI'
            WHEN n.EmpCode IS NULL THEN 'CHI_CU'
            ELSE 'OK' END                   AS TrangThai
FROM oldPerm o
FULL OUTER JOIN newPerm n
  ON n.EmpCode = o.EmpCode AND n.Feature = o.Feature AND n.ActionCode = o.ActionCode
ORDER BY TrangThai DESC, EmpCode, Feature, ActionCode;


-- ---------- 4.2 SPATROL ----------
IF OBJECT_ID('tempdb..#SpEmpRole') IS NOT NULL DROP TABLE #SpEmpRole;
IF OBJECT_ID('tempdb..#SpSample')  IS NOT NULL DROP TABLE #SpSample;
IF OBJECT_ID('tempdb..#SpEffRole') IS NOT NULL DROP TABLE #SpEffRole;

-- Role (đã tách) của từng NV trong HSE_EmpID
SELECT LTRIM(RTRIM(e.EmpID)) AS EmpCode, r.RoleCode
INTO #SpEmpRole
FROM dbo.HSE_EmpID e
CROSS APPLY (SELECT CAST('<a>' + REPLACE(REPLACE(REPLACE(ISNULL(e.Role, ''), '&', '&amp;'), '<', '&lt;'), ',', '</a><a>') + '</a>' AS XML) AS x) rx
CROSS APPLY rx.x.nodes('/a') rn(v)
CROSS APPLY (SELECT UPPER(LTRIM(RTRIM(rn.v.value('.', 'NVARCHAR(100)')))) AS RoleCode) r
WHERE LTRIM(RTRIM(ISNULL(e.EmpID, ''))) <> '' AND r.RoleCode <> '';

-- Mẫu: 3 NV có role super, 3 NV role thường, 3 NV Role trống
SELECT EmpCode
INTO #SpSample
FROM (
    SELECT k.EmpCode, ROW_NUMBER() OVER (PARTITION BY k.Kind ORDER BY k.EmpCode) AS rn
    FROM (
        SELECT LTRIM(RTRIM(e.EmpID)) AS EmpCode,
               CASE WHEN NOT EXISTS (SELECT 1 FROM #SpEmpRole x WHERE x.EmpCode = LTRIM(RTRIM(e.EmpID))) THEN 'EMPTY'
                    WHEN EXISTS (SELECT 1 FROM #SpEmpRole x
                                 JOIN dbo.SPatrol_Role r ON UPPER(LTRIM(RTRIM(r.RoleCode))) = x.RoleCode
                                 WHERE x.EmpCode = LTRIM(RTRIM(e.EmpID)) AND r.IsSuper = 1) THEN 'SUPER'
                    ELSE 'ROLE' END AS Kind
        FROM dbo.HSE_EmpID e
        WHERE LTRIM(RTRIM(ISNULL(e.EmpID, ''))) <> ''
    ) k
) x
WHERE rn <= 3;
-- INSERT INTO #SpSample (EmpCode) VALUES ('...');

-- Role hiệu lực cũ = USER + role hợp lệ
SELECT DISTINCT s.EmpCode, r.RoleCode
INTO #SpEffRole
FROM #SpSample s
CROSS APPLY (
    SELECT 'USER' AS RoleCode
    UNION
    SELECT x.RoleCode FROM #SpEmpRole x
    WHERE x.EmpCode = s.EmpCode
      AND EXISTS (SELECT 1 FROM dbo.SPatrol_Role sr WHERE UPPER(LTRIM(RTRIM(sr.RoleCode))) = x.RoleCode)
) r;

;WITH cat AS (
    SELECT g.Feature, a.ActionCode
    FROM (VALUES ('Patrol'), ('Audit'), ('QualityPatrol'), ('AssetUpdate'), ('FixedAsset')) g (Feature)
    CROSS JOIN (VALUES ('BEFORE'), ('AFTER'), ('RECHECK'), ('SUMMARY')) a (ActionCode)
),
sup AS (
    SELECT DISTINCT er.EmpCode
    FROM #SpEffRole er
    JOIN dbo.SPatrol_Role r ON UPPER(LTRIM(RTRIM(r.RoleCode))) = er.RoleCode AND r.IsSuper = 1
),
oldPerm AS (
    SELECT s.EmpCode, c.Feature, c.ActionCode FROM sup s CROSS JOIN cat c
    UNION
    SELECT er.EmpCode, n.Feature, ua.ActionCode
    FROM #SpEffRole er
    JOIN dbo.SPatrol_RolePermission p ON UPPER(LTRIM(RTRIM(p.RoleCode))) = er.RoleCode
    CROSS APPLY (SELECT CASE UPPER(LTRIM(RTRIM(p.GroupCode)))
        WHEN 'PATROL' THEN 'Patrol' WHEN 'AUDIT' THEN 'Audit' WHEN 'QUALITYPATROL' THEN 'QualityPatrol'
        WHEN 'ASSETUPDATE' THEN 'AssetUpdate' WHEN 'FIXEDASSET' THEN 'FixedAsset' END AS Feature) n
    CROSS APPLY (VALUES
        (CASE WHEN p.CanBefore  = 1 THEN 'BEFORE'  END),
        (CASE WHEN p.CanAfter   = 1 THEN 'AFTER'   END),
        (CASE WHEN p.CanRecheck = 1 THEN 'RECHECK' END),
        (CASE WHEN p.CanSummary = 1 THEN 'SUMMARY' END)
    ) ua (ActionCode)
    WHERE n.Feature IS NOT NULL
      AND ua.ActionCode IS NOT NULL
      AND er.EmpCode NOT IN (SELECT EmpCode FROM sup)
),
newPerm AS (
    SELECT DISTINCT v.EmpCode, c.Feature, c.ActionCode
    FROM dbo.vw_F2_ProductionControl_EmpPerm v
    JOIN cat c ON (v.Feature = '*' OR v.Feature = c.Feature)
              AND (v.ActionCode = '*' OR v.ActionCode = c.ActionCode)
    WHERE v.AppCode = 'SPATROL'
      AND v.EmpCode IN (SELECT EmpCode FROM #SpSample)
)
SELECT COALESCE(o.EmpCode, n.EmpCode)       AS EmpCode,
       (SELECT TOP 1 e.Role FROM dbo.HSE_EmpID e WHERE LTRIM(RTRIM(e.EmpID)) = COALESCE(o.EmpCode, n.EmpCode)) AS RoleCu_Cot,
       COALESCE(o.Feature, n.Feature)       AS Feature,
       COALESCE(o.ActionCode, n.ActionCode) AS ActionCode,
       CASE WHEN o.EmpCode IS NULL THEN 'CHI_MOI'
            WHEN n.EmpCode IS NULL THEN 'CHI_CU'
            ELSE 'OK' END                   AS TrangThai
FROM oldPerm o
FULL OUTER JOIN newPerm n
  ON n.EmpCode = o.EmpCode AND n.Feature = o.Feature AND n.ActionCode = o.ActionCode
ORDER BY TrangThai DESC, EmpCode, Feature, ActionCode;
-- CHI_CU mà NV không có dòng nào trong view -> thường do EmpCode không có trong DATASPC/ExtEmployee.