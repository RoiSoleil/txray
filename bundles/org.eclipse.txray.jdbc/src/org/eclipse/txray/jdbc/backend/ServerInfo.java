package org.eclipse.txray.jdbc.backend;

/** Description of the connection exposed by the debugger. */
public final class ServerInfo {

    public String bindingId;
    public String threadName;
    public String productName;
    public String productVersion;
    public String url;
    public String userName;
    public String catalog;
    public String schema;
    public boolean autoCommit;
    public int transactionIsolation;

}
