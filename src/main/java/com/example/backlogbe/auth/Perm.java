package com.example.backlogbe.auth;

// =========================================================
// Feature / Action của app PCW trong F2_ProductionControl_RolePerm
// (phải trùng giá trị trong DB; khác hoa thường vẫn khớp).
// =========================================================
public final class Perm {

	public static final String APP_CODE = "PCW";

	// ---- Feature ----
	public static final String BACKLOG = "BACKLOG";                 // Backlog, Remain PO, Sale follow
	public static final String FAC_CONFIRM = "FAC_CONFIRM";
	public static final String ODBF = "ODBF";
	public static final String SHIPMENT = "SHIPMENT";               // Shipment, Packing list
	public static final String COST_MONITORING = "COST_MONITORING";
	public static final String MACHINE_LOCATOR = "MACHINE_LOCATOR";
	public static final String ASSISTANT_LOG = "ASSISTANT_LOG";
	public static final String GENERAL = "GENERAL";                 // SPH, Asakai, PC training, Sales status, Management, Machine Monitor

	// ---- Action ----
	public static final String VIEW = "V";
	public static final String CREATE = "C";
	public static final String EDIT = "E";
	public static final String DELETE = "D";
	public static final String EXPORT_IMPORT = "X";

	private Perm() {
	}
}
