package ro.koppel.supplierDB;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class FetchSuppliers {

    private final Logger logger = LoggerFactory.getLogger(FetchSuppliers.class);

    @Autowired
    FetchSupplierDetails supplierFetcher;

    @Autowired
    HelperMethods helper;

    public void searchSuppliers(String searchTerm) {
        var found = false;
        var page = 0;
        do {
            page++;
            logger.atDebug().log("PAGE " + page);
//            String url = buildScrapingAntURL("https://www.globalsources.com/searchList/suppliers?keyWord=" + URLEncoder.encode(searchTerm, UTF_8) + "&pageNum"
//                    + "=" + page, "8e2b076fd0a742a4abbc1a52bec5a456");
            String globalsourcesURL = "https://www.globalsources.com/searchList/suppliers?keyWord=" + searchTerm + "&pageNum"
                    + "=" + page +"&vbTypes=Manufacturer";
            logger.atDebug().log(globalsourcesURL);
            found = retrievePage(globalsourcesURL, searchTerm);
        } while (found);

    }

    private boolean retrievePage(String url, String searchTerm) {
        boolean found;
        Document document = null;
        try {
            var fetched = false;
            do {
                try {
                    String scrapingAntURL = helper.buildScrapingAntURL(url);
                    logger.atDebug().log(scrapingAntURL);
                    document = Jsoup.connect(scrapingAntURL).timeout(5 * 60 * 1000).get();
                    fetched = true;
                } catch (IOException e) {
                    HelperMethods.replaceProxy();
                }
            } while (!fetched);
            Elements supplierElements = document.select(".mod-supp-info");
            found = supplierElements.size() > 0;
            if (!found) {
                logger.atDebug().log(document.text());
            }
            // Extract the supplier names
            var artificialLimit = 3;
            for (Element element : supplierElements) {
                var aTags = element.children().stream().filter(x -> x.tag().getName().equals("a")).toList();
                if (aTags.size() == 0) {
                    logger.atWarn().log("No 'a' tags found");
                    found = false;
                }
                //System.out.println(aTags);
                var titleTags = aTags.stream().filter(x -> x.hasClass("tit")).toList();
                if (titleTags.size() == 0) {
                    logger.atWarn().log("No 'a' tags with class tit found");
                    found = false;
                }
                //System.out.println(titleTags);
                var link = "https:" + titleTags.get(0).attr("href");
                logger.atDebug().log("LINK = " + link);
                supplierFetcher.retrieveAndStoreSupplier(link, searchTerm);
                artificialLimit--;
                if (artificialLimit == 0) {
                    found = false;
                    break;
                }
            }
        } catch (IOException e) {
            logger.atWarn().log("While retrieving {} the exception {} occurred.", new Object[]{url, e.getMessage()});
            if (document != null) {
                logger.atDebug().log(document.text());
            }
            found = false;
        }
        return found;
    }

}
