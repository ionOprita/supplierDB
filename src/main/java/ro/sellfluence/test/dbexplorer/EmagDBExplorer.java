package ro.sellfluence.test.dbexplorer;

import org.jetbrains.annotations.NotNull;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.db.POInfo;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumnModel;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;

import static javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS;
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;

public class EmagDBExplorer {
    private static EmagMirrorDB emagMirrorDB;
    public static final int tableWidth = 1800;
    public static final int tableHeight = 200;
    private static final TextAreaRenderer textAreaRenderer = new TextAreaRenderer();

    public static void main(String[] args) throws SQLException, IOException {
        var arguments = new Arguments(args);
        String databaseAlias = arguments.getOption(databaseOptionName, defaultDatabase);
        emagMirrorDB = EmagMirrorDB.getEmagMirrorDB(databaseAlias);
        initGUI(databaseAlias);
    }

    private static final JTextField orderField = new JTextField(20);
    private static final JButton orderButton = new JButton("Get Order By Order ID");
    private static final JTextField customerField = new JTextField(20);
    private static final JButton customerButton = new JButton("Get Orders By Customer ID");
    private static final JTextField productField = new JTextField(20);
    private static final JButton productButton = new JButton("Get Product");
    private static final OrderTableModel orderTableModel = new OrderTableModel();
    private static final JTable orderTable = new JTable(orderTableModel);
    private static final CustomerTableModel customerTableModel = new CustomerTableModel();
    private static final JTable customerTable = new JTable(customerTableModel);
    private static final ProductInOrderTableModel pioTableModel = new ProductInOrderTableModel();
    private static final JTable productInOrderTable = new JTable(pioTableModel);

    private static void initGUI(String databaseAlias) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Emag DB Browser (%s)".formatted(databaseAlias));
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            var outerBox = Box.createVerticalBox();
            frame.setContentPane(outerBox);

            // Line 1: Input Field + Get Order Button
            var line1 = Box.createHorizontalBox();
            line1.add(orderField);
            line1.add(orderButton);
            orderButton.addActionListener(EmagDBExplorer::lookupByOrderId);
            outerBox.add(line1);

            // Line 2: Input Field + Get Customer Button
            var line2 = Box.createHorizontalBox();
            line2.add(customerField);
            //line2.add(customerSelection);
            line2.add(customerButton);
            customerButton.addActionListener(EmagDBExplorer::lookupByCustomerId);
            outerBox.add(line2);

            // Line 3: Input Field + Get Product Button
            var line3 = Box.createHorizontalBox();
            line3.add(productField);
            line3.add(productButton);
//            outerBox.add(line3);

            // Order table
            outerBox.add(setupAndReturnOrderTable());
            // Customer table
            outerBox.add(setupAndReturnCustomerTable());
            // Product table
            outerBox.add(setupAndReturnProductTable());

            var buttonBox = Box.createHorizontalBox();

            var histogramButton = new JButton("Show fetch histogram");
            buttonBox.add(histogramButton);
            histogramButton.addActionListener(EmagDBExplorer::showFetchHistogram);

            var gmvButton = new JButton("Show GMV table");
            buttonBox.add(gmvButton);
            gmvButton.addActionListener(EmagDBExplorer::showGMVTable);

            outerBox.add(buttonBox);

