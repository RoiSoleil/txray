package org.eclipse.txray.dbeaver;

import org.jkiss.dbeaver.ext.generic.GenericDataSourceProviderBasic;

/**
 * DBeaver data source provider of TxRay. DBeaver loads the classes of a driver with the class
 * loader of its provider: declared in this bundle, the provider makes DBeaver use the TxRay driver
 * of this Eclipse (no jar to declare), which talks directly to the debugger.
 */
public class TxRayDataSourceProvider extends GenericDataSourceProviderBasic {
}
