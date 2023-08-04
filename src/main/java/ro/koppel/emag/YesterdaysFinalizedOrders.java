package ro.koppel.emag;

import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.ExtendedValue;
import com.google.api.services.sheets.v4.model.RowData;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.logging.Level.*;

public class YesterdaysFinalizedOrders {

    private static final String emagRO = "https://marketplace-api.emag.ro/api-3";

    private static final String spreadsheetId = "1fN3hjTHiwnDTsem0_o99bdt_SUyw-9s3hYgBI4it-rY";

    private static final String orderURI = emagRO + "/order";

    private static final String readOrder = orderURI + "/read";

    private static final DateTimeFormatter emagFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Logger logger = Logger.getLogger(YesterdaysFinalizedOrders.class.getName());

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Please supply username and password as arguments.");
            System.exit(1);
        }

        List<String> argsList = new ArrayList<>();
        argsList.addAll(Arrays.asList(args));
        List<List<String>> usernameAndPassList = new ArrayList<>();
// 4
        for (int i = 0; i < argsList.size(); i = i + 2) {
            usernameAndPassList.add(Arrays.asList(argsList.get(i), argsList.get(i + 1)));
        }

        for (List<String> username : usernameAndPassList) {
            System.out.println("Username is: " + username.get(0));
            run(username.get(0), username.get(1));
        }
    }

    private static void run(String username, String password) throws IOException, InterruptedException, GeneralSecurityException {
        var accumulatedResponses = new ArrayList<OrderResult>();
        var startTime = LocalDate.now().minusDays(1).atStartOfDay();
        var endTime = LocalDate.now().atStartOfDay();
        var credentials = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        var httpClient = HttpClient.newHttpClient();
        var page=0;
        var finished=false;
        while (!finished) {
            System.out.println("Requesting page "+page);
            page++;
            var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(readOrder + "?date=2023-03-10"))
                    .header("Authorization", "Basic " + credentials)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(
                            HttpRequest.BodyPublishers.ofString(
                                    "currentPage=%d&status=4&modifiedAfter=%s&modifiedAfter=%s"
                                            .formatted(
                                                    page,
                                                    URLEncoder.encode(startTime.format(emagFormat), UTF_8),
                                                    URLEncoder.encode(endTime.format(emagFormat), UTF_8)
                                            )
                            )
                    ).build();
            var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int statusCode = httpResponse.statusCode();
            logger.log(FINE, "Status code = " + statusCode);
            if (statusCode == HTTP_OK) {
                String receivedJSON = httpResponse.body();
                logger.log(FINE, () -> "Full response body: %s".formatted(receivedJSON));
                try {
                    Response response = new Gson().fromJson(receivedJSON, Response.class);
                    if (response.isError) {
                        logger.log(SEVERE, "Received error response %s".formatted(Arrays.toString(response.messages)));
                    } else {
                        logger.log(INFO, "Decoded JSON: " + response);
                        System.out.printf("Received %d results%n",response.results.length);
                        if (response.results.length>0) {
                            accumulatedResponses.addAll(Arrays.asList(response.results));
                        } else {
                            finished=true;
                        }
                    }
                } catch (JsonSyntaxException e) {
                    logger.log(SEVERE, "JSON decoded ended with error %s".formatted(e.getMessage()));
                    logger.log(INFO, receivedJSON);
                }
            } else {
                logger.log(SEVERE, "Received error status %s".formatted(statusCode));
            }
        }
        if (!accumulatedResponses.isEmpty()) processResponse(accumulatedResponses);
    }

    private static void processResponse(List<OrderResult> results) throws GeneralSecurityException, IOException {
        var splittedResults = splitOutDuplicates(results);
        if (!splittedResults.clean.isEmpty()) {
            SheetsQuickstart.append(spreadsheetId, linearizeOrderProductList(splittedResults.clean, false));
        }
        if (!splittedResults.duplicates.isEmpty()) {
            SheetsQuickstart.insertAtTop(spreadsheetId, linearizeOrderProductList(splittedResults.duplicates, true));
        }
    }

    /**
     * Convert the list of order where each order can have multiple products to single list having an entry
     * for each product in each order. The list items are already converted into a row for the spreadsheet,
     * thus the resulting list elements are of type List-of-Object.
     *
     * @param orderResults
     * @return
     */
    private static List<RowData> linearizeOrderProductList(List<OrderResult> orderResults, boolean duplicate) {
        return orderResults.stream()
                .flatMap(orderResult -> Arrays.stream(orderResult.products).map(product -> createSheetRow(orderResult, product, duplicate))
                )
                .toList();
    }

    private static SplittedResult splitOutDuplicates(List<OrderResult> results) {
        var duplicateOrders = new ArrayList<OrderResult>();
        var singleOrders = new ArrayList<OrderResult>();
        var ordersGroupedById = new TreeMap<String, List<OrderResult>>();
        for (var result : results) {
            var orderId = result.id;
            var list = ordersGroupedById.getOrDefault(orderId, new ArrayList<>());
            list.add(result);
            ordersGroupedById.put(orderId, list);
        }
        for (var orders : ordersGroupedById.values()) {
            if (orders.size() == 1) {
                singleOrders.add(orders.get(0));
            } else {
                duplicateOrders.addAll(orders);
            }
        }
        return new SplittedResult(duplicateOrders, singleOrders);
    }

    private static RowData createSheetRow(OrderResult orderResult, Product product, boolean duplicate) {
        List<Object> row = new ArrayList<>();
        row.add(dayConversion(orderResult.date));
        row.add(dayAndHourConversion(orderResult.date));
        row.add(monthConversion(orderResult.date));
        row.add(weekConversion(orderResult.date));
        row.add(orderResult.id);
        row.add(orderResult.customer.name);
        addModel(product, row);
        row.add(product.part_number_key);
        row.add("");
        row.add("");
        row.add("");
        if (duplicate) {
            row.add("Comanda dubla");
        } else {
            row.add("");
        }
        row.add("");
        row.add("");
        addCompany(product, row);
        row.add(orderResult.products.length);
        row.add("");
        row.add(true);
        row.add(true);
        row.add(true);
        row.add(false);
        row.add(orderResult.customer.shipping_phone);
        row.add("FBE");
        return new RowData().setValues(row.stream().map(value -> toCellData(value)).toList());
    }

    private static CellData toCellData(Object value) {
        if (value instanceof Number n) {
            return new CellData().setUserEnteredValue(new ExtendedValue().setNumberValue(n.doubleValue()));
        } else if (value instanceof Boolean b) {
            return new CellData().setUserEnteredValue(new ExtendedValue().setBoolValue(b));
        } else {
            return new CellData().setUserEnteredValue(new ExtendedValue().setStringValue(value.toString()));
        }
    }

    private static String productNameOf(Product product) {
        return switch (product.part_number_key) {
            case "D9LYDSBBM" -> "01/ 1+1 alb";
            case "DG20C1BBM" -> "02/ 1+1 negru";
            case "DRM7KSBBM" -> "03/ 1+2 alb";
            // etc.
            default -> "Product %s unknown.".formatted(product.part_number_key);
        };
    }

    //TODO: Instead of having methods that add to the row directly it would
    // be nicer to have just a conversion procedure, and also do it with a switch,
    // something like
    // Then in the processResponse method you can do a
    // row.add(productNameOf(product))
    // This way you separate the functionality of converting name from
    // the usage, which is now row.add, but maybe in other context it is different.

    private static void addModel(Product product, List<Object> row) {
        if ("D9LYDSBBM".equals(product.part_number_key)) {
            row.add("01/ 1+1 alb");
        } else if ("DG20C1BBM".equals(product.part_number_key)) {
            row.add("02/ 1+1 negru");
        } else if ("DRM7KSBBM".equals(product.part_number_key)) {
            row.add("03/ 1+2 alb");
        } else if ("D820C1BBM".equals(product.part_number_key)) {
            row.add("04/ 1+2 negru");
        } else if ("D1PCWXMBM".equals(product.part_number_key)) {
            row.add("05/ 1+1 alb kinetic");
        } else if ("D145CKMBM".equals(product.part_number_key)) {
            row.add("06/ 1+1 negru kinetic");
        } else if ("D5PCWXMBM".equals(product.part_number_key)) {
            row.add("07/ 1+2 alb kinetic");
        } else if ("D4Q09PMBM".equals(product.part_number_key)) {
            row.add("08/ 1+2 negru kinetic");
        } else if ("D72Z2RMBM".equals(product.part_number_key)) {
            row.add("09/ 1+1 RING negru");
        } else if ("DD7NTWMBM".equals(product.part_number_key)) {
            row.add("10/ 1+2 RING alb");
        } else if ("D77NTWMBM".equals(product.part_number_key)) {
            row.add("11/ 1+2 RING negru");
        } else if ("DZC42FMBM".equals(product.part_number_key)) {
            row.add("30/ Casti 360 Alb");
        } else if ("DLC42FMBM".equals(product.part_number_key)) {
            row.add("31/ Casti 360 Negru");
        } else if ("DVZ949MBM".equals(product.part_number_key)) {
            row.add("32/ Casti Bass Alb");
        } else if ("D30ZXJMBM".equals(product.part_number_key)) {
            row.add("33/ Casti Bass Gri");
        } else if ("D4Z949MBM".equals(product.part_number_key)) {
            row.add("34/ Casti Bass Negru");
        } else if ("D15VDXMBM".equals(product.part_number_key)) {
            row.add("35/ Casti BOLT");
        } else if ("DSZ949MBM".equals(product.part_number_key)) {
            row.add("36/ Casti DISC Alb");
        } else if ("DCZ949MBM".equals(product.part_number_key)) {
            row.add("37/ Casti DISC Negru");
        } else if ("DWPTVHMBM".equals(product.part_number_key)) {
            row.add("38/ Casti gaming Sharp");
        } else if ("D9555PMBM".equals(product.part_number_key)) {
            row.add("39/ Casti gaming V10 on ear");
        } else if ("D6J9QTMBM".equals(product.part_number_key)) {
            row.add("40/ Casti Neo albe");
        } else if ("D8J9QTMBM".equals(product.part_number_key)) {
            row.add("41/ Casti Neo negre");
        } else if ("DK8D0ZMBM".equals(product.part_number_key)) {
            row.add("42/ Casti Neo Plus negre");
        } else if ("DWS1VQMBM".equals(product.part_number_key)) {
            row.add("43/ Casti KLAR alb");
        } else if ("DDG8NWMBM".equals(product.part_number_key)) {
            row.add("44/ Casti Neo Plus alb");
//ToDO: castile Sharp pro
//                } else if("".equals(product.part_number_key)){
//                    row.add("45/ Casti SHARP pro");
        } else if ("DY3GLXMBM".equals(product.part_number_key)) {
            row.add("64/ Husa 8/3''");
        } else if ("DGZ397MBM".equals(product.part_number_key)) {
            row.add("65/ Husa 10/2''");
        } else if ("D5Z397MBM".equals(product.part_number_key)) {
            row.add("66/ Husa 10/5''");
        } else if ("DG8YYTMBM".equals(product.part_number_key)) {
            row.add("67/ Husa 10/9''");
        } else if ("DKM3TRMBM".equals(product.part_number_key)) {
            row.add("68/ Husa 10/9'' 10th gen.");
        } else if ("D78D97MBM".equals(product.part_number_key)) {
            row.add("69/ Husa 11''");
        } else if ("DW8YYTMBM".equals(product.part_number_key)) {
            row.add("70/ Husa 12/9''");
        } else if ("DZXJC5MBM".equals(product.part_number_key)) {
            row.add("71/ Tastatura wireless");
        } else if ("D599WFMBM".equals(product.part_number_key)) {
            row.add("90/ Capete Irigator H2O");
        } else if ("DFD0SSMBM".equals(product.part_number_key)) {
            row.add("91/ Capete Irigator Floss");
        } else if ("DL43KPMBM".equals(product.part_number_key)) {
            row.add("92/ Irigator Den To Go");
        } else if ("DV43KPMBM".equals(product.part_number_key)) {
            row.add("93/ Irigator Dental Alb");
        } else if ("D143KPMBM".equals(product.part_number_key)) {
            row.add("94/ Irigator Dental Negru");
        } else if ("DQ43KPMBM".equals(product.part_number_key)) {
            row.add("95/ Irigator Floss Alb");
        } else if ("D784ZSMBM".equals(product.part_number_key)) {
            row.add("96/ Irigator Floss Negru");
        } else if ("DKFMYZMBM".equals(product.part_number_key)) {
            row.add("97/ Irigator AquaPulse");
        } else if ("DP2DL5MBM".equals(product.part_number_key)) {
            row.add("98/ Capete Irigator Set 6");
        } else if ("DGJF2DMBM".equals(product.part_number_key)) {
            row.add("116/ Masina de Bani");
        } else if ("DDL4PPMBM".equals(product.part_number_key)) {
            row.add("117/ Masina ValueCounter");
        } else if ("DXW4DMYBM".equals(product.part_number_key)) {
            row.add("118/ Masina Value Touchscreen");
        } else if ("DGNK2XMBM".equals(product.part_number_key)) {
            row.add("138/ Perie Trimmer L");
        } else if ("D8NK2XMBM".equals(product.part_number_key)) {
            row.add("139/ Perie Trimmer M");
        } else if ("DWNK2XMBM".equals(product.part_number_key)) {
            row.add("140/ Pet Trimmer - Blade");
        } else if ("D14MYPMBM".equals(product.part_number_key)) {
            row.add("141/ Pet Trimmer Mediu");
        } else if ("DM243SMBM".equals(product.part_number_key)) {
            row.add("142/ Pet Trimmer 2 Viteze");
        } else if ("DC4MYPMBM".equals(product.part_number_key)) {
            row.add("143/ Pet Trimmer Pro");
//ToDO: pet trimmer 1 viteza
//                } else if("".equals(product.part_number_key)) {
//                    row.add("144/ Pet Trimmer 1 Viteza");
        } else if ("D7T819MBM".equals(product.part_number_key)) {
            row.add("164/ Periuta - Adult 4 capete");
        } else if ("DTGN9PMBM".equals(product.part_number_key)) {
            row.add("165/ Periuta - Adult Alb");
        } else if ("DDGN9PMBM".equals(product.part_number_key)) {
            row.add("166/ Periuta - Adult Roz");
        } else if ("DB2NQ7MBM".equals(product.part_number_key)) {
            row.add("187/ Pulsoximetru Alb");
        } else if ("DP4DWDMBM".equals(product.part_number_key)) {
            row.add("188/ Pulsoximetru Albastru");
        } else if ("DQDMKDMBM".equals(product.part_number_key)) {
            row.add("189/ Pulsoximetru Negru");
        } else if ("DNH8N3MBM".equals(product.part_number_key)) {
            row.add("210/ Stylus Alb Pro");
        } else if ("D0H8N3MBM".equals(product.part_number_key)) {
            row.add("211/ Stylus Negru Pro");
        } else if ("DBH3NDMBM".equals(product.part_number_key)) {
            row.add("212/ Stylus Alb Universal");
        } else if ("DV26HHMBM".equals(product.part_number_key)) {
            row.add("213/ Stylus Negru Universal");
        } else if ("DD1K8JMBM".equals(product.part_number_key)) {
            row.add("214/ Stylus Alb Univ. Cupru");
        } else if ("DDNX54MBM".equals(product.part_number_key)) {
            row.add("215/ Stylus Negru Univ. Cupru");
        } else if ("DYF0MHMBM".equals(product.part_number_key)) {
            row.add("236/ Z. Antilatrat Basic");
        } else if ("DR5RM0MBM".equals(product.part_number_key)) {
            row.add("237/ Z. Antilatrat Pro");
        } else if ("DY4ZJMMBM".equals(product.part_number_key)) {
            row.add("238/ Z. Dubla Nailon");
        } else if ("DY4SS3MBM".equals(product.part_number_key)) {
            row.add("239/ Z. Dubla Piele");
        } else if ("DW0QSBMBM".equals(product.part_number_key)) {
            row.add("240/ Z. Dubla Silicon");
        } else if ("DM4ZJMMBM".equals(product.part_number_key)) {
            row.add("241/ Zgarda Neagra");
        } else if ("DSQFMWBBM".equals(product.part_number_key)) {
            row.add("242/ Zgarda Orange");
        } else if ("DRSLDNMBM".equals(product.part_number_key)) {
            row.add("243/ Zgarda Patrat");
        } else if ("DB4SS3MBM".equals(product.part_number_key)) {
            row.add("244/ Zgarda Piele");
        } else if ("DCSZSLBBM".equals(product.part_number_key)) {
            row.add("245/ Zgarda Silicon");
        } else if ("DKXPJLMBM".equals(product.part_number_key)) {
            row.add("266/ smartCALL Negru");
        } else if ("DS0LLLMBM".equals(product.part_number_key)) {
            row.add("267/ smartCALL Roz");
        } else if ("D3QL6KMBM".equals(product.part_number_key)) {
            row.add("310/ Pensule");
        } else if ("DTSN3PMBM".equals(product.part_number_key)) {
            row.add("311/ Buretei Flawless");
        } else if ("D7XJ2SMBM".equals(product.part_number_key)) {
            row.add("312/ Pensule Complete");
        } else if ("D8Y4CLMBM".equals(product.part_number_key)) {
            row.add("313/ Suport Silicon Paww");
        } else if ("DC6M3VMBM".equals(product.part_number_key)) {
            row.add("314/ Buretei Flawless Bear");
        } else if ("D3HG3PMBM".equals(product.part_number_key)) {
            row.add("332/ Trusa manichiura neagra");
        } else if ("D2HG3PMBM".equals(product.part_number_key)) {
            row.add("333/ Trusa manichiura roz");
        } else if ("DL6M3VMBM".equals(product.part_number_key)) {
            row.add("354/ Ondulator 9mm");
        } else if ("D3T24SMBM".equals(product.part_number_key)) {
            row.add("376/ Bentita");
        } else if ("D5KZDTMBM".equals(product.part_number_key)) {
            row.add("398/ Judios Icon");
        } else if ("DPV2PTMBM".equals(product.part_number_key)) {
            row.add("399/ Judios Icon Negru");
        } else if ("D595SXMBM".equals(product.part_number_key)) {
            row.add("400/ Judios Echo");
        } else if ("DP5WFLMBM".equals(product.part_number_key)) {
            row.add("401/ Judios Luna");
        } else if ("DDPXMFMBM".equals(product.part_number_key)) {
            row.add("402/ Judios Mira");
        } else if ("DH4R2ZMBM".equals(product.part_number_key)) {
            row.add("403/ Judios 10X");
        } else if ("DS1XM8MBM".equals(product.part_number_key)) {
            row.add("404/ Judios Aura");
        } else if ("DVZZ9MYBM".equals(product.part_number_key)) {
            row.add("405/ Judios Aria");
        } else if ("D87MM7MBM".equals(product.part_number_key)) {
            row.add("439/ Premium negru");
        } else if ("DH63Q0MBM".equals(product.part_number_key)) {
            row.add("440/ Premium alb");
        } else if ("DNC50KMBM".equals(product.part_number_key)) {
            row.add("441/ Ice-cool negru");
        } else if ("DLWLMHMBM".equals(product.part_number_key)) {
            row.add("442/ Ice-cool alb");
        } else if ("DTGMCSMBM".equals(product.part_number_key)) {
            row.add("459/ Trimmer 1.0");
        } else if ("D7GMCSMBM".equals(product.part_number_key)) {
            row.add("460/ Double-cutter");
        } else if ("DDHSVQMBM".equals(product.part_number_key)) {
            row.add("461/ Trimmer 2.0");
        } else if ("DTHSVQMBM".equals(product.part_number_key)) {
            row.add("462/ Nose Trimmer");
        } else if ("DDGMCSMBM".equals(product.part_number_key)) {
            row.add("479/ Shaver");
        } else if ("DCFZVQMBM".equals(product.part_number_key)) {
            row.add("499/ All-in-One");
        }
//ToDo: ammo negru
//                } else if("".equals(product.part_number_key)) {
//                    row.add("539/ AMMO negru");
    }

    private static void addCompany(Product product, List<Object> row) {
        if ("D9LYDSBBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DG20C1BBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DRM7KSBBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D820C1BBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D1PCWXMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D145CKMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D5PCWXMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D4Q09PMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D72Z2RMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DD7NTWMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D77NTWMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DZC42FMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DLC42FMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DVZ949MBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("D30ZXJMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("D4Z949MBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("D15VDXMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DSZ949MBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DCZ949MBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DWPTVHMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("D9555PMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("D6J9QTMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("D8J9QTMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DK8D0ZMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DWS1VQMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DDG8NWMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DY3GLXMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DGZ397MBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D5Z397MBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DG8YYTMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DKM3TRMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D78D97MBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DW8YYTMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DZXJC5MBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D599WFMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DFD0SSMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DL43KPMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DV43KPMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D143KPMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DQ43KPMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D784ZSMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DKFMYZMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DP2DL5MBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DGJF2DMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DDL4PPMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DXW4DMYBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DGNK2XMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D8NK2XMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DWNK2XMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D14MYPMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DM243SMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DC4MYPMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("D7T819MBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DTGN9PMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DDGN9PMBM".equals(product.part_number_key)) {
            row.add("Zoopie Solutions");
        } else if ("DB2NQ7MBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("DP4DWDMBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("DQDMKDMBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("DNH8N3MBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("D0H8N3MBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DBH3NDMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DV26HHMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DD1K8JMBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DDNX54MBM".equals(product.part_number_key)) {
            row.add("Zoopie Invest");
        } else if ("DYF0MHMBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("DR5RM0MBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("DY4ZJMMBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("DY4SS3MBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("DW0QSBMBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("DM4ZJMMBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("DSQFMWBBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("DRSLDNMBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("DB4SS3MBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("DCSZSLBBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("DKXPJLMBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("DS0LLLMBM".equals(product.part_number_key)) {
            row.add("Zoopie Concept");
        } else if ("D3QL6KMBM".equals(product.part_number_key)) {
            row.add("310/ Pensule");
        } else if ("DTSN3PMBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("D7XJ2SMBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("D8Y4CLMBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("DC6M3VMBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("D3HG3PMBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("D2HG3PMBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("DL6M3VMBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("D3T24SMBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("D5KZDTMBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("DPV2PTMBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("D595SXMBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("DP5WFLMBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("DDPXMFMBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("DH4R2ZMBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("DS1XM8MBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("DVZZ9MYBM".equals(product.part_number_key)) {
            row.add("Judios Concept");
        } else if ("D87MM7MBM".equals(product.part_number_key)) {
            row.add("Sellfluence");
        } else if ("DH63Q0MBM".equals(product.part_number_key)) {
            row.add("Sellfluence");
        } else if ("DNC50KMBM".equals(product.part_number_key)) {
            row.add("Sellfluence");
        } else if ("DLWLMHMBM".equals(product.part_number_key)) {
            row.add("Sellfluence");
        } else if ("DTGMCSMBM".equals(product.part_number_key)) {
            row.add("Sellfluence");
        } else if ("D7GMCSMBM".equals(product.part_number_key)) {
            row.add("Sellfluence");
        } else if ("DDHSVQMBM".equals(product.part_number_key)) {
            row.add("Sellfluence");
        } else if ("DTHSVQMBM".equals(product.part_number_key)) {
            row.add("Sellfluence");
        } else if ("DDGMCSMBM".equals(product.part_number_key)) {
            row.add("Sellfluence");
        } else if ("DCFZVQMBM".equals(product.part_number_key)) {
            row.add("Sellfluence");
        }
    }

    private static String dayAndHourConversion(String date) {
        DateTimeFormatter emag;
        DateTimeFormatter excel;
        emag = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        excel = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
        return LocalDateTime.parse(date, emag).format(excel);
    }

    private static String dayConversion(String date) {
        DateTimeFormatter emag;
        DateTimeFormatter excel;
        emag = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        excel = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        return LocalDateTime.parse(date, emag).format(excel);
    }

    private static String monthConversion(String date) {
        DateTimeFormatter emag;
        DateTimeFormatter excel;
        emag = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        excel = DateTimeFormatter.ofPattern("01.MM.yyyy HH:mm:ss");
        return LocalDateTime.parse(date, emag).format(excel);
    }

    private static String weekConversion(String date) {
        DateTimeFormatter emag;
        DateTimeFormatter excel;
        emag = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        excel = DateTimeFormatter.ofPattern("ww.MM.yyyy HH:mm:ss");
        return LocalDateTime.parse(date, emag).format(excel);
    }

    private static record SplittedResult(List<OrderResult> duplicates, List<OrderResult> clean) {
    }
}