            frame.pack();
            frame.setVisible(true);
        });
    }

    private static @NotNull JScrollPane setupAndReturnCustomerTable() {
        // Customer ID, Name, Email, Phone, Billing Info, Shipping Info, Created, Modified.
        setColumnWidths(customerTable, 70, 180, 120, 100, 400, 500, 150, 150);
        // Apply a custom cell renderer for text wrapping
        customerTable.getColumnModel().getColumn(4).setCellRenderer(textAreaRenderer);  // Billing Info
        customerTable.getColumnModel().getColumn(5).setCellRenderer(textAreaRenderer);  // Shipping Info

        return encloseInScrollPane(customerTable);
    }

    private static @NotNull JScrollPane setupAndReturnProductTable() {
        // ID, PNK, Name, External ID, Quantity, Initial Quantity, Storno Quantity, Sale Price, Original Price
        setColumnWidths(productInOrderTable, 70, 70, 400, 70, 70, 70, 70, 100, 100);
        // Apply the custom cell renderer for text wrapping
        customerTable.getColumnModel().getColumn(2).setCellRenderer(textAreaRenderer);  // Name
        return encloseInScrollPane(productInOrderTable);
    }

    private static @NotNull JScrollPane setupAndReturnOrderTable() {
        // Add a mouse listener to the table
        orderTable.addMouseListener(getOrderIdMouseAdapter());
        orderTable.addMouseListener(updateProductTable());
        // Order ID, Vendor ID, Vendor Name, Customer ID, Status, Date, Created, Modified, Surrogate ID.
        setColumnWidths(orderTable, 100, 200, 150, 100, 50, 150, 150, 150, 50);
        return encloseInScrollPane(orderTable);
    }

    private static @NotNull MouseAdapter getOrderIdMouseAdapter() {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = orderTable.rowAtPoint(e.getPoint());
                int column = orderTable.columnAtPoint(e.getPoint());
                if (column == 0) { // Order ID column index
                    var orderId = (String) orderTable.getValueAt(row, column);
                    orderField.setText(orderId);
                }
            }
        };
    }

    private static @NotNull MouseAdapter updateProductTable() {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = orderTable.rowAtPoint(e.getPoint());
                var surrogateId =   orderTable.getValueAt(row, 8);
                if (surrogateId instanceof Long) updateProductTable((long)surrogateId);
            }
        };
    }

    private static void lookupByOrderId(ActionEvent actionEvent) {
        try {
            updateTables(emagMirrorDB.read(db -> OrderRecord.getOrdersById(db, orderField.getText())));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void lookupByCustomerId(ActionEvent actionEvent) {
        try {
            updateTables(emagMirrorDB.read(db -> OrderRecord.getOrdersByCustomerId(db, Integer.parseInt(customerField.getText()))));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    static JFrame histogramWindow = null;
    static HistogramPanel histogramPanel = null;
    static GMVTable gmvPanel = null;
    static JFrame gmvWindow;
    static POInfoTable poinfoPanel;
    static JFrame poinfoWindow;

    private static void showFetchHistogram(ActionEvent actionEvent) {
        try {
            var data = emagMirrorDB.readFetchHistogram();
            if (histogramPanel == null) {
                histogramPanel = new HistogramPanel(data);
            } else {
                histogramPanel.setData(data);
            }
            if (histogramWindow == null) {
                histogramWindow = new JFrame("Fetch log histogram");
                histogramWindow.getContentPane().add(histogramPanel);
            }
            histogramWindow.setVisible(true);
            histogramWindow.pack();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void showGMVTable(ActionEvent actionEvent) {
        try {
            var data = emagMirrorDB.getGMVTable();
            if (gmvPanel == null) {
                gmvPanel = new GMVTable();
            }
            gmvPanel.updateData(data);
            gmvPanel.setCellListener((product, yearMonth) -> {
                try {
                    var orders = emagMirrorDB.readProductInOrderByProductAndMonth(product.productCode(), yearMonth);
                    SwingUtilities.invokeLater(() -> showPOInfoTable(product.name(), yearMonth, orders));
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            if (gmvWindow == null) {
                gmvWindow = new JFrame("GMV Table");
                gmvWindow.getContentPane().add(gmvPanel);
            }
            gmvWindow.setVisible(true);
            gmvWindow.pack();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
    private static void showPOInfoTable(String productName, YearMonth month, List<POInfo> productOrders) {
        if (poinfoPanel == null) {
            poinfoPanel = new POInfoTable();
        }
        var title = "Orders with the product %s in the month %s".formatted(productName, month);
        poinfoPanel.updateTable(productOrders);
        if (poinfoWindow == null) {
            poinfoWindow = new JFrame(title);
            poinfoWindow.getContentPane().add(poinfoPanel);
        }
        poinfoWindow.setTitle(title);
        poinfoWindow.setVisible(true);
        poinfoWindow.invalidate();
        poinfoWindow.repaint();
        poinfoWindow.pack();
    }


    private static void updateTables(List<OrderRecord> orders) {
        orderTableModel.updateOrders(orders);
        var customerIds = orders.stream().map(OrderRecord::customerId).collect(Collectors.toSet());
        var customers = customerIds.stream().flatMap(customerId -> {
            try {
                return emagMirrorDB.read(db -> CustomerRecord.getCustomerById(db, customerId)).stream();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }).toList();
        customerTableModel.updateCustomers(customers);
        if (customerIds.size() == 1) {
            customerField.setText(customerIds.iterator().next().toString());
        }
    }

    /**
     * Helper to set preferred widths on a JTable.
     *
     * @param table on which to set column widths.
     * @param widths One item for each column.
     */
    public static void setColumnWidths(JTable table, int... widths) {
        TableColumnModel columnModel = table.getColumnModel();
        for (int i = 0; i < widths.length && i < columnModel.getColumnCount(); i++) {
            columnModel.getColumn(i).setPreferredWidth(widths[i]);
        }
    }

    /**
     * Helper to enclose a table in a scroll pane.
     *
     * @param table to be embedded in a scroll pane.
     * @return the scroll pane.
     */
    private static @NotNull JScrollPane encloseInScrollPane(final JTable table) {
        table.setFillsViewportHeight(true);  // Ensure headers are always visible
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(tableWidth, tableHeight));  // Set the preferred size for scrolling
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_ALWAYS);
        return scrollPane;
    }

    private static void updateProductTable(long surrogateId) {
        try {
            pioTableModel.updateTable(emagMirrorDB.read(db -> ProductInOrderRecord.getProductInOrderByOrder(db, surrogateId)));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}