package ro.sellfluence.test.dbexplorer;

import ro.sellfluence.db.EmagMirrorDB.POInfo;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

public class ProductInOrderTableModel extends AbstractTableModel {
    private final String[] columnNames = {
            "Product ID", "PNK", "Product Name", "External ID",
            "Quantity", "Initial Quantity", "Storno Quantity", "Sale Price", "Original Price"
    };
    private List<ProductInOrderRecord> pioList = new ArrayList<>();

    @Override
    public int getRowCount() {
        return pioList.size();
    }

    @Override
    public int getColumnCount() {
        return columnNames.length;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (rowIndex < 0 || rowIndex >= pioList.size()) {
            return null;
        }
        ProductInOrderRecord poInfo = pioList.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> poInfo.id();
            case 1 -> poInfo.pnk();
            case 2 -> poInfo.name();
            case 3 -> poInfo.externalId();
            case 4 -> poInfo.quantity();
            case 5 -> poInfo.initialQuantity();
            case 6 -> poInfo.stornoQuantity();
            case 7 -> poInfo.salePrice();
            case 8 -> poInfo.originalPrice();
            default -> null;
        };
    }

    @Override
    public String getColumnName(int columnIndex) {
        return columnNames[columnIndex];
    }

    public void updateTable(List<ProductInOrderRecord> products) {
       this.pioList = products;
       fireTableDataChanged();
    }
}