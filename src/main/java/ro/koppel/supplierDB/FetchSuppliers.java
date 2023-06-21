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
import java.net.URLEncoder;

import static java.nio.charset.StandardCharsets.UTF_8;
import static ro.koppel.supplierDB.HelperMethods.buildScrapingAntURL;

@Component
public class FetchSuppliers {

    private final Logger logger = LoggerFactory.getLogger(FetchSuppliers.class);

    @Autowired
    FetchSupplierDetails supplierFetcher;

    public void searchSuppliers(String searchTerm) {
        var found = false;
        var page = 0;
        do {
            page++;
            logger.atDebug().log("PAGE " + page);
//            String url = buildScrapingAntURL("https://www.globalsources.com/searchList/suppliers?keyWord=" + URLEncoder.encode(searchTerm, UTF_8) + "&pageNum"
//                    + "=" + page, "8e2b076fd0a742a4abbc1a52bec5a456");
            String url = buildScrapingAntURL("https://www.globalsources.com/searchList/suppliers?keyWord=" + searchTerm + "&pageNum"
                    + "=" + page, "8e2b076fd0a742a4abbc1a52bec5a456");
            logger.atDebug().log(url);
            found = retrievePage(url, searchTerm);
        } while (found);

    }

    private boolean retrievePage(String url, String searchTerm) {
        boolean found;
        try {
            Document document = Jsoup.connect(url).timeout(2 * 60 * 1000).get();
            Elements supplierElements = document.select(".mod-supp-info");
            found = supplierElements.size() > 0;
            // Extract the supplier names
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
            }
        } catch (IOException e) {
            logger.atWarn().log("While retrieving {} the exception {} occurred.", url, e);
            found = false;
        }
        return found;
    }

}
