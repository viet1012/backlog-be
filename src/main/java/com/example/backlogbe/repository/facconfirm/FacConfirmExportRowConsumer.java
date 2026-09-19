package com.example.backlogbe.repository.facconfirm;

import java.sql.ResultSet;
import java.sql.SQLException;

@FunctionalInterface
public interface FacConfirmExportRowConsumer {

	void accept(ResultSet rs)
			throws SQLException;
}
