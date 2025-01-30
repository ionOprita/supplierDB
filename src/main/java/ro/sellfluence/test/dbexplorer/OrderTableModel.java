package ro.sellfluence.test.dbexplorer;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

class OrderTableModel extends AbstractTableModel {
    private final String[] columnNames = {"Order ID", "Vendor ID", "Vendor Name", "Customer ID", "Status", "Date", "Created", "Modified"};
    private List<OrderRecord> orders = new ArrayList<>();

    @Override
    public int getRowCount() {
        return orders.size();
    }

    @Override
    public int getColumnCount() {
        return columnNames.length;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        OrderRecord order = orders.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> order.orderId();
            case 1 -> order.vendorId();
            case 2 -> order.vendorName();
            case 3 -> order.customerId();
            case 4 -> order.status();
            case 5 -> order.date();
            case 6 -> order.created();
            case 7 -> order.modified();
            default -> null;
        };
    }

    @Override
    public String getColumnName(int columnIndex) {
        return columnNames[columnIndex];
    }

    public void updateOrders(List<OrderRecord> newOrders) {
        this.orders = newOrders;
        fireTableDataChanged();  // Notify the table that the data has changed
    }

}
