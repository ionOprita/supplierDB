package ro.koppel.supplierDB;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ExcelCreator {

    @Autowired
    SearchRepository searchRepository;

    private static final String[] headers = {
            "Name", "URL", "Verified"
            , "Year established", "Factory Size", "Production Lines"
            , "Min Employees", "Max Employees"
            , "Min QC Staff", "Max QC Staff"
            , "Min R&D Staff", "Max R&D Staff"
            , "Min Annual Sales", "Max Annual Sales"
    };

    public void createExcel(Path path) {
        // Create a new workbook
        try (Workbook workbook = new XSSFWorkbook()) {

            var searchTerms = searchRepository.findAll();
            for (Search searchTerm : searchTerms) {

                // Create a new sheet for this search term
                String sheetName = searchTerm.searchTerms;
                Sheet sheet = workbook.createSheet(sheetName);

                // Create the header row
                Row headerRow = sheet.createRow(0);

                // Fill in the header row with column names
                for (int i = 0; i < headers.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                }

                var suppliers = searchTerm.supplierList;

                var rowIndex = 0;
                for (Supplier supplier : suppliers) {
                    rowIndex++;
                    Row row = sheet.createRow(rowIndex + 1);
                    var columnIndex = 0;
                    var cell = row.createCell(columnIndex++);
                    cell.setCellValue(supplier.name);
                    cell = row.createCell(columnIndex++);
                    cell.setCellValue(supplier.link.toExternalForm());
                    cell = row.createCell(columnIndex++);
                    cell.setCellValue(supplier.verified);
                    cell = row.createCell(columnIndex++);
                    cell.setCellValue(supplier.yearEstablished);
                    cell = row.createCell(columnIndex++);
                    cell.setCellValue(supplier.factorySize);
                    cell = row.createCell(columnIndex++);
                    cell.setCellValue(supplier.productionLines);
                    cell = row.createCell(columnIndex++);
                    cell.setCellValue(supplier.minEmployees);
                    cell = row.createCell(columnIndex++);
                    cell.setCellValue(supplier.maxEmployees);
                    cell = row.createCell(columnIndex++);
                    cell.setCellValue(supplier.minQcStaff);
                    cell = row.createCell(columnIndex++);
                    cell.setCellValue(supplier.maxQcStaff);
                    cell = row.createCell(columnIndex++);
                    cell.setCellValue(supplier.minRnDStaff);
                    cell = row.createCell(columnIndex++);
                    cell.setCellValue(supplier.maxRnDStaff);
                    cell = row.createCell(columnIndex++);
                    cell.setCellValue(supplier.minAnnualSales);
                    cell = row.createCell(columnIndex);
                    cell.setCellValue(supplier.maxAnnualSales);
                }
            }

            // Save to a file
            try (OutputStream outputStream = Files.newOutputStream(path)) {
                workbook.write(outputStream);
                System.out.printf("Excel file %s created successfully.%n", path);
            }
        } catch (IOException e) {
            System.out.printf("Error creating Excel file %s: %s%n", path, e);
        }
    }
}
