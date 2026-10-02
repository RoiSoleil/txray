package org.eclipse.txray.jdbc.backend;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of an execution: either rows (columns + string cells, binary as hex) or an update count.
 */
public final class ExecuteResult {

    public static final byte ROWS = 1;
    public static final byte UPDATE_COUNT = 2;

    public byte kind;
    public long updateCount = -1;
    public List<ColumnMeta> columns = new ArrayList<>();
    public List<String[]> rows = new ArrayList<>();
    /** true when maxRows stopped the read before the end of the cursor. */
    public boolean truncated;
    /** Duration of the execution inside the debugged VM, in milliseconds. */
    public long elapsedMillis;

}
