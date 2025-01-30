package ro.sellfluence.test.dbexplorer;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

class CustomerTableModel extends AbstractTableModel {
    private final String[] columnNames = {"Customer ID", "Name", "Email", "Phone", "Billing Info", "Shipping Info", "Created", "Modified"};
    private List<CustomerRecord> customers = new ArrayList<>();

    public CustomerTableModel() {
    }

    @Override
    public int getRowCount() {
        return customers.size();
    }

    @Override
    public int getColumnCount() {
        return columnNames.length;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        CustomerRecord customer = customers.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> customer.id();
            case 1 -> customer.name();
            case 2 -> customer.email();
            case 3 -> customer.phone1();
            case 4 -> customer.billingInfo();
            case 5 -> customer.shippingInfo();
            case 6 -> customer.created();
            case 7 -> customer.modified();
            default -> null;
        };
    }

    @Override
    public String getColumnName(int columnIndex) {
        return columnNames[columnIndex];
    }

    public void updateCustomers(List<CustomerRecord> newCustomers) {
        this.customers = newCustomers;
        fireTableDataChanged();  // Notify the table that the data has changed
    }
}
