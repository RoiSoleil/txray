package org.eclipse.txray.jdbc.backend;

import java.util.List;

/** SQL to run on the exposed connection. {@code params == null} means a plain Statement. */
public final class ExecuteRequest {

    public String sql;
    public int maxRows;
    public List<Param> params;

}
